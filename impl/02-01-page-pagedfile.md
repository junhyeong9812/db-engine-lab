# impl/02-01 — Page (mutable byte container) + PagedFile

> 상위 단계: `docs/stages/02-page-buffer.md`
> 이 세션의 범위: PageId, Page (mutable, dirty/pin), PagedFile (page 단위 IO).
> 예상 타이핑 시간: 30~40분.
> **✅ Claude 검증 완료 (2026-05-16): `./gradlew test` PagedFileTest 4 PASSED.**

---

## 0. 참조 출처

### 주 참조 (SimpleDB)
- 파일: `simpledb/storage/HeapPage.java`, `BufferPool.java` (상위 절반).
- 클래스/메서드: `HeapPage(HeapPageId, byte[])`, `BufferPool.getPage()` (구조 참고).
- 우리 코드 대응: `Page(PageId, ByteArray)`, `PagedFile.readPage/writePage`.

### 대조 참조 (BusTub)
- 파일: `src/storage/disk/disk_manager.cpp`, `src/include/storage/page/page.h`.
- 차이: BusTub Page는 lock/latch 필드 포함 (단계 9에서 도입). 우리는 단계 2에서 latch 미도입.

### 핵심 설계 결정 근거
- **Page는 `class` (data class 아님)** — `ByteArray`가 mutable이라 자동 equals/hashCode가 거짓말이 됨 (codex 보정 1, constraints.md).
- **PageId는 `data class` OK** — 값 객체, immutable, equals/hashCode가 올바름.
- **`require()`로 invariant 강제** — Page size 위반은 즉시 fail.

---

## 1. 만족시킬 invariant
- **I-1**: writePage(p) → sync → reopen → readPage(p) 시 동일 byte.
- **I-2**: Page size는 모든 페이지에서 동일 (`Page.PAGE_SIZE = 4096`).
- **I-3**: pageCount() = allocate된 누적 횟수.

---

## 2. 의존성
- 이전 세션: 01-01 (Record/AppendOnlyFile/StorageError) — StorageError 확장에 사용.
- 외부: `java.io.RandomAccessFile`.

---

## 3. 문제 정의

단계 1의 raw record append는 **메모리 한계**와 **고정 단위 IO 부재**라는 두 가지 한계가 있다. 거대한 record는 한 번에 메모리에 로드되고, OS·디스크 IO 단위와 어긋난다.

해결: **고정 크기 page (4KB) 단위로 분할 + IO**. 모든 read/write는 page 단위로.

---

## 4. 실패 테스트 (TDD step 2)

```kotlin
package com.dbenginelab.storage

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PagedFileTest {

    @Test
    fun `writePage 후 reopen하면 같은 내용 read`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("paged.db").toString()
        val pid: PageId
        PagedFile(path).use { pf ->
            pid = pf.allocatePage()
            val page = pf.readPage(pid)
            val payload = "hello-page".toByteArray()
            page.write(0, payload)
            pf.writePage(page)
            pf.sync()
        }
        PagedFile(path).use { pf ->
            val page = pf.readPage(pid)
            assertContentEquals("hello-page".toByteArray(), page.read(0, "hello-page".length))
        }
    }
}
```

**실행 결과**: `Unresolved reference: PageId / Page / PagedFile`. 5번에서 구현.

---

## 5. 구현 코드

### 5.1 `PageId.kt`

```kotlin
package com.dbenginelab.storage

// Q: 왜 data class? Page는 일반 class였는데 차이가 뭔가?
data class PageId(val fileId: Int, val pageNumber: Int) {
    companion object {
        const val INVALID_PAGE_NUMBER: Int = -1
    }
}
// <details><summary>A</summary>
//
// PageId는 immutable 값 객체 (식별자) — equals/hashCode가 값 동일성으로 동작해야 BufferPool의 HashMap key로 정확.
// </details>
```

### 5.2 `Page.kt`

```kotlin
package com.dbenginelab.storage

class Page(
    val id: PageId,
    private val data: ByteArray,
) {
    var isDirty: Boolean = false
        private set

    var pinCount: Int = 0
        private set

    init {
        // Q: 이 require가 빠지면 어떤 입력에서 깨지는가?
        require(data.size == PAGE_SIZE) {
            "Page data size must be exactly $PAGE_SIZE bytes (got ${data.size})"
        }
        // <details><summary>A</summary>
        //
        // 외부에서 잘못된 크기 ByteArray 넘기면 writePage 시 page boundary 어긋나 후속 page 손상 — invariant I-2 즉시 위반.
        // </details>
    }

    fun read(offset: Int, length: Int): ByteArray {
        checkRange(offset, length)
        return data.copyOfRange(offset, offset + length)
    }

    fun write(offset: Int, bytes: ByteArray) {
        checkRange(offset, bytes.size)
        // Q: 왜 System.arraycopy를 직접 호출? Kotlin idiomatic copyInto가 있는데.
        System.arraycopy(bytes, 0, data, offset, bytes.size)
        // <details><summary>A</summary>
        //
        // 둘 다 동작하지만, System.arraycopy는 JVM intrinsic이라 hot path에서 더 빠름. 학습 코드에서 의도 명확.
        // </details>
        isDirty = true
    }

    fun rawData(): ByteArray = data

    fun markDirty() {
        isDirty = true
    }

    fun markClean() {
        isDirty = false
    }

    fun pin() {
        pinCount++
    }

    fun unpin() {
        // Q: pinCount가 0인데 unpin 호출되면 어떤 상태?
        check(pinCount > 0) { "unpin called on un-pinned page ${id}" }
        // <details><summary>A</summary>
        //
        // pin/unpin 짝이 안 맞는 버그 — BufferPool 사용자가 unpin 두 번 호출하면 evict 가능한 페이지가 잘못 표시됨. 즉시 fail이 안전.
        // </details>
        pinCount--
    }

    private fun checkRange(offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= PAGE_SIZE) {
            "page range out of bounds: offset=$offset length=$length pageSize=$PAGE_SIZE"
        }
    }

    companion object {
        const val PAGE_SIZE: Int = 4096
    }
}
```

### 5.3 `PagedFile.kt`

```kotlin
package com.dbenginelab.storage

import java.io.Closeable
import java.io.RandomAccessFile

class PagedFile(path: String, val fileId: Int = 0) : Closeable {

    private val file: RandomAccessFile = RandomAccessFile(path, "rw")

    fun pageCount(): Int = (file.length() / Page.PAGE_SIZE).toInt()

    fun allocatePage(): PageId {
        val newPageNumber = pageCount()
        // Q: 왜 zero-filled로 미리 채우는가? 그냥 length만 늘리면 안 되나?
        val zeroes = ByteArray(Page.PAGE_SIZE)
        file.seek(file.length())
        file.write(zeroes)
        // <details><summary>A</summary>
        //
        // sparse file은 OS·FS별 동작이 다르고, 후속 read에서 random garbage가 나올 수 있음. 명시적 zero가 안전 (단계 8 WAL의 page version 자리 미리 확보).
        // </details>
        return PageId(fileId, newPageNumber)
    }

    fun readPage(id: PageId): Page {
        require(id.fileId == fileId) {
            "PageId fileId=${id.fileId} does not match this file fileId=$fileId"
        }
        val totalPages = pageCount()
        if (id.pageNumber < 0 || id.pageNumber >= totalPages) {
            // Q: 왜 sealed error로? null 반환이나 IllegalArgumentException은 안 되나?
            throw StorageError.PageNotFound(id)
            // <details><summary>A</summary>
            //
            // 단계 8 recovery에서 "없음"과 "잘못된 호출"을 다르게 처리해야 함 — sealed로 의미 분리 (constraints.md Kotlin 규칙).
            // </details>
        }
        val buf = ByteArray(Page.PAGE_SIZE)
        file.seek(id.pageNumber.toLong() * Page.PAGE_SIZE)
        file.readFully(buf)
        return Page(id, buf)
    }

    fun writePage(page: Page) {
        require(page.id.fileId == fileId) {
            "PageId fileId=${page.id.fileId} does not match this file fileId=$fileId"
        }
        file.seek(page.id.pageNumber.toLong() * Page.PAGE_SIZE)
        file.write(page.rawData())
    }

    fun sync() {
        file.fd.sync()
    }

    override fun close() {
        file.close()
    }
}
```

---

## 6. 검증 테스트

(이미 `src/test/kotlin/com/dbenginelab/storage/PagedFileTest.kt` 작성됨 — 4개 테스트 PASSED)

---

## 7. 직접 깨뜨릴 과제

- 과제 1: PAGE_SIZE를 4096이 아닌 4097로 바꾸면 어떤 테스트가 깨지는가? OS page와 어긋난 게 왜 비효율인가?
- 과제 2: `allocatePage`에서 zero-fill 안 하고 `file.setLength(newLength)`로만 늘리면 어떤 환경에서 깨지는가?
- 과제 3: 두 PagedFile 인스턴스를 같은 path로 동시에 열면 어떻게 되는가? 어디서 race가 발생할 수 있는가?

---

## 8. 다음 한계

- BufferPool 없이 매번 page IO → 메모리 cache 없음. → **02-02 BufferPool**.

---

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 — Claude 검증 완료 |
