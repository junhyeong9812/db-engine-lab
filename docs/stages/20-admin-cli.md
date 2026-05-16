# Stage 20 — Admin CLI / Management API

> **Status**: speculative
> **Must revalidate on entry**: 단계 14 wire protocol, 단계 15 auth, 단계 16 backup, 단계 17 monitoring API 확인.
> **Known assumptions**: Wire protocol + 운영 기능들 안정.
> **Invalidation triggers**: 운영 API 변경.
> **Discard or rewrite if prior stage output differs**.

---

## 1. 깨지는 가정

- 운영자가 DB를 다루는 표준 도구 없음 — backup/restore/user 관리 등.

## 2. 후보 도입 객체

| 후보 | 책임 |
|------|------|
| `AdminCli` | psql-like CLI |
| `AdminCommand` (sealed) | BACKUP, RESTORE, CREATE USER, GRANT, SHOW STATUS 등 |
| `CommandRouter` | wire protocol 또는 별도 admin port |
| `Configuration` | 런타임 설정 변경 (codex 누락 지적 흡수) |

## 3. Candidate invariant

- **CI-1**: admin 작업은 audit log 기록.
- **CI-2**: 권한 없는 admin 명령은 reject.
- **CI-3**: CLI 종료 후 state는 명시적 commit 후만 반영.

## 4. 가설값

| 항목 | 가설 |
|------|------|
| 인터페이스 | CLI (별도 binary) + SQL 명령 (CREATE USER 등) |
| Auth | 단계 15 활용 |
| Config 형식 | YAML 또는 SQL setting |
| Lifecycle 관리 | startup/shutdown (graceful) |

## 5. 후보 확인 질문

- Admin port 별도 vs 일반 wire protocol?
- Config는 runtime 변경 가능 vs restart 필요?
- Graceful shutdown 타임아웃?

## 6. 위험

- CLI 만드는 데 시간 큼 — 학습 가치는 그 자체보다 시스템 통합.
- Config management 누락 시 운영 불가.

## 7. 세션 분할 계획 (잠정)

| 세션 | 범위 (잠정) |
|------|------------|
| 20-01 | CLI 골격 + 연결 |
| 20-02 | Admin commands (BACKUP, USER) |
| 20-03 | Configuration management |
| 20-04 | Startup/shutdown lifecycle |

## 8. 참조 정책

- 주 참조: PostgreSQL `psql`, MySQL CLI 패턴.

## 9. 다음 단계의 동기

- 한 머신 용량 한계 → **단계 21 Sharding (capstone)**.

---

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 (speculative) |
