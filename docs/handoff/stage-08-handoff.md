# Handoff: Stage 08 (WAL + Recovery) 완료

## 한 줄
**진짜 ACID 시작.** LogManager + Transaction + Recovery (redo-only).

## 결정
- D-041: sealed LogRecord (BEGIN/INSERT/COMMIT/ABORT).
- D-042: Deferred-apply — insert는 WAL only, commit 시 sync + heap apply.
- D-043: Recovery는 redo-only + heap reconstruct (학습용 inefficient).
- D-044: partial trailing record는 EOF로 안전 처리.

## 코드
- `wal.LogRecord/LogManager/Transaction/TransactionManager/Recovery`

## 다음 입력 (9)
- Transaction에 lock 통합 (Strict 2PL). resource string key.
