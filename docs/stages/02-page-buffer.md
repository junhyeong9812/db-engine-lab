# Stage 02 — Page-based IO + Thin Buffer Pool

> **Status**: speculative (단계 2 진입 시 재검토 필수)
> **Must revalidate on entry**: 단계 1 handoff 결과와 대조 후 본문 모든 결정 재검토.
> **Known assumptions**: 단계 1의 raw record append가 풀스캔/메모리 한계로 깨지는 것이 동기.
> **Invalidation triggers**: 단계 1에서 page 구조를 미리 도입했거나, partial write 처리를 다르게 한 경우.
> **Discard or rewrite if prior stage output differs**.
> **Do not implement without rereading prior stage output**.

---

## 1. 깨지는 가정 (단계 1에서 넘어옴 후보)

- 큰 record/거대 데이터를 한 번에 read/write하면 메모리·IO 비효율.
- 풀스캔 외에 random access 불가 (단계 3 인덱스 도입 전제).
- OS·디스크 IO 단위와 application 단위가 어긋남.

## 2. 후보 도입 객체 (확정 아님)

| 후보 | 책임 | 위험 |
|------|------|------|
| `Page` (mutable byte container) | 고정 크기 byte 슬롯, dirty flag, pin/unpin | mutable이라 `data class` 사용 금지 (constraints.md) |
| `PageId` | page 식별 (file_id + page_number) | 단계 4 catalog 도입 후 multi-file에서 의미 확장 |
| `BufferPool` (얇은 버전) | LRU 또는 단순 FIFO, fixed capacity | 단계 13에서 튜닝, 여기서는 정확성만 |
| `PageManager` / `FileManager` | page_id → file offset 변환 | 단계 1의 `AppendOnlyFile`을 page 단위로 재작성 |

## 3. 만족시킬 candidate invariant (`must verify from latest stage output`)

- **CI-1**: writePage(p) → fsync → reopen → readPage(p) 시 동일 byte.
- **CI-2**: BufferPool에서 evict된 dirty page는 fsync 후에만 evict.
- **CI-3**: pin된 page는 evict되지 않음 (lock-free invariant, 단계 9에서 강화).
- **CI-4**: page size는 단일 값, 모든 IO는 page size의 배수.

## 4. 가설값 (변경 가능)

| 항목 | 가설 | 검증 시점 |
|------|------|----------|
| Page size | **4KB** (OS page와 일치) | 단계 2 진입 시 8KB 후보와 벤치 |
| BufferPool 크기 | 256 page (1MB) | 단계 13 튜닝 |
| Eviction 정책 | LRU | 단계 13에서 LRU-K, CLOCK 검토 |
| Page format | slot directory 아니고 단순 byte array | 단계 4 catalog 도입 시 slot directory 후보 |
| 비어있는 page 표시 | header magic number | 단계 8 WAL 도입 시 checksum과 통합 |

## 5. 후보 확인 질문 (구현 전에 답해야 할)

- Page는 fixed-size인가? variable-size record가 page를 넘으면? (overflow page 도입 시점은?)
- BufferPool의 page 식별은 PageId만으로 충분한가? (단계 7 transaction에서 version 추가 필요?)
- 단계 1의 `AppendOnlyFile`을 어떻게 마이그레이션? (그대로 wrap? 완전 재작성?)
- mmap을 쓸까, file IO를 직접 할까? (mmap은 OS page cache와 BufferPool 경계가 모호해짐 — constraints.md "안전망 가정")

## 6. 위험 (codex 보정 4: 권위화 방지)

- "Page = 4KB"가 확정처럼 보일 위험. 단계 2 진입 시 OS page size 직접 측정 후 결정.
- BufferPool LRU 결정은 너무 빠를 수 있음. 단계 13에서 의미 있게 차이남.
- 단계 1의 record 단위와 단계 2의 page 단위 사이 어댑터가 필요할 수 있음 (학습 가치 작으니 통합 재작성이 나을 수도).

## 7. 세션 분할 계획 (잠정 — `candidate sessions`)

| 세션 | 범위 (잠정) |
|------|------------|
| 02-01 | Page byte container + PageId + 단순 file mapping |
| 02-02 | 얇은 BufferPool (fixed capacity, LRU, pin/unpin) |
| 02-03 | (옵션) dirty page eviction 시 fsync 정책 |

## 8. 참조 정책 (단계 진입 시 정확화)

- 주 참조: SimpleDB `BufferPool`, `Page` 인터페이스.
- 대조 참조: BusTub `buffer_pool_manager_instance`, `lru_replacer`.
- 차이 주의: BusTub은 LRU-K 학습용 (단계 13 검토). SimpleDB는 LRU 또는 random.

## 9. 다음 단계의 동기 (이 단계가 깨지는 지점)

- random access 가능하지만 search는 여전히 풀스캔 → **단계 3 Index**.
- 다중 테이블·다중 파일 관리 안 됨 → **단계 4 Catalog**.

---

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 (speculative) |
