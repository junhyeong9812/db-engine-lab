# Stage 19 — Online DDL

> **Status**: speculative
> **Must revalidate on entry**: 단계 4 catalog의 schema versioning 지원, 단계 9 LockManager의 DDL lock 모드.
> **Known assumptions**: Catalog 안정. Transaction 안정.
> **Invalidation triggers**: Catalog format 변경, lock mode 부족.
> **Discard or rewrite if prior stage output differs**.

---

## 1. 깨지는 가정

- 스키마 변경 시 테이블 전체 lock → 운영 중 변경 불가.
- 단계 5에서 (또는 사전에) 단순 schema migration 정도만 있음.

## 2. 후보 도입 객체

| 후보 | 책임 |
|------|------|
| `SchemaVersion` | 한 테이블의 schema 진화 이력 |
| `OnlineAddColumn` | NULL default 컬럼 즉시 추가 |
| `OnlineDropColumn` | metadata-only drop + lazy reclaim |
| `OnlineAlterColumn` | shadow column + copy + swap |
| `DDLLock` | minimal lock으로 DDL |

## 3. Candidate invariant

- **CI-1**: DDL 도중 normal query 계속 동작.
- **CI-2**: DDL 실패 시 schema rollback.
- **CI-3**: DDL은 transaction 안에서 atomic.

## 4. 가설값

| 항목 | 가설 |
|------|------|
| 지원 DDL | ADD COLUMN, DROP COLUMN (metadata-only), RENAME — 우선. ALTER TYPE은 비목표 |
| Lock mode | DDL은 별도 lock mode (intent + 짧은 X) |
| Shadow table | ALTER TYPE 같은 큰 변경 시 |

## 5. 후보 확인 질문

- ADD COLUMN with NOT NULL DEFAULT — 즉시 vs lazy?
- Index creation online?
- pt-osc / gh-ost 패턴 도입?

## 6. 위험

- Online DDL은 정확성 함정 많음.
- Shadow column 방식은 storage cost.

## 7. 세션 분할 계획 (잠정)

| 세션 | 범위 (잠정) |
|------|------------|
| 19-01 | ADD COLUMN (metadata-only) |
| 19-02 | DROP COLUMN (lazy reclaim) |
| 19-03 | (옵션) Online index creation |
| 19-04 | (옵션) ALTER TYPE with shadow column |

## 8. 참조 정책

- 주 참조: MySQL online DDL docs + pt-osc/gh-ost 패턴.

## 9. 다음 단계의 동기

- 운영자 도구 부재 → **단계 20 관리 CLI**.

---

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 (speculative) |
