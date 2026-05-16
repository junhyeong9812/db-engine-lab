# impl/ — 따라치기 세션 문서

> 사용자가 손으로 직접 타이핑하며 학습하기 위한 코드 + 설명 문서가 모이는 곳.
> `docs/stages/NN-stage.md`(맥락·결정)와 **역할 분리** (codex 보정 8):
> - `docs/stages/NN-stage.md`: **decision / context** (왜 / invariant / 다음 한계)
> - `impl/NN-MM.md`: **TDD 실행 절차** (문제 정의 → 실패 테스트 → 구현 → Q/A → 검증 → 깨뜨릴 과제)
> 중복 정보는 한 곳에서만 정의하고 다른 쪽은 링크.

---

## 워크플로

### 1. 사용자가 단계 진입 결정
- `docs/stages/NN-stage.md` 가 먼저 작성됨.
- 그 단계의 invariant·시나리오 합의.

### 2. 사용자가 "이번 세션 만들어줘" 요청 (pull 방식)
- Claude가 `impl/NN-MM-주제.md` 1파일 생성.
- 한 세션 = 한 파일. 너무 크면 분할.

### 3. 사용자가 세션 따라치기
- 파일 읽고 → 자기 IDE에서 직접 타이핑.
- Q/A 주석은 답 보기 전에 자기 가설 만들기.

### 4. 세션 끝에 "직접 깨뜨릴 과제" 수행
- 정답 없는 과제를 사용자 스스로 풀어봄. **따라치기 함정 해소의 핵심** (codex 보정 4).

### 5. 다음 세션 요청
- 같은 단계 안의 다음 세션 또는 다음 단계 진입.

### 6. 단계 완료 시 handoff 작성
- `docs/handoff/stage-NN-handoff.md` 신규 생성.

---

## impl/NN-MM.md 표준 구조 (TDD 형식 + A' 정책)

```markdown
# impl/NN-MM — <주제>

> 상위 단계: docs/stages/NN-stage.md
> 이 세션의 범위: <한 줄>
> 예상 타이핑 시간: <분>

## 0. 참조 출처 (A' 정책 — codex 보정 1·2·6)

### 주 참조 (SimpleDB) — `docs/reference-policy.md` 참조
- 파일/클래스/메서드: ___
- commit/판본: ___
- 우리 코드 대응: ___

### 대조 참조 (BusTub) — 해당 시
- 파일: ___
- 우리 코드와의 차이: ___
- 차이 채택 여부: ___

### 참조 부재 (해당 시)
- "참조 자료 없음. Claude 자체 설계."
- 대안 참조: ___ (Phase B는 reference-policy 표)

### 핵심 설계 결정 근거 (1~3줄)
- ___

## 1. 만족시킬 invariant (3개 이내)
- I-1: ___
- I-2: ___
- I-3: ___

## 2. 의존성
- 이전 세션: <NN-MM-1>
- 파일/클래스: ___

## 3. 문제 정의 (TDD step 1 — what we want to achieve)

(자연어로 풀어쓴 문제. 구체적 시나리오.)

## 4. 문제를 시각화하는 실패 테스트 (TDD step 2 — failing test first)

(사용자가 먼저 작성해서 빨간 줄 보기. 코드 없으니 컴파일 실패 또는 실행 실패 확인.)

```kotlin
// 전체 테스트 코드, 생략 없음
```

**실행 결과**: 컴파일 실패 또는 ___ 예외 (예상 결과 명시).

## 5. 구현 코드 (TDD step 3 — make it pass)

(전체 코드, 생략 없음. Q/A 주석은 codex 보정 2·3 적용 — 위험 줄·상태 변경·분기·invariant·예외 줄에만)

```kotlin
package com.dbenginelab.<package>

// 자명한 import는 주석 없음.
import java.io.RandomAccessFile

class ExampleClass(private val path: String) {

    // 자명한 줄(필드 선언, 단순 대입)은 주석 없음.
    private val file = RandomAccessFile(path, "rw")

    fun append(record: ByteArray) {
        // Q: 왜 length를 먼저 쓰는가? 그냥 bytes만 쓰면 안 되나?
        file.writeInt(record.size)
        file.write(record)
        // <details><summary>A</summary>
        //
        // 가변 길이 데이터를 읽을 때 어디까지가 한 record인지 알아야 함. length가 frame 역할.
        // </details>
    }

    fun flush() {
        // Q: flush와 fsync의 차이는? sync()를 호출하지 않으면 어떤 입력에서 깨지나?
        file.fd.sync()
        // <details><summary>A</summary>
        //
        // flush는 OS buffer까지, fsync는 디스크까지. OS crash 후 살아남으려면 fsync 필수.
        // </details>
    }
}
```

## 6. 검증 테스트 (TDD step 4 — green)

```kotlin
// 모든 invariant 검증
```

## 7. 직접 깨뜨릴 과제 (codex 보정 4 — 정답 코드 없이 먼저 고치기)

각 과제는 **정답 코드 미제공**. 사용자가 자기 코드 변형해서 깨뜨려보고 결과를 `docs/stages/NN-stage.md` 또는 `docs/decision-log.md`에 기록.

- 과제 1: ___ 를 변경했을 때 무엇이 깨지는가?
- 과제 2: ___ 입력을 주면 어떤 invariant가 깨지는가?
- 과제 3: ___ 상황에서 사용자가 새 테스트를 작성해 깨지는 입력 찾기.

## 8. 다음 한계 (다음 세션의 동기)

이 세션 코드는 ___ 상황에서 깨진다 → 다음 세션 ___

## 9. Spot check 권고 (codex 보정 5)

- 초반 단계 (1~6): 단계 종료 시 SimpleDB 원본 코드 확인.
- 일치/불일치 결과를 `docs/handoff/stage-NN-handoff.md` "7. Spot check 결과"에 기록.
```

---

## 작성 규칙 (Claude가 지킨다)

### 코드 작성 규칙
1. **코드는 생략 없이 전체**. `// ...rest of class` 같은 축약 금지 (사용자 학습 정책 [[feedback-token-budget-learning]]).
2. **타입 명시**: Kotlin inference는 가독성에 좋지만 학습에서는 타입을 명시 (특히 collection, generic).
3. **`internal` modifier 의도 표현으로 유지** (단일 모듈에서 사실상 무력 — codex 보정 6).
4. **page/frame/slot은 mutable + explicit API**. `data class`로 만들지 않음 (codex 보정 1).
5. **sealed class는 진짜 닫힌 영역만**: parser AST, internal command (codex 보정 2).
6. **storage/index/transaction 확장 지점은 interface 또는 enum+handler** (codex 보정 2).
7. **에러 상태는 sealed error hierarchy**. nullable은 단순 조회 실패에만 (codex 보정 3).
8. **Coroutine은 학습 영역에서 금지** (단계 9 LockManager, 단계 13 parallel execution).

### Q/A 주석 작성 규칙 (codex 보정 2·3·4 — 사용자 결정 반영 + 보정)

#### 적용 대상 (모든 줄 아님)
- ✅ **상태 변경** (assignment that changes object state, lock acquire/release, file write).
- ✅ **분기 조건** (if/when/while 조건).
- ✅ **invariant 지키는 줄** (없으면 깨지는 줄).
- ✅ **예외 처리** (try/catch/finally).
- ✅ **나중에 바뀔 줄** (가설 코드, 다음 단계에서 교체될 부분).
- ✅ **이해가 갈리는 줄** (DB 학습 관점에서 처음 만나는 패턴).
- ❌ **자명한 줄**: 단순 변수 선언, 단순 대입, getter, 명백한 제어 흐름.
- ❌ **import**, **package 선언**, **빈 줄**.

#### Q/A 양식
```kotlin
// Q: <예측·비교·실패 사례 질문>
<코드 한 줄 또는 작은 블록>
// <details><summary>A</summary>
//
// <1문장 원칙. 길어지면 별도 섹션 참조 링크>
// </details>
```

#### 좋은 질문 vs 나쁜 질문 (codex 보정 3)
| 좋은 질문 | 나쁜 질문 |
|----------|----------|
| "이 줄 이후 무엇이 달라지는가?" | "이 변수는 무엇인가?" |
| "이 조건이 빠지면 어떤 입력에서 깨지는가?" | "이 함수는 무엇을 하는가?" |
| "왜 여기서 early return 하는가?" | "이 메서드는 어떻게 동작하는가?" |
| "fsync 없이 OS crash가 나면 결과는?" | "fsync는 무엇인가?" |

좋은 질문 = **예측·비교·실패 사례**.
나쁜 질문 = **코드 표면을 그대로 읽게 함**.

#### 답은 1문장 원칙 (codex 보정 4)
- 답이 길어지면 코드 흐름이 끊김.
- 1문장으로 안 되면 별도 섹션 (`## 부록 A: ___`)에 두고 링크.

#### 답 보기 전 사고 강제 (codex 보정 1)
- `<details><summary>A</summary>...</details>` 형식으로 답을 접음.
- 사용자가 의식적으로 클릭해야 답 보임 → "질문 → 코드 → 자기 가설 → 답 확인" 루틴 강제.

### 테스트 작성 규칙
- **TDD 순서**: 문제 정의 → 실패 테스트 → 구현 → 통과 테스트.
- **실패 테스트 먼저**: 사용자가 빨간 줄을 직접 봐야 함.
- **직접 깨뜨릴 과제**: 테스트 골격만 주고 사용자가 채움 (정답 코드 미제공).

---

## 따라치기 함정 보정책 (이 폴더 전체의 정신)

이 워크플로는 codex가 명시 경고한 "AI 설계 따라치기 = 이해 착각" 패턴이다.
완전 해소는 불가능하지만 다음으로 **함정의 일부를 줄인다**:

| 보정책 | 강제 위치 |
|--------|----------|
| (1) invariant 먼저 | 모든 세션 1번 섹션 |
| (2) TDD 순서 (문제 → 실패 → 구현) | 모든 세션 3·4·5번 |
| (3) "정답 코드 없이 먼저 고치기" 과제 | 모든 세션 7번 (codex 보정 4) |
| (4) Q/A 주석 (위험 줄 한정, 답 접기, 1문장) | 모든 코드 |
| (5) 참조 출처 명시 | 모든 세션 0번 (A' 정책) |
| (6) Spot check | 초반 매 단계, 안정화 후 3~5단계 (codex 보정 5) |

**여전히 남는 함정** (정직히 인정):
- 사용자가 실제로 설계를 철회하거나 대안을 비교하는 경험은 따라치기로 만들 수 없음.
- 시스템 설계의 트레이드오프 직관은 자기 자신의 실패에서만 나옴.
- 이를 보완하기 위해 **Phase 종료 시점에 회고 문서를 작성**하고, 그때 자기 결정을 사후 검증.

---

## Naming 규칙

```
impl/
├── README.md                        # 이 문서
├── NN-MM-<주제>.md                  # 단계 NN의 세션 MM (진입 시 생성)
│   예: 01-01-append-only-kv.md      # 작성됨
│       01-02-partial-write.md       # 단계 1 진입 시 결정
│       02-01-page-layout.md         # 단계 2 진입 시
│       ...
└── retrospective/                   # Phase 종료 회고 (Phase A·B 각 1개)
    ├── phase-a.md
    └── phase-b.md
```

---

## 단계별 진입 안내

> 각 단계의 잠정 세션 계획은 `docs/stages/NN-stage.md` "세션 분할 계획 (잠정)" 절 참조.
> 모든 stages는 `Status: speculative` — 진입 시 prior stage handoff와 대조 후 재검토.

| 단계 | stages 문서 | 잠정 세션 수 | 첫 세션 후보 |
|------|------------|-------------|------------|
| 1 | `01-storage.md` | 3 | `01-01-append-only-kv.md` ✅ |
| 2 | `02-page-buffer.md` | 3 | `02-01-page-byte-container.md` |
| 3 | `03-index.md` | 5 | `03-01-hash-index.md` (또는 직접 BTree) |
| 4 | `04-schema-catalog.md` | 4 | `04-01-type-system.md` |
| 5 | `05-constraints.md` | 4 | `05-01-pk-notnull.md` |
| 6 | `06-query-api.md` | 6 | `06-01-seqscan-operator.md` |
| 7 | `07-batch.md` | 2 | `07-01-workunit-begin-commit.md` |
| 8 | `08-wal-recovery.md` | **8** | `08-01-logrecord-format.md` (가장 큰 단계) |
| 9 | `09-locks.md` | 5 | `09-01-lockmanager-sx.md` |
| 10 | `10-mvcc.md` | 4 | `10-01-xmin-xmax-visibility.md` |
| 11 | `11-optimizer.md` | 5 | `11-01-statistics-analyze.md` |
| 12 | `12-sql-parser.md` (옵션) | 5 | `12-01-lexer.md` |
| 13 | `13-connection-pool.md` | 5 | `13-01-session-pool.md` |
| 14 | `14-wire-protocol.md` (Phase B 시작) | 4 | `14-01-tcp-message-framing.md` |
| 15 | `15-auth.md` | 4 | `15-01-user-password.md` |
| 16 | `16-backup.md` | 3 | `16-01-logical-dump.md` |
| 17 | `17-monitoring.md` | 4 | `17-01-metrics-registry.md` |
| 18 | `18-replication.md` | 4 | `18-01-walsender.md` |
| 19 | `19-online-ddl.md` | 4 | `19-01-add-column-metadata.md` |
| 20 | `20-admin-cli.md` | 4 | `20-01-cli-skeleton.md` |
| 21 | `21-sharding.md` (capstone) | 4 | 진입 결정 시 |

**총 잠정 세션 수**: 약 90개 (실제는 단계 진입 시 분할 재검토로 +/-30%).

**진입 절차**:
1. `docs/stages/NN-stage.md` 읽기 + Invalidation triggers 확인.
2. 직전 단계 `docs/handoff/stage-(NN-1)-handoff.md` 읽기.
3. stages 본문 갱신 (필요 시).
4. 사용자가 "단계 N 첫 세션 만들어줘" 요청.
5. Claude가 `impl/NN-01-주제.md` 생성.
6. 사용자 타이핑 → 깨뜨릴 과제 → 다음 세션.

---

## 변경 이력

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 — 워크플로 + 표준 구조 + 보정책 |
| 2026-05-16 | A' 정책 반영: 0. 참조 출처 섹션 추가. TDD 형식 명시 (3·4·5·6번 섹션). Q/A 주석 패턴 (codex 보정 2·3·4 — 위험 줄 한정, `<details>` 답 접기, 1문장, 예측·비교·실패 사례 질문). |
