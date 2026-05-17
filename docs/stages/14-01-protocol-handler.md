# Stage 14-01 — ProtocolHandler (Wire+Auth+SQL 통합, C6 보강)

> **Status**: implemented + verified
> 깨지는 가정: wire Message + auth + engine이 따로. protocol 흐름 (Startup→Query→Terminate) 통합 없음.

## 도입
- `wire.ProtocolHandler(auth, pool, engine)`: per-connection state machine.
- `ConnectionEvent` sealed: Authenticated, AuthFailed, QueryResponse, Closed.
- Query result → wire Message list 변환 (RowDescription + DataRow * N + CommandComplete).

## invariant
- Startup → Auth 성공/실패 분기.
- Auth 전 Query → Error.
- Query 실행 중 예외 → Error 메시지로 client 응답 (connection 안 끊김).
- Terminate → session close.

## 다음 한계
- 진짜 TCP server (Socket accept loop) 없음 — handler만.
- TLS 없음.
- Long-running query 중 Terminate 처리 없음.
