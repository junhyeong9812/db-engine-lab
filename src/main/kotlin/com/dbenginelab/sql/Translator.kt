package com.dbenginelab.sql

import com.dbenginelab.executor.Expression
import com.dbenginelab.optimizer.LogicalPlan

/**
 * Stage 13 보강 (C1): SQL AST → optimizer LogicalPlan 변환.
 *
 * SELECT 만 지원 (INSERT/CREATE/DROP 은 DbEngine facade가 별도 처리).
 */
object Translator {

    fun toLogicalPlan(statement: Statement.Select): LogicalPlan {
        var plan: LogicalPlan = LogicalPlan.Scan(statement.table)
        if (statement.where != null) {
            plan = LogicalPlan.FilterNode(plan, translateExpr(statement.where))
        }
        if (statement.columns != null) {
            plan = LogicalPlan.ProjectNode(plan, statement.columns)
        }
        return plan
    }

    private fun translateExpr(e: SqlExpr): Expression = when (e) {
        is SqlExpr.Col -> Expression.col(e.name)
        is SqlExpr.LitNumber -> Expression.lit(e.value)
        is SqlExpr.LitString -> Expression.lit(e.value)
        SqlExpr.LitNull -> Expression.lit(null)
        is SqlExpr.Compare -> Expression.Compare(
            translateExpr(e.left),
            translateCompareOp(e.op),
            translateExpr(e.right),
        )
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
