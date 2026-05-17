package com.dbenginelab.wal

import com.dbenginelab.catalog.Tuple
import com.dbenginelab.lock.LockManager
import com.dbenginelab.table.TableHeap

/**
 * Stage 9 보강 (C3): Transaction + LockManager 통합.
 *
 * WAL Transaction이 lock acquire/releaseAll을 자동 처리. 사용자가 직접 lock
 * 호출 안 해도 됨. Strict 2PL — commit/abort 시 releaseAll.
 *
 * Lock granularity: table 단위 (resource = tableName). Row 단위는 stage 9+ 후속.
 */
class TransactionWithLock internal constructor(
    val id: Long,
    private val logManager: LogManager,
    private val lockManager: LockManager,
) {
    private enum class State { ACTIVE, COMMITTED, ABORTED }
    private var state: State = State.ACTIVE
    private val pending: MutableList<Pair<TableHeap, Tuple>> = mutableListOf()
    private val acquiredResources: MutableSet<String> = mutableSetOf()

    init { logManager.append(LogRecord.BeginTx(id)) }

    fun read(tableName: String): TableHeap.() -> Sequence<Tuple> {
        check(state == State.ACTIVE)
        acquireIfNeeded(tableName, LockManager.Mode.SHARED)
        return { scan() }
    }

    fun insert(tableName: String, heap: TableHeap, tuple: Tuple) {
        check(state == State.ACTIVE)
        require(tuple.schema == heap.schema)
        acquireIfNeeded(tableName, LockManager.Mode.EXCLUSIVE)
        logManager.append(LogRecord.InsertRow(id, tableName, tuple.encode()))
        pending.add(heap to tuple)
    }

    fun commit() {
        check(state == State.ACTIVE)
        logManager.append(LogRecord.CommitTx(id))
        logManager.sync()
        for ((heap, tuple) in pending) heap.insert(tuple)
        pending.clear()
        lockManager.releaseAll(id)
        acquiredResources.clear()
        state = State.COMMITTED
    }

    fun abort() {
        check(state == State.ACTIVE)
        logManager.append(LogRecord.AbortTx(id))
        pending.clear()
        lockManager.releaseAll(id)
        acquiredResources.clear()
        state = State.ABORTED
    }

    fun isCommitted(): Boolean = state == State.COMMITTED
    fun isAborted(): Boolean = state == State.ABORTED

    private fun acquireIfNeeded(resource: String, mode: LockManager.Mode) {
        // Strict 2PL: lock은 한 번 잡으면 commit/abort까지 유지.
        // EXCLUSIVE 요청은 이미 SHARED 있어도 acquire (upgrade) — LockManager가 처리.
        lockManager.acquire(id, resource, mode)
        acquiredResources.add(resource)
    }
}

class TransactionWithLockManager(
    private val logManager: LogManager,
    private val lockManager: LockManager,
) {
    private val nextTxId = java.util.concurrent.atomic.AtomicLong(1)
    fun begin(): TransactionWithLock = TransactionWithLock(nextTxId.getAndIncrement(), logManager, lockManager)
}
