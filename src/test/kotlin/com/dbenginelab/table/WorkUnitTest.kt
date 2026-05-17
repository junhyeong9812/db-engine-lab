package com.dbenginelab.table

import com.dbenginelab.catalog.ColumnDef
import com.dbenginelab.catalog.Constraint
import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.catalog.Type
import com.dbenginelab.storage.BufferPool
import com.dbenginelab.storage.PagedFile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

class WorkUnitTest {
    private val schema = TableSchema(
        name = "users",
        columns = listOf(
            ColumnDef("id", Type.BIGINT, nullable = false),
            ColumnDef("name", Type.STRING, nullable = false),
        ),
        constraints = listOf(Constraint.PrimaryKey(listOf("id"))),
    )

    @Test
    fun `commit 후 heap 반영`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("w.data").toString()
        PagedFile(path).use { pf -> BufferPool(pf, 16).use { bp ->
            val heap = TableHeap(schema, pf, bp)
            val w = WorkUnit()
            w.insert(heap, Tuple(schema, listOf(1L, "Alice")))
            w.insert(heap, Tuple(schema, listOf(2L, "Bob")))
            assertEquals(0, heap.rowCount())
            w.commit()
            assertEquals(2, heap.rowCount())
        }}
    }

    @Test
    fun `abort 후 heap 변경 없음`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("w.data").toString()
        PagedFile(path).use { pf -> BufferPool(pf, 16).use { bp ->
            val heap = TableHeap(schema, pf, bp)
            val w = WorkUnit()
            w.insert(heap, Tuple(schema, listOf(1L, "Alice")))
            w.abort()
            assertEquals(0, heap.rowCount())
        }}
    }

    @Test
    fun `commit 시 constraint 위반이면 all-or-nothing`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("w.data").toString()
        PagedFile(path).use { pf -> BufferPool(pf, 16).use { bp ->
            val heap = TableHeap(schema, pf, bp)
            heap.insert(Tuple(schema, listOf(99L, "preexisting")))
            val w = WorkUnit()
            w.insert(heap, Tuple(schema, listOf(1L, "Alice")))
            w.insert(heap, Tuple(schema, listOf(99L, "dup")))
            assertThrows<ConstraintViolation> {
                w.commit(mapOf(heap to ConstraintValidator(heap)))
            }
            assertEquals(1, heap.rowCount())
        }}
    }

    @Test
    fun `committed 후 추가 insert 거부`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("w.data").toString()
        PagedFile(path).use { pf -> BufferPool(pf, 16).use { bp ->
            val heap = TableHeap(schema, pf, bp)
            val w = WorkUnit()
            w.commit()
            assertThrows<IllegalStateException> { w.insert(heap, Tuple(schema, listOf(1L, "X"))) }
        }}
    }
}
