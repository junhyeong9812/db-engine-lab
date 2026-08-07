# impl/13-01 — Session + ConnectionPool

> **종류**: 세션형
> **상위 단계**: `docs/stages/13-connection-pool.md`
> **코드 정본**: git `5505edc` — "complete: 21 stages + 12 보강 (120/120 tests)"
> **이 세션의 범위**: 여러 클라이언트를 동시에 받는 구조 — 세션과 그 상한. **Phase A의 마지막 단계.**
> **작성 파일**:
> - 신규 패키지: `src/main/kotlin/com/dbenginelab/session/`
> - 신규: `session/Session.kt` · `session/ConnectionPool.kt`
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/session/ConnectionPoolTest.kt`
> **검증**: `ConnectionPoolTest` 3 PASSED · **Phase A 13단계 완료**
> **예상 타이핑 시간**: 35분

---

## 0. 참조

- 참조 부재 — 일반적인 커넥션 풀 패턴(HikariCP 등)의 개념만 차용. 코드는 자체 설계.
- **코루틴 금지** (학습 정책) — 스레드 풀로 다룬다.

## 1. 만족시킬 invariant

- **CI-1**: capacity 이내의 `openSession`은 성공하고, 초과하면 거부된다.
- **CI-2**: `submit`한 작업은 스레드 풀에서 실행된다.

## 2. 의존성

- 없음에 가깝다 (독립 계층). 실제 질의 실행과의 결합은 14-00 `DbEngine`.

## 3. 문제 정의 (TDD step 1)

지금까지 우리 DB는 **한 프로세스 안에서 함수를 부르는** 물건이었다. 진짜 DB는 여러 클라이언트가 붙는다. 그러면 두 가지가 필요해진다:

1. **세션** — "누가 접속해 있는가"를 나타내는 단위. 각 접속은 자기 상태(트랜잭션, 인증 여부 등)를 갖는다.
2. **상한** — 무한정 받을 수 없다. 접속 하나마다 메모리와 스레드를 쓰기 때문이다. 그래서 capacity가 있고, **넘으면 거절해야 한다.**

2번이 중요하다. 상한 없이 받으면 부하가 몰릴 때 **DB 전체가 죽는다.** 거절은 실패가 아니라 방어다 — 실제 운영에서 "connection pool exhausted" 에러를 보는 이유가 이것이고, 그 에러가 없는 편이 더 위험하다.

## 4. 실패 테스트 (TDD step 2)

```kotlin
// src/test/kotlin/com/dbenginelab/session/ConnectionPoolTest.kt @ 5505edc
// ConnectionPool.kt
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
```

**예상 실패**: **컴파일 실패** — `Unresolved reference: ConnectionPool`, `Session`.

## 5. 구현 코드 (TDD step 3 — make it pass)

### 5.1 `Session.kt`

```kotlin
// src/main/kotlin/com/dbenginelab/session/Session.kt @ 5505edc
package com.dbenginelab.session

import java.util.concurrent.atomic.AtomicLong

class Session(val id: Long, val user: String) {
    @Volatile var currentTxId: Long? = null
    @Volatile var lastError: String? = null
    @Volatile var lastAccess: Long = System.currentTimeMillis()
    fun touch() { lastAccess = System.currentTimeMillis() }

    companion object {
        private val nextId = AtomicLong(1)
        fun nextId(): Long = nextId.getAndIncrement()
    }
}
```

### 5.2 `ConnectionPool.kt`

```kotlin
// src/main/kotlin/com/dbenginelab/session/ConnectionPool.kt @ 5505edc
// ConnectionPool.kt
package com.dbenginelab.session

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

class ConnectionPool(private val capacity: Int = DEFAULT_CAPACITY) : Closeable {

    private val sessions: ConcurrentHashMap<Long, Session> = ConcurrentHashMap()
    private val executor = Executors.newFixedThreadPool(capacity)

    fun openSession(user: String): Session {
        // Q: capacity 체크가 atomic 아닌데 race?
        require(sessions.size < capacity) { "pool full (capacity=$capacity)" }
        // <details><summary>A</summary>
        // size + put 사이 race로 capacity 약간 초과 가능. 학습 단순화. 진짜는 AtomicInteger counter.
        // </details>
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
```

## 6. 검증 테스트 (TDD step 4 — green)

테스트 파일은 §4에서 저장한 것이 그대로 최종본이다.

```bash
./gradlew test --tests 'com.dbenginelab.session.ConnectionPoolTest'
```

**기대 결과**: `ConnectionPoolTest` **3 PASSED**

invariant 대응:
- **CI-1** ← `openSession 후 활성 세션 수` · `capacity 초과 거부`
- **CI-2** ← `여러 task 병렬 실행`

## 7. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

**1.** capacity 검사를 지워라. 세션을 10만 개 열면 무슨 일이 일어나나? 어디에서 먼저 터지나?

<details><summary>답</summary>

**실측: `capacity 초과 거부` 하나가 실패한다** (3개 중 1개). 이 테스트가 검사의 유일한 방어선이다.

10만 개를 열면 — `Session` 객체 자체는 가볍다(필드 4개). `ConcurrentHashMap`에 10만 엔트리도 수십 MB 수준이라 **메모리가 먼저 터지지는 않는다.**

먼저 터지는 것은 **스레드 풀이 아니다.** 여기가 함정인데:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
private val executor = Executors.newFixedThreadPool(capacity)
```

스레드 수는 `capacity`로 **고정**되어 있고 세션 수와 무관하다. 세션 10만 개가 `submit`을 하면 스레드는 여전히 N개이고 **작업이 큐에 무한정 쌓인다.** `newFixedThreadPool`의 큐는 무제한(`LinkedBlockingQueue`)이라 여기서 메모리가 조용히 차오른다.

현실의 순서는 대개 이렇다:
1. **작업 큐 적체** → 응답 시간이 무한정 늘어난다(터지진 않는다 — 더 나쁘다)
2. 각 세션이 열어둔 자원(파일 핸들·소켓)이 있으면 **OS의 fd 한계**
3. 최종적으로 힙 고갈

**1번이 가장 위험하다.** 시스템이 죽지 않고 "무한정 느려지기"만 하면 장애 감지가 늦는다. 그래서 capacity는 "죽지 않게" 하는 장치가 아니라 **"빨리 실패하게"** 하는 장치다.
</details>

**2.** 세션을 닫지 않고 계속 여는 코드를 짜라(누수). 몇 번째에서 거절당하나? 실제로 이 누수는 어디서 생기나?

<details><summary>답</summary>

**capacity + 1번째**에서 `IllegalArgumentException("pool full")`. 기본값이 16이므로 17번째다.

실제 애플리케이션에서 생기는 자리는 거의 항상 **예외 경로**다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
val s = pool.openSession(user)
doWork(s)                       // ← 여기서 예외가 나면
pool.closeSession(s.id)         // ← 이 줄에 도달하지 못한다
```

정상 동작에서는 완벽하고, **에러가 날 때만** 하나씩 샌다. 그래서 평소엔 멀쩡하다가 **장애가 나면 그 장애 때문에 누수가 가속되어 더 큰 장애가 된다.**

막는 방법은 언어 차원의 장치를 쓰는 것이다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
try { doWork(s) } finally { pool.closeSession(s.id) }
```

이 프로젝트에서 같은 패턴을 이미 여러 번 봤다 — 02-02의 `unpinPage`, 03-01의 `try-finally`, 09-02의 `abort 시 releaseAll`. **자원을 빌렸으면 실패 경로에서도 돌려준다**가 계층을 가리지 않고 반복된다.

`ConnectionPool`이 `Closeable`인데 `Session`은 아니라는 점도 눈여겨봐라. `Session`도 `Closeable`이었다면 `use {}`로 강제할 수 있었을 것이다.
</details>

**3.** 두 스레드가 동시에 `openSession`을 부르면 capacity를 넘어설 수 있나?

<details><summary>답</summary>

**넘어설 수 있다.** 코드에 이미 그 사실이 Q/A로 적혀 있다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
// Q: capacity 체크가 atomic 아닌데 race?
require(sessions.size < capacity) { "pool full (capacity=$capacity)" }
// A: size + put 사이 race로 capacity 약간 초과 가능. 학습 단순화.
val s = Session(Session.nextId(), user)
sessions[s.id] = s
```

`size` 확인과 `put` 사이가 보호되지 않는다. capacity가 16이고 15개가 찬 상태에서 스레드 셋이 동시에 들어오면 **셋 다 `15 < 16`을 보고** 전부 통과해 18개가 된다.

`ConcurrentHashMap`을 써도 소용없다는 점이 중요하다 — **개별 연산은 원자적이지만 "확인 후 삽입"이라는 조합은 원자적이지 않다.** 09-01 과제 1번, 02-01 과제 3번과 완전히 같은 read-modify-write 문제다.

고치는 방법:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
private val count = AtomicInteger(0)
fun openSession(user: String): Session {
    if (count.incrementAndGet() > capacity) {      // 먼저 늘리고
        count.decrementAndGet()                     // 초과면 되돌린다
        throw IllegalArgumentException("pool full")
    }
    …
}
```

**"확인 후 증가"가 아니라 "증가 후 확인"** 으로 뒤집는 것이 요령이다. 08-02 과제 2번(meta 갱신 순서)에서도 같은 뒤집기가 나왔는데, 거기서는 반대 방향이 정답이었다 — **순서 선택은 "어느 쪽으로 틀리는 편이 안전한가"로 정한다.**
</details>

**4.** 세션마다 트랜잭션을 물리려면 어디를 바꿔야 하나? 세션이 닫힐 때 열린 트랜잭션은 commit인가 abort인가?

<details><summary>답</summary>

바꿀 자리는 이미 준비되어 있다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
class Session(val id: Long, val user: String) {
    @Volatile var currentTxId: Long? = null    // ← 여기
```

`closeSession`이 이 값을 보고 처리하면 된다. 지금은 그냥 `sessions.remove(sessionId)`뿐이다.

**답은 abort다.** 이유:

세션이 닫히는 상황은 대개 **정상 종료가 아니다** — 네트워크 끊김, 클라이언트 크래시, 타임아웃. 그 트랜잭션이 완결되었다고 볼 근거가 없다.

```
클라이언트: BEGIN → INSERT → INSERT → (연결 끊김)
서버가 commit한다면? 클라이언트는 세 번째 INSERT를 보내려던 참이었을 수도 있다
→ 반쪽짜리 작업이 확정된다
```

**커밋은 명시적 의사표시여야 한다.** "COMMIT을 받지 못했다"는 곧 "커밋하지 말라"는 뜻이다. 실제 DB가 전부 이렇게 동작한다 — PostgreSQL도 연결이 끊기면 열린 트랜잭션을 rollback한다.

08-01의 recovery 규칙(`커밋 레코드가 없으면 반영하지 않는다`)과 **같은 원칙**이다. 계층은 다르지만 판단 기준이 하나다 — **명시적 커밋이 없으면 없던 일로 한다.**
</details>

## 8. 다음 한계 — Phase A 종료

여기까지가 **Phase A**다. 저장(1~3) · 스키마(4~5) · 질의(6) · 트랜잭션(7~10) · 최적화(11) · SQL(12) · 세션(13)이 다 있다.

하지만 이 DB는 여전히 **같은 프로세스 안에서만** 쓸 수 있다. 네트워크로 접속할 방법이 없다. 그리고 지금까지 만든 조각들이 **서로 조립되어 있지 않다** — SQL 파서는 AST를 뱉고, 옵티마이저는 LogicalPlan을 받는데, 그 사이를 잇는 것이 없다.

→ **13-02 Translator**가 그 갭을 메우고, **14-00 DbEngine**이 전체를 하나의 입구로 묶는다. 그 다음이 **Phase B — 네트워크 프로토콜(14-01)부터**다.
