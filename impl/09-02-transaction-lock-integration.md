# impl/09-02 — Transaction + LockManager 통합 (보강 C3)

> **종류**: 보강형 (단계 8 `Transaction`과 단계 9 `LockManager`를 하나로 묶는다)
> **상위 단계**: `docs/stages/09-locks.md`
> **코드 정본**: git `5505edc` — "complete: 21 stages + 12 보강 (120/120 tests)"
> **이 세션의 범위**: 락 획득·해제를 트랜잭션이 **자동으로** 하게 만든다 — Strict 2PL의 자동화.
> **작성 파일**:
> - 신규: `src/main/kotlin/com/dbenginelab/wal/TransactionWithLock.kt`
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/wal/TransactionWithLockTest.kt`
> **검증**: `TransactionWithLockTest` 3 PASSED
> **예상 타이핑 시간**: 35분

---

## 0. 보강 동기

단계 8의 WAL `Transaction`과 단계 9의 `LockManager`가 **서로를 모른다.** 사용자가 매번 손으로 `acquire` → `insert` → `releaseAll` 순서를 지켜야 하고, 한 번이라도 빠뜨리면 조용히 보호가 사라진다. 규칙을 사람이 지키게 하는 대신 **코드가 지키게** 만드는 것이 이번 세션이다.

## 1. 만족시킬 invariant

- **CI-1**: `insert`가 EXCLUSIVE 락을 자동으로 획득한다.
- **CI-2**: `commit`/`abort`가 `releaseAll(txId)`을 자동으로 호출한다.
- **CI-3**: 두 트랜잭션이 같은 테이블에 EXCLUSIVE를 요구하면 두 번째가 실패한다.

## 2. 핵심 결정

- **락 단위 = 테이블** (자원 이름 = 테이블 이름). 행 단위는 후속 과제로 남긴다.
- **Strict 2PL** — 락을 중간에 풀지 않고 commit/abort까지 들고 있는다. 중간에 풀면 다른 트랜잭션이 그 틈을 파고들어 일관성이 깨진다.
- read는 SHARED, insert는 EXCLUSIVE.
- **기존 `Transaction`을 덮어쓰지 않고 별도 클래스로 둔다.** 단계 8의 코드와 테스트가 그대로 살아있어야 무엇이 추가됐는지 대조할 수 있기 때문이다.

## 3. 문제 정의

09-01의 `LockManager`는 완성되어 있지만 **호출하는 사람이 없다.** 붙일 지점은 명확하다:

- `insert(table, ...)` → 그 테이블에 X 락을 먼저 잡는다.
- `commit()` / `abort()` → 마지막에 `releaseAll(txId)`.

주의할 것은 **해제 시점**이다. insert가 끝날 때마다 풀면 안 된다 — 그건 2PL이 아니다. commit까지 들고 있어야 하고, 그래서 이름이 **Strict** 2PL이다.

그리고 `abort`에서도 반드시 풀어야 한다. abort 경로에서 해제를 빼먹으면 그 락은 **영원히 남는다.** 이런 누락은 정상 경로 테스트로는 절대 잡히지 않는다.

## 4. 구현 코드

```kotlin
// src/main/kotlin/com/dbenginelab/wal/TransactionWithLock.kt @ 5505edc
package com.dbenginelab.wal

import com.dbenginelab.catalog.Tuple
import com.dbenginelab.lock.LockManager
import com.dbenginelab.table.TableHeap

/**
 * Stage 9 보강 (C3): Transaction + LockManager 통합.
 *
 * WAL Transaction이 lock acquire/releaseAll을 자동 처리. 사용자가 직접 lock
 * 호출 안 해도 됨. Strict 2PL — commit/abort 시 releaseAll.
 *
 * Lock granularity: table 단위 (resource = tableName). Row 단위는 stage 9+ 후속.
 */
class TransactionWithLock internal constructor(
    val id: Long,
    private val logManager: LogManager,
    private val lockManager: LockManager,
) {
    private enum class State { ACTIVE, COMMITTED, ABORTED }
    private var state: State = State.ACTIVE
    private val pending: MutableList<Pair<TableHeap, Tuple>> = mutableListOf()
    private val acquiredResources: MutableSet<String> = mutableSetOf()

    init { logManager.append(LogRecord.BeginTx(id)) }

    fun read(tableName: String): TableHeap.() -> Sequence<Tuple> {
        check(state == State.ACTIVE)
        acquireIfNeeded(tableName, LockManager.Mode.SHARED)
        return { scan() }
    }

    fun insert(tableName: String, heap: TableHeap, tuple: Tuple) {
        check(state == State.ACTIVE)
        require(tuple.schema == heap.schema)
        // Q: 왜 매 insert마다 acquire? 처음 한 번 후 idempotent 아닌가?
        acquireIfNeeded(tableName, LockManager.Mode.EXCLUSIVE)
        // <details><summary>A</summary>
        //
        // LockManager.acquire는 같은 tx 같은 mode 재호출에 idempotent (no-op return). 안전 + 코드 명료.
        // </details>
        logManager.append(LogRecord.InsertRow(id, tableName, tuple.encode()))
        pending.add(heap to tuple)
    }

    fun commit() {
        check(state == State.ACTIVE)
        logManager.append(LogRecord.CommitTx(id))
        logManager.sync()
        for ((heap, tuple) in pending) heap.insert(tuple)
        pending.clear()
        lockManager.releaseAll(id)
        acquiredResources.clear()
        state = State.COMMITTED
    }

    fun abort() {
        check(state == State.ACTIVE)
        logManager.append(LogRecord.AbortTx(id))
        pending.clear()
        lockManager.releaseAll(id)
        acquiredResources.clear()
        state = State.ABORTED
    }

    fun isCommitted(): Boolean = state == State.COMMITTED
    fun isAborted(): Boolean = state == State.ABORTED

    private fun acquireIfNeeded(resource: String, mode: LockManager.Mode) {
        // Strict 2PL: lock은 한 번 잡으면 commit/abort까지 유지.
        // EXCLUSIVE 요청은 이미 SHARED 있어도 acquire (upgrade) — LockManager가 처리.
        lockManager.acquire(id, resource, mode)
        acquiredResources.add(resource)
    }
}

class TransactionWithLockManager(
    private val logManager: LogManager,
    private val lockManager: LockManager,
) {
    private val nextTxId = java.util.concurrent.atomic.AtomicLong(1)
    fun begin(): TransactionWithLock = TransactionWithLock(nextTxId.getAndIncrement(), logManager, lockManager)
}
```

## 5. 검증 테스트 (green)

```kotlin
// src/test/kotlin/com/dbenginelab/wal/TransactionWithLockTest.kt @ 5505edc
package com.dbenginelab.wal

import com.dbenginelab.catalog.ColumnDef
import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.catalog.Type
import com.dbenginelab.lock.LockConflict
import com.dbenginelab.lock.LockManager
import com.dbenginelab.storage.BufferPool
import com.dbenginelab.storage.PagedFile
import com.dbenginelab.table.TableHeap
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransactionWithLockTest {
    private val schema = TableSchema(
        name = "users",
        columns = listOf(
            ColumnDef("id", Type.BIGINT, nullable = false),
            ColumnDef("name", Type.STRING, nullable = false),
        ),
    )

    @Test
    fun `commit 시 lock 자동 release`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("w.log").toString()
        val data = tempDir.resolve("u.data").toString()
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val lockMgr = LockManager()
                val tm = TransactionWithLockManager(lm, lockMgr)
                val tx = tm.begin()
                tx.insert("users", heap, Tuple(schema, listOf(1L, "A")))
                assertTrue(lockMgr.isHeld(tx.id, "users"))
                tx.commit()
                assertEquals(false, lockMgr.isHeld(tx.id, "users"))
            }}
        }
    }

    @Test
    fun `abort 시 lock 자동 release`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("w.log").toString()
        val data = tempDir.resolve("u.data").toString()
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val lockMgr = LockManager()
                val tm = TransactionWithLockManager(lm, lockMgr)
                val tx = tm.begin()
                tx.insert("users", heap, Tuple(schema, listOf(1L, "A")))
                tx.abort()
                assertEquals(false, lockMgr.isHeld(tx.id, "users"))
            }}
        }
    }

    @Test
    fun `두 tx 같은 table EXCLUSIVE 충돌`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("w.log").toString()
        val data = tempDir.resolve("u.data").toString()
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val lockMgr = LockManager()
                val tm = TransactionWithLockManager(lm, lockMgr)
                val tx1 = tm.begin()
                tx1.insert("users", heap, Tuple(schema, listOf(1L, "A")))
                val tx2 = tm.begin()
                assertThrows<LockConflict> {
                    tx2.insert("users", heap, Tuple(schema, listOf(2L, "B")))
                }
                tx1.commit()
                // tx2는 abort 가능, lock 없으니 commit도 가능.
                tx2.insert("users", heap, Tuple(schema, listOf(2L, "B")))
                tx2.commit()
                assertEquals(2, heap.rowCount())
            }}
        }
    }
}
```

```bash
./gradlew test --tests 'com.dbenginelab.wal.TransactionWithLockTest'
```

**기대 결과**: `TransactionWithLockTest` **3 PASSED**

invariant 대응:
- **CI-1**, **CI-3** ← 두 tx가 같은 테이블에 insert를 시도하는 테스트
- **CI-2** ← commit/abort 후 다른 tx가 같은 테이블을 잡을 수 있는지 확인하는 테스트

## 6. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

**1.** `abort()`에서 `releaseAll`을 지워라. 세 테스트 중 몇 개가 깨지는가?

<details><summary>답</summary>

**실측: 1개 깨진다** — `abort 시 lock 자동 release`.

이 테스트가 있어서 다행인 경우다. 정상 경로(commit)만 검증했다면 이 결함은 **운영에서야 드러났을 것**이다:

```
tx가 예외로 abort된다 → 락이 남는다 → 그 테이블은 영원히 잠긴다
→ 이후 모든 트랜잭션이 LockConflict
→ 재시작 전까지 복구 불가 (LockManager는 메모리에만 있으므로)
```

**abort 경로의 자원 해제**는 테스트하기 귀찮아서 자주 빠지는 자리다. 그리고 정상 동작에서는 절대 안 드러난다 — 예외가 나야 타는 경로이기 때문이다.

같은 유형의 결함을 이 프로젝트에서 이미 여러 번 봤다: 02-02의 `unpinPage` 누락, 03-01의 `try-finally`. **"실패 경로에서도 자원을 돌려준다"** 가 반복되는 주제이고, Kotlin의 `use {}`와 `try-finally`가 그걸 강제하는 장치다.
</details>

**2.** 락을 `insert` 끝에서 바로 풀도록 바꿔라(2PL 위반). 두 트랜잭션이 번갈아 실행되는 구체적 순서를 적어라.

<details><summary>답</summary>

단일 스레드 테스트는 전부 통과한다 — 번갈아 실행되는 상황을 만들지 않기 때문이다.

깨지는 순서:

```
tx1: insert("users", Alice)   → X 락 잡고 → 로그 append → 락 해제(위반)
                                  pending = [Alice], heap은 아직 안 바뀜
tx2: insert("users", Bob)     → X 락 잡힘(tx1이 놨으므로) → 성공
tx2: commit()                 → heap에 Bob 들어감
tx1: commit()                 → heap에 Alice 들어감
```

여기까지는 둘 다 들어가니 괜찮아 보인다. 문제는 **읽기가 섞일 때**다:

```
tx1: insert("users", Alice)   → 락 잡았다 놓음
tx2: read("users")            → S 락 잡힘 → 스캔 → Alice가 없다
tx1: commit()                 → Alice 들어감
tx2: read("users")            → 또 스캔 → Alice가 있다
     ⇒ tx2가 같은 것을 두 번 읽었는데 결과가 다르다 (non-repeatable read)
```

2PL이 요구하는 것은 **"락을 놓기 시작하면 더는 잡지 않는다"**(growing phase → shrinking phase)이고, **Strict** 2PL은 한 발 더 나아가 **"커밋까지 하나도 놓지 않는다"**이다. 후자여야 위 시나리오가 막힌다 — tx1이 커밋할 때까지 tx2가 아예 못 읽기 때문이다.

즉 이 변형은 **격리 수준을 낮춘다.** 성능은 올라가고 이상현상이 늘어난다. 실제 DB의 `READ COMMITTED`가 대략 이 지점이다 — 읽기 락을 짧게 잡는다.
</details>

**3.** 락 단위를 테이블에서 행으로 바꾸려면 자원 이름을 어떻게 정해야 하나? "테이블 전체 스캔"은 그때 어떤 락을 잡아야 하나?

<details><summary>답</summary>

자원 이름은 **테이블과 행을 함께 식별**해야 한다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
"users"              // 지금 (테이블)
"users:42"           // 행 — PK 값으로
"users:page3:slot7"  // 또는 물리 주소(rowId)로
```

PK 방식은 사람이 읽기 쉽지만 **아직 없는 행을 잠글 수 없다.** rowId 방식은 물리 구조에 묶여 행이 이동하면 락이 어긋난다.

"테이블 전체 스캔"이 진짜 문제다. 세 가지 선택 모두 만족스럽지 않다:

1. **모든 행에 S 락** — 1억 행이면 1억 개. 비현실적이고, **스캔 중에 새로 들어오는 행은 잠글 수 없다**(팬텀).
2. **테이블 락 하나** — 그러면 행 락을 도입한 의미가 없어진다. 그리고 행 락을 든 다른 트랜잭션과 어떻게 충돌 판정을 하나? 행 락 전부를 뒤져야 한다.
3. **intention lock 도입** — 행 락을 잡기 전에 상위(테이블)에 IS/IX를 걸어둔다. 그러면 테이블 락을 원하는 쪽이 **상위만 보고** 충돌을 판정할 수 있다.

3번이 정답이고, 09-01 과제 4번에서 말한 multi-granularity locking이다. **행 락은 혼자 오지 않는다** — 계층 락 체계를 통째로 데려온다.
</details>

**4.** 두 트랜잭션이 서로 다른 테이블을 **엇갈린 순서로** 잡게 해봐라 (tx1: A→B, tx2: B→A). 지금 모델에서는? 대기 모델이었다면?

<details><summary>답</summary>

**지금 모델(즉시 실패)**: 한쪽이 `LockConflict`로 즉시 실패한다.

```
tx1: acquire(A) 성공
tx2: acquire(B) 성공
tx1: acquire(B) → LockConflict 예외 → tx1 실패
tx2: acquire(A) 성공 → tx2는 진행
```

tx1은 죽었지만 **시스템은 멈추지 않았다.** 애플리케이션이 재시도하면 된다.

**대기 모델이었다면**: 09-01 과제 3번의 교착이 정확히 이 순서로 일어난다. 둘 다 영원히 대기하고, 외부에서 탐지해 하나를 죽이기 전까지 아무 일도 진행되지 않는다.

여기서 볼 것은 **"즉시 실패"가 열등한 선택이 아니라는 점**이다. 교착 탐지 없이 교착을 원천 차단하는 대신, 실패를 애플리케이션에게 넘긴다. 실제로 이 전략을 쓰는 시스템이 있다 — 낙관적 동시성 제어(OCC)와 그 계열이 "충돌하면 실패시키고 재시도하게 한다"는 같은 철학이다.

교착을 **예방**하는 고전적 방법도 하나 알아둬라 — **모든 트랜잭션이 자원을 같은 순서로 잡게 강제**하는 것(예: 이름 알파벳순). tx2도 A→B 순으로 잡으면 순환이 생기지 않는다. 단순하지만 애플리케이션이 협조해야 하고, 어떤 자원을 잡을지 미리 알아야 한다는 전제가 붙는다.
</details>

## 7. 다음 한계

락은 정확하지만 **비싸다.** 읽기만 하는 트랜잭션도 쓰기를 막고, 쓰기는 읽기를 막는다. 보고서 조회 하나가 전체 쓰기를 멈춰 세운다.

→ **단계 10 MVCC**. 읽는 쪽이 **과거 버전**을 보게 해서 읽기와 쓰기가 서로를 막지 않게 만든다.
