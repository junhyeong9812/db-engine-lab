# Stage 12 — SQL Parser (Thin Layer) — Optional

> **Status**: speculative — 옵션 단계 (skip 가능)
> **Must revalidate on entry**: 단계 6 query API + 단계 11 logical plan과 매핑 가능한지.
> **Known assumptions**: 내부 query API + Optimizer 존재.
> **Invalidation triggers**: 내부 query API 모델 변경.
> **Discard or rewrite if prior stage output differs**.

---

## 1. 깨지는 가정

- 사용자가 내부 API로만 query 작성 → 표준 SQL 미지원.
- 다른 도구·CLI에서 표준 SQL을 받을 수 없음.

## 2. 본 단계가 옵션인 이유

학습 목표가 "DB 처리 흐름 이해"라면 SQL parser는 부수적. 단계 14 wire protocol 진입 직전에 필요. 학습자가 선택.

## 3. 후보 도입 객체

| 후보 | 책임 |
|------|------|
| `Lexer` | SQL → token |
| `Parser` | token → AST (sealed) — sealed OK (parser AST는 진짜 닫힌 영역) |
| `Validator` | AST + Catalog → 의미 검증 (table/column 존재) |
| `Translator` | AST → 단계 6 logical plan |
| `SqlExpression` | WHERE / SELECT expression |

## 4. Candidate invariant

- **CI-1**: parser는 valid SQL을 항상 같은 AST로.
- **CI-2**: AST → logical plan → physical plan → 결과는 내부 API 직접 호출과 동일.
- **CI-3**: SQL subset 외 입력은 명확한 에러 (silent ignore 금지).

## 5. 가설값

| 항목 | 가설 |
|------|------|
| 지원 SQL | SELECT (WHERE/JOIN/GROUP BY), INSERT, UPDATE, DELETE, CREATE/DROP TABLE, BEGIN/COMMIT/ROLLBACK |
| Parser 도구 | hand-written recursive descent (학습 가치). ANTLR/Apache Calcite는 비추 (학습 흐림) |
| 에러 메시지 | 위치 + 기대 token 명시 |
| Subquery | 비목표 (단계 12에서) |
| Window function | 비목표 |

## 6. 후보 확인 질문

- ANTLR로 가면 학습 가치 0. hand-written이 정직.
- SQL 표준 어디까지? PostgreSQL subset?
- 키워드 case sensitivity?
- Expression 우선순위 처리?

## 7. 위험

- Parser는 보일러플레이트가 많음 — 학습 가치 낮은 코드 비중 큼.
- AST → logical plan 매핑이 잘못되면 silent.
- subquery는 단계 12 안에서 폭증 위험.

## 8. 세션 분할 계획 (잠정)

| 세션 | 범위 (잠정) |
|------|------------|
| 12-01 | Lexer (token + keyword) |
| 12-02 | Parser — SELECT 기본 (FROM, WHERE) |
| 12-03 | Parser — INSERT/UPDATE/DELETE |
| 12-04 | Parser — DDL (CREATE/DROP TABLE) |
| 12-05 | AST → logical plan translator |

## 9. 참조 정책

- 주 참조: SimpleDB `Parser` (매우 단순).
- 대조 참조: 없음 (BusTub은 SQL parser 안 다룸).
- 대안: Apache Calcite — 학습 가치보다 참고로만.

## 10. 다음 단계의 동기

- 단일 스레드/단일 세션 가정 → **단계 13 Connection Pool**.

---

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 (speculative) — 옵션 단계 |
