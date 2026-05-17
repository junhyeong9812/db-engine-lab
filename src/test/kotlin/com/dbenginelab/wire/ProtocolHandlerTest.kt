package com.dbenginelab.wire

import com.dbenginelab.auth.AuthManager
import com.dbenginelab.auth.Privilege
import com.dbenginelab.auth.Role
import com.dbenginelab.engine.DbEngine
import com.dbenginelab.session.ConnectionPool
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProtocolHandlerTest {

    private fun build(tempDir: Path): Triple<ProtocolHandler, ConnectionPool, DbEngine> {
        val auth = AuthManager().apply {
            addRole(Role("user", setOf(Privilege.SELECT, Privilege.INSERT, Privilege.CREATE, Privilege.DROP)))
            addUser("alice", "pw", setOf("user"))
        }
        val pool = ConnectionPool(8)
        val engine = DbEngine(tempDir.toString())
        return Triple(ProtocolHandler(auth, pool, engine), pool, engine)
    }

    @Test
    fun `Startup with correct credentials returns AuthOk via Authenticated event`(@TempDir tempDir: Path) {
        val (handler, pool, engine) = build(tempDir)
        pool.use { engine.use { _ ->
            val ev = handler.handle(Message.Startup("alice", "pw"))
            assertTrue(ev is ProtocolHandler.ConnectionEvent.Authenticated)
        }}
    }

    @Test
    fun `Startup with wrong password returns AuthFailed`(@TempDir tempDir: Path) {
        val (handler, pool, engine) = build(tempDir)
        pool.use { engine.use { _ ->
            val ev = handler.handle(Message.Startup("alice", "wrong"))
            assertTrue(ev is ProtocolHandler.ConnectionEvent.AuthFailed)
        }}
    }

    @Test
    fun `Query without auth returns Error`(@TempDir tempDir: Path) {
        val (handler, pool, engine) = build(tempDir)
        pool.use { engine.use { _ ->
            val ev = handler.handle(Message.Query("SELECT 1")) as ProtocolHandler.ConnectionEvent.QueryResponse
            assertTrue(ev.messages[0] is Message.Error)
        }}
    }

    @Test
    fun `Full handshake CREATE INSERT SELECT round-trip`(@TempDir tempDir: Path) {
        val (handler, pool, engine) = build(tempDir)
        pool.use { engine.use { _ ->
            handler.handle(Message.Startup("alice", "pw"))
            handler.handle(Message.Query("CREATE TABLE t (id BIGINT NOT NULL, name STRING NOT NULL, PRIMARY KEY (id))"))
            handler.handle(Message.Query("INSERT INTO t VALUES (1, 'A')"))
            handler.handle(Message.Query("INSERT INTO t VALUES (2, 'B')"))
            val ev = handler.handle(Message.Query("SELECT * FROM t")) as ProtocolHandler.ConnectionEvent.QueryResponse
            assertTrue(ev.messages[0] is Message.RowDescription)
            val rowMsgs = ev.messages.filterIsInstance<Message.DataRow>()
            assertEquals(2, rowMsgs.size)
            assertTrue(ev.messages.last() is Message.CommandComplete)
        }}
    }

    @Test
    fun `Terminate closes session`(@TempDir tempDir: Path) {
        val (handler, pool, engine) = build(tempDir)
        pool.use { engine.use { _ ->
            handler.handle(Message.Startup("alice", "pw"))
            assertEquals(1, pool.activeSessions())
            handler.handle(Message.Terminate)
            assertEquals(0, pool.activeSessions())
        }}
    }
}
