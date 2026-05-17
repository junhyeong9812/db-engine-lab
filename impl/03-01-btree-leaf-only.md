# impl/03-01 — BTree Leaf Only (한 줄 한 줄)

> 상위: `docs/stages/03-index.md`
> **검증**: BTreeIndexTest 6 PASSED (전체 BTree 합산).
> 작성 파일:
> - 신규: `src/main/kotlin/com/dbenginelab/storage/BTreePage.kt`
> - 신규: `src/main/kotlin/com/dbenginelab/storage/BTreeIndex.kt`
> - 신규: `src/test/kotlin/com/dbenginelab/storage/BTreeIndexTest.kt`

## 0. 참조
- SimpleDB `BTreeFile/BTreeLeafPage` (lab 5).
- BusTub `b_plus_tree_leaf_page.h`.

## 1. invariant
- CI-1: insert(k,v) → search(k) = v.
- CI-2: 정렬 순서 유지 (key 오름차순).
- CI-3: duplicate key reject (단계 5 UNIQUE 전).

## 2. 의존성
- 02 (Page, PagedFile, BufferPool).

## 3. 핵심 결정
- **BTreePage = Page view class** — page byte 위에 BTree node 해석.
- **`data class` 부적합** — mutable byte 위 view, equals/hashCode 의미 없음.
- **ByteBuffer로 endian 안전** (직접 bit shift 대신).
- **leaf only 시작** — split까지 한 세션에 넣으면 디버깅 복잡.

## 4. BTreePage.kt — 한 줄 한 줄

```kotlin
package com.dbenginelab.storage                                      // storage 패키지

import java.nio.ByteBuffer                                           // big-endian read/write

class BTreePage(val page: Page) {                                    // page를 wrap

    enum class Type(val code: Byte) {                                // LEAF / INTERNAL
        LEAF(0), INTERNAL(1);
        companion object {
            fun fromCode(c: Byte): Type = when (c) {
                0.toByte() -> LEAF
                1.toByte() -> INTERNAL
                else -> error("unknown btree page type code: $c")
            }
        }
    }

    var type: Type                                                    // header byte 0
        get() = Type.fromCode(page.read(0, 1)[0])
        set(value) { page.write(0, byteArrayOf(value.code)) }

    var keyCount: Int                                                 // header bytes 1-4
        get() = readIntAt(1)
        set(v) { writeIntAt(1, v) }

    var auxPage: Int                                                  // LEAF: next leaf. INTERNAL: leftmost child.
        get() = readIntAt(5)
        set(v) { writeIntAt(5, v) }

    var parentPage: Int                                               // header bytes 9-12
        get() = readIntAt(9)
        set(v) { writeIntAt(9, v) }

    fun keyAt(slot: Int): Long = readLongAt(HEADER_SIZE + slot * ENTRY_SIZE)
    fun valueAt(slot: Int): Long = readLongAt(HEADER_SIZE + slot * ENTRY_SIZE + KEY_SIZE)

    fun setEntry(slot: Int, key: Long, value: Long) {
        writeLongAt(HEADER_SIZE + slot * ENTRY_SIZE, key)
        writeLongAt(HEADER_SIZE + slot * ENTRY_SIZE + KEY_SIZE, value)
    }

    fun insertAt(slot: Int, key: Long, value: Long) {                // 특정 slot에 삽입
        val count = keyCount
        require(count < MAX_ENTRIES) { "btree page full (max=$MAX_ENTRIES)" }
        require(slot in 0..count)
        // Q: count downTo slot + 1 — 왜 오른쪽부터?
        for (i in count downTo slot + 1) {
            setEntry(i, keyAt(i - 1), valueAt(i - 1))
        }
        // <details><summary>A</summary>
        // 오른쪽부터 한 칸씩 밀어야 overwrite 안 됨. 왼쪽부터 가면 이미 덮인 값으로 옮겨짐.
        // </details>
        setEntry(slot, key, value)
        keyCount = count + 1
    }

    fun findSlot(target: Long): Int {                                // binary search
        var lo = 0; var hi = keyCount
        while (lo < hi) {
            // Q: (lo + hi) / 2 가 아니라 ushr 1?
            val mid = (lo + hi) ushr 1
            if (keyAt(mid) < target) lo = mid + 1 else hi = mid
            // <details><summary>A</summary>
            // (lo+hi)가 Int 오버플로우 가능. ushr는 signed bit 영향 없이 정확. JDK Arrays.binarySearch와 같은 관용구.
            // </details>
        }
        return lo                                                     // 첫 slot with key >= target (or keyCount)
    }

    fun isFull(): Boolean = keyCount >= MAX_ENTRIES

    fun initAsEmpty(type: Type, parentPage: Int = INVALID, auxPage: Int = INVALID) {
        this.type = type; this.keyCount = 0
        this.parentPage = parentPage; this.auxPage = auxPage
    }

    private fun readIntAt(offset: Int): Int = ByteBuffer.wrap(page.read(offset, 4)).int
    private fun writeIntAt(offset: Int, v: Int) {
        page.write(offset, ByteBuffer.allocate(4).putInt(v).array())
    }
    private fun readLongAt(offset: Int): Long = ByteBuffer.wrap(page.read(offset, 8)).long
    private fun writeLongAt(offset: Int, v: Long) {
        page.write(offset, ByteBuffer.allocate(8).putLong(v).array())
    }

    companion object {
        const val INVALID: Int = -1
        const val KEY_SIZE: Int = 8                                   // Long key
        const val VALUE_SIZE: Int = 8                                 // Long value (또는 child page)
        const val ENTRY_SIZE: Int = KEY_SIZE + VALUE_SIZE            // 16 bytes per entry
        const val HEADER_SIZE: Int = 1 + 4 + 4 + 4                   // type(1) + keyCount(4) + auxPage(4) + parentPage(4) = 13
        const val MAX_ENTRIES: Int = (Page.PAGE_SIZE - HEADER_SIZE) / ENTRY_SIZE   // (4096-13)/16 = 255
    }
}
```

## 5. BTreeIndex.kt (leaf only 부분) — 한 줄 한 줄

```kotlin
package com.dbenginelab.storage
import java.io.Closeable

class BTreeIndex(
    private val pagedFile: PagedFile,
    private val bufferPool: BufferPool,
) : Closeable {

    init {
        // Q: pageCount == 0 일 때만 root 만드는 이유?
        if (pagedFile.pageCount() == 0) {
            val page = bufferPool.newPage()
            check(page.id.pageNumber == ROOT_PAGE_NUMBER)             // 첫 page = root
            BTreePage(page).initAsEmpty(BTreePage.Type.LEAF)
            bufferPool.unpinPage(page.id, isDirty = true)
        }
        // <details><summary>A</summary>
        // reopen 시 page 0이 이미 root — 다시 init하면 기존 데이터 날아감.
        // </details>
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
                    "BTree leaf full (${BTreePage.MAX_ENTRIES}). Split는 단계 3-2."
                )
            }
            btp.insertAt(slot, key, value)
        } finally {
            // Q: try-finally로 unpin 감싸는 이유?
            bufferPool.unpinPage(page.id, isDirty = true)
            // <details><summary>A</summary>
            // 중간에 throw해도 unpin 보장 — 안 하면 BufferPool에 pin이 남아 그 page 영구 evict 불가.
            // </details>
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

    fun size(): Int {                                                 // 단계 3-1에서는 root page의 keyCount
        val page = bufferPool.fetchPage(PageId(pagedFile.fileId, ROOT_PAGE_NUMBER))
        try { return BTreePage(page).keyCount }
        finally { bufferPool.unpinPage(page.id, isDirty = false) }
    }

    override fun close() { bufferPool.flushAll() }

    companion object {
        const val ROOT_PAGE_NUMBER: Int = 0                          // page 0이 영구 root
    }
}
```

## 6. 검증 (3-1 부분 6 PASSED)
- insert 후 search
- 존재하지 않는 key → null
- 정렬 안 된 순서 insert도 정렬 유지
- duplicate key → throw
- reopen 후 데이터 보존
- MAX_ENTRIES 초과 → UnsupportedOperationException

## 7. 깨뜨릴 과제
- insert 도중 process kill → reopen 시 어떻게? (BufferPool flush 관계)
- PAGE_SIZE 1024로 줄이면 MAX_ENTRIES 변화?
- `(lo+hi)/2`로 바꾸면 어떤 input에서 깨짐? (ushr vs /2)

## 8. 다음 한계
- 255 초과 insert → throw. **03-02 split**.
- range scan 없음 → **03-03**.
