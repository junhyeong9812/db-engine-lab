# impl/21-01 — Hash Sharding Stub (capstone)

> **종류**: 보강형 (stub — 새 파일 없음. `Replication.kt` 안의 `HashShardRouter` 한 클래스)
> **상위 단계**: `docs/stages/21-sharding.md`
> **코드 정본**: git `5505edc` — "complete: 21 stages + 12 보강 (120/120 tests)"
> **이 세션의 범위**: 키를 샤드로 보내는 함수 하나. **여기까지가 정직한 범위다.**
> **작성 파일**:
> - 코드는 `src/main/kotlin/com/dbenginelab/replication/Replication.kt` 안 (`HashShardRouter`) — 18-01에서 이미 파일 전문을 쳤다
> - 전용 테스트 없음
> **예상 타이핑 시간**: 10분

---

## 0. 정직한 평가 (codex 보정)

**샤딩은 DB 엔진 학습이 아니라 분산 시스템 학습이다.** 장르가 바뀐다. 이 프로젝트(db-engine-lab)의 본류는 단계 20에서 끝났고, 여기서 제대로 다루려면 합의 알고리즘·분산 트랜잭션·장애 감지가 전부 필요하다 — 그건 별도 트랙이다.

그래서 이 세션은 **stub 하나만** 만들고 나머지는 "무엇을 더 배워야 하는가"의 목록으로 남긴다. **못 하는 것을 못 한다고 적는 것도 설계다.**

## 1. Stub 코드

`HashShardRouter`는 `replication/Replication.kt` 안에 있다 — 18-01에서 그 파일 전문을 이미 쳤으므로 **새로 칠 것은 없다.** 아래는 그중 해당 부분만 발췌한 것이다.

```kotlin
// (발췌 — 18-01에서 친 replication/Replication.kt 안의 클래스)
class HashShardRouter(private val shardCount: Int) {
    init { require(shardCount > 0) }
    // Q: 왜 `and Int.MAX_VALUE`?
    fun shardOf(key: Any): Int = (key.hashCode() and Int.MAX_VALUE) % shardCount
    // <details><summary>A</summary>
    //
    // hashCode()는 음수가 될 수 있다(signed Int). Int.MAX_VALUE(0x7FFFFFFF) 마스킹이 부호 비트를 지워 mod 결과가 음수가 되는 것을 막는다.
    // </details>
}
```

이 한 줄짜리 함수가 샤딩의 **가장 쉬운 부분**이라는 것이 이 세션의 교훈이다.

## 2. 본격 구현에 필요한 것 (학습 지도)

| 주제 | 왜 어려운가 |
|------|-----------|
| **Cross-shard transaction** | 두 샤드에 걸친 트랜잭션의 원자성. 2PC는 coordinator가 죽으면 참여자가 무한 대기(blocking). Calvin·Spanner는 다른 접근 |
| **Range vs Hash sharding** | Hash는 고르게 퍼지지만 범위 조회가 전 샤드 조회가 된다. Range는 반대 — 범위 조회는 빠르지만 hot spot이 생긴다 |
| **Re-sharding** | 샤드 수가 바뀌면 `% shardCount`의 결과가 **전부** 바뀐다. → consistent hashing |
| **Coordinator HA** | 라우팅 정보를 누가 갖고 있나. 그것이 죽으면? → Raft / Paxos |

**과제**: 위 네 가지 중 하나를 골라 "우리 코드에 넣는다면 어디를 어떻게 바꿔야 하는가"를 한 페이지로 적어봐라. 코드는 쓰지 마라 — 설계만.

**계산 과제**: `shardCount`를 4에서 5로 늘리면 **키의 몇 %가 다른 샤드로 이동하는가?** 먼저 예측하고 확인해라.

<details><summary>답</summary>

**약 80%가 이동한다.** 대부분의 사람이 "20% 정도"라고 예측하는데, 실제는 정반대다.

이유: `key % 4`와 `key % 5`는 **아무 관계가 없는 두 함수**다. 같은 샤드에 남으려면 우연히 두 나머지가 같아야 하는데, 그럴 확률이 대략 `1/5`다. 따라서 **약 4/5 = 80%가 이동한다.**

직접 확인:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
val moved = (1..100000).count { (it % 4) != (it % 5) }
println(moved * 100.0 / 100000)      // ≈ 80
```

일반화하면 `N → N+1`에서 남는 비율은 대략 `1/(N+1)`이다. 샤드가 100개에서 101개가 되어도 **99%가 이동한다.** 샤드를 하나 늘리는 일이 사실상 **전체 데이터 재배치**가 되는 것이다.

**이것이 consistent hashing이 필요한 이유의 전부다.** consistent hashing은 키와 샤드를 같은 원형 공간에 배치하고 "시계 방향으로 가장 가까운 샤드"에 할당한다. 샤드를 하나 추가하면 **그 샤드가 맡게 될 구간의 키만** 이동한다 — `N → N+1`에서 이동 비율이 `1/(N+1)`, 즉 위와 정확히 뒤집힌다.

| | N→N+1에서 이동하는 키 |
|---|---|
| `% shardCount` | 약 `N/(N+1)` — 거의 전부 |
| consistent hashing | 약 `1/(N+1)` — 극히 일부 |

한 줄짜리 `%` 연산이 왜 프로덕션에 못 가는지가 이 표 하나로 설명된다. **문제는 정확성이 아니라 운영 가능성**이다 — `%`도 올바르게 동작하지만, 샤드를 늘리려면 서비스를 멈추고 전체를 옮겨야 한다.</details>

## 3. 참조 자료

- Citus / Vitess 문서 — 기존 DB 위에 샤딩을 얹는 접근
- TiDB / CockroachDB 설계 문서 — 처음부터 분산으로 만든 접근
- Google Spanner 논문 — TrueTime과 외부 일관성

## 4. Phase B 종료

여기까지가 db-engine-lab의 본류다. 단계 1의 append-only 파일에서 시작해 21단계까지 왔다.

**회고를 쓸 차례다** → `impl/retrospective/phase-b.md`. 회고에서 다룰 것:
- 각 단계의 "다음 한계"가 실제로 다음 단계의 동기가 되었는가
- "직접 깨뜨릴 과제"에서 예측이 틀린 적은 언제였나 — **틀린 예측이 가장 많이 배운 지점이다**
- 지금 다시 만든다면 어느 단계의 설계를 바꾸겠는가
