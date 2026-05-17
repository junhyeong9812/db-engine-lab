# impl/08-02 — LSN + Checkpoint + Idempotent Recovery (X2 + X5 + C5)

> **검증**: LsnRecoveryCheckpointTest 3 + PhysicalBackupTest 1 PASSED.
> 작성 파일:
> - 수정: `src/main/kotlin/com/dbenginelab/wal/LogManager.kt` (LSN 추가, replay 갱신, encode Checkpoint 추가)
> - 수정: `src/main/kotlin/com/dbenginelab/wal/LogRecord.kt` (`Checkpoint` data class + TAG_CHECKPOINT)
> - 수정: `src/main/kotlin/com/dbenginelab/wal/Recovery.kt` (Checkpoint when 분기 추가)
> - 신규: `src/main/kotlin/com/dbenginelab/wal/IdempotentRecovery.kt`
> - 신규: `src/main/kotlin/com/dbenginelab/wal/CheckpointManager.kt`
> - 신규: `src/main/kotlin/com/dbenginelab/backup/PhysicalBackup.kt`
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/wal/LsnRecoveryCheckpointTest.kt`, `src/test/kotlin/com/dbenginelab/backup/PhysicalBackupTest.kt`

## 0. 참조
- ARIES Analysis pass의 단순화 (pageLSN은 학습용으로 별도 메타).
- PostgreSQL `pg_control` + checkpoint LSN.

## 1. invariant
- LSN monotonic. reopen 시 file에서 count로 복원.
- IdempotentRecovery 두 번 호출해도 같은 결과 (skippedAlreadyApplied 증가, rowsReapplied=0).
- Checkpoint record는 LSN snapshot + active TX 기록.
- PhysicalBackup snapshot → restore → 데이터 동일.

## 2. 핵심 결정
- **LSN을 sequential count로** — file order = LSN. 단순화. 실 production은 page header에 pageLSN.
- **별도 `recovery.meta` 파일에 lastAppliedLsn** — idempotent 보장.
- **CheckpointRecord를 WAL에 작성** — backup/recovery가 같은 source-of-truth.
- **PhysicalBackup은 data + WAL 모두 복사** — restore 후 WAL replay로 incremental 가능.

## 3. 수정 코드 — LogManager.kt (한 줄 한 줄)

```kotlin
package com.dbenginelab.wal
import java.io.Closeable
import java.io.EOFException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class LogManager(path: String) : Closeable {
    private val file = RandomAccessFile(path, "rw")
    @Volatile private var nextLsn: Long = 1                          // Q: @Volatile 필요? multi-thread?
    // <details><summary>A</summary>
    // LogManager는 단계 13 ConnectionPool과 같이 쓰면 multi-thread. @Volatile + 향후 lock.
    // </details>

    init {
        file.seek(0)
        var count = 0L                                               // 파일에 있는 record 수 = restored LSN
        while (file.filePointer < file.length()) {
            try {
                val len = file.readInt()                             // record length 읽고 skip
                file.skipBytes(len)
                count++
            } catch (_: EOFException) { break }                      // partial trailing record 무시
        }
        nextLsn = count + 1                                          // 다음 LSN은 count+1
        file.seek(file.length())                                     // append 위치
    }

    fun append(record: LogRecord): Long {                            // Q: 반환 타입이 Unit → Long으로 변경된 이유?
        val payload = encode(record)
        file.seek(file.length())
        file.writeInt(payload.size)
        file.write(payload)
        val lsn = nextLsn                                            // 발급된 LSN을 caller에 반환
        nextLsn++
        return lsn
    }
    // <details><summary>A</summary>
    // Caller (Transaction.commit) 가 "commit LSN" 알면 group commit / replication position 추적 가능.
    // </details>

    fun sync() { file.fd.sync() }                                    // durability barrier
    fun currentLsn(): Long = nextLsn - 1                             // 마지막 발급 LSN

    fun replay(handler: (LogRecord) -> Unit) {                       // 기존 호환 — LSN drop
        replayWithLsn { _, rec -> handler(rec) }
    }

    fun replayWithLsn(handler: (Long, LogRecord) -> Unit) {          // 신규 — LSN과 record 함께
        file.seek(0)
        var lsn = 0L
        while (file.filePointer < file.length()) {
            try {
                val len = file.readInt()
                val bytes = ByteArray(len); file.readFully(bytes)
                lsn++
                handler(lsn, decode(bytes))
            } catch (_: EOFException) { break }
        }
        file.seek(file.length())
    }

    // encode/decode는 LogRecord의 4 + Checkpoint 5 케이스 처리 (전체 코드 src 파일 참조).
    private fun encode(record: LogRecord): ByteArray { /* 위 src 참조 */ TODO() }
    private fun decode(bytes: ByteArray): LogRecord { /* 위 src 참조 */ TODO() }

    override fun close() { file.close() }
}
```

## 4. 수정 코드 — LogRecord.kt (Checkpoint 추가)

```kotlin
sealed class LogRecord {
    abstract val txId: Long
    data class BeginTx(override val txId: Long) : LogRecord()
    data class InsertRow(...) : LogRecord() { /* equals/hashCode */ }
    data class CommitTx(override val txId: Long) : LogRecord()
    data class AbortTx(override val txId: Long) : LogRecord()

    // Q: txId가 의미 없는데 왜 override? sealed 인터페이스 제약?
    data class Checkpoint(val checkpointLsn: Long, val activeTxs: List<Long>) : LogRecord() {
        override val txId: Long = 0L
    }
    // <details><summary>A</summary>
    // sealed에서 모든 case가 같은 인터페이스 필요. Checkpoint는 system event라 txId=0 (sentinel).
    // </details>

    companion object {
        const val TAG_BEGIN: Byte = 0
        const val TAG_INSERT: Byte = 1
        const val TAG_COMMIT: Byte = 2
        const val TAG_ABORT: Byte = 3
        const val TAG_CHECKPOINT: Byte = 4                           // 신규 tag
    }
}
```

## 5. 신규 — IdempotentRecovery.kt

```kotlin
class IdempotentRecovery(
    private val logManager: LogManager,
    private val metaPath: String,                                    // 별도 meta 파일 (lastAppliedLsn 저장)
    private val heapLookup: (String) -> TableHeap?,
) {
    data class Stats(val skippedAlreadyApplied: Int, val rowsReapplied: Int, val newLastAppliedLsn: Long)

    fun recover(): Stats {
        val lastApplied = readLastAppliedLsn()                       // 이전 recovery 결과 읽음
        val perTxInserts = mutableMapOf<Long, MutableList<Triple<Long, String, ByteArray>>>()
        val committed = mutableSetOf<Long>()
        val aborted = mutableSetOf<Long>()
        var maxLsn = lastApplied
        var skipped = 0

        logManager.replayWithLsn { lsn, rec ->
            if (lsn <= lastApplied) {                                // 이미 적용된 LSN skip
                skipped++
                return@replayWithLsn
            }
            maxLsn = maxOf(maxLsn, lsn)
            when (rec) {
                is LogRecord.BeginTx -> perTxInserts[rec.txId] = mutableListOf()
                is LogRecord.InsertRow ->
                    perTxInserts.getOrPut(rec.txId) { mutableListOf() }
                        .add(Triple(lsn, rec.tableName, rec.tupleBytes))
                is LogRecord.CommitTx -> committed.add(rec.txId)
                is LogRecord.AbortTx -> aborted.add(rec.txId)
                is LogRecord.Checkpoint -> { /* 무시 — backup이 사용 */ }
            }
        }

        var rowsReapplied = 0
        for ((txId, inserts) in perTxInserts) {
            if (txId !in committed || txId in aborted) continue
            for ((_, tableName, tupleBytes) in inserts) {
                val heap = heapLookup(tableName) ?: continue
                heap.insert(Tuple.decode(heap.schema, tupleBytes))
                rowsReapplied++
            }
        }
        writeLastAppliedLsn(maxLsn)                                  // 다음 recovery 위해 저장
        return Stats(skipped, rowsReapplied, maxLsn)
    }

    private fun readLastAppliedLsn(): Long { ... }
    private fun writeLastAppliedLsn(lsn: Long) { ... }
}
```

## 6. 신규 — CheckpointManager.kt

```kotlin
class CheckpointManager(private val logManager: LogManager) {
    fun checkpoint(activeTxs: Set<Long>): Long {                     // Q: activeTxs를 caller가 넘기는 이유?
        val ckLsn = logManager.currentLsn()
        val lsn = logManager.append(LogRecord.Checkpoint(ckLsn, activeTxs.toList()))
        logManager.sync()
        return lsn
    }
    // <details><summary>A</summary>
    // TransactionManager가 active set의 source-of-truth. CheckpointManager가 직접 알기 어려움. dependency injection.
    // </details>

    fun lastCheckpoint(): LogRecord.Checkpoint? {                    // 가장 최근 Checkpoint 조회
        var last: LogRecord.Checkpoint? = null
        logManager.replay { rec ->
            if (rec is LogRecord.Checkpoint) last = rec               // 매번 덮어쓰면 마지막만 남음
        }
        return last
    }
}
```

## 7. 신규 — PhysicalBackup.kt

```kotlin
class PhysicalBackup(
    private val dataDir: String,                                     // 원본 데이터 디렉토리
    private val walPath: String,                                     // WAL 파일 경로
    private val backupDir: String,                                   // 백업 저장 위치
) {
    data class BackupInfo(val checkpointLsn: Long, val files: List<String>)

    fun snapshot(checkpointManager: CheckpointManager, activeTxs: Set<Long> = emptySet()): BackupInfo {
        val ckLsn = checkpointManager.checkpoint(activeTxs)          // 백업 직전 Checkpoint
        val backupDirFile = File(backupDir); backupDirFile.mkdirs()
        val dataFiles = File(dataDir).listFiles()?.filter { it.isFile } ?: emptyList()
        val copied = mutableListOf<String>()
        for (f in dataFiles) {
            f.copyTo(File(backupDirFile, f.name), overwrite = true)  // data 파일들 복사
            copied.add(f.name)
        }
        val walFile = File(walPath)
        if (walFile.exists()) {
            walFile.copyTo(File(backupDirFile, walFile.name), overwrite = true)  // WAL도 복사
            copied.add(walFile.name)
        }
        File(backupDirFile, "backup.meta").writeText(ckLsn.toString())  // metadata
        return BackupInfo(ckLsn, copied)
    }

    fun restore(targetDataDir: String) {                             // 백업 → 새 디렉토리 복원
        val backupDirFile = File(backupDir); require(backupDirFile.isDirectory)
        val target = File(targetDataDir); target.mkdirs()
        for (f in backupDirFile.listFiles().orEmpty()) {
            if (f.name == "backup.meta") continue                    // metadata는 복원 대상 아님
            f.copyTo(File(target, f.name), overwrite = true)
        }
    }

    fun readCheckpointLsn(): Long = File(backupDir, "backup.meta").readText().trim().toLong()
}
```

## 8. 검증 테스트 (4 PASSED)
- LSN monotonic + reopen 복원
- IdempotentRecovery 두 번 호출 — 두 번째는 skip + 중복 apply 없음
- Checkpoint record 작성 → lastCheckpoint로 조회
- PhysicalBackup snapshot → restore → 데이터 동일

## 9. 깨뜨릴 과제
- 진짜 pageLSN — Page header에 LSN 자리 추가하면 어디 영향? (단계 2 Page.kt 변경)
- WAL rule — flush 시 pageLSN <= WAL LSN 강제. 어떻게?
- Crash 중 Checkpoint — partial Checkpoint record는 recovery에서 어떻게?

## 10. 다음 한계
- pageLSN 없음 — 실 production은 page header에 LSN 박아서 idempotent guarantee 더 강함.
- group commit 없음.
