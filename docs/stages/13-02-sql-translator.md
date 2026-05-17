# Stage 13-02 — SQL AST → LogicalPlan Translator (C1 보강)

> **Status**: implemented + verified (DbEngineTest로 간접)
> 깨지는 가정: 단계 12 Parser는 AST만 만들고, 단계 11 Optimizer LogicalPlan과 매핑 없음.

## 도입
- `sql.Translator.toLogicalPlan(Statement.Select): LogicalPlan`.
- SqlExpr → Expression 재귀 변환.
- Compare op string → CompareOp enum.

## invariant
- SELECT AST 모든 분기 → 정확 LogicalPlan.
- INSERT/CREATE/DROP은 변환 안 함 (DbEngine 별도 처리).
