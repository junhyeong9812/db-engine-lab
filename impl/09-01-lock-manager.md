# impl/09-01 — LockManager (S/X 락과 Strict 2PL)

> **종류**: 세션형
> **상위 단계**: `docs/stages/09-locks.md`
> **코드 정본**: git `5505edc` — "complete: 21 stages + 12 보강 (120/120 tests)"
> **이 세션의 범위**: 공유(S)·배타(X) 락과 충돌 규칙. 대기 없이 **즉시 실패**하는 단순 모델이다.
> **작성 파일**:
> - 신규 패키지: `src/main/kotlin/com/dbenginelab/lock/`
> - 신규: `lock/LockManager.kt`
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/lock/LockManagerTest.kt`
> **검증**: `LockManagerTest` 7 PASSED
> **예상 타이핑 시간**: 40분

---

## 0. 참조

- 주 참조: SimpleDB `LockManager` (lab3).
- 대조 참조: BusTub `lock_manager` (project 3 — multi-granularity가 강하다).
- **차이 채택 여부**: multi-granularity(테이블 락 아래 행 락, intention lock)는 **채택 안 함.** 우리는 S/X 두 종류만 둔다. 계층 락은 규칙이 갑자기 복잡해지는데, 그 복잡성의 이유를 느끼려면 먼저 단순한 쪽에서 부족함을 겪어야 한다.
- **코루틴 금지** (학습 정책) — 동시성은 스레드와 `@Synchronized`로 다룬다.

## 1. 만족시킬 invariant

- **CI-1**: SHARED는 여러 트랜잭션이 동시에 보유할 수 있다.
- **CI-2**: EXCLUSIVE는 단독이어야 한다.
- **CI-3**: 같은 트랜잭션이 같은 모드를 다시 요청하는 것은 멱등이다.
- **CI-4 (Strict 2PL)**: 락은 commit/abort 시점에 `releaseAll`로 한꺼번에 푼다.

## 2. 의존성

- 없음 (독립 클래스). 단계 8의 `Transaction`과는 09-02에서 묶는다.

## 3. 문제 정의 (TDD step 1)

단계 8까지 우리 DB는 **한 번에 한 사람**을 가정한다. 두 트랜잭션이 같은 테이블을 동시에 건드리면 무슨 일이 일어나는지 코드 어디에도 정의되어 있지 않다 — 정의되지 않았다는 것은 **무엇이든 일어날 수 있다**는 뜻이다.

락의 발상은 단순하다. 자원마다 "지금 누가 무슨 모드로 쓰고 있는지"를 적어두고, 새 요청이 충돌하면 거절한다. 규칙은 두 줄이다:

- 읽기(S)끼리는 서로 방해하지 않는다.
- 쓰기(X)는 다른 무엇과도 함께할 수 없다.

여기서 결정할 것이 하나 있다 — **충돌하면 어떻게 할 것인가.** 기다릴 것인가, 즉시 실패할 것인가? 우리는 **즉시 실패**를 택한다. 기다림을 넣는 순간 교착(deadlock)이 생기고, 교착 탐지는 그 자체로 한 단계짜리 주제이기 때문이다. 즉시 실패는 정직한 미완성이다.

**upgrade**(S를 들고 있다가 X로 올리기)만 예외적으로 허용한다. 단, 나 말고 다른 누구도 S를 들고 있지 않아야 한다.

## 4. 실패 테스트 (TDD step 2)

```kotlin
// src/test/kotlin/com/dbenginelab/lock/LockManagerTest.kt @ 5505edc
package com.dbenginelab.lock

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LockManagerTest {

    @Test fun `여러 tx SHARED 동시 보유 가능`() {
        val lm = LockManager()
        lm.acquire(1, "t", LockManager.Mode.SHARED)
        lm.acquire(2, "t", LockManager.Mode.SHARED)
        lm.acquire(3, "t", LockManager.Mode.SHARED)
        assertEquals(3, lm.holderCount("t"))
    }

    @Test fun `EXCLUSIVE는 다른 tx SHARED와 충돌`() {
        val lm = LockManager()
        lm.acquire(1, "t", LockManager.Mode.SHARED)
        assertThrows<LockConflict> { lm.acquire(2, "t", LockManager.Mode.EXCLUSIVE) }
    }

    @Test fun `EXCLUSIVE 보유 중 다른 tx SHARED 충돌`() {
        val lm = LockManager()
        lm.acquire(1, "t", LockManager.Mode.EXCLUSIVE)
        assertThrows<LockConflict> { lm.acquire(2, "t", LockManager.Mode.SHARED) }
    }

    @Test fun `같은 tx S to X 업그레이드 단독시 OK`() {
        val lm = LockManager()
        lm.acquire(1, "t", LockManager.Mode.SHARED)
        lm.acquire(1, "t", LockManager.Mode.EXCLUSIVE)
        assertEquals(1, lm.holderCount("t"))
        assertTrue(lm.isHeld(1, "t"))
    }

    @Test fun `업그레이드시 다른 tx SHARED 보유면 충돌`() {
        val lm = LockManager()
        lm.acquire(1, "t", LockManager.Mode.SHARED)
        lm.acquire(2, "t", LockManager.Mode.SHARED)
        assertThrows<LockConflict> { lm.acquire(1, "t", LockManager.Mode.EXCLUSIVE) }
    }

    @Test fun `releaseAll 후 다른 tx EXCLUSIVE 가능`() {
        val lm = LockManager()
        lm.acquire(1, "t", LockManager.Mode.SHARED)
        lm.acquire(2, "t", LockManager.Mode.SHARED)
        lm.releaseAll(1); lm.releaseAll(2)
        lm.acquire(3, "t", LockManager.Mode.EXCLUSIVE)
        assertEquals(1, lm.holderCount("t"))
        assertFalse(lm.isHeld(1, "t"))
        assertTrue(lm.isHeld(3, "t"))
    }

    @Test fun `같은 tx 같은 SHARED 두 번 idempotent`() {
        val lm = LockManager()
        lm.acquire(1, "t", LockManager.Mode.SHARED)
        lm.acquire(1, "t", LockManager.Mode.SHARED)
        assertEquals(1, lm.holderCount("t"))
    }
}
```

**예상 실패**: **컴파일 실패** — `Unresolved reference: LockManager`.

## 5. 구현 코드 (TDD step 3 — make it pass)

```kotlin
// src/main/kotlin/com/dbenginelab/lock/LockManager.kt @ 5505edc
package com.dbenginelab.lock

class LockManager {
    enum class Mode { SHARED, EXCLUSIVE }
    private data class Holder(val txId: Long, val mode: Mode)
    private val holders: MutableMap<String, MutableList<Holder>> = mutableMapOf()

    @Synchronized
    fun acquire(txId: Long, resource: String, mode: Mode) {
        val current = holders.getOrPut(resource) { mutableListOf() }
        val mine = current.firstOrNull { it.txId == txId }
        if (mine != null) {
            if (mine.mode == Mode.EXCLUSIVE) return
            if (mine.mode == Mode.SHARED && mode == Mode.SHARED) return
            if (current.any { it.txId != txId }) {
                throw LockConflict(txId, resource, mode, current.toList())
            }
            current.remove(mine)
            current.add(Holder(txId, Mode.EXCLUSIVE))
            return
        }
        when (mode) {
            Mode.SHARED -> {
                if (current.any { it.mode == Mode.EXCLUSIVE }) {
                    throw LockConflict(txId, resource, mode, current.toList())
                }
                current.add(Holder(txId, Mode.SHARED))
            }
            Mode.EXCLUSIVE -> {
                if (current.isNotEmpty()) {
                    throw LockConflict(txId, resource, mode, current.toList())
                }
                current.add(Holder(txId, Mode.EXCLUSIVE))
            }
        }
    }

    @Synchronized
    fun releaseAll(txId: Long) {
        val empty = mutableListOf<String>()
        for ((res, list) in holders) {
            list.removeAll { it.txId == txId }
            if (list.isEmpty()) empty.add(res)
        }
        for (res in empty) holders.remove(res)
    }

    @Synchronized
    fun isHeld(txId: Long, resource: String): Boolean =
        holders[resource]?.any { it.txId == txId } ?: false

    @Synchronized
    fun holderCount(resource: String): Int = holders[resource]?.size ?: 0
}

class LockConflict(
    val requesterTxId: Long, val resource: String, val requestedMode: LockManager.Mode, val currentHolders: List<Any>,
) : RuntimeException("tx $requesterTxId cannot acquire $requestedMode on $resource (held by $currentHolders)")
```

## 6. 검증 테스트 (TDD step 4 — green)

테스트 파일은 §4에서 저장한 것이 그대로 최종본이다.

```bash
./gradlew test --tests 'com.dbenginelab.lock.LockManagerTest'
```

**기대 결과**: `LockManagerTest` **7 PASSED**

invariant 대응:
- **CI-1** ← `여러 tx SHARED 동시 보유 가능`
- **CI-2** ← `EXCLUSIVE는 다른 tx SHARED와 충돌` · `EXCLUSIVE 보유 중 다른 tx SHARED 충돌`
- (upgrade) ← `같은 tx S to X 업그레이드 단독시 OK` · `업그레이드시 다른 tx SHARED 보유면 충돌`
- **CI-4** ← `releaseAll 후 다른 tx EXCLUSIVE 가능`
- **CI-3** ← `같은 tx 같은 SHARED 두 번 idempotent`

**CI-4는 절반만 검증된다.** "releaseAll이 락을 푼다"는 확인되지만 "commit/abort **시점에** 푼다"는 이 클래스 밖의 이야기다 — 09-02에서 묶은 뒤에야 검증된다.

## 7. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

**1.** `@Synchronized`를 전부 지워라. 테스트는 통과하는가?

<details><summary>답</summary>

**실측: 7개 전부 통과한다.** `LockManagerTest`가 **단일 스레드**이기 때문이다 — 동기화가 필요한 상황을 만들지 않는다.

두 스레드로 깨뜨리는 테스트를 써봐도 **안정적으로 재현되지 않는다.** 그게 이 과제의 진짜 교훈이다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
// 이런 걸 써봐도
val threads = (1..100).map { Thread { lm.acquire(it.toLong(), "t", EXCLUSIVE) } }
// 100번 중 몇 번만 이상 동작하거나, 한 번도 안 날 수 있다
```

깨지는 지점은 `acquire` 안의 read-modify-write다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
val current = holders.getOrPut(resource) { mutableListOf() }
if (current.isNotEmpty()) throw LockConflict(...)   // ← 읽고
current.add(Holder(txId, Mode.EXCLUSIVE))          // ← 쓴다. 그 사이가 안 막혔다
```

두 스레드가 동시에 `isEmpty`를 보면 **둘 다 EXCLUSIVE를 얻는다.** 락 매니저가 락을 못 지키는 상태다.

**동시성 버그는 "재현 안 되면 없는 것"이 아니다.** 타이밍에 의존하므로 테스트가 통과했다는 것이 아무것도 증명하지 못한다. 그래서 이 영역은 테스트가 아니라 **코드 리뷰와 원칙**(공유 상태를 만지는 구간은 반드시 보호한다)으로 지킨다.
</details>

**2.** upgrade 검사(다른 tx가 S를 들고 있으면 거절)를 지워라. 어느 테스트가 잡나?

<details><summary>답</summary>

`업그레이드시 다른 tx SHARED 보유면 충돌`이 실패한다. 그 테스트가 정확히 이 분기를 겨냥하고 있다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
if (current.any { it.txId != txId }) {
    throw LockConflict(txId, resource, mode, current.toList())
}
```

지우면 tx1이 S를 든 채로, tx2도 S를 든 채로, **tx1이 X로 올라간다.** 그 순간 상태:

- tx1: X 보유 → 자유롭게 쓴다
- tx2: S 보유 → "아무도 안 바꾼다"고 믿고 읽는다

**tx2가 읽는 도중 tx1이 값을 바꾼다.** tx2가 같은 데이터를 두 번 읽으면 다른 값이 나온다 — non-repeatable read다. S 락의 의미("내가 읽는 동안 아무도 못 바꾼다")가 무효가 된다.

S→X 업그레이드는 **원래 위험한 연산**이다. 두 트랜잭션이 동시에 S를 들고 둘 다 X로 올리려 하면 서로를 기다리게 되는데(**upgrade deadlock**), 우리는 즉시 실패라 교착 대신 실패가 난다. 대기 모델이었다면 여기가 교착의 대표적 발생지다.
</details>

**3.** 충돌 시 즉시 실패 대신 대기하도록 바꾼다면 어떤 새 문제가 생기나?

<details><summary>답</summary>

**교착(deadlock)** 이 생긴다. 구체적 순서:

```
tx1: acquire(X, "users")   → 성공
tx2: acquire(X, "orders")  → 성공
tx1: acquire(X, "orders")  → tx2가 들고 있음 → 대기
tx2: acquire(X, "users")   → tx1이 들고 있음 → 대기
→ 둘 다 영원히 대기. 아무도 풀어줄 수 없다
```

각자는 완벽히 합리적으로 행동했는데 전체가 멈춘다. 그리고 **스스로 빠져나올 수 없다** — 대기 중인 트랜잭션은 락을 놓지 않기 때문이다(2PL이 그렇게 시킨다).

그래서 대기 모델을 도입하면 반드시 짝으로 필요한 것이 생긴다:

| 방법 | 내용 |
|---|---|
| **타임아웃** | N초 기다리다 포기. 단순하지만 진짜 교착인지 그냥 느린 건지 구분 못 함 |
| **대기 그래프 순환 탐지** | "누가 누구를 기다리는가" 그래프에 사이클이 생기면 하나를 죽인다. 정확하지만 비용이 든다 |
| **락 순서 강제** | 모든 트랜잭션이 자원을 정해진 순서로만 잡게 한다. 예방적이지만 애플리케이션이 협조해야 한다 |

즉시 실패는 이 셋을 전부 회피한다. **교착이 원천적으로 불가능**하기 때문이다. 대가는 경합이 조금만 있어도 트랜잭션이 자주 실패한다는 것 — 애플리케이션이 재시도를 책임져야 한다.
</details>

**4.** 자원 이름이 테이블 단위다. 행 단위로 바꾸면 동시성은 올라간다 — 대신 무엇이 나빠지나?

<details><summary>답</summary>

**락 개수가 폭발한다.**

```
테이블 단위: 테이블 100개 → 락 최대 100개
행 단위:    행 1억 개    → 락 최대 1억 개
```

`holders`가 `MutableMap<String, MutableList<Holder>>`인데, 행 하나당 엔트리가 생긴다. **락 관리 자체가 메모리와 시간을 먹는다.** 100만 행을 갱신하는 트랜잭션은 100만 개의 락을 잡고, `releaseAll`은 그 전부를 훑는다.

그리고 **"테이블 전체를 읽고 싶다"가 곤란해진다.** 전체 스캔을 하려면 모든 행에 S를 걸어야 하는데, 그건 테이블 락 하나보다 압도적으로 비싸다. 게다가 **아직 존재하지 않는 행**(다른 트랜잭션이 곧 넣을 행)은 잠글 수조차 없다 → 팬텀이 다시 등장한다.

실제 DB의 해법이 **multi-granularity locking**이다 — 테이블·page·행 여러 층위의 락을 두고, "이 테이블 아래 어딘가에 락이 있다"를 나타내는 **intention lock**(IS/IX)을 상위에 건다. 그러면 테이블 전체 락을 원하는 트랜잭션이 하위를 다 뒤지지 않고도 충돌을 판정할 수 있다.

BusTub이 이걸 구현하고 있고(§0의 대조 참조), 우리가 **채택하지 않은** 이유가 여기서 분명해진다 — 규칙이 갑자기 몇 배로 복잡해지는데, 그 복잡성의 필요를 먼저 겪어야 이해가 된다.
</details>

## 8. 다음 한계

락은 만들었지만 **아무도 쓰지 않는다.** 단계 8의 `Transaction`은 락의 존재를 모른다. 사용자가 직접 `acquire`/`releaseAll`을 부르지 않으면 아무 보호도 없다.

→ **09-02 TransactionWithLock**. insert가 X 락을 자동으로 잡고 commit/abort가 자동으로 푸는 것 — 그것이 Strict 2PL의 "자동화"다.
