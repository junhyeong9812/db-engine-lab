# impl/01-01 — Append-Only Key-Value Store

> 상위 단계: `docs/stages/01-storage.md`
> 이 세션의 범위: 가장 단순한 append-only 파일 저장소 (Record, AppendOnlyFile, StorageError).
> 예상 타이핑 시간: 30~45분 (코드 ~120줄 + 테스트 ~80줄).
> **✅ Claude 검증 완료 (2026-05-16): `./gradlew test` 3 tests PASSED. BUILD SUCCESSFUL.**
> **갱신**: `append()` 안에 `file.seek(file.length())` 명시 추가 (impl 5.4 가설 깨짐 — scan 후 append 안전성 확보).

---

## 0. 참조 출처

### 주 참조 (SimpleDB)
- 파일: `simpledb/storage/HeapFile.java` (단순화).
- 클래스/메서드: `HeapFile.writePage(Page)`, `HeapFile.iterator()`.
- 출처: MIT 6.830 lab1 starter / Sciore 책 5장.
- commit/판본: 사용자가 단계 1 spot check 시 명시.
- 우리 코드 대응: `AppendOnlyFile.append(Record)`, `AppendOnlyFile.scanAll()`.

### 대조 참조 (BusTub)
- 파일: `src/storage/disk/disk_manager.cpp`
- 함수: `DiskManager::WritePage(page_id_t, const char*)`
- 우리 코드와의 차이: BusTub은 처음부터 **page 단위** (page_id로 random access). 우리는 단계 1에서 **raw record 단위 append만** (random access는 단계 2 page에서).
- 차이 채택 여부: **채택 안 함**. 이유: "실패하는 가정의 순서" — 페이지 개념은 단계 2에서 자연 도입.

### 핵심 설계 결정 근거 (1~3줄)
- **Record는 일반 class (data class 아님)**: `ByteArray`는 가변이라 equals/hashCode가 reference equality로 동작. data class 자동 생성은 오히려 함정 (codex 보정 1).
- **RandomAccessFile 선택**: append-only지만 reader는 처음부터 읽어야 하므로 seek 가능한 API 필요. `FileOutputStream` + `FileInputStream` 분리도 가능하지만 단일 객체로 관리하는 게 학습 단순.
- **`writeInt(len) + write(bytes)` length-prefix 포맷**: 가변 길이 record를 frame으로 구분.

---

## 1. 만족시킬 invariant

- **I-1**: append 후 close → 재오픈 → scanAll 결과는 append한 모든 record를 순서대로 포함.
- **I-2**: 한 record의 경계가 파일에서 명확히 식별 가능 (length-prefix).
- **I-3**: flush() 호출 후 process kill해도 그 시점까지의 append는 살아남음 (OS crash는 fsync 정책에 따라).

---

## 2. 의존성

- 이전 세션: 없음 (첫 세션).
- 외부 의존: JDK 21 표준 API (`java.io.RandomAccessFile`, `java.io.File`).
- Gradle 의존: `kotlin-test`, `junit-jupiter` (이미 build.gradle.kts에 있음).

---

## 3. 문제 정의 (TDD step 1)

### 시나리오
1. 사용자가 새 파일에 record 3개를 append.
2. flush 후 close.
3. **새 프로세스**에서 같은 파일을 open.
4. scanAll → 3개의 record를 append한 순서로 받아야 함.

### 무엇이 어려운가?
- record는 가변 길이 (key/value 길이가 매번 다름) → 어디까지가 한 record인지 표시 필요.
- close 후 재오픈해도 같은 내용을 읽으려면 **disk에 실제로 도달**해야 함 (OS buffer가 아니라).
- 첫 read 위치는 파일 시작, 첫 append 위치는 파일 끝.

---

## 4. 문제를 시각화하는 실패 테스트 (TDD step 2 — failing test first)

다음 테스트를 먼저 작성하고 실행해보면 **컴파일 에러**(클래스 없음)로 실패한다. 빨간 줄을 직접 본 뒤 5번으로 진행.

**파일**: `src/test/kotlin/com/dbenginelab/storage/AppendOnlyFileTest.kt`

```kotlin
package com.dbenginelab.storage

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals

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
}
```

**실행 결과 (예상)**: `Unresolved reference: Record` `Unresolved reference: AppendOnlyFile`.
→ 컴파일 실패. 5번에서 구현해 통과시킨다.

---

## 5. 구현 코드 (TDD step 3 — make it pass)

### 5.1 `Record.kt` — 단순 key/value 컨테이너

**파일**: `src/main/kotlin/com/dbenginelab/storage/Record.kt`

```kotlin
package com.dbenginelab.storage

// Q: 왜 data class가 아닌가? 평범한 byte 묶음인데?
class Record(val key: ByteArray, val value: ByteArray)
// <details><summary>A</summary>
//
// ByteArray의 equals는 reference equality라서 data class의 자동 equals/hashCode가 학습자를 속인다 — "내용이 같으면 같다"고 착각하게 만든다.
// </details>
```

### 5.2 `StorageError.kt` — sealed error hierarchy

**파일**: `src/main/kotlin/com/dbenginelab/storage/StorageError.kt`

```kotlin
package com.dbenginelab.storage

// Q: 왜 nullable이 아니라 sealed class로 에러를 표현하는가?
sealed class StorageError(message: String) : RuntimeException(message) {
    class CorruptRecord(offset: Long, reason: String)
        : StorageError("offset=$offset: $reason")
    class UnexpectedEof(expectedBytes: Int, gotBytes: Int)
        : StorageError("expected $expectedBytes bytes, got $gotBytes (partial write?)")
}
// <details><summary>A</summary>
//
// "없음"(null)과 "깨진 상태"(corrupt)는 의미가 완전히 다르다 — 단계 8 recovery에서 둘을 다르게 처리해야 한다.
// </details>
```

### 5.3 `AppendOnlyFile.kt` — 핵심 저장소

**파일**: `src/main/kotlin/com/dbenginelab/storage/AppendOnlyFile.kt`

```kotlin
package com.dbenginelab.storage

import java.io.Closeable
import java.io.EOFException
import java.io.RandomAccessFile

class AppendOnlyFile(path: String) : Closeable {

    // Q: FileOutputStream/FileInputStream을 분리하지 않고 RandomAccessFile 하나로 쓰는 이유?
    private val file: RandomAccessFile = RandomAccessFile(path, "rw")
    // <details><summary>A</summary>
    //
    // append와 scan을 같은 객체에서 다루려면 seek가 필요하다 — RandomAccessFile이 그걸 제공한다.
    // </details>

    init {
        // Q: 객체 생성 시 파일 끝으로 위치를 옮기는 이유? scan을 먼저 호출하면 위치가 안 맞지 않나?
        file.seek(file.length())
        // <details><summary>A</summary>
        //
        // append가 먼저 호출되는 일반 사용 패턴 기준 — scan은 내부에서 명시적으로 seek(0)을 한다.
        // </details>
    }

    fun append(record: Record) {
        // Q: 왜 매 append마다 seek(length)를 명시하는가?
        file.seek(file.length())
        // <details><summary>A</summary>
        //
        // scan 후 file pointer가 끝에 있는 게 대부분이지만, EOFException 발생 시 위치 모호 — 명시적 seek가 안전 (5.4 부록 A 가설 갱신).
        // </details>

        // Q: 왜 length를 먼저 쓰는가? key/value bytes만 쓰면 안 되나?
        file.writeInt(record.key.size)
        // <details><summary>A</summary>
        //
        // 가변 길이 record의 경계를 식별하려면 frame이 필요하다 — length-prefix가 frame 역할 (I-2).
        // </details>
        file.write(record.key)
        file.writeInt(record.value.size)
        file.write(record.value)
    }

    fun flush() {
        // Q: file.fd.sync() 대신 아무것도 안 하면 어떤 입력에서 깨지는가?
        file.fd.sync()
        // <details><summary>A</summary>
        //
        // sync() 없이 process kill만 해도 OS buffer는 살지만, OS crash 시 OS buffer가 디스크에 도달하지 못해 데이터가 사라진다 (I-3).
        // </details>
    }

    fun scanAll(): List<Record> {
        // Q: scan 시작 전에 seek(0)을 명시하지 않으면 어떻게 되는가?
        file.seek(0)
        // <details><summary>A</summary>
        //
        // init에서 끝으로 옮겨졌거나 직전 append로 위치가 끝에 있을 수 있다 — 명시적으로 0으로 옮겨야 처음부터 읽는다.
        // </details>

        val result = mutableListOf<Record>()
        // Q: 종료 조건이 file.length()인데, partial write로 마지막 record가 잘렸으면 어떻게 되는가?
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
                // Q: EOFException을 잡아서 StorageError로 변환하는 이유?
                throw StorageError.UnexpectedEof(
                    expectedBytes = -1,
                    gotBytes = (file.length() - recordStart).toInt()
                )
                // <details><summary>A</summary>
                //
                // EOFException은 자바 표준 예외라 호출자는 "왜 EOF가?"를 모른다 — 도메인 에러로 감싸야 단계 8 recovery에서 의미가 분리된다.
                // </details>
            }
        }
        // <details><summary>A (위 while 종료 조건 질문)</summary>
        //
        // partial write가 있으면 마지막 read 도중 EOFException 발생 → catch에서 StorageError로 변환 → 단계 01-02에서 "잘린 record skip" 전략 도입 예정.
        // </details>
        return result
    }

    override fun close() {
        file.close()
    }
}
```

### 5.4 부록 A — scan 직후 append 위치 문제

질문에 대한 긴 답이 필요한 경우 별도 섹션 (codex 보정 4: "답 1문장 안 되면 별도 섹션").

scan 직후 `file.filePointer`는 파일 끝에 있다 (scan이 끝까지 읽었기 때문). 그래서 append 직전 추가 `seek(file.length())`는 불필요. 다만:
- scan을 중간에 멈추는 시나리오가 생기면 (단계 2 page에서 부분 읽기) 이 가정이 깨진다.
- 그때는 append 시작 시 명시적 seek가 필요.
- 이번 단계 1에서는 가정 유지, **단계 2 진입 시 재검토** (가설 표시).

---

## 6. 검증 테스트 (TDD step 4 — green)

5번 작성 후 4번 테스트를 다시 실행하면 통과해야 한다. 추가로 다음 테스트를 작성해서 invariant를 더 강하게 검증:

**파일에 추가**: `src/test/kotlin/com/dbenginelab/storage/AppendOnlyFileTest.kt`

```kotlin
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

            // scan 직후 append (5.4 부록 A 가정 검증)
            f.append(Record("c".toByteArray(), "3".toByteArray()))
            f.flush()

            val finalScan = f.scanAll()
            assertEquals(3, finalScan.size)
            assertEquals("c", String(finalScan[2].key))
        }
    }
```

**실행**: `./gradlew test` → 모든 테스트 통과 (초록).

---

## 7. 직접 깨뜨릴 과제 (codex 보정 4)

각 과제는 **정답 코드 미제공**. 사용자가 자기 코드 변형해서 깨뜨려보고 결과를 `docs/stages/01-storage.md` "9. 단계 1 학습 메모"에 짧게 기록.

### 과제 1: flush를 호출하지 않으면 어떤 입력에서 깨지는가?
힌트:
- write → close(without flush) → reopen → scan 결과는?
- write → 강제 process kill (테스트에서는 `Runtime.getRuntime().halt(0)` 또는 `exitProcess`) → 다른 process에서 reopen → 결과는?
- write → flush → process kill → reopen → 결과는?
- 어떤 시나리오에서 데이터가 살고 어떤 시나리오에서 죽는가? OS·파일시스템 의존성은?

### 과제 2: 한 record의 key가 거대 (예: 100MB) 일 때 어떤 일이 일어나는가?
힌트:
- 메모리 사용량은? `ByteArray(keyLen)` 가 OOM을 일으킬 수 있는가?
- file IO 성능은?
- 단계 2 page IO가 왜 필요한지의 동기.

### 과제 3: 길이 prefix를 손상시킨 파일에서 scan하면 어떤 결과?
힌트:
- 직접 파일에 0xFF로 4 byte 덮어쓰기 (`RandomAccessFile.seek(4); writeInt(Int.MAX_VALUE)` 같은 식).
- scan을 호출하면 어떤 예외가 나는가? StorageError.UnexpectedEof? 다른 예외?
- 단계 8 WAL에서 checksum이 왜 필요한지의 동기.

각 과제를 마쳤다면 학습 메모에:
- 어떤 입력에서 깨졌는지.
- 깨진 후 어떤 invariant가 위반됐는지.
- 다음 단계가 어떻게 이를 해결할지 1줄 추측.

---

## 8. 다음 한계 (다음 세션의 동기)

이 세션 코드는 다음 상황에서 깨진다:
- **partial write**: append 도중 process kill → 마지막 record 잘림 → scan 시 `StorageError.UnexpectedEof`. **→ 단계 01-02 또는 단계 8 WAL**.
- **무한 풀스캔**: record 수 늘면 O(N). **→ 단계 3 Index**.
- **메모리 한계**: 거대 record가 OOM. **→ 단계 2 Page IO**.
- **단일 writer**: 두 process가 동시에 append하면 데이터 깨짐. **→ 단계 9 LockManager** (또는 OS file lock 임시 사용).

---

## 9. Spot check 권고 (codex 보정 5)

**단계 1은 초반이므로 매 단계 spot check 권장.**

확인 항목 (사용자가 단계 종료 시 수행):
1. SimpleDB `HeapFile.java` 의 `writePage` 와 본 `append`의 구조 비교.
   - 차이: SimpleDB는 page 단위, 우리는 record 단위 (단계 2에서 통합).
2. SimpleDB의 `Tuple` / `TupleDesc` 가 어떻게 schema를 표현하는지 흝기 (단계 4 예고편).
3. BusTub `DiskManager::WritePage` 가 어떻게 page_id를 offset으로 변환하는지 확인 (단계 2 예고편).

결과를 `docs/handoff/stage-01-handoff.md` "7. Spot check 결과"에 기록.

---

## 10. 단계 완료 체크리스트

- [ ] 5번 코드 3개 파일 모두 타이핑 완료.
- [ ] 4번 + 6번 테스트 모두 통과 (`./gradlew test` 초록).
- [ ] 7번 깨뜨릴 과제 3개 모두 수행하고 학습 메모 기록.
- [ ] (옵션) 9번 Spot check 수행.
- [ ] `docs/handoff/stage-01-handoff.md` 작성.
- [ ] 다음 세션 결정: 01-02 (partial write 처리) vs 단계 2 (page IO).

---

## 변경 이력

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 작성. 단계 1 첫 세션. |
