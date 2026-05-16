# Stage 16 — Backup / Restore

> **Status**: speculative
> **Must revalidate on entry**: 단계 8 WAL의 segment 회전 정책, page format의 version/checksum 확인.
> **Known assumptions**: WAL + recovery 안정. Page format 안정.
> **Invalidation triggers**: Page format 변경, WAL retention 정책 변경.
> **Discard or rewrite if prior stage output differs**.

---

## 1. 깨지는 가정

- 데이터 손실 시 복원 불가. WAL은 recovery용이지 backup용 아님.

## 2. 후보 도입 객체

| 후보 | 책임 |
|------|------|
| `LogicalBackup` | SQL dump (CREATE + INSERT 스크립트) |
| `PhysicalBackup` | page 파일 snapshot + WAL segment |
| `PITR` (Point-In-Time Recovery) | base backup + WAL replay |
| `RestoreManager` | backup → live DB 변환 |

## 3. Candidate invariant

- **CI-1**: backup → restore 후 모든 commit된 데이터 동일.
- **CI-2**: PITR은 임의 timestamp까지 복원.
- **CI-3**: backup 도중 DB는 normal operation 가능 (online backup).

## 4. 가설값

| 항목 | 가설 |
|------|------|
| 우선순위 | 논리 backup → 물리 backup → PITR (3 sub-stage) |
| WAL retention | base backup 후 N일 |
| Hot vs Cold backup | hot 우선 (학습 가치) |

## 5. 후보 확인 질문

- 물리 backup의 consistency (page write 도중 snapshot)?
- WAL archive 정책 — 별도 디렉토리?
- 압축?

## 6. 위험

- 물리 backup은 단계 8 page format에 강하게 의존. 사전 작성 정확도 낮음.
- PITR은 WAL retention 정책과 함께 설계.

## 7. 세션 분할 계획 (잠정)

| 세션 | 범위 (잠정) |
|------|------------|
| 16-01 | 논리 backup (SQL dump) + restore |
| 16-02 | 물리 backup (page 파일 snapshot) |
| 16-03 | PITR (base + WAL replay) |

## 8. 참조 정책

- 주 참조: [SQLite Backup API](https://www.sqlite.org/backup.html) (단순), PostgreSQL `pg_dump`/PITR docs.

## 9. 다음 단계의 동기

- 운영 상태 안 보임 → **단계 17 모니터링**.

---

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 (speculative) |
