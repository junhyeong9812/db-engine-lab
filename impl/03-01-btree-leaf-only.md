# impl/03-01 — BTree Leaf Only

> **종류**: 세션형
> **상위 단계**: `docs/stages/03-index.md`
> **코드 정본**: git `95205bc` — "stage 3-1: BTree leaf only (17/17 tests)"
> **이 세션의 범위**: page 하나짜리 B+tree. split도 internal 노드도 없다 — leaf가 꽉 차면 예외를 던지고 끝낸다.
> **작성 파일**:
> - 신규: `src/main/kotlin/com/dbenginelab/storage/BTreePage.kt`
> - 신규: `src/main/kotlin/com/dbenginelab/storage/BTreeIndex.kt`
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/storage/BTreeIndexTest.kt`
> **검증**: `BTreeIndexTest` 6 PASSED · storage 누적 17 PASSED
> **예상 타이핑 시간**: 50분

---

## 0. 참조

- 주 참조: SimpleDB `BTreeFile` / `BTreeLeafPage` (lab 5).
- 대조 참조: BusTub `b_plus_tree_leaf_page.h`.
- **핵심 설계 결정 근거**: `BTreePage`는 데이터를 **소유하지 않는다**. `Page`의 바이트 위에 "여기부터 4바이트는 keyCount"라는 해석만 얹는 view class다. 이렇게 하면 BufferPool의 캐시·pin·dirty 관리가 그대로 살아있다.

## 1. 만족시킬 invariant

- **CI-1**: `insert(k, v)` 후 `search(k)` = `v`.
- **CI-2**: 저장 순서와 무관하게 key 오름차순이 유지된다.
- **CI-3**: duplicate key는 거부된다 (단계 5 UNIQUE 이전의 임시 규칙).

## 2. 의존성

- 이전 세션: `impl/02-02-buffer-pool.md` (`Page`, `PagedFile`, `BufferPool`)

## 3. 문제 정의 (TDD step 1)

02-02까지 오면 page 단위 IO와 캐시가 있다. 그런데 **키 하나를 찾는 방법이 여전히 전수 조사다.**

인덱스의 아이디어는 단순하다 — 키를 **정렬된 상태로** 유지하면 이진 탐색이 가능하다. 100만 건에서 20번이면 찾는다.

정렬 상태를 page 위에 유지하려면 두 가지가 필요하다:

1. **page 바이트를 구조로 해석하는 층** — 어디가 헤더고 어디부터 entry인가. 그게 `BTreePage`다. header 13바이트(type 1 + keyCount 4 + auxPage 4 + parentPage 4) 뒤에 16바이트짜리 entry(key 8 + value 8)가 이어진다. 그래서 한 page에 `(4096 - 13) / 16 = 255`개가 들어간다.
2. **정렬을 깨지 않고 삽입하는 연산** — 중간에 끼워 넣으려면 뒤쪽을 한 칸씩 밀어야 한다. **오른쪽부터** 밀어야 한다는 것이 이번 세션에서 손으로 확인할 첫 번째 함정이다.

이번 세션은 **page 하나**만 다룬다. 255개를 넘으면? 예외를 던진다. 그 예외가 03-02의 출발점이 된다. 처음부터 split까지 넣으면 디버깅이 두 배로 어려워지기 때문에 일부러 자른다.

## 4. 실패 테스트 (TDD step 2)

마지막 테스트가 특이하다 — **"꽉 차면 예외가 나야 한다"를 검증한다.** 미완성을 미완성으로 못 박는 테스트이고, 03-02에서 이 테스트는 split 테스트로 교체된다.

```kotlin
// src/test/kotlin/com/dbenginelab/storage/BTreeIndexTest.kt @ 95205bc
package com.dbenginelab.storage

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BTreeIndexTest {

    @Test
    fun `insert 후 search로 같은 값 찾는다`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("btree.db").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 16).use { bp ->
                val idx = BTreeIndex(pf, bp)
                idx.insert(42L, 1000L)
                idx.insert(100L, 2000L)
                idx.insert(7L, 3000L)

                assertEquals(1000L, idx.search(42L))
                assertEquals(2000L, idx.search(100L))
                assertEquals(3000L, idx.search(7L))
            }
        }
    }

    @Test
    fun `존재하지 않는 key는 null 반환`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("btree.db").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 16).use { bp ->
                val idx = BTreeIndex(pf, bp)
                idx.insert(1L, 10L)
                assertNull(idx.search(999L))
            }
        }
    }

    @Test
    fun `정렬 안 된 순서로 insert 해도 정렬된 상태 유지`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("btree.db").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 16).use { bp ->
                val idx = BTreeIndex(pf, bp)
                val keys = listOf(50L, 10L, 80L, 30L, 70L, 20L, 60L, 90L, 40L)
                keys.forEachIndexed { i, k -> idx.insert(k, (i * 1000).toLong()) }

                for ((i, k) in keys.withIndex()) {
                    assertEquals((i * 1000).toLong(), idx.search(k), "key=$k")
                }
            }
        }
    }

    @Test
    fun `duplicate key insert는 IllegalArgumentException`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("btree.db").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 16).use { bp ->
                val idx = BTreeIndex(pf, bp)
                idx.insert(5L, 100L)
                assertThrows<IllegalArgumentException> { idx.insert(5L, 200L) }
            }
        }
    }

    @Test
    fun `reopen 후에도 데이터 보존 (BufferPool flush)`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("btree.db").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 16).use { bp ->
                val idx = BTreeIndex(pf, bp)
                idx.insert(1L, 10L)
                idx.insert(2L, 20L)
                idx.insert(3L, 30L)
                idx.close()
            }
        }
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 16).use { bp ->
                val idx = BTreeIndex(pf, bp)
                assertEquals(10L, idx.search(1L))
                assertEquals(20L, idx.search(2L))
                assertEquals(30L, idx.search(3L))
                assertEquals(3, idx.size())
            }
        }
    }

    @Test
    fun `leaf full 후 insert는 UnsupportedOperationException (stage 3-2에서 split 도입)`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("btree.db").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 16).use { bp ->
                val idx = BTreeIndex(pf, bp)
                for (k in 1..BTreePage.MAX_ENTRIES) {
                    idx.insert(k.toLong(), (k * 10).toLong())
                }
                assertEquals(BTreePage.MAX_ENTRIES, idx.size())
                assertThrows<UnsupportedOperationException> {
                    idx.insert((BTreePage.MAX_ENTRIES + 1).toLong(), 0L)
                }
            }
        }
    }
}
```

**예상 실패**: **컴파일 실패** — `Unresolved reference: BTreeIndex`, `BTreePage`.

## 5. 구현 코드 (TDD step 3 — make it pass)

### 5.1 `BTreePage.kt` — page 바이트를 노드로 읽는 층

`data class`로 만들지 않는다. 이 객체는 값이 아니라 **mutable 바이트 위의 창(view)**이라 `equals`/`hashCode`가 의미를 갖지 않는다. 그리고 정수 읽기·쓰기는 직접 bit shift 하지 않고 `ByteBuffer`를 쓴다 — endian을 명시적으로 고정하기 위해서다.

```kotlin
// src/main/kotlin/com/dbenginelab/storage/BTreePage.kt @ 95205bc
package com.dbenginelab.storage

import java.nio.ByteBuffer

class BTreePage(val page: Page) {

    enum class Type(val code: Byte) {
        LEAF(0),
        INTERNAL(1);

        companion object {
            fun fromCode(c: Byte): Type = when (c) {
                0.toByte() -> LEAF
                1.toByte() -> INTERNAL
                else -> error("unknown btree page type code: $c")
            }
        }
    }

    var type: Type
        get() = Type.fromCode(page.read(0, 1)[0])
        set(value) { page.write(0, byteArrayOf(value.code)) }

    var keyCount: Int
        get() = readIntAt(1)
        set(v) { writeIntAt(1, v) }

    var auxPage: Int
        get() = readIntAt(5)
        set(v) { writeIntAt(5, v) }

    var parentPage: Int
        get() = readIntAt(9)
        set(v) { writeIntAt(9, v) }

    fun keyAt(slot: Int): Long = readLongAt(HEADER_SIZE + slot * ENTRY_SIZE)
    fun valueAt(slot: Int): Long = readLongAt(HEADER_SIZE + slot * ENTRY_SIZE + KEY_SIZE)

    fun setEntry(slot: Int, key: Long, value: Long) {
        writeLongAt(HEADER_SIZE + slot * ENTRY_SIZE, key)
        writeLongAt(HEADER_SIZE + slot * ENTRY_SIZE + KEY_SIZE, value)
    }

    fun insertAt(slot: Int, key: Long, value: Long) {
        val count = keyCount
        require(count < MAX_ENTRIES) { "btree page full (max=$MAX_ENTRIES)" }
        require(slot in 0..count) { "invalid slot $slot for count $count" }
        for (i in count downTo slot + 1) {
            setEntry(i, keyAt(i - 1), valueAt(i - 1))
        }
        setEntry(slot, key, value)
        keyCount = count + 1
    }

    fun findSlot(target: Long): Int {
        var lo = 0
        var hi = keyCount
        while (lo < hi) {
            // Q: (lo + hi) / 2 가 아니라 ushr 1?
            val mid = (lo + hi) ushr 1
            // <details><summary>A</summary>
            // (lo+hi)가 Int 오버플로우 가능. ushr는 signed bit 영향 없이 정확. JDK Arrays.binarySearch와 같은 관용구.
            // </details>
            if (keyAt(mid) < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun isFull(): Boolean = keyCount >= MAX_ENTRIES

    fun initAsEmpty(type: Type, parentPage: Int = INVALID, auxPage: Int = INVALID) {
        this.type = type
        this.keyCount = 0
        this.parentPage = parentPage
        this.auxPage = auxPage
    }

    private fun readIntAt(offset: Int): Int =
        ByteBuffer.wrap(page.read(offset, 4)).int

    private fun writeIntAt(offset: Int, v: Int) {
        page.write(offset, ByteBuffer.allocate(4).putInt(v).array())
    }

    private fun readLongAt(offset: Int): Long =
        ByteBuffer.wrap(page.read(offset, 8)).long

    private fun writeLongAt(offset: Int, v: Long) {
        page.write(offset, ByteBuffer.allocate(8).putLong(v).array())
    }

    companion object {
        const val INVALID: Int = -1
        const val KEY_SIZE: Int = 8
        const val VALUE_SIZE: Int = 8
        const val ENTRY_SIZE: Int = KEY_SIZE + VALUE_SIZE
        const val HEADER_SIZE: Int = 1 + 4 + 4 + 4
        const val MAX_ENTRIES: Int = (Page.PAGE_SIZE - HEADER_SIZE) / ENTRY_SIZE
    }
}
```

### 5.2 `BTreeIndex.kt` — leaf 하나만 쓰는 버전

```kotlin
// src/main/kotlin/com/dbenginelab/storage/BTreeIndex.kt @ 95205bc
package com.dbenginelab.storage

import java.io.Closeable

/**
 * B+tree index storing Long → Long mappings.
 *
 * Stage 3-1 limitations:
 *  - Single-leaf only. No split, no internal nodes.
 *  - When the leaf fills up (MAX_ENTRIES = 255), insert throws UnsupportedOperationException.
 *  - No duplicate keys.
 *  - No delete.
 *
 * Multi-leaf with split is added in stage 3-2; multi-level in stage 3-3.
 */
class BTreeIndex(
    private val pagedFile: PagedFile,
    private val bufferPool: BufferPool,
) : Closeable {

    init {
        if (pagedFile.pageCount() == 0) {
            val page = bufferPool.newPage()
            check(page.id.pageNumber == ROOT_PAGE_NUMBER) {
                "first allocated page must be root (page 0), got ${page.id.pageNumber}"
            }
            BTreePage(page).initAsEmpty(BTreePage.Type.LEAF)
            bufferPool.unpinPage(page.id, isDirty = true)
        }
    }

    fun insert(key: Long, value: Long) {
        val page = bufferPool.fetchPage(PageId(pagedFile.fileId, ROOT_PAGE_NUMBER))
        try {
            val btp = BTreePage(page)
            val slot = btp.findSlot(key)
            require(!(slot < btp.keyCount && btp.keyAt(slot) == key)) {
                "duplicate key not supported in stage 3: $key"
            }
            if (btp.isFull()) {
                throw UnsupportedOperationException(
                    "BTree leaf full (${BTreePage.MAX_ENTRIES} entries). Split is added in stage 3-2."
                )
            }
            btp.insertAt(slot, key, value)
        } finally {
            bufferPool.unpinPage(page.id, isDirty = true)
        }
    }

    fun search(key: Long): Long? {
        val page = bufferPool.fetchPage(PageId(pagedFile.fileId, ROOT_PAGE_NUMBER))
        try {
            val btp = BTreePage(page)
            val slot = btp.findSlot(key)
            return if (slot < btp.keyCount && btp.keyAt(slot) == key) btp.valueAt(slot) else null
        } finally {
            bufferPool.unpinPage(page.id, isDirty = false)
        }
    }

    fun size(): Int {
        val page = bufferPool.fetchPage(PageId(pagedFile.fileId, ROOT_PAGE_NUMBER))
        try {
            return BTreePage(page).keyCount
        } finally {
            bufferPool.unpinPage(page.id, isDirty = false)
        }
    }

    override fun close() {
        bufferPool.flushAll()
    }

    companion object {
        const val ROOT_PAGE_NUMBER: Int = 0
    }
}
```

`insert`와 `search` 모두 `try-finally`로 `unpinPage`를 감싼 것에 주목하라. 중간에 예외가 나가도 pin은 반드시 풀려야 한다. 안 풀리면 그 page는 **영원히 evict 불가**가 되고, BufferPool은 capacity가 찬 순간 `AllPagesPinned`로 죽는다. 원인을 찾기 매우 어려운 종류의 버그다.

## 6. 검증 테스트 (TDD step 4 — green)

테스트 파일은 §4에서 저장한 것이 그대로 최종본이다.

```bash
./gradlew test --tests 'com.dbenginelab.storage.BTreeIndexTest'
```

**기대 결과**: `BTreeIndexTest` **6 PASSED** · storage 누적 **17 PASSED**

invariant 대응:
- **CI-1** ← `insert 후 search로 같은 값 찾는다` · `존재하지 않는 key는 null 반환`
- **CI-2** ← `정렬 안 된 순서로 insert 해도 정렬된 상태 유지`
- **CI-3** ← `duplicate key insert는 IllegalArgumentException`
- (durability) ← `reopen 후에도 데이터 보존 (BufferPool flush)`
- (한계 고정) ← `leaf full 후 insert는 UnsupportedOperationException`

## 7. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

**1.** `insertAt`의 밀어내기를 `for (i in slot + 1..count)`로 **왼쪽부터** 바꿔라. 어떤 입력에서 무엇이 깨지는가?

<details><summary>답</summary>

**실측: 10개 중 3개 깨진다** — `insert 후 search로 같은 값 찾는다`, `정렬 안 된 순서로 insert 해도 정렬된 상태 유지`, `정렬 안 된 순서로 대량 insert …`.

**중간 삽입이 일어나는 순간** 깨진다. 첫 테스트가 `42 → 100 → 7` 순서인데, `7`을 넣을 때 slot 0에 끼워야 하므로 뒤를 밀어야 한다.

왼쪽부터 밀면 이렇게 된다 (`[42, 100]`에 `7`을 slot 0에 삽입, count=2):

```
i=1: setEntry(1, keyAt(0), ...) → [42, 42]      ← 100이 사라짐
i=2: setEntry(2, keyAt(1), ...) → [42, 42, 42]  ← 방금 덮은 42를 또 복사
결과: [7, 42, 42]  — 100이 유실
```

**이미 덮어쓴 값을 다시 읽어 옮기기 때문**이다. 그래서 slot 이후가 전부 같은 값으로 도배된다.

오른쪽부터 가면 항상 **아직 안 건드린 칸**을 읽는다:

```
i=2: setEntry(2, keyAt(1)) → [42, 100, 100]
i=1: setEntry(1, keyAt(0)) → [42, 42, 100]
결과: [7, 42, 100]  ✓
```

배열 안에서 겹치는 구간을 옮길 때는 **방향이 정답을 결정한다.** C의 `memmove`가 `memcpy`와 따로 있는 이유가 정확히 이것이다.
</details>

**2.** `findSlot`의 `(lo + hi) ushr 1`을 `(lo + hi) / 2`로 바꿔라. 안 깨진다 — **왜 안 깨지는지**, 그리고 어떤 조건이면 깨지는지 적어라.

<details><summary>답</summary>

**실측: 10개 전부 통과한다.**

안 깨지는 이유: `lo`와 `hi`는 최대 `keyCount`(= 255)까지만 간다. `lo + hi`가 커봐야 510이라 `Int` 범위(약 21억)와는 비교도 안 된다. **오버플로가 일어날 길이 없다.**

깨지는 조건은 `lo + hi > Int.MAX_VALUE`일 때다. 그러면 값이 음수로 뒤집히고, `/ 2`는 **음수를 0 쪽으로 반올림**하므로 `mid`가 `lo`보다 작아진다 → 루프가 끝나지 않거나 잘못된 위치를 반환한다. `ushr`(부호 없는 오른쪽 시프트)는 뒤집힌 부호 비트를 그대로 자릿수로 취급해서 올바른 중간값을 준다.

**그래서 이 줄은 "지금 필요한 방어"가 아니라 "관용구"다.** JDK의 `Arrays.binarySearch`도 같은 이유로 `ushr`을 쓴다 — 2006년에 Java 라이브러리의 이진 탐색에서 실제로 발견된 버그이고, 그 뒤로 표준 패턴이 됐다.

배열이 10억 개 원소를 가질 일이 우리 코드엔 없지만, **관용구를 관용구인 채로 쓰는 것**과 **이유를 알고 쓰는 것**의 차이가 이 문제의 핵심이다.
</details>

**3.** `PAGE_SIZE`를 1024로 줄이면 `MAX_ENTRIES`는 몇이 되나? 먼저 계산하고 나서 확인해라.

<details><summary>답</summary>

```
HEADER_SIZE = 1(type) + 4(keyCount) + 4(auxPage) + 4(parentPage) = 13
ENTRY_SIZE  = 8(key) + 8(value) = 16

MAX_ENTRIES = (1024 - 13) / 16 = 1011 / 16 = 63   (Int 나눗셈이라 버림)
```

**63개.** 참고로 4096일 때는 `(4096 - 13) / 16 = 255`다.

한 걸음 더 — page가 작아지면 트리의 **깊이**가 깊어진다. 한 노드에 63개씩 담으면 100만 건을 담는 데 `log₆₃(1,000,000) ≈ 3.3`레벨, 255개씩이면 `log₂₅₅(1,000,000) ≈ 2.5`레벨이다. 레벨 하나가 곧 디스크 IO 한 번이다.

**"page를 크게 하면 트리가 얕아진다"** — B+tree가 이진 트리가 아니라 다진 트리인 이유가 이것이다. 메모리 자료구조라면 이진 트리로 충분하지만, 디스크에서는 노드 하나를 읽는 비용이 크기 때문에 **노드를 최대한 뚱뚱하게** 만든다.
</details>

**4.** `insert` 도중 프로세스를 죽여라(flush 전). reopen하면 그 insert는 남아있는가?

<details><summary>답</summary>

**대개 남지 않는다.** `insert`는 `BufferPool`의 메모리 상의 `Page`만 고치고 `isDirty = true`로 표시할 뿐이다. 디스크에 실제로 쓰이는 시점은 셋 중 하나다:

1. `evictOne()`이 그 page를 내보낼 때 (capacity가 찼을 때)
2. `flushAll()` — `BufferPool.close()`가 부른다
3. `flushPage(id)` 명시 호출

셋 다 안 일어난 채 죽으면 **메모리에만 있던 변경이 사라진다.**

주의할 점: **"대개"** 라고 한 이유는 1번 때문이다. capacity가 작아 그 page가 이미 evict됐다면 디스크에 쓰여 있고, 그러면 살아남는다. 즉 **살아남는지 여부가 buffer pool 크기와 접근 패턴에 달려 있다** — 예측 불가능하다는 뜻이다.

"어떤 건 남고 어떤 건 안 남는데 무엇이 남을지 알 수 없다"는 상태가 단계 8 WAL이 필요한 이유다. WAL은 이 순서를 뒤집는다 — **데이터를 고치기 전에 로그를 먼저 디스크에 확정**시켜서, 무엇이 살아남을지를 예측 가능하게 만든다.
</details>

## 8. 다음 한계

255개를 넘기는 순간 `UnsupportedOperationException`이다. 인덱스라고 부르기엔 민망한 상한이다.

→ **03-02 split**. leaf가 꽉 차면 둘로 쪼개고, 그 위에 부모(internal 노드)를 만든다. 그때 B+tree가 비로소 트리가 된다. 그리고 **03-03 range scan** — 구간 조회는 leaf들이 옆으로 연결되어 있어야 가능한데, 그 연결(`auxPage`)은 지금 만들어두고 쓰지 않는 상태다.
