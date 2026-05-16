package com.dbenginelab.catalog

/**
 * Schema-level constraints. Declared on TableSchema; actual data-level enforcement
 * happens at mutation time (stage 6 query operator + stage 7 transaction).
 *
 * sealed because the set is closed at this stage: PrimaryKey, Unique, ForeignKey.
 * CHECK is deferred until stage 6 (needs Expression).
 */
sealed class Constraint {
    /** PRIMARY KEY (one or more columns). Implies UNIQUE + NOT NULL. */
    data class PrimaryKey(val columns: List<String>) : Constraint() {
        init { require(columns.isNotEmpty()) { "PrimaryKey must have at least one column" } }
    }

    /** UNIQUE (one or more columns). NULLs are allowed and treated as distinct. */
    data class Unique(val columns: List<String>) : Constraint() {
        init { require(columns.isNotEmpty()) { "Unique must have at least one column" } }
    }

    /** FOREIGN KEY (columns in this table) → (refTable.refColumns). RESTRICT only. */
    data class ForeignKey(
        val columns: List<String>,
        val refTable: String,
        val refColumns: List<String>,
    ) : Constraint() {
        init {
            require(columns.size == refColumns.size && columns.isNotEmpty()) {
                "ForeignKey columns and refColumns must match in count"
            }
        }
    }
}
