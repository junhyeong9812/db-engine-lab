package com.dbenginelab.sql

import com.dbenginelab.catalog.ColumnDef

sealed class Statement {
    data class Select(val columns: List<String>?, val table: String, val where: SqlExpr?) : Statement()
    data class Insert(val table: String, val values: List<SqlExpr>) : Statement()
    data class CreateTable(val name: String, val columns: List<ColumnDef>, val primaryKey: List<String>?) : Statement()
    data class DropTable(val name: String) : Statement()
}

sealed class SqlExpr {
    data class Col(val name: String) : SqlExpr()
    data class LitNumber(val value: Long) : SqlExpr()
    data class LitString(val value: String) : SqlExpr()
    object LitNull : SqlExpr()
    data class Compare(val left: SqlExpr, val op: String, val right: SqlExpr) : SqlExpr()
    data class And(val left: SqlExpr, val right: SqlExpr) : SqlExpr()
    data class Or(val left: SqlExpr, val right: SqlExpr) : SqlExpr()
}
