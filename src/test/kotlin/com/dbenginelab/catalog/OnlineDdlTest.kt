package com.dbenginelab.catalog

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

class OnlineDdlTest {
    @Test fun `ADD COLUMN`(@TempDir tempDir: Path) {
        val catalog = Catalog(tempDir.resolve("c.meta").toString())
        catalog.registerTable(TableSchema("t", listOf(ColumnDef("id", Type.BIGINT, nullable = false))))
        val newSchema = OnlineDdl.addColumn(catalog, "t", ColumnDef("note", Type.STRING, nullable = true))
        assertEquals(2, newSchema.columnCount)
    }
    @Test fun `NOT NULL 추가 거부`(@TempDir tempDir: Path) {
        val catalog = Catalog(tempDir.resolve("c.meta").toString())
        catalog.registerTable(TableSchema("t", listOf(ColumnDef("id", Type.BIGINT, nullable = false))))
        assertThrows<IllegalArgumentException> {
            OnlineDdl.addColumn(catalog, "t", ColumnDef("x", Type.INT, nullable = false))
        }
    }
}
