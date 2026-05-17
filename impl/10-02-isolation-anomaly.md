# impl/10-02 — Isolation Level + Anomaly Tests (X4 보강)

> **검증**: IsolationAnomalyTest 4 PASSED.

## 0. 보강 동기
codex 지적: Lock + MVCC 있어도 정확히 어떤 isolation level인지 라벨 없고, anomaly 검증 없음.

## 1. 우리 MVCC가 보장하는 isolation level
**Snapshot Isolation (SI)** — 단계 10에서 명시.

| Anomaly | 우리 모델 |
|---------|---------|
| Dirty Read | ✅ 방지 |
| Non-Repeatable Read | ✅ 방지 (snapshot 일관성) |
| Phantom Read | ✅ 방지 (snapshot 일관성) |
| Lost Update | ❌ 방지 못 함 (first-committer-wins 미구현) |
| Write Skew | ❌ 방지 못 함 (SSI 필요) |

## 2. 코드 — anomaly 검증 테스트

```kotlin
class IsolationAnomalyTest {
    @Test fun `dirty read 방지`() { ... }
    @Test fun `repeatable read`() { ... }
    @Test fun `phantom 방지`() { ... }
    @Test fun `lost update 미방지 - SSI 필요 (학습 데모)`() { ... }
}
```

## 3. 깨뜨릴 과제
- Lost update 방지하려면 — first-committer-wins 어떻게 구현? (insert 시 conflict 감지)
- Write skew 방지하려면 — SSI 어떻게? (read set tracking + dangerous structure)
- Serializable level 구현 비용?

## 4. 학습 포인트
SI는 4 anomaly 중 3 방지 + write skew 노출. PostgreSQL 9.1+ 의 SERIALIZABLE = SSI는 SI 위에 추가 검증.
