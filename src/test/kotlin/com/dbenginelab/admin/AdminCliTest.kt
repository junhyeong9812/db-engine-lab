package com.dbenginelab.admin

import com.dbenginelab.auth.AuthManager
import com.dbenginelab.auth.Privilege
import com.dbenginelab.auth.Role
import com.dbenginelab.catalog.Catalog
import com.dbenginelab.catalog.ColumnDef
import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Type
import com.dbenginelab.metrics.MetricsRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminCliTest {
    @Test fun `admin commands`(@TempDir tempDir: Path) {
        val catalog = Catalog(tempDir.resolve("c.meta").toString())
        catalog.registerTable(TableSchema("users", listOf(ColumnDef("id", Type.BIGINT, nullable = false))))
        val auth = AuthManager().apply { addRole(Role("admin", setOf(Privilege.SELECT, Privilege.INSERT))) }
        val metrics = MetricsRegistry().apply { incCounter("q", 5) }
        val cli = AdminCli(catalog, auth, metrics)
        assertEquals("users", cli.execute("list-tables"))
        assertTrue(cli.execute("show-metrics").contains("counter.q=5"))
        assertTrue(cli.execute("create-user alice pw admin").contains("created"))
        assertEquals("yes", cli.execute("grant-check alice SELECT"))
        assertEquals("no", cli.execute("grant-check alice DROP"))
    }
}
