# Stage 05 — Integrity Constraints (PK / FK / UNIQUE / NULL / CHECK)

> **Status**: speculative
> **Must revalidate on entry**: 단계 4 catalog가 미리 metadata 확장을 의식했는지 (codex 이전 보정 3) 확인.
> **Known assumptions**: 단계 4 Catalog + Tuple 존재. 단계 3 Index 존재 (UNIQUE 검증에 활용).
> **Invalidation triggers**: catalog 구조 변경, index가 secondary index 미지원, NULL 표현 변경.
> **Discard or rewrite if prior stage output differs**.

---

## 1. 깨지는 가정

- Schema만으로는 "음수 가격", "중복 ID", "고아 record (FK 위반)"을 막을 수 없음.
- 사용자가 잘못된 데이터를 insert하면 단계 6 query가 의미 없어짐.

## 2. 후보 도입 객체

| 후보 | 책임 |
|------|------|
| `Constraint` (enum + handler) | PK, FK, UNIQUE, NOT_NULL, CHECK |
| `PrimaryKeyConstraint` | 한 테이블에 1개, 자동 UNIQUE + NOT_NULL |
| `ForeignKeyConstraint` | 참조 테이블 + 컬럼 |
| `UniqueConstraint` | 단일/복합 컬럼 |
| `CheckConstraint` | boolean expression (단계 6 query API의 expression 재사용?) |
| `ConstraintValidator` | insert/update 시 모든 constraint 검증 |

## 3. Candidate invariant

- **CI-1**: PK constraint 위반 insert는 reject, 데이터 변경 없음.
- **CI-2**: FK constraint 위반 insert/delete는 reject 또는 cascade (정책 가설).
- **CI-3**: UNIQUE constraint 위반은 reject (NULL은 허용 — SQL 표준).
- **CI-4**: Constraint 검증은 모든 mutation 경로에서 동일 (단계 7 transaction의 모든 변경 포함).

## 4. 가설값

| 항목 | 가설 |
|------|------|
| FK cascade 정책 | RESTRICT만 (CASCADE는 단계 5 외) |
| CHECK 표현식 | 단계 6 query API의 expression 재사용 (chicken-and-egg) |
| UNIQUE 구현 | 단계 3 BTree index 활용 (자동 인덱스 생성) |
| Deferred constraints | 비목표 (즉시 검증만) |

## 5. 후보 확인 질문

- CHECK constraint expression — 단계 6 query API 전에 만들지, 후에 만들지? (단계 순서 변경 필요?)
- FK는 단방향 (참조하는 쪽)만? 양방향 (참조되는 쪽 인덱스 자동 생성)?
- Constraint 위반은 sealed error (StorageError처럼)?
- UNIQUE 검증을 위한 index가 단계 3에서 secondary index 지원 안 하면? (단계 3 사전 작성 시 미리 고려)

## 6. 위험

- 단계 6 query API와 CHECK constraint가 상호 의존 — 단계 순서 흔들릴 수 있음.
- FK cascade는 트랜잭션 (단계 7+8) 없이는 부분 실행 위험.
- 모든 mutation 경로에 validator 끼우는 게 누락되면 invariant 깨짐.

## 7. 세션 분할 계획 (잠정)

| 세션 | 범위 (잠정) |
|------|------------|
| 05-01 | PK + NOT_NULL constraint (가장 단순) |
| 05-02 | UNIQUE constraint (index 활용) |
| 05-03 | FK constraint (RESTRICT only) |
| 05-04 | CHECK constraint (단순 expression, 단계 6 의존 시 보류) |

## 8. 참조 정책

- 주 참조: **참조 부재** (SimpleDB·BusTub 거의 안 다룸).
- 대안 참조: PostgreSQL constraint docs + 일반 RDBMS 교과서.
- Claude 자체 설계 비중 큰 단계.

## 9. 다음 단계의 동기

- 의미 있는 검색·필터 표현 필요 → **단계 6 Query API**.

---

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 (speculative) |
