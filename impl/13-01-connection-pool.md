# impl/13-01 — Session + ConnectionPool (한 줄 한 줄)

> **검증**: ConnectionPoolTest 3 PASSED. **Phase A 13단계 완료.**
> 작성 파일:
> - 신규 패키지: `src/main/kotlin/com/dbenginelab/session/`
> - 신규: Session.kt, ConnectionPool.kt
> - 신규 테스트: ConnectionPoolTest.kt

## 0. 참조
HikariCP pattern. PostgreSQL pg_stat_activity.

## 1. invariant
- capacity 내 openSession OK, 초과 거부.
- submit한 task는 thread pool 실행.

## 2. Session.kt + ConnectionPool.kt — 한 줄 한 줄

```kotlin
// Session.kt
package com.dbenginelab.session                                      // 신규 session 패키지
import java.util.concurrent.atomic.AtomicLong

class Session(val id: Long, val user: String) {
    @Volatile var currentTxId: Long? = null                          // Q: @Volatile 왜?
    @Volatile var lastError: String? = null
    @Volatile var lastAccess: Long = System.currentTimeMillis()
    fun touch() { lastAccess = System.currentTimeMillis() }
    // <details><summary>A</summary>
    // 여러 thread가 같은 session 멤버 읽고 씀 — @Volatile로 visibility 보장.
    // </details>

    companion object {
        private val nextId = AtomicLong(1)
        fun nextId(): Long = nextId.getAndIncrement()
    }
}
```

```kotlin
// ConnectionPool.kt
package com.dbenginelab.session
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

class ConnectionPool(private val capacity: Int = DEFAULT_CAPACITY) : Closeable {

    private val sessions: ConcurrentHashMap<Long, Session> = ConcurrentHashMap()
    private val executor = Executors.newFixedThreadPool(capacity)    // Q: coroutine 안 쓰는 이유?
    // <details><summary>A</summary>
    // constraints.md — DB 학습 영역에서 coroutine 배제. Java concurrency 모델 유지 (lock 등 학습 변수 줄임).
    // </details>

    fun openSession(user: String): Session {
        // Q: capacity 체크가 atomic 아닌데 race?
        require(sessions.size < capacity) { "pool full (capacity=$capacity)" }
        val s = Session(Session.nextId(), user)
        sessions[s.id] = s
        return s
        // <details><summary>A</summary>
        // size + put 사이 race로 capacity 약간 초과 가능. 학습 단순화. 진짜는 AtomicInteger counter.
        // </details>
    }

    fun closeSession(sessionId: Long) { sessions.remove(sessionId) }
    fun activeSessions(): Int = sessions.size

    fun <T> submit(sessionId: Long, task: (Session) -> T): Future<T> {
        val s = sessions[sessionId] ?: throw IllegalStateException("session $sessionId not found")
        return executor.submit<T> { task(s.also { it.touch() }) }    // touch() 후 task
    }

    override fun close() {
        executor.shutdown()
        sessions.clear()
    }

    companion object { const val DEFAULT_CAPACITY: Int = 16 }
}
```

## 3. 검증 (3 PASSED)
- openSession 후 활성 세션 수
- capacity 초과 거부
- 여러 task 병렬 실행

## 4. 깨뜨릴 과제
- capacity 동시 초과 race — AtomicInteger로 고치면?
- session timeout (lastAccess 기반) 추가?
- Java Virtual Threads (Project Loom) 적용?

## 5. 다음 한계
- 외부 접근 없음 → Phase B / 단계 14 Wire Protocol.
