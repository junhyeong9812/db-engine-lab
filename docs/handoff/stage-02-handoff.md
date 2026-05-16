# Handoff: Stage 02 (Page + BufferPool) 완료 시점

> 작성일: 2026-05-16
> 직전 handoff: stage-01-handoff.md
> 다음 단계: 3 (Index — Hash → BTree)

## 0. 한 줄 요약
Page-based IO + LRU BufferPool 완성. PagedFileTest 4 PASSED, BufferPoolTest 4 PASSED. 누적 11 PASSED.

## 1. 결정된 사항 (누적)
- D-018: PageId는 data class (값 동등성), Page는 일반 class (mutable).
- D-019: Page size = 4096 bytes (가설값 유지).
- D-020: BufferPool LRU via `LinkedHashMap(_, _, true)` access-order. 외부 의존 없음.
- D-021: allocatePage는 명시적 zero-fill (sparse file 위험 회피).
- D-022: evict 시 모두 pinned면 silent block 대신 `AllPagesPinned` throw (학습 친화).

## 2. 만족하는 invariant (누적)
- I-1, I-2, I-3 (단계 1, 검증)
- I-4 (page IO round-trip), I-5 (page size 일관성), I-6 (pageCount 정확) — `PagedFileTest`
- CI-1 (같은 PageId fetch 시 같은 객체), CI-2 (dirty evict 시 flush), CI-3 (pinned는 evict 금지) — `BufferPoolTest`

## 3. 사용한 참조 출처
- SimpleDB `HeapPage`, `BufferPool.getPage`/`evictPage` — 구조 참고.
- BusTub `disk_manager`, `lru_replacer` — 대조. LRU-K는 단계 13 검토.

## 4. 핵심 코드 위치
- `storage.PageId` (data class — 식별자).
- `storage.Page` (class — mutable, dirty/pin, `require/check` invariant).
- `storage.PagedFile` (Closeable — allocate/read/write/sync).
- `storage.BufferPool` (Closeable — fetch/new/unpin/flush/eviction).
- `storage.StorageError` 확장: PageNotFound, PageNotInPool, AllPagesPinned.

공개 API:
- `PagedFile(path, fileId=0)` + `allocatePage()`, `readPage(id)`, `writePage(page)`, `sync()`, `pageCount()`, `close()`.
- `BufferPool(pagedFile, capacity=256)` + `newPage()`, `fetchPage(id)`, `unpinPage(id, isDirty)`, `flushPage(id)`, `flushAll()`, `cachedPageCount()`, `close()`.

## 5. 깨진 가설 / 갱신된 결정
- 단계 1 AppendOnlyFile은 변경 없음 (가설 유지). 단계 2 PagedFile은 별도 추상화로 공존. 단계 3부터는 PagedFile 기반.

## 6. 사용자가 막혔던 지점
- (Claude 검증 시점, 사용자 따라치기 전)

## 7. Spot check 결과
- (사용자 따라치기 시점에 SimpleDB `BufferPool.getPage` 흐름과 비교 권장)

## 8. 다음 단계 입력
- 단계 3 BTreeIndex는 PagedFile + BufferPool 위에 구축.
- BTree node = 1 page. fanout은 page size / (key+pointer) ≈ 250.
- Hash index는 단계 3 안에서 옵션 (생략 또는 별도 세션).

## 9. 새 세션을 위한 권고
- 이 문서 + `docs/stages/03-index.md` Invalidation triggers (page size, PageId 구조, fixed-size record 가정) 확인 후 진입.

---

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 단계 2 완료 snapshot (검증 통과 11/11) |
