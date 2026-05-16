# Stage 10 — MVCC (Snapshot Isolation)

> **Status**: speculative
> **Must revalidate on entry**: 단계 8 LogRecord/Transaction, 단계 9 LockManager의 X lock 의미 재정의 필요할 수 있음.
> **Known assumptions**: Transaction + Strict 2PL. Page format에 LSN.
> **Invalidation triggers**: TX id 모델 변경 (snapshot에 영향), page format 변경, undo log 형태 변경.
> **Discard or rewrite if prior stage output differs**.

---

## 1. 깨지는 가정

- 단계 9의 잠금은 read가 write를 block, write가 read를 block → 동시성 심각히 제한.
- 사용자가 "긴 read 쿼리"를 돌리면 writer가 모두 기다림.

## 2. 후보 도입 객체

| 후보 | 책임 |
|------|------|
| `Snapshot` | TX 시작 시점의 (commit timestamp, active TX list) |
| `TupleVersion` | 한 row의 여러 버전 chain |
| `UndoChain` (또는 page in-place + version) | 옛 버전 보관 |
| `VersionVisibility` | snapshot 기준 visibility 판단 |
| `Vacuum` (선택) | dead version 회수 (PG식). undo 기반이면 불필요. |

## 3. Candidate invariant

- **CI-1 (Snapshot Isolation)**: TX는 자기 snapshot 시점의 commit된 데이터만 봄.
- **CI-2 (Repeatable Read)**: 같은 read를 두 번 해도 같은 결과.
- **CI-3 (First-committer-wins)**: 동시 write conflict 시 한 TX만 commit.
- **CI-4**: read는 lock 안 잡음 (단계 9의 S lock 제거 또는 보존 둘 다 후보).

## 4. 가설값

| 항목 | 가설 |
|------|------|
| 구현 방식 | **PostgreSQL식 tuple versioning** (in-place 추가 + xmin/xmax) — 가설. Oracle/InnoDB식 undo log도 후보 |
| Snapshot 형태 | (txid_min, txid_max, active_xids) — PG식 |
| Conflict detection | 단계 10 안에서 first-committer-wins. SSI(Serializable Snapshot Isolation)는 옵션 |
| Vacuum | 단계 10 안에 단순 vacuum (background thread). 본격은 별도 |
| Read locks | SI에서는 불필요. 단계 9의 lock manager는 write conflict에만 |

## 5. 후보 확인 질문

- PG식 (tuple versioning + vacuum) vs Oracle/InnoDB식 (undo log + 별도 tablespace)?
- xmin/xmax 자리는 단계 4 tuple format에 미리 두는가, 단계 10에서 추가?
- HOT update (PG의 heap-only tuple) 도입할까? — 학습 가치 큼.
- SI vs SSI — SSI는 학습 가치 큰데 구현 복잡.
- Vacuum과 단계 9 lock의 동시성 (vacuum도 page 잠가야 함).

## 6. 위험

- Tuple versioning은 page format 영향이 큼. 단계 4 tuple format 미리 의식 안 했으면 큰 재작성.
- vacuum 누락 시 bloat 폭증 → 학습 프로젝트에서도 명백히 보일 정도.
- First-committer-wins detection 누락 시 silent corruption.

## 7. 세션 분할 계획 (잠정)

| 세션 | 범위 (잠정) |
|------|------------|
| 10-01 | xmin/xmax 추가 + visibility check |
| 10-02 | Snapshot 생성 + read 경로 |
| 10-03 | Write conflict detection (first-committer-wins) |
| 10-04 | 단순 Vacuum (foreground 또는 단계 13 background) |

## 8. 참조 정책

- 주 참조: **참조 부재** (SimpleDB·BusTub 둘 다 MVCC는 매우 단순 또는 안 다룸).
- 대안 참조: PostgreSQL MVCC 문서 + 책 *PostgreSQL Internals* (Suzuki).

## 9. 다음 단계의 동기

- 항상 nested loop / 풀스캔 — 인덱스가 있어도 plan 선택 안 됨 → **단계 11 Optimizer**.

---

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 (speculative) |
