# Handoff: Stage 16 (Backup) 완료

## 한 줄
Logical backup (SQL dump).

## 결정
- D-067: 논리 백업만 (CREATE + INSERT).
- D-068: 물리/PITR 비목표.
- D-069: Restore는 SQL parsing 안 함.

## 코드
- `backup.LogicalBackup`
