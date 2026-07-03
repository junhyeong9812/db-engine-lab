package com.dbenginelab.storage

class Page(
    val id: PageId,
    private val data: ByteArray,
) {
    var isDirty: Boolean = false
        private set

    var pinCount: Int = 0
        private set

    init {
        require(data.size == PAGE_SIZE) {
            "Page data size must be exactly $PAGE_SIZE bytes (got ${data.size})"
        }
    }

    fun read(offset: Int, length: Int): ByteArray {
        checkRange(offset, length)
        return data.copyOfRange(offset, offset + length)
    }

    fun write(offset: Int, bytes: ByteArray) {
        checkRange(offset, bytes.size)
        System.arraycopy(bytes, 0, data, offset, bytes.size)
        isDirty = true
    }

    fun rawData(): ByteArray = data

    fun markDirty() {isDirty = true}
    fun markClean() {isDirty = false}

    fun pin() {pinCount++}
    fun unpin() {
        check(pinCount > 0) {"unpin called on un-pinned page ${id}"}
        pinCount--
    }

    private fun checkRange(offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= PAGE_SIZE) {
            "page range out of bounds: offset=$offset length=$length pageSize=$PAGE_SIZE"
        }
    }

    companion object {
        const val PAGE_SIZE: Int = 4096
    }
}