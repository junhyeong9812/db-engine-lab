# impl/08-01 — WAL + Transaction + Recovery

> **종류**: 세션형
> **상위 단계**: `docs/stages/08-wal-recovery.md`
> **코드 정본**: git `5505edc` — "complete: 21 stages + 12 보강 (120/120 tests)"
> **이 세션의 범위**: 변경을 **먼저 로그에 적고**, 재시작 시 그 로그로 복구한다. 여기서 `WorkUnit`이 `Transaction`이 된다.
> **작성 파일**:
> - 신규 패키지: `src/main/kotlin/com/dbenginelab/wal/`
> - 신규: `LogRecord.kt` · `LogManager.kt` · `Transaction.kt` · `Recovery.kt`
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/wal/WalRecoveryTest.kt`
> **검증**: `WalRecoveryTest` 5 PASSED
> **예상 타이핑 시간**: 80분 (단계 8은 가장 큰 단계 — 나눠 쳐도 된다)

---

## 0. 참조

- 주 참조: SimpleDB `LogFile` (lab4의 ARIES를 크게 단순화).
- 대조 참조: BusTub `log_manager`, `recovery_manager` (project 4).

## 1. 만족시킬 invariant

- **CI-1 (Atomicity)**: commit/abort가 all-or-nothing이다.
- **CI-2 (Durability)**: commit 직후 crash가 나도 recovery가 복원한다.
- **CI-3 (WAL rule)**: 데이터를 바꾸기 **전에** 로그가 먼저 디스크에 있어야 한다.

## 2. 단순화 — deferred-apply + redo-only

이 구현이 진짜 ARIES와 다른 지점을 먼저 못 박는다:

- `Transaction.insert`는 **WAL에 append만** 한다. heap은 건드리지 않는다.
- `commit`에서 sync한 뒤 heap에 일괄 apply한다.
- 따라서 **abort는 heap을 되돌릴 필요가 없다** — 애초에 쓰지 않았으니까. **undo가 없다.**
- recovery는 **redo-only**로 heap을 다시 만든다. 학습용으로는 명료하지만 비효율적이다.

07-01 `WorkUnit`의 deferred insert가 여기서 그대로 살아난다. 그때는 메모리 버퍼였고, 지금은 그 버퍼가 **디스크의 로그**다. 그 차이 하나가 durability를 만든다.

## 3. 문제 정의 (TDD step 1)

07-01의 `WorkUnit`은 commit 도중 죽으면 반쯤 적용된 채 남는다. 그리고 commit이 끝났어도 BufferPool이 flush하기 전에 죽으면 **커밋했다고 응답한 데이터가 사라진다.**

WAL의 발상은 하나다 — **바꾸기 전에 무엇을 바꿀지 먼저 적어라.** 로그는 순차 append라 빠르고, 로그만 디스크에 있으면 나머지는 재구성할 수 있다.

그러려면 세 가지가 필요하다:

1. **로그 레코드의 형식** — BEGIN / INSERT / COMMIT / ABORT. 바이트로 굽고 되읽어야 하므로 종류를 구분하는 tag byte를 쓴다(05-01의 constraint 저장과 같은 수법).
2. **로그 파일 관리** — append와 순차 재생(replay). 그리고 **언제 fsync하는가.** commit에서 sync하지 않으면 durability는 없다.
3. **복구 절차** — 로그를 처음부터 훑어 **커밋된 트랜잭션의 INSERT만** 다시 적용한다. 커밋 레코드가 없는 트랜잭션은 무시한다. 이 한 줄이 atomicity다.

## 4. 실패 테스트 (TDD step 2)

```kotlin
// src/test/kotlin/com/dbenginelab/wal/WalRecoveryTest.kt @ 5505edc
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

class WalRecoveryTest {
    private val schema = TableSchema(
        name = "users",
        columns = listOf(
            ColumnDef("id", Type.BIGINT, nullable = false),
            ColumnDef("name", Type.STRING, nullable = false),
        ),
    )

    @Test
    fun `commit 후 heap 반영`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("wal.log").toString()
        val data = tempDir.resolve("u.data").toString()
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val tx = TransactionManager(lm).begin()
                tx.insert("users", heap, Tuple(schema, listOf(1L, "A")))
                tx.commit()
                assertEquals(1, heap.rowCount())
            }}
        }
    }

    @Test
    fun `abort 시 heap 변경 없음`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("wal.log").toString()
        val data = tempDir.resolve("u.data").toString()
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val tx = TransactionManager(lm).begin()
                tx.insert("users", heap, Tuple(schema, listOf(1L, "A")))
                tx.abort()
                assertEquals(0, heap.rowCount())
            }}
        }
    }

    @Test
    fun `crash 후 recovery로 committed 복원`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("wal.log").toString()
        val data = tempDir.resolve("u.data").toString()
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val tx = TransactionManager(lm).begin()
                tx.insert("users", heap, Tuple(schema, listOf(100L, "X")))
                tx.commit()
            }}
        }
        java.io.File(data).delete()
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val stats = Recovery(lm) { name -> if (name == "users") heap else null }.recover()
                assertEquals(1, stats.txCommitted)
                assertEquals(1, stats.rowsReapplied)
                assertEquals(1, heap.rowCount())
            }}
        }
    }

    @Test
    fun `aborted, incomplete tx는 recovery 시 무시`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("wal.log").toString()
        val data = tempDir.resolve("u.data").toString()
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val tm = TransactionManager(lm)
                val tx1 = tm.begin(); tx1.insert("users", heap, Tuple(schema, listOf(1L, "kept"))); tx1.commit()
                val tx2 = tm.begin(); tx2.insert("users", heap, Tuple(schema, listOf(2L, "aborted"))); tx2.abort()
                val tx3 = tm.begin(); tx3.insert("users", heap, Tuple(schema, listOf(3L, "incomplete")))
            }}
        }
        java.io.File(data).delete()
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val stats = Recovery(lm) { name -> if (name == "users") heap else null }.recover()
                assertEquals(1, stats.txCommitted)
                assertEquals(1, stats.txAborted)
                assertEquals(1, stats.rowsReapplied)
                assertEquals(1, heap.rowCount())
            }}
        }
    }

    @Test
    fun `partial trailing record는 EOF로 안전 처리`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("wal.log").toString()
        LogManager(log).use { lm ->
            lm.append(LogRecord.BeginTx(1L)); lm.sync()
        }
        java.io.RandomAccessFile(log, "rw").use { raf ->
            raf.seek(raf.length())
            raf.writeInt(999); raf.write(byteArrayOf(0x01, 0x02))
        }
        LogManager(log).use { lm ->
            val records = mutableListOf<LogRecord>()
            lm.replay { records += it }
            assertEquals(1, records.size)
            assertEquals(LogRecord.BeginTx(1L), records[0])
        }
    }
}
```

**예상 실패**: **컴파일 실패** — `Unresolved reference: LogManager`, `LogRecord`, `Transaction`, `Recovery`.

## 5. 구현 코드 (TDD step 3 — make it pass)

> **정본 특이사항 (중요)**: 정본에는 **단계 8-1 시점의 스냅샷이 남아있지 않다** — 6-2 이후 21단계가 커밋 하나에 압축되어 있기 때문이다. 그래서 아래 `LogRecord.kt`·`LogManager.kt`·`Recovery.kt`는 **08-02(LSN·Checkpoint)까지 반영된 최종형**이다. `lsn`·`Checkpoint`가 눈에 띄면 그건 다음 세션의 것이니 지금은 그대로 치고 넘어가라 — 08-02에서 그 부분만 따로 설명한다.

### 5.1 `LogRecord.kt` — 로그의 문법

```kotlin
// src/main/kotlin/com/dbenginelab/wal/LogRecord.kt @ 5505edc
package com.dbenginelab.wal

sealed class LogRecord {
    abstract val txId: Long

    data class BeginTx(override val txId: Long) : LogRecord()
    data class InsertRow(
        override val txId: Long,
        val tableName: String,
        val tupleBytes: ByteArray,
    ) : LogRecord() {
        override fun equals(other: Any?): Boolean {
            if (other !is InsertRow) return false
            return txId == other.txId && tableName == other.tableName &&
                tupleBytes.contentEquals(other.tupleBytes)
        }
        override fun hashCode(): Int =
            (31 * (31 * txId.hashCode() + tableName.hashCode())) + tupleBytes.contentHashCode()
    }
    data class CommitTx(override val txId: Long) : LogRecord()
    data class AbortTx(override val txId: Long) : LogRecord()

    /**
     * Stage 8 보강 (X5): Checkpoint record.
     * Records the LSN snapshot + active transactions at checkpoint time.
     * Recovery / backup uses this as a consistent starting point.
     */
    data class Checkpoint(val checkpointLsn: Long, val activeTxs: List<Long>) : LogRecord() {
        override val txId: Long = 0L
    }

    companion object {
        const val TAG_BEGIN: Byte = 0
        const val TAG_INSERT: Byte = 1
        const val TAG_COMMIT: Byte = 2
        const val TAG_ABORT: Byte = 3
        const val TAG_CHECKPOINT: Byte = 4
    }
}
```

`InsertRow`가 `equals`/`hashCode`를 직접 구현한 것에 주목하라. `ByteArray` 필드가 있으면 `data class`의 자동 `equals`가 거짓말을 한다 — 01-01에서 `Record`를 일반 class로 둔 것과 같은 문제인데, 여기서는 data class의 편의가 필요해서 **손으로 고쳐 쓰는** 쪽을 택했다.

### 5.2 `LogManager.kt` — append와 replay

```kotlin
// src/main/kotlin/com/dbenginelab/wal/LogManager.kt @ 5505edc
package com.dbenginelab.wal

import java.io.Closeable
import java.io.EOFException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Stage 8 보강 (X2): LSN(Log Sequence Number) 발급.
 *
 * Each appended record gets a monotonic Long LSN (sequential count = LSN).
 * append() returns the assigned LSN. currentLsn() reflects the latest issued.
 *
 * Backward-compatible: legacy `replay(handler: (LogRecord) -> Unit)` 유지 + 신규
 * `replayWithLsn(handler: (Long, LogRecord) -> Unit)` 추가.
 *
 * Reopen 시 LSN은 파일 전체 record 수로 복원 (init).
 */
class LogManager(path: String) : Closeable {

    companion object {
        // wire format 크기 — LogManager가 소유한 직렬화 세부. allocate 합산식과 put 호출의 대응을 이름으로 고정한다.
        private const val TAG_BYTES = 1         // record tag (Byte)
        private const val TX_ID_BYTES = 8       // txId (Long)
        private const val LSN_BYTES = 8         // checkpointLsn (Long)
        private const val LEN_PREFIX_BYTES = 4  // 길이·개수 접두 (Int)
        private const val HEADER_BYTES = TAG_BYTES + TX_ID_BYTES  // 모든 레코드 공통 머리: [tag][txId]
    }

    private val file = RandomAccessFile(path, "rw")
    @Volatile private var nextLsn: Long = 1

    init {
        // Count existing records to restore nextLsn.
        file.seek(0)
        var count = 0L
        while (file.filePointer < file.length()) {
            try {
                val len = file.readInt()
                file.skipBytes(len)
                count++
            } catch (_: EOFException) { break }
        }
        nextLsn = count + 1
        file.seek(file.length())
    }

    /** Append a record and return its LSN. */
    fun append(record: LogRecord): Long {
        val payload = encode(record)
        file.seek(file.length())
        file.writeInt(payload.size)
        file.write(payload)
        val lsn = nextLsn
        nextLsn++
        return lsn
    }

    fun sync() { file.fd.sync() }

    fun currentLsn(): Long = nextLsn - 1

    /** Legacy replay — record only, LSN discarded. */
    fun replay(handler: (LogRecord) -> Unit) {
        replayWithLsn { _, rec -> handler(rec) }
    }

    /** LSN-aware replay — handler receives (lsn, record). */
    fun replayWithLsn(handler: (Long, LogRecord) -> Unit) {
        file.seek(0)
        var lsn = 0L
        while (file.filePointer < file.length()) {
            try {
                val len = file.readInt()
                val bytes = ByteArray(len)
                file.readFully(bytes)
                lsn++
                handler(lsn, decode(bytes))
            } catch (_: EOFException) {
                break
            }
        }
        file.seek(file.length())
    }

    override fun close() { file.close() }

    private fun encode(record: LogRecord): ByteArray {
        return when (record) {
            is LogRecord.BeginTx -> {
                val buf = ByteBuffer.allocate(HEADER_BYTES)
                buf.put(LogRecord.TAG_BEGIN); buf.putLong(record.txId)
                buf.array()
            }
            is LogRecord.CommitTx -> {
                val buf = ByteBuffer.allocate(HEADER_BYTES)
                buf.put(LogRecord.TAG_COMMIT); buf.putLong(record.txId)
                buf.array()
            }
            is LogRecord.AbortTx -> {
                val buf = ByteBuffer.allocate(HEADER_BYTES)
                buf.put(LogRecord.TAG_ABORT); buf.putLong(record.txId)
                buf.array()
            }
            is LogRecord.InsertRow -> {
                val name = record.tableName.toByteArray(StandardCharsets.UTF_8)
                val buf = ByteBuffer.allocate(
                    HEADER_BYTES + LEN_PREFIX_BYTES + name.size + LEN_PREFIX_BYTES + record.tupleBytes.size
                )
                buf.put(LogRecord.TAG_INSERT)
                buf.putLong(record.txId)
                buf.putInt(name.size); buf.put(name)
                buf.putInt(record.tupleBytes.size); buf.put(record.tupleBytes)
                buf.array()
            }
            is LogRecord.Checkpoint -> {
                val activeTxsBuf = ByteBuffer.allocate(LEN_PREFIX_BYTES + record.activeTxs.size * TX_ID_BYTES)
                activeTxsBuf.putInt(record.activeTxs.size)
                for (tx in record.activeTxs) activeTxsBuf.putLong(tx)
                val buf = ByteBuffer.allocate(HEADER_BYTES + LSN_BYTES + activeTxsBuf.position())
                buf.put(LogRecord.TAG_CHECKPOINT)
                buf.putLong(0L)  // txId placeholder
                buf.putLong(record.checkpointLsn)
                buf.put(activeTxsBuf.array(), 0, activeTxsBuf.position())
                buf.array()
            }
        }
    }

    private fun decode(bytes: ByteArray): LogRecord {
        val buf = ByteBuffer.wrap(bytes)
        val tag = buf.get()
        val txId = buf.long
        return when (tag) {
            LogRecord.TAG_BEGIN -> LogRecord.BeginTx(txId)
            LogRecord.TAG_COMMIT -> LogRecord.CommitTx(txId)
            LogRecord.TAG_ABORT -> LogRecord.AbortTx(txId)
            LogRecord.TAG_INSERT -> {
                val nameLen = buf.int
                val nameBytes = ByteArray(nameLen); buf.get(nameBytes)
                val tupleLen = buf.int
                val tupleBytes = ByteArray(tupleLen); buf.get(tupleBytes)
                LogRecord.InsertRow(txId, String(nameBytes, StandardCharsets.UTF_8), tupleBytes)
            }
            LogRecord.TAG_CHECKPOINT -> {
                val ckLsn = buf.long
                val count = buf.int
                val active = LongArray(count) { buf.long }.toList()
                LogRecord.Checkpoint(ckLsn, active)
            }
            else -> error("unknown log tag: $tag")
        }
    }
}
```

### 5.3 `Transaction.kt`

```kotlin
// src/main/kotlin/com/dbenginelab/wal/Transaction.kt @ 5505edc
package com.dbenginelab.wal

import com.dbenginelab.catalog.Tuple
import com.dbenginelab.table.TableHeap
import java.util.concurrent.atomic.AtomicLong

class Transaction internal constructor(
    val id: Long,
    private val logManager: LogManager,
) {
    private enum class State { ACTIVE, COMMITTED, ABORTED }
    private var state: State = State.ACTIVE
    private val pending: MutableList<Pair<TableHeap, Tuple>> = mutableListOf()

    init { logManager.append(LogRecord.BeginTx(id)) }

    fun insert(tableName: String, heap: TableHeap, tuple: Tuple) {
        check(state == State.ACTIVE) { "transaction $id not active (state=$state)" }
        require(tuple.schema == heap.schema) { "tuple schema does not match heap" }
        logManager.append(LogRecord.InsertRow(id, tableName, tuple.encode()))
        pending.add(heap to tuple)
    }

    fun commit() {
        check(state == State.ACTIVE)
        logManager.append(LogRecord.CommitTx(id))
        // Q: sync 호출 위치 — heap insert 후가 아니라?
        logManager.sync()
        // <details><summary>A</summary>
        // durability barrier — COMMIT record가 disk 도달 시점에 tx는 committed. heap apply 후 crash해도 recovery로 복원.
        // </details>
        for ((heap, tuple) in pending) heap.insert(tuple)
        pending.clear()
        state = State.COMMITTED
    }

    fun abort() {
        check(state == State.ACTIVE)
        logManager.append(LogRecord.AbortTx(id))
        pending.clear()
        state = State.ABORTED
    }

    fun isCommitted(): Boolean = state == State.COMMITTED
    fun isAborted(): Boolean = state == State.ABORTED
}

class TransactionManager(private val logManager: LogManager) {
    private val nextTxId = AtomicLong(1)
    fun begin(): Transaction = Transaction(nextTxId.getAndIncrement(), logManager)
}
```

### 5.4 `Recovery.kt` — 커밋된 것만 다시 적용

```kotlin
// src/main/kotlin/com/dbenginelab/wal/Recovery.kt @ 5505edc
package com.dbenginelab.wal

import com.dbenginelab.catalog.Tuple
import com.dbenginelab.table.TableHeap

class Recovery(
    private val logManager: LogManager,
    private val heapLookup: (String) -> TableHeap?,
) {
    data class Stats(val txObserved: Int, val txCommitted: Int, val txAborted: Int, val rowsReapplied: Int)

    fun recover(): Stats {
        val perTxInserts = mutableMapOf<Long, MutableList<Pair<String, ByteArray>>>()
        val committed = mutableSetOf<Long>()
        val aborted = mutableSetOf<Long>()

        logManager.replay { rec ->
            when (rec) {
                is LogRecord.BeginTx -> perTxInserts[rec.txId] = mutableListOf()
                is LogRecord.InsertRow ->
                    perTxInserts.getOrPut(rec.txId) { mutableListOf() }
                        .add(rec.tableName to rec.tupleBytes)
                is LogRecord.CommitTx -> committed.add(rec.txId)
                is LogRecord.AbortTx -> aborted.add(rec.txId)
                is LogRecord.Checkpoint -> { /* legacy Recovery ignores checkpoints */ }
            }
        }

        var rowsReapplied = 0
        for ((txId, inserts) in perTxInserts) {
            // Q: committed인데 aborted도 가능?
            if (txId !in committed || txId in aborted) continue
            // <details><summary>A</summary>
            // 정상은 mutually exclusive. corruption/bug 방어로 둘 다 체크.
            // </details>
            for ((tableName, tupleBytes) in inserts) {
                val heap = heapLookup(tableName) ?: continue
                val tuple = Tuple.decode(heap.schema, tupleBytes)
                heap.insert(tuple)
                rowsReapplied++
            }
        }

        return Stats(perTxInserts.size, committed.size, aborted.size, rowsReapplied)
    }
}
```

## 6. 검증 테스트 (TDD step 4 — green)

테스트 파일은 §4에서 저장한 것이 그대로 최종본이다.

```bash
./gradlew test --tests 'com.dbenginelab.wal.WalRecoveryTest'
```

**기대 결과**: `WalRecoveryTest` **5 PASSED**

invariant 대응은 테스트 이름을 보고 **직접 채워라** — 5개 중 어느 것이 CI-1(atomicity), CI-2(durability), CI-3(WAL rule)에 대응하는지, 그리고 **어느 invariant가 테스트로 덮이지 않는지**를 판단하는 것이 이번 세션의 과제 중 하나다. (힌트: CI-3은 fsync 순서에 관한 것이라 프로세스 안에서 검증하기 어렵다.)

## 7. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

**1.** `commit`에서 fsync(`logManager.sync()`)를 지워라. 테스트는 통과하는가?

<details><summary>답</summary>

**실측: wal 테스트 14개가 전부 통과한다.**

01-01 과제 1번과 같은 이유다. 테스트는 프로세스 안에서 파일을 닫았다 다시 열 뿐이고, **커널은 죽지 않는다.** 페이지 캐시에 있던 로그가 그대로 읽히니 아무 차이가 없다.

사라지는 경우는 **커널까지 죽을 때**뿐이다 — 전원 차단, 커널 패닉, VM 강제 종료, 컨테이너 호스트 다운.

여기서 정확히 짚을 것: fsync가 없으면 **"커밋했다"는 응답이 거짓말이 된다.** 사용자에게 성공을 알렸는데 전원이 나가면 그 트랜잭션이 사라진다. D(durability)의 정의가 "응답한 것은 살아남는다"이므로, **fsync 없는 commit은 durability가 없는 것**이다.

그리고 이 결함은 **테스트로 잡을 수 없다.** 프로세스 안에서 커널 크래시를 흉내낼 방법이 없기 때문이다. 실제 DB들은 이걸 검증하려고 전원을 실제로 끊는 하드웨어 테스트를 한다. **"테스트가 통과한다 = 안전하다"가 성립하지 않는 영역**이 있다는 것을 여기서 확인해둬라.
</details>

**2.** recovery에서 "커밋 레코드가 있는 트랜잭션만" 조건(`if (txId !in committed || txId in aborted) continue`)을 지워라. 어느 테스트가 잡나?

<details><summary>답</summary>

**실측: 3개 실패.**

- `aborted, incomplete tx는 recovery 시 무시`
- `crash 직전 commit 미실행 — tx 데이터 미반영` (08-03)
- `randomized tx 시퀀스 - commit abort 섞어도 일관` (08-03)

heap에 남는 것은 **abort한 트랜잭션의 데이터와, 커밋도 abort도 못 하고 죽은 트랜잭션의 데이터**다. 세 번째 테스트를 보면 30개 중 무작위로 abort한 것들이 전부 되살아난다.

이 한 줄이 A(atomicity)의 전부다. WAL은 **모든** 변경을 기록하므로 로그에는 실패한 트랜잭션의 흔적도 남아 있다. 복구가 그것을 걸러내지 않으면 **"취소했다고 응답한 것이 되살아난다."**

08-03의 무작위 테스트가 왜 가치 있는지도 여기서 보인다 — 손으로 만든 시나리오 하나보다, 30개를 무작위로 섞고 **커밋 집합과 정확히 일치하는지** 대조하는 쪽이 훨씬 촘촘하다.
</details>

**3.** 로그를 적기 전에 heap에 먼저 쓰도록 순서를 뒤집어라(WAL rule 위반). 어느 시점에 죽어야 문제가 드러나나?

<details><summary>답</summary>

우리 구조에서는 이 변형이 **즉시 잡힌다.** `insert`가 heap을 바로 건드리게 되므로 `abort 시 heap 변경 없음` 테스트가 실패한다 — abort해도 데이터가 남기 때문이다.

이건 deferred-apply 설계 덕분이다(§2). heap을 commit 시점에만 만지므로 "로그보다 먼저 쓴다"는 상황 자체가 만들어지지 않는다.

**진짜 WAL rule이 문제되는 건 in-place update를 하는 설계에서다.** page를 직접 고치는 DB라면:

```
1. page를 메모리에서 고침
2. 그 page가 evict되어 디스크에 쓰임        ← 로그는 아직 안 씀
3. 여기서 죽음
4. 복구: 로그에 없는 변경이 디스크에 있다 → undo할 근거가 없다
```

그래서 규칙이 **"page를 디스크에 쓰기 전에 그 page의 로그를 먼저 디스크에 확정하라"**(Write-Ahead)가 된다. 우리는 애초에 page를 미리 안 쓰므로 이 규칙을 우회한 셈이고, 대가는 **undo를 못 한다는 것**(§2의 redo-only)이다.
</details>

**4.** 로그 파일 끝을 잘라내라(partial write 흉내). replay는 무엇을 하는가 — 예외인가, 조용한 무시인가? 어느 쪽이 옳은가?

<details><summary>답</summary>

**조용히 멈춘다.** `partial trailing record는 EOF로 안전 처리` 테스트가 그 동작을 못 박고 있다 — 잘린 꼬리를 버리고 **앞의 정상 레코드는 살린다.**

01-01의 `scanAll`과 정반대다:

| | 잘린 꼬리를 만나면 |
|---|---|
| `AppendOnlyFile.scanAll` | `UnexpectedEof`를 던져 **앞의 정상 record까지 전부 잃는다** |
| `LogManager.replay` | 거기서 멈추고 **앞은 살린다** |

**로그에서는 후자가 옳다.** 마지막 레코드가 잘렸다는 건 "쓰는 도중에 죽었다"는 뜻이고, 그 트랜잭션은 애초에 커밋되지 않았다. 버려도 아무것도 잃지 않는다. 반면 앞의 레코드까지 버리면 **커밋 완료된 트랜잭션을 잃는다** — 그게 진짜 데이터 손실이다.

다만 이 처리에는 사각이 있다. **"잘림"과 "중간 손상"을 구분하지 못한다.** 파일 중간이 깨져 길이 필드가 이상해져도 replay는 그냥 EOF로 보고 멈추고, **그 뒤의 멀쩡한 레코드를 전부 버린다.** 08-03 과제 3번이 이걸 다룬다.
</details>

## 8. 다음 한계

- recovery를 **두 번 돌리면** 같은 INSERT가 두 번 적용된다. 멱등(idempotent)이 아니다.
- 로그가 무한히 자란다. 재시작할 때마다 **처음부터 전부** 재생한다.
- 로그 레코드에 순번(LSN)이 없어서 "어디까지 적용했는지"를 기록할 수 없다.

→ **08-02 LSN + Checkpoint + IdempotentRecovery**가 이 셋을 한꺼번에 다룬다.
