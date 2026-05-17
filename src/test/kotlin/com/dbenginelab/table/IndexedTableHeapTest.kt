package com.dbenginelab.table

import com.dbenginelab.catalog.ColumnDef
import com.dbenginelab.catalog.Constraint
import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.catalog.Type
import com.dbenginelab.storage.BTreeIndex
import com.dbenginelab.storage.BufferPool
import com.dbenginelab.storage.PagedFile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IndexedTableHeapTest {
    private val schema = TableSchema(
        name = "users",
        columns = listOf(
            ColumnDef("id", Type.BIGINT, nullable = false),
            ColumnDef("name", Type.STRING, nullable = false),
        ),
        constraints = listOf(Constraint.PrimaryKey(listOf("id"))),
    )

    @Test
    fun `insert 후 findByKey로 빠르게 찾음`(@TempDir tempDir: Path) {
        val heapPath = tempDir.resolve("h.data").toString()
        val idxPath = tempDir.resolve("h.idx").toString()
        PagedFile(heapPath).use { hpf -> BufferPool(hpf, 16).use { hbp ->
            PagedFile(idxPath).use { ipf -> BufferPool(ipf, 16).use { ibp ->
                val heap = TableHeap(schema, hpf, hbp)
                val idx = BTreeIndex(ipf, ibp)
                val ith = IndexedTableHeap(heap, idx, "id")
                ith.insert(Tuple(schema, listOf(1L, "Alice")))
                ith.insert(Tuple(schema, listOf(2L, "Bob")))
                ith.insert(Tuple(schema, listOf(3L, "Charlie")))

                val found = ith.findByKey(2L)!!
                assertEquals("Bob", found.get("name"))
                assertNull(ith.findByKey(999L))
                assertEquals(3, ith.rowCount())
            }}
        }}
    }

    @Test
    fun `PK 중복 insert는 ConstraintViolation - heap 변경 없음`(@TempDir tempDir: Path) {
        val heapPath = tempDir.resolve("h.data").toString()
        val idxPath = tempDir.resolve("h.idx").toString()
        PagedFile(heapPath).use { hpf -> BufferPool(hpf, 16).use { hbp ->
            PagedFile(idxPath).use { ipf -> BufferPool(ipf, 16).use { ibp ->
                val heap = TableHeap(schema, hpf, hbp)
                val idx = BTreeIndex(ipf, ibp)
                val ith = IndexedTableHeap(heap, idx, "id")
                ith.insert(Tuple(schema, listOf(1L, "Alice")))
                assertThrows<ConstraintViolation> {
                    ith.insert(Tuple(schema, listOf(1L, "Bob")))
                }
                assertEquals(1, ith.rowCount())  // heap 변경 없음
            }}
        }}
    }

    @Test
    fun `nullable PK 거부`(@TempDir tempDir: Path) {
        val nullableSchema = TableSchema(
            name = "x",
            columns = listOf(ColumnDef("id", Type.BIGINT, nullable = true)),
        )
        val heapPath = tempDir.resolve("h.data").toString()
        val idxPath = tempDir.resolve("h.idx").toString()
        PagedFile(heapPath).use { hpf -> BufferPool(hpf, 16).use { hbp ->
            PagedFile(idxPath).use { ipf -> BufferPool(ipf, 16).use { ibp ->
                val heap = TableHeap(nullableSchema, hpf, hbp)
                val idx = BTreeIndex(ipf, ibp)
                assertThrows<IllegalArgumentException> {
                    IndexedTableHeap(heap, idx, "id")
                }
            }}
        }}
    }
}
