package com.dbenginelab.optimizer

import com.dbenginelab.table.TableHeap

data class Statistics(
    val tableName: String,
    val rowCount: Long,
    val perColumnDistinct: Map<String, Long>,
) {
    fun equalitySelectivity(column: String): Double {
        val distinct = perColumnDistinct[column] ?: return 1.0
        return if (distinct == 0L) 1.0 else 1.0 / distinct
    }
}

object StatisticsCollector {
    fun analyze(name: String, heap: TableHeap): Statistics {
        var rowCount = 0L
        val distinctSets = heap.schema.columns.associate { it.name to mutableSetOf<Any?>() }
        for (tuple in heap.scan()) {
            rowCount++
            for (col in heap.schema.columns) distinctSets[col.name]!!.add(tuple.get(col.name))
        }
        return Statistics(name, rowCount, distinctSets.mapValues { it.value.size.toLong() })
    }
}
