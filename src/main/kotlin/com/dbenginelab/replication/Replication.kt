package com.dbenginelab.replication

import com.dbenginelab.wal.LogManager
import com.dbenginelab.wal.LogRecord

class WalSender(private val primary: LogManager) {
    fun stream(): List<LogRecord> {
        val out = mutableListOf<LogRecord>()
        primary.replay { out.add(it) }
        return out
    }
}

class WalReceiver(private val replica: LogManager) {
    fun apply(records: List<LogRecord>) {
        for (r in records) replica.append(r)
        replica.sync()
    }
}

class HashShardRouter(private val shardCount: Int) {
    init { require(shardCount > 0) }
    fun shardOf(key: Any): Int = (key.hashCode() and Int.MAX_VALUE) % shardCount
}
