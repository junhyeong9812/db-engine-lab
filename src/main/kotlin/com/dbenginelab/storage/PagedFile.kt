package com.dbenginelab.storage

import java.io.Closeable;
import java.io.RandomAccessFile;

class PagedFile(path: String, val fileId: Int = 0) : Closeable {
    private val file: RandomAccessFile = RandomAccessFile(path, "rw")

    fun pageCount(): Int = (file.length() / Page.PAGE_SIZE).toInt()

    fun allocatePage(): PageId {
        val newPageNumber = pageCount()
        val zeroes = ByteArray(Page.PAGE_SIZE)
        file.write(zeroes)
        return PageId(fileId, newPageNumber)
    }

    fun readPage(id: PageId): Page {
        require(id.fileId == fileId) {
            "PageId fileId=${id.fileId} does not match this file fileId=$fileId"
        }
        val totalPages = pageCount()
        if (id.pageNumber < 0 || id.pageNumber >= totalPages) {
            throw StorageError.PageNotFound(id)
        }
        val buf = ByteArray(Page.PAGE_SIZE)
        file.seek(id.pageNumber.toLong() * Page.PAGE_SIZE)
        file.readFully(buf)
        return Page(id, buf)
    }

    fun writePage(page: Page) {
        require(page.id.fileId == fileId)
        file.seek(page.id.pageNumber.toLong() * Page.PAGE_SIZE)
        file.write(page.rawData())
    }

    fun sync() { file.fd.sync() }

    override fun close() {file.close()}
}