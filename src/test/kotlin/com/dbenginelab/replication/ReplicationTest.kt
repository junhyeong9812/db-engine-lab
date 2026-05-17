package com.dbenginelab.replication

import com.dbenginelab.wal.LogManager
import com.dbenginelab.wal.LogRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

class ReplicationTest {
    @Test fun `primary WAL 내용이 replica로`(@TempDir tempDir: Path) {
        val pPath = tempDir.resolve("p.log").toString()
        val rPath = tempDir.resolve("r.log").toString()
        LogManager(pPath).use { p ->
            p.append(LogRecord.BeginTx(1)); p.append(LogRecord.CommitTx(1)); p.sync()
        }
        val records = LogManager(pPath).use { WalSender(it).stream() }
        LogManager(rPath).use { r -> WalReceiver(r).apply(records) }
        val replicated = mutableListOf<LogRecord>()
        LogManager(rPath).use { it.replay { rec -> replicated.add(rec) } }
        assertEquals(2, replicated.size)
    }
}
