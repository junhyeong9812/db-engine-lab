package com.dbenginelab.wal

import java.util.concurrent.atomic.AtomicLong

class TransactionManager(private val logManager: LogManager) {
    private val nextTxId = AtomicLong(logManager.maxTxId() + 1)
    fun begin(): Transaction = Transaction(nextTxId.getAndIncrement(), logManager)
}