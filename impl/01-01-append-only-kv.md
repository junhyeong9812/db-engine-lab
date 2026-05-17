# impl/01-01 — Append-Only Key-Value Store (한 줄 한 줄)

> 상위: `docs/stages/01-storage.md`
> **검증**: AppendOnlyFileTest 3 PASSED.
> 작성 파일:
> - 신규: `src/main/kotlin/com/dbenginelab/storage/Record.kt`
> - 신규: `src/main/kotlin/com/dbenginelab/storage/StorageError.kt`
> - 신규: `src/main/kotlin/com/dbenginelab/storage/AppendOnlyFile.kt`
> - 신규: `src/test/kotlin/com/dbenginelab/storage/AppendOnlyFileTest.kt`

## 0. 참조
- SimpleDB `HeapFile.java` (단순화)
- BusTub `disk_manager.cpp` (대조 — page 단위 vs 우리 raw record)

## 1. invariant
- I-1: append 후 close → reopen → scanAll 결과 동일.
- I-2: 한 record 경계 식별 가능 (length-prefix).
- I-3: flush 후 process kill → 데이터 보존.

## 2. 의존성
- 없음 (첫 세션).
- 외부: `java.io.RandomAccessFile`.

## 3. 문제 정의
데이터를 process 재시작 후에도 읽을 수 있어야. 가장 단순 = append-only file.

## 4. 실패 테스트 (TDD step 2)

작성 위치: `src/test/kotlin/com/dbenginelab/storage/AppendOnlyFileTest.kt`

```kotlin
package com.dbenginelab.storage                                      // 패키지

import org.junit.jupiter.api.Test                                    // JUnit 5
import org.junit.jupiter.api.io.TempDir                              // 임시 디렉토리 자동 생성/삭제
import java.nio.file.Path                                            // 임시 디렉토리 타입
import kotlin.test.assertContentEquals                               // ByteArray 비교
import kotlin.test.assertEquals

class AppendOnlyFileTest {
    @Test
    fun `I-1 append 후 reopen하면 모든 record를 순서대로 다시 읽는다`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("data.log").toString()            // 임시 파일 경로
        val r1 = Record("k1".toByteArray(), "v1".toByteArray())      // 테스트용 record 3개
        val r2 = Record("longer-key-2".toByteArray(), "value-2".toByteArray())
        val r3 = Record("k3".toByteArray(), "v3".toByteArray())

        AppendOnlyFile(path).use { writer ->                         // Q: use {} 의 역할?
            writer.append(r1); writer.append(r2); writer.append(r3)
            writer.flush()                                            // disk 동기화
        }
        // <details><summary>A</summary>
        // Closeable + use {} — try-finally로 close 자동. close 시 file handle 풀려서 reopen 가능.
        // </details>

        AppendOnlyFile(path).use { reader ->                         // reopen
            val records: List<Record> = reader.scanAll()
            assertEquals(3, records.size, "record 개수 일치")
            assertContentEquals(r1.key, records[0].key)
            // ... (모든 record 검증)
        }
    }
}
```

→ `Unresolved reference: Record / AppendOnlyFile`. 5번에서 구현.

---

## 5. 구현 코드 — 한 줄 한 줄

### 5.1 `Record.kt`

작성 위치: `src/main/kotlin/com/dbenginelab/storage/Record.kt`

```kotlin
package com.dbenginelab.storage                                      // storage 패키지 — 모든 storage 클래스

// Q: 왜 data class 가 아닌가? 평범한 byte 묶음인데?
class Record(val key: ByteArray, val value: ByteArray)
// <details><summary>A</summary>
// ByteArray.equals 는 reference equality — data class 자동 equals가 "내용 같으면 같다" 거짓말. 일반 class로 명시.
// </details>
```

### 5.2 `StorageError.kt`

작성 위치: `src/main/kotlin/com/dbenginelab/storage/StorageError.kt`

```kotlin
package com.dbenginelab.storage                                      // 같은 패키지

// Q: 왜 nullable이 아니라 sealed class로 에러 표현?
sealed class StorageError(message: String) : RuntimeException(message) {
    // <details><summary>A</summary>
    // 단계 8 recovery에서 "없음" (null)과 "깨진 상태" (corrupt)를 다르게 처리해야. sealed로 의미 분리.
    // </details>

    class CorruptRecord(offset: Long, reason: String)                // 손상된 record (단계 8에서 사용)
        : StorageError("offset=$offset: $reason")

    class UnexpectedEof(expectedBytes: Int, gotBytes: Int)           // partial write 감지
        : StorageError("expected $expectedBytes bytes, got $gotBytes (partial write?)")
}
```

### 5.3 `AppendOnlyFile.kt`

작성 위치: `src/main/kotlin/com/dbenginelab/storage/AppendOnlyFile.kt`

```kotlin
package com.dbenginelab.storage                                      // 같은 패키지

import java.io.Closeable                                             // try-with-resources 인터페이스
import java.io.EOFException                                          // partial read 시 throw
import java.io.RandomAccessFile                                      // seek 가능한 file IO

class AppendOnlyFile(path: String) : Closeable {                     // Closeable로 use {} 지원

    // Q: FileOutputStream/FileInputStream 분리 안 하고 RandomAccessFile 하나로?
    private val file: RandomAccessFile = RandomAccessFile(path, "rw")
    // <details><summary>A</summary>
    // append와 scan을 같은 객체에서 — seek 필요 (scan은 처음부터, append는 끝). RandomAccessFile만 제공.
    // </details>

    init {
        // Q: 객체 생성 시 file pointer를 끝으로 옮기는 이유?
        file.seek(file.length())
        // <details><summary>A</summary>
        // append-only 패턴 — 새 데이터는 끝에 추가. init에서 미리 옮겨두면 첫 append에서 seek 불필요 (성능).
        // </details>
    }

    fun append(record: Record) {
        // Q: 왜 매 append 마다 seek(length) 명시?
        file.seek(file.length())
        // <details><summary>A</summary>
        // scan 후 pointer가 끝에 있지만, scan이 EOFException으로 중단 시 위치 모호. 안전을 위해 명시 seek.
        // </details>

        // Q: 왜 length를 먼저 쓰는가? key/value bytes만 쓰면 안 되나?
        file.writeInt(record.key.size)
        file.write(record.key)
        file.writeInt(record.value.size)
        file.write(record.value)
        // <details><summary>A</summary>
        // 가변 길이 record의 경계 식별 — length-prefix가 frame. 안 쓰면 어디서 한 record 끝나는지 모름 (I-2).
        // </details>
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
        // Q: 종료 조건이 file.length()인데 partial write로 마지막이 잘렸으면?
        while (file.filePointer < file.length()) {
            val recordStart = file.filePointer                       // 디버그용 (에러 메시지)
            try {
                val keyLen = file.readInt()                          // length-prefix 읽음
                val key = ByteArray(keyLen)
                file.readFully(key)                                  // key bytes
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
                // EOFException은 자바 표준 예외 — 호출자는 "왜 EOF?"를 모름. 도메인 에러로 감싸야 단계 8 recovery에서 의미 분리.
                // </details>
            }
        }
        return result
    }

    override fun close() {
        file.close()                                                 // file handle 해제
    }
}
```

---

## 6. 검증 테스트 (TDD step 4)

추가 테스트 2개 (`I-2` 빈 record, `I-1` 혼합 시퀀스) — 모두 3 PASSED.

## 7. 직접 깨뜨릴 과제
- flush 안 하고 process kill → 어떤 입력에서 데이터 잃음? (OS buffer 분석)
- 거대 record (100MB) → OOM? IO 성능?
- 길이 prefix 손상 → scan 결과?

## 8. 다음 한계
- partial write → `UnexpectedEof`. **단계 01-02 또는 단계 8 WAL**.
- 풀스캔 O(N) → **단계 3 Index**.
- 메모리 한계 → **단계 2 Page IO**.
- 단일 writer → **단계 9 Lock**.
