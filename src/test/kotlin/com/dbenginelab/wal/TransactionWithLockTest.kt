package com.dbenginelab.wal

import com.dbenginelab.catalog.ColumnDef
import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.catalog.Type
import com.dbenginelab.lock.LockConflict
import com.dbenginelab.lock.LockManager
import com.dbenginelab.storage.BufferPool
import com.dbenginelab.storage.PagedFile
import com.dbenginelab.table.TableHeap
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransactionWithLockTest {
    private val schema = TableSchema(
        name = "users",
        columns = listOf(
            ColumnDef("id", Type.BIGINT, nullable = false),
            ColumnDef("name", Type.STRING, nullable = false),
        ),
    )

    @Test
    fun `commit 시 lock 자동 release`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("w.log").toString()
        val data = tempDir.resolve("u.data").toString()
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val lockMgr = LockManager()
                val tm = TransactionWithLockManager(lm, lockMgr)
                val tx = tm.begin()
                tx.insert("users", heap, Tuple(schema, listOf(1L, "A")))
                assertTrue(lockMgr.isHeld(tx.id, "users"))
                tx.commit()
                assertEquals(false, lockMgr.isHeld(tx.id, "users"))
            }}
        }
    }

    @Test
    fun `abort 시 lock 자동 release`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("w.log").toString()
        val data = tempDir.resolve("u.data").toString()
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val lockMgr = LockManager()
                val tm = TransactionWithLockManager(lm, lockMgr)
                val tx = tm.begin()
                tx.insert("users", heap, Tuple(schema, listOf(1L, "A")))
                tx.abort()
                assertEquals(false, lockMgr.isHeld(tx.id, "users"))
            }}
        }
    }

    @Test
    fun `두 tx 같은 table EXCLUSIVE 충돌`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("w.log").toString()
        val data = tempDir.resolve("u.data").toString()
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val lockMgr = LockManager()
                val tm = TransactionWithLockManager(lm, lockMgr)
                val tx1 = tm.begin()
                tx1.insert("users", heap, Tuple(schema, listOf(1L, "A")))
                val tx2 = tm.begin()
                assertThrows<LockConflict> {
                    tx2.insert("users", heap, Tuple(schema, listOf(2L, "B")))
                }
                tx1.commit()
                // tx2는 abort 가능, lock 없으니 commit도 가능.
                tx2.insert("users", heap, Tuple(schema, listOf(2L, "B")))
                tx2.commit()
                assertEquals(2, heap.rowCount())
            }}
        }
    }
}
