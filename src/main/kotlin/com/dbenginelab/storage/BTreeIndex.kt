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
        val leafPageNumber = findLeafPage(key)
        insertIntoLeaf(leafPageNumber, key, value)
    }

    fun search(key: Long): Long? {
        val leafPageNumber = findLeafPage(key)
        val page = bufferPool.fetchPage(PageId(pagedFile.fileId, leafPageNumber))
        try {
            val btp = BTreePage(page)
            val slot = btp.findSlot(key)
            return if (slot < btp.keyCount && btp.keyAt(slot) == key) btp.valueAt(slot) else null
        } finally {
            bufferPool.unpinPage(page.id, isDirty = false)
        }
    }

    /**
     * Range scan: returns all (key, value) pairs where [fromInclusive] <= key < [toExclusive],
     * in key ascending order. Uses leaf sibling pointers (auxPage on leaves).
     */
    fun rangeScan(fromInclusive: Long, toExclusive: Long): List<Pair<Long, Long>> {
        require(fromInclusive <= toExclusive) { "fromInclusive ($fromInclusive) > toExclusive ($toExclusive)" }
        val result = mutableListOf<Pair<Long, Long>>()
        var leafPageNo = findLeafPage(fromInclusive)
        while (leafPageNo != BTreePage.INVALID) {
            val page = bufferPool.fetchPage(PageId(pagedFile.fileId, leafPageNo))
            try {
                val btp = BTreePage(page)
                val startSlot = btp.findSlot(fromInclusive)
                for (i in startSlot until btp.keyCount) {
                    val k = btp.keyAt(i)
                    if (k >= toExclusive) return result
                    result.add(k to btp.valueAt(i))
                }
                leafPageNo = btp.auxPage
            } finally {
                bufferPool.unpinPage(page.id, isDirty = false)
            }
        }
        return result
    }

    /** Returns total number of (key, value) entries across all leaves. */
    fun size(): Int {
        var total = 0
        var leafPageNo = leftmostLeafPage()
        while (leafPageNo != BTreePage.INVALID) {
            val page = bufferPool.fetchPage(PageId(pagedFile.fileId, leafPageNo))
            try {
                val btp = BTreePage(page)
                total += btp.keyCount
                leafPageNo = btp.auxPage
            } finally {
                bufferPool.unpinPage(page.id, isDirty = false)
            }
        }
        return total
    }

    private fun leftmostLeafPage(): Int {
        var pageNo = ROOT_PAGE_NUMBER
        while (true) {
            val page = bufferPool.fetchPage(PageId(pagedFile.fileId, pageNo))
            val btp = BTreePage(page)
            if (btp.type == BTreePage.Type.LEAF) {
                bufferPool.unpinPage(page.id, isDirty = false)
                return pageNo
            }
            val next = btp.auxPage
            bufferPool.unpinPage(page.id, isDirty = false)
            pageNo = next
        }
    }

    private fun findLeafPage(key: Long): Int {
        var pageNo = ROOT_PAGE_NUMBER
        while (true) {
            val page = bufferPool.fetchPage(PageId(pagedFile.fileId, pageNo))
            val btp = BTreePage(page)
            if (btp.type == BTreePage.Type.LEAF) {
                bufferPool.unpinPage(page.id, isDirty = false)
                return pageNo
            }
            // INTERNAL: auxPage = leftmost child. entries = (sepKey, rightChild).
            // findSlot(key) returns first slot with sepKey >= key.
            // Child for `key`:
            //   slot == 0 → auxPage (key < sepKey[0])
            //   slot > 0 → valueAt(slot - 1) (sepKey[slot-1] <= key < sepKey[slot])
            // If slot < keyCount and sepKey[slot] == key, we still want right side
            // (B+tree convention: separator key lives in right subtree's leftmost leaf).
            val slot = btp.findSlot(key)
            // B+tree separator convention: if key == sepKey, go RIGHT (separator key lives
            // in right subtree's leftmost leaf). This check must precede the slot==0 branch,
            // otherwise findSlot returning 0 (key == sepKey[0]) would send us to the leftmost
            // child where the key does NOT exist.
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
        require(!(slot < btp.keyCount && btp.keyAt(slot) == key)) {
            "duplicate key not supported in stage 3: $key"
        }
        if (!btp.isFull()) {
            btp.insertAt(slot, key, value)
            bufferPool.unpinPage(leafPage.id, isDirty = true)
            return
        }
        bufferPool.unpinPage(leafPage.id, isDirty = false)
        splitLeafAndInsert(leafPageNumber, key, value)
    }

    private fun splitLeafAndInsert(leafPageNumber: Int, key: Long, value: Long) {
        if (leafPageNumber == ROOT_PAGE_NUMBER) {
            splitRootLeaf(key, value)
            return
        }

        val leftPage = bufferPool.fetchPage(PageId(pagedFile.fileId, leafPageNumber))
        val left = BTreePage(leftPage)

        val rightPageRaw = bufferPool.newPage()
        val right = BTreePage(rightPageRaw)
        right.initAsEmpty(BTreePage.Type.LEAF, parentPage = left.parentPage, auxPage = left.auxPage)
        left.auxPage = rightPageRaw.id.pageNumber

        moveHalfTo(left, right)
        insertIntoCorrectSide(left, right, key, value)

        val separator = right.keyAt(0)
        val parentPageNo = left.parentPage

        bufferPool.unpinPage(leftPage.id, isDirty = true)
        bufferPool.unpinPage(rightPageRaw.id, isDirty = true)

        insertIntoParent(parentPageNo, separator, rightPageRaw.id.pageNumber)
    }

    private fun splitRootLeaf(key: Long, value: Long) {
        val rootPage = bufferPool.fetchPage(PageId(pagedFile.fileId, ROOT_PAGE_NUMBER))
        val rootView = BTreePage(rootPage)

        // Allocate leftChild and copy root content into it.
        val leftChildPageRaw = bufferPool.newPage()
        val leftChild = BTreePage(leftChildPageRaw)
        copyPageContent(from = rootView, to = leftChild)
        leftChild.parentPage = ROOT_PAGE_NUMBER

        // Allocate rightChild as empty leaf.
        val rightChildPageRaw = bufferPool.newPage()
        val rightChild = BTreePage(rightChildPageRaw)
        rightChild.initAsEmpty(
            type = BTreePage.Type.LEAF,
            parentPage = ROOT_PAGE_NUMBER,
            auxPage = leftChild.auxPage,
        )
        leftChild.auxPage = rightChildPageRaw.id.pageNumber

        moveHalfTo(leftChild, rightChild)
        insertIntoCorrectSide(leftChild, rightChild, key, value)

        val separator = rightChild.keyAt(0)

        // Turn root into INTERNAL with auxPage=leftChild, single entry (separator, rightChild).
        rootView.initAsEmpty(
            type = BTreePage.Type.INTERNAL,
            parentPage = BTreePage.INVALID,
            auxPage = leftChildPageRaw.id.pageNumber,
        )
        rootView.insertAt(0, separator, rightChildPageRaw.id.pageNumber.toLong())

        bufferPool.unpinPage(rootPage.id, isDirty = true)
        bufferPool.unpinPage(leftChildPageRaw.id, isDirty = true)
        bufferPool.unpinPage(rightChildPageRaw.id, isDirty = true)
    }

    private fun moveHalfTo(left: BTreePage, right: BTreePage) {
        val total = left.keyCount
        val splitPoint = total / 2
        for (i in splitPoint until total) {
            right.insertAt(right.keyCount, left.keyAt(i), left.valueAt(i))
        }
        left.keyCount = splitPoint
    }

    private fun insertIntoCorrectSide(left: BTreePage, right: BTreePage, key: Long, value: Long) {
        val firstRightKey = right.keyAt(0)
        if (key < firstRightKey) {
            val slot = left.findSlot(key)
            left.insertAt(slot, key, value)
        } else {
            val slot = right.findSlot(key)
            right.insertAt(slot, key, value)
        }
    }

    private fun copyPageContent(from: BTreePage, to: BTreePage) {
        to.type = from.type
        to.keyCount = 0
        to.auxPage = from.auxPage
        to.parentPage = from.parentPage
        val count = from.keyCount
        for (i in 0 until count) {
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
            throw UnsupportedOperationException(
                "internal node split not yet supported (stage 3-3)"
            )
        }
    }

    override fun close() {
        bufferPool.flushAll()
    }

    companion object {
        const val ROOT_PAGE_NUMBER: Int = 0
    }
}
