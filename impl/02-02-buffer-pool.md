# impl/02-02 — BufferPool (한 줄 한 줄)

> 상위: `docs/stages/02-page-buffer.md`
> **검증**: BufferPoolTest 4 PASSED.
> 작성 파일:
> - 수정: `src/main/kotlin/com/dbenginelab/storage/StorageError.kt` (PageNotFound/PageNotInPool/AllPagesPinned 추가)
> - 신규: `src/main/kotlin/com/dbenginelab/storage/BufferPool.kt`
> - 신규: `src/test/kotlin/com/dbenginelab/storage/BufferPoolTest.kt`

## 0. 참조
- SimpleDB `BufferPool.getPage`/`evictPage`.
- BusTub `buffer_pool_manager_instance.cpp`, `lru_replacer.cpp` (단계 13 LRU-K 검토).

## 1. invariant
- CI-1: 같은 PageId fetch → 같은 객체 (캐시 일관).
- CI-2: dirty page evict 시 fsync.
- CI-3: pinned page 절대 evict 안 됨.

## 2. 의존성
- 02-01 (Page, PageId, PagedFile).
- StorageError 확장.

## 3. StorageError.kt 확장

```kotlin
// 기존 sealed class StorageError 에 추가
class PageNotFound(id: PageId) : StorageError("page not found: $id")
class PageNotInPool(id: PageId) : StorageError("page not in buffer pool: $id")
class AllPagesPinned(capacity: Int) : StorageError("all $capacity pages are pinned, cannot evict")
```

## 4. BufferPool.kt — 한 줄 한 줄

```kotlin
package com.dbenginelab.storage                                      // storage 패키지

import java.io.Closeable

class BufferPool(
    private val pagedFile: PagedFile,                                // 어떤 PagedFile 대상
    private val capacity: Int = DEFAULT_CAPACITY,                    // 최대 page 수
) : Closeable {

    // Q: 왜 LinkedHashMap(_, _, true)? HashMap은 안 되나?
    private val pages: LinkedHashMap<PageId, Page> = LinkedHashMap(capacity, 0.75f, true)
    // <details><summary>A</summary>
    // access-order=true — get() 호출이 entry를 가장 뒤로 옮김. iteration 첫 번째 = LRU victim 후보. 외부 의존 없이 LRU 구현.
    // </details>

    fun fetchPage(id: PageId): Page {
        pages[id]?.let { cached ->                                   // 캐시 hit
            // Q: 이 시점에 pin 왜?
            cached.pin()
            return cached
            // <details><summary>A</summary>
            // 반환 직후 다른 fetch가 evict 시도하면 caller가 쓰는 동안 page 사라짐. 반환 전 pin이 race 방어.
            // </details>
        }
        if (pages.size >= capacity) {                                // 캐시 full
            evictOne()                                                // 한 page 내보냄 (LRU)
        }
        val loaded = pagedFile.readPage(id)                          // disk → memory
        loaded.pin()                                                  // 반환 전 pin
        pages[id] = loaded                                            // 캐시 등록
        return loaded
    }

    fun newPage(): Page {
        val id = pagedFile.allocatePage()                            // disk에 새 page 할당
        if (pages.size >= capacity) {
            evictOne()
        }
        val page = Page(id, ByteArray(Page.PAGE_SIZE))               // 빈 Page 객체
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
        page.unpin()
        // <details><summary>A</summary>
        // cumulative dirty — markClean은 flush 후에만 호출. 한 번 dirty면 evict 전까지 dirty.
        // </details>
    }

    fun flushPage(id: PageId) {
        val page = pages[id] ?: return                                // 없으면 no-op
        if (page.isDirty) {
            pagedFile.writePage(page)                                 // disk에 쓰기
            page.markClean()                                          // 이제 깨끗
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
            ?: throw StorageError.AllPagesPinned(capacity)
        // <details><summary>A</summary>
        // access-order LinkedHashMap iteration = LRU 순. 첫 unpinned = 가장 오래 안 쓴 page (LRU victim).
        // </details>
        if (victim.isDirty) {
            pagedFile.writePage(victim)                              // dirty면 disk 쓰기 (sync 안 함 — flushAll에서)
            victim.markClean()
        }
        pages.remove(victim.id)                                      // 캐시에서 제거
    }

    override fun close() {
        flushAll()                                                    // close 시 자동 flush
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 256                        // 256 page = 1MB (4KB × 256)
    }
}
```

## 5. 검증 (4 PASSED)
- newPage 후 fetch → 같은 객체
- LRU eviction — capacity=2에 3 page 넣으면 첫째 evict + flush
- 모두 pinned 상태에서 newPage → AllPagesPinned throw
- flushPage 후 reopen → 데이터 보존

## 6. 깨뜨릴 과제
- capacity=1 — 어떤 사용 패턴에서 무한 IO?
- unpin 안 하고 fetch 반복 → 어떤 에러?
- dirty page evict 안 하고 process kill → 데이터 손실 분석.

## 7. 다음 한계
- search 여전히 풀스캔 → **단계 3 BTreeIndex**.
