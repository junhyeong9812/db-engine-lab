# impl/06-01 — TableHeap + Operator + SeqScan + InsertOp

> **종류**: 세션형
> **상위 단계**: `docs/stages/06-query-api.md`
> **코드 정본**: git `7285798` — "stage 6-1: TableHeap + Operator + SeqScan + InsertOp (36 tests)"
> **이 세션의 범위**: 행을 page에 담는 저장소(`TableHeap`)와, 그것을 한 건씩 뱉는 연산자(`SeqScan`). 여기서 **질의 실행기의 뼈대**가 생긴다.
> **작성 파일**:
> - 신규 패키지: `src/main/kotlin/com/dbenginelab/table/` · `src/main/kotlin/com/dbenginelab/executor/`
> - 신규: `table/TableHeap.kt` · `executor/Operator.kt` · `executor/SeqScan.kt` · `executor/InsertOp.kt`
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/executor/SeqScanTest.kt`
> **검증**: `SeqScanTest` 3 PASSED · 전체 누적 36 PASSED
> **예상 타이핑 시간**: 60분

---

## 0. 참조

- 주 참조: SimpleDB `HeapFile` / `HeapPage` / `OpIterator` / `SeqScan`.
- 대조 참조: BusTub `executor`, `table_heap` — Volcano(iterator) 모델.
- **차이 채택 여부**: SimpleDB의 slot directory(page 안에서 빈 자리를 재사용하는 구조)는 **채택 안 함**. 우리는 append-only + sequential 배치로 단순화한다. delete가 없는 동안은 slot directory가 할 일이 없다.

## 1. 만족시킬 invariant

- **CI-1**: insert한 모든 tuple이 scan에서 **삽입 순서대로** 나온다.
- **CI-2**: multi-page heap에서도 page 경계를 넘어 끊김 없이 scan된다.
- **CI-3**: reopen 후에도 데이터가 보존된다.
- **CI-4**: page 크기를 넘는 tuple은 거부된다.

## 2. 의존성

- 이전 세션: `impl/05-01-constraints.md` (`TableSchema`, `Tuple`)
- storage: `PagedFile`, `BufferPool`, `Page`

## 3. 문제 정의 (TDD step 1)

단계 4에서 `Tuple`을 바이트로 굽는 법을 배웠고, 단계 2에서 page에 쓰는 법을 배웠다. 아직 안 배운 것은 **그 둘을 잇는 규칙**이다.

- page 안에 tuple을 여러 개 담으려면 각 tuple의 경계를 알아야 한다 → 01-01에서 쓴 length-prefix와 같은 발상.
- page가 꽉 차면 새 page를 할당하고 이어서 담는다 → scan은 그 page들을 순서대로 훑는다.
- tuple 하나가 page보다 크면? 이 설계로는 담을 수 없다 → 거부한다(CI-4). 진짜 DB는 여기서 TOAST/overflow page를 쓴다.

그리고 두 번째 축 — **연산자(Operator)** 라는 인터페이스가 등장한다. 지금은 `SeqScan` 하나뿐이라 과해 보이지만, 이 인터페이스가 있어야 다음 세션에서 `Filter`가 `SeqScan`을 감싸고, 그 위를 `Project`가 감쌀 수 있다. 질의 실행이 **연산자를 쌓아 만든 파이프**가 되는 것이 Volcano 모델이다.

## 4. 실패 테스트 (TDD step 2)

```kotlin
// src/test/kotlin/com/dbenginelab/executor/SeqScanTest.kt @ 7285798
package com.dbenginelab.executor

import com.dbenginelab.catalog.ColumnDef
import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.catalog.Type
import com.dbenginelab.storage.BufferPool
import com.dbenginelab.storage.PagedFile
import com.dbenginelab.table.TableHeap
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

class SeqScanTest {

    private fun userSchema() = TableSchema(
        name = "users",
        columns = listOf(
            ColumnDef("id", Type.BIGINT, nullable = false),
            ColumnDef("name", Type.STRING, nullable = false),
            ColumnDef("age", Type.INT, nullable = true),
        ),
    )

    @Test
    fun `insert 후 SeqScan으로 같은 tuple 반환`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("users.data").toString()
        val schema = userSchema()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val ins = InsertOp(heap)
                ins.insertOne(Tuple(schema, listOf(1L, "Alice", 30)))
                ins.insertOne(Tuple(schema, listOf(2L, "Bob", null)))
                ins.insertOne(Tuple(schema, listOf(3L, "Charlie", 25)))

                val scan = SeqScan(heap)
                val tuples = scan.iterator().toList()
                assertEquals(3, tuples.size)
                assertEquals(1L, tuples[0].get("id"))
                assertEquals("Bob", tuples[1].get("name"))
                assertEquals(null, tuples[1].get("age"))
                assertEquals(25, tuples[2].get("age"))
            }
        }
    }

    @Test
    fun `대량 insert로 multi-page heap 검증 (rowCount + scan)`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("big.data").toString()
        val schema = userSchema()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 32).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val n = 500
                val tuples = (1..n).map { Tuple(schema, listOf(it.toLong(), "name-$it", it)) }
                InsertOp(heap).insertMany(tuples)
                assertEquals(n, heap.rowCount())

                val scanned = SeqScan(heap).iterator().toList()
                assertEquals(n, scanned.size)
                for (i in 0 until n) {
                    assertEquals((i + 1).toLong(), scanned[i].get("id"))
                }
            }
        }
    }

    @Test
    fun `reopen 후에도 데이터 보존`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("persist.data").toString()
        val schema = userSchema()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                InsertOp(heap).insertOne(Tuple(schema, listOf(42L, "kept", 100)))
                heap.close()
            }
        }
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val t = SeqScan(heap).iterator().toList()
                assertEquals(1, t.size)
                assertEquals(42L, t[0].get("id"))
                assertEquals("kept", t[0].get("name"))
            }
        }
    }
}
```

**예상 실패**: **컴파일 실패** — `Unresolved reference: TableHeap`, `SeqScan`, `InsertOp`.

## 5. 구현 코드 (TDD step 3 — make it pass)

### 5.1 `table/TableHeap.kt`

```kotlin
// src/main/kotlin/com/dbenginelab/table/TableHeap.kt @ 7285798
package com.dbenginelab.table

import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.storage.BufferPool
import com.dbenginelab.storage.Page
import com.dbenginelab.storage.PageId
import com.dbenginelab.storage.PagedFile
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * Heap-organized tuple storage. Each page layout:
 *   [4 bytes: tupleCount]
 *   [4 bytes: freeOffset]  (where the next tuple would be written)
 *   then variable-size entries:
 *     [4 bytes: tuple length][tuple bytes...]
 *
 * Tuples are appended sequentially within a page; when a page can't fit
 * the new tuple, a new page is allocated. No in-page slot reuse (stage 6-1
 * simplification — delete/update is stage 7+).
 */
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
        require(tuple.schema == schema) { "tuple schema mismatch" }
        val tupleBytes = tuple.encode()
        val entrySize = 4 + tupleBytes.size
        require(entrySize + HEADER_SIZE <= Page.PAGE_SIZE) {
            "tuple too large (${entrySize} bytes) for page size ${Page.PAGE_SIZE}"
        }

        // Try last page first.
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

        // Allocate new page.
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

    /** Sequential scan iterator. Materializes one page at a time. */
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
            try {
                total += readTupleCount(page)
            } finally {
                bufferPool.unpinPage(page.id, isDirty = false)
            }
        }
        return total
    }

    // page layout: [4: tupleCount][4: freeOffset][entries: 4+bytes 반복]
    private fun initEmptyPage(page: Page) {
        writeTupleCount(page, 0)
        writeFreeOffset(page, HEADER_SIZE)
    }

    private fun readTupleCount(page: Page): Int =
        ByteBuffer.wrap(page.read(0, 4)).int

    private fun writeTupleCount(page: Page, count: Int) {
        page.write(0, ByteBuffer.allocate(4).putInt(count).array())
    }

    private fun readFreeOffset(page: Page): Int =
        ByteBuffer.wrap(page.read(4, 4)).int

    private fun writeFreeOffset(page: Page, offset: Int) {
        page.write(4, ByteBuffer.allocate(4).putInt(offset).array())
    }

    private fun writeTupleAt(page: Page, offset: Int, tupleBytes: ByteArray) {
        page.write(offset, ByteBuffer.allocate(4).putInt(tupleBytes.size).array())
        page.write(offset + 4, tupleBytes)
    }

    override fun close() {
        bufferPool.flushAll()
    }

    companion object {
        const val HEADER_SIZE: Int = 8  // tupleCount(4) + freeOffset(4)
    }
}
```

### 5.2 `executor/Operator.kt` — 파이프의 인터페이스

```kotlin
// src/main/kotlin/com/dbenginelab/executor/Operator.kt @ 7285798
package com.dbenginelab.executor

import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple

/**
 * Volcano-model operator. Producers implement [iterator]; consumers consume the
 * Sequence by `iterator()` or operator chaining.
 *
 * Output schema may differ from input (Project) so each operator exposes its own.
 */
interface Operator {
    val outputSchema: TableSchema
    fun iterator(): Sequence<Tuple>
}
```

### 5.3 `executor/SeqScan.kt`

```kotlin
// src/main/kotlin/com/dbenginelab/executor/SeqScan.kt @ 7285798
// SeqScan.kt
package com.dbenginelab.executor

import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.table.TableHeap

class SeqScan(private val heap: TableHeap) : Operator {
    override val outputSchema: TableSchema = heap.schema
    override fun iterator(): Sequence<Tuple> = heap.scan()
}
```

### 5.4 `executor/InsertOp.kt`

```kotlin
// src/main/kotlin/com/dbenginelab/executor/InsertOp.kt @ 7285798
// InsertOp.kt
package com.dbenginelab.executor

import com.dbenginelab.catalog.Tuple
import com.dbenginelab.table.TableHeap

/**
 * Imperative insert (not an Operator that returns tuples — returns inserted count).
 * In stage 6, mutation operators are kept simple as direct calls; richer DML operator
 * model is added with the SQL layer (stage 12).
 */
class InsertOp(private val heap: TableHeap) {
    fun insertOne(tuple: Tuple) {
        heap.insert(tuple)
    }

    fun insertMany(tuples: Iterable<Tuple>): Int {
        var count = 0
        for (t in tuples) {
            heap.insert(t)
            count++
        }
        return count
    }
}
```

## 6. 검증 테스트 (TDD step 4 — green)

테스트 파일은 §4에서 저장한 것이 그대로 최종본이다.

```bash
./gradlew test --tests 'com.dbenginelab.executor.SeqScanTest'
```

**기대 결과**: `SeqScanTest` **3 PASSED** · 전체 누적 **36 PASSED**

invariant 대응:
- **CI-1** ← `insert 후 SeqScan으로 같은 tuple 반환`
- **CI-2** ← `대량 insert로 multi-page heap 검증 (rowCount + scan)`
- **CI-3** ← `reopen 후에도 데이터 보존`

**CI-4(page 초과 tuple 거부)는 이 3개로 검증되지 않는다.** §7 과제 2번에서 직접 확인해라 — invariant 개수와 테스트 개수가 다를 수 있다는 것을 다시 확인하는 자리다.

## 7. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

**1.** tuple 앞의 길이 프리픽스를 빼고 저장해봐라. 한 page에 tuple이 하나뿐이면 통과한다 — 두 개부터 무엇이 어긋나나?

<details><summary>답</summary>

page 레이아웃이 이렇게 생겼다:

```
[4B tupleCount][4B freeOffset][4B len][tuple bytes][4B len][tuple bytes]…
```

tuple이 **하나뿐이면** 길이가 없어도 읽을 수 있다 — `freeOffset`까지가 곧 그 tuple이기 때문이다. 우연히 동작한다.

둘부터는 첫 tuple이 어디서 끝나는지 알 방법이 없다. `Tuple.decode`는 스키마를 보고 필요한 만큼 읽으므로 **고정 길이 컬럼만 있으면** 계산이 가능하지만, `STRING`이 하나라도 있으면 tuple마다 길이가 달라 불가능하다.

01-01의 record 경계 문제와 **완전히 같은 문제**이고 같은 해법이다. 단계가 올라가도 "가변 길이를 나열하려면 경계를 적어야 한다"는 규칙은 계속 따라온다 — 04-01의 NULL bitmap, 14-01의 메시지 프레임까지 같은 계열이다.
</details>

**2.** `Page.PAGE_SIZE`보다 긴 문자열을 가진 tuple을 insert해봐라. 무슨 예외가 나오나?

<details><summary>답</summary>

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
require(entrySize + HEADER_SIZE <= Page.PAGE_SIZE) {
    "tuple too large (${entrySize} bytes) for page size ${Page.PAGE_SIZE}"
}
```

`IllegalArgumentException("tuple too large …")`. **page 하나를 넘는 행은 이 설계로 담을 수 없다** — 넘어가는 부분을 다음 page로 이어붙이는 코드가 없기 때문이다.

PostgreSQL은 **TOAST**(The Oversized-Attribute Storage Technique)로 푼다: 행이 page(8KB)의 약 1/4를 넘으면 큰 컬럼을 압축하거나, 그래도 크면 **별도의 TOAST 테이블에 조각내 저장하고 본 행에는 포인터만 남긴다.** 조회할 때 필요하면 그때 조각을 모은다.

핵심 발상은 "page 크기를 키우자"가 아니라 **"큰 값은 밖으로 빼고 참조만 남긴다"**는 것이다. page를 키우면 작은 행들의 IO 효율이 나빠지기 때문이다.
</details>

**3.** `SeqScan`이 page를 다 읽고 `unpinPage`를 부르지 않도록 고쳐라. 몇 page째에서 무슨 일이 일어나나?

<details><summary>답</summary>

`BufferPool` capacity + 1번째 page를 fetch할 때 `StorageError.AllPagesPinned`가 난다.

**그런데 지금 테스트로는 안 잡힌다.** 계산해보면 이유가 보인다:

```
schema (id BIGINT 8B, name STRING ~11B, age INT 4B) + NULL bitmap 1B ≈ 24B
+ 길이 프리픽스 4B = 28B
page당 (4096 - 8) / 28 ≈ 146개
500건 → 약 4 page
```

가장 큰 테스트(`대량 insert로 multi-page heap 검증`)가 500건이라 **4 page**밖에 안 쓰는데, `BufferPool` capacity는 32다. 자리가 남아도니 evict가 아예 일어나지 않고, pin이 새도 드러나지 않는다.

잡으려면 **page 수가 capacity를 넘도록** 데이터를 늘리거나 capacity를 4 미만으로 줄여야 한다. 직접 `BufferPool(pf, capacity = 2)`로 바꿔 돌려봐라 — 그때 비로소 터진다.

여기서 볼 것: **자원 누수 테스트는 자원을 부족하게 만들어야 성립한다.** 넉넉한 환경에서는 누수가 있어도 증상이 없다.
</details>

**4.** 같은 `TableHeap`에 `InsertOp` 두 개가 번갈아 insert하면 순서는 보장되나?

<details><summary>답</summary>

**단일 스레드라면 보장된다.** `InsertOp`은 상태를 갖지 않고 `heap.insert(tuple)`을 그대로 넘길 뿐이라, 두 개든 열 개든 같은 heap을 순서대로 건드린다.

보장의 근거는 `TableHeap` 쪽에 있다:
- `insert`는 항상 **마지막 page의 `freeOffset` 위치에 이어 붙인다.**
- `scan`은 page 번호 순으로, page 안에서는 offset 순으로 읽는다.

쓰는 순서 = 저장 위치 순서 = 읽는 순서이므로 삽입 순서가 그대로 나온다.

**멀티 스레드면 깨진다.** `insert`가 `freeOffset`을 읽고 → tuple을 쓰고 → `freeOffset`을 갱신하는 read-modify-write인데 보호가 없다. 02-01 과제 3번(`allocatePage` 경합)과 같은 구조다:

```
A: freeOffset 읽음 = 100
B: freeOffset 읽음 = 100
A: 100에 씀, freeOffset = 128
B: 100에 씀, freeOffset = 128   ← A가 쓴 것을 덮어씀
```

tuple 하나가 사라지는데 `tupleCount`는 2가 되어 **개수와 내용이 어긋난다.** 단계 9 lock이 필요한 이유가 여기서도 나온다.
</details>

## 8. 다음 한계

지금 할 수 있는 질의는 `SELECT * FROM t` 하나뿐이다. `WHERE`도 없고 컬럼 고르기도 없다.

→ **06-02 Filter · Project · Expression**. `Operator` 인터페이스를 만들어둔 값을 여기서 처음 받는다 — `Filter(SeqScan(heap), 조건)` 처럼 감싸기만 하면 된다.
