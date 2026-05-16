package com.dbenginelab.catalog

data class ColumnDef(
    val name: String,
    val type: Type,
    val nullable: Boolean = true,
)
