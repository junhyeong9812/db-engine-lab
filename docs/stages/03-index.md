# Stage 03 — Index (Hash → BTree)

> **Status**: speculative
> **Must revalidate on entry**: 단계 2 handoff (page size, BufferPool 인터페이스, PageId 형태) 대조 필수.
> **Known assumptions**: 단계 2 page IO + BufferPool 존재.
> **Invalidation triggers**: page size 변경, PageId 구조 변경, fixed-size record 가정 변경.
> **Discard or rewrite if prior stage output differs**.

---

## 1. 깨지는 가정

- 풀스캔은 O(N). record 수 늘면 search 시간 비례 증가.
- 단계 1·2는 어떤 key로도 정확 매칭 검색이 풀스캔.

## 2. 후보 도입 객체

| 후보 | 책임 | 위험 |
|------|------|------|
| `HashIndex` | bucket-based equality search | 범위 search 불가, BTree 도입 동기 |
| `BTreeIndex` | sorted key → record location | page split 복잡성, leaf 연결 |
| `IndexEntry` | (key, record_pointer) | record_pointer는 단계 2 PageId+slot |
| `IndexFile` | 인덱스 page 저장 (BufferPool 통해) | 데이터 파일과 분리 vs 같은 파일 |

## 3. Candidate invariant

- **CI-1**: insert(k, p) 후 search(k) → p.
- **CI-2**: range scan은 정렬 순서로 반환.
- **CI-3**: BTree split 후 모든 leaf의 sibling pointer 정상.
- **CI-4**: 인덱스 page도 BufferPool 통해 dirty/fsync 정책 적용.

## 4. 가설값

| 항목 | 가설 |
|------|------|
| Key 타입 | ByteArray (lex order) — 단계 4에서 typed key 확장 |
| BTree fanout | page size에 따라 (4KB이면 ~250) |
| Leaf 연결 | doubly linked (range scan 양방향) |
| Duplicate key | 허용 (단계 5에서 UNIQUE 제약으로 금지 가능) |

## 5. 후보 확인 질문

- Hash → BTree로 갈지, 처음부터 BTree만 할지? (학습 가치 비교)
- 인덱스는 primary key용만? secondary index는?
- 인덱스 entry의 pointer 형태 — slot id? record id? (단계 7 transaction의 tuple id와 호환)
- 인덱스와 데이터 파일을 같이 둘지 (clustered) 분리할지 (heap + index)?

## 6. 위험

- Hash·BTree 둘 다 만들면 시간 비용 큼. 학습 가치는 BTree에 집중.
- B+tree split/merge 알고리즘은 단계 3 안에서도 큰 sub-step (3~5 세션 가능).
- Concurrent split (단계 9 lock과 충돌)은 미루기 — 단계 9에서 latch 도입.

## 7. 세션 분할 계획 (잠정)

| 세션 | 범위 (잠정) |
|------|------------|
| 03-01 | HashIndex (단순 bucket, no resize) |
| 03-02 | BTree leaf only (정렬 + binary search) |
| 03-03 | BTree internal node + insert split |
| 03-04 | BTree delete + merge |
| 03-05 | Range scan (sibling pointer 활용) |

03-01은 학습 가치 작으면 skip 가능. 단계 진입 시 결정.

## 8. 참조 정책

- 주 참조: SimpleDB `BTreeFile`, `BTreePage` (lab 5).
- 대조 참조: BusTub `b_plus_tree` (project 2).
- 차이 주의: BusTub은 latch crabbing 등 동시성 다룸 (단계 9에서 우리도 도입).

## 9. 다음 단계의 동기

- "이 데이터는 무엇인가?" 의미 부여 필요 → **단계 4 Schema/Catalog**.
- 인덱스 + 풀스캔만으로 schema 검증 불가 → **단계 5 Constraints**.

---

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 (speculative) |
