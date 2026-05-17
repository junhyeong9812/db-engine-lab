package com.dbenginelab.mvcc

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
import kotlin.test.assertNull

class MVCCTableHeapTest {
    private val schema = TableSchema(
        name = "users",
        columns = listOf(
            ColumnDef("id", Type.BIGINT, nullable = false),
            ColumnDef("name", Type.STRING, nullable = false),
        ),
    )

    @Test
    fun `MVCC TableHeap insert + snapshot read`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("u.data").toString()
        PagedFile(path).use { pf -> BufferPool(pf, 16).use { bp ->
            val heap = TableHeap(schema, pf, bp)
            val mvccHeap = MVCCTableHeap(heap, "id")
            val sp = SnapshotProvider()

            val tx1 = sp.begin()
            mvccHeap.insert(Tuple(schema, listOf(1L, "Alice")), tx1.xid)
            sp.commit(tx1)

            val tx2 = sp.begin()
            val found = mvccHeap.read(1L, tx2)!!
            assertEquals("Alice", found.get("name"))
        }}
    }

    @Test
    fun `delete 후 옛 snapshot은 보고 새 snapshot은 못 봄`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("u.data").toString()
        PagedFile(path).use { pf -> BufferPool(pf, 16).use { bp ->
            val heap = TableHeap(schema, pf, bp)
            val mvccHeap = MVCCTableHeap(heap, "id")
            val sp = SnapshotProvider()

            val tx1 = sp.begin()
            mvccHeap.insert(Tuple(schema, listOf(1L, "A")), tx1.xid); sp.commit(tx1)
            val older = sp.begin()  // delete 전 snapshot
            val tx2 = sp.begin()
            mvccHeap.delete(1L, tx2.xid); sp.commit(tx2)

            assertEquals("A", mvccHeap.read(1L, older)?.get("name"))
            val newer = sp.begin()
            assertNull(mvccHeap.read(1L, newer))
        }}
    }
}
