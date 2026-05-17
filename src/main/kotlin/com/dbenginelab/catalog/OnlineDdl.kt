package com.dbenginelab.catalog

object OnlineDdl {
    fun addColumn(catalog: Catalog, tableName: String, newColumn: ColumnDef): TableSchema {
        require(newColumn.nullable) { "ADD COLUMN online supports nullable columns only" }
        val current = catalog.getTable(tableName)
        val newSchema = current.copy(columns = current.columns + newColumn)
        catalog.dropTable(tableName)
        catalog.registerTable(newSchema)
        return newSchema
    }
}
