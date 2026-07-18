package com.dbenginelab.storage

import java.io.Closeable

class BTreeIndex(
    private val pagedFile: PagedFile,
    private val bufferPool: BufferPool
) : Closeable {
    init {
        if (pagedFile.pageCount() == 0) {
            val page = bufferPool.newPage()
            check(page.id.pageNumber == ROOT_PAGE_NUMBER)
            BTreePage(page).initAsEmpty(BTreePage.Type.LEAF)
            bufferPool.unpinPage(page.id, isDirty = true)
        }
    }

    fun insert(key: Long, value: Long) {
        val leafPageNumber = findLeafPage(key)
        insertIntoLeaf(leafPageNumber, key, value)
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
        try { return BTreePage(page).keyCount }
        finally { bufferPool.unpinPage(page.id, isDirty = true)}
    }

    private fun findLeafPage(key: Long): Int {                           // root → leaf 따라감
        var pageNo = ROOT_PAGE_NUMBER
        while (true) {
            val page = bufferPool.fetchPage(PageId(pagedFile.fileId, pageNo))
            val btp = BTreePage(page)
            if (btp.type == BTreePage.Type.LEAF) {
                bufferPool.unpinPage(page.id, isDirty = false)
                return pageNo                                             // leaf 도달
            }
            // INTERNAL: navigation (separator convention 핵심)
            val slot = btp.findSlot(key)
            val childPageNo = when {
                slot < btp.keyCount && btp.keyAt(slot) == key -> btp.valueAt(slot).toInt()
                slot == 0 -> btp.auxPage
                else -> btp.valueAt(slot - 1).toInt()
            }
            bufferPool.unpinPage(page.id, isDirty = false)
            pageNo = childPageNo
        }
    }

    private fun insertIntoLeaf(leafPageNumber: Int, key: Long, value: Long) {
        val leafPage = bufferPool.fetchPage(PageId(pagedFile.fileId, leafPageNumber))
        val btp = BTreePage(leafPage)
        val slot = btp.findSlot(key)
        require(!(slot < btp.keyCount && btp.keyAt(slot) == key))         // duplicate reject
        if (!btp.isFull()) {                                              // 여유 있으면 그냥 insert
            btp.insertAt(slot, key, value)
            bufferPool.unpinPage(leafPage.id, isDirty = true)
            return
        }
        bufferPool.unpinPage(leafPage.id, isDirty = false)
        splitLeafAndInsert(leafPageNumber, key, value)                    // full → split
    }

    private fun splitLeafAndInsert(leafPageNumber: Int, key: Long, value: Long) {
        if (leafPageNumber == ROOT_PAGE_NUMBER) {                         // root split = 특별 처리
            splitRootLeaf(key, value); return
        }
        val leftPage = bufferPool.fetchPage(PageId(pagedFile.fileId, leafPageNumber))
        val left = BTreePage(leftPage)
        val rightPageRaw = bufferPool.newPage()                           // 새 leaf 할당
        val right = BTreePage(rightPageRaw)
        right.initAsEmpty(BTreePage.Type.LEAF, parentPage = left.parentPage, auxPage = left.auxPage)
        left.auxPage = rightPageRaw.id.pageNumber                         // sibling 포인터
        moveHalfTo(left, right)                                           // 절반 이동
        insertIntoCorrectSide(left, right, key, value)                    // 새 entry insert
        val separator = right.keyAt(0)                                    // promote할 key
        val parentPageNo = left.parentPage
        bufferPool.unpinPage(leftPage.id, isDirty = true)
        bufferPool.unpinPage(rightPageRaw.id, isDirty = true)
        insertIntoParent(parentPageNo, separator, rightPageRaw.id.pageNumber)
    }

    private fun splitRootLeaf(key: Long, value: Long) {
        val rootPage = bufferPool.fetchPage(PageId(pagedFile.fileId, ROOT_PAGE_NUMBER))
        val rootView = BTreePage(rootPage)
        // Q: 왜 root를 직접 split 안 하고 content를 새 page로 옮김?
        val leftChildPageRaw = bufferPool.newPage()
        val leftChild = BTreePage(leftChildPageRaw)
        copyPageContent(from = rootView, to = leftChild)                  // root → leftChild 복사
        leftChild.parentPage = ROOT_PAGE_NUMBER

        val rightChildPageRaw = bufferPool.newPage()
        val rightChild = BTreePage(rightChildPageRaw)
        rightChild.initAsEmpty(BTreePage.Type.LEAF, parentPage = ROOT_PAGE_NUMBER, auxPage = leftChild.auxPage)
        leftChild.auxPage = rightChildPageRaw.id.pageNumber

        moveHalfTo(leftChild, rightChild)
        insertIntoCorrectSide(leftChild, rightChild, key, value)
        val separator = rightChild.keyAt(0)

        // root를 INTERNAL로 변환
        rootView.initAsEmpty(BTreePage.Type.INTERNAL, parentPage = BTreePage.INVALID, auxPage = leftChildPageRaw.id.pageNumber)
        rootView.insertAt(0, separator, rightChildPageRaw.id.pageNumber.toLong())

        bufferPool.unpinPage(rootPage.id, isDirty = true)
        bufferPool.unpinPage(leftChildPageRaw.id, isDirty = true)
        bufferPool.unpinPage(rightChildPageRaw.id, isDirty = true)
    }

    private fun moveHalfTo(left: BTreePage, right: BTreePage) {
        val total = left.keyCount
        val splitPoint = total / 2                                        // Q: 정확히 절반인 이유?
        for (i in splitPoint until total) {
            right.insertAt(right.keyCount, left.keyAt(i), left.valueAt(i))
        }
        left.keyCount = splitPoint
    }

    private fun insertIntoCorrectSide(left: BTreePage, right: BTreePage, key: Long, value: Long) {
        val firstRightKey = right.keyAt(0)
        if (key < firstRightKey) { left.insertAt(left.findSlot(key), key, value) }
        else { right.insertAt(right.findSlot(key), key, value) }
    }

    private fun copyPageContent(from: BTreePage, to: BTreePage) {
        to.type = from.type; to.keyCount = 0
        to.auxPage = from.auxPage; to.parentPage = from.parentPage
        for (i in 0 until from.keyCount) {
            to.insertAt(to.keyCount, from.keyAt(i), from.valueAt(i))
        }
    }

    private fun insertIntoParent(parentPageNo: Int, separator: Long, rightChildPageNo: Int) {
        val parentPage = bufferPool.fetchPage(PageId(pagedFile.fileId, parentPageNo))
        val parent = BTreePage(parentPage)
        if (!parent.isFull()) {
            val slot = parent.findSlot(separator)
            parent.insertAt(slot, separator, rightChildPageNo.toLong())
            bufferPool.unpinPage(parentPage.id, isDirty = true)
        } else {
            bufferPool.unpinPage(parentPage.id, isDirty = false)
            throw UnsupportedOperationException("internal split 미구현 — 트리 높이 2 상한")
        }
    }

    override fun close() {bufferPool.flushAll()}

    companion object {
        const val ROOT_PAGE_NUMBER: Int = 0
    }
}