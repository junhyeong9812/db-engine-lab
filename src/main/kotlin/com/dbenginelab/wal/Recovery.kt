package com.dbenginelab.wal

import com.dbenginelab.catalog.Tuple
import com.dbenginelab.table.TableHeap

class Recovery(
    private val logManager: LogManager,
    private val heapLookup: (String) -> TableHeap?,
) {
    data class Stats(val txObserved: Int, val txCommitted: Int, val txAborted: Int, val rowsReapplied: Int)

    fun recover(): Stats {
        val perTxInserts = mutableMapOf<Long, MutableList<Pair<String, ByteArray>>>()
        val committed = mutableSetOf<Long>()
        val aborted = mutableSetOf<Long>()

        logManager.replay { rec ->
            when (rec) {
                is LogRecord.BeginTx -> perTxInserts[rec.txId] = mutableListOf()
                is LogRecord.InsertRow ->
                    perTxInserts.getOrPut(rec.txId) { mutableListOf() }
                        .add(rec.tableName to rec.tupleBytes)
                is LogRecord.CommitTx -> committed.add(rec.txId)
                is LogRecord.AbortTx -> aborted.add(rec.txId)
                is LogRecord.Checkpoint -> { /* legacy Recovery ignores checkpoints */ }
            }
        }

        var rowsReapplied = 0
        for ((txId, inserts) in perTxInserts) {
            if (txId !in committed || txId in aborted) continue
            for ((tableName, tupleBytes) in inserts) {
                val heap = heapLookup(tableName) ?: continue
                val tuple = Tuple.decode(heap.schema, tupleBytes)
                heap.insert(tuple)
                rowsReapplied++
            }
        }

        return Stats(perTxInserts.size, committed.size, aborted.size, rowsReapplied)
    }
}
