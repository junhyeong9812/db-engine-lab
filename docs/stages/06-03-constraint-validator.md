# Stage 06-03 — ConstraintValidator

> **Status**: implemented + verified
> 깨지는 가정: 단계 5 constraints는 schema 레벨 정의만. 실제 row 검증 없음.

## 도입
- `table.ConstraintValidator`: insert 시 PK/Unique/FK 검증.
- 풀스캔 O(N) — 단계 11+ index-backed 후속.

## invariant
- 중복 PK/Unique → throw ConstraintViolation.
- FK 위반 → throw.
- NULL은 PK/Unique에서 distinct.
