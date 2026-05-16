# Stage 17 — Monitoring / Metrics / Observability

> **Status**: speculative
> **Must revalidate on entry**: 단계 9 LockManager, 단계 11 Optimizer, 단계 13 BufferPool의 metric 노출 가능 여부.
> **Known assumptions**: 모든 핵심 컴포넌트가 instrumentation 가능.
> **Invalidation triggers**: 컴포넌트 인터페이스 큰 변경.
> **Discard or rewrite if prior stage output differs**.

---

## 1. 깨지는 가정

- 운영 중 무슨 일이 일어나는지 안 보임. slow query, lock wait, buffer hit rate 등.

## 2. 후보 도입 객체

| 후보 | 책임 |
|------|------|
| `Metric` (counter/gauge/histogram) | 기본 metric type |
| `MetricsRegistry` | 전체 metric 모음 |
| `SlowQueryLog` | threshold 초과 query 기록 |
| `SystemView` (`pg_stat_*` 패턴) | SQL로 조회 가능한 view |
| `StructuredLogger` | level + field-based log |

## 3. Candidate invariant

- **CI-1**: 모든 query는 trace ID 부여.
- **CI-2**: metric 수집은 normal operation 지연시키지 않음 (<1% overhead).
- **CI-3**: log는 structured (key-value), 사람이 grep 가능.

## 4. 가설값

| 항목 | 가설 |
|------|------|
| Metric 형식 | Prometheus exposition format |
| Log 형식 | JSON Lines 또는 logfmt |
| SlowQuery threshold | 가설 1초 (설정 가능) |
| Trace 표준 | OpenTelemetry는 비목표, 단순 trace ID |

## 5. 후보 확인 질문

- Prometheus export vs 자체 단순 JSON?
- system view를 단계 17에서 정의 vs 단계 15 catalog 확장?
- Configuration management (codex 누락 지적) 여기에 흡수?

## 6. 위험

- Metric overhead 측정 누락 시 production에서 문제.
- log volume 폭증.

## 7. 세션 분할 계획 (잠정)

| 세션 | 범위 (잠정) |
|------|------------|
| 17-01 | Metrics registry + counter/gauge |
| 17-02 | Slow query log + structured log |
| 17-03 | System view (pg_stat_activity 패턴) |
| 17-04 | (옵션) Prometheus exporter |

## 8. 참조 정책

- 주 참조: PostgreSQL `pg_stat_*` views + Prometheus exporter 패턴.

## 9. 다음 단계의 동기

- 단일 인스턴스 장애 위험 → **단계 18 Replication**.

---

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 (speculative) |
