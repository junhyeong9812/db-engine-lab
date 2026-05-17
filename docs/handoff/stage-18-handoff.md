# Handoff: Stage 18 (Replication) 완료

## 한 줄
WAL shipping (read replica, no failover).

## 결정
- D-073: read replica only.
- D-074: Async only.
- D-075: WalSender List 반환.

## 코드
- `replication.WalSender`, `replication.WalReceiver`
