# impl/02-01 — Page + PagedFile

> **종류**: 세션형
> **상위 단계**: `docs/stages/02-page-buffer.md`
> **코드 정본**: git `5505edc` — "complete: 21 stages + 12 보강 (120/120 tests)"
> **이 세션의 범위**: 파일을 고정 크기(4096B) 조각으로 자른다. 이제부터 IO 단위는 record가 아니라 page다.
> **작성 파일**:
> - 신규: `src/main/kotlin/com/dbenginelab/storage/PageId.kt`
> - 신규: `src/main/kotlin/com/dbenginelab/storage/Page.kt`
> - 신규: `src/main/kotlin/com/dbenginelab/storage/PagedFile.kt`
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/storage/PagedFileTest.kt`
> **검증**: `PagedFileTest` 4 PASSED
> **예상 타이핑 시간**: 35분

---

## 0. 참조

- 주 참조: SimpleDB `HeapPage.java` + `BufferPool.java`의 상위 절반.
- 대조 참조: BusTub `disk_manager.cpp`, `page.h`.
- **차이 채택 여부**: BusTub의 `page.h`는 처음부터 latch(`RWLatch`)를 품고 있다. **채택 안 함** — 동시성은 단계 9에서 따로 다룬다. 지금 latch를 넣으면 "왜 필요한가"를 겪기 전에 코드만 복잡해진다.

## 1. 만족시킬 invariant

- **I-4**: `writePage` → `sync` → reopen → `readPage` 결과가 동일하다.
- **I-5**: 모든 page 크기는 정확히 `Page.PAGE_SIZE`(4096)다.
- **I-6**: `pageCount()` = `allocatePage` 누적 호출 횟수.

## 2. 의존성

- 이전 세션: `impl/01-01-append-only-kv.md` (`StorageError`에 `PageNotFound`를 쓴다 — 파일은 01-01에서 이미 최종형으로 쳤다)

## 3. 문제 정의 (TDD step 1)

01-01의 `scanAll()`은 파일 전체를 `List<Record>`로 메모리에 올린다. 파일이 RAM보다 크면 그 줄에서 끝난다. **이게 page가 필요한 이유의 전부다.**

해법은 파일을 고정 크기 조각으로 자르고, 필요한 조각만 읽는 것이다. 크기를 4096으로 잡는 이유는 OS의 page 크기와 맞추기 위해서다 — 어긋나면 한 번의 논리적 읽기가 물리적으로 두 page를 건드린다.

고정 크기로 자르는 순간 새 규칙이 생긴다:
- 조각마다 **주소**가 필요하다 → `PageId(fileId, pageNumber)`
- 조각의 크기는 **정확히** 4096이어야 한다. 하나라도 어긋나면 그 뒤 모든 page의 경계가 밀린다 → `init`의 `require`
- 읽기·쓰기 위치는 `pageNumber * PAGE_SIZE`로 계산된다 → 산술이 곧 주소 변환

시나리오: page를 하나 할당하고(zero-filled), 거기에 쓰고, 파일을 닫았다 다시 열어 같은 내용이 나오는지 본다.

## 4. 실패 테스트 (TDD step 2)

```kotlin
// src/test/kotlin/com/dbenginelab/storage/PagedFileTest.kt @ 5505edc
package com.dbenginelab.storage

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PagedFileTest {

    @Test
    fun `allocatePage 후 readPage하면 zero-filled page`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("paged.db").toString()
        PagedFile(path).use { pf ->
            val pid = pf.allocatePage()
            assertEquals(0, pid.pageNumber)
            val page = pf.readPage(pid)
            assertContentEquals(ByteArray(Page.PAGE_SIZE), page.rawData())
        }
    }

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

    @Test
    fun `존재하지 않는 page 읽으면 PageNotFound`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("paged.db").toString()
        PagedFile(path).use { pf ->
            assertFailsWith<StorageError.PageNotFound> {
                pf.readPage(PageId(0, 99))
            }
        }
    }

    @Test
    fun `pageCount는 allocate 횟수와 일치`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("paged.db").toString()
        PagedFile(path).use { pf ->
            assertEquals(0, pf.pageCount())
            pf.allocatePage()
            pf.allocatePage()
            pf.allocatePage()
            assertEquals(3, pf.pageCount())
        }
    }
}
```

**예상 실패**: **컴파일 실패** — `Unresolved reference: PagedFile`, `Page`, `PageId`.

## 5. 구현 코드 (TDD step 3 — make it pass)

### 5.1 `PageId.kt`

```kotlin
// src/main/kotlin/com/dbenginelab/storage/PageId.kt @ 5505edc
package com.dbenginelab.storage

data class PageId(val fileId: Int, val pageNumber: Int) {
    companion object {
        const val INVALID_PAGE_NUMBER: Int = -1
    }
}
```

여기는 `data class`가 맞다. 01-01의 `Record`와 정반대 결정이라 헷갈리기 쉬운데 기준은 하나다 — **`ByteArray` 필드가 있는가.** `PageId`는 `Int` 두 개뿐인 값 객체라 자동 `equals`/`hashCode`가 정확하고, 곧 `BufferPool`의 `HashMap` 키로 쓰인다.

### 5.2 `Page.kt`

```kotlin
// src/main/kotlin/com/dbenginelab/storage/Page.kt @ 5505edc
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
        require(data.size == PAGE_SIZE) {
            "Page data size must be exactly $PAGE_SIZE bytes (got ${data.size})"
        }
    }

    fun read(offset: Int, length: Int): ByteArray {
        checkRange(offset, length)
        return data.copyOfRange(offset, offset + length)
    }

    fun write(offset: Int, bytes: ByteArray) {
        checkRange(offset, bytes.size)
        // Q: 왜 System.arraycopy 직접? Kotlin idiom (copyInto)이 있는데?
        System.arraycopy(bytes, 0, data, offset, bytes.size)
        // <details><summary>A</summary>
        // System.arraycopy는 JVM intrinsic — hot path에서 더 빠름. 학습 코드 의도 명확.
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
        const val PAGE_SIZE: Int = 4096
    }
}
```

`isDirty`와 `pinCount`가 `private set`인 것에 주목. 이 둘은 **02-02 BufferPool이 evict 여부를 판단하는 근거**다. 외부에서 아무나 바꿀 수 있으면 "이 page를 내보내도 되는가"의 답이 흔들린다.

### 5.3 `PagedFile.kt`

```kotlin
// src/main/kotlin/com/dbenginelab/storage/PagedFile.kt @ 5505edc
package com.dbenginelab.storage

import java.io.Closeable
import java.io.RandomAccessFile

class PagedFile(path: String, val fileId: Int = 0) : Closeable {

    private val file: RandomAccessFile = RandomAccessFile(path, "rw")

    fun pageCount(): Int = (file.length() / Page.PAGE_SIZE).toInt()

    fun allocatePage(): PageId {
        val newPageNumber = pageCount()
        // Q: 왜 zero-fill 미리? 그냥 length만 늘리면 안 되나?
        val zeroes = ByteArray(Page.PAGE_SIZE)
        // <details><summary>A</summary>
        // sparse file은 OS/FS별 동작 다름 — 후속 read에서 random garbage 가능. 명시 zero가 안전 (단계 8 WAL이 page header 사용 시 더 중요).
        // </details>
        file.seek(file.length())
        file.write(zeroes)
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

## 6. 검증 테스트 (TDD step 4 — green)

테스트 파일은 §4에서 저장한 것이 그대로 최종본이다.

```bash
./gradlew test --tests 'com.dbenginelab.storage.PagedFileTest'
```

**기대 결과**: `PagedFileTest` **4 PASSED** · storage 누적 7 PASSED

invariant 대응:
- **I-5** ← `allocatePage 후 readPage하면 zero-filled page`
- **I-4** ← `writePage 후 reopen하면 같은 내용 read`
- (에러 경로) ← `존재하지 않는 page 읽으면 PageNotFound`
- **I-6** ← `pageCount는 allocate 횟수와 일치`

## 7. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

**1.** `PAGE_SIZE`를 4097로 바꿔라. 테스트는 통과하는가? 통과한다면 **테스트가 못 잡는 무엇이 나빠졌는지** 적어라.

<details><summary>답</summary>

**실측: storage 테스트 21개 전부 통과한다.** 코드가 내부적으로 일관되기 때문이다 — `Page`는 `data.size == PAGE_SIZE`만 확인하고, `PagedFile`은 `pageNumber * PAGE_SIZE`로 위치를 계산한다. 4097로 통일해도 아무 모순이 없다.

못 잡는 것은 **물리 계층과의 어긋남**이다:

- OS·디스크의 IO 단위는 4096바이트(또는 512·4096의 배수)다. 4097짜리 논리 page 하나는 **물리 page 2개에 걸친다.**
- 그래서 논리적 읽기 1회가 물리적으로 2회가 된다. page 0은 물리 0~1, page 1은 물리 1~2… **모든 page가 경계를 넘는다.**
- 더 나쁜 건 쓰기다. 디스크는 sector 단위로만 원자성을 보장하는데, 경계를 넘는 쓰기는 중간에 전원이 나가면 **앞부분만 쓰인 상태**가 될 수 있다(torn write).

기능 테스트로는 절대 안 잡힌다. 성능과 내구성의 문제이고, 둘 다 정상 동작에서는 보이지 않기 때문이다.
</details>

**2.** `allocatePage`의 zero-fill을 지우고 파일 길이만 늘려라(`setLength`). `readPage`가 쓰레기를 반환하는가? 네 환경에서는 재현되는가?

<details><summary>답</summary>

**대부분의 환경에서 재현되지 않는다.** POSIX는 파일의 구멍(hole)을 읽으면 **0으로 채워진 바이트를 돌려주도록 규정**하고 있고, ext4·XFS·APFS 모두 이를 지킨다. 그러니 `assertContentEquals(ByteArray(PAGE_SIZE), ...)`도 통과한다.

> **정본 주석과 다른 판단**: `PagedFile.allocatePage`의 Q/A는 "sparse file은 OS/FS별 동작이 달라 random garbage 가능"이라고 적고 있는데, **이건 과장이다.** 구멍에서 쓰레기가 나오는 건 규격 위반이다.

명시적 zero-fill의 실질적 이득은 다른 데 있다:

1. **공간을 미리 확보한다.** sparse로 두면 나중에 그 page에 실제로 쓸 때 블록을 할당하는데, 그 시점에 디스크가 차 있으면 **`ENOSPC`로 실패한다.** "할당은 됐는데 쓸 수가 없는" 상태가 생긴다.
2. **`pageCount()`가 `file.length() / PAGE_SIZE`로 단순해진다.** 실제로 쓴 만큼만 길이가 늘어나므로 계산과 현실이 어긋나지 않는다.

즉 이 결정의 근거는 "쓰레기 방지"가 아니라 **"실패 시점을 앞당기기"**다. 나중에 조용히 실패하느니 지금 요란하게 실패하는 편이 낫다는 것.
</details>

**3.** 같은 경로로 `PagedFile` 인스턴스를 두 개 만들어 동시에 `allocatePage`를 호출하면? 두 인스턴스가 같은 `pageNumber`를 반환할 수 있는 순서를 하나 적어봐라.

<details><summary>답</summary>

`allocatePage`는 **읽고-나서-쓰는(read-modify-write)** 구조인데 그 사이가 보호되지 않는다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
val newPageNumber = pageCount()   // ← 읽기
...
file.seek(file.length())
file.write(zeroes)                // ← 쓰기
return PageId(fileId, newPageNumber)
```

깨지는 순서:

```
파일에 page 5개 있음 (length = 5 * 4096)

A: pageCount() → 5
B: pageCount() → 5          ← A가 아직 안 썼으므로 똑같이 5
A: write(zeroes) → length 6개분
B: write(zeroes) → length 7개분
A: return PageId(0, 5)
B: return PageId(0, 5)      ← 같은 번호!
```

결과: **page 6개분이 할당됐는데 둘 다 5번을 쓴다.** 하나는 덮어써져 사라지고, 파일 끝에는 아무도 쓰지 않는 빈 page가 남는다.

막으려면 `pageCount()` 읽기부터 `write` 완료까지를 하나의 임계 구역으로 묶어야 한다 — 이게 단계 9 lock의 축소판이다. (덧붙여, 같은 파일을 두 `RandomAccessFile`이 여는 것 자체가 OS 수준에서 막히지 않는다는 점도 확인해봐라.)
</details>

**4.** `Page.write`의 `checkRange`를 지우고 `offset = 4090, bytes.size = 10`으로 써봐라. 무엇이 깨지는가 — 이 page인가, 다음 page인가?

<details><summary>답</summary>

**실측: `checkRange`를 지워도 테스트 21개가 전부 통과한다** — 경계를 넘는 쓰기를 하는 테스트가 하나도 없기 때문이다.

직접 `offset = 4090, size = 10`으로 써보면 **`IndexOutOfBoundsException`**이 난다. `System.arraycopy`가 대상 배열의 경계를 스스로 확인하기 때문이다.

그래서 답은 **"둘 다 안 깨진다"**이다. `data`는 정확히 4096바이트짜리 `ByteArray`이고, JVM이 그 밖을 건드리는 것을 허용하지 않는다. 다음 page는 애초에 다른 객체라 닿을 수가 없다.

그럼 `checkRange`는 왜 있나 — **진단 가능성** 때문이다:

```
checkRange 있음: "page range out of bounds: offset=4090 length=10 pageSize=4096"
checkRange 없음: "Index 4096 out of bounds for length 4096"   ← 어느 page인지, 무슨 연산인지 모름
```

만약 이 코드가 C였다면 이야기가 완전히 달라진다. `memcpy`는 경계를 안 보므로 **다음 page의 메모리를 조용히 덮어쓴다.** 그리고 그 손상은 한참 뒤 엉뚱한 page를 읽을 때 드러난다. JVM에서 "그냥 예외"인 것이 C에서는 "원인 추적 불가능한 데이터 손상"이 되는 셈이다.
</details>

## 8. 다음 한계

`readPage`를 부를 때마다 실제 디스크 IO가 일어난다. 같은 page를 100번 읽으면 100번 읽는다. **캐시가 없다.**

→ **02-02 BufferPool**. 자주 쓰는 page를 메모리에 붙잡아두고, 자리가 모자라면 무엇을 내보낼지 결정한다. 그 결정에 `isDirty`와 `pinCount`가 쓰인다.
