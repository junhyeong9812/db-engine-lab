# impl/02-01 — Page + PagedFile (한 줄 한 줄)

> 상위: `docs/stages/02-page-buffer.md`
> **검증**: PagedFileTest 4 PASSED.
> 작성 파일:
> - 신규: `src/main/kotlin/com/dbenginelab/storage/PageId.kt`
> - 신규: `src/main/kotlin/com/dbenginelab/storage/Page.kt`
> - 신규: `src/main/kotlin/com/dbenginelab/storage/PagedFile.kt`
> - 신규: `src/test/kotlin/com/dbenginelab/storage/PagedFileTest.kt`

## 0. 참조
- SimpleDB `HeapPage.java`, `BufferPool.java` 상위 절반.
- BusTub `disk_manager.cpp`, `page.h` (차이: BusTub은 처음부터 latch 포함, 우리는 단계 9 lock에서 도입).

## 1. invariant
- I-4: writePage → sync → reopen → readPage 결과 동일.
- I-5: 모든 page 크기 = `Page.PAGE_SIZE` (4096).
- I-6: pageCount() = allocatePage 누적 횟수.

## 2. 의존성
- 01-01 (StorageError 확장 — PageNotFound 추가).

## 3. 구현 코드 — 한 줄 한 줄

### 3.1 `PageId.kt`

```kotlin
package com.dbenginelab.storage

// Q: 왜 data class? Page는 일반 class였는데 차이는?
data class PageId(val fileId: Int, val pageNumber: Int) {
    companion object {
        const val INVALID_PAGE_NUMBER: Int = -1                      // root 없음 등 sentinel
    }
}
// <details><summary>A</summary>
// PageId는 immutable 값 객체 (식별자) — equals/hashCode가 값 동일성으로. BufferPool의 HashMap key로 정확.
// </details>
```

### 3.2 `Page.kt`

```kotlin
package com.dbenginelab.storage

class Page(
    val id: PageId,                                                   // 어떤 page인지 식별
    private val data: ByteArray,                                      // 실제 byte 내용
) {
    var isDirty: Boolean = false                                      // BufferPool eviction 시 fsync 판단
        private set                                                   // 외부 직접 set 금지

    var pinCount: Int = 0                                             // 사용 중 page는 evict 금지
        private set

    init {
        // Q: 이 require 빠지면 어떤 입력에서 깨지나?
        require(data.size == PAGE_SIZE) {
            "Page data size must be exactly $PAGE_SIZE bytes (got ${data.size})"
        }
        // <details><summary>A</summary>
        // 외부에서 잘못된 크기 ByteArray 넘기면 writePage 시 page boundary 어긋남 → 후속 page 손상. invariant I-5 즉시 위반.
        // </details>
    }

    fun read(offset: Int, length: Int): ByteArray {
        checkRange(offset, length)                                    // 경계 검증
        return data.copyOfRange(offset, offset + length)              // 방어 복사 — 외부 변경 차단
    }

    fun write(offset: Int, bytes: ByteArray) {
        checkRange(offset, bytes.size)
        // Q: 왜 System.arraycopy 직접? Kotlin idiom (copyInto)이 있는데?
        System.arraycopy(bytes, 0, data, offset, bytes.size)
        // <details><summary>A</summary>
        // System.arraycopy는 JVM intrinsic — hot path에서 더 빠름. 학습 코드 의도 명확.
        // </details>
        isDirty = true                                                // write되면 자동 dirty
    }

    fun rawData(): ByteArray = data                                   // PagedFile.writePage가 사용 (방어 복사 안 함)

    fun markDirty() { isDirty = true }                                // 수동 dirty 표시
    fun markClean() { isDirty = false }                               // flush 후 호출

    fun pin() { pinCount++ }                                          // 사용 시작
    fun unpin() {
        // Q: pinCount=0인데 unpin 호출되면?
        check(pinCount > 0) { "unpin called on un-pinned page ${id}" }
        // <details><summary>A</summary>
        // pin/unpin 짝 안 맞으면 BufferPool에서 evict 가능한 page를 잘못 판단. 즉시 fail이 안전.
        // </details>
        pinCount--
    }

    private fun checkRange(offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= PAGE_SIZE) {
            "page range out of bounds: offset=$offset length=$length pageSize=$PAGE_SIZE"
        }
    }

    companion object {
        const val PAGE_SIZE: Int = 4096                               // OS page와 일치 — fsync 단위 최소화
    }
}
```

### 3.3 `PagedFile.kt`

```kotlin
package com.dbenginelab.storage

import java.io.Closeable
import java.io.RandomAccessFile

class PagedFile(path: String, val fileId: Int = 0) : Closeable {     // fileId — 다중 파일 시점에 의미

    private val file: RandomAccessFile = RandomAccessFile(path, "rw")

    fun pageCount(): Int = (file.length() / Page.PAGE_SIZE).toInt()  // 전체 page 수 = 파일 크기 / page size

    fun allocatePage(): PageId {
        val newPageNumber = pageCount()                               // 새 page = 끝 + 1
        // Q: 왜 zero-fill 미리? 그냥 length만 늘리면 안 되나?
        val zeroes = ByteArray(Page.PAGE_SIZE)
        file.seek(file.length())
        file.write(zeroes)
        // <details><summary>A</summary>
        // sparse file은 OS/FS별 동작 다름 — 후속 read에서 random garbage 가능. 명시 zero가 안전 (단계 8 WAL이 page header 사용 시 더 중요).
        // </details>
        return PageId(fileId, newPageNumber)
    }

    fun readPage(id: PageId): Page {
        require(id.fileId == fileId) {
            "PageId fileId=${id.fileId} does not match this file fileId=$fileId"
        }
        val totalPages = pageCount()
        if (id.pageNumber < 0 || id.pageNumber >= totalPages) {
            // Q: 왜 sealed error? null 반환 안 되나?
            throw StorageError.PageNotFound(id)
            // <details><summary>A</summary>
            // 단계 8 recovery가 "없음"과 "잘못된 호출"을 다르게 처리 — sealed로 의미 분리.
            // </details>
        }
        val buf = ByteArray(Page.PAGE_SIZE)
        file.seek(id.pageNumber.toLong() * Page.PAGE_SIZE)            // page boundary로 seek
        file.readFully(buf)                                           // 정확히 PAGE_SIZE 읽음
        return Page(id, buf)
    }

    fun writePage(page: Page) {
        require(page.id.fileId == fileId)
        file.seek(page.id.pageNumber.toLong() * Page.PAGE_SIZE)
        file.write(page.rawData())                                    // 정확히 PAGE_SIZE 씀
    }

    fun sync() { file.fd.sync() }                                     // OS buffer → disk

    override fun close() { file.close() }
}
```

## 4. 검증 (4 PASSED)
- allocate → zero-fill page
- writePage → reopen → 같은 내용
- 존재하지 않는 page read → PageNotFound
- pageCount = allocate 횟수

## 5. 깨뜨릴 과제
- PAGE_SIZE를 4097로 — OS page와 어긋난 비효율은?
- allocate에서 zero-fill 제거 — sparse file 어떤 OS에서 깨짐?
- 두 PagedFile 인스턴스가 같은 path → race?

## 6. 다음 한계
- 매번 IO → 캐시 없음 → **02-02 BufferPool**.
