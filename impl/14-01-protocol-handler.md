# impl/14-01 — ProtocolHandler (Wire+Auth+DbEngine 통합, C6)

> **검증**: ProtocolHandlerTest 5 PASSED.
> 작성 파일:
> - 신규: `src/main/kotlin/com/dbenginelab/wire/ProtocolHandler.kt`
> - 신규: `src/test/kotlin/com/dbenginelab/wire/ProtocolHandlerTest.kt`

## 0. 참조
PostgreSQL protocol state machine (단순화).

## 1. invariant
- Startup → Auth 성공/실패 따라 Authenticated/AuthFailed.
- Auth 전 Query → Error("not authenticated").
- Query → DbEngine.execute → 결과를 wire Message로 변환.
- Terminate → 세션 close.

## 2. 핵심 결정
- per-connection state — `sessionId: Long?` 멤버.
- Event 패턴 (`ConnectionEvent` sealed) — 호출자가 어떻게 응답할지 결정.
- DataRow에 String 변환 — wire는 string-based (단순화).

## 3. 구현 코드 — 한 줄 한 줄

작성 위치: `src/main/kotlin/com/dbenginelab/wire/ProtocolHandler.kt`

```kotlin
package com.dbenginelab.wire                                       // wire 패키지에 protocol handler 추가

import com.dbenginelab.auth.AuthManager                             // 인증 (단계 15)
import com.dbenginelab.engine.DbEngine                              // SQL 실행 (C2 보강)
import com.dbenginelab.session.ConnectionPool                       // 세션 관리 (단계 13)

class ProtocolHandler(                                              // Q: 왜 class? object 안 되나?
    private val auth: AuthManager,                                  // per-connection state 필요 (sessionId)
    private val pool: ConnectionPool,
    private val engine: DbEngine,
) {
    // <details><summary>A</summary>
    // object는 singleton — 다중 connection 처리에 부적합. 각 connection이 자기 ProtocolHandler 인스턴스 가짐.
    // </details>

    sealed class ConnectionEvent {                                  // 호출자에게 알리는 사건들 (sealed)
        data class Authenticated(val sessionId: Long) : ConnectionEvent()      // 인증 성공
        data class AuthFailed(val reason: String) : ConnectionEvent()          // 인증 실패
        data class QueryResponse(val messages: List<Message>) : ConnectionEvent()  // 쿼리 응답 (여러 Message)
        object Closed : ConnectionEvent()                            // 세션 close
    }

    private var sessionId: Long? = null                             // Q: @Volatile 필요? thread-safe?
    // <details><summary>A</summary>
    // ProtocolHandler 인스턴스는 단일 connection 전용 — single thread 가정. @Volatile 불필요.
    // </details>

    fun handle(msg: Message): ConnectionEvent {                     // 모든 client message 진입점
        return when (msg) {                                         // sealed Message — exhaustive
            is Message.Startup -> handleStartup(msg)
            is Message.Query -> handleQuery(msg)
            Message.Terminate -> handleTerminate()
            else -> ConnectionEvent.QueryResponse(listOf(Message.Error("unexpected client message: ${msg::class.simpleName}")))
            // Q: 왜 else 필요? sealed인데?
            // <details><summary>A</summary>
            // server-side messages (AuthOk, RowDescription 등)이 client 메시지로 잘못 도착했을 때 방어.
            // </details>
        }
    }

    private fun handleStartup(msg: Message.Startup): ConnectionEvent {
        if (!auth.authenticate(msg.user, msg.password)) {           // 인증 실패 시
            return ConnectionEvent.AuthFailed("authentication failed for user ${msg.user}")
        }
        val session = pool.openSession(msg.user)                    // ConnectionPool에 새 세션 등록
        sessionId = session.id                                      // 멤버에 저장 (이후 Query 추적용)
        return ConnectionEvent.Authenticated(session.id)            // 호출자가 AuthOk 메시지 보내야
    }

    private fun handleQuery(msg: Message.Query): ConnectionEvent {
        val sid = sessionId                                         // 인증 전 Query는 거부
            ?: return ConnectionEvent.QueryResponse(listOf(Message.Error("not authenticated")))
        return try {
            val result = engine.execute(msg.sql)                    // DbEngine 호출 (C2 facade)
            ConnectionEvent.QueryResponse(toMessages(result))       // QueryResult → Message 변환
        } catch (e: Exception) {                                    // Q: 왜 모든 Exception catch?
            ConnectionEvent.QueryResponse(listOf(Message.Error(e.message ?: e::class.simpleName ?: "error")))
        }
        // <details><summary>A</summary>
        // SQL 에러 (syntax/runtime) 가 protocol 끊지 않게 catch → wire Error 메시지로 client에 보고.
        // </details>
    }

    private fun handleTerminate(): ConnectionEvent {
        sessionId?.let { pool.closeSession(it) }                    // null-safe close
        sessionId = null
        return ConnectionEvent.Closed
    }

    private fun toMessages(result: DbEngine.QueryResult): List<Message> {  // QueryResult → 다중 Message
        return when (result) {                                       // sealed exhaustive
            is DbEngine.QueryResult.Rows -> buildList {              // SELECT 결과
                add(Message.RowDescription(result.columns))          // 컬럼 이름 먼저
                for (row in result.rows) {
                    add(Message.DataRow(row.map { it?.toString() }))  // 각 row를 DataRow로 (null→null)
                }
                add(Message.CommandComplete("SELECT ${result.rows.size}"))  // PostgreSQL 호환 tag
            }
            is DbEngine.QueryResult.Updated -> listOf(Message.CommandComplete("INSERT ${result.count}"))
            is DbEngine.QueryResult.Created -> listOf(Message.CommandComplete("CREATE TABLE ${result.tableName}"))
            is DbEngine.QueryResult.Dropped -> listOf(Message.CommandComplete("DROP TABLE ${result.tableName}"))
        }
    }
}
```

## 4. 검증 테스트 (5 PASSED)
- Startup correct credentials → Authenticated
- Startup wrong password → AuthFailed
- Query without auth → Error
- Full handshake CREATE+INSERT+SELECT round-trip
- Terminate closes session

## 5. 깨뜨릴 과제
- 동시 Startup 두 번 → 세션 두 개 생기는지?
- Long-running Query 도중 Terminate → 어떻게? (현재는 처리 안 함)
- SSL/TLS 추가하려면 어디?

## 6. 다음 한계
- 진짜 TCP 서버 (Socket accept loop) 없음 — 단계 14-02에서 추가 가능.
