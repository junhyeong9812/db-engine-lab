# impl/06-01 — TableHeap + Operator + SeqScan + InsertOp (한 줄 한 줄)

> **검증**: SeqScanTest 3 PASSED.
> 작성 파일:
> - 신규 패키지: `src/main/kotlin/com/dbenginelab/{table,executor}/`
> - 신규: TableHeap.kt, Operator.kt, SeqScan.kt, InsertOp.kt
> - 신규 테스트: SeqScanTest.kt

## 0. 참조
- SimpleDB `HeapFile/HeapPage/OpIterator/SeqScan` — slot directory 미사용, sequential-only로 단순화.
- BusTub `executor`, `table_heap` — Volcano pattern.

## 1. invariant
- insert → scan 시 모든 tuple 반환 (insertion order).
- multi-page heap에서도 끊김 없이 scan.
- reopen 후 데이터 보존.
- tuple이 page 크기 초과 → throw.

## 2. TableHeap.kt — 한 줄 한 줄

작성 위치: `src/main/kotlin/com/dbenginelab/table/TableHeap.kt`

```kotlin
package com.dbenginelab.table                                        // 신규 table 패키지

import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.storage.BufferPool
import com.dbenginelab.storage.Page
import com.dbenginelab.storage.PageId
import com.dbenginelab.storage.PagedFile
import java.io.Closeable
import java.nio.ByteBuffer

class TableHeap(
    val schema: TableSchema,                                         // 어떤 schema의 row 저장
    private val pagedFile: PagedFile,
    private val bufferPool: BufferPool,
) : Closeable {

    init {
        if (pagedFile.pageCount() == 0) {                            // 빈 파일이면 첫 page 생성
            val page = bufferPool.newPage()
            initEmptyPage(page)
            bufferPool.unpinPage(page.id, isDirty = true)
        }
    }

    fun insert(tuple: Tuple) {
        require(tuple.schema == schema)
        val tupleBytes = tuple.encode()
        val entrySize = 4 + tupleBytes.size                          // length-prefix + bytes
        require(entrySize + HEADER_SIZE <= Page.PAGE_SIZE) {         // Q: page 초과면 throw?
            "tuple too large (${entrySize} bytes) for page size ${Page.PAGE_SIZE}"
        }
        // <details><summary>A</summary>
        // 단계 6 단순화 — overflow page 미지원. 단계 8 WAL/page format 강화 시 검토.
        // </details>

        val lastPageNo = pagedFile.pageCount() - 1                   // 마지막 page에 fit 시도
        val lastPage = bufferPool.fetchPage(PageId(pagedFile.fileId, lastPageNo))
        try {
            val freeOffset = readFreeOffset(lastPage)
            if (freeOffset + entrySize <= Page.PAGE_SIZE) {          // 여유 있으면 append
                writeTupleAt(lastPage, freeOffset, tupleBytes)
                writeTupleCount(lastPage, readTupleCount(lastPage) + 1)
                writeFreeOffset(lastPage, freeOffset + entrySize)
                return
            }
        } finally {
            bufferPool.unpinPage(lastPage.id, isDirty = true)
        }

        // 마지막 page 꽉참 → 새 page 할당
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

    fun scan(): Sequence<Tuple> = sequence {                         // Q: lazy Sequence?
        for (pageNo in 0 until pagedFile.pageCount()) {
            val page = bufferPool.fetchPage(PageId(pagedFile.fileId, pageNo))
            val tuples: List<Tuple> = try {
                val count = readTupleCount(page)
                var offset = HEADER_SIZE
                val list = mutableListOf<Tuple>()
                repeat(count) {                                       // page 내 모든 tuple
                    val len = ByteBuffer.wrap(page.read(offset, 4)).int
                    val bytes = page.read(offset + 4, len)
                    list += Tuple.decode(schema, bytes)
                    offset += 4 + len
                }
                list
            } finally {
                bufferPool.unpinPage(page.id, isDirty = false)
            }
            yieldAll(tuples)                                          // page 단위로 yield
        }
        // <details><summary>A</summary>
        // page 전체를 list로 materialize 후 yieldAll — page IO 동안 unpin 안전. 진짜 streaming은 단계 13.
        // </details>
    }

    fun rowCount(): Int {
        var total = 0
        for (pageNo in 0 until pagedFile.pageCount()) {
            val page = bufferPool.fetchPage(PageId(pagedFile.fileId, pageNo))
            try { total += readTupleCount(page) }
            finally { bufferPool.unpinPage(page.id, isDirty = false) }
        }
        return total
    }

    // page layout: [4: tupleCount][4: freeOffset][entries: 4+bytes 반복]
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

    companion object { const val HEADER_SIZE: Int = 8 }              // tupleCount + freeOffset
}
```

## 3. Operator.kt + SeqScan.kt + InsertOp.kt — 한 줄 한 줄

```kotlin
// Operator.kt
package com.dbenginelab.executor                                     // 신규 executor 패키지
import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple

interface Operator {                                                 // Volcano model
    val outputSchema: TableSchema                                    // Q: 왜 schema 노출?
    fun iterator(): Sequence<Tuple>
}
// <details><summary>A</summary>
// Project가 schema 변경 — caller가 결과 컬럼 알아야. 각 operator가 자기 output schema 책임.
// </details>
```

```kotlin
// SeqScan.kt
package com.dbenginelab.executor
import com.dbenginelab.table.TableHeap
import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple

class SeqScan(private val heap: TableHeap) : Operator {
    override val outputSchema: TableSchema = heap.schema             // heap schema 그대로
    override fun iterator(): Sequence<Tuple> = heap.scan()           // delegate
}
```

```kotlin
// InsertOp.kt
package com.dbenginelab.executor
import com.dbenginelab.table.TableHeap
import com.dbenginelab.catalog.Tuple

// Q: 왜 Operator 안 됨?
class InsertOp(private val heap: TableHeap) {
    fun insertOne(tuple: Tuple) { heap.insert(tuple) }
    fun insertMany(tuples: Iterable<Tuple>): Int {
        var count = 0
        for (t in tuples) { heap.insert(t); count++ }
        return count
    }
}
// <details><summary>A</summary>
// mutation은 result tuple 의미 작음. Operator의 iterator() semantics와 안 맞음. 단계 12 SQL DML에서 통일.
// </details>
```

## 4. 검증 (3 PASSED)
- insert 후 SeqScan으로 같은 tuple
- 대량 insert로 multi-page heap (n=500)
- reopen 후 데이터 보존

## 5. 다음 한계
- WHERE/SELECT col 없음 → **06-02 Filter+Project**.
- Constraint 검증 없음 → **06-03**.
- DELETE/UPDATE 없음 → 단계 7+.
