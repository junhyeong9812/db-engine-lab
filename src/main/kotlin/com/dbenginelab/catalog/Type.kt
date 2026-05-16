package com.dbenginelab.catalog

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

enum class Type {
    INT,
    BIGINT,
    STRING;

    fun encode(value: Any?, buffer: ByteBuffer) {
        when (this) {
            INT -> buffer.putInt(value as Int)
            BIGINT -> buffer.putLong(value as Long)
            STRING -> {
                val bytes = (value as String).toByteArray(StandardCharsets.UTF_8)
                buffer.putInt(bytes.size)
                buffer.put(bytes)
            }
        }
    }

    fun decode(buffer: ByteBuffer): Any = when (this) {
        INT -> buffer.int
        BIGINT -> buffer.long
        STRING -> {
            val len = buffer.int
            val bytes = ByteArray(len)
            buffer.get(bytes)
            String(bytes, StandardCharsets.UTF_8)
        }
    }

    /** Fixed encoded size, or -1 if variable (STRING). */
    fun fixedSize(): Int = when (this) {
        INT -> 4
        BIGINT -> 8
        STRING -> -1
    }
}
