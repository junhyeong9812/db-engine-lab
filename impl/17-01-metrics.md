# impl/17-01 — MetricsRegistry + SlowQueryLog

> **종류**: 세션형
> **상위 단계**: `docs/stages/17-monitoring.md`
> **코드 정본**: git `5505edc` — "complete: 21 stages + 12 보강 (120/120 tests)"
> **이 세션의 범위**: DB가 지금 무엇을 하고 있는지 **관찰 가능하게** 만든다 — counter · gauge · 느린 질의 기록.
> **작성 파일**:
> - 신규 패키지: `src/main/kotlin/com/dbenginelab/metrics/`
> - 신규: `metrics/Metrics.kt`
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/metrics/MetricsTest.kt`
> **검증**: `MetricsTest` 4 PASSED
> **예상 타이핑 시간**: 30분

---

## 0. 참조

- Prometheus의 metric 종류(counter / gauge) 개념. 노출 형식은 구현하지 않는다.

## 1. 만족시킬 invariant

- **CI-1**: counter는 누적된다 (감소하지 않는다).
- **CI-2**: gauge는 현재 값으로 덮인다.
- **CI-3**: 임계값을 넘긴 질의만 slow query log에 남는다.
- **CI-4**: snapshot이 모든 metric을 한 번에 준다.

## 2. 의존성

- 없음 (독립 계층).

## 3. 문제 정의 (TDD step 1)

단계 16까지 오면 DB는 동작한다. 그런데 **느려졌을 때 왜 느린지 알 방법이 없다.** 운영에서 가장 먼저 필요한 것이 관찰이다.

측정값에는 성격이 두 가지 있고, 이 구분이 이번 세션의 핵심이다:

- **counter** — 누적량. "지금까지 처리한 질의 수". **줄어들지 않는다.** 재시작하면 0으로 돌아간다.
- **gauge** — 현재 상태. "지금 열린 세션 수". 오르내린다.

둘을 헷갈리면 대시보드가 거짓말을 한다. 누적량을 gauge로 두면 "초당 처리량"을 계산할 수 없고, 현재값을 counter로 두면 값이 영원히 커진다.

그리고 **slow query log** — 모든 질의를 기록하면 그 자체가 부하다. 임계값을 넘는 것만 남긴다. 이건 "무엇을 버릴 것인가"의 결정이고, 임계값을 잘못 잡으면 정작 필요한 것이 안 남는다.

## 4. 실패 테스트 (TDD step 2)

```kotlin
// src/test/kotlin/com/dbenginelab/metrics/MetricsTest.kt @ 5505edc
package com.dbenginelab.metrics

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MetricsTest {
    @Test fun `counter 누적`() {
        val r = MetricsRegistry()
        r.incCounter("q"); r.incCounter("q", 5)
        assertEquals(6, r.counter("q"))
    }
    @Test fun `gauge set`() {
        val r = MetricsRegistry()
        r.setGauge("b", 42); r.setGauge("b", 100)
        assertEquals(100, r.gauge("b"))
    }
    @Test fun `SlowQueryLog threshold`() {
        val log = SlowQueryLog(500)
        log.record("fast", 100); log.record("slow", 1500); log.record("th", 500)
        assertEquals(2, log.entries().size)
    }
    @Test fun `snapshot 모든 metric`() {
        val r = MetricsRegistry()
        r.incCounter("a", 3); r.setGauge("b", 7)
        val s = r.snapshot()
        assertEquals(3, s["counter.a"]); assertEquals(7, s["gauge.b"])
    }
}
```

**예상 실패**: **컴파일 실패** — `Unresolved reference: MetricsRegistry`, `SlowQueryLog`.

## 5. 구현 코드 (TDD step 3 — make it pass)

```kotlin
// src/main/kotlin/com/dbenginelab/metrics/Metrics.kt @ 5505edc
package com.dbenginelab.metrics

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class MetricsRegistry {
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val gauges = ConcurrentHashMap<String, AtomicLong>()

    fun incCounter(name: String, delta: Long = 1) {
        counters.computeIfAbsent(name) { AtomicLong(0) }.addAndGet(delta)
    }
    fun setGauge(name: String, value: Long) {
        gauges.computeIfAbsent(name) { AtomicLong(0) }.set(value)
    }
    fun counter(name: String): Long = counters[name]?.get() ?: 0
    fun gauge(name: String): Long = gauges[name]?.get() ?: 0
    fun snapshot(): Map<String, Long> {
        val out = mutableMapOf<String, Long>()
        counters.forEach { (k, v) -> out["counter.$k"] = v.get() }
        gauges.forEach { (k, v) -> out["gauge.$k"] = v.get() }
        return out
    }
}

class SlowQueryLog(private val thresholdMillis: Long = 1000) {
    private val entries = mutableListOf<Entry>()
    data class Entry(val timestamp: Long, val sql: String, val durationMillis: Long)

    @Synchronized
    fun record(sql: String, durationMillis: Long) {
        if (durationMillis >= thresholdMillis) {
            entries.add(Entry(System.currentTimeMillis(), sql, durationMillis))
        }
    }
    @Synchronized
    fun entries(): List<Entry> = entries.toList()
}
```

## 6. 검증 테스트 (TDD step 4 — green)

```bash
./gradlew test --tests 'com.dbenginelab.metrics.MetricsTest'
```

**기대 결과**: `MetricsTest` **4 PASSED**

invariant 대응:
- **CI-1** ← `counter 누적`
- **CI-2** ← `gauge set`
- **CI-3** ← `SlowQueryLog threshold`
- **CI-4** ← `snapshot 모든 metric`

## 7. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

**1.** counter를 감소시킬 수 있게 만들어라. "초당 처리량" 계산이 어떻게 망가지나?

<details><summary>답</summary>

모니터링 도구는 counter에서 **비율(rate)** 을 이렇게 계산한다:

```
rate = (지금 값 − 아까 값) / 경과 시간
```

이 식은 **counter가 절대 줄지 않는다**는 전제 위에 있다. 줄어들면 분자가 음수가 되어 **"초당 -30건 처리"** 같은 값이 나온다.

더 골치 아픈 것 — 모니터링 도구는 값이 줄어들면 **"프로세스가 재시작했다"** 고 해석한다(counter reset). Prometheus의 `rate()` 함수는 감소를 감지하면 그 구간을 리셋으로 보고 보정한다. 그래서 애플리케이션이 임의로 값을 줄이면 **없던 재시작이 그래프에 찍힌다.**

그래서 규약이 강하다:

| | 규칙 | 예 |
|---|---|---|
| counter | 단조 증가만. 리셋은 프로세스 재시작 때만 | 총 질의 수, 총 에러 수 |
| gauge | 자유롭게 오르내림 | 현재 세션 수, 큐 길이 |

"현재 활성 트랜잭션 수"처럼 오르내리는 값을 counter로 만들면 안 되고, 반대로 "총 처리 건수"를 gauge로 만들면 rate를 계산할 수 없다. **이름이 아니라 성질로 고른다.**
</details>

**2.** slow query 임계값을 0으로 두면? 1시간으로 두면? 각각 무엇을 놓치나?

<details><summary>답</summary>

**0으로 두면** — 모든 질의가 기록된다.

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
if (durationMillis >= thresholdMillis)   // 0 이상이면 전부 참
```

놓치는 것: **아무것도 안 놓치지만, 로그 자체가 부하가 된다.** 초당 1만 질의를 처리하는 서버라면 초당 1만 줄이 쌓인다. 디스크·메모리를 먹고, 기록하는 시간이 질의 시간에 더해진다. **관측이 성능 문제를 만드는** 전형적인 자기모순이고, 그 안에서 정작 느린 질의를 찾을 수 없다(신호가 잡음에 묻힌다).

**1시간으로 두면** — 사실상 아무것도 안 남는다. 놓치는 것이 훨씬 중요하다:

- **누적 부하형 문제**를 못 본다. 200ms짜리 질의가 초당 1000번 돌면 서버가 죽는데, 하나하나는 임계값 근처에도 안 간다.
- 사용자가 체감하는 지연(수백 ms)이 전부 투명해진다.

**적절한 값은 "사용자가 느리다고 느끼는 지점"** 근처다 — 웹 요청이면 보통 100~500ms. 그리고 임계값 하나로는 부족해서, 실무에서는 **백분위수(p50/p95/p99)** 를 함께 본다. slow query log는 "개별 범인 찾기", 백분위수는 "전체 상태 파악"으로 역할이 다르다.

지금 `MetricsRegistry`에는 백분위수를 낼 수단이 없다(counter와 gauge뿐) — **히스토그램**이 필요하다.
</details>

**3.** slow query log가 무한히 쌓이면? 상한이 있나?

<details><summary>답</summary>

**상한이 없다.**

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
private val entries = mutableListOf<Entry>()
fun record(sql: String, durationMillis: Long) {
    if (durationMillis >= thresholdMillis) entries.add(Entry(…))   // 계속 추가만
}
```

지우는 코드도, 크기 제한도 없다. 느린 질의가 꾸준히 발생하면 **메모리가 계속 찬다.** 그리고 각 `Entry`가 SQL 문자열 전체를 들고 있어 하나가 수백 바이트~수 KB다.

며칠 뒤 일어나는 일: 힙이 차면서 GC가 잦아지고 → **DB가 느려지고** → 느린 질의가 더 많이 기록되고 → 더 빨리 찬다. **양의 되먹임**이다. 관측 장치가 장애의 원인이 되는 최악의 형태.

필요한 것 중 하나:

1. **고정 크기 순환 버퍼** — 최근 N개만 유지. 가장 단순하고 대개 충분하다.
2. **파일로 내보내고 메모리는 비우기** — 로테이션은 외부 도구(logrotate)에 맡긴다.
3. **집계만 보관** — 개별 질의 대신 "정규화된 질의 패턴별 횟수·평균 시간". `pg_stat_statements`가 이 방식이다.

3번이 실무에서 가장 유용하다. 파라미터만 다른 같은 질의 100만 건을 하나로 묶어 보여주기 때문이다.

**메모리에 무한정 쌓는 구조는 전부 같은 결말**이라는 점을 기억해둬라 — 16-01의 `restore`, 03-03의 `rangeScan`, 01-01의 `scanAll`이 전부 같은 계열이었다.
</details>

**4.** 여러 스레드가 동시에 counter를 올리면 정확한가? 코드에서 그것을 보장하는 부분을 짚어라.

<details><summary>답</summary>

**정확하다. 이미 보장되어 있다.**

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
private val counters = ConcurrentHashMap<String, AtomicLong>()
fun incCounter(name: String, delta: Long = 1) {
    counters.computeIfAbsent(name) { AtomicLong(0) }.addAndGet(delta)
}
```

두 겹이다:
- `ConcurrentHashMap.computeIfAbsent` — 같은 키에 대해 한 번만 생성되도록 원자적으로 처리
- `AtomicLong.addAndGet` — CPU의 CAS 명령으로 원자적 증가

`SlowQueryLog`는 방식이 다르다 — `@Synchronized`로 메서드 전체를 잠근다. 자료구조가 `mutableListOf`(비동기화)라 그렇게 해야 한다.

**만약 `Int`를 `++` 했다면** — `count++`는 실제로 세 단계다(읽기 → 더하기 → 쓰기). 두 스레드가 겹치면:

```
A: 읽음 100    B: 읽음 100
A: 101 씀      B: 101 씀     ← 두 번 올렸는데 101
```

**갱신이 유실된다.** 10-01 과제 4번의 lost update와 정확히 같은 구조이고, 계층만 다르다(DB 트랜잭션 vs CPU 레지스터).

여기서 볼 것 — 09-01의 `LockManager`는 `@Synchronized`가 있어야 안전했는데 테스트가 못 잡았다(과제 1번). 이 코드는 **처음부터 동시성 안전한 자료구조를 골랐다.** 락으로 감싸는 것보다 **애초에 안전한 도구를 쓰는 편**이 실수할 여지가 적다.
</details>

**5.** 이 metric들을 실제로 어디서 올려야 하나? `DbEngine.execute` 안인가, `ProtocolHandler` 안인가?

<details><summary>답</summary>

**둘 다에서 올리면 안 된다** — 한 질의가 두 번 세어진다. 한 곳을 골라야 한다.

`DbEngine.execute`가 맞다. 근거:

1. **모든 경로가 여기를 지난다.** `ProtocolHandler`(네트워크)뿐 아니라 `AdminCli`(단계 20), 테스트 코드, 향후 추가될 진입점까지 전부 `execute`를 부른다. `ProtocolHandler`에 두면 **네트워크로 온 질의만** 세어진다.
2. **측정 대상과 가깝다.** "질의 실행 시간"을 재려는 것이지 "네트워크 왕복 시간"을 재려는 게 아니다.

다만 **둘 다 필요한 경우도 있다** — 측정 대상이 다르기 때문이다:

```
ProtocolHandler:  연결 수, 인증 실패 수, 네트워크 포함 응답 시간
DbEngine:         질의 수, 질의 실행 시간, 테이블별 접근 수
```

이때 **이름으로 구분**한다. `counter.query.total`(엔진)과 `counter.connection.request.total`(핸들러)처럼. 같은 것을 두 이름으로 세는 게 아니라 **다른 것을 각자 세는** 것이면 문제가 없다.

원칙 한 줄: **하나의 사건은 한 곳에서만 센다.** 중복 계수는 "요청 수가 실제의 2배로 보이는" 형태로 나타나고, 알아채기 전까지 모든 판단이 틀어진다.
</details>

## 8. 다음 한계

관찰은 되지만 **DB가 하나뿐이다.** 그 서버가 죽으면 전부 멈추고, 읽기 부하를 나눌 방법도 없다.

→ **단계 18 Replication**.
