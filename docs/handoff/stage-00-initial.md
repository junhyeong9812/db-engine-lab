# Handoff: Stage 00 (문서 설계) 완료 시점

> 작성일: 2026-05-16
> 직전 handoff: 없음 (프로젝트 시작점)
> 다음 단계: 1 (append-only file storage)

## 0. 한 줄 요약

문서 설계 + Kotlin Gradle 골격 완료. **stages 21개 모두 사전 작성** (codex 안전장치 헤더 강제). 21단계 시퀀스(Phase A 13 + Phase B 8) + 옵션 A'(Claude impl/ + SimpleDB·BusTub 참조 검산) 정책 확정. impl 본문은 `01-01-append-only-kv.md` 1개만 작성, 나머지는 단계 진입 시 생성. 코드는 아직 없음.

## 1. 결정된 사항 (누적)

- D-001 ~ D-013 (`docs/decision-log.md` 참조). 핵심:
  - **D-002**: 진행 방식 = "실패하는 가정의 순서".
  - **D-003**: 21단계 (Phase A 13 + Phase B 8).
  - **D-005**: 데이터 모델·인터페이스는 living doc (가설 표시).
  - **D-009**: 언어 = Kotlin 2.1 + JVM target 21. Kotlin 학습은 in-flight.
  - **D-010**: 워크플로 = Claude impl/ 따라치기 + 보정책 5개.
  - **D-014 (이번)**: 옵션 A' — SimpleDB 기본 / BusTub 대조 참조 강제. "설계 검산" 목적. `reference-policy.md` 참조.
  - **D-015 (이번)**: 페이즈/단계 단위 handoff 문서 도입.

## 2. 만족하는 invariant (누적)

현재 단계 0이므로 코드 invariant 없음. 메타 invariant만:
- **M-1**: 모든 impl/ 세션은 `0. 참조 출처` 섹션 포함.
- **M-2**: 모든 단계 진입 시 `docs/stages/NN-stage.md` 먼저 작성 후 `impl/NN-MM.md`.
- **M-3**: 단계 완료 시 `stage-NN-handoff.md` 신규 작성.
- **M-4**: 데이터 모델·page format·인터페이스 시그니처는 "가설" 표시 (변경 가능).

## 3. 사용한 참조 출처

해당 없음 (문서 설계 단계). 단계 1부터 시작.

## 4. 핵심 코드 위치

코드 없음. Gradle 골격만:
- `build.gradle.kts` (Kotlin 2.1, JVM 21, JUnit 5)
- `settings.gradle.kts` (rootProject = "db-engine-lab")
- `src/main/kotlin/com/dbenginelab/storage/` (빈 패키지)
- `src/test/kotlin/com/dbenginelab/storage/` (빈 패키지)

문서:
- `docs/stages/01-storage.md` ~ `21-sharding.md` (21개 사전 작성, 02~21은 speculative)
- `impl/01-01-append-only-kv.md` (첫 따라치기 세션)

## 5. 깨진 가설 / 갱신된 결정

세션 중 다음 결정들이 흔들리고 재정의됨:
- **언어**: Java → Kotlin → (자바 재검토) → Kotlin 최종 (codex 보정으로 "Kotlin 규칙이 자바에도 필요" 확인).
- **워크플로**: A → A+H2 하이브리드 → D(SimpleDB 변환) → **A'(A + 참조 검산)** 최종.
- **단계별 문서 구조**: 4파일 → 1파일 → docs/stages + impl/NN 분리 + handoff/ 추가.
- **시퀀스 명명**: "역사 순서" → **"실패하는 가정의 순서"** (codex 보정).
- **Phase B 통합 여부**: 통합 → 분리 검토 → **통합 유지** (A' 정책으로 가능).

## 6. 사용자가 막혔던 지점 (학습 메모)

- (단계 0 — 본격 학습 시작 전이므로 학습 메모 없음)
- 다만 **학습 스타일이 결정됨**:
  - Kotlin은 in-flight 학습 (별도 학습 예산 없음).
  - 따라치기 + Q/A 주석 패턴 선호.
  - codex 보정으로 Q/A는 "위험 줄·상태 변경·분기·invariant·예외" 줄에 집중하기로.

## 7. Spot check 결과

해당 없음 (참조 사용 안 함).

## 8. 다음 단계가 받아야 할 입력

### 단계 1 진입 시 결정해야 할 것
- 첫 세션 (`impl/01-01-*.md`) 범위:
  - 최소 단위: byte array record + append + scan
  - SimpleDB의 `HeapFile` 단순화 버전
- Record 직렬화 가설: `[keyLen:4][key][valueLen:4][value]` (변경 가능).
- `RandomAccessFile` vs `FileChannel` vs `Files.newByteChannel` 결정.

### 단계 1이 깨야 할 가정 (단계 2의 동기)
- 큰 파일을 통째로 읽는 건 비효율.
- partial write·crash 처리 안 됨.
- 페이지 단위 IO 필요.

## 9. 새 세션을 위한 권고

세션이 새로 시작될 때 읽을 순서:
1. **이 문서** (stage-00-initial.md) — 현재 컨텍스트.
2. `docs/sequence.md` — 전체 21단계 + 현재 위치.
3. `docs/reference-policy.md` — A' 정책 + SimpleDB/BusTub 매핑.
4. `docs/constraints.md` — Kotlin 사용 규칙.
5. `impl/README.md` — 따라치기 워크플로 + 표준 구조 + 단계별 진입 안내.
6. `docs/stages/01-storage.md` — 단계 1 컨텍스트.
7. `impl/01-01-append-only-kv.md` — 첫 세션 (작성됨).

다른 단계 stages (02~21)는 **speculative**. 단계 N 진입 시 stages/NN 의 Invalidation triggers 확인 후 갱신.

codex 호출 시 본 문서 + `reference-policy.md` 본문 첨부.

---

## 변경 이력 (이 문서는 1회 작성 후 변경 금지)

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 작성, 단계 1 진입 직전 snapshot |
