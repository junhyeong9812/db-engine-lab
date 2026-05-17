package com.dbenginelab.optimizer

import com.dbenginelab.executor.Expression
import com.dbenginelab.executor.Filter
import com.dbenginelab.executor.Operator
import com.dbenginelab.executor.Project
import com.dbenginelab.executor.SeqScan
import com.dbenginelab.table.TableHeap

sealed class LogicalPlan {
    data class Scan(val table: String) : LogicalPlan()
    data class FilterNode(val child: LogicalPlan, val predicate: Expression) : LogicalPlan()
    data class ProjectNode(val child: LogicalPlan, val columns: List<String>) : LogicalPlan()
}

data class PhysicalCost(val io: Double, val cpu: Double) {
    val total: Double get() = io + cpu
}

class SimpleOptimizer(
    private val tableLookup: (String) -> TableHeap,
    private val statisticsLookup: (String) -> Statistics? = { null },
) {
    data class PhysicalPlan(val root: Operator, val cost: PhysicalCost)

    fun optimize(logical: LogicalPlan): PhysicalPlan = buildPhysical(logical)

    private fun buildPhysical(plan: LogicalPlan): PhysicalPlan = when (plan) {
        is LogicalPlan.Scan -> {
            val heap = tableLookup(plan.table)
            val stats = statisticsLookup(plan.table)
            val rowCount = stats?.rowCount ?: 1000L
            PhysicalPlan(SeqScan(heap), PhysicalCost(io = rowCount.toDouble(), cpu = rowCount * 0.1))
        }
        is LogicalPlan.FilterNode -> {
            val child = buildPhysical(plan.child)
            val sel = estimateSelectivity(plan.predicate, plan.child)
            val rowsOut = child.cost.io * sel
            val cost = PhysicalCost(io = child.cost.io, cpu = child.cost.cpu + rowsOut * 0.05)
            PhysicalPlan(Filter(child.root, plan.predicate), cost)
        }
        is LogicalPlan.ProjectNode -> {
            val child = buildPhysical(plan.child)
            val cost = PhysicalCost(io = child.cost.io, cpu = child.cost.cpu + child.cost.io * 0.02)
            PhysicalPlan(Project(child.root, plan.columns), cost)
        }
    }

    private fun estimateSelectivity(expr: Expression, child: LogicalPlan): Double {
        if (expr is Expression.Compare && expr.op == Expression.CompareOp.EQ) {
            val colName = (expr.left as? Expression.ColumnRef)?.name
                ?: (expr.right as? Expression.ColumnRef)?.name
            val table = baseTable(child)
            if (colName != null && table != null) {
                statisticsLookup(table)?.let { return it.equalitySelectivity(colName) }
            }
        }
        return 0.3
    }

    private fun baseTable(plan: LogicalPlan): String? = when (plan) {
        is LogicalPlan.Scan -> plan.table
        is LogicalPlan.FilterNode -> baseTable(plan.child)
        is LogicalPlan.ProjectNode -> baseTable(plan.child)
    }
}
