# impl/09-02 — Transaction + LockManager 통합 (C3 보강)

> **검증**: TransactionWithLockTest 3 PASSED.

## 0. 보강 동기
단계 8 WAL Transaction과 단계 9 LockManager가 별개 — 사용자가 두 가지를 직접 묶어야 함. C3 보강은 Strict 2PL 자동화.

## 1. invariant
- insert는 EXCLUSIVE lock 자동 acquire.
- commit/abort 시 releaseAll(txId) 자동.
- 두 tx 같은 table EXCLUSIVE → 두 번째 throw.

## 2. 핵심 결정
- Lock granularity: **table 단위** (resource = tableName). row 단위는 후속.
- Strict 2PL: lock은 commit/abort까지 유지.
- read 시 SHARED, insert 시 EXCLUSIVE (자동 upgrade는 LockManager가).
- 별도 클래스 `TransactionWithLock` — 기존 `Transaction` 호환성 유지 (덮어쓰기 X).

## 3. 코드 (wal/TransactionWithLock.kt)

```kotlin
class TransactionWithLock internal constructor(
    val id: Long,
    private val logManager: LogManager,
    private val lockManager: LockManager,
) {
    private val acquiredResources: MutableSet<String> = mutableSetOf()

    fun insert(tableName: String, heap: TableHeap, tuple: Tuple) {
        check(state == State.ACTIVE)
        // Q: 왜 매 insert마다 acquire? 처음 한 번 후 idempotent 아닌가?
        acquireIfNeeded(tableName, LockManager.Mode.EXCLUSIVE)
        logManager.append(LogRecord.InsertRow(id, tableName, tuple.encode()))
        pending.add(heap to tuple)
        // <details><summary>A</summary>
        //
        // LockManager.acquire는 같은 tx 같은 mode 재호출에 idempotent (no-op return). 안전 + 코드 명료.
        // </details>
    }

    fun commit() {
        check(state == State.ACTIVE)
        logManager.append(LogRecord.CommitTx(id))
        logManager.sync()
        for ((heap, tuple) in pending) heap.insert(tuple)
        pending.clear()
        // Q: lock release를 heap apply 후가 아니라 같이? Strict 2PL이면 어디?
        lockManager.releaseAll(id)
        acquiredResources.clear()
        state = State.COMMITTED
        // <details><summary>A</summary>
        //
        // Strict 2PL — commit 결정 후 lock 유지 불필요. heap apply 후 release 안전. abort도 동일.
        // </details>
    }
}
```

## 4. 깨뜨릴 과제
- Row 단위 lock으로 확장? (resource = "table:rowId")
- Lock timeout (현재는 즉시 throw)?
- 두 tx 같은 row update → 어떤 isolation?

## 5. 다음 한계
- MVCC와 통합 안 됨. isolation level 명시 안 됨. → **stage 10 보강 (X4)**.
