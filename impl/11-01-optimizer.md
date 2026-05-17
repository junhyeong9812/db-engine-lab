# impl/11-01 — Statistics + SimpleOptimizer (한 줄 한 줄)

> **검증**: OptimizerTest 2 PASSED.
> 작성 파일:
> - 신규 패키지: `src/main/kotlin/com/dbenginelab/optimizer/`
> - 신규: Statistics.kt, SimpleOptimizer.kt
> - 신규 테스트: OptimizerTest.kt

## 0. 참조
- SimpleDB `JoinOptimizer` (lab6).
- BusTub `optimizer`.

## 1. invariant
- Statistics = rowCount + perColumn distinct count.
- equalitySelectivity = 1/distinct.
- LogicalPlan → PhysicalPlan 변환 후 같은 결과.

## 2. Statistics.kt — 한 줄 한 줄

```kotlin
package com.dbenginelab.optimizer                                    // 신규 optimizer 패키지

import com.dbenginelab.table.TableHeap

data class Statistics(
    val tableName: String,
    val rowCount: Long,
    val perColumnDistinct: Map<String, Long>,
) {
    fun equalitySelectivity(column: String): Double {                // Q: 1/distinct?
        val distinct = perColumnDistinct[column] ?: return 1.0
        return if (distinct == 0L) 1.0 else 1.0 / distinct
        // <details><summary>A</summary>
        // uniform distribution 가정 — N개 distinct value면 한 value의 선택율은 1/N. histogram 없을 때 기본.
        // </details>
    }
}

object StatisticsCollector {
    fun analyze(name: String, heap: TableHeap): Statistics {         // 풀스캔 ANALYZE
        var rowCount = 0L
        val distinctSets = heap.schema.columns.associate { it.name to mutableSetOf<Any?>() }
        for (tuple in heap.scan()) {
            rowCount++
            for (col in heap.schema.columns) distinctSets[col.name]!!.add(tuple.get(col.name))
        }
        return Statistics(name, rowCount, distinctSets.mapValues { it.value.size.toLong() })
    }
}
```

## 3. SimpleOptimizer.kt — 한 줄 한 줄

```kotlin
package com.dbenginelab.optimizer
import com.dbenginelab.executor.*
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
            val rowCount = stats?.rowCount ?: 1000L                  // stats 없으면 default
            // Q: cost = IO + CPU. IO weight = rowCount, CPU = rowCount * 0.1?
            val cost = PhysicalCost(io = rowCount.toDouble(), cpu = rowCount * 0.1)
            PhysicalPlan(SeqScan(heap), cost)
            // <details><summary>A</summary>
            // 단순화 — page IO와 tuple 비용 비례. 진짜는 ceil(rowCount * tupleSize / pageSize) page IO.
            // </details>
        }
        is LogicalPlan.FilterNode -> {
            val child = buildPhysical(plan.child)
            val sel = estimateSelectivity(plan.predicate, plan.child)
            val rowsOut = child.cost.io * sel                         // 추정 출력 row
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
        // Q: EQ만 정밀? LT/GT는 default 0.3?
        if (expr is Expression.Compare && expr.op == Expression.CompareOp.EQ) {
            val colName = (expr.left as? Expression.ColumnRef)?.name
                ?: (expr.right as? Expression.ColumnRef)?.name
            val table = baseTable(child)
            if (colName != null && table != null) {
                statisticsLookup(table)?.let { return it.equalitySelectivity(colName) }
            }
        }
        return 0.3                                                    // PostgreSQL 기본값과 유사
        // <details><summary>A</summary>
        // EQ는 distinct count로 정밀. LT/GT는 histogram 필요 — 단계 11+ 후속.
        // </details>
    }

    private fun baseTable(plan: LogicalPlan): String? = when (plan) {
        is LogicalPlan.Scan -> plan.table
        is LogicalPlan.FilterNode -> baseTable(plan.child)
        is LogicalPlan.ProjectNode -> baseTable(plan.child)
    }
}
```

## 4. 검증 (2 PASSED)
- ANALYZE 정확한 distinct count
- LogicalPlan → PhysicalPlan 변환 + 실행

## 5. 깨뜨릴 과제
- histogram (equi-width) — LT/GT 정밀 추정?
- IndexScan operator + index cost — SeqScan vs IndexScan 어디서 선택?
- Join cost (nested loop vs hash join)?
