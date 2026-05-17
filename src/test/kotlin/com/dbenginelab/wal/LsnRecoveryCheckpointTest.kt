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
import kotlin.test.assertTrue

class LsnRecoveryCheckpointTest {
    private val schema = TableSchema(
        name = "users",
        columns = listOf(
            ColumnDef("id", Type.BIGINT, nullable = false),
            ColumnDef("name", Type.STRING, nullable = false),
        ),
    )

    @Test
    fun `LSN은 monotonic + reopen 복원`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("w.log").toString()
        LogManager(log).use { lm ->
            val l1 = lm.append(LogRecord.BeginTx(1L))
            val l2 = lm.append(LogRecord.CommitTx(1L))
            assertEquals(1L, l1); assertEquals(2L, l2)
            assertEquals(2L, lm.currentLsn())
            lm.sync()
        }
        LogManager(log).use { lm ->
            assertEquals(2L, lm.currentLsn())
            val l3 = lm.append(LogRecord.BeginTx(2L))
            assertEquals(3L, l3)
        }
    }

    @Test
    fun `IdempotentRecovery는 두 번 호출해도 중복 apply 안 함`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("w.log").toString()
        val data = tempDir.resolve("u.data").toString()
        val meta = tempDir.resolve("recovery.meta").toString()
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val tm = TransactionManager(lm)
                val tx = tm.begin()
                tx.insert("users", heap, Tuple(schema, listOf(1L, "A")))
                tx.commit()
            }}
        }
        java.io.File(data).delete()
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val s1 = IdempotentRecovery(lm, meta) { if (it == "users") heap else null }.recover()
                assertEquals(1, s1.rowsReapplied)
                assertEquals(1, heap.rowCount())

                // 두 번째 recovery — 이미 적용된 LSN 모두 skip.
                val s2 = IdempotentRecovery(lm, meta) { if (it == "users") heap else null }.recover()
                assertEquals(0, s2.rowsReapplied)
                assertTrue(s2.skippedAlreadyApplied > 0)
                assertEquals(1, heap.rowCount())  // 중복 apply 없음
            }}
        }
    }

    @Test
    fun `Checkpoint record 작성 후 lastCheckpoint로 조회`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("w.log").toString()
        LogManager(log).use { lm ->
            lm.append(LogRecord.BeginTx(1L))
            lm.append(LogRecord.CommitTx(1L))
            val cm = CheckpointManager(lm)
            val ckLsn = cm.checkpoint(setOf(2L, 3L))
            assertTrue(ckLsn > 0)
        }
        LogManager(log).use { lm ->
            val ck = CheckpointManager(lm).lastCheckpoint()!!
            assertEquals(listOf(2L, 3L), ck.activeTxs.sorted())
        }
    }
}
