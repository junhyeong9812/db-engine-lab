package com.dbenginelab.lock

class LockManager {
    enum class Mode { SHARED, EXCLUSIVE }
    private data class Holder(val txId: Long, val mode: Mode)
    private val holders: MutableMap<String, MutableList<Holder>> = mutableMapOf()

    @Synchronized
    fun acquire(txId: Long, resource: String, mode: Mode) {
        val current = holders.getOrPut(resource) { mutableListOf() }
        val mine = current.firstOrNull { it.txId == txId }
        if (mine != null) {
            if (mine.mode == Mode.EXCLUSIVE) return
            if (mine.mode == Mode.SHARED && mode == Mode.SHARED) return
            if (current.any { it.txId != txId }) {
                throw LockConflict(txId, resource, mode, current.toList())
            }
            current.remove(mine)
            current.add(Holder(txId, Mode.EXCLUSIVE))
            return
        }
        when (mode) {
            Mode.SHARED -> {
                if (current.any { it.mode == Mode.EXCLUSIVE }) {
                    throw LockConflict(txId, resource, mode, current.toList())
                }
                current.add(Holder(txId, Mode.SHARED))
            }
            Mode.EXCLUSIVE -> {
                if (current.isNotEmpty()) {
                    throw LockConflict(txId, resource, mode, current.toList())
                }
                current.add(Holder(txId, Mode.EXCLUSIVE))
            }
        }
    }

    @Synchronized
    fun releaseAll(txId: Long) {
        val empty = mutableListOf<String>()
        for ((res, list) in holders) {
            list.removeAll { it.txId == txId }
            if (list.isEmpty()) empty.add(res)
        }
        for (res in empty) holders.remove(res)
    }

    @Synchronized
    fun isHeld(txId: Long, resource: String): Boolean =
        holders[resource]?.any { it.txId == txId } ?: false

    @Synchronized
    fun holderCount(resource: String): Int = holders[resource]?.size ?: 0
}

class LockConflict(
    val requesterTxId: Long, val resource: String, val requestedMode: LockManager.Mode, val currentHolders: List<Any>,
) : RuntimeException("tx $requesterTxId cannot acquire $requestedMode on $resource (held by $currentHolders)")
