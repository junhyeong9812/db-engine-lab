# Stage 06-02 — Filter + Project + Expression

> **Status**: implemented + verified
> 깨지는 가정: 단계 06-01 SeqScan은 모든 row 반환 — WHERE/SELECT col 의미 표현 불가.

## 도입
- `executor.Expression` (sealed): ColumnRef, Literal, Compare, Logical (AND/OR), Not.
- `executor.Filter`: predicate 적용.
- `executor.Project`: 컬럼 좁힘.

## invariant
- pred 결과가 null → false 취급 (SQL three-valued).
- Project는 schema 변경.
