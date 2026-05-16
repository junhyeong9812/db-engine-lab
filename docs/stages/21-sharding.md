# Stage 21 — Sharding / Partitioning (Capstone)

> **Status**: speculative — **CAPSTONE**, 분산 시스템 학습으로 장르 전환. 진행 여부 신중 결정.
> **Must revalidate on entry**: 모든 이전 단계 안정성 + 학습자가 분산 시스템 학습에 들어갈 의사 확인.
> **Known assumptions**: 단계 18 read replica + Phase A 안정.
> **Invalidation triggers**: 분산 학습이 본 프로젝트 범위 외라 결정되면 건너뛰기.
> **Discard or rewrite if prior stage output differs**.

---

## 1. 깨지는 가정

- 한 머신의 storage·CPU·메모리 한계. 데이터 폭증 시 분할 필요.

## 2. 본 단계의 정직한 평가

**capstone 트랙. 본 프로젝트의 본질(단일 DB 처리 흐름 이해)에서 벗어남.** codex가 "분산 시스템 학습으로 장르 전환"이라 명시.

진행 여부는 학습자가 결정:
- 진행: capstone으로 의미 있음. 그러나 학습 비용 큼.
- skip: Phase B 단계 20까지로 본 프로젝트 종료.

## 3. 후보 도입 객체 (진행 결정 시)

| 후보 | 책임 |
|------|------|
| `Shard` | 데이터 분할 단위 |
| `ShardKey` | hash/range 분할 키 |
| `Router` | client query를 적절한 shard로 |
| `Coordinator` | cross-shard query 분산 + 결과 결합 |
| `DistributedTransaction` (2PC?) | cross-shard atomicity — 매우 복잡 |

## 4. Candidate invariant

- **CI-1**: shard key 기반 query는 단일 shard만 접근.
- **CI-2**: cross-shard query는 결과 일관 (eventually 또는 strongly).
- **CI-3**: shard failure 시 routing 격리.

## 5. 가설값

| 항목 | 가설 |
|------|------|
| 분할 전략 | Hash sharding 우선 (단순). Range는 옵션 |
| Cross-shard TX | 비목표 또는 2PC (학습 가치 vs 비용) |
| Re-sharding | 비목표 |
| Coordinator | 단일 coordinator (HA는 별도) |

## 6. 후보 확인 질문

- 본 단계를 정말 진행할지?
- 진행하면 어디까지 (단일 shard query만 vs cross-shard 포함)?
- 분산 합의 (Raft/Paxos) 필요한가?

## 7. 위험 (capstone)

- 분산 시스템 학습은 그 자체로 학기 1개 이상. db-engine-lab의 학습 목표 흐려짐.
- Cross-shard TX는 본격 분산 합의 필요.
- 실패 모드가 단일 머신과 완전히 다름.

## 8. 세션 분할 계획 (잠정 — 진행 결정 시)

| 세션 | 범위 (잠정) |
|------|------------|
| 21-01 | Hash sharding + Router (single-shard query만) |
| 21-02 | Cross-shard query (read only) |
| 21-03 | (옵션) 2PC for cross-shard write |
| 21-04 | (옵션) Re-sharding |

## 9. 참조 정책

- 주 참조: Citus 아키텍처 docs, Vitess docs.
- 대안: TiDB, CockroachDB 디자인 문서 (학습 가치 큼).

## 10. 다음 단계의 동기

- Phase B 종료. 본 프로젝트 본류 종료.
- 다음은 회고 (`docs/handoff/phase-b-summary.md`) + 별도 학습 트랙 결정.

---

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 (speculative) — capstone 결정 보류 |
