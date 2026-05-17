package com.dbenginelab.wal

/**
 * Stage 8 보강 (X5): Checkpoint manager — periodic snapshot of (LSN, active txs).
 *
 * Records a Checkpoint log record. Backup/Recovery use it as a consistent
 * starting point.
 */
class CheckpointManager(private val logManager: LogManager) {

    /**
     * Create a checkpoint at the current LSN with the given active tx ids.
     * Returns the LSN assigned to the checkpoint record itself.
     */
    fun checkpoint(activeTxs: Set<Long>): Long {
        val ckLsn = logManager.currentLsn()
        val lsn = logManager.append(LogRecord.Checkpoint(ckLsn, activeTxs.toList()))
        logManager.sync()
        return lsn
    }

    /** Find the most recent checkpoint in the log. Returns null if none. */
    fun lastCheckpoint(): LogRecord.Checkpoint? {
        var last: LogRecord.Checkpoint? = null
        logManager.replay { rec ->
            if (rec is LogRecord.Checkpoint) last = rec
        }
        return last
    }
}
