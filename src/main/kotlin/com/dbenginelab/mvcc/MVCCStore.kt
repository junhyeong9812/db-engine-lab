package com.dbenginelab.mvcc

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class MVCCStore<K, V> {

    data class Version<V>(
        val value: V?,
        val xidStart: Long,
        @Volatile var xidEnd: Long = INFINITY,
    ) {
        companion object { const val INFINITY: Long = Long.MAX_VALUE }
    }

    data class Snapshot(val xid: Long, val active: Set<Long>) {
        fun isVisible(v: Version<*>): Boolean {
            if (v.xidStart > xid) return false
            if (v.xidStart in active) return false
            if (v.xidEnd == Version.INFINITY) return true
            if (v.xidEnd > xid) return true
            if (v.xidEnd in active) return true
            return false
        }
    }

    private val chains: ConcurrentHashMap<K, MutableList<Version<V>>> = ConcurrentHashMap()

    @Synchronized
    fun insert(key: K, value: V, xid: Long) {
        val chain = chains.getOrPut(key) { mutableListOf() }
        chain.firstOrNull { it.xidEnd == Version.INFINITY }?.xidEnd = xid
        chain.add(Version(value, xid))
    }

    @Synchronized
    fun delete(key: K, xid: Long) {
        val chain = chains[key] ?: return
        chain.firstOrNull { it.xidEnd == Version.INFINITY }?.xidEnd = xid
        chain.add(Version(null, xid))
    }

    @Synchronized
    fun get(key: K, snapshot: Snapshot): V? {
        val chain = chains[key] ?: return null
        for (i in chain.indices.reversed()) {
            val v = chain[i]
            if (snapshot.isVisible(v)) return v.value
        }
        return null
    }

    @Synchronized
    fun versionCount(key: K): Int = chains[key]?.size ?: 0
}

class SnapshotProvider {
    private val nextXid = AtomicLong(1)
    private val active: MutableSet<Long> = Collections.synchronizedSet(mutableSetOf())

    @Synchronized
    fun begin(): MVCCStore.Snapshot {
        val xid = nextXid.getAndIncrement()
        active.add(xid)
        return MVCCStore.Snapshot(xid, active.toSet() - xid)
    }

    @Synchronized
    fun commit(snapshot: MVCCStore.Snapshot) { active.remove(snapshot.xid) }
}
