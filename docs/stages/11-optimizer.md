# Stage 11 — Query Planner + Optimizer

> **Status**: speculative
> **Must revalidate on entry**: 단계 6 Operator 인터페이스가 cost annotation을 지원하는지, 단계 3 Index가 statistics 노출 가능한지.
> **Known assumptions**: Operator 트리 존재. Index 존재. Catalog 존재.
> **Invalidation triggers**: Operator 모델 변경, Index API 변경.
> **Discard or rewrite if prior stage output differs**.

---

## 1. 깨지는 가정

- 단계 6은 사용자가 직접 SeqScan/IndexScan/Filter 트리를 구성. 사용자가 어떤 인덱스를 쓸지, join 순서를 어떻게 할지 직접 결정.
- 같은 SQL 결과를 내는 plan이 여러 개인데 비용 차이가 큼.

## 2. 후보 도입 객체

| 후보 | 책임 |
|------|------|
| `LogicalPlan` | 의미만 표현 (relational algebra) |
| `PhysicalPlan` | 실행 가능 (SeqScan vs IndexScan 등 구체) |
| `Statistics` | 테이블 row count, 컬럼 distinct count, histogram |
| `CostModel` | IO + CPU 비용 추정 |
| `Optimizer` | logical → physical 변환 + cost-based 선택 |
| `JoinOrderEnumerator` | join 순서 탐색 (DP) |

## 3. Candidate invariant

- **CI-1**: 같은 logical plan에 대해 optimizer는 결정적 plan 선택 (statistics 동일하면).
- **CI-2**: 선택된 physical plan은 logical plan과 의미적으로 동일 (결과 동일).
- **CI-3**: cost 추정은 monotonic (row 많아지면 cost 증가).

## 4. 가설값

| 항목 | 가설 |
|------|------|
| Optimizer 형태 | **Rule-based + Cost-based 하이브리드** — 규칙으로 logical → physical 변환, cost로 join 순서 선택 |
| Cost 단위 | IO (page read) + CPU (tuple 처리) |
| Statistics 수집 | ANALYZE 명령 + 자동 갱신 (단순) |
| Histogram | equi-width (단순) |
| Join 알고리즘 | nested loop + hash join (단순) — sort-merge는 옵션 |
| Join 순서 | DP for ≤10 tables, greedy beyond |

## 5. 후보 확인 질문

- Volcano / Cascades 같은 본격 framework vs 단순 rule + DP?
- Statistics 부재 시 fallback?
- Index-only scan 지원?
- Subquery / view inlining?

## 6. 위험

- Optimizer는 본격적으로 들어가면 학기 1개 분량. 단순화 필수.
- Cost 모델 부정확하면 좋은 plan 선택 못 함 — 디버깅 어려움.
- Single-machine 가정 (단계 21 sharding 없음).

## 7. 세션 분할 계획 (잠정)

| 세션 | 범위 (잠정) |
|------|------------|
| 11-01 | Statistics 수집 (ANALYZE) + 단순 histogram |
| 11-02 | Logical/Physical plan 분리 + 단순 변환 규칙 |
| 11-03 | Cost model + IndexScan vs SeqScan 선택 |
| 11-04 | Hash Join + Join 알고리즘 선택 |
| 11-05 | Join 순서 enumeration (DP) |

## 8. 참조 정책

- 주 참조: SimpleDB `LogicalPlanner`, `JoinOptimizer` (lab6).
- 대조 참조: BusTub `optimizer` (project 3 일부).
- 추가: Apache Calcite (full-fledged optimizer 참고).

## 9. 다음 단계의 동기

- 사용자가 항상 내부 API로 query 작성 — 표준 SQL 표면 필요 → **단계 12 SQL Parser thin layer**.

---

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 (speculative) |
