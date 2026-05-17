# Handoff: Stage 14 (Wire Protocol) 완료

## 한 줄
Length-prefixed wire protocol (PostgreSQL 단순화).

## 결정
- D-061: Frame `[4 len][1 tag][payload]`.
- D-062: sealed Message (Startup/Query/Terminate/AuthOk/RowDesc/DataRow/CommandComplete/Error).
- D-063: TLS·extended query 비목표.

## 코드
- `wire.Message`, `wire.MessageCodec`

## 다음 입력 (15)
- 아무나 접근 → auth.
