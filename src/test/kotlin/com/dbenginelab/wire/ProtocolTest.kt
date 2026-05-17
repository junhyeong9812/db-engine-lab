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
