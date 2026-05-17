# impl/21-01 — Hash Sharding Stub (한 줄 한 줄)

> ⚠ **Stub only — capstone 분리.** 분산 시스템 학습은 별도 트랙.
> 작성 파일:
> - 코드는 `src/main/kotlin/com/dbenginelab/replication/Replication.kt` 안 (HashShardRouter)

## 0. 정직한 평가 (codex 보정)
분산 시스템으로 장르 전환. db-engine-lab 본류 외.

## 1. Stub 코드 — 한 줄 한 줄

```kotlin
// replication/Replication.kt 내부에 같이 작성
class HashShardRouter(private val shardCount: Int) {
    init { require(shardCount > 0) }
    // Q: 왜 `and Int.MAX_VALUE`?
    fun shardOf(key: Any): Int = (key.hashCode() and Int.MAX_VALUE) % shardCount
    // <details><summary>A</summary>
    // hashCode() 음수 가능 (signed Int). Int.MAX_VALUE (0x7FFFFFFF) 마스킹 = sign bit 제거 → mod 음수 회피.
    // </details>
}
```

## 2. 본격 구현 시 필요 (학습 자료)
- Cross-shard transaction → 2PC 또는 Calvin
- Range vs Hash sharding trade-off
- Re-sharding (consistent hashing)
- Coordinator HA (Raft/Paxos)

## 3. 참조 자료
- Citus / Vitess docs
- TiDB / CockroachDB design

## 4. 다음
Phase B 종료. db-engine-lab 본류 완료. 회고 → `impl/retrospective/phase-b.md` (별도 trip).
