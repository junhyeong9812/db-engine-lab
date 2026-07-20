package com.dbenginelab.catalog

sealed class Constraint {

    data class PrimaryKey(val columns: List<String>) : Constraint() {
        init {require(columns.isNotEmpty()) { "PrimaryKey must have at least one column" }}
    }

    data class Unique(val columns: List<String>) : Constraint() {
        init {require(columns.isNotEmpty()) {"Unique must have at least one column"}}
    }

    data class ForeignKey(
        val columns: List<String>,
        val refTable: String,
        val refColumns: List<String>
    ) : Constraint() {
        init {
            require(columns.size == refColumns.size && columns.isNotEmpty()) {
                "ForeignKey columns and refColumns must match in count"
            }
        }
    }
}