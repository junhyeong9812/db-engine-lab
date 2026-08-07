# impl/02-02 — BufferPool

> **종류**: 세션형
> **상위 단계**: `docs/stages/02-page-buffer.md`
> **코드 정본**: git `5505edc` — "complete: 21 stages + 12 보강 (120/120 tests)"
> **이 세션의 범위**: page를 메모리에 붙잡아두는 캐시. 자리가 없으면 무엇을 내보낼지(LRU), 내보내도 되는지(pin), 내보내기 전에 써야 하는지(dirty)를 결정한다.
> **작성 파일**:
> - 신규: `src/main/kotlin/com/dbenginelab/storage/BufferPool.kt`
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/storage/BufferPoolTest.kt`
> - (`StorageError`의 `PageNotFound`·`PageNotInPool`·`AllPagesPinned`를 쓴다 — 파일은 01-01에서 이미 최종형으로 쳤다)
> **검증**: `BufferPoolTest` 4 PASSED
> **예상 타이핑 시간**: 40분

---

## 0. 참조

- 주 참조: SimpleDB `BufferPool.getPage` / `evictPage`.
- 대조 참조: BusTub `buffer_pool_manager_instance.cpp` + `lru_replacer.cpp`.
- **차이 채택 여부**: BusTub은 replacer를 별도 클래스로 분리하고 LRU-K를 쓴다. **채택 안 함** — 우리는 `LinkedHashMap(access-order=true)` 하나로 LRU를 얻는다. 교체 정책을 갈아끼울 일이 생기면 그때 분리한다.

## 1. 만족시킬 invariant

- **CI-1**: 같은 `PageId`로 fetch하면 같은 객체가 온다 (캐시 일관성).
- **CI-2**: dirty page를 evict할 때 반드시 먼저 디스크에 쓴다.
- **CI-3**: pinned page는 절대 evict되지 않는다.

## 2. 의존성

- 이전 세션: `impl/02-01-page-pagedfile.md` (`Page`, `PageId`, `PagedFile`)
- `StorageError` (01-01)

## 3. 문제 정의 (TDD step 1)

02-01의 `PagedFile.readPage`는 부를 때마다 디스크를 친다. 같은 page를 100번 읽으면 디스크 IO 100번이다. B+tree의 root page는 모든 탐색이 거쳐 가는데, 그때마다 디스크에서 읽는다면 인덱스를 만든 의미가 없다.

그래서 캐시가 필요하다. 그런데 캐시를 두는 순간 세 가지 결정이 생긴다:

1. **무엇을 내보낼 것인가** — 자리가 꽉 찼을 때. 가장 오래 안 쓴 것(LRU)을 고른다.
2. **내보내도 되는가** — 누군가 그 page를 지금 쓰고 있으면 안 된다. 그래서 `pinCount`. 쓰는 동안 pin, 다 쓰면 unpin.
3. **내보내기 전에 써야 하는가** — 메모리에서만 바뀐 내용이 있으면(dirty) 디스크에 먼저 써야 한다. 안 그러면 **조용히 데이터가 사라진다.**

3번이 가장 위험하다. 잊어도 테스트가 통과할 수 있고, 나중에 reopen했을 때야 드러나기 때문이다. CI-2가 그걸 못 박는다.

## 4. 실패 테스트 (TDD step 2)

```kotlin
// src/test/kotlin/com/dbenginelab/storage/BufferPoolTest.kt @ 5505edc
package com.dbenginelab.storage

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BufferPoolTest {

    @Test
    fun `newPage 후 같은 id로 fetch하면 같은 페이지`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("bp.db").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 4).use { bp ->
                val p1 = bp.newPage()
                p1.write(0, "x".toByteArray())
                bp.unpinPage(p1.id, isDirty = true)

                val p2 = bp.fetchPage(p1.id)
                assertContentEquals("x".toByteArray(), p2.read(0, 1))
                bp.unpinPage(p2.id, isDirty = false)
            }
        }
    }

    @Test
    fun `LRU eviction이 unpinned 페이지를 내보내고 dirty면 flush`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("bp.db").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 2).use { bp ->
                val a = bp.newPage()
                a.write(0, "A".toByteArray())
                bp.unpinPage(a.id, isDirty = true)

                val b = bp.newPage()
                b.write(0, "B".toByteArray())
                bp.unpinPage(b.id, isDirty = true)

                val c = bp.newPage()
                c.write(0, "C".toByteArray())
                bp.unpinPage(c.id, isDirty = true)

                assertEquals(2, bp.cachedPageCount())

                bp.flushAll()
            }
            PagedFile(path).use { pf2 ->
                val a2 = pf2.readPage(PageId(0, 0))
                assertContentEquals("A".toByteArray(), a2.read(0, 1))
                val c2 = pf2.readPage(PageId(0, 2))
                assertContentEquals("C".toByteArray(), c2.read(0, 1))
            }
        }
    }

    @Test
    fun `모든 페이지 pinned 상태에서 eviction 시도하면 AllPagesPinned`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("bp.db").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 2).use { bp ->
                bp.newPage()
                bp.newPage()
                assertFailsWith<StorageError.AllPagesPinned> {
                    bp.newPage()
                }
            }
        }
    }

    @Test
    fun `flushPage 후 reopen해서 같은 데이터 확인`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("bp.db").toString()
        val pid: PageId
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 4).use { bp ->
                val p = bp.newPage()
                pid = p.id
                p.write(0, "persist".toByteArray())
                bp.unpinPage(pid, isDirty = true)
                bp.flushPage(pid)
                pf.sync()
            }
        }
        PagedFile(path).use { pf ->
            val p = pf.readPage(pid)
            assertContentEquals("persist".toByteArray(), p.read(0, "persist".length))
        }
    }
}
```

**예상 실패**: **컴파일 실패** — `Unresolved reference: BufferPool`.

## 5. 구현 코드 (TDD step 3 — make it pass)

핵심은 첫 줄의 `LinkedHashMap(capacity, 0.75f, true)`다. 세 번째 인자 `true`가 access-order를 켜고, 그 순간 이 자료구조는 **LRU 큐가 된다** — `get()`이 항목을 뒤로 밀기 때문에 iteration 순서의 앞쪽이 곧 가장 오래 안 쓴 page다. 별도 replacer 클래스 없이 LRU를 얻는 방법이다.

```kotlin
// src/main/kotlin/com/dbenginelab/storage/BufferPool.kt @ 5505edc
package com.dbenginelab.storage

import java.io.Closeable

class BufferPool(
    private val pagedFile: PagedFile,
    private val capacity: Int = DEFAULT_CAPACITY,
) : Closeable {

    // Q: 왜 LinkedHashMap(_, _, true)? HashMap은 안 되나?
    private val pages: LinkedHashMap<PageId, Page> = LinkedHashMap(capacity, 0.75f, true)
    // <details><summary>A</summary>
    // access-order=true — get() 호출이 entry를 가장 뒤로 옮김. iteration 첫 번째 = LRU victim 후보. 외부 의존 없이 LRU 구현.
    // </details>

    fun fetchPage(id: PageId): Page {
        pages[id]?.let { cached ->
            // Q: 이 시점에 pin 왜?
            cached.pin()
            // <details><summary>A</summary>
            // 반환 직후 다른 fetch가 evict 시도하면 caller가 쓰는 동안 page 사라짐. 반환 전 pin이 race 방어.
            // </details>
            return cached
        }
        if (pages.size >= capacity) {
            evictOne()
        }
        val loaded = pagedFile.readPage(id)
        loaded.pin()
        pages[id] = loaded
        return loaded
    }

    fun newPage(): Page {
        val id = pagedFile.allocatePage()
        if (pages.size >= capacity) {
            evictOne()
        }
        val page = Page(id, ByteArray(Page.PAGE_SIZE))
        // Q: 새 page 가 처음부터 dirty?
        page.markDirty()
        // <details><summary>A</summary>
        // allocatePage는 zero-fill만 — 진짜 내용은 caller가 곧 쓸 것. 명시 dirty가 evict 시 정확한 flush 보장.
        // </details>
        page.pin()
        pages[id] = page
        return page
    }

    fun unpinPage(id: PageId, isDirty: Boolean) {
        val page = pages[id] ?: throw StorageError.PageNotInPool(id)
        // Q: isDirty=true 한 번이라도 받으면 page는 dirty?
        if (isDirty) page.markDirty()
        // <details><summary>A</summary>
        // cumulative dirty — markClean은 flush 후에만 호출. 한 번 dirty면 evict 전까지 dirty.
        // </details>
        page.unpin()
    }

    fun flushPage(id: PageId) {
        val page = pages[id] ?: return
        if (page.isDirty) {
            pagedFile.writePage(page)
            page.markClean()
        }
    }

    fun flushAll() {
        for (page in pages.values) {
            if (page.isDirty) {
                pagedFile.writePage(page)
                page.markClean()
            }
        }
        // Q: flushAll 끝에 sync 호출 이유?
        pagedFile.sync()
        // <details><summary>A</summary>
        // write는 OS buffer까지, sync가 disk까지 — durability invariant CI-2 만족.
        // </details>
    }

    fun cachedPageCount(): Int = pages.size

    private fun evictOne() {
        // Q: firstOrNull { pinCount == 0 } — iteration 순서가 무엇 보장?
        val victim = pages.values.firstOrNull { it.pinCount == 0 }
        // <details><summary>A</summary>
        // access-order LinkedHashMap iteration = LRU 순. 첫 unpinned = 가장 오래 안 쓴 page (LRU victim).
        // </details>
            ?: throw StorageError.AllPagesPinned(capacity)
        if (victim.isDirty) {
            pagedFile.writePage(victim)
            victim.markClean()
        }
        pages.remove(victim.id)
    }

    override fun close() {
        flushAll()
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 256
    }
}
```

## 6. 검증 테스트 (TDD step 4 — green)

테스트 파일은 §4에서 저장한 것이 그대로 최종본이다.

```bash
./gradlew test --tests 'com.dbenginelab.storage.BufferPoolTest'
```

**기대 결과**: `BufferPoolTest` **4 PASSED** · storage 누적 11 PASSED

invariant 대응:
- **CI-1** ← `newPage 후 같은 id로 fetch하면 같은 페이지`
- **CI-2** ← `LRU eviction이 unpinned 페이지를 내보내고 dirty면 flush`
- **CI-3** ← `모든 페이지 pinned 상태에서 eviction 시도하면 AllPagesPinned`
- (durability) ← `flushPage 후 reopen해서 같은 데이터 확인`

## 7. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

**1.** `evictOne()`에서 `if (victim.isDirty)` 블록을 통째로 지워라. 어느 테스트가 깨지는가?

<details><summary>답</summary>

**실측: 1개 깨진다** — `LRU eviction이 unpinned 페이지를 내보내고 dirty면 flush`.

잡히는 이유가 중요하다. 그 테스트는 **evict된 뒤 파일을 다시 열어 디스크 내용을 확인**하기 때문이다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
bp.flushAll()
}
PagedFile(path).use { pf2 ->                       // ← reopen
    val a2 = pf2.readPage(PageId(0, 0))
    assertContentEquals("A".toByteArray(), a2.read(0, 1))   // ← 디스크에 실제로 있나
}
```

page A는 capacity를 넘기며 이미 evict된 상태다. evict할 때 안 썼으면 디스크에 "A"가 없으니 여기서 걸린다.

**메모리 상태만 확인하는 테스트였다면 절대 못 잡는다.** dirty page 유실은 "지금 읽으면 멀쩡한데 재시작하면 사라지는" 종류의 결함이라, **reopen이 들어간 테스트만이 잡을 수 있다.** 단계 1부터 거의 모든 테스트에 reopen이 들어있는 이유가 이것이다.
</details>

**2.** `capacity = 1`로 두고 B+tree처럼 두 page를 번갈아 접근하는 코드를 짜라. IO 횟수가 어떻게 되는가?

<details><summary>답</summary>

**접근 횟수와 IO 횟수가 1:1이 된다.** 캐시가 있으나 마나다.

```
capacity = 1, page A와 B를 번갈아 접근:
fetch(A) → miss, 디스크 읽기 1회
fetch(B) → 자리 없음 → A evict(dirty면 쓰기 1회) → 디스크 읽기 1회
fetch(A) → A는 이미 쫓겨남 → B evict → 디스크 읽기 1회
...
```

히트율 0%. 이것을 **thrashing**이라 한다. 캐시가 "가장 오래 안 쓴 것"을 고르는데, **번갈아 쓰면 방금 쫓아낸 것이 바로 다음에 필요해지기** 때문이다. LRU의 최악 패턴이다.

실제 DB에서 이 현상이 어떻게 나타나는지 생각해봐라 — B+tree 탐색은 매번 root를 거친다. root가 캐시에 상주하지 못하면 모든 탐색이 디스크 IO 한 번을 더 쓴다. 그래서 buffer pool 크기 설정이 DB 튜닝의 첫 번째 항목이다.
</details>

**3.** `fetchPage` 후 `unpinPage`를 부르지 않는 코드를 반복 실행하면 몇 번째에 무슨 예외가 나오나?

<details><summary>답</summary>

**`capacity + 1`번째**에 `StorageError.AllPagesPinned`가 난다.

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
private fun evictOne() {
    val victim = pages.values.firstOrNull { it.pinCount == 0 }
        ?: throw StorageError.AllPagesPinned(capacity)
```

capacity만큼 채우는 동안은 evict가 필요 없어서 조용히 성공한다. 그 다음 요청에서 자리를 만들려는데 **전부 pin되어 있어 내보낼 것이 없다.**

이게 실제 코드에서 얼마나 쉽게 일어나는지 보려면 `BTreeIndex`를 봐라 — 모든 fetch가 `try { } finally { unpinPage(...) }`로 감싸여 있다. `finally`가 없으면 예외 경로에서 pin이 새고, **누수는 즉시가 아니라 capacity번째에** 드러난다. 원인과 증상이 멀리 떨어져 있어 추적이 어려운 종류의 버그다.
</details>

**4.** `fetchPage`의 캐시 hit 경로에서 `cached.pin()`을 지워라. 단일 스레드 테스트는 통과할 것이다. **어떤 순서**로 호출하면 깨지는지 시나리오를 글로 적어봐라.

<details><summary>답</summary>

```
capacity = 2, 캐시에 P·Q가 있고 둘 다 unpinned.

1. A: fetchPage(P) → 캐시 hit → pin 없이 Page 객체 반환 (pinCount는 0)
2. A: 반환받은 P를 아직 쓰고 있음
3. B: fetchPage(R) → 캐시 miss, 자리 없음 → evictOne()
4.    evictOne이 pinCount == 0 인 첫 page를 찾는다 → P가 걸린다
5.    P는 dirty면 디스크에 쓰이고 캐시에서 제거됨
6. A: 들고 있던 P 객체에 write() → 이 객체는 이제 캐시에 없다
7. 나중에 누군가 fetchPage(P) → 디스크에서 새로 읽어 새 Page 객체를 만든다
   → A가 6번에서 쓴 내용은 어디에도 없다
```

**조용한 데이터 유실**이다. 예외도 안 나고 로그도 안 남는다.

`pin()`이 하는 일을 한 문장으로 하면 — **"내가 이 객체를 들고 있는 동안은 캐시에서 빼지 마라"**는 예약이다. 캐시 miss 경로에는 `loaded.pin()`이 있는데 hit 경로에만 빠지면, **자주 쓰는 page일수록 위험해진다**는 역설이 생긴다(hit이 많이 나는 page가 곧 자주 쓰는 page이므로).
</details>

## 8. 다음 한계

캐시가 생겨서 IO는 줄었지만, **키 하나를 찾는 방법은 여전히 풀스캔**이다. 100만 건에서 하나를 찾으려면 100만 건을 다 본다. 캐시는 각 page를 읽는 비용을 줄일 뿐, 읽어야 할 page 수를 줄이지 못한다.

→ **단계 3 BTreeIndex**. 읽어야 할 page 수 자체를 `log N`으로 줄인다.
