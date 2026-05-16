# constraints.md — 가정·제약·비목표

> 현재 단계에서 **의도적으로 단순화한 가정**과 **이 프로젝트에서 다루지 않는 것**.
> Living document. 단계 진입 시 갱신.

---

## 전역 제약 (모든 단계 공통)

| 제약 | 값 / 정책 |
|------|----------|
| 언어 | **Kotlin 2.1+** |
| JVM target | **Java 21 LTS** (toolchain) |
| 빌드 도구 | Gradle 9 (Kotlin DSL) |
| 테스트 프레임워크 | JUnit 5 + `kotlin-test` |
| 실행 환경 | 단일 프로세스, 단일 JVM |
| 데이터 위치 | 단일 머신 로컬 파일 시스템 |
| SQL 지원 범위 | 제한 SQL subset (단계 12에서 정의) |
| 트랜잭션 모델 | 단계별로 다름 (1~7은 ACID 아님, 8 이후 ACID) |
| 동시성 모델 | 단계별로 다름 (1~8은 단일 사용자, 9 이후 다중) |
| 메모리 모델 | 단계 1~12 on-heap, 단계 13에서 off-heap 도입 검토 |
| Page size | 단계 2에서 결정 (가설값, 변경 가능) |
| Crash 가정 | 정상 process kill / OS crash (디스크 손상은 비목표) |

---

## Kotlin 사용 규칙 (codex 보정 1·2·3·6)

이 규칙은 "Kotlin의 우아함이 DB 내부의 저장소 현실을 숨기지 않도록" 만든다.

### `data class` 사용 제한
- **허용**: 도메인 메타데이터 (`ColumnDef`, `TableSchema`, `QueryPlan` 노드 등).
- **금지**: 저장소 핵심 객체 (`Page`, `Frame`, `Slot`, `Buffer`, `LogRecord`).
- **이유**: data class는 값 객체처럼 안전하게 다뤄지는 착각을 만듦. 실제 저장소는 aliasing, mutable buffer, dirty state가 핵심.
- **대안**: 저장소 객체는 일반 class + **explicit mutable API** (`mark()`, `pin()`, `unpin()`, `setDirty()`).

### `sealed class` 사용 제한
- **허용**: 진짜 닫힌 영역.
  - parser AST (`Expression`, `Statement`).
  - internal command (`Command.Insert`, `Command.Update` 등).
  - sealed error hierarchy (`DbError.Corrupt`, `DbError.NotFound`, `DbError.Conflict`).
- **금지**: 확장 지점.
  - `IndexType`, `WALRecord`, `LockMode`, `IsolationLevel` 등은 **interface** 또는 **enum + explicit handler**.
- **이유**: sealed는 "이 타입은 닫혔다"는 가정이 너무 빨리 들어옴. DB 학습에서 인덱스 타입/lock mode/isolation behavior는 단계가 진행되며 늘어남.

### null safety vs 상태 구분
- **nullable (`T?`) 허용**: 단순 조회 실패만 (`fun findById(id: Long): Row?`).
- **nullable 금지**: 다음 상태들은 **명시적 sealed error**로:
  - `missing page` (디스크에 있어야 할 페이지 부재)
  - `deleted tuple` (논리 삭제됨)
  - `uncommitted version` (다른 TX의 미커밋 버전)
  - `corrupt record` (체크섬 불일치)
  - `not yet flushed` (메모리에만 있음)
- **이유**: nullable로 뭉개면 "없음"과 "깨짐"의 의미 차이가 사라짐.

### `internal` modifier 정책
- **단일 모듈에서 `internal`은 사실상 무력하지만 의도 표현으로 유지**.
- 패키지 외부에서 호출하면 안 되는 함수/클래스는 명시적으로 `internal`.
- 테스트에서 internal 직접 접근 금지 — public surface로만 검증 (codex 보정 6).
- public surface는 각 패키지 `package-info.md` (단계 진입 시 작성)에 문서화.

### Coroutine 사용 정책
- **사용 금지** (학습 영역에서):
  - 단계 9 (LockManager): 동기 모델로 lock semantics를 직접 학습. coroutine으로 추상화하면 학습 가치 손실.
  - 단계 13 (parallel execution): 명시적 thread pool 사용. coroutine은 결정 후 도입.
- **허용 영역**: 단계 14 wire protocol의 connection handling (학습 가치보다 boilerplate 회피 효과 큼).

### 표현력 vs 학습 가치 판단 기준
- "이 Kotlin 기능이 학습 동기를 흐리지 않는가?" 가 1차 질문.
- 흐리지 않으면 사용, 흐리면 명시적 자바 스타일로 작성.

---

## 비목표 (이 프로젝트에서 다루지 않음)

### 엔진 정확성 면
- 실제 MySQL/Oracle/PostgreSQL의 byte-level 호환성.
- 산업용 성능 (throughput / latency 벤치마크 경쟁).
- 모든 SQL 표준 (subset만).
- 모든 데이터 타입 (기본 타입만).
- 멀티 데이터베이스/스키마/네임스페이스 (단일 catalog).
- character encoding의 완전한 처리 (UTF-8 가정).

### 제품 운영 면
- 분산 시스템 (sharding은 capstone 검토만).
- 멀티 데이터센터 / geo-replication.
- 디스크/파일시스템 손상 복구 (corruption detection은 단계 8 페이지 checksum 한정).
- 고급 보안 (encryption at rest, TLS, audit log).
- 인덱스 종류의 전체 (Hash, BTree만. GIN/GiST/BRIN 등 비목표).
- 트리거, 저장 프로시저, 사용자 정의 함수.
- 풀텍스트 검색.
- JSON / 공간 / 시계열 타입.

### 학습 외 영역
- 성능 튜닝 가이드 생성.
- 운영 매뉴얼 / 사용자 가이드.
- 패키징 (jar 배포, Docker 이미지) — 학습용으로만 실행.

---

## 가설값 (변경 가능)

단계 진입 시 결정·검증되는 값. 각 단계 문서에서 갱신.

| 항목 | 현재 가설 | 결정 단계 |
|------|----------|----------|
| Page size | 미정 (4KB 또는 8KB 후보) | 단계 2 |
| BufferPool 크기 | 미정 | 단계 2 |
| WAL segment 크기 | 미정 | 단계 8 |
| Lock granularity | row 단위 (잠정) | 단계 9 |
| MVCC 버전 보존 방식 | tuple versioning (PG계열 잠정) | 단계 10 |
| Catalog 저장 위치 | 별도 시스템 테이블 | 단계 4 |

---

## "안전망" 가정 (이 프로젝트가 의존하는 외부 보장)

- 파일 시스템의 `fsync` semantics가 정상 (POSIX fsync 약속 신뢰).
- OS page cache는 비활성화 가정 (또는 O_DIRECT 검토는 단계 8).
- JVM의 메모리 모델(JMM)이 standard semantics를 따름.
- TCP 동작은 정상 (단계 14의 wire protocol).

---

## 변경 이력

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 |
