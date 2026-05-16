# reference-policy.md — 참조 자료 정책

> 옵션 A' 정책: Claude impl/ 작성 시 외부 검증 코드를 참조하여 **설계 검산**.
> **목적: "코드 이식"이 아니라 "설계 검산"** (codex 보정 7).
> Claude가 자유롭게 설계하되 검증된 교육 코드와 일치하는지 검산 도구로 사용.

---

## Phase A — 엔진 내부 참조 정책

### 기본 기준 (Primary)
**SimpleDB (Java)** — MIT 6.830 / Sciore 책 `Database Design and Implementation`

- 고정 버전 (Spot check 시 사용): 사용자가 단계 1 진입 시 commit hash 또는 책 판본 명시.
- 강점: 설명 중심, Java 교육 코드, 6 lab 구조.
- 한계: embedded educational DB. wire protocol/auth 등 Phase B 거의 안 다룸.

### 대조 참조 (Secondary)
**BusTub (C++)** — CMU 15-445

- 고정 버전: 단계 진입 시 학기 버전 commit hash 명시 (예: Fall 2023).
- 강점: DBMS 과제용 skeleton, 동시성·recovery·optimizer가 SimpleDB보다 깊음.
- 한계: C++ syntax 차이 (Kotlin 변환 직접 비교 어려움).

### 두 자료의 역할 차이 (codex 보정 6)
- **동등 병렬 참조 금지** — 선택 기준이 흐려짐.
- SimpleDB는 **설계 기준**, BusTub은 **대조 검증**.
- conflict 발생 시 SimpleDB 우선, BusTub은 "다른 선택이 있다" 정도.

### Phase A 단계별 참조 매핑 (잠정 — 단계 진입 시 정확화)

| 단계 | SimpleDB 매핑 (기본) | BusTub 매핑 (대조) | 참조 부재 |
|------|---------------------|-------------------|----------|
| 1 storage | `HeapFile`, `HeapPage` 단순화 | `disk_manager` | - |
| 2 page/buffer | `BufferPool`, `Page` 인터페이스 | `buffer_pool_manager`, `lru_replacer` | BusTub의 LRU-K는 학습 후반 검토 |
| 3 index | `BTreeFile`, `BTreePage` | `b_plus_tree` | - |
| 4 schema/catalog | `Catalog`, `TupleDesc` | `catalog`, `column` | 둘 다 매우 단순 — Claude 보강 필요 |
| 5 constraints | (SimpleDB 거의 안 다룸) | (BusTub 거의 안 다룸) | **참조 부재 — Claude 자체 설계** |
| 6 query API | `OpIterator`, `Operator` | `executor`, `plan` | - |
| 7 batch | `Transaction` | `transaction_manager` | 진짜 ACID는 단계 8 |
| 8 WAL/recovery | `LogFile`, ARIES 변형 | `log_manager`, `recovery_manager` | - |
| 9 locks (2PL) | `LockManager` | `lock_manager` | - |
| 10 MVCC | (SimpleDB 거의 안 다룸) | `transaction_manager` MVCC 확장 | BusTub의 HLL/MVCC 참조, **PostgreSQL docs도 참조** |
| 11 optimizer | `LogicalPlanner`, `JoinOptimizer` | `optimizer` | - |
| 12 SQL parser | (SimpleDB 매우 제한) | (BusTub 안 다룸) | **참조 부재 — Apache Calcite 패턴 참조** |
| 13 connection pool | (둘 다 안 다룸) | (둘 다 안 다룸) | **참조 부재 — Claude 자체 설계 + HikariCP 패턴 참조** |

---

## Phase B — 별도 reference policy (codex 보정 4)

Phase B는 BusTub/SimpleDB 커버리지가 거의 없음. **별도 reference policy 필수**.

| 단계 | 기준 자료 |
|------|----------|
| 14 wire protocol | [PostgreSQL frontend/backend protocol](https://www.postgresql.org/docs/current/protocol.html) |
| 15 인증/권한 | PostgreSQL `pg_hba.conf` docs + RBAC 일반 패턴 |
| 16 백업 | [SQLite Backup API](https://www.sqlite.org/backup.html) + PostgreSQL `pg_dump` / PITR docs |
| 17 모니터링 | PostgreSQL `pg_stat_*` 뷰 + Prometheus exporter 패턴 |
| 18 replication | PostgreSQL streaming replication docs |
| 19 online DDL | MySQL online DDL docs |
| 20 관리 CLI | PostgreSQL `psql` / MySQL CLI 패턴 |
| 21 sharding (capstone) | Citus / Vitess 아키텍처 docs |

---

## impl/NN-MM.md 표준 "0. 참조 출처" 양식

```markdown
## 0. 참조 출처

### 주 참조 (SimpleDB)
- 파일: `simpledb/storage/HeapFile.java`
- 클래스/메서드: `HeapFile.readPage(PageId)`
- 출처 commit: <hash> (또는 책 페이지)
- 우리 코드 대응: `AppendOnlyFile.readAt(offset)`

### 대조 참조 (BusTub) — 해당 시
- 파일: `src/storage/disk/disk_manager.cpp`
- 우리 코드와의 차이: ___
- 차이 채택 여부: 채택 안 함, 이유 ___

### 참조 부재 (해당 시)
- "참조 자료 없음. Claude 자체 설계."
- 대안 참조: ___ (Phase B는 reference-policy 표 참조)

### 핵심 설계 결정에는 근거 요약 1~3줄 (codex 보정 1)
```

---

## Spot check 정책 (codex 보정 5)

- **단계 1~6 (초반): 매 단계 사용자가 원본 확인**.
- **단계 7~13 (안정화): 3~5단계 간격**.
- 불일치 발견 시 `docs/decision-log.md`에 기록.
- handoff 문서에도 spot check 결과 누적 (`docs/handoff/stage-NN-handoff.md`).

---

## 변경 이력

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 — A' 정책 + 단계별 참조 매핑 + Phase B 별도 정책 |
