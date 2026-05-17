# Stage 09-02 — TransactionWithLock 통합 (C3 보강)

> **Status**: implemented + verified
> 깨지는 가정: 단계 8 WAL Transaction + 단계 9 LockManager가 따로. caller가 직접 묶어야.

## 도입
- `wal.TransactionWithLock`: insert/read 시 lock 자동 acquire, commit/abort 시 releaseAll.
- `wal.TransactionWithLockManager`: factory.

## invariant
- insert는 EXCLUSIVE lock 자동 (Strict 2PL).
- commit/abort 시 lockManager.releaseAll(txId) 자동.
- 두 tx 같은 table EXCLUSIVE → 두 번째 throw LockConflict.

## 다음 한계
- Row 단위 lock 미지원 (table 단위만).
- Lock timeout 없음, wait 없음 — 단계 9 단순화 그대로.
