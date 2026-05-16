# Stage 04 — Schema, Type, Catalog

> **Status**: speculative
> **Must revalidate on entry**: 단계 1·2·3의 ByteArray-based record/page/index가 typed key/value로 어떻게 마이그레이션될지 확인 필수.
> **Known assumptions**: 단계 1~3은 ByteArray 기반. 의미 부여는 단계 4에서 시작.
> **Invalidation triggers**: index entry 형태 변경, PageId 구조 변경, record 직렬화 방식 변경.
> **Discard or rewrite if prior stage output differs**.

---

## 1. 깨지는 가정

- raw ByteArray는 의미 없음. 사용자는 "id는 INT, name은 STRING" 같은 구조를 원함.
- 한 파일에 여러 테이블 못 둠. 다중 테이블 관리 필요.

## 2. 후보 도입 객체

| 후보 | 책임 |
|------|------|
| `Type` (sealed) | INT, BIGINT, STRING, BYTES (확장 가능) — sealed 사용 위험 (constraints.md), enum+handler 검토 |
| `ColumnDef` (data class OK — 도메인 메타) | name, type, nullable |
| `TableSchema` | List<ColumnDef> + table name |
| `Catalog` | TableSchema 전체 관리 + persistence |
| `Tuple` | TableSchema + 값 배열, 직렬화/역직렬화 |

## 3. Candidate invariant

- **CI-1**: Catalog에 등록된 모든 테이블은 reopen 후에도 동일 schema로 복원.
- **CI-2**: Tuple 직렬화 → 역직렬화 라운드트립이 동일.
- **CI-3**: TableSchema와 실제 page 내용의 컬럼 수가 일치 (단계 5 constraints에서 강화).

## 4. 가설값

| 항목 | 가설 |
|------|------|
| Type 표현 | enum + per-type handler (sealed의 확장성 문제 회피 — codex 보정 2) |
| Catalog 저장 | 별도 시스템 테이블 (`__catalog__`) — 자기 자신을 dogfood |
| Tuple 직렬화 | 컬럼 순서대로 length-prefix (단계 1 record format 확장) |
| NULL 표현 | bitmap 또는 sentinel 값 |
| Schema 변경 | 단계 4에서 안 다룸 (단계 19 online DDL) |

## 5. 후보 확인 질문

- Type을 sealed vs enum vs 별도 class hierarchy 어떻게? (Kotlin 사용 규칙 검토)
- Catalog를 별도 파일로 vs 시스템 테이블로? (시스템 테이블이면 chicken-and-egg — 시스템 테이블 schema는 어디?)
- Tuple은 immutable value vs mutable builder? (codex 보정 1 — page는 mutable, tuple은 ?)
- Multi-file 도입 시 PageId 구조 변경 — file_id 어떻게 부여?

## 6. 위험

- Type system이 너무 크면 단계 4가 무거워짐. INT + STRING 정도로 시작.
- Catalog를 dogfood로 만들면 부트스트랩 까다로움. 별도 JSON/text 파일도 후보.
- Schema 변경 (online DDL)은 강제로 단계 19로 미룸 — 단계 4에서 다루면 폭증.

## 7. 세션 분할 계획 (잠정)

| 세션 | 범위 (잠정) |
|------|------------|
| 04-01 | Type 시스템 (INT, STRING) + ColumnDef + TableSchema |
| 04-02 | Tuple 직렬화/역직렬화 |
| 04-03 | Catalog (등록/조회/persistence) |
| 04-04 | (옵션) 시스템 테이블로 dogfood |

## 8. 참조 정책

- 주 참조: SimpleDB `Catalog`, `TupleDesc`, `Tuple`.
- 대조 참조: BusTub `catalog`, `column`, `schema`.
- 둘 다 매우 단순. PostgreSQL `pg_class`, `pg_attribute` 패턴도 참고 가능.

## 9. 다음 단계의 동기

- Schema만으론 무결성 안 됨 (음수 가격, FK 위반 등) → **단계 5 Constraints**.
- 의미 있는 query 표현 부족 → **단계 6 Query API**.

---

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 (speculative) |
