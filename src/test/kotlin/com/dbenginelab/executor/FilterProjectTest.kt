package com.dbenginelab.executor

import com.dbenginelab.catalog.ColumnDef
import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.catalog.Type
import com.dbenginelab.storage.BufferPool
import com.dbenginelab.storage.PagedFile
import com.dbenginelab.table.TableHeap
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

class FilterProjectTest {

    private val schema = TableSchema(
        name = "users",
        columns = listOf(
            ColumnDef("id", Type.BIGINT, nullable = false),
            ColumnDef("name", Type.STRING, nullable = false),
            ColumnDef("age", Type.INT, nullable = true),
        ),
    )

    @Test
    fun `Filter age greater than 28`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("f.data").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                InsertOp(heap).insertMany(listOf(
                    Tuple(schema, listOf(1L, "Alice", 30)),
                    Tuple(schema, listOf(2L, "Bob", 25)),
                    Tuple(schema, listOf(3L, "Charlie", 35)),
                    Tuple(schema, listOf(4L, "Dave", null)),
                ))
                val filtered = Filter(SeqScan(heap), Expression.gt(Expression.col("age"), Expression.lit(28)))
                val r = filtered.iterator().toList()
                assertEquals(2, r.size)
                assertEquals(setOf(30, 35), r.map { it.get("age") }.toSet())
            }
        }
    }

    @Test
    fun `Project id and name only`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("p.data").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                InsertOp(heap).insertOne(Tuple(schema, listOf(1L, "A", 10)))
                val p = Project(SeqScan(heap), listOf("id", "name"))
                val r = p.iterator().toList()
                assertEquals(2, p.outputSchema.columnCount)
                assertEquals("A", r[0].get("name"))
            }
        }
    }

    @Test
    fun `Filter then Project chain`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("fp.data").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                InsertOp(heap).insertMany(listOf(
                    Tuple(schema, listOf(1L, "A", 10)),
                    Tuple(schema, listOf(2L, "B", 20)),
                ))
                val chain = Project(
                    Filter(SeqScan(heap), Expression.eq(Expression.col("id"), Expression.lit(2L))),
                    listOf("name"),
                )
                val r = chain.iterator().toList()
                assertEquals(1, r.size)
                assertEquals("B", r[0].get("name"))
            }
        }
    }
}
