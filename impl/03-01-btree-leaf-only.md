# impl/03-01 — BTreeIndex (leaf only, no split)

> 상위 단계: `docs/stages/03-index.md`
> 이 세션의 범위: BTreePage view + 단일 leaf BTreeIndex (insert/search). split은 03-02.
> 예상 타이핑 시간: 40~60분.
> **✅ Claude 검증 완료: BTreeIndexTest 6 PASSED. 누적 17/17.**

---

## 0. 참조 출처

### 주 참조 (SimpleDB)
- `simpledb/index/BTreeFile.java`, `BTreeLeafPage.java` — page layout / slot 개념.
- 단순화: 우리는 Long key only (단계 4 type 도입 전), single leaf로 시작.

### 대조 참조 (BusTub)
- `src/include/storage/page/b_plus_tree_leaf_page.h` — leaf header + entries 패턴 그대로 차용.
- 차이: BusTub은 page-level latch 포함 (단계 9). 우리는 미도입.

### 핵심 설계 결정 근거
- **BTreePage는 view class** (Page를 wrap, byte offset 기반 read/write) — `data class` 부적합 (mutable byte 위 view).
- **ByteBuffer로 endian 안전** — 직접 비트 시프트보다 안전, 학습 코드.
- **leaf only 시작** — split까지 한 세션에 넣으면 디버깅 복잡, 학습 효율 ↓.

---

## 1. invariant
- **CI-1**: insert(k, v) → search(k) = v.
- **CI-2**: 정렬 순서 유지 (key 오름차순).
- **CI-3**: duplicate key는 reject (단계 5 UNIQUE constraint 전).

## 2. 의존성
- 02-01, 02-02 (Page, PagedFile, BufferPool).

---

## 3. 문제 정의

단계 2의 BufferPool로 page IO 효율은 좋아졌지만, 특정 key를 찾으려면 여전히 풀스캔이 필요하다. 인덱스가 필요 — 정렬된 자료구조로 O(log N) search.

**가장 단순한 인덱스: 단일 page에 정렬된 (key, value) 슬롯**. binary search로 O(log N).

단점: 한 page (255 entries) 넘으면 못 들어감. → 03-02에서 split + multi-leaf.

---

## 4. 실패 테스트

```kotlin
@Test
fun `insert 후 search로 같은 값 찾는다`(@TempDir tempDir: Path) {
    val path = tempDir.resolve("btree.db").toString()
    PagedFile(path).use { pf ->
        BufferPool(pf, capacity = 16).use { bp ->
            val idx = BTreeIndex(pf, bp)
            idx.insert(42L, 1000L)
            assertEquals(1000L, idx.search(42L))
        }
    }
}
```

→ `Unresolved reference: BTreeIndex`. 5번에서 구현.

---

## 5. 구현 코드

### 5.1 `BTreePage.kt` — page를 BTree 노드로 해석

핵심 idea: page byte 위에 view를 얹어서, header (type/keyCount/auxPage/parentPage)와 entries (16-byte key+value 슬롯들)을 읽고 쓴다.

```kotlin
package com.dbenginelab.storage

import java.nio.ByteBuffer

class BTreePage(val page: Page) {

    enum class Type(val code: Byte) {
        LEAF(0), INTERNAL(1);
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

    // LEAF: next leaf page. INTERNAL: leftmost child page.
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
        // Q: 왜 count downTo slot + 1 ? slot..count - 1로는 안 되나?
        for (i in count downTo slot + 1) {
            setEntry(i, keyAt(i - 1), valueAt(i - 1))
        }
        // <details><summary>A</summary>
        //
        // 오른쪽부터 한 칸씩 밀어야 overwrite 안 됨 — 왼쪽부터 가면 다음 entry가 이미 덮인 값으로 옮겨짐.
        // </details>
        setEntry(slot, key, value)
        keyCount = count + 1
    }

    /** Binary search returning first slot with key >= target (or keyCount). */
    fun findSlot(target: Long): Int {
        var lo = 0
        var hi = keyCount
        while (lo < hi) {
            // Q: (lo + hi) / 2 가 아니라 ushr 1을 쓰는 이유?
            val mid = (lo + hi) ushr 1
            if (keyAt(mid) < target) lo = mid + 1 else hi = mid
        }
        // <details><summary>A</summary>
        //
        // (lo+hi)가 Int 오버플로우 가능 — ushr는 signed bit 영향 없이 정확. JDK Arrays.binarySearch와 같은 관용구.
        // </details>
        return lo
    }

    fun isFull(): Boolean = keyCount >= MAX_ENTRIES

    fun initAsEmpty(type: Type, parentPage: Int = INVALID, auxPage: Int = INVALID) {
        this.type = type
        this.keyCount = 0
        this.parentPage = parentPage
        this.auxPage = auxPage
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
        const val KEY_SIZE: Int = 8
        const val VALUE_SIZE: Int = 8
        const val ENTRY_SIZE: Int = KEY_SIZE + VALUE_SIZE  // 16
        // Header: type(1) + keyCount(4) + auxPage(4) + parentPage(4) = 13
        const val HEADER_SIZE: Int = 13
        const val MAX_ENTRIES: Int = (Page.PAGE_SIZE - HEADER_SIZE) / ENTRY_SIZE  // 255
    }
}
```

### 5.2 `BTreeIndex.kt` — single leaf only

```kotlin
package com.dbenginelab.storage

import java.io.Closeable

class BTreeIndex(
    private val pagedFile: PagedFile,
    private val bufferPool: BufferPool,
) : Closeable {

    init {
        // Q: 왜 pageCount == 0 일 때만 root 만드나? 매번 만들면 안 되나?
        if (pagedFile.pageCount() == 0) {
            val page = bufferPool.newPage()
            check(page.id.pageNumber == ROOT_PAGE_NUMBER) {
                "first allocated page must be root (page 0), got ${page.id.pageNumber}"
            }
            BTreePage(page).initAsEmpty(BTreePage.Type.LEAF)
            bufferPool.unpinPage(page.id, isDirty = true)
        }
        // <details><summary>A</summary>
        //
        // reopen 시 page 0이 이미 root로 있음 — 다시 init하면 기존 데이터 날아감.
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
                // Q: split 대신 throw하는 이유? 단계 진행 중 throw는 사용자에게 무엇을 알려주나?
                throw UnsupportedOperationException(
                    "BTree leaf full (${BTreePage.MAX_ENTRIES} entries). Split is added in stage 3-2."
                )
                // <details><summary>A</summary>
                //
                // 학습용 — 단계 3-2 진입 동기 ("이 한계가 깨졌을 때 다음 단계가 필요"). production에서는 silent 동작 안 됨.
                // </details>
            }
            btp.insertAt(slot, key, value)
        } finally {
            // Q: try-finally로 unpin을 감싸는 이유?
            bufferPool.unpinPage(page.id, isDirty = true)
            // <details><summary>A</summary>
            //
            // 중간에 throw해도 unpin이 보장돼야 — 안 하면 BufferPool에 pin이 남아 그 page 영구히 evict 불가.
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

    fun size(): Int {
        val page = bufferPool.fetchPage(PageId(pagedFile.fileId, ROOT_PAGE_NUMBER))
        try {
            return BTreePage(page).keyCount
        } finally {
            bufferPool.unpinPage(page.id, isDirty = false)
        }
    }

    override fun close() { bufferPool.flushAll() }

    companion object {
        const val ROOT_PAGE_NUMBER: Int = 0
    }
}
```

---

## 6. 검증 테스트

(`BTreeIndexTest` — 6 tests PASSED, 누적 17/17)

---

## 7. 직접 깨뜨릴 과제

- 과제 1: insert 도중 process kill (close 호출 안 함) → reopen 시 데이터 어떻게 되나? BufferPool flush와의 관계는?
- 과제 2: PAGE_SIZE를 1024로 줄이면 MAX_ENTRIES가 몇이 되고 어떤 테스트 깨지나?
- 과제 3: `findSlot`의 `(lo + hi) / 2`로 바꾸면 어떤 입력 (얼마나 큰 keyCount)에서 깨지나? `ushr 1`이 왜 필요한가?

---

## 8. 다음 한계

- 255번째 entry 후 insert → `UnsupportedOperationException`. → **03-02 split**.
- 정확 매칭만 가능, range scan 없음. → **03-03 range scan**.

---

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 — Claude 검증 완료 |
