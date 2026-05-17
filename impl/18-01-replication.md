# impl/18-01 — WAL Shipping (한 줄 한 줄)

> **검증**: ReplicationTest 1 PASSED.
> 작성 파일:
> - 신규 패키지: `src/main/kotlin/com/dbenginelab/replication/`
> - 신규: Replication.kt (WalSender + WalReceiver + HashShardRouter)
> - 신규 테스트: ReplicationTest.kt

## 0. 참조
PostgreSQL streaming replication docs.

## 1. invariant (codex 보정 — read replica only, no failover)
- primary WAL의 모든 record → replica WAL 정확.
- async only.
- replica WAL 위에 Recovery → primary 데이터 재현.

## 2. Replication.kt — 한 줄 한 줄

```kotlin
package com.dbenginelab.replication                                  // 신규 replication 패키지

import com.dbenginelab.wal.LogManager
import com.dbenginelab.wal.LogRecord

class WalSender(private val primary: LogManager) {
    // Q: List 반환 — streaming (Sequence) 안 됨?
    fun stream(): List<LogRecord> {
        val out = mutableListOf<LogRecord>()
        primary.replay { out.add(it) }
        return out
    }
    // <details><summary>A</summary>
    // 학습 단순화. 진짜 streaming은 long-lived TCP + tail polling + position tracking 필요. 단계 18-2 후보.
    // </details>
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

class HashShardRouter(private val shardCount: Int) {                 // Stage 21 capstone stub
    init { require(shardCount > 0) }
    // Q: 왜 `and Int.MAX_VALUE`?
    fun shardOf(key: Any): Int = (key.hashCode() and Int.MAX_VALUE) % shardCount
    // <details><summary>A</summary>
    // hashCode() 음수 가능 (signed Int). MAX_VALUE 마스킹으로 음수 비트 제거 → mod 음수 회피.
    // </details>
}
```

## 3. 검증 (1 PASSED)
- primary WAL 모든 record → replica WAL

## 4. 깨뜨릴 과제
- replica lag 측정 — sender vs receiver LSN 차이?
- read-your-writes 보장?
- failover (primary 죽으면 replica promote)?

## 5. 다음 한계
- read replica only — failover, sync replication 미지원.
- streaming 미지원 (List 한 번 끝).
