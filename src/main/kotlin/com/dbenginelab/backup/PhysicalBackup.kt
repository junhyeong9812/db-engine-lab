package com.dbenginelab.backup

import com.dbenginelab.wal.CheckpointManager
import com.dbenginelab.wal.LogManager
import java.io.File

/**
 * Stage 16 보강 (X5): Physical backup with checkpoint snapshot.
 *
 * Procedure:
 *  1. Issue a Checkpoint via CheckpointManager (Backup의 consistent 시점).
 *  2. Copy data directory files into backupDir.
 *  3. Copy WAL file as well (for redo since checkpoint).
 *  4. Write `backup.meta` with the checkpoint LSN.
 *
 * Restore:
 *  1. Copy backup files into target dataDir.
 *  2. Open LogManager → run IdempotentRecovery (LSN > checkpointLsn 만 apply).
 */
class PhysicalBackup(
    private val dataDir: String,
    private val walPath: String,
    private val backupDir: String,
) {

    data class BackupInfo(val checkpointLsn: Long, val files: List<String>)

    fun snapshot(checkpointManager: CheckpointManager, activeTxs: Set<Long> = emptySet()): BackupInfo {
        val ckLsn = checkpointManager.checkpoint(activeTxs)
        val backupDirFile = File(backupDir)
        backupDirFile.mkdirs()

        val dataFiles = File(dataDir).listFiles()?.filter { it.isFile } ?: emptyList()
        val copied = mutableListOf<String>()
        for (f in dataFiles) {
            val dst = File(backupDirFile, f.name)
            f.copyTo(dst, overwrite = true)
            copied.add(f.name)
        }
        // Also snapshot the WAL.
        val walFile = File(walPath)
        if (walFile.exists()) {
            walFile.copyTo(File(backupDirFile, walFile.name), overwrite = true)
            copied.add(walFile.name)
        }
        File(backupDirFile, "backup.meta").writeText(ckLsn.toString())
        return BackupInfo(ckLsn, copied)
    }

    fun restore(targetDataDir: String) {
        val backupDirFile = File(backupDir)
        require(backupDirFile.isDirectory) { "backup dir not found: $backupDir" }
        val target = File(targetDataDir)
        target.mkdirs()
        for (f in backupDirFile.listFiles().orEmpty()) {
            if (f.name == "backup.meta") continue
            f.copyTo(File(target, f.name), overwrite = true)
        }
    }

    fun readCheckpointLsn(): Long {
        return File(backupDir, "backup.meta").readText().trim().toLong()
    }
}
