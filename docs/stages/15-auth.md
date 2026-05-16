# Stage 15 — Authentication + Authorization (RBAC)

> **Status**: speculative
> **Must revalidate on entry**: 단계 4 catalog의 metadata 확장 능력 (user/role 테이블 추가), 단계 12 SQL parser의 GRANT/REVOKE 지원.
> **Known assumptions**: Wire protocol 존재. Catalog가 시스템 테이블 확장 가능.
> **Invalidation triggers**: Catalog가 user/role 표현 불가, parser가 GRANT 미지원.
> **Discard or rewrite if prior stage output differs**.

---

## 1. 깨지는 가정

- 단계 14는 누구나 접근 가능. 권한 모델 없음.

## 2. 후보 도입 객체

| 후보 | 책임 |
|------|------|
| `User` | 식별 + 인증 정보 (password hash) |
| `Role` | 권한 묶음 |
| `Privilege` (enum) | SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, GRANT |
| `AuthManager` | login + permission check |
| `SystemCatalog` 확장 | `pg_user`, `pg_role`, `pg_privilege` 같은 테이블 |
| `GrantStatement` (parser AST 추가) | SQL 통합 |

## 3. Candidate invariant

- **CI-1**: 권한 없는 작업은 wire protocol 레벨에서 reject.
- **CI-2**: GRANT/REVOKE는 즉시 적용 + persist.
- **CI-3**: 기본 admin user 1개 부트스트랩.

## 4. 가설값

| 항목 | 가설 |
|------|------|
| Password hash | SHA-256 (학습용) — bcrypt/argon2는 비목표 |
| Auth 방식 | password만 (SSL/cert 비목표) |
| RBAC vs ACL | RBAC (학습 가치 큼) |
| 시스템 테이블 | catalog 확장 |

## 5. 후보 확인 질문

- Permission check 위치 (parser? executor? operator?)
- Role hierarchy 지원?
- Row-level security는 비목표 (확정)?
- Anonymous user 처리?

## 6. 위험

- Permission check 누락 시 silent 위반.
- Catalog 확장이 단계 4 사전 작성과 다르면 큰 변경.

## 7. 세션 분할 계획 (잠정)

| 세션 | 범위 (잠정) |
|------|------------|
| 15-01 | User + password auth + login |
| 15-02 | Privilege enum + permission check |
| 15-03 | Role + GRANT/REVOKE |
| 15-04 | System catalog 확장 |

## 8. 참조 정책

- 주 참조: PostgreSQL `pg_hba.conf` + RBAC 일반 패턴.
- 대조 참조: 없음.

## 9. 다음 단계의 동기

- 데이터 손실 복원 불가 → **단계 16 백업**.

---

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 (speculative) |
