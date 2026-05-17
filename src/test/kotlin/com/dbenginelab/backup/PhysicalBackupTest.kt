package com.dbenginelab.backup

import com.dbenginelab.catalog.ColumnDef
import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.catalog.Type
import com.dbenginelab.storage.BufferPool
import com.dbenginelab.storage.PagedFile
import com.dbenginelab.table.TableHeap
import com.dbenginelab.wal.CheckpointManager
import com.dbenginelab.wal.LogManager
import com.dbenginelab.wal.TransactionManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhysicalBackupTest {
    private val schema = TableSchema(
        name = "users",
        columns = listOf(
            ColumnDef("id", Type.BIGINT, nullable = false),
            ColumnDef("name", Type.STRING, nullable = false),
        ),
    )

    @Test
    fun `snapshot 후 restore로 데이터 복원`(@TempDir tempDir: Path) {
        val dataDir = tempDir.resolve("data").toString()
        val backupDir = tempDir.resolve("backup").toString()
        val targetDir = tempDir.resolve("target").toString()
        java.io.File(dataDir).mkdirs()
        val walPath = "$dataDir/w.log"
        val dataPath = "$dataDir/users.data"

        LogManager(walPath).use { lm ->
            PagedFile(dataPath).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val tx = TransactionManager(lm).begin()
                tx.insert("users", heap, Tuple(schema, listOf(1L, "A")))
                tx.insert("users", heap, Tuple(schema, listOf(2L, "B")))
                tx.commit()
            }}
            val backup = PhysicalBackup(dataDir, walPath, backupDir)
            val info = backup.snapshot(CheckpointManager(lm))
            assertTrue(info.checkpointLsn > 0)
            assertTrue(info.files.any { it == "users.data" })
            assertTrue(info.files.any { it == "w.log" })
        }

        PhysicalBackup(dataDir, walPath, backupDir).restore(targetDir)
        val restoredHeap = "$targetDir/users.data"
        PagedFile(restoredHeap).use { pf -> BufferPool(pf, 16).use { bp ->
            val heap = TableHeap(schema, pf, bp)
            assertEquals(2, heap.rowCount())
        }}
    }
}
