# Phase A Overview — 엔진 내부 (Engine Correctness)

> 이 문서는 **로드맵**이지 **설계 확정문**이 아니다.
> 목표 / 제약 / 아직 모르는 것 / 재검토 조건만 적는다.
> 구체 API / 패키지명 / 클래스명은 각 단계 진입 시 `docs/stages/NN-stage.md`에서 결정한다.
> (codex 보정 7 — overview가 설계 확정문처럼 굳지 않도록)

---

## Phase A 목표

DB 엔진이 **올바르게 동작하기 위해** 갖춰야 할 모든 핵심 메커니즘을 처음부터 구현하면서 이해한다.
"올바르게"의 기준은 단계마다 강해진다.

| 강도 | 단계 | "올바르게"의 정의 |
|------|------|------------------|
| L1 | 1~3 | 데이터를 잃지 않고 다시 읽을 수 있다 |
| L2 | 4~6 | 구조 있는 데이터를 의미 있게 다룰 수 있다 |
| L3 | 7~8 | 변경의 원자성·내구성이 보장된다 (진짜 ACID 시작) |
| L4 | 9~10 | 다중 사용자가 동시에 써도 깨지지 않는다 |
| L5 | 11~13 | 실제로 쓸 만한 성능을 낸다 |

---

## Phase A의 제약 (Phase 전체에서 유지)

- 단일 프로세스, 단일 JVM, 단일 파일 (또는 단일 디렉토리).
- 외부 접근 없음 (wire protocol은 Phase B).
- 인증·권한 없음 (모든 호출은 신뢰됨).
- Crash 가정: 정상 process kill / OS crash. **디스크 손상은 비목표**.
- 데이터 사이즈는 학습용 (수십만 record 수준).
- 성능 벤치마크는 비목표 (정확성이 우선).

---

## Phase A에서 **아직 모르는 것**

이 항목들은 단계 진입 시 결정·재검토된다:

| 항목 | 결정 단계 | 결정 시점에 사용할 입력 |
|------|----------|---------------------|
| Page size (4KB? 8KB? 16KB?) | 단계 2 | OS page size, BufferPool 크기, fsync 비용 |
| Page format (slot directory? heap?) | 단계 2 | tuple 가변 길이 처리 방식 |
| Index 분기 인수 (B-Tree fanout) | 단계 3 | page size에 따라 결정 |
| Catalog 저장 위치 (시스템 테이블? 별도 파일?) | 단계 4 | 권한/통계가 catalog를 요구할지 |
| Type system 범위 | 단계 4 | 어디까지 지원할지 |
| Query API 형태 (relational algebra? volcano executor?) | 단계 6 | SQL parser와의 분리 가능성 |
| Transaction ID 모델 (단조 증가? UUID? 64-bit?) | 단계 7 또는 8 | MVCC 단계의 snapshot 모델에 영향 |
| WAL record format | 단계 8 | recovery 알고리즘 (ARIES 변형?) |
| Lock granularity (row? page? table?) | 단계 9 | MVCC 도입 후 변경 가능 |
| MVCC 버전 chain 위치 (tuple 내부? 별도 저장?) | 단계 10 | vacuum 정책에 영향 |
| Optimizer 입력 (rule-based? cost-based?) | 단계 11 | statistics 수집 시점 |
| SQL subset 범위 | 단계 12 | DDL / DML / DCL / DQL 어디까지 |
| 멀티 세션 모델 (thread? coroutine? actor?) | 단계 13 | lock 구현과 직접 충돌 가능 |

---

## Phase A의 재검토 조건

다음 신호가 발생하면 Phase A 진행을 멈추고 sequence/decision-log를 재검토한다:

1. **이전 단계의 데이터 모델이 두 단계 이상 깨질 때** — 단순 갱신이 아니라 근본 재설계 필요.
2. **두 단계가 서로 강결합되어 분리 불가능할 때** — 단계 분할 자체가 잘못됐을 가능성.
3. **invariant가 명확히 정의되지 않은 단계로 진입할 때** — 그 단계의 학습 가치 불명확.
4. **codex가 동일 보정을 3회 이상 반복할 때** — 설계가 codex 모델과 학습 직관 사이에서 진동.
5. **사용자가 학습 동기를 잃을 때** — 시퀀스 압축 또는 우선순위 재정렬 검토.

---

## Phase A → Phase B 전환 조건

Phase A를 "완료"로 선언하는 기준 (의무는 아니지만 권장):
- 단계 1~11이 모두 통과 (12·13은 옵션).
- 한 SQL subset (또는 내부 query API)으로 end-to-end 시나리오 동작:
  `CREATE TABLE → INSERT → SELECT (with index) → BEGIN/COMMIT/ROLLBACK → crash → recover → SELECT 결과 일치`.
- 동시 트랜잭션 시나리오 1개 이상 검증 (lost update 방어, snapshot read).
- Phase A 학습 회고 문서 작성 (`docs/phase-a-retrospective.md`).

---

## 단계 간 관계 (high-level)

```
[1 storage] → [2 page/buffer] → [3 index]
                                    ↓
[4 schema/catalog] → [5 constraints] → [6 query API]
                                              ↓
[7 batch (not-yet-ACID)] → [8 WAL+recovery (real ACID)]
                                              ↓
[9 locks (2PL)] → [10 MVCC]
                       ↓
[11 optimizer] → [12 SQL] → [13 connection pool]
```

이 관계는 **점선 화살표**가 더 정확하다 — 단계는 건너뛸 수 있고, 이전 단계 결과가 후속 단계에서 갱신될 수 있다.

---

## 변경 이력

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 — 목표/제약/모르는 것/재검토 조건만 (codex 보정 7) |
