# decision-log.md — 주요 결정 이력

> 결정·번복·재검토를 시간 순으로 기록.
> 단계별 세부 결정은 각 `stages/NN-stage.md` 에 기록.

---

## 2026-05-16

### D-001: 프로젝트 본질
- **결정**: 자바로 단일 DB 엔진을 처음부터 구현하면서 DB 처리 흐름을 학습.
- **배경**: MySQL/Oracle/PG 차이 비교가 아니라 **DB 자체 처리 흐름 이해**가 1순위.
- **기각된 대안**:
  - (가) MySQL/Oracle/PG 3개 따로 구현 — 비현실적 (각 1년+, 학습 한계 효용 급감).
  - mod.yml + EngineProfile 다중 모드 — 1순위가 아니라서 보류.

### D-002: 진행 방식 — "실패하는 가정의 순서"
- **결정**: 단계별 점진적 풀스택 확장. 각 단계는 이전 단계의 가정이 깨지는 지점에서 동기 도출.
- **배경**: "역사적 순서"는 정당화가 약함. "실패 가정"이 더 정직.
- **출처**: codex 보정 (2026-05-16, 호출 4회차).

### D-003: 시퀀스 21단계 (Phase A 13 + Phase B 8)
- **결정**: Phase A (엔진 내부 정확성) 13단계 + Phase B (제품 운영) 8단계.
- **배경**: 사용자가 codex의 "자동 도달 환상" 우려를 받아들이되, 별도 축이 아니라 같은 시퀀스로 의도적 단계화.
- **산출물 기준**:
  - Phase A: `engine correctness`.
  - Phase B: `operational usability`.

### D-004: 단계별 1파일 원칙
- **결정**: 각 단계의 문서는 `docs/stages/NN-stage.md` 1파일. 빈 골격 미리 만들지 않음.
- **배경**: 4파일 분할은 10단계 × 4 = 40파일로 유지비 폭증.
- **출처**: codex 보정.

### D-005: 데이터 모델·인터페이스는 living doc
- **결정**: page format, tuple layout, log record format, 인터페이스 시그니처는 "가설"로 표시. 단계 진행 중 갱신 가능.
- **배경**: 초기 고정 시 후속 단계가 깨질 때 재작업 큼.
- **출처**: codex 보정.

### D-006: 단계별 "검토 후보" 메모
- **결정**: codex가 추가 제안한 미세 조정(wire protocol 역류, catalog 확장, page format versioning, replication 축소, migration 분리, lifecycle/logging 누락)은 해당 단계 진입 시 재검토. sequence.md에 메모.
- **배경**: 사용자가 21단계 골격을 합의한 상태에서 한 번 더 흔들지 않으면서 codex 보정 정보를 잃지 않기 위함.

### D-007: 폴더 구조 — `docs/` 4문서 + `stages/` (비어있음)
- **결정**: 시작 산출물은 `README + sequence + constraints + decision-log` 4개. `stages/`는 단계 1 진입 시점에 첫 파일 생성.
- **src/ 생성 시점**: 단계 1 진입 결정 시.

### D-008: acid-Lab- 처리 — 보류
- **결정**: `/home/jun/project/acid-Lab-` 는 나중에 이어서. db-engine-lab의 단계별 ACID 시나리오와 자연스럽게 겹치므로 별도 진행 가능.
- **배경**: 마지막 커밋 2026-03-22 (TransactionService 진입 직후 중단). db-engine-lab과 학습 영역이 겹침.

---

### D-009: 언어 — Kotlin (JVM target 21)
- **결정**: 메인 언어 Kotlin 2.1+, JVM toolchain 21 LTS.
- **배경**: Java 대비 표현력(data class / sealed / extension / null safety)이 학습 코드 가독성에 유리. JVM 위라서 시스템 프로그래밍 함정(GC, off-heap, fsync, mmap)은 동등.
- **Kotlin 사용 규칙**: `constraints.md` "Kotlin 사용 규칙" 절. data class/sealed/null safety의 매력이 저장소 현실을 숨기지 않도록 제한.
- **출처**: codex 보정 (2026-05-16, 6회차) 지적 1·2·3·6.

### D-010: 워크플로 — impl/ 마크다운 따라치기 + 보정책 5개
- **결정**: 사용자가 손으로 타이핑하며 학습. Claude가 `impl/NN-MM-주제.md` 1파일에 (invariant / naive 코드 / 깨지는 테스트 / 개선 코드 / 검증 테스트 / 직접 깨뜨릴 과제) 작성. Pull 방식 — 사용자 요청 시점에만 생성.
- **배경**: 사용자의 학습 스타일. codex가 명시 경고한 "AI 설계 따라치기 = 이해 착각" 패턴임을 인지한 상태에서 선택.
- **보정책 5개** (impl/README.md):
  1. invariant 먼저 (코드 보기 전)
  2. naive → 깨지는 테스트 → 개선 3블록
  3. "정답 코드 없이 먼저 고치기" 과제 강제 (codex 보정 4)
  4. 라인 주석은 "위험한 줄 / invariant 줄 / 바뀔 줄"에만 (codex 보정 5)
  5. Phase 종료 회고 문서
- **정직히 인정**: 함정의 100% 해소는 불가능. "스스로 설계 결정의 트레이드오프 도출 경험"은 따라치기로 만들 수 없음.
- **출처**: codex 보정 (2026-05-16, 6회차) 지적 4·5.

### D-011: src 초기 구조 — 단일 모듈, 패키지 분화는 단계 진입 시
- **결정**: 단일 Gradle 모듈, 루트 패키지 `com.dbenginelab`. 패키지 분화(`storage`, `transaction`, `index` 등)는 단계 진입 시 책임 단위로.
- **`internal` modifier**: 단일 모듈에서 사실상 무력하지만 의도 표현으로 유지. 테스트에서 internal 직접 접근 금지.
- **package-info.md**: 각 패키지의 public surface는 단계 진입 시 작성.
- **멀티 모듈 분리 시점**: 단계 4(catalog) 또는 단계 8(WAL) 진입 시 재검토.
- **출처**: codex 보정 (2026-05-16, 6회차) 지적 6.

### D-012: 문서 역할 분리 — docs/stages vs impl/NN
- **결정**: 
  - `docs/stages/NN-stage.md` = **decision / context** (왜 / invariant / 다음 한계).
  - `impl/NN-MM-주제.md` = **실행 절차** (코드 + 깨뜨릴 과제).
- **중복 방지**: 같은 정보를 양쪽에 적지 않음. 한쪽에서 정의하고 다른 쪽은 링크.
- **출처**: codex 보정 (2026-05-16, 6회차) 지적 8.

### D-013: Phase overview 사전 작성 — 로드맵만, 설계 확정 아님
- **결정**: `phase-a-overview.md`, `phase-b-overview.md` 사전 작성. 단 내용은 **목표 / 제약 / 아직 모르는 것 / 재검토 조건**만. 구체 API/패키지/클래스명은 단계 진입 시.
- **배경**: overview가 living doc 원칙 위반하지 않도록.
- **출처**: codex 보정 (2026-05-16, 6회차) 지적 7.

---

### D-014: 옵션 A' — Claude impl/ + SimpleDB·BusTub 참조 검산
- **결정**: 워크플로는 옵션 A 유지(Claude impl/ 따라치기 + 21단계 시퀀스). 단 Claude가 impl/ 문서를 만들 때 **SimpleDB (Java) 기본 / BusTub (C++) 대조** 참조 강제. 목적은 **"코드 이식"이 아니라 "설계 검산"** (codex 보정 7).
- **세부**: `docs/reference-policy.md` 참조.
  - Phase A 단계별 참조 매핑.
  - Phase B는 별도 reference policy (PostgreSQL/MySQL/SQLite docs).
  - 모든 impl/ 세션 첫 섹션 `0. 참조 출처` 의무.
  - 핵심 설계 결정에는 원문 링크 + commit hash + 근거 1~3줄.
  - Spot check: 초반 매 단계, 안정화 후 3~5단계.
- **출처**: codex 7회차 보정.

### D-015: 페이즈/단계 단위 handoff 문서 도입
- **결정**: 단계 완료 시 `docs/handoff/stage-NN-handoff.md` 신규 작성. 새 세션의 Claude 또는 codex가 이 문서 하나로 컨텍스트 회복 가능.
- **세부**: `docs/handoff/README.md` 양식.
  - 한 번 작성하면 변경 금지 (snapshot).
  - 분량 제한 1500자 이내.
  - codex 호출 시 첨부 자료로 사용.
- **현재 단계 (0) handoff**: `docs/handoff/stage-00-initial.md` 작성됨.

### D-016: TDD 형식 + Q/A 주석 패턴 (사용자 결정 + codex 보정)
- **결정**: impl/NN-MM.md 표준 구조를 TDD 순서로 (문제 정의 → 실패 테스트 → 구현 → 검증 → 깨뜨릴 과제). 구현 코드에는 Q/A 주석 패턴.
- **사용자 원안**: "한 줄 한 줄 전체 주석".
- **codex 보정 8회차 반영**:
  - Q/A는 **위험한 줄·상태 변경·분기·invariant·예외·이해 갈리는 줄에 한정** (자명한 줄 제외).
  - 답은 `<details>` 접기로 → 보기 전 사고 강제.
  - 답은 **1문장 원칙**.
  - 질문은 **예측·비교·실패 사례** (코드 표면 설명형 금지).
- **세부**: `impl/README.md` "Q/A 주석 작성 규칙" 절.

---

## 다음 결정 지점

- **D-017 (예정)**: 단계 2 진입 시 page size / BufferPool 크기 가설값 결정.
- **D-018 (예정)**: 단계 1 두 번째 세션 (`impl/01-02-*.md`) 범위 (partial write 처리?).

---

## 결정 번복 양식

번복이 발생하면 아래 양식으로 기록:

```
### D-NNN-rev: <원 결정 ID> 번복
- **번복 일자**: YYYY-MM-DD
- **원 결정**: <요약>
- **번복 사유**: <어떤 단계에서, 어떤 깨진 가정이, 왜 원 결정을 무효화했는가>
- **새 결정**: <대체 결정>
```
