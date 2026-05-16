# Stage 08 — WAL + Recovery (진짜 ACID Transaction 시작점)

> **Status**: speculative — 본 단계는 가장 큰 변환점. 사전 작성 정확도 낮음.
> **Must revalidate on entry**: 단계 7 WorkUnit, 단계 2 Page format, 단계 4 catalog persistence 모두 대조 필수.
> **Known assumptions**: Page IO + WorkUnit 존재. 단계 5 constraint validator 동작.
> **Invalidation triggers**: page format에 LSN/version 자리 없음, WorkUnit이 in-memory undo 다르게 표현, catalog가 시스템 테이블 아님.
> **Discard or rewrite if prior stage output differs**.

---

## 1. 깨지는 가정

- 단계 7 WorkUnit은 process crash 후 atomicity/durability 보장 못 함.
- partial write로 page가 corrupted → 단계 1·2의 단순 checksum도 없음.
- 단계 7의 in-memory undo는 process 죽으면 사라짐.

## 2. 본 단계의 의미

**여기서부터 진짜 ACID Transaction이 정의된다.** 단계 7 WorkUnit이 단계 8에서 Transaction으로 승격.

## 3. 후보 도입 객체

| 후보 | 책임 |
|------|------|
| `LogRecord` (sealed) | BEGIN, COMMIT, ABORT, UPDATE(undo+redo), CLR | 진짜 닫힌 영역 — sealed OK |
| `LogManager` | append log + fsync 정책 + log buffer |
| `LSN` (Log Sequence Number) | 단조 증가, page header에 저장 |
| `Recovery` (ARIES 변형) | Analysis → Redo → Undo 3-pass |
| `Transaction` (단계 7 WorkUnit 승격) | begin/commit/abort + LSN 추적 |
| `Checkpoint` | 주기적 dirty page flush + active TX 기록 |

## 4. Candidate invariant

- **CI-1 (Atomicity)**: commit 또는 abort된 TX의 mutation은 all-or-nothing.
- **CI-2 (Durability)**: commit 직후 process kill → recovery 후 모든 commit visible.
- **CI-3 (WAL rule)**: 어떤 page도 그 page의 가장 최근 LSN보다 작은 log record가 fsync되기 전에 디스크에 쓰이면 안 됨.
- **CI-4 (Recovery)**: 어떤 crash 시점에도 recovery 후 DB 상태가 ACID 만족.

## 5. 가설값

| 항목 | 가설 |
|------|------|
| Recovery 알고리즘 | ARIES 변형 (Analysis → Redo from earliest dirty page LSN → Undo of loser TXs) |
| Log format | length-prefix + checksum + LSN + TX id + type + payload |
| Group commit | 단계 8 안에 — 단순 1-TX-1-fsync도 OK |
| Checkpoint 정책 | 시간 기반 (예: 30초) — 단순. fuzzy checkpoint는 미루기 |
| Undo type | physical (page+offset+old_value) 또는 logical (operation) — physical 우선 |

## 6. 후보 확인 질문

- ARIES 풀로 vs 단순 redo-only logging? (학습 가치 vs 비용)
- 단계 7의 WorkUnit을 그대로 승격 vs 새 Transaction 만들기?
- LSN을 page에 저장하면 page format 변경 — 단계 2 page에 LSN 자리 미리 둬야.
- Checkpoint 도중 active TX 처리.

## 7. 위험 (이 단계가 가장 큼)

- ARIES는 정확한 구현이 어렵고 학습 비용 큼. 학기 1개 수준의 분량.
- WAL rule 위반은 silent — 테스트하기 어려움. Crash 시뮬레이션 필수.
- 단계 9 lock과 강하게 얽힘 (recovery 시 lock 정보 복원).
- Checkpoint와 normal operation의 동시성 (단계 9 의존).

## 8. 세션 분할 계획 (잠정 — 가장 큰 단계)

| 세션 | 범위 (잠정) |
|------|------------|
| 08-01 | LogRecord format + LogManager + LSN |
| 08-02 | Transaction begin/commit + WAL append |
| 08-03 | Page에 LSN 저장 + WAL rule 강제 |
| 08-04 | Recovery — Analysis pass |
| 08-05 | Recovery — Redo pass |
| 08-06 | Recovery — Undo pass + CLR |
| 08-07 | Checkpoint |
| 08-08 | Crash 시뮬레이션 테스트 |

08-04~08-06은 ARIES 전체 구현이라 진입 시 분할 재검토.

## 9. 참조 정책

- 주 참조: SimpleDB `LogFile` (lab4 — 단순화된 ARIES).
- 대조 참조: BusTub `log_manager`, `recovery_manager` (project 4).
- 추가: ARIES 원논문 (Mohan et al. 1992) 추상 — 필요 시 참조.

## 10. 다음 단계의 동기

- 단일 사용자 가정 깨짐 → **단계 9 Lock (2PL)**.
- WAL은 있지만 동시 TX 간 격리 없음 → 단계 9.

---

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 (speculative) — 가장 큰 단계, 진입 시 분할 재검토 |
