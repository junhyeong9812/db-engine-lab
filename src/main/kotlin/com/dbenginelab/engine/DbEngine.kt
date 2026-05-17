package com.dbenginelab.engine

import com.dbenginelab.catalog.Catalog
import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.optimizer.SimpleOptimizer
import com.dbenginelab.optimizer.Statistics
import com.dbenginelab.optimizer.StatisticsCollector
import com.dbenginelab.sql.Lexer
import com.dbenginelab.sql.Parser
import com.dbenginelab.sql.Statement
import com.dbenginelab.sql.Translator
import com.dbenginelab.storage.BufferPool
import com.dbenginelab.storage.PagedFile
import com.dbenginelab.table.TableHeap
import java.io.Closeable
import java.io.File

/**
 * Stage 14 보강 (C2): End-to-end SQL DbEngine.
 *
 * SQL string → Lexer → Parser → (Translator | direct exec) → Optimizer → Executor → Result.
 *
 * Supported:
 *  - CREATE TABLE: catalog.registerTable + open heap
 *  - INSERT INTO ... VALUES (...): heap.insert
 *  - SELECT [* | cols] FROM table [WHERE expr]: optimizer + iterator
 *  - DROP TABLE: catalog.dropTable + close heap
 *
 * Not supported: JOINs, subqueries, aggregates, transactions through SQL
 * (transactions are programmatic via wal.TransactionManager — stage 14+ work).
 */
class DbEngine(private val dataDir: String) : Closeable {

    private val catalog: Catalog
    private val heaps: MutableMap<String, TableHeap> = mutableMapOf()
    private val pagedFiles: MutableMap<String, PagedFile> = mutableMapOf()
    private val bufferPools: MutableMap<String, BufferPool> = mutableMapOf()
    private val stats: MutableMap<String, Statistics> = mutableMapOf()

    init {
        File(dataDir).mkdirs()
        catalog = Catalog("$dataDir/catalog.meta")
        for (name in catalog.listTables()) openHeap(name)
    }

    sealed class QueryResult {
        data class Rows(val columns: List<String>, val rows: List<List<Any?>>) : QueryResult()
        data class Updated(val count: Int) : QueryResult()
        data class Created(val tableName: String) : QueryResult()
        data class Dropped(val tableName: String) : QueryResult()
    }

    fun execute(sql: String): QueryResult {
        val stmt = Parser(Lexer(sql).tokenize()).parseStatement()
        return when (stmt) {
            is Statement.Select -> executeSelect(stmt)
            is Statement.Insert -> executeInsert(stmt)
            is Statement.CreateTable -> executeCreate(stmt)
            is Statement.DropTable -> executeDrop(stmt)
        }
    }

    private fun executeSelect(stmt: Statement.Select): QueryResult.Rows {
        val logical = Translator.toLogicalPlan(stmt)
        val optimizer = SimpleOptimizer(
            tableLookup = { name -> heaps[name] ?: error("table $name not found") },
            statisticsLookup = { name -> stats[name] },
        )
        val physical = optimizer.optimize(logical)
        val rows = physical.root.iterator().toList()
        val columns = physical.root.outputSchema.columns.map { it.name }
        return QueryResult.Rows(columns, rows.map { tuple -> tuple.values.toList() })
    }

    private fun executeInsert(stmt: Statement.Insert): QueryResult.Updated {
        val heap = heaps[stmt.table] ?: error("table ${stmt.table} not found")
        val schema = heap.schema
        require(stmt.values.size == schema.columnCount) {
            "INSERT values count ${stmt.values.size} != schema columns ${schema.columnCount}"
        }
        val values = stmt.values.mapIndexed { i, expr ->
            literalValue(expr, schema, i)
        }
        heap.insert(Tuple(schema, values))
        return QueryResult.Updated(1)
    }

    private fun executeCreate(stmt: Statement.CreateTable): QueryResult.Created {
        val pk = stmt.primaryKey?.let {
            listOf(com.dbenginelab.catalog.Constraint.PrimaryKey(it))
        } ?: emptyList()
        val schema = TableSchema(stmt.name, stmt.columns, pk)
        catalog.registerTable(schema)
        openHeap(stmt.name)
        return QueryResult.Created(stmt.name)
    }

    private fun executeDrop(stmt: Statement.DropTable): QueryResult.Dropped {
        closeHeap(stmt.name)
        catalog.dropTable(stmt.name)
        File("$dataDir/${stmt.name}.data").delete()
        return QueryResult.Dropped(stmt.name)
    }

    fun analyze(tableName: String) {
        val heap = heaps[tableName] ?: error("table $tableName not found")
        stats[tableName] = StatisticsCollector.analyze(tableName, heap)
    }

    private fun openHeap(name: String) {
        val schema = catalog.getTable(name)
        val pf = PagedFile("$dataDir/$name.data")
        val bp = BufferPool(pf, capacity = 32)
        val heap = TableHeap(schema, pf, bp)
        pagedFiles[name] = pf
        bufferPools[name] = bp
        heaps[name] = heap
    }

    private fun closeHeap(name: String) {
        bufferPools.remove(name)?.close()
        pagedFiles.remove(name)?.close()
        heaps.remove(name)
        stats.remove(name)
    }

    private fun literalValue(expr: com.dbenginelab.sql.SqlExpr, schema: TableSchema, columnIndex: Int): Any? {
        val col = schema.columns[columnIndex]
        return when (expr) {
            is com.dbenginelab.sql.SqlExpr.LitNumber -> when (col.type) {
                com.dbenginelab.catalog.Type.INT -> expr.value.toInt()
                com.dbenginelab.catalog.Type.BIGINT -> expr.value
                else -> error("column ${col.name} expects ${col.type}, got number")
            }
            is com.dbenginelab.sql.SqlExpr.LitString -> {
                require(col.type == com.dbenginelab.catalog.Type.STRING) {
                    "column ${col.name} expects ${col.type}, got string"
                }
                expr.value
            }
            com.dbenginelab.sql.SqlExpr.LitNull -> null
            else -> error("INSERT VALUES must be literals (got ${expr::class.simpleName})")
        }
    }

    override fun close() {
        for (name in heaps.keys.toList()) closeHeap(name)
    }
}
