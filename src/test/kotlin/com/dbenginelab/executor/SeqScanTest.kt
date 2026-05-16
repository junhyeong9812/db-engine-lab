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

class SeqScanTest {

    private fun userSchema() = TableSchema(
        name = "users",
        columns = listOf(
            ColumnDef("id", Type.BIGINT, nullable = false),
            ColumnDef("name", Type.STRING, nullable = false),
            ColumnDef("age", Type.INT, nullable = true),
        ),
    )

    @Test
    fun `insert 후 SeqScan으로 같은 tuple 반환`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("users.data").toString()
        val schema = userSchema()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val ins = InsertOp(heap)
                ins.insertOne(Tuple(schema, listOf(1L, "Alice", 30)))
                ins.insertOne(Tuple(schema, listOf(2L, "Bob", null)))
                ins.insertOne(Tuple(schema, listOf(3L, "Charlie", 25)))

                val scan = SeqScan(heap)
                val tuples = scan.iterator().toList()
                assertEquals(3, tuples.size)
                assertEquals(1L, tuples[0].get("id"))
                assertEquals("Bob", tuples[1].get("name"))
                assertEquals(null, tuples[1].get("age"))
                assertEquals(25, tuples[2].get("age"))
            }
        }
    }

    @Test
    fun `대량 insert로 multi-page heap 검증 (rowCount + scan)`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("big.data").toString()
        val schema = userSchema()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 32).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val n = 500
                val tuples = (1..n).map { Tuple(schema, listOf(it.toLong(), "name-$it", it)) }
                InsertOp(heap).insertMany(tuples)
                assertEquals(n, heap.rowCount())

                val scanned = SeqScan(heap).iterator().toList()
                assertEquals(n, scanned.size)
                for (i in 0 until n) {
                    assertEquals((i + 1).toLong(), scanned[i].get("id"))
                }
            }
        }
    }

    @Test
    fun `reopen 후에도 데이터 보존`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("persist.data").toString()
        val schema = userSchema()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                InsertOp(heap).insertOne(Tuple(schema, listOf(42L, "kept", 100)))
                heap.close()
            }
        }
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val t = SeqScan(heap).iterator().toList()
                assertEquals(1, t.size)
                assertEquals(42L, t[0].get("id"))
                assertEquals("kept", t[0].get("name"))
            }
        }
    }
}
