# impl/08-01 — WAL + Transaction + Recovery (한 줄 한 줄)

> **검증**: WalRecoveryTest 5 PASSED.
> 작성 파일:
> - 신규 패키지: `src/main/kotlin/com/dbenginelab/wal/`
> - 신규: LogRecord.kt, LogManager.kt, Transaction.kt, Recovery.kt
> - 신규 테스트: WalRecoveryTest.kt

## 0. 참조
- SimpleDB `LogFile` (lab4 ARIES 단순화).
- BusTub `log_manager`, `recovery_manager` (project 4).

## 1. invariant
- Atomicity: commit/abort all-or-nothing.
- Durability: commit 직후 crash → recovery 복원.
- WAL rule: 변경 전 log 먼저 disk.

## 2. 단순화 — Deferred-apply + redo-only
- Transaction.insert는 WAL append만, commit 시 sync + heap apply.
- Abort는 heap 미변경 → undo 불필요 (in-place mutation 아님).
- Recovery는 redo-only + heap reconstruct (학습용 inefficient).

## 3. LogRecord.kt — 한 줄 한 줄

```kotlin
package com.dbenginelab.wal                                          // 신규 wal 패키지

sealed class LogRecord {                                             // Q: sealed인 이유?
    // <details><summary>A</summary>
    // WAL 프로토콜은 진짜 닫힌 집합 (BEGIN/INSERT/COMMIT/ABORT). 확장은 명시 (Checkpoint는 보강).
    // </details>
    abstract val txId: Long

    data class BeginTx(override val txId: Long) : LogRecord()
    data class InsertRow(
        override val txId: Long,
        val tableName: String,
        val tupleBytes: ByteArray,                                   // Q: ByteArray라 equals 어떻게?
    ) : LogRecord() {
        override fun equals(other: Any?): Boolean {
            if (other !is InsertRow) return false
            return txId == other.txId && tableName == other.tableName &&
                tupleBytes.contentEquals(other.tupleBytes)            // content 비교 (reference 아님)
        }
        override fun hashCode(): Int =
            (31 * (31 * txId.hashCode() + tableName.hashCode())) + tupleBytes.contentHashCode()
        // <details><summary>A</summary>
        // ByteArray default equals = reference. data class 자동 equals가 거짓말. content 비교 명시.
        // </details>
    }
    data class CommitTx(override val txId: Long) : LogRecord()
    data class AbortTx(override val txId: Long) : LogRecord()

    companion object {
        const val TAG_BEGIN: Byte = 0
        const val TAG_INSERT: Byte = 1
        const val TAG_COMMIT: Byte = 2
        const val TAG_ABORT: Byte = 3
    }
}
```

## 4. LogManager.kt — 한 줄 한 줄

```kotlin
package com.dbenginelab.wal
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class LogManager(path: String) : Closeable {
    private val file = RandomAccessFile(path, "rw")
    init { file.seek(file.length()) }                                // append 위치로

    fun append(record: LogRecord) {                                  // (보강 후 LSN 반환)
        val payload = encode(record)
        file.seek(file.length())
        file.writeInt(payload.size)                                  // length-prefix
        file.write(payload)
    }

    fun sync() { file.fd.sync() }                                    // durability barrier

    fun replay(handler: (LogRecord) -> Unit) {                       // 모든 record 순차 처리
        file.seek(0)
        while (file.filePointer < file.length()) {
            try {
                val len = file.readInt()
                val bytes = ByteArray(len); file.readFully(bytes)
                handler(decode(bytes))
            } catch (_: EOFException) {                              // Q: partial trailing 무시?
                break
            }
            // <details><summary>A</summary>
            // crash 시 마지막 record 잘릴 수 있음 — 그 tx는 commit 안 됨, 무시해도 됨. throw하면 recovery 불가.
            // </details>
        }
    }

    override fun close() { file.close() }

    private fun encode(record: LogRecord): ByteArray = when (record) {
        is LogRecord.BeginTx -> ByteBuffer.allocate(1 + 8)
            .put(LogRecord.TAG_BEGIN).putLong(record.txId).array()
        is LogRecord.CommitTx -> ByteBuffer.allocate(1 + 8)
            .put(LogRecord.TAG_COMMIT).putLong(record.txId).array()
        is LogRecord.AbortTx -> ByteBuffer.allocate(1 + 8)
            .put(LogRecord.TAG_ABORT).putLong(record.txId).array()
        is LogRecord.InsertRow -> {
            val name = record.tableName.toByteArray(StandardCharsets.UTF_8)
            val buf = ByteBuffer.allocate(1 + 8 + 4 + name.size + 4 + record.tupleBytes.size)
            buf.put(LogRecord.TAG_INSERT)
            buf.putLong(record.txId)
            buf.putInt(name.size); buf.put(name)
            buf.putInt(record.tupleBytes.size); buf.put(record.tupleBytes)
            buf.array()
        }
    }

    private fun decode(bytes: ByteArray): LogRecord {
        val buf = ByteBuffer.wrap(bytes)
        val tag = buf.get(); val txId = buf.long
        return when (tag) {
            LogRecord.TAG_BEGIN -> LogRecord.BeginTx(txId)
            LogRecord.TAG_COMMIT -> LogRecord.CommitTx(txId)
            LogRecord.TAG_ABORT -> LogRecord.AbortTx(txId)
            LogRecord.TAG_INSERT -> {
                val nameLen = buf.int; val nameBytes = ByteArray(nameLen); buf.get(nameBytes)
                val tupleLen = buf.int; val tupleBytes = ByteArray(tupleLen); buf.get(tupleBytes)
                LogRecord.InsertRow(txId, String(nameBytes, StandardCharsets.UTF_8), tupleBytes)
            }
            else -> error("unknown log tag: $tag")
        }
    }
}
```

## 5. Transaction.kt — 한 줄 한 줄

```kotlin
package com.dbenginelab.wal
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.table.TableHeap
import java.util.concurrent.atomic.AtomicLong

class Transaction internal constructor(val id: Long, private val logManager: LogManager) {
    private enum class State { ACTIVE, COMMITTED, ABORTED }
    private var state: State = State.ACTIVE
    private val pending: MutableList<Pair<TableHeap, Tuple>> = mutableListOf()

    init { logManager.append(LogRecord.BeginTx(id)) }                // BEGIN 즉시 기록

    fun insert(tableName: String, heap: TableHeap, tuple: Tuple) {
        check(state == State.ACTIVE)
        require(tuple.schema == heap.schema)
        // Q: WAL append를 heap insert보다 먼저 하는 이유?
        logManager.append(LogRecord.InsertRow(id, tableName, tuple.encode()))
        pending.add(heap to tuple)                                   // heap 즉시 안 함 — commit 시
        // <details><summary>A</summary>
        // WAL rule — 변경이 disk 도달 전에 log가 disk. durability 보장. deferred-apply라 commit 시 heap.
        // </details>
    }

    fun commit() {
        check(state == State.ACTIVE)
        logManager.append(LogRecord.CommitTx(id))
        // Q: sync 호출 위치 — heap insert 후가 아니라?
        logManager.sync()
        // <details><summary>A</summary>
        // durability barrier — COMMIT record가 disk 도달 시점에 tx는 committed. heap apply 후 crash해도 recovery로 복원.
        // </details>
        for ((heap, tuple) in pending) heap.insert(tuple)            // 실 apply
        pending.clear()
        state = State.COMMITTED
    }

    fun abort() {
        check(state == State.ACTIVE)
        logManager.append(LogRecord.AbortTx(id))
        pending.clear()                                              // heap 미변경 → 그냥 버림
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

## 6. Recovery.kt — 한 줄 한 줄

```kotlin
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

        logManager.replay { rec ->                                   // 전체 WAL replay
            when (rec) {
                is LogRecord.BeginTx -> perTxInserts[rec.txId] = mutableListOf()
                is LogRecord.InsertRow ->
                    perTxInserts.getOrPut(rec.txId) { mutableListOf() }.add(rec.tableName to rec.tupleBytes)
                is LogRecord.CommitTx -> committed.add(rec.txId)
                is LogRecord.AbortTx -> aborted.add(rec.txId)
                is LogRecord.Checkpoint -> { /* X5 보강에서 사용 */ }
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
                heap.insert(Tuple.decode(heap.schema, tupleBytes))
                rowsReapplied++
            }
        }
        return Stats(perTxInserts.size, committed.size, aborted.size, rowsReapplied)
    }
}
```

## 7. 검증 (5 PASSED)
- commit 후 heap 반영
- abort 시 heap 변경 없음
- crash 후 recovery 복원
- aborted/incomplete tx 무시
- partial trailing record EOF 안전

## 8. 깨뜨릴 과제
- commit 직전 (sync 전) kill → 데이터 사라짐 (commit 아님).
- sync 제거 → OS crash에서 손실.
- 같은 heap 두 번 recovery → 중복 (LSN 기반 idempotent 보강 필요 → 08-02).
