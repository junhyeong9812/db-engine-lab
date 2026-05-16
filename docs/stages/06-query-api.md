# Stage 06 — Internal Query API (Relational Algebra-like)

> **Status**: speculative
> **Must revalidate on entry**: 단계 4 Tuple/Schema, 단계 5 CHECK expression이 query expression과 통합되는지 확인.
> **Known assumptions**: Catalog + Tuple 존재. Index 존재.
> **Invalidation triggers**: Tuple immutability 변경, Type system 확장, index pointer 변경.
> **Discard or rewrite if prior stage output differs**.

---

## 1. 깨지는 가정

- 직접 API (`catalog.get("table").scan()`)는 표현력 작고 매번 코드.
- 의미 있는 query (filter, project, join) 표현 부족.
- 단계 12 SQL parser가 변환할 대상이 없음.

## 2. 후보 도입 객체

| 후보 | 책임 |
|------|------|
| `Operator` (interface) | `next(): Tuple?` 또는 `next(): Iterator<Tuple>` |
| `SeqScan` | 테이블 전체 스캔 |
| `IndexScan` | 단계 3 인덱스 활용 |
| `Filter` | predicate expression |
| `Project` | 컬럼 선택 |
| `Join` (nested loop) | 단순 NLJ — 단계 11에서 hash join 등 |
| `Expression` | column ref, literal, binary op, logical op |

## 3. Candidate invariant

- **CI-1**: SeqScan → 모든 tuple 정확히 한 번 반환.
- **CI-2**: Filter(pred, child) → child의 tuple 중 pred(tuple)=true인 것만.
- **CI-3**: 같은 query plan을 두 번 실행하면 결과 동일 (read-only invariant, 단계 9 lock 전).
- **CI-4**: Operator 트리는 lazy (volcano model — pull 기반).

## 4. 가설값

| 항목 | 가설 |
|------|------|
| 실행 모델 | Volcano (pull) — 단순. 단계 11에서 pipelined 검토 |
| Expression tree | sealed class (parser AST와 통합 가능 — codex 보정 2 진짜 닫힌 영역) |
| Type 강제 | runtime 검증 (Int + String 같은 위반은 sealed error) |
| Aggregation | 단계 6 안에서 (COUNT, SUM, AVG) 또는 별도 6.5 |
| Sort | 단계 11 optimizer에서 (또는 6에 inline) |

## 5. 후보 확인 질문

- Volcano vs vectorized — 학습 가치 vs 구현 비용 (BusTub은 두 가지 다 다룸).
- Expression evaluator는 Operator와 분리? 통합?
- Join은 nested loop만? hash/merge는 단계 11?
- Aggregation의 단계 위치 (6 vs 별도 7).

## 6. 위험

- Expression tree가 너무 일찍 풍부해지면 단계 12 SQL parser가 매핑 어려움.
- Volcano 결정은 단계 13 parallel execution과 충돌할 수 있음.
- "next() 한 번 호출에 tuple 1개"의 단순함이 batch operator (예: HashJoin)에서 깨짐.

## 7. 세션 분할 계획 (잠정)

| 세션 | 범위 (잠정) |
|------|------------|
| 06-01 | SeqScan + Operator interface |
| 06-02 | Filter + Expression (column ref, literal, comparison) |
| 06-03 | Project |
| 06-04 | IndexScan (단계 3 BTree 활용) |
| 06-05 | Join (nested loop) |
| 06-06 | Aggregation (COUNT, SUM) |

## 8. 참조 정책

- 주 참조: SimpleDB `OpIterator`, `SeqScan`, `Filter`, `Join`.
- 대조 참조: BusTub `executor`, `plan_node`.

## 9. 다음 단계의 동기

- 여러 변경을 묶는 단위 없음 → **단계 7 batch (논리적 작업 단위)**.
- 변경의 원자성·내구성 보장 없음 → **단계 8 WAL/Recovery**.

---

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 (speculative) |
