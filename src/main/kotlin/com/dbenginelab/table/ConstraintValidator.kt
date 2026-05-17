package com.dbenginelab.table

import com.dbenginelab.catalog.Constraint
import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple

class ConstraintValidator(
    private val heap: TableHeap,
    private val foreignKeyLookup: (String) -> TableHeap? = { null },
) {
    private val schema: TableSchema get() = heap.schema

    fun validateInsert(tuple: Tuple) {
        for (constraint in schema.constraints) {
            when (constraint) {
                is Constraint.PrimaryKey -> validateUniqueColumns(tuple, constraint.columns, "PRIMARY KEY")
                is Constraint.Unique -> validateUniqueColumns(tuple, constraint.columns, "UNIQUE")
                is Constraint.ForeignKey -> validateForeignKey(tuple, constraint)
            }
        }
    }

    private fun validateUniqueColumns(tuple: Tuple, columns: List<String>, label: String) {
        val newValues = columns.map { tuple.get(it) }
        if (newValues.any { it == null }) return
        for (existing in heap.scan()) {
            val existingValues = columns.map { existing.get(it) }
            if (existingValues.any { it == null }) continue
            if (existingValues == newValues) {
                throw ConstraintViolation(
                    "$label violation on (${columns.joinToString(",")}) in table ${schema.name}: $newValues already exists"
                )
            }
        }
    }

    private fun validateForeignKey(tuple: Tuple, fk: Constraint.ForeignKey) {
        val childValues = fk.columns.map { tuple.get(it) }
        if (childValues.any { it == null }) return
        val refHeap = foreignKeyLookup(fk.refTable)
            ?: throw ConstraintViolation("FOREIGN KEY refTable ${fk.refTable} not available")
        for (parent in refHeap.scan()) {
            if (fk.refColumns.map { parent.get(it) } == childValues) return
        }
        throw ConstraintViolation("FOREIGN KEY violation: $childValues not in ${fk.refTable}")
    }
}

class ConstraintViolation(message: String) : RuntimeException(message)
