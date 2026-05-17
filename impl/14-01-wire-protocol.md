# impl/14-01 — Wire Protocol (한 줄 한 줄)

> **검증**: ProtocolTest 5 PASSED. **Phase B 시작.**
> 작성 파일:
> - 신규 패키지: `src/main/kotlin/com/dbenginelab/wire/`
> - 신규: Protocol.kt (Message sealed + MessageCodec)
> - 신규 테스트: ProtocolTest.kt

## 0. 참조
- PostgreSQL frontend/backend protocol (매우 단순화).

## 1. invariant
- Message encode → decode round-trip.
- DataRow null 표현.
- Frame: `[4 len][1 tag][payload]`.

## 2. Protocol.kt — 한 줄 한 줄

```kotlin
package com.dbenginelab.wire                                         // 신규 wire 패키지

import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

sealed class Message {                                               // Q: sealed인 이유?
    abstract val typeByte: Byte
    // <details><summary>A</summary>
    // wire 메시지는 진짜 닫힌 집합 (protocol 정의). sealed로 exhaustive when 강제.
    // </details>

    data class Startup(val user: String, val password: String) : Message() {
        override val typeByte: Byte = 0x01
    }
    data class Query(val sql: String) : Message() {
        override val typeByte: Byte = 0x02
    }
    object Terminate : Message() {                                   // 인자 없음 — object
        override val typeByte: Byte = 0x03
    }
    data class AuthOk(val sessionId: Long) : Message() {
        override val typeByte: Byte = 0x80.toByte()
    }
    data class RowDescription(val columns: List<String>) : Message() {
        override val typeByte: Byte = 0x81.toByte()
    }
    data class DataRow(val values: List<String?>) : Message() {      // Q: 왜 String?
        override val typeByte: Byte = 0x82.toByte()
    }
    // <details><summary>A</summary>
    // wire는 string-based — 모든 값 string 변환 (단순화). production은 type-coded.
    // </details>
    data class CommandComplete(val tag: String) : Message() {
        override val typeByte: Byte = 0x83.toByte()
    }
    data class Error(val message: String) : Message() {
        override val typeByte: Byte = 0xFF.toByte()
    }
}

object MessageCodec {
    fun write(out: DataOutputStream, msg: Message) {
        val payload = encodePayload(msg)
        // Q: length가 payload만이 아니라 payload+tag?
        out.writeInt(1 + payload.size)
        out.writeByte(msg.typeByte.toInt())
        out.write(payload); out.flush()
        // <details><summary>A</summary>
        // reader가 length만큼 readFully 하면 tag 포함 — 한 frame 단위. tag만 따로 처리 안 함.
        // </details>
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
            is Message.RowDescription -> {
                dos.writeInt(msg.columns.size); msg.columns.forEach { writeStr(dos, it) }
            }
            is Message.DataRow -> {
                dos.writeInt(msg.values.size)
                // Q: null을 " "로 인코딩? 진짜 null 표현은?
                msg.values.forEach { v -> writeStr(dos, v ?: " ") }
            }
            // <details><summary>A</summary>
            // 단순화 — null sentinel로 " " (공백) 사용. " " 자체 값과 충돌 — 진짜는 별도 null flag byte.
            // </details>
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
            0x82.toByte() -> Message.DataRow((1..dis.readInt()).map {
                val s = readStr(dis); if (s == " ") null else s     // " " → null 복원
            })
            0x83.toByte() -> Message.CommandComplete(readStr(dis))
            0xFF.toByte() -> Message.Error(readStr(dis))
            else -> error("unknown message tag: ${tag.toUByte()}")
        }
    }

    private fun writeStr(dos: DataOutputStream, s: String) {         // length-prefix UTF-8
        val b = s.toByteArray(StandardCharsets.UTF_8)
        dos.writeInt(b.size); dos.write(b)
    }
    private fun readStr(dis: DataInputStream): String {
        val len = dis.readInt(); val b = ByteArray(len); dis.readFully(b)
        return String(b, StandardCharsets.UTF_8)
    }
}
```

## 3. 검증 (5 PASSED)
- Startup/Query/AuthOk/DataRow(with null)/Error round-trip

## 4. 깨뜨릴 과제
- Frame 손상 (length만 읽고 connection 끊김) — 복구?
- UTF-8 멀티바이트 — length는 byte 단위 (char 아님).
- Streaming result (큰 SELECT) — chunk DataRow?
- null sentinel " " 자체 값과 충돌 — 해결?

## 5. 다음 한계
- 진짜 TCP server 없음 — handler만 (단계 14-01 보강).
- TLS 없음.
