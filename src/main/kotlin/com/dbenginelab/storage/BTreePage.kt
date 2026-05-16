package com.dbenginelab.storage

import java.nio.ByteBuffer

class BTreePage(val page: Page) {

    enum class Type(val code: Byte) {
        LEAF(0),
        INTERNAL(1);

        companion object {
            fun fromCode(c: Byte): Type = when (c) {
                0.toByte() -> LEAF
                1.toByte() -> INTERNAL
                else -> error("unknown btree page type code: $c")
            }
        }
    }

    var type: Type
        get() = Type.fromCode(page.read(0, 1)[0])
        set(value) { page.write(0, byteArrayOf(value.code)) }

    var keyCount: Int
        get() = readIntAt(1)
        set(v) { writeIntAt(1, v) }

    var auxPage: Int
        get() = readIntAt(5)
        set(v) { writeIntAt(5, v) }

    var parentPage: Int
        get() = readIntAt(9)
        set(v) { writeIntAt(9, v) }

    fun keyAt(slot: Int): Long = readLongAt(HEADER_SIZE + slot * ENTRY_SIZE)
    fun valueAt(slot: Int): Long = readLongAt(HEADER_SIZE + slot * ENTRY_SIZE + KEY_SIZE)

    fun setEntry(slot: Int, key: Long, value: Long) {
        writeLongAt(HEADER_SIZE + slot * ENTRY_SIZE, key)
        writeLongAt(HEADER_SIZE + slot * ENTRY_SIZE + KEY_SIZE, value)
    }

    fun insertAt(slot: Int, key: Long, value: Long) {
        val count = keyCount
        require(count < MAX_ENTRIES) { "btree page full (max=$MAX_ENTRIES)" }
        require(slot in 0..count) { "invalid slot $slot for count $count" }
        for (i in count downTo slot + 1) {
            setEntry(i, keyAt(i - 1), valueAt(i - 1))
        }
        setEntry(slot, key, value)
        keyCount = count + 1
    }

    fun findSlot(target: Long): Int {
        var lo = 0
        var hi = keyCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keyAt(mid) < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun isFull(): Boolean = keyCount >= MAX_ENTRIES

    fun initAsEmpty(type: Type, parentPage: Int = INVALID, auxPage: Int = INVALID) {
        this.type = type
        this.keyCount = 0
        this.parentPage = parentPage
        this.auxPage = auxPage
    }

    private fun readIntAt(offset: Int): Int =
        ByteBuffer.wrap(page.read(offset, 4)).int

    private fun writeIntAt(offset: Int, v: Int) {
        page.write(offset, ByteBuffer.allocate(4).putInt(v).array())
    }

    private fun readLongAt(offset: Int): Long =
        ByteBuffer.wrap(page.read(offset, 8)).long

    private fun writeLongAt(offset: Int, v: Long) {
        page.write(offset, ByteBuffer.allocate(8).putLong(v).array())
    }

    companion object {
        const val INVALID: Int = -1
        const val KEY_SIZE: Int = 8
        const val VALUE_SIZE: Int = 8
        const val ENTRY_SIZE: Int = KEY_SIZE + VALUE_SIZE
        const val HEADER_SIZE: Int = 1 + 4 + 4 + 4
        const val MAX_ENTRIES: Int = (Page.PAGE_SIZE - HEADER_SIZE) / ENTRY_SIZE
    }
}
