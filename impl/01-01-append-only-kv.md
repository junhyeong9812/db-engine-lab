# impl/01-01 — Append-Only Key-Value Store

> **종류**: 세션형
> **상위 단계**: `docs/stages/01-storage.md`
> **코드 정본**: git `5505edc` — "complete: 21 stages + 12 보강 (120/120 tests)"
> **이 세션의 범위**: 프로세스가 죽어도 살아남는 가장 단순한 저장소 — append-only 파일 한 개.
> **작성 파일**:
> - 신규: `src/main/kotlin/com/dbenginelab/storage/Record.kt`
> - 신규: `src/main/kotlin/com/dbenginelab/storage/StorageError.kt`
> - 신규: `src/main/kotlin/com/dbenginelab/storage/AppendOnlyFile.kt`
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/storage/AppendOnlyFileTest.kt`
> **검증**: `AppendOnlyFileTest` 3 PASSED
> **예상 타이핑 시간**: 40분 (첫 세션 — 천천히)

---

## 0. 참조

- 주 참조: SimpleDB `HeapFile.java` (대폭 단순화 — page 개념 없이 raw record)
- 대조 참조: BusTub `disk_manager.cpp` — BusTub은 처음부터 page 단위. 우리는 단계 2에서 그리로 간다.
- **차이 채택 여부**: 채택 안 함. 단계 1의 목적은 "왜 page가 필요한가"를 몸으로 느끼는 것이고, 그러려면 page 없이 먼저 겪어야 한다.

## 1. 만족시킬 invariant

- **I-1**: append 후 close → reopen → `scanAll` 결과가 동일하다.
- **I-2**: 한 record의 경계를 식별할 수 있다 (length-prefix).
- **I-3**: `flush()` 후 프로세스가 죽어도 데이터가 보존된다.

## 2. 의존성

- 없음 (첫 세션).
- 외부: `java.io.RandomAccessFile`.

## 3. 문제 정의 (TDD step 1)

DB의 가장 밑바닥 요구는 하나다 — **프로세스가 죽어도 데이터가 남아야 한다.** 메모리의 `HashMap`은 이 조건을 못 지킨다.

가장 단순한 해법은 파일 끝에 계속 이어 붙이는 것이다(append-only). 수정도 삭제도 없이 쓰기만 한다. 그러면 두 가지 문제가 즉시 생긴다:

1. **경계 문제** — `k1v1longer-key-2value-2` 를 다시 읽을 때 어디까지가 한 record인가? 바이트 열만으로는 알 수 없다. 그래서 길이를 먼저 적는다(length-prefix).
2. **내구성 문제** — `write()`가 돌아왔다고 디스크에 갔다는 뜻이 아니다. OS 버퍼에 있을 뿐이다. 전원이 나가면 사라진다. 그래서 `fsync`가 필요하다.

이 두 가지가 I-2, I-3이다. 시나리오: record 3개를 서로 다른 길이로 넣고 flush → 파일을 닫고 다시 열어서 → 순서·내용이 그대로인지 본다.

## 4. 실패 테스트 (TDD step 2)

아무 클래스도 없는 상태에서 아래 파일을 저장한다.

```kotlin
// src/test/kotlin/com/dbenginelab/storage/AppendOnlyFileTest.kt @ 5505edc
package com.dbenginelab.storage

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class AppendOnlyFileTest {

    @Test
    fun `I-1 append 후 reopen하면 모든 record를 순서대로 다시 읽는다`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("data.log").toString()

        val r1 = Record("k1".toByteArray(), "v1".toByteArray())
        val r2 = Record("longer-key-2".toByteArray(), "value-2".toByteArray())
        val r3 = Record("k3".toByteArray(), "v3".toByteArray())

        AppendOnlyFile(path).use { writer ->
            writer.append(r1)
            writer.append(r2)
            writer.append(r3)
            writer.flush()
        }

        AppendOnlyFile(path).use { reader ->
            val records: List<Record> = reader.scanAll()
            assertEquals(3, records.size, "record 개수 일치")
            assertContentEquals(r1.key, records[0].key)
            assertContentEquals(r1.value, records[0].value)
            assertContentEquals(r2.key, records[1].key)
            assertContentEquals(r2.value, records[1].value)
            assertContentEquals(r3.key, records[2].key)
            assertContentEquals(r3.value, records[2].value)
        }
    }

    @Test
    fun `I-2 빈 record(zero-length key·value)도 정상 처리`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("empty.log").toString()

        AppendOnlyFile(path).use { writer ->
            writer.append(Record(ByteArray(0), ByteArray(0)))
            writer.append(Record("k".toByteArray(), ByteArray(0)))
            writer.append(Record(ByteArray(0), "v".toByteArray()))
            writer.flush()
        }

        AppendOnlyFile(path).use { reader ->
            val records = reader.scanAll()
            assertEquals(3, records.size)
            assertEquals(0, records[0].key.size)
            assertEquals(0, records[0].value.size)
            assertEquals("k", String(records[1].key))
            assertEquals(0, records[1].value.size)
            assertEquals(0, records[2].key.size)
            assertEquals("v", String(records[2].value))
        }
    }

    @Test
    fun `I-1 append→scan→append 시퀀스도 모든 record 보존`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("interleaved.log").toString()

        AppendOnlyFile(path).use { f ->
            f.append(Record("a".toByteArray(), "1".toByteArray()))
            f.append(Record("b".toByteArray(), "2".toByteArray()))
            f.flush()

            val midScan = f.scanAll()
            assertEquals(2, midScan.size)

            f.append(Record("c".toByteArray(), "3".toByteArray()))
            f.flush()

            val finalScan = f.scanAll()
            assertEquals(3, finalScan.size)
            assertEquals("c", String(finalScan[2].key))
        }
    }
}
```

**예상 실패**: **컴파일 실패** — `Unresolved reference: Record`, `Unresolved reference: AppendOnlyFile`.

`./gradlew test` 로 그 메시지를 직접 볼 것. 이게 이 프로젝트에서 보는 첫 빨간 줄이다.

## 5. 구현 코드 (TDD step 3 — make it pass)

### 5.1 `Record.kt`

```kotlin
// src/main/kotlin/com/dbenginelab/storage/Record.kt @ 5505edc
package com.dbenginelab.storage

// Q: 왜 data class 가 아닌가? 평범한 byte 묶음인데?
class Record(val key: ByteArray, val value: ByteArray)
// <details><summary>A</summary>
// ByteArray.equals 는 reference equality — data class 자동 equals가 "내용 같으면 같다" 거짓말. 일반 class로 명시.
// </details>
```

한 줄짜리지만 결정이 하나 들어있다 — **`data class`가 아니다.** `ByteArray`의 `equals`는 참조 비교라서, `data class`가 만들어주는 `equals`는 "내용이 같으면 같다"는 거짓말을 하게 된다. 그 거짓말이 테스트에서 조용히 통과하는 것보다, 평범한 class로 두고 비교는 `assertContentEquals`로 명시하는 편이 낫다.

### 5.2 `StorageError.kt`

```kotlin
// src/main/kotlin/com/dbenginelab/storage/StorageError.kt @ 5505edc
package com.dbenginelab.storage

// Q: 왜 nullable이 아니라 sealed class로 에러 표현?
sealed class StorageError(message: String) : RuntimeException(message) {
// <details><summary>A</summary>
// 단계 8 recovery에서 "없음" (null)과 "깨진 상태" (corrupt)를 다르게 처리해야. sealed로 의미 분리.
// </details>
    class CorruptRecord(offset: Long, reason: String)
        : StorageError("offset=$offset: $reason")

    class UnexpectedEof(expectedBytes: Int, gotBytes: Int)
        : StorageError("expected $expectedBytes bytes, got $gotBytes (partial write?)")

    class PageNotFound(id: PageId)
        : StorageError("page not found: $id")

    class PageNotInPool(id: PageId)
        : StorageError("page not in buffer pool: $id")

    class AllPagesPinned(capacity: Int)
        : StorageError("all $capacity pages are pinned, cannot evict")
}
```

> **정본 특이사항**: `PageNotFound`·`PageNotInPool`·`AllPagesPinned`는 **단계 2에서야 의미가 생긴다**. 정본에 단계 1 시점의 스냅샷이 남아있지 않아 최종형을 그대로 싣는다. 지금은 `CorruptRecord`와 `UnexpectedEof` 둘만 보면 된다 — 나머지는 02-01·02-02에서 다시 만난다.

에러를 nullable이 아니라 sealed class로 두는 이유: 단계 8 recovery에서 **"데이터가 없다"와 "데이터가 깨졌다"를 다르게 처리해야** 하기 때문이다. `null` 하나로는 그 구분이 안 된다.

### 5.3 `AppendOnlyFile.kt`

```kotlin
// src/main/kotlin/com/dbenginelab/storage/AppendOnlyFile.kt @ 5505edc
package com.dbenginelab.storage

import java.io.Closeable
import java.io.EOFException
import java.io.RandomAccessFile

class AppendOnlyFile(path: String) : Closeable {

    // Q: FileOutputStream/FileInputStream 분리 안 하고 RandomAccessFile 하나로?
    private val file: RandomAccessFile = RandomAccessFile(path, "rw")
    // <details><summary>A</summary>
    // append와 scan을 같은 객체에서 — seek 필요 (scan은 처음부터, append는 끝). RandomAccessFile만 제공.
    // </details>

    init {
        file.seek(file.length())
    }

    fun append(record: Record) {
        file.seek(file.length())
        // Q: 왜 length를 먼저 쓰는가? key/value bytes만 쓰면 안 되나?
        file.writeInt(record.key.size)
        // <details><summary>A</summary>
        // 가변 길이 record의 경계 식별 — length-prefix가 frame. 안 쓰면 어디서 한 record 끝나는지 모름 (I-2).
        // </details>
        file.write(record.key)
        file.writeInt(record.value.size)
        file.write(record.value)
    }

    fun flush() {
        // Q: file.fd.sync()와 그냥 write 차이는?
        file.fd.sync()
        // <details><summary>A</summary>
        // write는 OS buffer까지만, sync()가 디스크까지. OS crash 시 sync 없으면 OS buffer가 사라져 데이터 손실 (I-3).
        // </details>
    }

    fun scanAll(): List<Record> {
        // Q: scan 시작 전에 seek(0) 명시 안 하면?
        file.seek(0)
        // <details><summary>A</summary>
        // init에서 끝으로 옮겨졌거나 직전 append로 위치가 끝에 있을 수 있음 — 명시 seek가 처음부터 읽기 보장.
        // </details>
        val result = mutableListOf<Record>()
        while (file.filePointer < file.length()) {
            val recordStart = file.filePointer
            try {
                val keyLen = file.readInt()
                val key = ByteArray(keyLen)
                file.readFully(key)
                val valueLen = file.readInt()
                val value = ByteArray(valueLen)
                file.readFully(value)
                result.add(Record(key, value))
            } catch (e: EOFException) {
                throw StorageError.UnexpectedEof(
                    expectedBytes = -1,
                    gotBytes = (file.length() - recordStart).toInt()
                )
            }
        }
        return result
    }

    override fun close() {
        file.close()
    }
}
```

## 6. 검증 테스트 (TDD step 4 — green)

테스트 파일은 §4에서 저장한 것이 그대로 최종본이다.

```bash
./gradlew test --tests 'com.dbenginelab.storage.AppendOnlyFileTest'
```

**기대 결과**: `AppendOnlyFileTest` **3 PASSED**

invariant 대응:
- **I-1** ← `I-1 append 후 reopen하면 모든 record를 순서대로 다시 읽는다`
- **I-2** ← `I-2 빈 record(zero-length key·value)도 정상 처리` — 길이 0인 record가 경계 규칙의 극단값이다
- **I-1** ← `I-1 append→scan→append 시퀀스도 모든 record 보존` — scan이 file pointer를 옮긴 뒤 append해도 깨지지 않는지

I-3(fsync 후 crash 보존)은 여기서 자동 검증되지 않는다. 실제 전원 차단이 필요하기 때문이다 — §7 과제 1번으로 넘긴다. **"테스트가 3개 통과했다"가 "invariant 3개가 다 지켜졌다"는 뜻이 아니라는 것**을 첫 세션부터 확인해두라.

## 7. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

> 답은 접혀 있다. **먼저 예측하고 나서** 펼쳐라 — 예측이 틀린 지점이 가장 많이 배우는 곳이다.

**1.** `flush()`를 부르지 않고 프로세스를 강제 종료(`kill -9`)해봐라. 데이터가 남는가? 남는다면 왜 남았고, 어떤 상황이면 안 남는가?

<details><summary>답</summary>

**남는다.** `file.write(...)`는 이미 데이터를 커널의 페이지 캐시로 넘겼고, 프로세스가 죽어도 **커널은 살아있다.** 커널이 나중에 알아서 디스크로 내려보낸다.

사라지는 건 **커널이 함께 죽을 때**다 — 전원 차단, 커널 패닉, VM 강제 종료.

그래서 `fsync`(`file.fd.sync()`)는 **프로세스 crash가 아니라 머신 crash에 대한 방어**다. 이 구분을 놓치면 "kill -9 해봤는데 멀쩡하네, fsync 필요 없나?"라는 잘못된 결론에 도달한다.
</details>

**2.** `append` 도중 강제 종료해서 length-prefix만 쓰이고 body가 안 쓰인 파일을 만들어라. `scanAll`은 무엇을 던지는가?

<details><summary>답</summary>

`file.readFully(key)`가 `EOFException`을 던지고, `scanAll`의 `catch`가 그것을 `StorageError.UnexpectedEof`로 바꿔 던진다.

**중요한 건 그 다음이다** — 예외가 `scanAll` 밖으로 나가면서 `result`에 모아둔 **정상 record들까지 통째로 버려진다.** 파일 끝 하나가 잘렸을 뿐인데 앞의 멀쩡한 데이터를 못 읽는다.

단계 8 WAL의 `replay`는 같은 상황을 다르게 처리한다 — **잘린 꼬리는 버리고 앞은 살린다.** 08-03의 `WAL 파일 끝 partial bytes — replay 시 무시` 테스트가 그것이다. 두 코드를 나란히 놓고 비교해보면 설계 차이가 선명하다.
</details>

**3.** 100MB짜리 record 하나를 넣으면? `scanAll`이 반환하는 `List<Record>`가 메모리에 다 올라간다 — 몇 건에서 터지나?

<details><summary>답</summary>

`scanAll`은 파일 전체를 `List<Record>`로 만들어 반환하므로 **필요 메모리 ≈ 파일 크기**다. JVM 기본 힙(보통 물리 메모리의 1/4)에서 100MB record라면 대략 수십 건에서 `OutOfMemoryError`다.

정확한 숫자보다 중요한 건 **한 건도 못 읽는다**는 점이다. 100MB 중 1KB만 필요해도 100MB를 다 올린다. 부분 읽기가 불가능한 구조다.

이게 단계 2 Page IO의 존재 이유다 — 파일을 4096바이트 조각으로 자르면 필요한 조각만 읽을 수 있다.
</details>

**4.** length-prefix 4바이트를 손으로 깨뜨려라(`RandomAccessFile`로 특정 위치에 `writeInt`). `scanAll` 결과가 어떻게 되는가 — 예외인가, 조용한 쓰레기 데이터인가?

<details><summary>답</summary>

**깨뜨린 값에 따라 둘 다 나온다.** 이게 핵심이다.

| 손상된 길이 값 | 결과 |
|---|---|
| 파일 크기를 넘김 (예: 999999) | `readFully` → `EOFException` → `UnexpectedEof`. **요란하게 실패** — 그나마 낫다 |
| 여전히 파일 안 (예: 3) | **조용히 쓰레기 record를 만든다.** 그리고 그 뒤로 읽는 위치가 어긋나 이후 record가 전부 엉킨다 |

두 번째가 이 설계의 진짜 위험이다. **에러 없이 틀린 데이터**를 돌려주고, 호출자는 그것이 틀렸다는 것을 알 방법이 없다.

막으려면 record마다 **체크섬**이 필요하다 — 길이와 내용의 해시를 함께 적고 읽을 때 대조한다. 실제 WAL 구현들이 전부 체크섬을 갖는 이유가 이것이고, 우리 코드에는 없다.
</details>

결과는 `docs/stages/01-storage.md` 또는 `docs/decision-log.md`에 기록.

## 8. 다음 한계

- **partial write** → `UnexpectedEof`로 감지만 하고 복구는 못 한다. → 단계 8 WAL.
- **풀스캔 O(N)** → 키 하나 찾자고 파일 전체를 읽는다. → 단계 3 Index.
- **메모리 한계** → `scanAll`이 전부 메모리에 올린다. 파일이 RAM보다 크면 끝. → **단계 2 Page IO**가 바로 이것 때문이다.
- **단일 writer** → 동시 append는 정의되지 않은 동작. → 단계 9 Lock.
