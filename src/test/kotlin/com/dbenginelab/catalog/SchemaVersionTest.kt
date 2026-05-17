package com.dbenginelab.catalog

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

class SchemaVersionTest {

    @Test
    fun `record schema changes and reopen`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("schema.log").toString()
        SchemaVersionLog(path).apply {
            record("CREATE TABLE users (id BIGINT NOT NULL)")
            record("ADD COLUMN name STRING")
        }
        val reopened = SchemaVersionLog(path)
        val history = reopened.history()
        assertEquals(2, history.size)
        assertEquals(1, history[0].version)
        assertEquals(2, history[1].version)
        assertEquals(2, reopened.currentVersion())
    }

    @Test
    fun `empty log version 0`(@TempDir tempDir: Path) {
        val log = SchemaVersionLog(tempDir.resolve("s.log").toString())
        assertEquals(0, log.currentVersion())
    }
}
