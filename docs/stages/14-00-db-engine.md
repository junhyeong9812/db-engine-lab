# Stage 14-00 — DbEngine Facade (C2 보강)

> **Status**: implemented + verified
> 깨지는 가정: Catalog/TableHeap/Optimizer/Parser/Translator가 독립. end-to-end entry point 없음.

## 도입
- `engine.DbEngine(dataDir)`: SQL string → 모든 컴포넌트 통합 실행.
- `QueryResult` sealed: Rows/Updated/Created/Dropped.
- Per-table PagedFile/BufferPool/TableHeap 자동 관리.

## invariant
- CREATE → catalog 등록 + heap open.
- INSERT → SqlExpr literal → Tuple → heap.
- SELECT → Lexer→Parser→Translator→Optimizer→Executor.
- DROP → close + 파일 삭제.
- reopen 시 catalog 자동 복원, 기존 테이블 자동 open.

## 다음 한계
- Transaction 미통합 (programmatic only, SQL BEGIN/COMMIT 없음).
- 단일 thread/session.
