package com.dbenginelab.wal

sealed class LogRecord {
    abstract val txId: Long

    data class BeginTx(override val txId: Long): LogRecord()
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

    data class Checkpoint(val checkpointLsn: Long, val activeTxs: List<Long>) : LogRecord() {
        override val txId: Long = 0L
    }
//    Lsn -> Log Sequence Number(로그 순차 번호)의 약자.
//    WAL에서 각 로그 레코드에 부여되는 단조 증가하는 고유 번호, 로그의 어느 지점인지 가리키는 주소 -> 즉 체크포인트가 찍힌 시점의 로그 위치
//    이 지점부터 로그를 재생하면 되므로 전체 로그를 읽지않아도 된다.

    companion object {
        const val TAG_BEGIN: Byte = 0
        const val TAG_INSERT: Byte = 1
        const val TAG_COMMIT: Byte = 2
        const val TAG_ABORT: Byte = 3
        const val TAG_CHECKPOINT: Byte = 4
    }
}