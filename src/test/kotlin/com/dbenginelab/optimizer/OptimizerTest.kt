package com.dbenginelab.optimizer

import com.dbenginelab.catalog.ColumnDef
import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.catalog.Type
import com.dbenginelab.executor.Expression
import com.dbenginelab.executor.InsertOp
import com.dbenginelab.storage.BufferPool
import com.dbenginelab.storage.PagedFile
import com.dbenginelab.table.TableHeap
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OptimizerTest {
    private val schema = TableSchema(
        name = "users",
        columns = listOf(
            ColumnDef("id", Type.BIGINT, nullable = false),
            ColumnDef("name", Type.STRING, nullable = false),
        ),
    )

    @Test fun `Statistics ANALYZE 정확한 distinct count`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("s.data").toString()
        PagedFile(path).use { pf -> BufferPool(pf, 16).use { bp ->
            val heap = TableHeap(schema, pf, bp)
            val ins = InsertOp(heap)
            ins.insertOne(Tuple(schema, listOf(1L, "A")))
            ins.insertOne(Tuple(schema, listOf(2L, "A")))
            ins.insertOne(Tuple(schema, listOf(3L, "B")))
            val stats = StatisticsCollector.analyze("users", heap)
            assertEquals(3, stats.rowCount)
            assertEquals(2L, stats.perColumnDistinct["name"])
            assertEquals(0.5, stats.equalitySelectivity("name"))
        }}
    }

    @Test fun `Optimizer가 PhysicalPlan으로 변환 + 실행`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("o.data").toString()
        PagedFile(path).use { pf -> BufferPool(pf, 16).use { bp ->
            val heap = TableHeap(schema, pf, bp)
            InsertOp(heap).insertMany((1..10).map { Tuple(schema, listOf(it.toLong(), "n$it")) })
            val logical = LogicalPlan.ProjectNode(
                LogicalPlan.FilterNode(
                    LogicalPlan.Scan("users"),
                    Expression.eq(Expression.col("id"), Expression.lit(5L)),
                ),
                listOf("name"),
            )
            val opt = SimpleOptimizer({ heap }, { StatisticsCollector.analyze("users", heap) })
            val physical = opt.optimize(logical)
            val results = physical.root.iterator().toList()
            assertEquals(1, results.size)
            assertEquals("n5", results[0].get("name"))
            assertTrue(physical.cost.total > 0)
        }}
    }
}
