# db-engine-lab

> **Kotlin (JVM target 21)** 으로 단일 DB 엔진을 처음부터 구현하면서 **DB 처리의 전체 흐름**을 학습하는 프로젝트.

---

## 학습 목표

- **본질**: 데이터베이스 자체의 처리 흐름(저장 / 인덱스 / 스키마 / 쿼리 / 트랜잭션 / 복구 / 동시성 / 최적화 / 운영)을 자바 코드로 직접 구현하면서 이해도를 올린다.
- **방법**: **"실패하는 가정의 순서"** 에 따라 점진적으로 풀스택을 확장한다.
- **부수 효과**: ACID 문제(lost update, dirty read, durability 깨짐 등)는 단계 진입 시점에 자연 발생하므로, 그 시점에 테스트로 검증한다.

## 비목표

- 실제 MySQL/Oracle/PostgreSQL의 정확한 재구현이 아님 (역사 재현이 아니라 **실패 가정 순서**).
- 제품 수준의 성능/안정성/호환성이 아님.
- 분산 sharding은 capstone 트랙으로 검토 (Phase B 본류 외).

## 자연 발생하지 않는 영역 (의도적으로 단계화)

다음 항목들은 엔진 내부 단계에서 자연 도달하지 않지만, "실제 불편함의 개선"이라는 같은 원리에 속하므로 **Phase B로 분리해 의도적으로 다룬다**:

- wire protocol, 인증/권한, 백업/복원, 모니터링/메트릭, replication, partitioning, migration, 관리 도구.

산출물 기준은 두 Phase에서 다르다:
- **Phase A** (단계 1~13): `engine correctness` — 정확성·invariant 만족.
- **Phase B** (단계 14~21): `operational usability` — 운영 가능성.

## 진행 방식

- **실패하는 가정의 순서**: 각 단계는 이전 단계 코드를 직접 깨뜨려보고 그 한계에서 동기를 도출한다.
- **AI 설계 따라치기 함정 인지**: codex가 명시 경고한 패턴이므로 **보정책 5개** 적용 (impl/README.md 참조):
  1. invariant 먼저 (코드 보기 전)
  2. naive → 깨지는 테스트 → 개선 3블록
  3. "정답 코드 없이 먼저 고치기" 과제 강제
  4. 라인 주석은 "위험한 줄 / invariant 줄 / 바뀔 줄"에만
  5. 단계 종료 점검 질문
- **Living document**: 데이터 모델·인터페이스·page format은 모두 "가설" 표시. 다음 단계가 갱신할 수 있다.
- **단계별 1파일** (docs/stages): 깨진 가정 / 도입 모델 / 시나리오 / 다음 한계.
- **세션별 1파일** (impl): pull 방식 — 사용자가 요청 시 1개씩 생성.
- **단계 진입 시점에만 문서 생성**: 빈 골격을 미리 만들지 않는다.

## Kotlin 사용 규칙

(codex 보정 1·2·3·6 — 자세히는 `docs/constraints.md` 참조)

- `data class` 는 **도메인 메타데이터에만** (page/frame/slot 은 explicit mutable API).
- `sealed class` 는 **진짜 닫힌 영역만** (parser AST, internal command).
- 확장 지점은 **interface 또는 enum+handler** (storage/index/transaction).
- 에러 상태는 **sealed error hierarchy** (nullable은 단순 조회 실패 한정).
- `internal` modifier 의도 표현으로 유지 (단일 모듈에서는 사실상 무력하지만).

## 폴더 구조

```
db-engine-lab/
├── README.md                    # 이 문서
├── build.gradle.kts             # Kotlin 2.1 + JVM toolchain 21
├── settings.gradle.kts
├── gradle.properties
├── .gitignore
├── docs/
│   ├── sequence.md              # 전체 21단계 + 현재 위치
│   ├── constraints.md           # 가정·제약·비목표 + Kotlin 사용 규칙
│   ├── decision-log.md          # 주요 결정 이력
│   ├── phase-a-overview.md      # Phase A 로드맵 (목표/제약/모르는 것/재검토 조건)
│   ├── phase-b-overview.md      # Phase B 로드맵
│   ├── reference-policy.md      # A' 정책 — SimpleDB 기본 / BusTub 대조 참조 매핑
│   ├── handoff/                 # 세션 간 컨텍스트 인계 문서
│   │   ├── README.md            # handoff 양식 + 사용법
│   │   └── stage-NN-handoff.md  # 단계 완료 시 신규 작성
│   └── stages/                  # 단계 진입 시점에 생성 (decision/context 전용)
├── impl/                        # 따라치기 세션 문서 (코드 + 한정된 주석)
│   ├── README.md                # 워크플로 + 표준 구조 + 보정책
│   └── (사용자 요청 시 NN-MM-주제.md 생성)
└── src/
    ├── main/kotlin/com/dbenginelab/   # 단계 진입 시 패키지 분화
    └── test/kotlin/com/dbenginelab/
```

**문서 역할 분리** (codex 보정 8):
- `docs/stages/NN-stage.md` — **decision / context**: 왜 이 단계인가, invariant.
- `impl/NN-MM-주제.md` — **실행 절차**: 코드 + 깨뜨릴 과제.
- 중복 정보는 한 곳에서만 정의, 다른 쪽은 링크.

## 현재 위치

**단계 1 진입.** `docs/stages/01-storage.md` + `impl/01-01-append-only-kv.md` 작성 완료. 사용자 타이핑 대기.
참조 정책: 옵션 A' (`docs/reference-policy.md`).
세션 인계: `docs/handoff/stage-00-initial.md`.

## 참고 자료

- 책: *Database Internals* (Alex Petrov)
- 강의: CMU 15-445 / 15-721 (Andy Pavlo)
- 코드 참조: H2 Database (Java), Apache Derby, Apache Calcite

## 관련 프로젝트

- `/home/jun/project/acid-Lab-` — ACID 시나리오 실험실 (보류 중, 이 프로젝트에서 ACID 문제는 단계별로 자연 발생하므로 별도 학습 트랙).
