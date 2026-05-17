package com.dbenginelab.wal

import com.dbenginelab.catalog.Tuple
import com.dbenginelab.table.TableHeap
import java.util.concurrent.atomic.AtomicLong

class Transaction internal constructor(
    val id: Long,
    private val logManager: LogManager,
) {
    private enum class State { ACTIVE, COMMITTED, ABORTED }
    private var state: State = State.ACTIVE
    private val pending: MutableList<Pair<TableHeap, Tuple>> = mutableListOf()

    init { logManager.append(LogRecord.BeginTx(id)) }

    fun insert(tableName: String, heap: TableHeap, tuple: Tuple) {
        check(state == State.ACTIVE) { "transaction $id not active (state=$state)" }
        require(tuple.schema == heap.schema) { "tuple schema does not match heap" }
        logManager.append(LogRecord.InsertRow(id, tableName, tuple.encode()))
        pending.add(heap to tuple)
    }

    fun commit() {
        check(state == State.ACTIVE)
        logManager.append(LogRecord.CommitTx(id))
        logManager.sync()
        for ((heap, tuple) in pending) heap.insert(tuple)
        pending.clear()
        state = State.COMMITTED
    }

    fun abort() {
        check(state == State.ACTIVE)
        logManager.append(LogRecord.AbortTx(id))
        pending.clear()
        state = State.ABORTED
    }

    fun isCommitted(): Boolean = state == State.COMMITTED
    fun isAborted(): Boolean = state == State.ABORTED
}

class TransactionManager(private val logManager: LogManager) {
    private val nextTxId = AtomicLong(1)
    fun begin(): Transaction = Transaction(nextTxId.getAndIncrement(), logManager)
}
