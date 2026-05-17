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

/**
 * Stage 8 보강 (X6): 실패 주입 — crash mid-commit, partial write, randomized.
 */
class CrashSimulationTest {
    private val schema = TableSchema(
        name = "users",
        columns = listOf(
            ColumnDef("id", Type.BIGINT, nullable = false),
            ColumnDef("name", Type.STRING, nullable = false),
        ),
    )

    @Test
    fun `crash 직전 commit 미실행 — tx 데이터 미반영`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("w.log").toString()
        val data = tempDir.resolve("u.data").toString()
        // tx1 insert + commit, tx2 insert (commit 안 함 — crash 시뮬레이션)
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val tm = TransactionManager(lm)
                val tx1 = tm.begin()
                tx1.insert("users", heap, Tuple(schema, listOf(1L, "committed")))
                tx1.commit()
                val tx2 = tm.begin()
                tx2.insert("users", heap, Tuple(schema, listOf(2L, "lost-on-crash")))
                // no commit, no abort — "crash"
            }}
        }
        // recovery — fresh heap
        java.io.File(data).delete()
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val stats = Recovery(lm) { name -> if (name == "users") heap else null }.recover()
                assertEquals(1, stats.txCommitted)  // tx1만 commit
                assertEquals(0, stats.txAborted)    // tx2는 ABORT 안 적힘 (crash)
                assertEquals(1, stats.rowsReapplied)
                assertEquals(1, heap.rowCount())
            }}
        }
    }

    @Test
    fun `WAL 파일 끝 partial bytes — replay 시 무시`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("w.log").toString()
        LogManager(log).use { lm ->
            lm.append(LogRecord.BeginTx(1L))
            lm.append(LogRecord.CommitTx(1L))
            lm.sync()
        }
        // 손상된 trailing bytes 주입
        java.io.RandomAccessFile(log, "rw").use { raf ->
            raf.seek(raf.length())
            raf.writeInt(5000); raf.write(byteArrayOf(1, 2, 3))  // 5000 bytes claimed, only 3 written
        }
        LogManager(log).use { lm ->
            val records = mutableListOf<LogRecord>()
            lm.replay { records.add(it) }
            assertEquals(2, records.size)  // BeginTx + CommitTx만, partial 무시
        }
    }

    @Test
    fun `randomized tx 시퀀스 - commit abort 섞어도 일관`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("w.log").toString()
        val data = tempDir.resolve("u.data").toString()
        val rnd = kotlin.random.Random(42)
        val commits = mutableListOf<Long>()
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val tm = TransactionManager(lm)
                for (i in 1..30) {
                    val tx = tm.begin()
                    tx.insert("users", heap, Tuple(schema, listOf(i.toLong(), "r$i")))
                    if (rnd.nextBoolean()) {
                        tx.commit(); commits.add(i.toLong())
                    } else {
                        tx.abort()
                    }
                }
            }}
        }
        java.io.File(data).delete()
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                Recovery(lm) { if (it == "users") heap else null }.recover()
                assertEquals(commits.size, heap.rowCount())
                val recovered = heap.scan().map { it.get("id") as Long }.toSet()
                assertEquals(commits.toSet(), recovered)
            }}
        }
    }
}
