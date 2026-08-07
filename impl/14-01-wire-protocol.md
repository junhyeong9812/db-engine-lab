# impl/14-01 — Wire Protocol (Message + Codec)

> **종류**: 세션형
> **상위 단계**: `docs/stages/14-wire-protocol.md`
> **코드 정본**: git `5505edc` — "complete: 21 stages + 12 보강 (120/120 tests)"
> **이 세션의 범위**: 클라이언트와 주고받을 **메시지의 형식**을 정한다. **Phase B 시작.**
> **작성 파일**:
> - 신규 패키지: `src/main/kotlin/com/dbenginelab/wire/`
> - 신규: `wire/Protocol.kt` (`Message` sealed + `MessageCodec`)
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/wire/ProtocolTest.kt`
> **검증**: `ProtocolTest` 5 PASSED
> **예상 타이핑 시간**: 45분

---

## 0. 참조

- PostgreSQL frontend/backend protocol을 **매우** 단순화.
- **핵심 설계 결정 근거**: `Message`는 sealed class. 프로토콜은 정의상 닫힌 집합이고, 새 메시지 종류가 생기면 인코더·디코더·핸들러가 **전부** 알아야 한다. `when`이 빠뜨리면 컴파일이 막아준다.

## 1. 만족시킬 invariant

- **CI-1**: 모든 메시지가 encode → decode round-trip을 통과한다.
- **CI-2**: `DataRow`가 NULL을 표현할 수 있다.
- **CI-3**: 프레임 형식이 `[4바이트 길이][1바이트 태그][payload]`로 고정된다.

## 2. 의존성

- 없음 (독립 계층). `DbEngine`과의 결합은 14-01 ProtocolHandler.

## 3. 문제 정의 (TDD step 1)

네트워크는 **바이트 스트림**이다. 경계가 없다. `"SELECT 1"`을 보내면 상대가 8바이트를 한 번에 받을 수도, 3바이트 + 5바이트로 나눠 받을 수도 있다.

그래서 **프레이밍**이 필요하다. 01-01에서 record 경계를 위해 length-prefix를 쓴 것과 **정확히 같은 문제**이고 같은 해법이다:

```
[4바이트 길이][1바이트 태그][payload …]
```

길이를 먼저 읽으면 "이만큼 더 읽으면 한 메시지"를 안다. 태그는 그 payload를 어떤 메시지로 해석할지 알려준다 — 05-01의 constraint tag byte, 08-01의 로그 레코드 tag byte와 같은 수법이 세 번째로 나오는 셈이다.

그리고 NULL. `DataRow`가 값을 담는데, "빈 문자열"과 "값 없음"은 다르다. 04-01에서 NULL bitmap을 쓴 것과 같은 문제를 여기서 다시 만난다 — **표현 방식은 계층마다 다를 수 있지만 문제는 같다.**

## 4. 실패 테스트 (TDD step 2)

```kotlin
// src/test/kotlin/com/dbenginelab/wire/ProtocolTest.kt @ 5505edc
package com.dbenginelab.wire

import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.test.assertEquals

class ProtocolTest {
    private fun rt(msg: Message): Message {
        val bos = ByteArrayOutputStream()
        MessageCodec.write(DataOutputStream(bos), msg)
        return MessageCodec.read(DataInputStream(ByteArrayInputStream(bos.toByteArray())))
    }

    @Test fun `Startup round-trip`() { assertEquals(Message.Startup("a", "p"), rt(Message.Startup("a", "p"))) }
    @Test fun `Query round-trip`() { assertEquals(Message.Query("SELECT 1"), rt(Message.Query("SELECT 1"))) }
    @Test fun `AuthOk round-trip`() { assertEquals(Message.AuthOk(42L), rt(Message.AuthOk(42L))) }
    @Test fun `DataRow with null round-trip`() {
        val m = Message.DataRow(listOf("v1", null, "v3"))
        assertEquals(m, rt(m))
    }
    @Test fun `Error round-trip`() { assertEquals(Message.Error("syntax"), rt(Message.Error("syntax"))) }
}
```

**예상 실패**: **컴파일 실패** — `Unresolved reference: Message`, `MessageCodec`.

## 5. 구현 코드 (TDD step 3 — make it pass)

```kotlin
// src/main/kotlin/com/dbenginelab/wire/Protocol.kt @ 5505edc
package com.dbenginelab.wire

import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

sealed class Message {
    abstract val typeByte: Byte
    data class Startup(val user: String, val password: String) : Message() { override val typeByte: Byte = 0x01 }
    data class Query(val sql: String) : Message() { override val typeByte: Byte = 0x02 }
    object Terminate : Message() { override val typeByte: Byte = 0x03 }
    data class AuthOk(val sessionId: Long) : Message() { override val typeByte: Byte = 0x80.toByte() }
    data class RowDescription(val columns: List<String>) : Message() { override val typeByte: Byte = 0x81.toByte() }
    data class DataRow(val values: List<String?>) : Message() { override val typeByte: Byte = 0x82.toByte() }
    data class CommandComplete(val tag: String) : Message() { override val typeByte: Byte = 0x83.toByte() }
    data class Error(val message: String) : Message() { override val typeByte: Byte = 0xFF.toByte() }
}

object MessageCodec {
    fun write(out: DataOutputStream, msg: Message) {
        val payload = encodePayload(msg)
        // Q: length가 payload만이 아니라 payload+tag?
        out.writeInt(1 + payload.size)
        // <details><summary>A</summary>
        // reader가 length만큼 readFully 하면 tag 포함 — 한 frame 단위. tag만 따로 처리 안 함.
        // </details>
        out.writeByte(msg.typeByte.toInt())
        out.write(payload); out.flush()
    }

    fun read(input: DataInputStream): Message {
        val len = input.readInt()
        val tag = input.readByte()
        val payload = ByteArray(len - 1); input.readFully(payload)
        return decodePayload(tag, payload)
    }

    private fun encodePayload(msg: Message): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        when (msg) {
            is Message.Startup -> { writeStr(dos, msg.user); writeStr(dos, msg.password) }
            is Message.Query -> writeStr(dos, msg.sql)
            is Message.Terminate -> {}
            is Message.AuthOk -> dos.writeLong(msg.sessionId)
            is Message.RowDescription -> { dos.writeInt(msg.columns.size); msg.columns.forEach { writeStr(dos, it) } }
            is Message.DataRow -> { dos.writeInt(msg.values.size); msg.values.forEach { v -> writeStr(dos, v ?: " ") } }
            is Message.CommandComplete -> writeStr(dos, msg.tag)
            is Message.Error -> writeStr(dos, msg.message)
        }
        return bos.toByteArray()
    }

    private fun decodePayload(tag: Byte, payload: ByteArray): Message {
        val dis = DataInputStream(java.io.ByteArrayInputStream(payload))
        return when (tag) {
            0x01.toByte() -> Message.Startup(readStr(dis), readStr(dis))
            0x02.toByte() -> Message.Query(readStr(dis))
            0x03.toByte() -> Message.Terminate
            0x80.toByte() -> Message.AuthOk(dis.readLong())
            0x81.toByte() -> Message.RowDescription((1..dis.readInt()).map { readStr(dis) })
            0x82.toByte() -> Message.DataRow((1..dis.readInt()).map { val s = readStr(dis); if (s == " ") null else s })
            0x83.toByte() -> Message.CommandComplete(readStr(dis))
            0xFF.toByte() -> Message.Error(readStr(dis))
            else -> error("unknown message tag: ${tag.toUByte()}")
        }
    }

    private fun writeStr(dos: DataOutputStream, s: String) {
        val b = s.toByteArray(StandardCharsets.UTF_8); dos.writeInt(b.size); dos.write(b)
    }
    private fun readStr(dis: DataInputStream): String {
        val len = dis.readInt(); val b = ByteArray(len); dis.readFully(b)
        return String(b, StandardCharsets.UTF_8)
    }
}
```

## 6. 검증 테스트 (TDD step 4 — green)

테스트 파일은 §4에서 저장한 것이 그대로 최종본이다.

```bash
./gradlew test --tests 'com.dbenginelab.wire.ProtocolTest'
```

**기대 결과**: `ProtocolTest` **5 PASSED**

invariant 대응:
- **CI-1** ← 각 메시지 종류의 round-trip 테스트들
- **CI-2** ← `DataRow`의 NULL 표현 테스트
- **CI-3** ← 프레임 길이·태그를 확인하는 테스트

## 7. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

**1.** 길이 프리픽스를 빼고 태그만 남겨라. 한 메시지만 보내면 동작한다. **두 개를 이어 보내면** 무엇이 어긋나나?

<details><summary>답</summary>

**두 번째 메시지의 시작 위치를 알 수 없다.**

메시지마다 payload 길이가 다르다 — `Query("SELECT 1")`과 `Query("SELECT * FROM very_long_table_name WHERE …")`는 길이가 완전히 다르다. 길이를 안 적으면 디코더는 **어디서 첫 메시지가 끝나는지** 판단할 근거가 없다.

가능한 대안은 **구분자(delimiter)** 를 넣는 것인데, 그러면 **payload 안에 그 바이트가 나오면 깨진다.** 이스케이프가 필요해지고, 이스케이프는 다시 12-01 과제 4번의 따옴표 문제를 부른다.

그래서 이진 프로토콜은 거의 항상 **length-prefix**를 쓴다. 이 프로젝트에서만 네 번째 등장이다:

| 계층 | 무엇의 경계 |
|---|---|
| 01-01 `AppendOnlyFile` | record |
| 04-01 `Tuple` (NULL bitmap) | 어느 컬럼이 있는지 |
| 06-01 `TableHeap` | page 안의 tuple |
| 14-01 `MessageCodec` | 네트워크 메시지 |

**"가변 길이를 나열하려면 경계를 데이터 밖에서 알려줘야 한다"** 는 규칙이 계층을 가리지 않는다.
</details>

**2.** 프레임을 반으로 잘라 두 번에 나눠 넣어봐라. 지금 코덱이 이걸 처리하나? 못 한다면 어느 계층이 처리해야 하나?

<details><summary>답</summary>

**처리하지 못한다.** `MessageCodec`은 **완전한 프레임 하나**가 주어진다고 가정하고 디코딩한다. 절반만 주면 읽다가 버퍼 끝을 넘는다.

그런데 이건 **결함이 아니라 책임 분리**다. TCP는 스트림이라 "보낸 단위"와 "받는 단위"가 일치하지 않는다 — 한 번에 다 올 수도, 세 조각으로 올 수도, 두 메시지가 붙어 올 수도 있다. 이걸 다루는 계층이 따로 있어야 한다:

```
소켓 ─→ [누적 버퍼] ─→ [프레이머] ─→ [MessageCodec] ─→ Message
              ↑            ↑
       바이트를 모은다   길이를 읽어 완전한 프레임인지 판단
```

**프레이머의 일**: 앞 4바이트를 읽어 길이 N을 얻고, 버퍼에 N바이트가 다 모였는지 확인한다. 안 모였으면 **아무것도 하지 않고 기다린다.** 모였으면 그만큼 잘라 코덱에 넘긴다.

Netty의 `LengthFieldBasedFrameDecoder`가 정확히 이 일만 하는 컴포넌트다. 코덱을 순수하게 유지하면 **테스트가 쉬워진다** — 소켓 없이 `ByteArray`만으로 검증할 수 있고, `ProtocolTest`가 그렇게 되어 있다.
</details>

**3.** 길이 필드를 4바이트에서 2바이트로 줄이면 최대 메시지 크기는? 그 크기를 넘는 `DataRow`를 보내려면?

<details><summary>답</summary>

`Short`는 부호 있는 16비트라 **최대 32,767바이트** — 약 32KB다. (부호 없이 쓰면 65,535까지.)

32KB를 넘는 행은 흔하다. `TEXT` 컬럼 하나에 긴 글이 들어가면 바로 넘는다. 04-01 과제 4번에서 본 것과 **똑같은 오버플로**가 여기서 재연된다 — 길이가 음수로 뒤집히거나 0이 되어 조용히 잘린다.

넘는 데이터를 보내는 방법은 둘이다:

1. **청크 분할** — 큰 메시지를 여러 프레임으로 쪼개고 "이어짐" 플래그를 둔다. HTTP/2의 프레임, WebSocket의 fragmentation이 이 방식이다.
2. **스트리밍 응답** — `DataRow`를 행 단위가 아니라 조각 단위로 보내고 수신 쪽이 조립한다. PostgreSQL 프로토콜이 큰 값에 대해 하는 일이다.

**4바이트를 쓰는 이유가 곧 답이다** — 2GB까지 표현되니 사실상 제한이 없고, 프레임당 3바이트를 더 쓰는 대가로 이 복잡성 전체를 회피한다. **싼 곳에서 넉넉하게 잡아 비싼 문제를 없애는** 전형적인 교환이다.
</details>

**4.** 알 수 없는 태그 값을 받으면 어떻게 되나? 예외인가 무시인가 — 프로토콜에서는 어느 쪽이 옳은가?

<details><summary>답</summary>

지금 코드는 `when`에서 매칭되지 않으면 **예외**를 던진다.

"어느 쪽이 옳은가"는 **프로토콜 진화 전략**에 달렸고, 답이 갈린다:

| 전략 | 알 수 없는 태그 | 대가 |
|---|---|---|
| **엄격(fail fast)** | 예외 — 연결을 끊는다 | 구버전 서버가 신버전 클라이언트를 아예 거부한다. 대신 **잘못된 해석이 없다** |
| **관대(ignore unknown)** | 무시하고 다음 프레임으로 | 롤링 업그레이드가 쉽다. 대신 **중요한 메시지를 조용히 버릴 수 있다** |

관대한 쪽을 택하려면 **길이 프리픽스가 반드시 있어야 한다** — 모르는 메시지를 건너뛰려면 그 길이를 알아야 하기 때문이다. 우리 프레임 구조가 그것을 가능하게 해뒀다는 점을 눈여겨봐라.

실무의 절충은 **"무시하되 기록한다"** 이다. 처리하지 않고 넘어가되 메트릭을 올리고 로그를 남긴다 — 조용히 버리지 않는 것이 핵심이다. 단계 17의 `MetricsRegistry`가 붙을 자리가 여기다.

Protocol Buffers가 unknown field를 보존하는 것도 같은 계열의 설계다 — 모르는 것을 버리지 않고 그대로 들고 있다가 되돌려준다.
</details>

**5.** `DataRow`의 NULL 표현을 "빈 문자열"로 바꿔봐라. 어느 테스트가 잡나? 안 잡힌다면 잡는 테스트를 직접 써라.

<details><summary>답</summary>

`ProtocolTest`에 NULL을 다루는 테스트가 있다면 잡히고, 없다면 안 잡힌다 — **직접 확인해보고 없으면 써라.** (04-01·06-03에서 이미 두 번, "NULL 경로는 테스트가 잘 안 덮는다"를 봤다.)

`''`과 `NULL`을 구분 못 하는 DB가 내는 사고:

```sql
SELECT COUNT(*) FROM users WHERE nickname IS NULL     -- 실제 미입력자 수
SELECT COUNT(*) FROM users WHERE nickname = ''        -- 빈 문자열로 입력한 사람 수
```

**둘은 의미가 다르다.** "닉네임을 안 정했다"와 "닉네임을 빈칸으로 정했다"는 다른 상태다. 프로토콜에서 뭉개지면 클라이언트가 이 둘을 영원히 구분할 수 없다.

더 나쁜 경우 — 집계에서 갈린다. `AVG(score)`는 NULL을 **제외**하고 계산하지만 0으로 들어온 값은 **포함**한다. 표현 계층에서 NULL이 사라지면 통계가 조용히 틀린다.

04-01에서 NULL bitmap까지 만들어 지킨 구분이 **맨 마지막 출구에서 무너지는** 셈이다. 계층 하나만 틀려도 전체가 무의미해진다는 예다. Oracle이 빈 문자열을 NULL로 취급해서 오랫동안 논란인 것도 같은 이유다.
</details>

## 8. 다음 한계

메시지 형식은 정해졌지만 **아무도 그 메시지에 응답하지 않는다.** `Query` 메시지를 받아 `DbEngine`을 부르고 결과를 `DataRow`로 돌려주는 주체가 없다. 인증도 없어서 누구든 붙으면 뭐든 할 수 있다.

→ **14-01 ProtocolHandler** (Wire + Auth + DbEngine 통합)와 **단계 15 Auth**.
