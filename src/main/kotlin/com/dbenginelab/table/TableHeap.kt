package com.dbenginelab.table

import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.storage.BufferPool
import com.dbenginelab.storage.Page
import com.dbenginelab.storage.PageId
import com.dbenginelab.storage.PagedFile
import java.io.Closeable
import java.nio.ByteBuffer

class TableHeap(
    val schema: TableSchema,
    private val pagedFile: PagedFile,
    private val bufferPool: BufferPool,
) : Closeable {

    init {
        if (pagedFile.pageCount() == 0) {
            val page = bufferPool.newPage()
            initEmptyPage(page)
            bufferPool.unpinPage(page.id, isDirty = true)
        }
    }

    fun insert(tuple: Tuple) {
        require(tuple.schema == schema)
        val tupleBytes = tuple.encode()
        val entrySize = 4 + tupleBytes.size
        require(entrySize + HEADER_SIZE <= Page.PAGE_SIZE) {
            "tuple to large (${entrySize} bytes) for page size ${Page.PAGE_SIZE}"
        }
        val lastPageNo = pagedFile.pageCount() - 1
        val lastPage = bufferPool.fetchPage(PageId(pagedFile.fileId, lastPageNo))
        try {
            val freeOffset = readFreeOffset(lastPage)
            if (freeOffset + entrySize <= Page.PAGE_SIZE) {
                writeTupleAt(lastPage, freeOffset, tupleBytes)
                writeTupleCount(lastPage, readTupleCount(lastPage) + 1)
                writeFreeOffset(lastPage, freeOffset + entrySize)
                return
            }
        } finally {
            bufferPool.unpinPage(lastPage.id, isDirty = true)
        }

        val newPage = bufferPool.newPage()
        try {
            initEmptyPage(newPage)
            val freeOffset = readFreeOffset(newPage)
            writeTupleAt(newPage, freeOffset, tupleBytes)
            writeTupleCount(newPage, 1)
            writeFreeOffset(newPage, freeOffset + entrySize)
        } finally {
            bufferPool.unpinPage(newPage.id, isDirty = true)
        }
    }

    fun scan(): Sequence<Tuple> = sequence {
        for (pageNo in 0 until pagedFile.pageCount()) {
            val page = bufferPool.fetchPage(PageId(pagedFile.fileId, pageNo))
            val tuples: List<Tuple> = try {
                val count = readTupleCount(page)
                var offset = HEADER_SIZE
                val list = mutableListOf<Tuple>()
                repeat(count) {
                    val len = ByteBuffer.wrap(page.read(offset, 4)).int
                    val bytes = page.read(offset + 4, len)
                    list += Tuple.decode(schema, bytes)
                    offset += 4 + len
                }
                list
            } finally {
                bufferPool.unpinPage(page.id, isDirty = false)
            }
            yieldAll(tuples)
        }
    }

    fun rowCount(): Int {
        var total = 0
        for (pageNo in 0 until pagedFile.pageCount()) {
            val page = bufferPool.fetchPage(PageId(pagedFile.fileId, pageNo))
            try { total += readTupleCount(page)}
            finally { bufferPool.unpinPage(page.id, isDirty = false)}
        }
        return total
    }

    // page layout: [4:tupleCount][4: freeOffset][entries: 4+bytes반복]
    private fun initEmptyPage(page: Page) {
        writeTupleCount(page, 0)
        writeFreeOffset(page, HEADER_SIZE)
    }

    private fun readTupleCount(page: Page): Int = ByteBuffer.wrap(page.read(0, 4)).int
    private fun writeTupleCount(page: Page, count: Int) {
        page.write(0, ByteBuffer.allocate(4).putInt(count).array())
    }
    private fun readFreeOffset(page: Page): Int = ByteBuffer.wrap(page.read(4, 4)).int
    private fun writeFreeOffset(page: Page, offset: Int) {
        page.write(4, ByteBuffer.allocate(4).putInt(offset).array())
    }
    private fun writeTupleAt(page: Page, offset: Int, tupleBytes: ByteArray) {
        page.write(offset, ByteBuffer.allocate(4).putInt(tupleBytes.size).array())
        page.write(offset + 4, tupleBytes)
    }

    override fun close() { bufferPool.flushAll() }

    companion object { const val HEADER_SIZE: Int = 8 }
}