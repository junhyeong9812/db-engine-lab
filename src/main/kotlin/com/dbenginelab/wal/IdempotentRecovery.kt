package com.dbenginelab.wal

import com.dbenginelab.catalog.Tuple
import com.dbenginelab.table.TableHeap
import java.io.File

/**
 * Stage 8 보강 (C5): LSN-based idempotent recovery.
 *
 * 각 데이터 디렉토리에 `recovery.meta` 파일을 두고 마지막으로 적용된 LSN을 기록.
 * Recovery 시 LSN > lastAppliedLsn 인 record만 apply → 중복 회피.
 *
 * Format: 단일 long 값 (lastAppliedLsn).
 */
class IdempotentRecovery(
    private val logManager: LogManager,
    private val metaPath: String,
    private val heapLookup: (String) -> TableHeap?,
) {

    data class Stats(
        val skippedAlreadyApplied: Int,
        val rowsReapplied: Int,
        val newLastAppliedLsn: Long,
    )

    fun recover(): Stats {
        val lastApplied = readLastAppliedLsn()
        val perTxInserts = mutableMapOf<Long, MutableList<Triple<Long, String, ByteArray>>>()
        val committed = mutableSetOf<Long>()
        val aborted = mutableSetOf<Long>()
        var maxLsn = lastApplied
        var skipped = 0

        logManager.replayWithLsn { lsn, rec ->
            if (lsn <= lastApplied) {
                skipped++
                return@replayWithLsn
            }
            maxLsn = maxOf(maxLsn, lsn)
            when (rec) {
                is LogRecord.BeginTx -> perTxInserts[rec.txId] = mutableListOf()
                is LogRecord.InsertRow ->
                    perTxInserts.getOrPut(rec.txId) { mutableListOf() }
                        .add(Triple(lsn, rec.tableName, rec.tupleBytes))
                is LogRecord.CommitTx -> committed.add(rec.txId)
                is LogRecord.AbortTx -> aborted.add(rec.txId)
                is LogRecord.Checkpoint -> { /* ignore for now — used by Backup */ }
            }
        }

        var rowsReapplied = 0
        for ((txId, inserts) in perTxInserts) {
            if (txId !in committed || txId in aborted) continue
            for ((_, tableName, tupleBytes) in inserts) {
                val heap = heapLookup(tableName) ?: continue
                heap.insert(Tuple.decode(heap.schema, tupleBytes))
                rowsReapplied++
            }
        }

        writeLastAppliedLsn(maxLsn)
        return Stats(skipped, rowsReapplied, maxLsn)
    }

    private fun readLastAppliedLsn(): Long {
        val file = File(metaPath)
        if (!file.exists()) return 0L
        return file.readText().trim().toLong()
    }

    private fun writeLastAppliedLsn(lsn: Long) {
        val file = File(metaPath)
        file.parentFile?.mkdirs()
        file.writeText(lsn.toString())
    }
}
