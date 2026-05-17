package com.dbenginelab.backup

import com.dbenginelab.catalog.Catalog
import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.table.TableHeap
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.FileReader
import java.io.FileWriter

class LogicalBackup {
    fun dump(catalog: Catalog, heaps: Map<String, TableHeap>, outPath: String) {
        BufferedWriter(FileWriter(outPath)).use { w ->
            for (tableName in catalog.listTables()) {
                val schema = catalog.getTable(tableName)
                val heap = heaps[tableName] ?: continue
                w.write(createTableStmt(schema)); w.newLine()
                for (tuple in heap.scan()) {
                    w.write(insertStmt(tuple)); w.newLine()
                }
            }
        }
    }

    fun restore(inPath: String): List<String> {
        val statements = mutableListOf<String>()
        BufferedReader(FileReader(inPath)).useLines { lines ->
            for (line in lines) if (line.isNotBlank()) statements.add(line)
        }
        return statements
    }

    private fun createTableStmt(schema: TableSchema): String {
        val cols = schema.columns.joinToString(", ") { c ->
            val nn = if (!c.nullable) " NOT NULL" else ""
            "${c.name} ${c.type.name}$nn"
        }
        return "CREATE TABLE ${schema.name} ($cols);"
    }

    private fun insertStmt(tuple: Tuple): String {
        val vals = tuple.values.joinToString(", ") { v ->
            when (v) {
                null -> "NULL"
                is String -> "'${v.replace("'", "''")}'"
                else -> v.toString()
            }
        }
        return "INSERT INTO ${tuple.schema.name} VALUES ($vals);"
    }
}
