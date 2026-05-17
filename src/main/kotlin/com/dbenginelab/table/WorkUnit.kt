package com.dbenginelab.table

import com.dbenginelab.catalog.Tuple

class WorkUnit {
    private enum class State { ACTIVE, COMMITTED, ABORTED }
    private var state: State = State.ACTIVE
    private val pending: MutableList<Pair<TableHeap, Tuple>> = mutableListOf()

    fun insert(heap: TableHeap, tuple: Tuple) {
        check(state == State.ACTIVE) { "WorkUnit not active (state=$state)" }
        pending.add(heap to tuple)
    }

    fun pendingCount(): Int = pending.size

    fun commit(validators: Map<TableHeap, ConstraintValidator> = emptyMap()) {
        check(state == State.ACTIVE)
        for ((heap, tuple) in pending) validators[heap]?.validateInsert(tuple)
        for ((heap, tuple) in pending) heap.insert(tuple)
        pending.clear()
        state = State.COMMITTED
    }

    fun abort() {
        check(state == State.ACTIVE)
        pending.clear()
        state = State.ABORTED
    }

    fun isActive(): Boolean = state == State.ACTIVE
    fun isCommitted(): Boolean = state == State.COMMITTED
    fun isAborted(): Boolean = state == State.ABORTED
}
