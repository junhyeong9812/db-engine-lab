package com.dbenginelab.wal

import java.io.Closeable
import java.io.EOFException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Stage 8 보강 (X2): LSN(Log Sequence Number) 발급.
 *
 * Each appended record gets a monotonic Long LSN (sequential count = LSN).
 * append() returns the assigned LSN. currentLsn() reflects the latest issued.
 *
 * Backward-compatible: legacy `replay(handler: (LogRecord) -> Unit)` 유지 + 신규
 * `replayWithLsn(handler: (Long, LogRecord) -> Unit)` 추가.
 *
 * Reopen 시 LSN은 파일 전체 record 수로 복원 (init).
 */
class LogManager(path: String) : Closeable {

    private val file = RandomAccessFile(path, "rw")
    @Volatile private var nextLsn: Long = 1

    init {
        // Count existing records to restore nextLsn.
        file.seek(0)
        var count = 0L
        while (file.filePointer < file.length()) {
            try {
                val len = file.readInt()
                file.skipBytes(len)
                count++
            } catch (_: EOFException) { break }
        }
        nextLsn = count + 1
        file.seek(file.length())
    }

    /** Append a record and return its LSN. */
    fun append(record: LogRecord): Long {
        val payload = encode(record)
        file.seek(file.length())
        file.writeInt(payload.size)
        file.write(payload)
        val lsn = nextLsn
        nextLsn++
        return lsn
    }

    fun sync() { file.fd.sync() }

    fun currentLsn(): Long = nextLsn - 1

    /** Legacy replay — record only, LSN discarded. */
    fun replay(handler: (LogRecord) -> Unit) {
        replayWithLsn { _, rec -> handler(rec) }
    }

    /** LSN-aware replay — handler receives (lsn, record). */
    fun replayWithLsn(handler: (Long, LogRecord) -> Unit) {
        file.seek(0)
        var lsn = 0L
        while (file.filePointer < file.length()) {
            try {
                val len = file.readInt()
                val bytes = ByteArray(len)
                file.readFully(bytes)
                lsn++
                handler(lsn, decode(bytes))
            } catch (_: EOFException) {
                break
            }
        }
        file.seek(file.length())
    }

    override fun close() { file.close() }

    private fun encode(record: LogRecord): ByteArray {
        return when (record) {
            is LogRecord.BeginTx -> {
                val buf = ByteBuffer.allocate(1 + 8)
                buf.put(LogRecord.TAG_BEGIN); buf.putLong(record.txId)
                buf.array()
            }
            is LogRecord.CommitTx -> {
                val buf = ByteBuffer.allocate(1 + 8)
                buf.put(LogRecord.TAG_COMMIT); buf.putLong(record.txId)
                buf.array()
            }
            is LogRecord.AbortTx -> {
                val buf = ByteBuffer.allocate(1 + 8)
                buf.put(LogRecord.TAG_ABORT); buf.putLong(record.txId)
                buf.array()
            }
            is LogRecord.InsertRow -> {
                val name = record.tableName.toByteArray(StandardCharsets.UTF_8)
                val buf = ByteBuffer.allocate(1 + 8 + 4 + name.size + 4 + record.tupleBytes.size)
                buf.put(LogRecord.TAG_INSERT)
                buf.putLong(record.txId)
                buf.putInt(name.size); buf.put(name)
                buf.putInt(record.tupleBytes.size); buf.put(record.tupleBytes)
                buf.array()
            }
            is LogRecord.Checkpoint -> {
                val activeTxsBuf = ByteBuffer.allocate(4 + record.activeTxs.size * 8)
                activeTxsBuf.putInt(record.activeTxs.size)
                for (tx in record.activeTxs) activeTxsBuf.putLong(tx)
                val buf = ByteBuffer.allocate(1 + 8 + 8 + activeTxsBuf.position())
                buf.put(LogRecord.TAG_CHECKPOINT)
                buf.putLong(0L)  // txId placeholder
                buf.putLong(record.checkpointLsn)
                buf.put(activeTxsBuf.array(), 0, activeTxsBuf.position())
                buf.array()
            }
        }
    }

    private fun decode(bytes: ByteArray): LogRecord {
        val buf = ByteBuffer.wrap(bytes)
        val tag = buf.get()
        val txId = buf.long
        return when (tag) {
            LogRecord.TAG_BEGIN -> LogRecord.BeginTx(txId)
            LogRecord.TAG_COMMIT -> LogRecord.CommitTx(txId)
            LogRecord.TAG_ABORT -> LogRecord.AbortTx(txId)
            LogRecord.TAG_INSERT -> {
                val nameLen = buf.int
                val nameBytes = ByteArray(nameLen); buf.get(nameBytes)
                val tupleLen = buf.int
                val tupleBytes = ByteArray(tupleLen); buf.get(tupleBytes)
                LogRecord.InsertRow(txId, String(nameBytes, StandardCharsets.UTF_8), tupleBytes)
            }
            LogRecord.TAG_CHECKPOINT -> {
                val ckLsn = buf.long
                val count = buf.int
                val active = LongArray(count) { buf.long }.toList()
                LogRecord.Checkpoint(ckLsn, active)
            }
            else -> error("unknown log tag: $tag")
        }
    }
}
