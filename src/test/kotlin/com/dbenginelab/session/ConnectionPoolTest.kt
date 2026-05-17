package com.dbenginelab.session

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

class ConnectionPoolTest {
    @Test fun `openSession 후 활성 세션 수`() {
        ConnectionPool(4).use { pool ->
            val s1 = pool.openSession("alice")
            pool.openSession("bob")
            assertEquals(2, pool.activeSessions())
            pool.closeSession(s1.id)
            assertEquals(1, pool.activeSessions())
        }
    }

    @Test fun `capacity 초과 거부`() {
        ConnectionPool(2).use { pool ->
            pool.openSession("a"); pool.openSession("b")
            assertThrows<IllegalArgumentException> { pool.openSession("c") }
        }
    }

    @Test fun `여러 task 병렬 실행`() {
        ConnectionPool(4).use { pool ->
            val s = pool.openSession("u")
            val counter = AtomicInteger(0)
            val futures = (1..10).map { pool.submit(s.id) { _ -> counter.incrementAndGet() } }
            futures.forEach { it.get() }
            assertEquals(10, counter.get())
        }
    }
}
