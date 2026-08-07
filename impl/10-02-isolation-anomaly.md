# impl/10-02 — Isolation Level + Anomaly Tests (보강 X4)

> **종류**: 보강형 (테스트 전용 — 신규 main 코드 없음)
> **상위 단계**: `docs/stages/10-mvcc.md`
> **코드 정본**: git `5505edc` — "complete: 21 stages + 12 보강 (120/120 tests)"
> **이 세션의 범위**: 우리가 만든 것이 **정확히 어떤 isolation level인지** 라벨을 붙이고, 막는 것과 못 막는 것을 테스트로 못 박는다.
> **작성 파일**:
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/mvcc/IsolationAnomalyTest.kt`
> **검증**: `IsolationAnomalyTest` 4 PASSED
> **예상 타이핑 시간**: 30분

---

## 0. 보강 동기

codex 지적: Lock(단계 9)도 있고 MVCC(단계 10)도 있는데, **"그래서 이 DB의 isolation level이 뭐냐"에 답할 수 없다.** 이상현상(anomaly)을 검증하는 테스트도 없다. 라벨이 없으면 사용자는 자기 코드가 무엇을 보장하는지 모른 채 쓰게 된다.

## 1. 우리 MVCC가 보장하는 것 — Snapshot Isolation (SI)

| Anomaly | 우리 모델 | 근거 |
|---------|---------|------|
| Dirty Read | ✅ 방지 | 미커밋 버전은 어떤 snapshot에도 안 보인다 |
| Non-Repeatable Read | ✅ 방지 | snapshot이 고정되어 있다 |
| Phantom Read | ✅ 방지 | 위와 같은 이유 |
| **Lost Update** | ❌ **방지 못 함** | first-committer-wins 미구현 |
| **Write Skew** | ❌ **방지 못 함** | SSI(Serializable Snapshot Isolation) 필요 |

아래 두 줄이 이 세션의 핵심이다. **못 막는 것을 테스트로 명시한다** — "이건 아직 안 된다"를 코드로 남기는 것이 주석으로 남기는 것보다 훨씬 오래간다.

## 2. 문제 정의

isolation level은 마케팅 용어가 아니라 **"어떤 이상현상이 일어날 수 있는가"의 목록**이다. 그러니 검증 방법도 정해져 있다 — 각 이상현상을 **재현하려고 시도하고**, 막히는지 뚫리는지 본다.

주의할 점: 이상현상 테스트는 **성공을 검증하는 테스트가 아니다.** `lost update 미방지` 테스트는 **버그가 재현되는 것을 확인하고 통과한다.** 이런 테스트가 왜 가치 있는지 생각해봐라 — 나중에 SSI를 구현하면 이 테스트가 **깨지고**, 그것이 곧 "고쳐졌다"는 신호가 된다.

## 3. 검증 테스트

```kotlin
// src/test/kotlin/com/dbenginelab/mvcc/IsolationAnomalyTest.kt @ 5505edc
package com.dbenginelab.mvcc

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Stage 10 보강 (X4): SI가 막아주는 anomaly 검증 + 막지 못하는 anomaly 명시.
 *
 * Snapshot Isolation (우리 모델):
 *  - Dirty Read: 방지 ✅
 *  - Lost Update: 방지 못 함 ❌ (first-committer-wins 미구현)
 *  - Non-Repeatable Read: 방지 ✅ (snapshot 일관성)
 *  - Phantom Read: 방지 ✅ (snapshot 일관성)
 *  - Write Skew: 방지 못 함 ❌ (SSI 필요)
 */
class IsolationAnomalyTest {

    @Test
    fun `dirty read 방지 - tx2는 tx1 미커밋 변경 못 봄`() {
        val store = MVCCStore<Long, String>()
        val sp = SnapshotProvider()
        val tx1 = sp.begin()
        store.insert(1L, "tx1-dirty", tx1.xid)
        // tx1 미커밋
        val tx2 = sp.begin()
        assertNull(store.get(1L, tx2))  // ✅ dirty read 방지
    }

    @Test
    fun `repeatable read - tx2 두 번 read 같은 결과 (tx3가 commit해도)`() {
        val store = MVCCStore<Long, String>()
        val sp = SnapshotProvider()
        val tx1 = sp.begin(); store.insert(1L, "v1", tx1.xid); sp.commit(tx1)

        val tx2 = sp.begin()
        val r1 = store.get(1L, tx2)

        val tx3 = sp.begin(); store.insert(1L, "v2", tx3.xid); sp.commit(tx3)

        val r2 = store.get(1L, tx2)
        assertEquals(r1, r2)  // ✅ repeatable read
        assertEquals("v1", r2)
    }

    @Test
    fun `phantom 방지 - tx2 snapshot 시점 이후 tx3 insert는 invisible`() {
        val store = MVCCStore<Long, String>()
        val sp = SnapshotProvider()
        val tx1 = sp.begin(); store.insert(1L, "v1", tx1.xid); sp.commit(tx1)

        val tx2 = sp.begin()
        val tx3 = sp.begin(); store.insert(2L, "phantom", tx3.xid); sp.commit(tx3)

        assertNull(store.get(2L, tx2))  // ✅ phantom 방지 — tx2는 key=2 못 봄
    }

    @Test
    fun `lost update 미방지 - first-committer-wins 미구현 (학습 데모)`() {
        // SI는 "lost update"를 SSI 또는 first-committer-wins로 막아야 하는데
        // 우리 단순 모델은 막지 못함 — 두 tx가 같은 key update하면 마지막이 이김.
        val store = MVCCStore<Long, String>()
        val sp = SnapshotProvider()
        val tx0 = sp.begin(); store.insert(1L, "init", tx0.xid); sp.commit(tx0)

        val tx1 = sp.begin(); val tx2 = sp.begin()
        store.insert(1L, "tx1-update", tx1.xid)  // tx1 update
        store.insert(1L, "tx2-update", tx2.xid)  // tx2 update — 우리 모델은 silent OK
        sp.commit(tx1); sp.commit(tx2)

        val tx3 = sp.begin()
        // 마지막 update 결과 보임. SSI라면 한 tx abort.
        assertEquals("tx2-update", store.get(1L, tx3))
        // 학습 메모: 이 anomaly를 막으려면 write conflict detection 또는 SSI.
    }
}
```

```bash
./gradlew test --tests 'com.dbenginelab.mvcc.IsolationAnomalyTest'
```

**기대 결과**: `IsolationAnomalyTest` **4 PASSED**

- `dirty read 방지 - tx2는 tx1 미커밋 변경 못 봄`
- `repeatable read - tx2 두 번 read 같은 결과 (tx3가 commit해도)`
- `phantom 방지 - tx2 snapshot 시점 이후 tx3 insert는 invisible`
- `lost update 미방지 - first-committer-wins 미구현 (학습 데모)` ← **한계를 고정하는 테스트**

## 4. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

**1.** `lost update 미방지` 테스트를 읽고, 어떤 순서로 한쪽의 갱신이 사라지는지 순서도로 그려라. 은행 잔고로 바꾸면?

<details><summary>답</summary>

```
시각  tx1                       tx2                      저장된 값
─────────────────────────────────────────────────────────────────
 1    begin (snapshot=A)                                 "init"
 2                              begin (snapshot=B)       "init"
 3    read → "init"                                      "init"
 4                              read → "init"            "init"
 5    write "tx1-update"                                 버전 추가
 6                              write "tx2-update"       버전 추가
 7    commit                                             tx1 확정
 8                              commit                   tx2 확정
─────────────────────────────────────────────────────────────────
결과: "tx2-update"   ← tx1이 쓴 것은 아무 흔적도 없다
```

**결정적 지점은 3~4행**이다. 둘 다 같은 값("init")을 읽었고, 그것이 곧 "내가 본 것 위에 계산했다"는 뜻인데, **한쪽의 전제가 커밋 시점에 이미 거짓이 되어 있다.** 아무도 그걸 확인하지 않는다.

은행으로 옮기면:

```
잔고 1000원
창구A: 잔고 읽음(1000) → 100원 출금 → 900 기록 → 커밋
창구B: 잔고 읽음(1000) → 100원 출금 → 900 기록 → 커밋
결과: 200원이 나갔는데 잔고는 900원.  100원이 증발했다
```

이 사고의 성질을 봐라 — **두 트랜잭션 각각은 완벽히 정상**이다. 둘을 따로 실행하면 아무 문제가 없다. 문제는 **겹쳤을 때만** 생기고, 그래서 테스트로 재현하려면 의도적으로 순서를 겹쳐야 한다.
</details>

**2.** first-committer-wins를 구현하려면 `MVCCStore`에 무엇을 추가해야 하나? 필요한 정보와 검사 지점만 적어라.

<details><summary>답</summary>

**필요한 정보 세 가지:**

1. **트랜잭션별 write set** — "이 트랜잭션이 어떤 키를 썼는가". 지금은 버전 체인에만 흩어져 있어 트랜잭션 단위로 모으지 못한다.
2. **각 키의 마지막 커밋 xid** — "이 키를 마지막으로 확정한 트랜잭션이 누구인가".
3. **snapshot 시점** — 이미 있다(`Snapshot.xid`).

**검사 지점은 commit 직전 한 곳:**

```
for (key in myWriteSet) {
    if (key의 마지막 커밋 xid > 내 snapshot.xid) {
        abort()   // 내가 읽은 뒤 누군가 이 키를 바꿨다
    }
}
// 통과하면 커밋 확정
```

그리고 이 검사 구간 자체가 **원자적**이어야 한다. 두 트랜잭션이 동시에 검사하면 둘 다 통과할 수 있다(06-03 과제 4번의 TOCTOU가 여기서도 나온다). 그래서 커밋 구간에 짧은 락이 필요하다 — **MVCC라고 락이 아예 없는 게 아니다.**

이름이 `first-committer-wins`인 이유도 명확해진다. 먼저 커밋한 쪽이 검사를 통과하고, 나중 쪽이 "내 전제가 깨졌다"를 발견해 스스로 물러난다.
</details>

**3.** **Write Skew** 테스트를 직접 작성해라. 이 테스트는 **실패해야 정상이다** — 우리 모델이 못 막으니까.

<details><summary>답</summary>

시나리오를 코드로 옮기면:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
// 의사 두 명이 당직. 제약: "최소 1명은 당직이어야 한다"
store.insert("doctorA", "oncall", tx0.xid)
store.insert("doctorB", "oncall", tx0.xid)
sp.commit(tx0)

val tx1 = sp.begin()
val tx2 = sp.begin()

// tx1: "B가 당직이니 나는 빠져도 된다"
val bStatus = store.get("doctorB", tx1)      // "oncall" — 아직 B는 당직
if (bStatus == "oncall") store.insert("doctorA", "off", tx1.xid)

// tx2: "A가 당직이니 나는 빠져도 된다"
val aStatus = store.get("doctorA", tx2)      // "oncall" — tx1은 아직 미커밋
if (aStatus == "oncall") store.insert("doctorB", "off", tx2.xid)

sp.commit(tx1); sp.commit(tx2)

// 이제 둘 다 off — 제약이 깨졌다
val tx3 = sp.begin()
assertEquals("off", store.get("doctorA", tx3))
assertEquals("off", store.get("doctorB", tx3))   // ← 통과한다. 그게 문제다
```

**lost update와 무엇이 다른지**가 핵심이다:

| | lost update | write skew |
|---|---|---|
| 쓰는 키 | **같은** 키 | **다른** 키 |
| first-committer-wins로 막히나 | **막힌다** | **못 막는다** |

write skew는 겹치는 쓰기가 없다. tx1은 A만, tx2는 B만 쓴다. **쓰기 충돌 검사로는 잡을 수 없다** — 깨진 것은 "읽은 것과 쓴 것 사이의 관계"이기 때문이다.

막으려면 **읽기까지 추적**해야 한다. tx1이 B를 읽었다는 사실을 기록해두고, tx2가 B를 바꾸면 충돌로 본다. 이것이 **SSI**(Serializable Snapshot Isolation)이고 PostgreSQL의 `SERIALIZABLE` 격리 수준이 쓰는 방식이다. 비용은 읽기 추적 오버헤드.
</details>

**4.** 단계 9의 Strict 2PL 락을 쓰면 이 네 가지 중 무엇이 달라지나? 락과 MVCC 중 어느 쪽이 더 강한 보장인가?

<details><summary>답</summary>

Strict 2PL(테이블 단위, 우리 구현)을 쓰면 **네 가지가 전부 막힌다** — 다만 이유가 시시하다. 테이블 락이라 **트랜잭션이 사실상 직렬 실행**되기 때문이다. 동시성이 없으니 이상현상도 없다.

**"어느 쪽이 강한가"에 답이 하나가 아닌 이유:**

1. **차원이 다르다.** 락은 "동시 접근을 막아서" 이상현상을 없애고, MVCC는 "각자 다른 시점을 보게 해서" 없앤다. 목표는 같아도 수단이 반대다.
2. **막는 것이 다르다.** 우리 MVCC는 dirty read·non-repeatable read·phantom을 막지만 lost update·write skew는 못 막는다. 우리 락은 전부 막지만 **읽기와 쓰기가 서로를 차단**한다.
3. **비용이 다르다.** 락은 대기(또는 실패)를 만들고, MVCC는 버전 저장과 정리 비용을 만든다.

실제 DB는 **둘 다 쓴다.** PostgreSQL은 읽기에 MVCC, 쓰기 충돌에 락, `SERIALIZABLE`에는 SSI를 얹는다. "MVCC가 락을 대체한다"는 흔한 오해이고, 정확히는 **읽기-쓰기 충돌만 없앤다.** 쓰기-쓰기 충돌은 여전히 조정이 필요하다.

한 문장으로 정리하면 — **MVCC는 읽기를 자유롭게 하고, 락은 쓰기를 안전하게 한다.**
</details>

## 5. 다음 한계

라벨은 붙었지만 이 MVCC는 **메모리 안에서만** 동작한다. 디스크의 `TableHeap`은 여전히 버전 개념이 없다.

→ **10-03 MVCCTableHeap**.
