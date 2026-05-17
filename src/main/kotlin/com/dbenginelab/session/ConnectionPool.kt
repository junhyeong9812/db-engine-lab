package com.dbenginelab.session

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

class ConnectionPool(private val capacity: Int = DEFAULT_CAPACITY) : Closeable {

    private val sessions: ConcurrentHashMap<Long, Session> = ConcurrentHashMap()
    private val executor = Executors.newFixedThreadPool(capacity)

    fun openSession(user: String): Session {
        require(sessions.size < capacity) { "pool full (capacity=$capacity)" }
        val s = Session(Session.nextId(), user)
        sessions[s.id] = s
        return s
    }

    fun closeSession(sessionId: Long) { sessions.remove(sessionId) }
    fun activeSessions(): Int = sessions.size

    fun <T> submit(sessionId: Long, task: (Session) -> T): Future<T> {
        val s = sessions[sessionId] ?: throw IllegalStateException("session $sessionId not found")
        return executor.submit<T> { task(s.also { it.touch() }) }
    }

    override fun close() { executor.shutdown(); sessions.clear() }

    companion object { const val DEFAULT_CAPACITY: Int = 16 }
}
