package com.dbenginelab.catalog

data class TableSchema(
    val name: String,
    val columns: List<ColumnDef>,
) {
    init {
        require(columns.isNotEmpty()) { "table $name must have at least one column" }
        require(columns.map { it.name }.toSet().size == columns.size) {
            "duplicate column names in table $name"
        }
    }

    val columnCount: Int get() = columns.size

    fun columnIndex(name: String): Int {
        val idx = columns.indexOfFirst { it.name == name }
        require(idx >= 0) { "column $name not found in table ${this.name}" }
        return idx
    }

    fun column(name: String): ColumnDef = columns[columnIndex(name)]
}
