package com.dbenginelab.storage

import java.io.Closeable

/**
 * B+tree index storing Long → Long mappings.
 *
 * Stage 3-1 limitations:
 *  - Single-leaf only. No split, no internal nodes.
 *  - When the leaf fills up (MAX_ENTRIES = 255), insert throws UnsupportedOperationException.
 *  - No duplicate keys.
 *  - No delete.
 *
 * Multi-leaf with split is added in stage 3-2; multi-level in stage 3-3.
 */
class BTreeIndex(
    private val pagedFile: PagedFile,
    private val bufferPool: BufferPool,
) : Closeable {

    init {
        if (pagedFile.pageCount() == 0) {
            val page = bufferPool.newPage()
            check(page.id.pageNumber == ROOT_PAGE_NUMBER) {
                "first allocated page must be root (page 0), got ${page.id.pageNumber}"
            }
            BTreePage(page).initAsEmpty(BTreePage.Type.LEAF)
            bufferPool.unpinPage(page.id, isDirty = true)
        }
    }

    fun insert(key: Long, value: Long) {
        val page = bufferPool.fetchPage(PageId(pagedFile.fileId, ROOT_PAGE_NUMBER))
        try {
            val btp = BTreePage(page)
            val slot = btp.findSlot(key)
            require(!(slot < btp.keyCount && btp.keyAt(slot) == key)) {
                "duplicate key not supported in stage 3: $key"
            }
            if (btp.isFull()) {
                throw UnsupportedOperationException(
                    "BTree leaf full (${BTreePage.MAX_ENTRIES} entries). Split is added in stage 3-2."
                )
            }
            btp.insertAt(slot, key, value)
        } finally {
            bufferPool.unpinPage(page.id, isDirty = true)
        }
    }

    fun search(key: Long): Long? {
        val page = bufferPool.fetchPage(PageId(pagedFile.fileId, ROOT_PAGE_NUMBER))
        try {
            val btp = BTreePage(page)
            val slot = btp.findSlot(key)
            return if (slot < btp.keyCount && btp.keyAt(slot) == key) btp.valueAt(slot) else null
        } finally {
            bufferPool.unpinPage(page.id, isDirty = false)
        }
    }

    fun size(): Int {
        val page = bufferPool.fetchPage(PageId(pagedFile.fileId, ROOT_PAGE_NUMBER))
        try {
            return BTreePage(page).keyCount
        } finally {
            bufferPool.unpinPage(page.id, isDirty = false)
        }
    }

    override fun close() {
        bufferPool.flushAll()
    }

    companion object {
        const val ROOT_PAGE_NUMBER: Int = 0
    }
}
