package com.dbenginelab.session

import java.util.concurrent.atomic.AtomicLong

class Session(val id: Long, val user: String) {
    @Volatile var currentTxId: Long? = null
    @Volatile var lastError: String? = null
    @Volatile var lastAccess: Long = System.currentTimeMillis()
    fun touch() { lastAccess = System.currentTimeMillis() }

    companion object {
        private val nextId = AtomicLong(1)
        fun nextId(): Long = nextId.getAndIncrement()
    }
}
