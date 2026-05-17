package com.dbenginelab.backup

import com.dbenginelab.catalog.Catalog
import com.dbenginelab.catalog.ColumnDef
import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.catalog.Type
import com.dbenginelab.storage.BufferPool
import com.dbenginelab.storage.PagedFile
import com.dbenginelab.table.TableHeap
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertTrue

class BackupTest {
    @Test fun `dump restore SQL 라인`(@TempDir tempDir: Path) {
        val schema = TableSchema("users", listOf(
            ColumnDef("id", Type.BIGINT, nullable = false),
            ColumnDef("name", Type.STRING, nullable = true),
        ))
        val catPath = tempDir.resolve("c.meta").toString()
        val dataPath = tempDir.resolve("u.data").toString()
        val dumpPath = tempDir.resolve("dump.sql").toString()
        val catalog = Catalog(catPath).apply { registerTable(schema) }
        PagedFile(dataPath).use { pf -> BufferPool(pf, 16).use { bp ->
            val heap = TableHeap(schema, pf, bp)
            heap.insert(Tuple(schema, listOf(1L, "Alice")))
            heap.insert(Tuple(schema, listOf(2L, null)))
            LogicalBackup().dump(catalog, mapOf("users" to heap), dumpPath)
        }}
        val stmts = LogicalBackup().restore(dumpPath)
        assertTrue(stmts.any { it.startsWith("CREATE TABLE") })
        assertTrue(stmts.any { it.contains("INSERT INTO users VALUES (1") })
        assertTrue(stmts.any { it.contains("NULL") })
    }
}
