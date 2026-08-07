# impl/18-01 — WAL Shipping (읽기 복제본)

> **종류**: 세션형
> **상위 단계**: `docs/stages/18-replication.md`
> **코드 정본**: git `5505edc` — "complete: 21 stages + 12 보강 (120/120 tests)"
> **이 세션의 범위**: primary의 WAL을 replica로 보내 같은 상태를 만든다. **읽기 복제본까지 — failover는 없다.**
> **작성 파일**:
> - 신규 패키지: `src/main/kotlin/com/dbenginelab/replication/`
> - 신규: `replication/Replication.kt` (`WalSender` · `WalReceiver`)
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/replication/ReplicationTest.kt`
> **검증**: `ReplicationTest` 1 PASSED
> **예상 타이핑 시간**: 30분

---

## 0. 참조

- PostgreSQL의 WAL shipping / streaming replication 개념.
- **정직한 범위 (codex 보정)**: **read replica까지만이다.** failover(primary가 죽었을 때 replica를 승격), split-brain 방지, 동기 복제 — 아무것도 없다. 이 셋이 복제의 진짜 어려운 부분이고, 여기서는 다루지 않는다.

## 1. 만족시킬 invariant

- **CI-1**: primary의 WAL 내용이 replica의 WAL로 전달되어 같은 레코드 열이 된다.

## 2. 의존성

- `impl/08-01-wal-recovery.md` (`LogManager` — 복제의 단위는 로그 레코드다)

## 3. 문제 정의 (TDD step 1)

복제의 핵심 통찰은 이것이다 — **데이터를 복사할 필요가 없다. 변경 이력을 복사하면 된다.**

우리는 이미 그 이력을 갖고 있다. 단계 8의 WAL이 "무엇이 어떤 순서로 바뀌었는가"의 완전한 기록이다. 그것을 다른 서버에 보내 같은 순서로 재생하면 같은 상태가 된다.

그래서 필요한 것은 두 조각뿐이다:
- **WalSender** (primary 쪽) — 로그를 읽어 내보낸다.
- **WalReceiver** (replica 쪽) — 받아서 자기 로그에 적는다.

여기서 즉시 따라오는 질문들이 있는데, 이 세션은 **답하지 않는다** — 그게 정직한 범위 설정이다:
- primary가 커밋 응답을 언제 하나? 보내기 전인가 후인가 (비동기 vs 동기 복제)
- replica가 뒤처지면? (replication lag)
- primary가 죽으면 누가 승격하나? 둘 다 primary라고 믿으면? (split-brain)

## 4. 실패 테스트 (TDD step 2)

```kotlin
// src/test/kotlin/com/dbenginelab/replication/ReplicationTest.kt @ 5505edc
package com.dbenginelab.replication

import com.dbenginelab.wal.LogManager
import com.dbenginelab.wal.LogRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

class ReplicationTest {
    @Test fun `primary WAL 내용이 replica로`(@TempDir tempDir: Path) {
        val pPath = tempDir.resolve("p.log").toString()
        val rPath = tempDir.resolve("r.log").toString()
        LogManager(pPath).use { p ->
            p.append(LogRecord.BeginTx(1)); p.append(LogRecord.CommitTx(1)); p.sync()
        }
        val records = LogManager(pPath).use { WalSender(it).stream() }
        LogManager(rPath).use { r -> WalReceiver(r).apply(records) }
        val replicated = mutableListOf<LogRecord>()
        LogManager(rPath).use { it.replay { rec -> replicated.add(rec) } }
        assertEquals(2, replicated.size)
    }
}
```

**예상 실패**: **컴파일 실패** — `Unresolved reference: WalSender`, `WalReceiver`.

## 5. 구현 코드 (TDD step 3 — make it pass)

> **정본 특이사항**: 이 파일에는 단계 21의 `HashShardRouter`도 **함께 들어있다.** 정본에 21단계 시점의 스냅샷이 따로 없어 최종형을 그대로 싣는다. 지금은 `WalSender`·`WalReceiver` 둘만 보면 되고, `HashShardRouter`는 `21-01-sharding-stub.md`에서 다룬다.

```kotlin
// src/main/kotlin/com/dbenginelab/replication/Replication.kt @ 5505edc
package com.dbenginelab.replication

import com.dbenginelab.wal.LogManager
import com.dbenginelab.wal.LogRecord

class WalSender(private val primary: LogManager) {
    // Q: List 반환 — streaming (Sequence) 안 됨?
    fun stream(): List<LogRecord> {
    // <details><summary>A</summary>
    // 학습 단순화. 진짜 streaming은 long-lived TCP + tail polling + position tracking 필요. 단계 18-2 후보.
    // </details>
        val out = mutableListOf<LogRecord>()
        primary.replay { out.add(it) }
        return out
    }
}

class WalReceiver(private val replica: LogManager) {
    fun apply(records: List<LogRecord>) {
        for (r in records) replica.append(r)
        // Q: batch sync — 매 record마다 sync 아닌 이유?
        replica.sync()
        // <details><summary>A</summary>
        // 매번 sync는 IO 폭증. batch가 효율적. 단 ack 필요하면 sync 시점 critical.
        // </details>
    }
}

class HashShardRouter(private val shardCount: Int) {
    init { require(shardCount > 0) }
    // Q: 왜 `and Int.MAX_VALUE`?
    fun shardOf(key: Any): Int = (key.hashCode() and Int.MAX_VALUE) % shardCount
    // <details><summary>A</summary>
    // hashCode() 음수 가능 (signed Int). MAX_VALUE 마스킹으로 음수 비트 제거 → mod 음수 회피.
    // </details>
}
```

## 6. 검증 테스트 (TDD step 4 — green)

```bash
./gradlew test --tests 'com.dbenginelab.replication.ReplicationTest'
```

**기대 결과**: `ReplicationTest` **1 PASSED** (`primary WAL 내용이 replica로`)

**테스트가 하나뿐이라는 사실이 이 세션의 범위를 정확히 말해준다** — 우리가 검증하는 것은 "로그가 옮겨진다" 하나뿐이다. 복제의 어려운 부분은 전부 §7에 질문으로만 남아 있다.

## 7. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

**1.** replica에 이미 일부 로그가 있는 상태에서 복제를 시작해봐라. 중복 적용되나?

<details><summary>답</summary>

**중복된다.** `WalSender.stream()`은 **항상 처음부터** 전부 보낸다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
fun stream(): List<LogRecord> {
    val out = mutableListOf<LogRecord>()
    primary.replay { out.add(it) }      // ← 로그 전체
    return out
}
```

그리고 `WalReceiver.apply`는 받은 것을 그대로 append한다. 이미 있는 레코드도 다시 붙으므로 **replica의 WAL에 같은 레코드가 두 벌** 생긴다. 그 상태로 recovery를 돌리면 INSERT가 두 번 적용된다.

막는 방법이 08-02에 이미 있다:

- **LSN** — receiver가 "나는 LSN 500까지 받았다"를 알리고, sender는 **501부터** 보낸다. 실제 streaming replication이 하는 일이 정확히 이것이다(PostgreSQL의 `pg_stat_replication`에 `sent_lsn`·`replay_lsn`이 보이는 이유).
- **IdempotentRecovery** — 설령 중복이 와도 `lastAppliedLsn` 이하는 건너뛴다.

즉 **재료는 다 있는데 이 세션이 연결하지 않았다.** `stream()`이 `fromLsn: Long` 파라미터를 받도록 고치는 것이 첫걸음이다.
</details>

**2.** 전송 중간에 끊긴 상태(로그 절반만 도착)를 만들어봐라. 08-03의 partial write 처리가 여기서도 통하나?

<details><summary>답</summary>

**통한다 — 다만 우연에 가깝다.**

`WalReceiver`가 레코드를 절반만 쓰고 죽으면 replica의 WAL 끝에 불완전한 바이트가 남는다. 그 파일을 나중에 `replay`하면 08-01에서 만든 EOF 처리가 **그대로 작동해** 잘린 꼬리를 버린다.

같은 파일 형식을 쓰기 때문에 저절로 얻어진 성질이다. 로그 형식을 공유한다는 것 자체가 설계 이득인 셈이다.

**그런데 새 문제가 생긴다** — replica는 그 레코드를 **버렸다는 사실을 primary에게 알려야 한다.** 안 그러면:

```
primary: "LSN 500까지 보냈다"
replica: 실제로는 498까지만 온전하다 (499~500은 잘려 버려짐)
→ 다음 전송이 501부터 시작하면 499~500이 영구 유실
```

그래서 실제 프로토콜은 **replica가 자기 상태를 보고**하는 방향의 통신이 반드시 있다. 단방향 push만으로는 복제가 성립하지 않는다.

08-03 과제 3번에서 본 한계도 그대로 따라온다 — **"잘림"과 "손상"을 구분 못 한다.** 네트워크로 온 데이터는 손상 가능성이 파일보다 높으므로 체크섬의 필요가 더 크다.
</details>

**3.** 비동기 복제에서 primary가 죽으면 아직 안 보낸 커밋은 어떻게 되나? 이 유실을 뭐라고 부르나?

<details><summary>답</summary>

**사라진다.** 그리고 사용자에게는 이미 "커밋 완료"라고 응답한 뒤다.

이것을 **데이터 유실 창(data loss window)** 또는 복제 지연으로 인한 **RPO**(Recovery Point Objective) 손실이라 부른다. "장애 시 몇 초치 데이터를 잃을 수 있는가"가 곧 RPO다.

비동기 복제의 정의가 이것이다 — **primary가 replica의 확인을 기다리지 않고 커밋을 확정한다.** 빠른 대신 창이 열린다.

선택지와 대가:

| 방식 | primary가 커밋 확정하는 시점 | RPO | 대가 |
|---|---|---|---|
| **비동기** | 자기 디스크에 쓰면 끝 | 수 초 유실 가능 | 빠름 |
| **동기** | replica가 받았다고 확인하면 | 0 | 매 커밋이 네트워크 왕복만큼 느려짐 |
| **준동기** | replica가 **디스크에 쓰기 전** 수신만 확인 | 거의 0 | 중간 |

**동기 복제의 함정**도 알아둬라 — replica가 죽거나 느려지면 **primary가 커밋을 못 한다.** 가용성을 높이려고 도입한 복제가 오히려 전체를 멈춰 세운다. 그래서 "replica가 N초 응답 없으면 비동기로 강등"하는 정책이 함께 온다.

**공짜 점심이 없다**는 것이 이 표의 전부다.
</details>

**4.** replica에 쓰기를 시도하면? 지금 코드가 막나?

<details><summary>답</summary>

**막지 않는다.** replica는 그냥 `LogManager`를 가진 또 하나의 인스턴스일 뿐이고, "나는 replica다"라는 상태를 어디에도 갖고 있지 않다. `WalReceiver`가 붙어 있을 뿐 그 외의 쓰기를 거부할 장치가 없다.

일어나는 일 — **분기(divergence)** 다:

```
replica에 직접 INSERT (id=99)
primary에서도 INSERT (id=99)  → 복제로 전달됨
→ replica에서 PK 중복 또는 서로 다른 데이터
→ 이제 두 노드의 상태가 영원히 다르다
```

한 번 갈라지면 **자동으로 되돌릴 방법이 없다.** replica를 통째로 다시 만드는 것(re-seed) 말고는.

그래서 실제 시스템은 노드에 **역할 상태**를 두고 replica에서는 쓰기를 거부한다(PostgreSQL의 `hot_standby`는 읽기 전용). 그리고 그 역할이 바뀌는 순간(승격)을 엄격히 통제한다.

**"막지 않는다"가 곧 과제 5번의 전제**가 된다 — 두 노드가 모두 쓰기를 받아들일 수 있으면 split-brain이 가능해진다.
</details>

**5.** primary와 replica가 둘 다 자기가 primary라고 믿으면? (split-brain) 어떤 손상이 일어나나?

<details><summary>답</summary>

전형적인 발생 경로:

```
1. primary A와 replica B 사이 네트워크가 끊긴다 (A는 멀쩡히 살아있다)
2. 감시 시스템이 "A가 죽었다"고 판단 → B를 primary로 승격
3. 그런데 A도 계속 살아서 클라이언트 요청을 받고 있다
4. 이제 A와 B가 각자 다른 데이터를 쓴다
5. 네트워크가 복구된다 → 어느 쪽이 진짜인가?
```

손상의 구체적 모습:

- **같은 PK로 다른 데이터.** A에서는 주문 #1000이 "홍길동", B에서는 "김철수". 병합할 방법이 없다.
- **양쪽에서 발급한 ID가 충돌.** 시퀀스가 각자 돌아 같은 번호가 두 번 쓰인다.
- **어느 쪽을 버려도 실제 사용자의 데이터가 사라진다.** A를 버리면 A로 주문한 사람들의 주문이 통째로 없어진다.

**핵심 어려움은 "죽었다"와 "연결이 안 된다"를 구분할 수 없다는 것**이다. 네트워크 너머의 침묵은 두 가지 원인을 갖는데 관측자는 둘을 구분할 수단이 없다.

그래서 실제 해법은 **과반수(quorum)** 다 — 노드가 3개 이상일 때 "과반의 동의를 얻은 쪽만 primary가 된다". 소수 쪽은 스스로 쓰기를 멈춘다(fencing). 이것을 정확히 하려면 합의 알고리즘(Raft·Paxos)이 필요하고, **그래서 §0이 failover를 범위 밖으로 선언한 것**이다. 21-01의 "본격 구현에 필요한 것" 표와 같은 자리로 이어진다.
</details>

## 8. 다음 한계

복제는 되지만 **스키마를 바꾸는 순간 문제가 생긴다.** `ALTER TABLE`을 하려면 지금은 테이블을 잠그고 전체를 다시 써야 한다 — 서비스가 멈춘다.

→ **단계 19 Online DDL**.
