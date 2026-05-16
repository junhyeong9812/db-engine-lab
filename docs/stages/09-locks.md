# Stage 09 — Lock Manager (2PL)

> **Status**: speculative
> **Must revalidate on entry**: 단계 8 Transaction의 LSN과 lock 정보가 어떻게 통합되는지 확인. 단계 2 BufferPool의 pin/unpin과 lock의 관계.
> **Known assumptions**: Transaction (단계 8) 존재. WAL이 진짜 ACID 보장.
> **Invalidation triggers**: Transaction lifecycle 변경, BufferPool pin 의미 변경, page id 구조 변경.
> **Discard or rewrite if prior stage output differs**.

---

## 1. 깨지는 가정

- 단일 사용자 가정 (단계 7·8까지). 동시 TX 도입하면 lost update, dirty read, phantom 발생.
- 단계 2 BufferPool pin은 page eviction 보호용일 뿐, TX 간 격리 안 함.

## 2. 후보 도입 객체

| 후보 | 책임 |
|------|------|
| `LockMode` (enum) | S (shared), X (exclusive), IS, IX, SIX (intent locks) |
| `LockManager` | TX × resource → mode 관리 |
| `WaitForGraph` | deadlock detection |
| `LockRequest` | TX가 요청한 lock + wait queue |
| `Latch` (lock과 다름) | page/data structure 보호용 단기 lock (B+tree split 등) |

## 3. Candidate invariant

- **CI-1 (2PL)**: TX가 lock release 후 추가 lock 획득 금지 (Strict 2PL은 commit/abort 시까지 유지).
- **CI-2**: 서로 호환되지 않는 lock mode는 동시 보유 불가.
- **CI-3 (Deadlock)**: deadlock 감지 시 한 TX abort (단계 8 abort 활용).
- **CI-4 (Lock granularity)**: row/page/table 일관된 escalation (가설).

## 4. 가설값

| 항목 | 가설 |
|------|------|
| 2PL 형태 | **Strict 2PL** (commit/abort까지 lock 유지) — 가장 단순 |
| Lock granularity | **row 단위** (page intent lock과 함께) |
| Deadlock 정책 | wait-for graph + victim selection (youngest TX abort) |
| Lock timeout | 가설값 5초 (단계 13에서 튜닝) |
| Multi-granularity | IS/IX/SIX 도입 (BusTub 따름) |

## 5. 후보 확인 질문

- Strict 2PL vs basic 2PL? (학습 가치 vs 단순성)
- Row lock + page intent lock 조합 정확히 구현 가능한가?
- Deadlock detection 주기 vs lock timeout (wait-die / wound-wait)?
- Latch와 Lock의 분리 명확한가? (B+tree split의 latch crabbing 필요 시점)
- Coroutine 금지 (constraints.md) — Thread.sleep / wait/notify 어떻게?

## 6. 위험

- Deadlock detection은 정확하지만 비용. wait-die가 단순.
- Latch crabbing은 단계 3 BTree에서 미리 안 했으면 여기서 재작성.
- Lock granularity escalation 잘못하면 deadlock 폭증.

## 7. 세션 분할 계획 (잠정)

| 세션 | 범위 (잠정) |
|------|------------|
| 09-01 | LockManager S/X (table 단위) |
| 09-02 | Multi-granularity (IS/IX/SIX, row 단위 escalation) |
| 09-03 | Deadlock detection (wait-for graph) |
| 09-04 | Strict 2PL 통합 + Transaction 연동 |
| 09-05 | (옵션) Latch crabbing for BTree |

## 8. 참조 정책

- 주 참조: SimpleDB `LockManager` (lab3 일부).
- 대조 참조: BusTub `lock_manager` (project 3 — multi-granularity 강함).

## 9. 다음 단계의 동기

- 잠금은 강한 격리 보장하지만 reader/writer 동시성 약함 → **단계 10 MVCC**.

---

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 (speculative) |
