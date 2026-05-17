package com.dbenginelab.wire

import com.dbenginelab.auth.AuthManager
import com.dbenginelab.engine.DbEngine
import com.dbenginelab.session.ConnectionPool

/**
 * Stage 14+15 보강 (C6): Wire protocol + Auth + DbEngine 통합 handler.
 *
 * Per-connection state machine:
 *   1. Receive Startup(user, password) → AuthManager.authenticate
 *      - success: ConnectionPool.openSession + reply AuthOk(sessionId)
 *      - fail: reply Error("authentication failed") + Terminate
 *   2. Receive Query(sql) → DbEngine.execute → reply
 *      - QueryResult.Rows → RowDescription + DataRow * N + CommandComplete("SELECT N")
 *      - QueryResult.Updated → CommandComplete("INSERT 1")
 *      - QueryResult.Created/Dropped → CommandComplete("CREATE TABLE")
 *      - exception → Error(message)
 *   3. Receive Terminate → close session
 */
class ProtocolHandler(
    private val auth: AuthManager,
    private val pool: ConnectionPool,
    private val engine: DbEngine,
) {
    sealed class ConnectionEvent {
        data class Authenticated(val sessionId: Long) : ConnectionEvent()
        data class AuthFailed(val reason: String) : ConnectionEvent()
        data class QueryResponse(val messages: List<Message>) : ConnectionEvent()
        object Closed : ConnectionEvent()
    }

    private var sessionId: Long? = null

    fun handle(msg: Message): ConnectionEvent {
        return when (msg) {
            is Message.Startup -> handleStartup(msg)
            is Message.Query -> handleQuery(msg)
            Message.Terminate -> handleTerminate()
            else -> ConnectionEvent.QueryResponse(listOf(Message.Error("unexpected client message: ${msg::class.simpleName}")))
        }
    }

    private fun handleStartup(msg: Message.Startup): ConnectionEvent {
        if (!auth.authenticate(msg.user, msg.password)) {
            return ConnectionEvent.AuthFailed("authentication failed for user ${msg.user}")
        }
        val session = pool.openSession(msg.user)
        sessionId = session.id
        return ConnectionEvent.Authenticated(session.id)
    }

    private fun handleQuery(msg: Message.Query): ConnectionEvent {
        val sid = sessionId
            ?: return ConnectionEvent.QueryResponse(listOf(Message.Error("not authenticated")))
        return try {
            val result = engine.execute(msg.sql)
            ConnectionEvent.QueryResponse(toMessages(result))
        } catch (e: Exception) {
            ConnectionEvent.QueryResponse(listOf(Message.Error(e.message ?: e::class.simpleName ?: "error")))
        }
    }

    private fun handleTerminate(): ConnectionEvent {
        sessionId?.let { pool.closeSession(it) }
        sessionId = null
        return ConnectionEvent.Closed
    }

    private fun toMessages(result: DbEngine.QueryResult): List<Message> {
        return when (result) {
            is DbEngine.QueryResult.Rows -> buildList {
                add(Message.RowDescription(result.columns))
                for (row in result.rows) {
                    add(Message.DataRow(row.map { it?.toString() }))
                }
                add(Message.CommandComplete("SELECT ${result.rows.size}"))
            }
            is DbEngine.QueryResult.Updated -> listOf(Message.CommandComplete("INSERT ${result.count}"))
            is DbEngine.QueryResult.Created -> listOf(Message.CommandComplete("CREATE TABLE ${result.tableName}"))
            is DbEngine.QueryResult.Dropped -> listOf(Message.CommandComplete("DROP TABLE ${result.tableName}"))
        }
    }
}
