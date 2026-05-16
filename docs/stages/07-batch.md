# Stage 07 — Logical Work Unit (Batch — Not Yet ACID)

> **Status**: speculative
> **Must revalidate on entry**: 단계 6 Operator의 mutation 경로 확인.
> **Known assumptions**: Operator 존재. mutation API (Insert/Update/Delete) 존재 또는 본 단계에서 도입.
> **Invalidation triggers**: Operator가 mutation을 다르게 표현, Constraint 검증 위치 변경.
> **Discard or rewrite if prior stage output differs**.

---

## 1. 깨지는 가정

- 여러 변경을 한 단위로 묶을 수 없음 → 중간에 실패하면 부분 적용.
- 사용자가 "이체" 같은 다중 mutation 시나리오 표현 불가.

## 2. 본 단계의 정직한 한계 (codex 보정 3 이전)

**"논리적 작업 단위"는 진짜 ACID Transaction이 아니다.**
- **atomicity**: 메모리상에서만 (process crash 시 rollback 불가).
- **isolation**: 단일 사용자 가정 (단계 9에서 lock).
- **durability**: 본 단계 없음 (단계 8 WAL).
- **consistency**: 단계 5 constraint 검증만.

이 단계를 거치는 이유: **진짜 ACID Transaction을 단계 8에서 정의하기 전에 "묶음 단위"의 형태가 필요**.

## 3. 후보 도입 객체

| 후보 | 책임 |
|------|------|
| `WorkUnit` 또는 `Batch` (Transaction이라 부르지 않음 — 단계 8에서 진짜 이름) | begin/commit/abort/추가 |
| `MutationLog` (in-memory) | abort 시 되돌리기 위한 in-memory undo |
| `WorkUnitId` | 단계 9 lock에서 사용할 가능성 |

## 4. Candidate invariant

- **CI-1**: commit 후 모든 mutation이 visible.
- **CI-2**: abort 후 모든 mutation이 visible 아님 (in-memory rollback).
- **CI-3**: WorkUnit 도중 process crash → 결과는 undefined (단계 8에서 정의).

## 5. 가설값

| 항목 | 가설 |
|------|------|
| In-memory undo | List<MutationRecord> — abort 시 역순 적용 |
| 동시 WorkUnit | 1개만 (단계 9에서 N개) |
| Name | `WorkUnit` 또는 `Batch` (Transaction은 단계 8에서) |

## 6. 후보 확인 질문

- "Transaction"이라는 이름을 단계 7에서 쓸까, 단계 8에서 쓸까? (혼동 위험)
- in-memory undo는 mutation type별 handler 필요 (Insert undo = Delete, Delete undo = Insert + restore data).
- abort 후 인덱스 일관성 어떻게 (인덱스도 mutation 대상)?

## 7. 위험

- "ACID처럼 보이지만 아니다"가 학습자에게 혼란. 명확히 이름·문서로 분리.
- in-memory undo를 너무 정교하게 만들면 단계 8 WAL undo와 중복.

## 8. 세션 분할 계획 (잠정)

| 세션 | 범위 (잠정) |
|------|------------|
| 07-01 | WorkUnit begin/commit (no abort, no concurrency) |
| 07-02 | In-memory undo + abort |

## 9. 참조 정책

- 주 참조: SimpleDB `Transaction` (lab3) — 단순 in-memory tx id.
- 대조 참조: BusTub `transaction_manager`.

## 10. 다음 단계의 동기

- crash 시 atomicity/durability 깨짐 → **단계 8 WAL + Recovery (진짜 ACID)**.

---

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 (speculative) |
