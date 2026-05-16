# Stage 14 — Wire Protocol (Phase B 진입)

> **Status**: speculative — Phase B 시작. 학습 layer 전환점.
> **Must revalidate on entry**: 단계 13 session/connection pool 모델 확인. Phase A 완료 권장.
> **Known assumptions**: 엔진이 in-process API로 정상 동작. 단계 12 SQL parser 또는 내부 query API.
> **Invalidation triggers**: Session 모델 변경, parser API 변경.
> **Discard or rewrite if prior stage output differs**.

---

## 1. 깨지는 가정

- 단계 13까지는 같은 JVM 안에서만 접근. 외부 process·다른 언어에서 호출 불가.

## 2. 후보 도입 객체

| 후보 | 책임 |
|------|------|
| `TcpServer` | 포트 listen + connection accept |
| `MessageFrame` | length-prefix + type + payload |
| `Protocol` (sealed) | Startup, Query, Parse, Bind, Execute, Sync, Terminate, ErrorResponse, RowDescription, DataRow 등 |
| `MessageHandler` | per-connection state machine |

## 3. Candidate invariant

- **CI-1**: client가 valid message 시퀀스를 보내면 정상 응답.
- **CI-2**: protocol 위반 시 client에 명확한 에러 + connection close.
- **CI-3**: 한 connection의 메시지는 순서 보장.

## 4. 가설값

| 항목 | 가설 |
|------|------|
| Protocol 형태 | PostgreSQL frontend/backend (학습 가치 + 기존 클라이언트 호환 가능) |
| TLS | 비목표 |
| Connection model | 단계 13의 thread-per-session |
| 메시지 직렬화 | byte-level (PostgreSQL 그대로 또는 단순화) |

## 5. 후보 확인 질문

- PostgreSQL protocol vs 자체 단순 protocol?
- Extended query protocol (Parse/Bind/Execute) 지원 여부?
- 인증 메시지 (단계 15와 통합)?

## 6. 위험

- PostgreSQL protocol 완전 호환은 큰 작업.
- Connection lifecycle 잘못하면 resource leak.

## 7. 세션 분할 계획 (잠정)

| 세션 | 범위 (잠정) |
|------|------------|
| 14-01 | TCP server + message framing |
| 14-02 | Simple query protocol (Query → result) |
| 14-03 | Startup + termination 핸드셰이크 |
| 14-04 | (옵션) Extended query (prepare/execute) |

## 8. 참조 정책

- 주 참조: [PostgreSQL frontend/backend protocol](https://www.postgresql.org/docs/current/protocol.html).
- 대조 참조: 없음.

## 9. 다음 단계의 동기

- 누구나 접근 가능 → **단계 15 인증/권한**.

---

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 (speculative) |
