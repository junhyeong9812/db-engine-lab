# Stage 10-02 — Isolation Level + Anomaly Tests (X4 보강)

> **Status**: tests only — label + verify
> 깨지는 가정: Lock + MVCC 있어도 어떤 isolation level인지 라벨 없음.

## 우리 모델 = Snapshot Isolation (SI)
| Anomaly | 우리 모델 |
|---|---|
| Dirty Read | ✅ 방지 |
| Repeatable Read | ✅ 방지 |
| Phantom Read | ✅ 방지 |
| Lost Update | ❌ 미방지 (first-committer-wins 미구현) |
| Write Skew | ❌ 미방지 (SSI 필요) |

## 검증
- `IsolationAnomalyTest` 4 PASSED.
- Lost update는 학습용으로 명시적 실패 시연.

## 다음 한계
- SSI 구현으로 Serializable 보장 — read set tracking 필요.
