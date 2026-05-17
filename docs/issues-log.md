# issues-log.md — 구현 중 발생한 문제 + 해결 기록

> 각 단계에서 빌드/테스트 실패, 버그, 설계 갈등, 가설 깨짐 등 일어난 문제와 해결을 시간 순으로 누적.
> 사용자 학습 시 "왜 이 코드가 이 모양인가"의 배경 자료.

---

## ISSUE-004 (보강 Stage 8 X2): Recovery `when` exhaustive 미충족

**증상**: `'when' expression must be exhaustive. Add the 'is Checkpoint' branch or an 'else' branch.`

**원인**: LogRecord에 `Checkpoint` 새 케이스 추가 → 기존 `Recovery.recover()`의 `when` 분기가 비exhaustive.

**해결**: `is LogRecord.Checkpoint -> { /* legacy Recovery ignores */ }` 분기 추가. 새 IdempotentRecovery만 Checkpoint 사용.

**학습**: sealed class에 케이스 추가 시 모든 호출자 컴파일러가 잡아줌 — 안전한 변경.

---

## ISSUE-003 (보강 Stage 11): InsertOp import path 오류

**증상**: `Unresolved reference 'InsertOp'`. OptimizerTest 3 곳.

**원인**: `InsertOp`은 `com.dbenginelab.executor` 패키지에 있는데 테스트 코드가 `com.dbenginelab.table.InsertOp`로 import.

**해결**: import path 수정.

**학습**: 같은 도메인 클래스가 여러 패키지에 분산 시 import 오류 빈번. IDE 자동완성 의존 위험.

---

## ISSUE-002 (Stage 6-2): Kotlin backtick 함수명에 콜론 사용 불가

**증상**: `Name contains illegal characters: :.`

**원인**: Kotlin backtick identifier에 `:` illegal.

**해결**: `Filter: age...` → `Filter age...`로 변경.

**학습**: Kotlin backtick은 `:`, `;`, `.`, `,`, `[`, `]`, `<`, `>`, `(`, `)`, `{`, `}`, `/` 등 illegal. 한국어/공백은 OK.

---

## ISSUE-001 (Stage 3-2): B+tree separator navigation 분기 순서 버그

**발생 단계**: 3-2 (BTree leaf split)
**발견 시점**: 첫 `./gradlew test` 실행, 3 tests FAILED
**증상**:
```
key=128 expected: <1280> but was: <null>
key=128 after reopen expected: <896> but was: <null>
key=128 expected: <127> but was: <null>
```

### 원인
B+tree internal node navigation에서 `findSlot(key)`가 첫 separator와 정확히 같은 key를 만났을 때 (slot=0), 분기 순서가 잘못돼 **leftmost child로 navigation 됐다**.

문제 코드:
```kotlin
val childPageNo = when {
    slot == 0 -> btp.auxPage              // ← 잘못된 첫 분기
    slot < keyCount && keyAt(slot) == key -> btp.valueAt(slot).toInt()
    else -> btp.valueAt(slot - 1).toInt()
}
```

`findSlot(128)`이 sepKey[0]=128을 만나면 slot=0 반환 → 첫 분기 트리거 → leftmost child (page 1, keys 1..127) → key=128 없음 → null.

### B+tree 규약 (recap)
Separator key는 **right subtree의 leftmost leaf에 존재**한다. 즉 `key == sepKey`이면 right로 가야 함.

### 해결
분기 순서를 바꿔서 separator match를 첫 분기로:
```kotlin
val childPageNo = when {
    slot < btp.keyCount && btp.keyAt(slot) == key -> btp.valueAt(slot).toInt()  // separator match → right
    slot == 0 -> btp.auxPage                                                    // key < first sep → leftmost
    else -> btp.valueAt(slot - 1).toInt()                                       // between separators
}
```

### 검증
- 재실행: 19/19 PASSED.
- 회귀 방지: 코드 주석에 명시 ("This check must precede the slot==0 branch").

### 학습 포인트
- B+tree separator convention은 "leaf duplicates separator" — 학습자가 자주 헷갈리는 지점.
- `when` 분기 순서는 의미적으로 중요 — 첫 매칭이 우선.
- 디버깅 방법: 실패 key가 정확히 무엇인지 (`key=128` 같이 임계값) 보면 split point의 invariant 위반 의심.

---
