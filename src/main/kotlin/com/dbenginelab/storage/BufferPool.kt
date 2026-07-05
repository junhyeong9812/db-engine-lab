package com.dbenginelab.storage

import java.io.Closeable

class BufferPool(
    private val pagedFile: PagedFile,
    private val capacity: Int = DEFAULT_CAPACITY,
) : Closeable {

    private val pages: LinkedHashMap<PageId, Page> = LinkedHashMap(capacity, 0.75f, true)

    fun fetchPage(id: PageId): Page {
        pages[id]?.let { cached ->
            cached.pin()
            return cached
        }
        if (pages.size >= capacity) {
            evictOne()
        }
        val loaded = pagedFile.readPage(id)
        loaded.pin()
        pages[id] = loaded
        return loaded
    }

    fun newPage(): Page {
        val id = pagedFile.allocatePage()
        if (pages.size >= capacity) {
            evictOne()
        }
        val page = Page(id, ByteArray(Page.PAGE_SIZE))
        page.markDirty()
        page.pin()
        pages[id] = page
        return page
    }

    fun unpinPage(id: PageId, isDirty: Boolean) {
        val page = pages[id] ?: throw StorageError.PageNotInPool(id)
        if (isDirty) page.markDirty()
        page.unpin()
    }

    fun flushPage(id: PageId) {
        val page = pages[id] ?: return
        if (page.isDirty) {
            pagedFile.writePage(page)
            page.markClean()
        }
    }

    fun flushAll() {
        for (page in pages.values) {
            if (page.isDirty) {
                pagedFile.writePage(page)
                page.markClean()
            }
        }
        pagedFile.sync()
    }

    fun cachedPageCount(): Int = pages.size

    private fun evictOne() {
        val victim = pages.values.firstOrNull { it.pinCount == 0 }
            ?: throw StorageError.AllPagesPinned(capacity)

        if (victim.isDirty) {
            pagedFile.writePage(victim)
            victim.markClean()
        }
        pages.remove(victim.id)
    }

    override fun close() {
        flushAll()
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 256
    }
}