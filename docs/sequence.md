# sequence.md — 실패하는 가정의 순서

> 이 문서는 living document. 각 단계 진입 시점에 갱신된다.
> "역사적 순서"가 아니라 **"이전 단계의 어떤 가정이 깨지면서 다음 단계가 등장하는가"** 의 순서.

---

## 현재 위치

**Phase A / 단계 1 ✅ Claude 검증 완료** — `./gradlew test` 3 PASSED. 단계 2 진입.
- 단계 1 코드: `src/main/kotlin/com/dbenginelab/storage/` (Record, StorageError, AppendOnlyFile).
- 단계 1 책: `impl/01-01-append-only-kv.md` (검증 완료 표시).
- handoff: `docs/handoff/stage-01-handoff.md` 작성.

워크플로: **P1 (책 저술)** — Claude가 21단계 전체 src/ 구현 + 빌드/테스트 검증 + impl/ 책 작성 → 완성 후 사용자 학습.
언어: **Kotlin 2.1 / JVM 21**. Gradle 8.14.4 + wrapper.
참조 정책: **옵션 A'**. `docs/reference-policy.md`.
세션 인계: 가장 최근 = `docs/handoff/stage-01-handoff.md`.

진행 상태 — **전체 완료. 89/89 tests PASSED.**

Phase A (1~13):
- [x] 1 storage / [x] 2 page+buffer / [x] 3 BTree (3-1/3-2/3-3)
- [x] 4 catalog / [x] 5 constraints / [x] 6 query API (6-1/6-2/6-3)
- [x] 7 WorkUnit / [x] 8 WAL+Recovery (진짜 ACID) / [x] 9-1 LockManager
- [x] 10 MVCC / [x] 11 Optimizer / [x] 12 SQL Parser / [x] 13 ConnectionPool

Phase B (14~21):
- [x] 14 wire / [x] 15 auth / [x] 16 backup / [x] 17 monitoring
- [x] 18 replication / [x] 19 online DDL / [x] 20 admin CLI / [x] 21 sharding stub

impl/ 26 책 (각 단계 검증 직후 작성 — (b) 사이클).
handoff/ 22 (stage-00 ~ stage-21).
issues-log 1건 (ISSUE-001 B+tree separator navigation).

---

## Phase A — 엔진 내부 (Engine Correctness)

산출물 기준: 정확성과 invariant 만족.

| # | 깨지는 가정 | 도입되는 객체·모델 | 검토 후보 (단계 진입 시 결정) |
|---|---|---|---|
| 1 | 메모리에만 두면 사라진다 | append-only file storage, `Record` | - |
| 2 | 큰 파일을 통째로는 못 다룬다 | Block/Page 단위 IO + 얇은 BufferPool | - |
| 3 | 풀스캔은 느리다 | Hash → BTree `Index` | - |
| 4 | 임의 byte는 의미 부여가 어렵다 | `Schema`, `Type`, `Catalog` | future metadata 확장 의식 (권한·통계·migration이 catalog를 요구함) |
| 5 | column 만으로는 무결성이 깨진다 | PK/FK/UNIQUE/NULL/CHECK 제약 | 5직후에 단순 schema versioning/migration 분리 검토 |
| 6 | 직접 API는 표현력이 작다 | 내부 query API (relational algebra-like), 단순 nested loop executor | - |
| 7 | 여러 변경을 묶는 단위가 없다 | "논리적 작업 단위" (batch) — **진짜 ACID 아님 명시** | - |
| 8 | 장애에서 atomicity·durability가 깨진다 | WAL (redo + undo) + recovery → **여기서 진짜 ACID Transaction이 됨** | 백업 도입 전 page format versioning/checksum/checkpoint 함께 잡기 |
| 9 | 단일 사용자만 가정한다 | `LockManager`, 2PL | - |
| 10 | 잠금만으로는 읽기 성능 한계 | MVCC, `Snapshot`, version chain | - |
| 11 | 항상 nested loop는 느리다 | `Planner`, `Statistics`, `Optimizer` | - |
| 12 | (옵션) 표준 표면이 부족하다 | SQL parser/AST → 내부 query API로 변환하는 thin layer | - |
| 13 | (옵션) 단일 스레드/단일 세션 가정 | BufferPool 튜닝, parallel execution, connection pool | wire protocol을 13.5에 minimal로 먼저 도입하는 옵션 검토 (Phase B 14의 protocol 제품화와 분리) |

---

## Phase B — 제품 운영 (Operational Usability)

산출물 기준: 운영 가능성.
**자연 발생하지 않으므로 의도적으로 단계화** (codex가 우려한 "자동 도달 환상"을 방지).

| # | 깨지는 가정 / 불편 | 도입되는 객체·모델 | 검토 후보 |
|---|---|---|---|
| 14 | 외부 프로세스가 접근할 수 없다 | wire protocol (TCP + 메시지 포맷 + 버전·호환) | 13.5에서 minimal TCP가 이미 들어왔다면 14에서는 제품화 |
| 15 | 아무나 접근할 수 있다 | 인증/권한 (user, role, GRANT/REVOKE) | 15 직전에 system catalog 확장 단계 검토 (권한은 parser/executor/planner 전체에 영향) |
| 16 | 데이터 손실 시 복원 불가 | 백업/복원 (논리 → 물리 → PITR) | 단계 8에서 storage format/version/checksum이 준비됐는지 확인. 안 됐으면 논리 백업으로 제한 |
| 17 | 운영 상태가 안 보인다 | 모니터링/메트릭/관측성 (slow query, lock wait, metric export) | - |
| 18 | 단일 인스턴스 장애 위험 | replication — **read replica only, no failover로 시작** | failover, async/sync 차이는 18+로 미루기. 본격 분산 시스템은 별도 트랙 |
| 19 | 스키마·데이터 진화 어려움 | online DDL (단계 5직후의 단순 migration 위에) | - |
| 20 | 운영자 도구 부재 | 관리 CLI / admin API | - |
| 21 | 한 머신 용량 초과 | partitioning / sharding | **capstone 트랙으로 분리 가능** — 분산 시스템 학습으로 장르 전환 시점 |

### Phase B에서 검토 후보 (아직 단계 미배치)

codex가 지적한 누락 항목 — 진행 중 어느 단계에 끼울지 결정:
- `configuration management`
- `startup/shutdown lifecycle`
- `structured logging / error codes`
- `version compatibility / rolling upgrade`

→ 14~17 사이에 별도 단계로 추가할지, 각 단계에 흡수할지는 Phase A 마무리 시점에 재검토.

---

## 단계 진행 규칙

1. 한 단계 진입 시 `docs/stages/NN-stage.md` 생성.
2. 그 단계의 **깨지는 가정 / 도입 모델 / 시나리오 / 다음 한계** 를 1파일에 정리.
3. naive 구현 → 깨지는 테스트 → 개선 → 다음 단계 동기 도출.
4. 이전 단계의 모델·문서가 깨지면 living doc로서 갱신 (decision-log에 이력 남김).
5. 단계는 건너뛸 수 있다 — 단, 건너뛴 이유는 decision-log에 기록.

---

## 변경 이력

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 — 21단계 (Phase A 13 + Phase B 8) + 검토 후보 메모 |
| 2026-05-16 | 언어 Kotlin/JVM 21 명시 (D-009), Phase overview 신설 — overview는 로드맵만 (D-013) |
