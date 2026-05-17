# impl/07-01 — WorkUnit (논리적 작업 단위, ACID 아님)

> 상위: `docs/stages/07-batch.md`
> **검증**: WorkUnitTest 4 PASSED.
> **정직히**: 진짜 ACID Transaction 아님. 단계 8 WAL 후 진짜 Transaction.

## 0. 참조
SimpleDB `Transaction` (lab3 단순 in-memory). BusTub `transaction_manager`.

## 1. invariant
- **CI-1**: commit 후 buffered insert가 heap에 반영.
- **CI-2**: abort 시 buffer만 비움, heap 불변.
- **CI-3**: commit 시 ConstraintValidator 통과 후 일괄 apply (all-or-nothing at commit).

## 2. 정직한 한계 (codex 보정 3 반영)
- **atomicity**: 메모리만 (process crash 시 rollback 불가).
- **isolation**: 단일 사용자.
- **durability**: 없음.
- 진짜 ACID는 단계 8 WAL 후.

## 3. 핵심 결정
- **Deferred insert**: insert는 buffer에만, commit 시 heap.insert 일괄.
- abort = buffer clear (heap 미변경).
- ConstraintValidator는 commit 시점 일괄 호출.

## 4. 구현 코드 (WorkUnit.kt)

```kotlin
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

    fun commit(validators: Map<TableHeap, ConstraintValidator> = emptyMap()) {
        check(state == State.ACTIVE)
        // Q: 왜 validation 다 한 뒤 insert? 섞으면 안 되나?
        for ((heap, tuple) in pending) validators[heap]?.validateInsert(tuple)
        for ((heap, tuple) in pending) heap.insert(tuple)
        // <details><summary>A</summary>
        //
        // all-or-nothing 보장 — validation 도중 throw하면 일부 heap 이미 apply된 상태에서 throw → 부분 적용. validation 전부 통과 후 일괄 apply가 atomic.
        // </details>
        pending.clear()
        state = State.COMMITTED
    }

    fun abort() {
        check(state == State.ACTIVE)
        pending.clear()
        state = State.ABORTED
    }
}
```

## 5. 깨뜨릴 과제
- 과제 1: commit 도중 process kill — buffer 사라지고 heap 부분 apply됨. atomicity 깨짐 → 단계 8 WAL.
- 과제 2: 두 WorkUnit이 같은 heap에 동시 insert — race. → 단계 9 lock.
- 과제 3: WorkUnit이 read도 지원하려면? (자기 buffer + heap merge view)

## 6. 다음 한계
- crash 시 atomicity·durability 깨짐 → **단계 8 WAL**.
