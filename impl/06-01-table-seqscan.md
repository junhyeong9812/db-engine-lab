# impl/06-01 — TableHeap + Operator + SeqScan + InsertOp

> 상위: `docs/stages/06-query-api.md`
> 범위: 가장 기초 — heap-organized tuple storage + Volcano operator interface + SeqScan + Insert.
> **✅ 검증 완료. SeqScanTest 3 PASSED.**

---

## 0. 참조 출처
- SimpleDB `HeapFile`, `HeapPage`, `OpIterator`, `SeqScan` — slot directory 미사용, sequential-only로 단순화.
- BusTub `executor`, `table_heap` — Volcano pattern.

## 1. invariant
- **CI-1**: insert → scan 시 모든 tuple 반환 (order: insertion order within page, page order globally).
- **CI-2**: multi-page heap에서도 끊김 없이 scan.
- **CI-3**: reopen 후 데이터 보존.
- **CI-4**: tuple이 한 page 크기 초과하면 throw (단계 8 WAL overflow는 별도).

## 2. Page layout (TableHeap)
```
[4: tupleCount]
[4: freeOffset]
then: [4: tupleLen][tuple bytes] * tupleCount
```
- No slot directory (delete/update가 단계 7+이므로 in-page 재사용 안 함).
- Insert는 last page 시도 → 안 맞으면 new page allocate.

## 3. Operator interface (Volcano)
```kotlin
interface Operator {
    val outputSchema: TableSchema
    fun iterator(): Sequence<Tuple>
}
```
- Pull-based (Sequence lazy)
- Schema는 operator별 (Project가 schema 변경 가능)

## 4. 핵심 결정
- **TableHeap.scan()는 page 단위 materialize** — page 전체 tuple을 리스트로 디코드 후 yieldAll. lazy 단순화. 단계 13에서 진짜 streaming.
- **InsertOp는 Operator 아님** — mutation은 result tuple이 의미 작음. 단계 12 SQL DML에서 통일.
- **Constraint validation 미통합** — PK/Unique/FK 검증은 단계 6-3 또는 단계 7+ transaction.

## 5. 다음 한계
- WHERE 같은 필터 없음 → **06-02 Filter + Expression**
- SELECT col1, col2 같은 projection 없음 → **06-02 Project**
- Index 활용 안 함 → **06-04 IndexScan + Join**
- Constraint 위반 시점 검증 안 함 → **06-03 ConstraintValidator**

---
| 2026-05-16 | 초안 |
