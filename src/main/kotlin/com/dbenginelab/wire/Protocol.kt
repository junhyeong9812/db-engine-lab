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
        out.writeInt(1 + payload.size)
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
