package com.dbenginelab.catalog

data class TableSchema(
    val name: String,
    val columns: List<ColumnDef>,
    val constraints: List<Constraint> = emptyList(),
) {
    init {
        require(columns.isNotEmpty()) {"table $name must have at least one column"}
        require(columns.map {it.name}.toSet().size == columns.size) {
            "duplicate column names in table $name"
        }
        validateConstraints()
    }

    val columnCount: Int get () = columns.size

    fun columnIndex(name: String): Int {
        val idx = columns.indexOfFirst {it.name == name}
        require(idx >= 0) {"column $name not found in table ${this.name}"}
        return idx
    }

    fun column(name: String): ColumnDef = columns[columnIndex(name)]

    fun primaryKey(): Constraint.PrimaryKey? = constraints.filterIsInstance<Constraint.PrimaryKey>().firstOrNull()

    private fun validateConstraints() {
        val pks = constraints.filterIsInstance<Constraint.PrimaryKey>()
        require(pks.size <= 1) { "table $name has more than one PRIMARY KEY" }
        for (constraint in constraints) {
            when (constraint) {
                is Constraint.PrimaryKey  -> {
                    constraint.columns.forEach { c ->
                        val col = column(c)
                        require(!col.nullable) {
                            "PRIMARY KEY column $c must be NOT NULL in table $name"
                        }
                    }
                }
                is Constraint.Unique -> {
                    constraint.columns.forEach { c -> columnIndex(c) }
                }
                is Constraint.ForeignKey -> {
                    constraint.columns.forEach { c -> columnIndex(c) }
                }
            }
        }
    }
}