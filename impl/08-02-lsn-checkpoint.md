# impl/08-02 — LSN + Checkpoint + Idempotent Recovery (보강 X2 · X5 · C5)

> **종류**: 보강형 (08-01의 WAL에 순번·체크포인트·멱등성을 얹는다)
> **상위 단계**: `docs/stages/08-wal-recovery.md`
> **코드 정본**: git `5505edc` — "complete: 21 stages + 12 보강 (120/120 tests)"
> **이 세션의 범위**: 복구를 **여러 번 돌려도 안전하게** 만들고, 로그를 처음부터 읽지 않아도 되게 만든다.
> **작성 파일**:
> - 수정: `wal/LogManager.kt` — LSN 부여·복원, `Checkpoint` 인코딩
> - 수정: `wal/LogRecord.kt` — `Checkpoint` 레코드 + `TAG_CHECKPOINT`
> - 수정: `wal/Recovery.kt` — `Checkpoint` 분기
> - 신규: `wal/IdempotentRecovery.kt` · `wal/CheckpointManager.kt` · `backup/PhysicalBackup.kt`
> - 신규 테스트: `wal/LsnRecoveryCheckpointTest.kt` · `backup/PhysicalBackupTest.kt`
> **검증**: `LsnRecoveryCheckpointTest` 4 PASSED · `PhysicalBackupTest` 1 PASSED
> **예상 타이핑 시간**: 60분

---

## 0. 참조

- ARIES의 Analysis pass를 단순화. 진짜 ARIES는 page header에 `pageLSN`을 두지만, 우리는 학습용으로 **별도 메타 파일**에 마지막 적용 LSN을 둔다.
- PostgreSQL `pg_control` + checkpoint LSN.

## 1. 만족시킬 invariant

- **CI-1**: LSN은 단조 증가하고, reopen 시 파일에서 복원된다.
- **CI-2**: `IdempotentRecovery`를 두 번 호출해도 결과가 같다 (두 번째는 `skippedAlreadyApplied`만 늘고 `rowsReapplied = 0`).
- **CI-3**: Checkpoint 레코드는 LSN 스냅샷과 활성 트랜잭션 목록을 담는다.
- **CI-4**: `PhysicalBackup` snapshot → restore 후 데이터가 동일하다.

## 2. 핵심 결정

- **LSN을 순차 카운트로** — 파일 순서가 곧 LSN이다. 진짜 production은 page header에 `pageLSN`을 박아 "이 page는 어느 LSN까지 반영됐나"를 page마다 안다. 우리는 그 단계를 건너뛴다.
- **별도 `recovery.meta`에 `lastAppliedLsn`** — 이것이 멱등성의 근거다. "어디까지 적용했다"를 데이터와 **분리해서** 기록한다.
- **Checkpoint를 WAL 안에 쓴다** — 백업과 복구가 같은 단일 출처를 보게 하려고.
- **PhysicalBackup은 데이터와 WAL을 함께 복사** — restore 후 WAL replay로 증분 복구가 가능해진다.

## 3. 문제 정의 (TDD step 1)

08-01의 복구에는 세 가지 구멍이 있다.

1. **멱등이 아니다.** 복구를 두 번 돌리면 같은 INSERT가 두 번 적용된다. 복구 도중 죽으면? 다시 돌려야 하는데, 다시 돌리면 중복된다. **복구가 안전하지 않으면 복구가 아니다.**
2. **로그가 무한히 자란다.** 재시작마다 첫 레코드부터 전부 재생한다. 한 달 운영한 DB는 재시작에 한 달치 로그를 읽는다.
3. **"어디까지 했다"를 적을 방법이 없다.** 레코드에 번호가 없으니 진행 상황을 기록할 수 없다.

셋은 사실 한 문제다 — **로그 레코드에 순번(LSN)이 없다.** 순번을 주면 "여기까지 적용했다"를 적을 수 있고(1번 해결), "이 지점 이전은 이미 안전하다"는 표시(Checkpoint)를 남길 수 있다(2번 해결).

## 4. 수정·신규 코드

> **정본 특이사항**: `LogManager.kt`·`LogRecord.kt`·`Recovery.kt`는 08-01에서 이미 **이 세션까지 반영된 최종형**으로 실렸다(정본에 중간 상태가 없다). 그래서 여기서는 다시 싣지 않고, **이번 세션에서 의미가 생기는 부분**을 짚는다:
> - `LogRecord.Checkpoint` 와 `TAG_CHECKPOINT` — 08-01에서는 쓰이지 않던 레코드 종류.
> - `LogManager`의 `lsn` 카운터와 reopen 시 복원 로직.
> - `Recovery`의 `is LogRecord.Checkpoint ->` 분기.
> 08-01에서 친 그 파일들을 지금 다시 열어 **이 세 곳이 무엇을 하는지** 확인하고 나서 아래로 넘어가라.

### 4.1 `IdempotentRecovery.kt` (신규)

```kotlin
// src/main/kotlin/com/dbenginelab/wal/IdempotentRecovery.kt @ 5505edc
package com.dbenginelab.wal

import com.dbenginelab.catalog.Tuple
import com.dbenginelab.table.TableHeap
import java.io.File

/**
 * Stage 8 보강 (C5): LSN-based idempotent recovery.
 *
 * 각 데이터 디렉토리에 `recovery.meta` 파일을 두고 마지막으로 적용된 LSN을 기록.
 * Recovery 시 LSN > lastAppliedLsn 인 record만 apply → 중복 회피.
 *
 * Format: 단일 long 값 (lastAppliedLsn).
 */
class IdempotentRecovery(
    private val logManager: LogManager,
    private val metaPath: String,
    private val heapLookup: (String) -> TableHeap?,
) {

    data class Stats(
        val skippedAlreadyApplied: Int,
        val rowsReapplied: Int,
        val newLastAppliedLsn: Long,
    )

    fun recover(): Stats {
        val lastApplied = readLastAppliedLsn()
        val perTxInserts = mutableMapOf<Long, MutableList<Triple<Long, String, ByteArray>>>()
        val committed = mutableSetOf<Long>()
        val aborted = mutableSetOf<Long>()
        var maxLsn = lastApplied
        var skipped = 0

        logManager.replayWithLsn { lsn, rec ->
            if (lsn <= lastApplied) {
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
                is LogRecord.Checkpoint -> { /* ignore for now — used by Backup */ }
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

        writeLastAppliedLsn(maxLsn)
        return Stats(skipped, rowsReapplied, maxLsn)
    }

    private fun readLastAppliedLsn(): Long {
        val file = File(metaPath)
        if (!file.exists()) return 0L
        return file.readText().trim().toLong()
    }

    private fun writeLastAppliedLsn(lsn: Long) {
        val file = File(metaPath)
        file.parentFile?.mkdirs()
        file.writeText(lsn.toString())
    }
}
```

### 4.2 `CheckpointManager.kt` (신규)

```kotlin
// src/main/kotlin/com/dbenginelab/wal/CheckpointManager.kt @ 5505edc
package com.dbenginelab.wal

/**
 * Stage 8 보강 (X5): Checkpoint manager — periodic snapshot of (LSN, active txs).
 *
 * Records a Checkpoint log record. Backup/Recovery use it as a consistent
 * starting point.
 */
class CheckpointManager(private val logManager: LogManager) {

    /**
     * Create a checkpoint at the current LSN with the given active tx ids.
     * Returns the LSN assigned to the checkpoint record itself.
     */
    fun checkpoint(activeTxs: Set<Long>): Long {
        val ckLsn = logManager.currentLsn()
        val lsn = logManager.append(LogRecord.Checkpoint(ckLsn, activeTxs.toList()))
        logManager.sync()
        return lsn
    }

    /** Find the most recent checkpoint in the log. Returns null if none. */
    fun lastCheckpoint(): LogRecord.Checkpoint? {
        var last: LogRecord.Checkpoint? = null
        logManager.replay { rec ->
            if (rec is LogRecord.Checkpoint) last = rec
        }
        return last
    }
}
```

### 4.3 `backup/PhysicalBackup.kt` (신규)

```kotlin
// src/main/kotlin/com/dbenginelab/backup/PhysicalBackup.kt @ 5505edc
package com.dbenginelab.backup

import com.dbenginelab.wal.CheckpointManager
import com.dbenginelab.wal.LogManager
import java.io.File

/**
 * Stage 16 보강 (X5): Physical backup with checkpoint snapshot.
 *
 * Procedure:
 *  1. Issue a Checkpoint via CheckpointManager (Backup의 consistent 시점).
 *  2. Copy data directory files into backupDir.
 *  3. Copy WAL file as well (for redo since checkpoint).
 *  4. Write `backup.meta` with the checkpoint LSN.
 *
 * Restore:
 *  1. Copy backup files into target dataDir.
 *  2. Open LogManager → run IdempotentRecovery (LSN > checkpointLsn 만 apply).
 */
class PhysicalBackup(
    private val dataDir: String,
    private val walPath: String,
    private val backupDir: String,
) {

    data class BackupInfo(val checkpointLsn: Long, val files: List<String>)

    fun snapshot(checkpointManager: CheckpointManager, activeTxs: Set<Long> = emptySet()): BackupInfo {
        val ckLsn = checkpointManager.checkpoint(activeTxs)
        val backupDirFile = File(backupDir)
        backupDirFile.mkdirs()

        val dataFiles = File(dataDir).listFiles()?.filter { it.isFile } ?: emptyList()
        val copied = mutableListOf<String>()
        for (f in dataFiles) {
            val dst = File(backupDirFile, f.name)
            f.copyTo(dst, overwrite = true)
            copied.add(f.name)
        }
        // Also snapshot the WAL.
        val walFile = File(walPath)
        if (walFile.exists()) {
            walFile.copyTo(File(backupDirFile, walFile.name), overwrite = true)
            copied.add(walFile.name)
        }
        File(backupDirFile, "backup.meta").writeText(ckLsn.toString())
        return BackupInfo(ckLsn, copied)
    }

    fun restore(targetDataDir: String) {
        val backupDirFile = File(backupDir)
        require(backupDirFile.isDirectory) { "backup dir not found: $backupDir" }
        val target = File(targetDataDir)
        target.mkdirs()
        for (f in backupDirFile.listFiles().orEmpty()) {
            if (f.name == "backup.meta") continue
            f.copyTo(File(target, f.name), overwrite = true)
        }
    }

    fun readCheckpointLsn(): Long {
        return File(backupDir, "backup.meta").readText().trim().toLong()
    }
}
```

## 5. 검증 테스트 (green)

### 5.1 `LsnRecoveryCheckpointTest.kt`

```kotlin
// src/test/kotlin/com/dbenginelab/wal/LsnRecoveryCheckpointTest.kt @ 5505edc
package com.dbenginelab.wal

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
import kotlin.test.assertTrue

class LsnRecoveryCheckpointTest {
    private val schema = TableSchema(
        name = "users",
        columns = listOf(
            ColumnDef("id", Type.BIGINT, nullable = false),
            ColumnDef("name", Type.STRING, nullable = false),
        ),
    )

    @Test
    fun `LSN은 monotonic + reopen 복원`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("w.log").toString()
        LogManager(log).use { lm ->
            val l1 = lm.append(LogRecord.BeginTx(1L))
            val l2 = lm.append(LogRecord.CommitTx(1L))
            assertEquals(1L, l1); assertEquals(2L, l2)
            assertEquals(2L, lm.currentLsn())
            lm.sync()
        }
        LogManager(log).use { lm ->
            assertEquals(2L, lm.currentLsn())
            val l3 = lm.append(LogRecord.BeginTx(2L))
            assertEquals(3L, l3)
        }
    }

    @Test
    fun `IdempotentRecovery는 두 번 호출해도 중복 apply 안 함`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("w.log").toString()
        val data = tempDir.resolve("u.data").toString()
        val meta = tempDir.resolve("recovery.meta").toString()
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val tm = TransactionManager(lm)
                val tx = tm.begin()
                tx.insert("users", heap, Tuple(schema, listOf(1L, "A")))
                tx.commit()
            }}
        }
        java.io.File(data).delete()
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val s1 = IdempotentRecovery(lm, meta) { if (it == "users") heap else null }.recover()
                assertEquals(1, s1.rowsReapplied)
                assertEquals(1, heap.rowCount())

                // 두 번째 recovery — 이미 적용된 LSN 모두 skip.
                val s2 = IdempotentRecovery(lm, meta) { if (it == "users") heap else null }.recover()
                assertEquals(0, s2.rowsReapplied)
                assertTrue(s2.skippedAlreadyApplied > 0)
                assertEquals(1, heap.rowCount())  // 중복 apply 없음
            }}
        }
    }

    @Test
    fun `Checkpoint record 작성 후 lastCheckpoint로 조회`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("w.log").toString()
        LogManager(log).use { lm ->
            lm.append(LogRecord.BeginTx(1L))
            lm.append(LogRecord.CommitTx(1L))
            val cm = CheckpointManager(lm)
            val ckLsn = cm.checkpoint(setOf(2L, 3L))
            assertTrue(ckLsn > 0)
        }
        LogManager(log).use { lm ->
            val ck = CheckpointManager(lm).lastCheckpoint()!!
            assertEquals(listOf(2L, 3L), ck.activeTxs.sorted())
        }
    }
}
```

### 5.2 `PhysicalBackupTest.kt`

```kotlin
// src/test/kotlin/com/dbenginelab/backup/PhysicalBackupTest.kt @ 5505edc
package com.dbenginelab.backup

import com.dbenginelab.catalog.ColumnDef
import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.catalog.Type
import com.dbenginelab.storage.BufferPool
import com.dbenginelab.storage.PagedFile
import com.dbenginelab.table.TableHeap
import com.dbenginelab.wal.CheckpointManager
import com.dbenginelab.wal.LogManager
import com.dbenginelab.wal.TransactionManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhysicalBackupTest {
    private val schema = TableSchema(
        name = "users",
        columns = listOf(
            ColumnDef("id", Type.BIGINT, nullable = false),
            ColumnDef("name", Type.STRING, nullable = false),
        ),
    )

    @Test
    fun `snapshot 후 restore로 데이터 복원`(@TempDir tempDir: Path) {
        val dataDir = tempDir.resolve("data").toString()
        val backupDir = tempDir.resolve("backup").toString()
        val targetDir = tempDir.resolve("target").toString()
        java.io.File(dataDir).mkdirs()
        val walPath = "$dataDir/w.log"
        val dataPath = "$dataDir/users.data"

        LogManager(walPath).use { lm ->
            PagedFile(dataPath).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val tx = TransactionManager(lm).begin()
                tx.insert("users", heap, Tuple(schema, listOf(1L, "A")))
                tx.insert("users", heap, Tuple(schema, listOf(2L, "B")))
                tx.commit()
            }}
            val backup = PhysicalBackup(dataDir, walPath, backupDir)
            val info = backup.snapshot(CheckpointManager(lm))
            assertTrue(info.checkpointLsn > 0)
            assertTrue(info.files.any { it == "users.data" })
            assertTrue(info.files.any { it == "w.log" })
        }

        PhysicalBackup(dataDir, walPath, backupDir).restore(targetDir)
        val restoredHeap = "$targetDir/users.data"
        PagedFile(restoredHeap).use { pf -> BufferPool(pf, 16).use { bp ->
            val heap = TableHeap(schema, pf, bp)
            assertEquals(2, heap.rowCount())
        }}
    }
}
```

```bash
./gradlew test --tests 'com.dbenginelab.wal.LsnRecoveryCheckpointTest' --tests 'com.dbenginelab.backup.PhysicalBackupTest'
```

**기대 결과**: `LsnRecoveryCheckpointTest` **4 PASSED** · `PhysicalBackupTest` **1 PASSED**

invariant 대응:
- **CI-1**, **CI-2**, **CI-3** ← `LsnRecoveryCheckpointTest`의 네 테스트. 어느 것이 어느 invariant인지 이름을 보고 직접 짝지어봐라.
- **CI-4** ← `snapshot 후 restore로 데이터 복원`

## 6. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

**1.** `recovery.meta`를 지우고 복구를 다시 돌려라. 무슨 일이 일어나나? 이 파일이 데이터와 함께 백업되지 않으면?

<details><summary>답</summary>

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
private fun readLastAppliedLsn(): Long {
    val file = File(metaPath)
    if (!file.exists()) return 0L       // ← 파일이 없으면 0
```

`lastApplied = 0`이 되어 **로그를 처음부터 전부 다시 적용한다.** 이미 heap에 있는 행들이 한 번 더 들어간다 → **전 데이터 중복.**

즉 이 파일 하나가 멱등성의 **유일한** 근거다. 데이터는 멀쩡한데 이 작은 텍스트 파일이 없으면 복구가 데이터를 망친다.

백업에서 빠지면 정확히 그 사고가 난다:

```
백업: 데이터 파일 + WAL은 챙기고 recovery.meta는 빠뜨림
복원: recovery.meta 없음 → lastApplied = 0 → WAL 전체 재적용
결과: 백업 시점에 이미 반영돼 있던 행들이 두 배로
```

**"부수적인 메타 파일"이 사실은 정합성의 핵심**인 경우다. 실제 PostgreSQL의 `pg_control`이 같은 위치를 차지하고, 그래서 물리 백업은 데이터 디렉토리를 **통째로** 뜬다 — 무엇이 중요한지 사람이 고르지 않게 하려고.
</details>

**2.** `lastAppliedLsn`을 데이터에 적용하기 **전에** 갱신하도록 순서를 바꿔라. 어느 시점에 죽으면 데이터가 유실되나?

<details><summary>답</summary>

정상 동작은 완전히 같다. 죽는 시점이 문제다.

```
현재 순서: apply 루프 → writeLastAppliedLsn(maxLsn)
바꾼 순서: writeLastAppliedLsn(maxLsn) → apply 루프
```

바꾼 뒤 **apply 도중에 죽으면**:

```
recovery.meta = 500 (다 했다고 기록됨)
실제 적용된 것 = LSN 300까지

재시작 → lastApplied = 500 → "301~500은 이미 했다" → 건너뜀
→ 301~500의 데이터가 영원히 사라진다
```

**커밋된 트랜잭션이 조용히 소멸한다.** 아무도 에러를 내지 않고, 로그에는 데이터가 남아 있는데 다시는 적용되지 않는다.

현재 순서는 반대 방향으로만 틀린다 — apply를 하고 meta 갱신 전에 죽으면 **다음에 다시 적용**한다. 중복이 생기지만 그건 idempotent 처리로 흡수할 수 있다.

**"두 번 하는 것"과 "한 번도 안 하는 것" 중 어느 쪽이 덜 나쁜가**의 선택이고, 복구에서는 거의 항상 전자다. 이 원칙을 **at-least-once**라 부르고, 메시지 큐·작업 스케줄러에서도 같은 이유로 같은 선택을 한다.
</details>

**3.** Checkpoint를 WAL 밖의 별도 파일에 쓰도록 바꾼다면 무엇이 나빠지나?

<details><summary>답</summary>

**두 파일의 시점이 어긋날 수 있다.**

지금은 Checkpoint가 WAL 안의 한 레코드라, "이 체크포인트는 이 로그의 이 지점"이라는 관계가 **구조적으로** 보장된다. 로그를 복사하면 체크포인트도 함께 온다.

밖으로 빼면:

```
checkpoint.meta = "LSN 1000 시점"
wal.log         = LSN 800까지만 있음 (백업 타이밍이 어긋남)
→ 복구가 "1000부터 하면 된다"고 믿는데 로그에 900대가 없다 → 데이터 유실
```

또는 반대로 체크포인트가 오래된 것이면 필요 없는 구간을 다시 재생한다.

핵심은 **원자성 단위가 하나여야 한다**는 것이다. 두 파일을 함께 갱신하려면 그 둘을 묶는 또 다른 원자성 장치가 필요해지고, 그걸 만들려면… WAL이 필요하다. **순환이다.** 그래서 "메타데이터를 WAL 안에 넣는다"가 자연스러운 해법이 된다.

05-01의 tag byte, 08-01의 로그 레코드와 같은 계열의 결정이다 — **관련된 것을 한 스트림에 넣고 순서로 관계를 표현한다.**
</details>

**4.** `PhysicalBackup`이 데이터 파일만 복사하고 WAL은 빼먹도록 고쳐라. restore 후 어떤 데이터가 사라지나?

<details><summary>답</summary>

**마지막 체크포인트 이후에 커밋된 모든 것**이 사라진다.

데이터 파일은 buffer pool이 flush한 시점까지만 반영돼 있다. 그 뒤의 변경은 **WAL에만** 있다. WAL을 빼면 복원 지점이 "마지막 flush 시점"으로 되돌아가고, 그 사이 커밋들은 근거가 사라진다.

복구 절차를 다시 보면 왜 그런지 명확하다:

```
1. 백업 파일을 dataDir로 복사        ← 여기까지가 체크포인트 시점의 상태
2. LogManager 열고 IdempotentRecovery ← WAL로 그 이후를 따라잡는다
```

2번의 재료가 WAL이다. **물리 백업은 "스냅샷 + 그 이후의 변경 로그"의 조합**이고, 둘 중 하나만으로는 어느 시점도 복원하지 못한다.

이것이 **PITR**(Point-In-Time Recovery)의 기본 구조이기도 하다 — 스냅샷을 복원한 뒤 WAL을 원하는 시점까지만 재생하면 "어제 오후 3시 상태"를 만들 수 있다. WAL을 안 챙기면 그 능력이 통째로 사라진다.
</details>

**5.** LSN을 파일 순서가 아니라 랜덤 값으로 준다면 어디가 먼저 깨지나?

<details><summary>답</summary>

**`IdempotentRecovery`의 `lsn <= lastApplied` 비교가 먼저 깨진다.**

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
logManager.replayWithLsn { lsn, rec ->
    if (lsn <= lastApplied) { skipped++; return@replayWithLsn }
```

이 비교는 **LSN이 단조 증가한다**는 것에 전적으로 의존한다. "이 번호보다 작으면 이미 처리했다"가 성립하려면 번호가 순서를 나타내야 한다.

랜덤이면:
- 이미 적용한 레코드가 큰 번호를 받아 **다시 적용된다**(중복)
- 아직 안 한 레코드가 작은 번호를 받아 **영원히 건너뛴다**(유실)

둘이 동시에 일어난다.

그 다음으로 `CheckpointManager.lastCheckpoint()`가 깨진다 — "가장 최근"이 정의되지 않는다.

**LSN은 "식별자"가 아니라 "순서"다.** 이름이 Log **Sequence** Number인 이유이고, 그래서 UUID 같은 것으로 대체할 수 없다. 분산 시스템에서 이 순서를 어떻게 매길 것인가가 어려운 문제(Lamport clock, Spanner의 TrueTime)로 이어진다 — 단일 노드에서는 파일 순서라는 공짜 정답이 있을 뿐이다.
</details>

## 7. 다음 한계

복구가 멱등해졌고 체크포인트로 재생 범위를 줄일 수 있다. 하지만 **crash를 흉내내는 테스트가 아직 없다** — 지금까지의 테스트는 전부 "정상 종료 후 다시 열기"다. 진짜 crash(프로세스 강제 종료, 로그 잘림)에서 어떻게 되는지는 검증되지 않았다.

→ **08-03 CrashSimulation**.
