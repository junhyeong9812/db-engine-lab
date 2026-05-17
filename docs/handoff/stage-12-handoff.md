# Handoff: Stage 12 (SQL Parser) 완료

## 한 줄
Hand-written recursive descent. SELECT/INSERT/CREATE/DROP.

## 결정
- D-054: Hand-written (ANTLR 미사용).
- D-055: SQL subset (subquery/window 비목표).
- D-056: AST sealed.
- D-057: 연산자 우선순위 — parseOr → parseAnd → parseCompare.

## 코드
- `sql.Token/Lexer/Ast/Parser`

## 다음 입력 (13)
- 단일 thread 가정 깨고 multi-session.
