# Stage 13 — Connection Pool + Parallel Execution + BufferPool Tuning

> **Status**: speculative
> **Must revalidate on entry**: 단계 9 LockManager의 thread safety, 단계 2 BufferPool의 concurrency 정책 확인.
> **Known assumptions**: 단일 스레드/단일 세션 가정으로 단계 12까지 동작.
> **Invalidation triggers**: LockManager의 thread model 변경.
> **Discard or rewrite if prior stage output differs**.

---

## 1. 깨지는 가정

- 단일 스레드 — 다중 client 처리 못 함.
- BufferPool, LogManager가 single-threaded 가정.
- BufferPool 크기·eviction이 튜닝 안 됨.

## 2. 후보 도입 객체

| 후보 | 책임 |
|------|------|
| `Session` | 한 client의 state (current TX, statement cache 등) |
| `ConnectionPool` | session reuse |
| `Executor` (thread pool) | parallel query 또는 multi-session |
| `BufferPoolTuner` | eviction 정책 동적 조정 |
| (옵션) Off-heap BufferPool | DirectByteBuffer / Foreign Memory API 21+ |

## 3. Candidate invariant

- **CI-1**: 두 session이 동시에 read해도 결과 일관.
- **CI-2**: BufferPool은 race condition 없이 page eviction.
- **CI-3**: LogManager append는 thread-safe (group commit 가능).

## 4. 가설값

| 항목 | 가설 |
|------|------|
| Thread model | Thread pool (coroutine 금지 — constraints.md) |
| Session 모델 | thread-per-session 또는 task-per-statement |
| BufferPool concurrency | striped lock 또는 lock-free (단계 13 안에서 결정) |
| Off-heap | 옵션 — 단계 13 안에서 실험 |
| Parallel query | 단순 inter-operator parallelism |

## 5. 후보 확인 질문

- thread-per-session vs task pool?
- BufferPool concurrency를 단계 9 lock과 통합? 별도 latch?
- Off-heap 도입 비용 vs 학습 가치?
- Wire protocol (단계 14) 들어오기 전에 session 만들 가치?

## 6. 위험

- 동시성 도입 시 silent race condition. JCStress 같은 도구 필요.
- BufferPool 변경은 단계 8 WAL의 fsync 정책과 충돌 가능.
- Off-heap은 학습 가치 큰데 디버깅 어려움.

## 7. 세션 분할 계획 (잠정)

| 세션 | 범위 (잠정) |
|------|------------|
| 13-01 | Session + ConnectionPool |
| 13-02 | BufferPool thread safety |
| 13-03 | LogManager thread safety + group commit |
| 13-04 | (옵션) Off-heap BufferPool |
| 13-05 | (옵션) Parallel query (inter-operator) |

## 8. 참조 정책

- 주 참조: 없음 (SimpleDB·BusTub 둘 다 약함).
- 대안: HikariCP (connection pool 패턴), PostgreSQL `pg_stat_activity`.

## 9. 다음 단계의 동기

- 외부 process에서 접근 불가 → **단계 14 Wire Protocol** (Phase B 진입).

---

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 (speculative) |
