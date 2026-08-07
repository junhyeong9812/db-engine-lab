# impl/07-01 — WorkUnit (논리적 작업 단위, ACID 아님)

> **종류**: 세션형
> **상위 단계**: `docs/stages/07-batch.md`
> **코드 정본**: git `5505edc` — "complete: 21 stages + 12 보강 (120/120 tests)"
> **이 세션의 범위**: 여러 insert를 한 덩어리로 묶어 commit/abort하는 껍데기를 만든다. **진짜 ACID Transaction이 아니다.**
> **작성 파일**:
> - 신규: `src/main/kotlin/com/dbenginelab/table/WorkUnit.kt`
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/table/WorkUnitTest.kt`
> **검증**: `WorkUnitTest` 4 PASSED
> **예상 타이핑 시간**: 20분

---

## 0. 참조

SimpleDB `Transaction` (lab3의 단순 in-memory 버전). BusTub `transaction_manager`.

## 1. 만족시킬 invariant

- **CI-1**: commit 후 buffered insert가 heap에 반영된다.
- **CI-2**: abort 시 buffer만 비우고 heap은 불변이다.
- **CI-3**: commit 시 ConstraintValidator를 전부 통과한 뒤 일괄 apply한다 (all-or-nothing at commit).

## 2. 정직한 한계 (codex 보정 3 반영)

- **atomicity**: 메모리 안에서만. process crash 시 rollback 불가.
- **isolation**: 없음. 단일 사용자 가정.
- **durability**: 없음.
- 진짜 ACID는 단계 8 WAL 이후다. 지금 만드는 것은 "작업을 묶는 그릇"까지다.

## 3. 문제 정의 (TDD step 1)

지금까지 insert는 한 건씩 heap에 바로 꽂혔다. 그런데 "주문 한 건"은 보통 여러 행이다 — 주문 헤더 1행 + 품목 3행. 이 중 품목 2번째가 PK 중복이면 어떻게 되어야 하나?

지금 구조로는 **헤더와 품목 1번은 이미 들어가 있고 2번에서 터진다.** 반쯤 들어간 주문이 남는다. 이게 atomicity가 없다는 말의 구체적 의미다.

이번 세션의 목표는 그 절반을 없애는 것이다. insert를 **바로 쓰지 않고 모아뒀다가**, commit 시점에 검증을 전부 통과한 뒤 한꺼번에 넣는다. 검증에서 걸리면 heap은 손도 대지 않은 상태로 남는다.

주의 — 이건 "메모리 안에서의 all-or-nothing"일 뿐이다. 일괄 apply 도중 프로세스가 죽으면 여전히 반쯤 들어간다. 그 구멍은 단계 8에서 WAL로 막는다.

## 4. 실패 테스트 (TDD step 2)

`WorkUnit` 클래스가 아직 없는 상태에서 아래 파일을 저장한다.

```kotlin
// src/test/kotlin/com/dbenginelab/table/WorkUnitTest.kt @ 5505edc
package com.dbenginelab.table

import com.dbenginelab.catalog.ColumnDef
import com.dbenginelab.catalog.Constraint
import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.catalog.Type
import com.dbenginelab.storage.BufferPool
import com.dbenginelab.storage.PagedFile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

class WorkUnitTest {
    private val schema = TableSchema(
        name = "users",
        columns = listOf(
            ColumnDef("id", Type.BIGINT, nullable = false),
            ColumnDef("name", Type.STRING, nullable = false),
        ),
        constraints = listOf(Constraint.PrimaryKey(listOf("id"))),
    )

    @Test
    fun `commit 후 heap 반영`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("w.data").toString()
        PagedFile(path).use { pf -> BufferPool(pf, 16).use { bp ->
            val heap = TableHeap(schema, pf, bp)
            val w = WorkUnit()
            w.insert(heap, Tuple(schema, listOf(1L, "Alice")))
            w.insert(heap, Tuple(schema, listOf(2L, "Bob")))
            assertEquals(0, heap.rowCount())
            w.commit()
            assertEquals(2, heap.rowCount())
        }}
    }

    @Test
    fun `abort 후 heap 변경 없음`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("w.data").toString()
        PagedFile(path).use { pf -> BufferPool(pf, 16).use { bp ->
            val heap = TableHeap(schema, pf, bp)
            val w = WorkUnit()
            w.insert(heap, Tuple(schema, listOf(1L, "Alice")))
            w.abort()
            assertEquals(0, heap.rowCount())
        }}
    }

    @Test
    fun `commit 시 constraint 위반이면 all-or-nothing`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("w.data").toString()
        PagedFile(path).use { pf -> BufferPool(pf, 16).use { bp ->
            val heap = TableHeap(schema, pf, bp)
            heap.insert(Tuple(schema, listOf(99L, "preexisting")))
            val w = WorkUnit()
            w.insert(heap, Tuple(schema, listOf(1L, "Alice")))
            w.insert(heap, Tuple(schema, listOf(99L, "dup")))
            assertThrows<ConstraintViolation> {
                w.commit(mapOf(heap to ConstraintValidator(heap)))
            }
            assertEquals(1, heap.rowCount())
        }}
    }

    @Test
    fun `committed 후 추가 insert 거부`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("w.data").toString()
        PagedFile(path).use { pf -> BufferPool(pf, 16).use { bp ->
            val heap = TableHeap(schema, pf, bp)
            val w = WorkUnit()
            w.commit()
            assertThrows<IllegalStateException> { w.insert(heap, Tuple(schema, listOf(1L, "X"))) }
        }}
    }
}
```

**예상 실패**: **컴파일 실패** — `Unresolved reference: WorkUnit` (WorkUnitTest.kt 여러 곳).

`./gradlew test --tests 'com.dbenginelab.table.WorkUnitTest'` 로 직접 확인하고 넘어갈 것.

## 5. 구현 코드 (TDD step 3 — make it pass)

핵심 결정 세 가지가 코드에 그대로 드러난다:
- **Deferred insert** — `insert()`는 `pending`에만 쌓고 heap을 건드리지 않는다.
- **abort = buffer clear** — heap을 되돌릴 필요가 없다. 애초에 쓰지 않았으니까.
- **검증은 commit 시점 일괄** — validation 루프와 apply 루프가 **분리되어 있다**. 이 분리가 CI-3의 전부다.

```kotlin
// src/main/kotlin/com/dbenginelab/table/WorkUnit.kt @ 5505edc
package com.dbenginelab.table

import com.dbenginelab.catalog.Tuple

class WorkUnit {
    private enum class State { ACTIVE, COMMITTED, ABORTED }
    private var state: State = State.ACTIVE
    private val pending: MutableList<Pair<TableHeap, Tuple>> = mutableListOf()

    fun insert(heap: TableHeap, tuple: Tuple) {
        // Q: 왜 check가 require가 아닌가?
        check(state == State.ACTIVE) { "WorkUnit not active (state=$state)" }
        // <details><summary>A</summary>
        //
        // check는 내부 상태 검증 (IllegalStateException), require는 인자 검증 (IllegalArgumentException). state는 객체 내부 상태이므로 check.
        // </details>
        pending.add(heap to tuple)
    }

    fun pendingCount(): Int = pending.size

    fun commit(validators: Map<TableHeap, ConstraintValidator> = emptyMap()) {
        check(state == State.ACTIVE)
        // Q: 왜 validation 다 한 뒤 insert? 섞으면 안 되나?
        for ((heap, tuple) in pending) validators[heap]?.validateInsert(tuple)
        // <details><summary>A</summary>
        //
        // all-or-nothing 보장 — validation 도중 throw하면 일부 heap 이미 apply된 상태에서 throw → 부분 적용. validation 전부 통과 후 일괄 apply가 atomic.
        // </details>
        for ((heap, tuple) in pending) heap.insert(tuple)
        pending.clear()
        state = State.COMMITTED
    }

    fun abort() {
        check(state == State.ACTIVE)
        pending.clear()
        state = State.ABORTED
    }

    fun isActive(): Boolean = state == State.ACTIVE
    fun isCommitted(): Boolean = state == State.COMMITTED
    fun isAborted(): Boolean = state == State.ABORTED
}
```

## 6. 검증 테스트 (TDD step 4 — green)

테스트 파일은 §4에서 저장한 것이 그대로 최종본이다 — 더 손댈 것이 없다.

```bash
./gradlew test --tests 'com.dbenginelab.table.WorkUnitTest'
```

**기대 결과**: `WorkUnitTest` **4 PASSED**

invariant 대응:
- **CI-1** ← `commit 후 heap 반영`
- **CI-2** ← `abort 후 heap 변경 없음`
- **CI-3** ← `commit 시 constraint 위반이면 all-or-nothing`
- (상태 기계) ← `committed 후 추가 insert 거부`

## 7. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

**1.** `commit()`의 두 루프를 **하나로 합쳐라** — `validateInsert` 하고 바로 `heap.insert` 하도록. 어느 테스트가 깨지는가?

<details><summary>답</summary>

**실측: `commit 시 constraint 위반이면 all-or-nothing`이 실패한다** (4개 중 1개).

테스트가 만드는 상황:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
heap.insert(Tuple(schema, listOf(99L, "preexisting")))   // 기존 1건
w.insert(heap, Tuple(schema, listOf(1L,  "Alice")))      // 정상
w.insert(heap, Tuple(schema, listOf(99L, "dup")))        // PK 중복
assertThrows<ConstraintViolation> { w.commit(...) }
assertEquals(1, heap.rowCount())                          // ← 여기서 실패
```

합친 뒤의 실행 순서:

```
Alice → validate 통과 → heap.insert   ← 이미 들어감
99    → validate 실패 → throw
결과: heap.rowCount() == 2  (기존 1 + Alice 1)
```

기대는 **1**(아무것도 안 들어감)인데 **2**가 나온다. Alice가 남았다.

두 루프로 나눠두면 `99`에서 던질 때 아직 `heap.insert`를 한 번도 안 했으므로 heap이 원래대로다. **"전부 검사한 뒤에 전부 적용"이 all-or-nothing의 전부**이고, 그것이 이 두 줄의 분리로 표현되어 있다.

06-04 과제 1번(index 검사와 heap.insert 순서)과 완전히 같은 원리다 — 규모만 다르다.
</details>

**2.** commit 도중 프로세스를 죽이면? (`heap.insert` 루프 중간에 예외를 강제로 던져봐라.) atomicity가 어디서 깨지는지 한 문장으로 적어라.

<details><summary>답</summary>

**apply 루프 자체가 원자적이지 않다.**

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
for ((heap, tuple) in pending) validators[heap]?.validateInsert(tuple)   // 검사 끝
for ((heap, tuple) in pending) heap.insert(tuple)   // ← 이 루프 중간에 죽으면
pending.clear()
state = State.COMMITTED
```

3건 중 2건까지 넣고 죽으면 **2건은 heap에 있고 `pending`은 메모리와 함께 사라진다.** 나머지 1건이 무엇이었는지 아무도 모르고, 되돌릴 근거도 없다.

한 문장으로: **검증은 일괄이 됐지만 적용은 여전히 한 건씩이고, 그 사이에는 아무 보호가 없다.**

여기서 중요한 인식 하나 — 이 문제는 **코드를 더 잘 짜서 풀 수 없다.** 루프를 어떻게 배치하든 "여러 page를 바꾸는 동안 죽지 않는다"를 프로세스 안에서 보장할 방법이 없다. 필요한 건 **"무엇을 하려 했는지"를 죽어도 남는 곳에 먼저 적어두는 것**이고, 그게 WAL이다.

08-01의 `Transaction`이 이 클래스와 거의 같은 모양인데 `pending`이 **디스크의 로그**로 바뀐 것뿐이라는 점을 그때 확인해봐라.
</details>

**3.** 두 WorkUnit이 같은 heap에 동시에 insert하면?

<details><summary>답</summary>

두 층위에서 깨진다.

**(1) `TableHeap` 수준의 경합** — 06-01 과제 4번과 같다. `freeOffset`을 읽고 쓰는 사이에 끼어들면 tuple이 서로를 덮는다.

**(2) 검증의 무효화 — 이쪽이 더 흥미롭다.**

```
W1: pending = [(id=1)]      W2: pending = [(id=1)]
W1: commit → validate 통과 (heap에 1 없음)
W2: commit → validate 통과 (heap에 아직 1 없음 — W1이 apply 전)
W1: apply → id=1 들어감
W2: apply → id=1 또 들어감    ← PK 중복이 통과
```

**둘 다 검증을 통과하고 둘 다 적용된다.** 각자 보기엔 아무 문제가 없었다.

이게 06-03 과제 4번의 TOCTOU가 트랜잭션 수준으로 확대된 모습이다. 그리고 **deferred insert 구조가 이 창을 오히려 넓힌다** — 검증과 적용 사이 간격이 insert 한 번이 아니라 commit 전체가 되기 때문이다.

막으려면 commit 구간 전체를 직렬화해야 한다(단계 9 lock) 또는 커밋 시점에 충돌을 감지해야 한다(단계 10 MVCC + first-committer-wins — 다만 우리 MVCC는 이걸 구현하지 않는다, 10-02 참조).
</details>

**4.** WorkUnit이 read도 지원하려면? 어떤 순서로 합쳐야 "내가 방금 넣은 행이 보인다"가 성립하나?

<details><summary>답</summary>

**heap을 먼저, 그 뒤에 `pending`을 이어 붙인다.**

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
fun scan(heap: TableHeap): Sequence<Tuple> =
    heap.scan() + pending.filter { it.first == heap }.map { it.second }
//  ↑ 이미 커밋된 것    ↑ 내가 아직 커밋 안 한 것
```

이 순서여야 하는 이유는 **삽입 순서를 보존**하기 위해서다. `pending`은 정의상 heap의 내용보다 나중에 생긴 것들이므로 뒤에 와야 한다.

이 기능을 **read-your-own-writes**라고 부른다. 트랜잭션 안에서 자기가 넣은 행이 자기에게는 보여야 한다는 성질이고, 없으면 이런 코드가 깨진다:

```sql
BEGIN;
INSERT INTO orders VALUES (…);
SELECT COUNT(*) FROM orders;   -- 방금 넣은 게 안 세어지면 이상하다
COMMIT;
```

**여기서 어려워지는 지점**을 짚어두면:

- **UPDATE/DELETE가 들어오면** 단순 이어붙이기가 안 된다. `pending`에 "이 행을 지웠다"는 표시가 있으면 heap 쪽 결과에서 **빼야** 한다 → 10-01의 tombstone과 같은 문제.
- **다른 트랜잭션에게는 안 보여야 한다.** 즉 이 합친 뷰는 나만의 것이다 → 그게 isolation이고, 단계 10 MVCC의 snapshot이 같은 일을 훨씬 일반적으로 한다.

지금 `WorkUnit`에 read가 없는 것은 게으름이 아니라 **범위 설정**이다 — read를 넣는 순간 isolation을 정의해야 하고, 그건 단계 10의 주제다.
</details>

## 8. 다음 한계

crash가 나면 atomicity도 durability도 없다. `pending`은 메모리에만 있고, 일괄 apply 도중 죽으면 부분 적용이 그대로 남는다.

→ **단계 8 WAL**. 변경을 먼저 로그에 적고(write-ahead), 그 로그로 재시작 시 복구한다. 그때 비로소 이 클래스가 `Transaction`이라는 이름을 가질 자격이 생긴다.
