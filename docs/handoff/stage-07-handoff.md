# Handoff: Stage 07 (WorkUnit) 완료

## 한 줄
Deferred-insert WorkUnit. ACID 아님 — 단계 8에서 진짜 Transaction.

## 결정
- D-038: deferred insert (buffer 후 commit 시 flush).
- D-039: WorkUnit은 명시적 "ACID 아님" 표시.
- D-040: ConstraintValidator commit 시점 일괄 (all-or-nothing).

## 코드
- `table.WorkUnit`

## 다음 입력 (8)
- WorkUnit + WAL 통합 → 진짜 Transaction. LogManager + Recovery 필요.
