# Stage 18 — Replication (Read Replica Only, No Failover)

> **Status**: speculative — 분산 시스템 학습으로 장르 전환 시점 (codex 이전 보정 5).
> **Must revalidate on entry**: 단계 8 WAL의 streaming 가능 여부, 단계 14 wire protocol의 replica 연결 지원.
> **Known assumptions**: WAL 안정. Wire protocol 안정.
> **Invalidation triggers**: WAL format 변경, wire protocol 변경.
> **Discard or rewrite if prior stage output differs**.

---

## 1. 깨지는 가정

- 단일 인스턴스 장애 = 전체 down. read 부하 분산 불가.

## 2. 본 단계의 범위 (codex 보정으로 축소)

- **Read replica only**.
- **No failover** (자동 승격은 별도 트랙).
- **단순 WAL shipping** (streaming은 옵션).
- **async replication** 우선 (sync는 옵션).

## 3. 후보 도입 객체

| 후보 | 책임 |
|------|------|
| `WalSender` | primary 측 — WAL segment 전송 |
| `WalReceiver` | replica 측 — WAL 수신 + apply |
| `ReplicaState` | LSN tracking, lag |
| `ReplicaRouter` | client가 read를 replica로 routing |

## 4. Candidate invariant

- **CI-1**: replica는 primary의 모든 commit을 eventually 반영.
- **CI-2**: replica에서 read는 stale 가능 (async).
- **CI-3**: replica는 write 거부.

## 5. 가설값

| 항목 | 가설 |
|------|------|
| Replication 모드 | Async only (sync 비목표) |
| 전송 단위 | WAL segment |
| Failover | 비목표 (수동 promotion 정도) |
| Conflict resolution | 비목표 (single primary) |

## 6. 후보 확인 질문

- Streaming vs file-based WAL shipping?
- Replica read consistency (read-your-writes)?
- 단계 14 wire protocol에서 replica routing 어떻게?

## 7. 위험

- 분산 시스템 학습으로 전환 — Phase B 단일 트랙 유지 어려움.
- Async replication의 data loss window.

## 8. 세션 분할 계획 (잠정)

| 세션 | 범위 (잠정) |
|------|------------|
| 18-01 | WalSender (file-based, polling) |
| 18-02 | WalReceiver + apply |
| 18-03 | Replica read routing |
| 18-04 | (옵션) Streaming WAL |

## 9. 참조 정책

- 주 참조: PostgreSQL streaming replication docs.

## 10. 다음 단계의 동기

- 스키마/데이터 진화 어려움 → **단계 19 Online DDL**.

---

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 (speculative) — codex 보정으로 read replica only, no failover 축소 |
