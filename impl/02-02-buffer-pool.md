# impl/02-02 — BufferPool (LRU eviction, pin/unpin)

> 상위 단계: `docs/stages/02-page-buffer.md`
> 이 세션의 범위: BufferPool — fetchPage/unpinPage/flush/eviction.
> 예상 타이핑 시간: 30~40분.
> **✅ Claude 검증 완료: BufferPoolTest 4 PASSED.**

---

## 0. 참조 출처

### 주 참조 (SimpleDB)
- `simpledb/storage/BufferPool.java` — `getPage`, `evictPage`.

### 대조 참조 (BusTub)
- `src/buffer/buffer_pool_manager_instance.cpp`, `src/buffer/lru_replacer.cpp`.
- 차이: BusTub은 LRU-K (project 1 advanced). 우리는 단순 LRU. **단계 13 튜닝 시 LRU-K 검토**.

### 핵심 설계 결정 근거
- **`LinkedHashMap(capacity, 0.75f, true)` access-order** — Kotlin/Java 표준 라이브러리만으로 LRU 구현. 외부 의존 불필요.
- **eviction 시 dirty면 fsync 후 evict** — invariant CI-2 (page durability).
- **모두 pinned면 throw** — silent block보다 명시 fail이 학습 친화.

---

## 1. invariant
- **CI-1**: 같은 PageId fetch 시 같은 객체 반환 (캐시 일관성).
- **CI-2**: 모든 dirty page는 evict 또는 flush 전 fsync.
- **CI-3**: pin된 page는 evict 안 됨.

## 2. 의존성
- 02-01 (PageId, Page, PagedFile).
- StorageError 확장 (PageNotInPool, AllPagesPinned).

---

## 3. 문제 정의

매번 PagedFile에서 page를 읽으면 IO 비용 큼. 자주 쓰는 page를 메모리에 cache하고, 메모리 한계 도달 시 가장 오래 안 쓴 page를 evict.

---

## 4. 실패 테스트

```kotlin
@Test
fun `LRU eviction이 unpinned 페이지를 내보내고 dirty면 flush`(@TempDir tempDir: Path) {
    val path = tempDir.resolve("bp.db").toString()
    PagedFile(path).use { pf ->
        BufferPool(pf, capacity = 2).use { bp ->
            val a = bp.newPage(); a.write(0, "A".toByteArray()); bp.unpinPage(a.id, true)
            val b = bp.newPage(); b.write(0, "B".toByteArray()); bp.unpinPage(b.id, true)
            val c = bp.newPage(); c.write(0, "C".toByteArray()); bp.unpinPage(c.id, true)
            assertEquals(2, bp.cachedPageCount())
            bp.flushAll()
        }
        // capacity=2였으니 A가 evict됐을 것. evict 시 disk에 flush 됐어야.
        PagedFile(path).use { pf2 ->
            val a2 = pf2.readPage(PageId(0, 0))
            assertContentEquals("A".toByteArray(), a2.read(0, 1))
            val c2 = pf2.readPage(PageId(0, 2))
            assertContentEquals("C".toByteArray(), c2.read(0, 1))
        }
    }
}
```

---

## 5. 구현 코드

### 5.1 `StorageError.kt` 확장

```kotlin
// 기존 sealed class StorageError에 추가:
class PageNotFound(id: PageId) : StorageError("page not found: $id")
class PageNotInPool(id: PageId) : StorageError("page not in buffer pool: $id")
class AllPagesPinned(capacity: Int) : StorageError("all $capacity pages are pinned, cannot evict")
```

### 5.2 `BufferPool.kt`

```kotlin
package com.dbenginelab.storage

import java.io.Closeable

class BufferPool(
    private val pagedFile: PagedFile,
    private val capacity: Int = DEFAULT_CAPACITY,
) : Closeable {

    // Q: 왜 LinkedHashMap을 access-order(true)로? 그냥 HashMap은 안 되나?
    private val pages: LinkedHashMap<PageId, Page> = LinkedHashMap(capacity, 0.75f, true)
    // <details><summary>A</summary>
    //
    // access-order이면 get() 호출이 entry를 가장 뒤로 옮김 — iteration 첫 번째가 가장 오래 안 쓴 page (LRU victim 후보).
    // </details>

    fun fetchPage(id: PageId): Page {
        pages[id]?.let { cached ->
            // Q: 이 시점에 pin을 왜 하는가? caller가 직접 pin해도 되지 않나?
            cached.pin()
            return cached
            // <details><summary>A</summary>
            //
            // fetchPage 반환 직후 다른 fetch가 evict 시도하면 caller의 작업 중에 page가 사라짐 — 반환 전 pin이 race 방어.
            // </details>
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
        // Q: 새 페이지가 왜 처음부터 dirty인가?
        page.markDirty()
        // <details><summary>A</summary>
        //
        // allocatePage가 zero-fill만 했고 진짜 내용은 caller가 곧 쓸 것. 명시적 dirty가 다음 evict 시 정확한 flush 보장.
        // </details>
        page.pin()
        pages[id] = page
        return page
    }

    fun unpinPage(id: PageId, isDirty: Boolean) {
        val page = pages[id] ?: throw StorageError.PageNotInPool(id)
        // Q: unpin 시 isDirty=true로 받은 적이 한 번이라도 있으면 page는 dirty인가?
        if (isDirty) page.markDirty()
        page.unpin()
        // <details><summary>A</summary>
        //
        // 한 번이라도 dirty=true 신호 받으면 page 전체가 dirty (markClean은 flush 후에만). cumulative dirty 누적.
        // </details>
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
        // Q: flushAll 끝에 pagedFile.sync()를 호출하는 이유?
        pagedFile.sync()
        // <details><summary>A</summary>
        //
        // write만으로는 OS buffer까지, sync()가 디스크까지 — durability invariant CI-2 만족.
        // </details>
    }

    fun cachedPageCount(): Int = pages.size

    private fun evictOne() {
        // Q: 왜 firstOrNull { pinCount == 0 } ? iteration 순서가 무엇을 보장하는가?
        val victim = pages.values.firstOrNull { it.pinCount == 0 }
            ?: throw StorageError.AllPagesPinned(capacity)
        // <details><summary>A</summary>
        //
        // access-order LinkedHashMap의 iteration은 LRU 순 — 첫 unpinned가 가장 오래 안 쓴 page (LRU victim).
        // </details>
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

---

## 6. 검증 테스트

(`src/test/kotlin/com/dbenginelab/storage/BufferPoolTest.kt` 4 PASSED)

---

## 7. 직접 깨뜨릴 과제

- 과제 1: capacity를 1로 줄이면 어떤 사용 패턴에서 무한 IO 발생? hit ratio 측정 방법은?
- 과제 2: unpin 안 하고 fetch만 계속하면 어떤 에러? 학습 코드에서 typical bug 패턴은?
- 과제 3: dirty page를 evict 안 하고 process kill하면 어떤 데이터 손실? `flushAll`을 어디서 부르는 게 안전한가?

---

## 8. 다음 한계

- BufferPool은 IO 최적화지만 search는 여전히 풀스캔. → **단계 3 BTreeIndex**.

---

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 — 검증 완료 |
