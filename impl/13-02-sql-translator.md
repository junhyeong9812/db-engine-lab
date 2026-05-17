# impl/13-02 — SQL AST → LogicalPlan Translator (C1 보강)

> **검증**: DbEngine end-to-end test 5 PASSED.
> 보강: 단계 12 SQL Parser는 AST만, 단계 11 Optimizer LogicalPlan과 매핑 없었음. 이 갭 채움.

## 0. 참조 — Apache Calcite SqlToRelConverter 패턴 (단순화).

## 1. invariant
- SELECT AST → LogicalPlan (Scan/Filter/Project).
- Compare op string ("=", "<>", ...) → CompareOp enum 정확 매핑.
- AND/OR/Compare 재귀 변환.

## 2. 코드 (sql/Translator.kt)

```kotlin
object Translator {
    fun toLogicalPlan(statement: Statement.Select): LogicalPlan {
        var plan: LogicalPlan = LogicalPlan.Scan(statement.table)
        if (statement.where != null) plan = LogicalPlan.FilterNode(plan, translateExpr(statement.where))
        if (statement.columns != null) plan = LogicalPlan.ProjectNode(plan, statement.columns)
        return plan
    }

    private fun translateExpr(e: SqlExpr): Expression = when (e) {
        is SqlExpr.Col -> Expression.col(e.name)
        is SqlExpr.LitNumber -> Expression.lit(e.value)
        is SqlExpr.LitString -> Expression.lit(e.value)
        SqlExpr.LitNull -> Expression.lit(null)
        is SqlExpr.Compare -> Expression.Compare(translateExpr(e.left), translateCompareOp(e.op), translateExpr(e.right))
        is SqlExpr.And -> Expression.and(translateExpr(e.left), translateExpr(e.right))
        is SqlExpr.Or -> Expression.or(translateExpr(e.left), translateExpr(e.right))
    }

    private fun translateCompareOp(op: String): Expression.CompareOp = when (op) {
        "=" -> Expression.CompareOp.EQ
        "<>", "!=" -> Expression.CompareOp.NE
        "<" -> Expression.CompareOp.LT
        "<=" -> Expression.CompareOp.LE
        ">" -> Expression.CompareOp.GT
        ">=" -> Expression.CompareOp.GE
        else -> error("unknown compare op: $op")
    }
}
```

## 3. 깨뜨릴 과제
- subquery는? (Translator는 SELECT만)
- aggregate (COUNT/SUM) 추가?
- NOT op?

## 4. 다음 한계
- Parser+Translator만으론 entry point 없음 → **14 DbEngine facade**.
