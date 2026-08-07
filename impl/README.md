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

## 문서 2종 (2026-07-31 확정)

한 종류로 강제하지 않는다. 세션의 성격이 실제로 두 가지이기 때문이다.

| | **세션형** | **보강형** |
|---|---|---|
| 언제 | 신규 개념·클래스 도입 | 기존 코드에 기능 추가·수정 |
| §3 문제 정의 | 필수 | 필수 (짧게 — "무엇이 부족한가") |
| §4 실패 테스트 | **필수** | 생략 가능 |
| §5 구현 코드 | 필수 (파일 전문) | 필수 (파일 전문) |
| §6 검증 테스트 | **필수** (파일 전문) | **필수** (파일 전문) |
| 예 | `01-01`, `03-03`, `06-02` | `06-04`, `08-03`, `10-03` |

문서 헤더 첫 줄에 `> **종류**: 세션형` 또는 `> **종류**: 보강형` 을 명시한다.
**보강형에서도 §6의 테스트 코드 전문은 면제되지 않는다** — 생략해도 되는 것은 "빨간 줄 먼저 보기" 절차뿐이다.

## impl/NN-MM.md 표준 구조 (TDD 형식 + A' 정책)

```markdown
# impl/NN-MM — <주제>

> **종류**: 세션형 | 보강형
> **상위 단계**: docs/stages/NN-stage.md
> **코드 정본**: git <커밋 sha> — <커밋 제목>
> **이 세션의 범위**: <한 줄>
> **작성 파일**:
> - 신규|수정: `src/main/kotlin/com/dbenginelab/<pkg>/<Class>.kt` — <무엇>
> - 신규|수정: `src/test/kotlin/com/dbenginelab/<pkg>/<Class>Test.kt` — <무엇>
> **검증**: <TestClass> N PASSED · <누적>
> **예상 타이핑 시간**: <분>

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

## 4. 실패 테스트 (TDD step 2 — failing test first)

(그 시점의 테스트 파일 **전문**. 기존 테스트 + 이번 세션의 실패 테스트가 함께 들어간다.
사용자는 이 블록을 통째로 저장하고 빨간 줄을 눈으로 본다.)

```kotlin
// src/test/kotlin/com/dbenginelab/<pkg>/<Class>Test.kt @ <정본 sha>
package com.dbenginelab.<pkg>

import ...   // 전부 명시

class <Class>Test {
    // 픽스처 포함 전문 — 생략 금지
}
```

**예상 실패**: (반드시 형태를 문장으로 명시한다. 아래 둘 중 하나)
- `**컴파일 실패** — Unresolved reference: <심볼> (<파일> N곳)` ← 신규 클래스·메서드 도입 시 정상적인 빨간 줄
- `**assertion 실패** — expected: <X> but was: <Y>` ← 기존 코드의 동작을 바꿀 때

넘겨짚지 말고 `./gradlew test` 로 그 메시지를 실제로 확인하게 시킬 것.

## 5. 구현 코드 (TDD step 3 — make it pass)

(전체 코드, 생략 없음. Q/A 주석은 codex 보정 2·3 적용 — 위험 줄·상태 변경·분기·invariant·예외 줄에만)

```kotlin
// src/main/kotlin/com/dbenginelab/<pkg>/ExampleClass.kt @ <정본 sha>
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

**최종 테스트 파일 전문**을 다시 싣는다. 사용자는 이 블록으로 덮어쓰면 끝나야 한다 — 눈으로 병합하게 만들지 않는다.

```kotlin
// src/test/kotlin/com/dbenginelab/<pkg>/<Class>Test.kt @ <정본 sha>
// 모든 invariant를 덮는 최종 상태
```

§4와 파일 내용이 **완전히 같으면 재게시하지 않는다**. 대신 그 사실을 한 줄로 적고(`§4의 파일이 곧 최종본`)
실행 명령과 기대 결과만 남긴다. 같은 200줄을 두 번 싣는 것은 생략 금지 정책이 요구하는 바가 아니다.

```bash
./gradlew test --tests 'com.dbenginelab.<pkg>.<Class>Test'
```

**기대 결과**: `<Class>Test` N PASSED · 누적 M PASSED
그리고 invariant ↔ 테스트 대응을 명시한다 (`I-1 ← <테스트명>`). 어떤 테스트가 어떤 invariant를 지키는지
적히지 않으면 §1은 장식이 된다.

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

### 코드 블록 규칙 (2026-07-31 확정 — 여기가 이 문서의 핵심)

**0. 블록 1개 = 파일 1개. 첫 줄은 앵커 주석이다.**

```kotlin
// src/main/kotlin/com/dbenginelab/storage/BTreeIndex.kt @ 742c55c
package com.dbenginelab.storage
```

- 앵커는 두 가지를 동시에 한다 — 사용자에게 **"이 코드를 어느 파일에 넣는가"**, 기계에게 **"정본이 무엇인가"**.
- `@` 뒤는 정본 커밋. `python3 scripts/check-impl-canon.py` 가 `git show <sha>:<path>` 와 대조한다(주석·공백 제외, 코드 라인 일치).
- 정본에 중간 상태가 없어 재구성한 블록은 `@ reconstructed(<base>..<head>) — <사유>` 로 표기한다. 대조에서 제외되는 대신 **해당 세션 테스트로 컴파일·실행 실증**이 조건이다.
- 설명을 위해 코드 일부만 보이는 블록은 첫 줄에 `// (발췌 — <어디의 일부인지>)` 를 적는다. 대조에서 제외된다. **발췌는 §2·§3 같은 설명 절에서만 쓴다** — §4·§5·§6의 블록은 반드시 파일 전문이어야 한다.

**1. 파일 단위 완전형 — 그대로 치면 컴파일되어야 한다.**
- `package` 선언, `import` 전부, `class` 래퍼, 공용 픽스처(`private val schema = …`)까지 포함.
- **금지**: `...`, `/* 요약 */`, `// 이하 동일`, `// rest of class`, 정의 없이 쓰이는 심볼.
- 특히 **구현을 주석으로 대체하지 않는다**. `private fun load() { /* binary read */ }` 같은 줄은 문서를 따라친 사용자에게 원인 불명의 테스트 실패로 돌아온다(2026-07-31 실측: CatalogTest 2건이 정확히 이 원인).
- 같은 파일이 여러 세션에 걸쳐 자라면 **매 세션 그 시점의 전문**을 싣는다. 중복은 감수한다 — 사용자가 자기 파일을 정본과 대조할 수 있어야 하기 때문이다. 어디를 새로 치는지는 머리말이나 주석으로 짚어준다.

**2. 정본 개작 금지.** 정본 코드에서 낡은 주석·의심스러운 로직을 발견해도 고치지 않는다. 그대로 옮기고 필요하면 `> **정본 특이사항**` 인용구로 짚는다.

**3. Q/A 주석은 자유롭게 추가.** 대조 스크립트가 주석을 무시하므로 학습용 주석은 얼마든지 덧붙일 수 있다(적용 대상은 아래 Q/A 규칙).

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

### 테스트 작성 규칙 (2026-07-31 확정)

- **TDD 순서**: 문제 정의 → 실패 테스트 → 구현 → 통과 테스트. (보강형은 실패 테스트 단계만 생략 가능)
- **실패 테스트 먼저**: 사용자가 빨간 줄을 직접 봐야 함. 컴파일 실패도 정당한 빨간 줄이지만 **형태를 §4에 문장으로 명시**해야 한다(§4 템플릿 참조).
- **테스트 코드는 절대 생략 대상이 아니다.** "검증 (N PASSED)" 불릿 목록만 남기는 것은 금지 — 그것은 **이전 회차의 실행 보고**일 뿐이고, 사용자가 재현할 수단이 아니다. 이 금지가 이번 정비의 출발점이다(2026-07-31: 36개 문서 중 테스트 코드가 실재한 것이 6개였다).
- **테스트 파일 규칙**
  - 경로: `src/test/kotlin/com/dbenginelab/<pkg>/<Class>Test.kt` — 대상 클래스와 같은 패키지.
  - 클래스명: `<대상클래스>Test`. 한 세션이 여러 클래스를 건드리면 대표 클래스 기준 1개 파일 + 필요 시 분리.
  - 테스트명: 백틱 한글 문장. invariant를 직접 검증하면 번호를 앞에 붙인다 — `` fun `I-1 append 후 reopen하면 …`() ``.
  - 파일 I/O는 `@TempDir tempDir: Path` 파라미터. 고정 경로 금지.
  - 프레임워크: JUnit5(`org.junit.jupiter.api.Test`) + `kotlin.test` assertion. 예외는 `org.junit.jupiter.api.assertThrows`.
- **새 실패 테스트를 어디에 추가하는가**: 기존 테스트 클래스가 있으면 **그 파일 안에 @Test 메서드를 추가**하고, §4에는 추가된 상태의 **파일 전문**을 싣는다. 새 클래스가 필요한 경우에만 새 파일을 만들고 그 경로를 헤더 `작성 파일`에 적는다.
- **직접 깨뜨릴 과제 (2026-08-05 개정)**: 각 문항 아래에 **`<details>`로 접은 답**을 단다.
  - 개정 사유: "정답 미제공"이 원칙이었으나 실제로 사용자가 과제를 수행하지 못한 채 막혔다. 답이 아예 없는 것보다, **접혀 있어 먼저 생각할 여지가 남는 쪽**이 실효가 있다고 판단(사용자 결정).
  - **답의 사실 주장은 실측으로 뒷받침한다.** "이 테스트가 깨진다"고 쓰려면 `scripts/mutate-check.py`로 실제 변형을 가해 돌려보고 그 결과를 적는다. **추측을 단정으로 쓰지 않는다.**
  - **"안 깨진다"도 그대로 적는다.** 테스트 스위트가 못 잡는 결함이 어디인지가 오히려 가장 좋은 학습 재료다 — 그때는 "잡는 테스트를 직접 써라"로 이어간다.
  - 정답이 하나가 아닌 문항(설계 판단·테스트 작성)은 **모범답안 대신 판단 근거와 실제 DB가 택한 방향**을 적는다.

### 검증 도구

| 스크립트 | 용도 |
|---|---|
| `scripts/check-impl-canon.py` | 문서 코드 블록 ↔ 정본 커밋 실파일 대조. 문서를 고칠 때마다 실행 |
| `scripts/extract-impl-blocks.py` | 문서 블록을 실제 파일로 추출 (`--out` 필수, repo 밖) → 빌드·테스트로 "문서만으로 green이 나오는가" 실증 |
| `scripts/mutate-check.py` | 정본에 변형을 가하고 테스트를 돌려 **무엇이 깨지는지 실측** — 깨뜨릴 과제 답의 근거 |

새 문서를 쓰거나 고쳤으면 **두 개를 다 돌린 뒤에** 끝났다고 말한다.

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
| 2026-07-31 | **문서 2종(세션형/보강형) 도입 · 코드 블록 앵커(`// <경로> @ <정본 sha>`) · 파일 단위 완전형 · §4 예상 실패 명시 의무 · §6 최종 전문 재게시 · 테스트 파일/이름 규칙 · 검증 스크립트 2종.** 계기: 36개 문서 전수 조사에서 테스트 코드 실재 6개, 실패 테스트 섹션 3개, 문서 코드량이 정본의 62%로 확인됨. 근거·경위 = `docs/plans/2026-07-31/impl-doc-canonical-restore/` |
