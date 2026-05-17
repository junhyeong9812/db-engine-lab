package com.dbenginelab.wal

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

class WalRecoveryTest {
    private val schema = TableSchema(
        name = "users",
        columns = listOf(
            ColumnDef("id", Type.BIGINT, nullable = false),
            ColumnDef("name", Type.STRING, nullable = false),
        ),
    )

    @Test
    fun `commit 후 heap 반영`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("wal.log").toString()
        val data = tempDir.resolve("u.data").toString()
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val tx = TransactionManager(lm).begin()
                tx.insert("users", heap, Tuple(schema, listOf(1L, "A")))
                tx.commit()
                assertEquals(1, heap.rowCount())
            }}
        }
    }

    @Test
    fun `abort 시 heap 변경 없음`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("wal.log").toString()
        val data = tempDir.resolve("u.data").toString()
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val tx = TransactionManager(lm).begin()
                tx.insert("users", heap, Tuple(schema, listOf(1L, "A")))
                tx.abort()
                assertEquals(0, heap.rowCount())
            }}
        }
    }

    @Test
    fun `crash 후 recovery로 committed 복원`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("wal.log").toString()
        val data = tempDir.resolve("u.data").toString()
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val tx = TransactionManager(lm).begin()
                tx.insert("users", heap, Tuple(schema, listOf(100L, "X")))
                tx.commit()
            }}
        }
        java.io.File(data).delete()
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val stats = Recovery(lm) { name -> if (name == "users") heap else null }.recover()
                assertEquals(1, stats.txCommitted)
                assertEquals(1, stats.rowsReapplied)
                assertEquals(1, heap.rowCount())
            }}
        }
    }

    @Test
    fun `aborted, incomplete tx는 recovery 시 무시`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("wal.log").toString()
        val data = tempDir.resolve("u.data").toString()
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val tm = TransactionManager(lm)
                val tx1 = tm.begin(); tx1.insert("users", heap, Tuple(schema, listOf(1L, "kept"))); tx1.commit()
                val tx2 = tm.begin(); tx2.insert("users", heap, Tuple(schema, listOf(2L, "aborted"))); tx2.abort()
                val tx3 = tm.begin(); tx3.insert("users", heap, Tuple(schema, listOf(3L, "incomplete")))
            }}
        }
        java.io.File(data).delete()
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val stats = Recovery(lm) { name -> if (name == "users") heap else null }.recover()
                assertEquals(1, stats.txCommitted)
                assertEquals(1, stats.txAborted)
                assertEquals(1, stats.rowsReapplied)
                assertEquals(1, heap.rowCount())
            }}
        }
    }

    @Test
    fun `partial trailing record는 EOF로 안전 처리`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("wal.log").toString()
        LogManager(log).use { lm ->
            lm.append(LogRecord.BeginTx(1L)); lm.sync()
        }
        java.io.RandomAccessFile(log, "rw").use { raf ->
            raf.seek(raf.length())
            raf.writeInt(999); raf.write(byteArrayOf(0x01, 0x02))
        }
        LogManager(log).use { lm ->
            val records = mutableListOf<LogRecord>()
            lm.replay { records += it }
            assertEquals(1, records.size)
            assertEquals(LogRecord.BeginTx(1L), records[0])
        }
    }
}
