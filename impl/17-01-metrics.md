# impl/17-01 — MetricsRegistry + SlowQueryLog (한 줄 한 줄)

> **검증**: MetricsTest 4 PASSED.
> 작성 파일:
> - 신규 패키지: `src/main/kotlin/com/dbenginelab/metrics/`
> - 신규: Metrics.kt
> - 신규 테스트: MetricsTest.kt

## 0. 참조
PostgreSQL pg_stat_*. Prometheus.

## 1. invariant
- counter 누적, gauge set.
- snapshot 일관 view.
- SlowQueryLog threshold 이상만 기록.

## 2. Metrics.kt — 한 줄 한 줄

```kotlin
package com.dbenginelab.metrics                                      // 신규 metrics 패키지

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class MetricsRegistry {
    private val counters = ConcurrentHashMap<String, AtomicLong>()   // Q: 왜 AtomicLong?
    private val gauges = ConcurrentHashMap<String, AtomicLong>()
    // <details><summary>A</summary>
    // multi-thread 동시 inc 안전. @Synchronized 대신 lock-free CAS.
    // </details>

    fun incCounter(name: String, delta: Long = 1) {
        counters.computeIfAbsent(name) { AtomicLong(0) }.addAndGet(delta)
    }
    fun setGauge(name: String, value: Long) {
        gauges.computeIfAbsent(name) { AtomicLong(0) }.set(value)
    }
    fun counter(name: String): Long = counters[name]?.get() ?: 0
    fun gauge(name: String): Long = gauges[name]?.get() ?: 0

    fun snapshot(): Map<String, Long> {                              // Q: weak consistency OK?
        val out = mutableMapOf<String, Long>()
        counters.forEach { (k, v) -> out["counter.$k"] = v.get() }
        gauges.forEach { (k, v) -> out["gauge.$k"] = v.get() }
        return out
        // <details><summary>A</summary>
        // Prometheus도 weak consistency. counter A 읽고 B 읽기 사이 변경 가능. strong은 lock overhead 큼.
        // </details>
    }
}

class SlowQueryLog(private val thresholdMillis: Long = 1000) {
    private val entries = mutableListOf<Entry>()
    data class Entry(val timestamp: Long, val sql: String, val durationMillis: Long)

    @Synchronized                                                    // Q: 왜 여기는 @Synchronized?
    fun record(sql: String, durationMillis: Long) {
        if (durationMillis >= thresholdMillis) {                     // threshold check
            entries.add(Entry(System.currentTimeMillis(), sql, durationMillis))
        }
    }
    // <details><summary>A</summary>
    // mutableListOf는 thread-safe 아님 — @Synchronized로 atomic add 보장.
    // </details>

    @Synchronized
    fun entries(): List<Entry> = entries.toList()                    // 방어 복사
}
```

## 3. 검증 (4 PASSED)
- counter 누적
- gauge set
- SlowQueryLog threshold
- snapshot 모든 metric

## 4. 깨뜨릴 과제
- histogram (p50/p99) — HdrHistogram?
- SlowQueryLog 무한 증가 — ring buffer 한정?
- metric labels (table=users, op=insert)?
