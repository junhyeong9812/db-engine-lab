# Stage 19-02 — SchemaVersionLog (X3 보강)

> **Status**: implemented + verified
> 깨지는 가정: catalog version 없음, schema 변경 이력 없음. migration / replication 호환성 판단 불가.

## 도입
- `catalog.SchemaChange(version, timestamp, description)`.
- `catalog.SchemaVersionLog(path)`: append-only log + 자동 version 증가.

## invariant
- version monotonic 1,2,3,...
- reopen 시 파일에서 복원.
- 변경 이력 영구 (append-only).

## 다음 한계
- description 자동 migration application 없음.
- description에 tab/newline 포함 시 깨짐 (escape 필요).
