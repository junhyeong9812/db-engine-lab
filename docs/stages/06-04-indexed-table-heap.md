# Stage 06-04 — IndexedTableHeap (X1 보강)

> **Status**: implemented + verified
> 깨지는 가정: 단계 3 BTree와 단계 6 TableHeap 따로. INSERT 시 index 갱신/unique 검증/rollback 한 계약으로 안 묶임.

## 도입
- `table.IndexedTableHeap`: TableHeap + BTreeIndex 통합 wrapper.
- insert(tuple) = PK index 중복 검사 → heap.insert + index.insert (한 계약).
- findByKey(key) = index search → heap 조회.

## invariant
- nullable PK 거부 (init).
- BIGINT PK만.
- 중복 PK → ConstraintViolation, heap 변경 없음.

## 다음 한계
- Secondary index 미지원 (single PK index만).
- DELETE/UPDATE 미지원.
- crash 시 heap 적용 + index 미적용 race → 단계 8 WAL 통합 필요.
