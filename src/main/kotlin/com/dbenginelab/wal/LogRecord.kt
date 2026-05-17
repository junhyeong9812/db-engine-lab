package com.dbenginelab.wal

sealed class LogRecord {
    abstract val txId: Long

    data class BeginTx(override val txId: Long) : LogRecord()
    data class InsertRow(
        override val txId: Long,
        val tableName: String,
        val tupleBytes: ByteArray,
    ) : LogRecord() {
        override fun equals(other: Any?): Boolean {
            if (other !is InsertRow) return false
            return txId == other.txId && tableName == other.tableName &&
                tupleBytes.contentEquals(other.tupleBytes)
        }
        override fun hashCode(): Int =
            (31 * (31 * txId.hashCode() + tableName.hashCode())) + tupleBytes.contentHashCode()
    }
    data class CommitTx(override val txId: Long) : LogRecord()
    data class AbortTx(override val txId: Long) : LogRecord()

    /**
     * Stage 8 보강 (X5): Checkpoint record.
     * Records the LSN snapshot + active transactions at checkpoint time.
     * Recovery / backup uses this as a consistent starting point.
     */
    data class Checkpoint(val checkpointLsn: Long, val activeTxs: List<Long>) : LogRecord() {
        override val txId: Long = 0L
    }

    companion object {
        const val TAG_BEGIN: Byte = 0
        const val TAG_INSERT: Byte = 1
        const val TAG_COMMIT: Byte = 2
        const val TAG_ABORT: Byte = 3
        const val TAG_CHECKPOINT: Byte = 4
    }
}
