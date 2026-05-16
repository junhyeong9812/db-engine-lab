# Phase B Overview — 제품 운영 (Operational Usability)

> 이 문서는 **로드맵**이지 **설계 확정문**이 아니다.
> 목표 / 제약 / 아직 모르는 것 / 재검토 조건만 적는다.
> 구체 API / 패키지명은 각 단계 진입 시 결정한다.
> (codex 보정 7)

---

## Phase B 목표

엔진이 동작하는 것을 넘어 **운영 가능한 시스템**으로 진화시킨다.
이 진화는 Phase A에서 자연 발생하지 않으므로, **의도적으로 단계화**한다.
(codex 지적 6 — "DBMS 내부 풀스택과 제품 풀스택의 자동 도달 환상" 회피)

| 강도 | 단계 | "운영 가능"의 정의 |
|------|------|-------------------|
| L1 | 14 | 외부에서 접근할 수 있다 |
| L2 | 15 | 권한 없는 접근을 막을 수 있다 |
| L3 | 16~17 | 장애 시 복원·관찰 가능 |
| L4 | 18~19 | 무중단 진화 가능 |
| L5 | 20~21 | 운영자가 안전하게 다룰 수 있다 / 확장 가능 |

---

## Phase B의 제약 (Phase 전체에서 유지)

- 단일 머신 또는 read-replica 수준의 단순 분산 (sharding은 capstone 검토 대상).
- TLS / 암호화는 비목표 (인증/권한은 평문 + 단순 hash).
- 호환성 검증은 자기 자신 버전 간만 (외부 DB와 호환 아님).
- 운영 자동화 도구 (Ansible / Terraform 등 IaC)는 비목표.
- GUI 관리 콘솔은 비목표 (CLI 전용).

---

## Phase B에서 **아직 모르는 것**

| 항목 | 결정 단계 | 결정 시점 입력 |
|------|----------|---------------|
| Wire protocol 모양 (text? binary? Postgres-like?) | 단계 14 | 학습 가치 vs 단순성 |
| 연결 모델 (per-connection thread? NIO? coroutine?) | 단계 14 | Phase A 13의 connection pool 결정과 정합 |
| 인증 방식 (password hash 알고리즘) | 단계 15 | bcrypt? argon2? 학습용 단순 SHA? |
| 권한 모델 (RBAC? ACL?) | 단계 15 | RBAC 권장 (학습 가치) |
| 백업 형식 (논리: dump? 물리: snapshot?) | 단계 16 | Phase A 8의 page format이 안정화됐는지 |
| PITR 가능 여부 | 단계 16 | WAL retention 정책 필요 |
| 메트릭 export 방식 (prometheus? 단순 JSON?) | 단계 17 | 학습 가치는 prometheus, 단순성은 JSON |
| Replication 방식 (logical? physical?) | 단계 18 | WAL shipping이 가장 단순 |
| Online DDL 범위 (어디까지 무중단?) | 단계 19 | ADD COLUMN만? DROP/ALTER까지? |
| 관리 CLI 명령 범위 | 단계 20 | startup/shutdown/backup/restore/user/grant 정도 |
| Sharding 전략 (range? hash? consistent hash?) | 단계 21 (만약 진행 시) | 분산 합의 필요 여부 |

---

## Phase B의 재검토 조건

다음 신호 발생 시 멈추고 재검토:

1. **Phase A의 변경이 Phase B 단계를 무효화할 때** — Phase A로 되돌아가 재설계.
2. **단계 14~17 중 어느 단계가 분산 시스템 학습으로 장르 전환을 일으킬 때** — 별도 트랙으로 분리.
3. **단계 21 sharding이 capstone이 아니라 본류로 들어와야 할 때** — 학습 목표 자체 재정의 필요.

---

## codex가 누락 지적한 항목 (단계 미배치)

진행 중 어느 단계에 끼울지 결정:

| 항목 | 후보 배치 |
|------|----------|
| Configuration management | 14 직후 |
| Startup/shutdown lifecycle | 14 또는 17과 합침 |
| Structured logging / error codes | 17에 흡수 또는 별도 |
| Version compatibility / rolling upgrade | 18~19 사이 별도 단계 |
| Packaging / deployment | Phase B 종료 시점 |

---

## Phase B 완료 조건 (의무 아님)

- Phase B 단계 14~20 통과 (21은 옵션).
- 외부 클라이언트에서 SQL 실행 (인증 통과 → 쿼리 → 결과 수신).
- 백업 → 복원 → 결과 일치 시나리오 통과.
- 단순 replication 검증 (primary write → replica에서 read 일치).
- Phase B 학습 회고 문서 작성.

---

## 변경 이력

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 (codex 보정 7) |
