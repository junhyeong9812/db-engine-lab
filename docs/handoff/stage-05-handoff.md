# Handoff: Stage 05 (Constraints) 완료

> 2026-05-16. 직전: stage-04. 다음: 6 (Query API).

## 한 줄 요약
sealed Constraint (PK/Unique/FK). TableSchema 레벨 정의·검증·persistence. 데이터 검증은 단계 6에서. 33+ tests.

## 결정
- D-031: Constraint는 sealed (PK/Unique/FK 닫힌 집합).
- D-032: 검증 2단계 — schema 정의 / data 실제 (단계 6).
- D-033: CHECK constraint는 단계 6 expression 후 5-2 별도.
- D-034: FK는 RESTRICT only (CASCADE 비목표).

## 코드
- `catalog.Constraint` (sealed)
- `catalog.TableSchema` (constraints field + validateConstraints)
- `catalog.Catalog` (constraint persistence — tag byte + binary)

## 다음 입력 (Stage 6)
- mutation operator (Insert/Update/Delete)가 ConstraintValidator를 호출.
- PK/Unique는 BTree index 활용 (단계 3) 또는 풀스캔 (느림).
- FK는 다른 테이블 BTree 조회.

---
| 2026-05-16 |
