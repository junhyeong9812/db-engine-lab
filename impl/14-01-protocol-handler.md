# impl/14-01 — ProtocolHandler (Wire + Auth + DbEngine 통합, 보강 C6)

> **종류**: 보강형
> **상위 단계**: `docs/stages/14-wire-protocol.md`
> **코드 정본**: git `5505edc` — "complete: 21 stages + 12 보강 (120/120 tests)"
> **이 세션의 범위**: 들어온 메시지에 **응답하는 주체**를 만든다 — 인증 상태를 들고 질의를 `DbEngine`에 넘긴다.
> **작성 파일**:
> - 신규: `src/main/kotlin/com/dbenginelab/wire/ProtocolHandler.kt`
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/wire/ProtocolHandlerTest.kt`
> **검증**: `ProtocolHandlerTest` 5 PASSED
> **예상 타이핑 시간**: 40분

---

## 0. 보강 동기

`Protocol`(메시지 형식)·`Auth`(인증)·`DbEngine`(실행)이 각각 있는데 **셋을 잇는 것이 없다.** 실제 서버가 하는 일은 정확히 그 연결이다 — 접속이 열리면 인증 상태를 들고 있다가, 질의가 오면 실행하고, 끊기면 정리한다.

## 1. 만족시킬 invariant

- **CI-1**: `Startup` → 인증 성공/실패에 따라 `Authenticated` / `AuthFailed`.
- **CI-2**: 인증 전에 `Query`가 오면 `Error("not authenticated")`.
- **CI-3**: `Query` → `DbEngine.execute` → 결과를 wire `Message`로 변환.
- **CI-4**: `Terminate` → 세션 종료.

**CI-2가 이 세션에서 가장 중요하다.** 인증을 건너뛴 질의가 통과하면 나머지 보안 장치는 전부 무의미해진다.

## 2. 핵심 결정

- **접속별 상태를 멤버로 둔다** (`sessionId: Long?`). 핸들러 인스턴스 하나 = 접속 하나.
- **응답을 직접 보내지 않고 `ConnectionEvent`(sealed)를 반환한다.** 어떻게 응답할지는 호출자가 정한다 — 이렇게 하면 소켓 없이도 테스트할 수 있다. `ProtocolHandlerTest`가 소켓을 열지 않는 이유가 이것이다.

## 3. 문제 정의

메시지가 왔을 때 무엇을 할지는 **지금 상태에 달려 있다.** 같은 `Query` 메시지라도 인증 전이면 거부, 후면 실행이다. 즉 이 클래스는 작은 상태 기계다.

상태는 하나뿐이다 — 인증됐는가(`sessionId != null`). 상태가 하나여도 **분기를 빠뜨리면 구멍이 난다.** CI-2를 검증하는 테스트가 없으면 "인증 전 질의 허용"이라는 결함이 조용히 남는다. 그게 보안 결함의 전형적인 모양이다.

## 4. 구현 코드

```kotlin
// src/main/kotlin/com/dbenginelab/wire/ProtocolHandler.kt @ 5505edc
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
```

## 5. 검증 테스트 (green)

```kotlin
// src/test/kotlin/com/dbenginelab/wire/ProtocolHandlerTest.kt @ 5505edc
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
```

```bash
./gradlew test --tests 'com.dbenginelab.wire.ProtocolHandlerTest'
```

**기대 결과**: `ProtocolHandlerTest` **5 PASSED**

invariant 대응:
- **CI-1** ← 인증 성공/실패 각각의 테스트
- **CI-2** ← 인증 전 질의 거부 테스트 ← **이 테스트가 없으면 안 된다**
- **CI-3** ← 질의 실행 후 `DataRow` 변환 테스트
- **CI-4** ← `Terminate` 테스트

## 6. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

**1.** 인증 검사(`sessionId == null` 분기)를 지워라. 어느 테스트가 잡나?

<details><summary>답</summary>

**실측: 하나도 안 잡는다. 5개 전부 통과한다.** — 이 세션에서 가장 중요한 발견이다.

`Query without auth returns Error`라는 이름의 테스트가 **분명히 있는데도** 통과한다. 이유는 단언이 **타입만** 보기 때문이다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
val ev = handler.handle(Message.Query("SELECT 1")) as ConnectionEvent.QueryResponse
assertTrue(ev.messages[0] is Message.Error)      // ← 에러이기만 하면 통과
```

인증 검사를 빼면 `engine.execute("SELECT 1")`이 실제로 실행되는데, 우리 파서는 `SELECT 1`(FROM 없음)을 파싱하지 못한다 → 예외 → `catch`가 잡아 `Message.Error(파싱 에러)`를 돌려준다. **에러 타입이 맞으니 단언이 통과한다.**

즉 이 테스트는 **다른 이유로 난 에러를 인증 거부로 착각하고 있다.**

만약 SQL이 유효했다면(예: 다른 접속이 만들어둔 테이블 조회) **인증 없이 질의가 성공**하고, 테스트는 그것도 못 잡는다.

고치는 법 — 내용까지 확인한다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
val err = ev.messages[0] as Message.Error
assertEquals("not authenticated", err.message)
```

또는 **유효한 SQL**로 시험해서 "실행되지 않았음"을 확인한다.

**"테스트가 있다"와 "검증된다"가 다르다**는 것을 가장 비싼 자리(인증)에서 보여주는 사례다. 이름만 보고 커버리지를 판단하면 안 된다.
</details>

**2.** `Terminate` 처리에서 세션 정리를 지워라. 접속을 1000번 열고 닫으면?

<details><summary>답</summary>

`Terminate closes session` 테스트가 잡는다 — `assertEquals(0, pool.activeSessions())`가 실패한다. (앞 과제와 달리 이건 **상태를 직접 확인**하므로 제대로 잡는다.)

운영에서는 13-01 과제 2번의 누수가 그대로 재연된다. 접속이 끊길 때마다 세션이 하나씩 남고, **capacity(기본 16)번째 접속부터 거부**된다.

특히 위험한 이유 — 네트워크 환경에서는 `Terminate`가 **오지 않는 경우가 정상적으로 존재한다.** 클라이언트가 크래시하거나 케이블이 뽑히면 종료 메시지 없이 연결만 끊긴다. 즉 `Terminate` 처리에만 의존하면 **정상적인 상황에서도 샌다.**

그래서 실제 서버는 두 겹으로 막는다:
1. `Terminate` 수신 시 정리 (정상 경로)
2. **소켓이 닫혔음을 감지했을 때 정리** (비정상 경로) — 그리고 이쪽이 실질적인 방어선이다
3. 추가로 **idle timeout** — 아무것도 안 하는 세션을 일정 시간 후 회수. `Session.lastAccess`가 그 용도로 준비되어 있는데 **아무도 쓰지 않고 있다.**
</details>

**3.** 인증 실패 응답에 "사용자 없음"과 "비밀번호 틀림"을 구분해서 담아봐라. 보안상 무엇이 나빠지나?

<details><summary>답</summary>

**계정 열거(account enumeration)** 가 가능해진다.

```
"user not found"     → 그 계정은 존재하지 않는다
"wrong password"     → 그 계정은 존재한다  ← 정보 유출
```

공격자가 아이디 목록을 대입해 **어떤 계정이 실재하는지** 알아낼 수 있다. 그 목록으로 비밀번호 공격을 집중하거나, 유출된 다른 서비스의 비밀번호를 대입한다(credential stuffing).

그래서 표준 관행은 **"아이디 또는 비밀번호가 올바르지 않습니다"** 하나로 통일하는 것이다. 사용성이 나빠지는 대가로 정보를 감춘다.

한 가지 더 — **응답 시간으로도 새어나간다.** 사용자가 없으면 해싱을 건너뛰어 빨리 응답하고, 있으면 해싱 후 비교하느라 느리다. 그 차이로 계정 존재를 알 수 있다(타이밍 공격의 일종). 막으려면 **사용자가 없어도 더미 해싱을 수행**해 시간을 맞춘다.

15-01 과제 2번의 타이밍 공격과 같은 계열이고, **"정보는 값뿐 아니라 시간으로도 샌다"** 는 원칙이 반복된다.
</details>

**4.** `ConnectionEvent`를 반환하는 대신 핸들러가 직접 소켓에 쓰도록 바꾼다면, 지금의 테스트 5개를 그대로 쓸 수 있나?

<details><summary>답</summary>

**쓸 수 없다.** 지금 테스트가 소켓 없이 도는 이유가 정확히 이 설계 덕분이다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
val ev = handler.handle(Message.Query("…"))    // 반환값을 그냥 검사
assertTrue(ev.messages[0] is Message.RowDescription)
```

소켓에 직접 쓰면 검증하려면 다음 중 하나가 필요해진다:

1. **실제 소켓 열기** — 포트를 잡아야 하고, 테스트가 느려지고, 병렬 실행 시 포트 충돌이 난다.
2. **`OutputStream` 목(mock) 주입** — 핸들러가 스트림을 주입받게 고쳐야 한다. 결국 의존성을 밖으로 빼는 것이라 **지금 설계와 같은 방향**이다.
3. **바이트를 파싱해 되돌리기** — 쓰인 바이트를 `MessageCodec`으로 디코딩해 확인. 가능하지만 테스트가 코덱 버그에도 함께 실패해 **원인 분리가 안 된다.**

핵심 원리는 **"결정"과 "부수효과"를 분리하라**는 것이다. 핸들러는 *무엇을 응답할지 결정*하고, 그것을 *실제로 보내는 일*은 바깥이 한다. 그러면 결정 로직은 순수 함수에 가까워져 테스트가 쉬워진다.

이 프로젝트에서 같은 패턴을 이미 봤다 — 06-03 `ConstraintValidator`가 검증만 하고 삽입은 호출자에게 맡긴 것, 06-02 `Operator`가 `Sequence`를 돌려줄 뿐 출력하지 않는 것. **테스트 가능성은 설계의 결과이지 나중에 붙이는 것이 아니다.**
</details>

## 7. 다음 한계

핸들러가 `Auth`를 부르지만 **그 `Auth`가 아직 없다.** 사용자·비밀번호·권한이 정의되지 않았다.

→ **단계 15 Auth**.
