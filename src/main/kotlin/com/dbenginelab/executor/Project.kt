package com.dbenginelab.executor

import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple

class Project(private val child: Operator, private val columnNames: List<String>) : Operator {
    override val outputSchema: TableSchema
    private val indices: IntArray

    init {
        val childCols = child.outputSchema.columns
        val keptCols = columnNames.map { name ->
            childCols.firstOrNull { it.name == name }
                ?: error("Project: column $name not in child schema")
        }
        outputSchema = TableSchema(name = "${child.outputSchema.name}_projected", columns = keptCols)
        indices = IntArray(columnNames.size) { i -> child.outputSchema.columnIndex(columnNames[i]) }
    }

    override fun iterator(): Sequence<Tuple> = child.iterator().map { srcTuple ->
        val newValues = indices.map { srcTuple.values[it] }
        Tuple(outputSchema, newValues)
    }
}
