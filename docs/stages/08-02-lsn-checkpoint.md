# Stage 08-02 — LSN + Checkpoint + Idempotent Recovery (X2 + X5 + C5 보강)

> **Status**: implemented + verified
> 깨지는 가정:
> - 단계 8 LogManager는 LSN 없음 → 동일 record 두 번 apply 위험 (C5).
> - Backup이 일관 시점 없음 (X5).
> - WAL rule (pageLSN) 미정 (X2 부분 — page header LSN은 다음 보강 후보).

## 도입
- `wal.LogManager.append` 가 LSN 반환 (Long, monotonic).
- `wal.LogManager.replayWithLsn(handler: (Long, LogRecord) -> Unit)` 신규.
- `wal.LogRecord.Checkpoint(checkpointLsn, activeTxs)` 케이스 추가.
- `wal.CheckpointManager.checkpoint(activeTxs): Long` — checkpoint record 작성.
- `wal.IdempotentRecovery` — lastAppliedLsn 메타 파일로 두 번 호출해도 중복 apply 없음.
- `backup.PhysicalBackup` — checkpoint snapshot 기반 backup/restore.

## invariant
- LSN monotonic. reopen 시 file count로 복원.
- IdempotentRecovery 두 번 호출 시 첫 번째만 apply, 두 번째는 skip.
- Checkpoint record는 WAL에 영구 — `lastCheckpoint()` 로 조회.
- PhysicalBackup snapshot → restore → 데이터 동일.

## 다음 한계
- 진짜 pageLSN (page header 안) 없음 — page format 변경 필요 (단계 2 Page.kt 영향 큼).
- WAL rule (flush before page) 강제 안 됨.
