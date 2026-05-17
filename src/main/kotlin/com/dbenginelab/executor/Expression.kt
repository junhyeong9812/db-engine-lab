package com.dbenginelab.executor

import com.dbenginelab.catalog.Tuple

sealed class Expression {
    abstract fun evaluate(tuple: Tuple): Any?

    data class ColumnRef(val name: String) : Expression() {
        override fun evaluate(tuple: Tuple): Any? = tuple.get(name)
    }

    data class Literal(val value: Any?) : Expression() {
        override fun evaluate(tuple: Tuple): Any? = value
    }

    enum class CompareOp { EQ, NE, LT, LE, GT, GE }

    data class Compare(val left: Expression, val op: CompareOp, val right: Expression) : Expression() {
        override fun evaluate(tuple: Tuple): Any? {
            val l = left.evaluate(tuple)
            val r = right.evaluate(tuple)
            if (l == null || r == null) return null
            val cmp = compareValues(l, r)
            return when (op) {
                CompareOp.EQ -> cmp == 0
                CompareOp.NE -> cmp != 0
                CompareOp.LT -> cmp < 0
                CompareOp.LE -> cmp <= 0
                CompareOp.GT -> cmp > 0
                CompareOp.GE -> cmp >= 0
            }
        }

        private fun compareValues(a: Any, b: Any): Int = when {
            a is Int && b is Int -> a.compareTo(b)
            a is Long && b is Long -> a.compareTo(b)
            a is Int && b is Long -> a.toLong().compareTo(b)
            a is Long && b is Int -> a.compareTo(b.toLong())
            a is String && b is String -> a.compareTo(b)
            else -> error("incomparable types: ${a::class.simpleName} vs ${b::class.simpleName}")
        }
    }

    enum class LogicalOp { AND, OR }

    data class Logical(val left: Expression, val op: LogicalOp, val right: Expression) : Expression() {
        override fun evaluate(tuple: Tuple): Any? {
            val l = left.evaluate(tuple) as? Boolean ?: return null
            val r = right.evaluate(tuple) as? Boolean ?: return null
            return if (op == LogicalOp.AND) l && r else l || r
        }
    }

    data class Not(val expr: Expression) : Expression() {
        override fun evaluate(tuple: Tuple): Any? {
            val v = expr.evaluate(tuple) as? Boolean ?: return null
            return !v
        }
    }

    companion object {
        fun col(name: String): Expression = ColumnRef(name)
        fun lit(value: Any?): Expression = Literal(value)
        fun eq(l: Expression, r: Expression): Expression = Compare(l, CompareOp.EQ, r)
        fun lt(l: Expression, r: Expression): Expression = Compare(l, CompareOp.LT, r)
        fun gt(l: Expression, r: Expression): Expression = Compare(l, CompareOp.GT, r)
        fun and(l: Expression, r: Expression): Expression = Logical(l, LogicalOp.AND, r)
        fun or(l: Expression, r: Expression): Expression = Logical(l, LogicalOp.OR, r)
    }
}
