# impl/13-02 — SQL AST → LogicalPlan Translator (보강 C1)

> **종류**: 보강형
> **상위 단계**: `docs/stages/13-connection-pool.md`
> **코드 정본**: git `5505edc` — "complete: 21 stages + 12 보강 (120/120 tests)"
> **이 세션의 범위**: 단계 12의 AST와 단계 11의 LogicalPlan 사이에 다리를 놓는다.
> **작성 파일**:
> - 신규: `src/main/kotlin/com/dbenginelab/sql/Translator.kt`
> - **별도 테스트 없음** — 검증은 14-00 `DbEngineTest`의 end-to-end 경유
> **검증**: `DbEngineTest` 5 PASSED (14-00에서)
> **예상 타이핑 시간**: 25분

---

## 0. 참조

- Apache Calcite `SqlToRelConverter` 패턴을 크게 단순화.

## 1. 만족시킬 invariant

- **CI-1**: SELECT AST → LogicalPlan (`Scan` / `Filter` / `Project`).
- **CI-2**: 비교 연산자 문자열(`"="`, `"<>"`, …)이 `CompareOp` enum에 **정확히** 매핑된다.
- **CI-3**: AND/OR/Compare가 재귀적으로 변환된다.

## 2. 문제 정의

단계 12는 `Ast.Select`를 만들고, 단계 11은 `LogicalPlan`을 받는다. **둘은 서로를 모른다.** 사용자가 손으로 옮겨 담아야 한다면 파서를 만든 의미가 없다.

변환 자체는 기계적이다. 다만 조용히 틀리기 쉬운 곳이 하나 있다 — **연산자 문자열 매핑**이다. `"<>"`를 `NE`가 아니라 `LT`로 잘못 적어도 컴파일은 통과하고, 대부분의 테스트도 통과한다. **그 연산자를 쓰는 질의에서만** 조용히 틀린 답이 나온다.

CI-2가 그래서 따로 적힌 invariant다.

## 3. 구현 코드

```kotlin
// src/main/kotlin/com/dbenginelab/sql/Translator.kt @ 5505edc
package com.dbenginelab.sql

import com.dbenginelab.executor.Expression
import com.dbenginelab.optimizer.LogicalPlan

/**
 * Stage 13 보강 (C1): SQL AST → optimizer LogicalPlan 변환.
 *
 * SELECT 만 지원 (INSERT/CREATE/DROP 은 DbEngine facade가 별도 처리).
 */
object Translator {

    fun toLogicalPlan(statement: Statement.Select): LogicalPlan {
        var plan: LogicalPlan = LogicalPlan.Scan(statement.table)
        if (statement.where != null) {
            plan = LogicalPlan.FilterNode(plan, translateExpr(statement.where))
        }
        if (statement.columns != null) {
            plan = LogicalPlan.ProjectNode(plan, statement.columns)
        }
        return plan
    }

    private fun translateExpr(e: SqlExpr): Expression = when (e) {
        is SqlExpr.Col -> Expression.col(e.name)
        is SqlExpr.LitNumber -> Expression.lit(e.value)
        is SqlExpr.LitString -> Expression.lit(e.value)
        SqlExpr.LitNull -> Expression.lit(null)
        is SqlExpr.Compare -> Expression.Compare(
            translateExpr(e.left),
            translateCompareOp(e.op),
            translateExpr(e.right),
        )
        is SqlExpr.And -> Expression.and(translateExpr(e.left), translateExpr(e.right))
        is SqlExpr.Or -> Expression.or(translateExpr(e.left), translateExpr(e.right))
    }

    private fun translateCompareOp(op: String): Expression.CompareOp = when (op) {
        "=" -> Expression.CompareOp.EQ
        "<>", "!=" -> Expression.CompareOp.NE
        "<" -> Expression.CompareOp.LT
        "<=" -> Expression.CompareOp.LE
        ">" -> Expression.CompareOp.GT
        ">=" -> Expression.CompareOp.GE
        else -> error("unknown compare op: $op")
    }
}
```

## 4. 검증 — 별도 테스트가 없다는 것에 대하여

이 클래스에는 전용 테스트 파일이 없다. 검증은 14-00의 `DbEngineTest`가 end-to-end로 한다 — SQL 문자열이 들어가서 올바른 행이 나오면 번역기도 맞았다고 보는 것이다.

**이 선택의 위험을 알아둬라.** end-to-end 테스트는 "어딘가 틀렸다"는 것만 알려주고 **어디가 틀렸는지는 알려주지 않는다.** `<>` 매핑이 틀렸을 때 실패하는 것은 `DbEngineTest`이고, 원인을 찾으려면 파서·번역기·옵티마이저·실행기를 다 뒤져야 한다.

§5 과제 1번이 이 문제를 직접 다룬다.

## 5. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

**1.** **이 클래스의 단위 테스트를 직접 써라.** 연산자 6종이 전부 올바른 `CompareOp`로 가는지 확인해라.

<details><summary>답</summary>

과제 2번의 실측 결과를 먼저 보고 오면 이게 왜 필수인지 알게 된다 — **지금 이 클래스는 사실상 검증되지 않고 있다.**

써야 할 테스트 골격:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
class TranslatorTest {
    @Test fun `비교 연산자 6종이 정확히 매핑된다`() {
        val cases = mapOf(
            "="  to Expression.CompareOp.EQ,
            "<>" to Expression.CompareOp.NE,
            "!=" to Expression.CompareOp.NE,
            "<"  to Expression.CompareOp.LT,
            "<=" to Expression.CompareOp.LE,
            ">"  to Expression.CompareOp.GT,
            ">=" to Expression.CompareOp.GE,
        )
        for ((op, expected) in cases) {
            val stmt = Statement.Select(
                columns = null, table = "t",
                where = SqlExpr.Compare(SqlExpr.Col("a"), op, SqlExpr.LitNumber(1L)),
            )
            val plan = Translator.toLogicalPlan(stmt) as LogicalPlan.FilterNode
            val cmp = plan.predicate as Expression.Compare
            assertEquals(expected, cmp.op, "연산자 $op")
        }
    }
}
```

AST를 손으로 만들면 되니 파일도 heap도 필요 없다. **빠르고, 실패하면 원인이 하나다.** end-to-end 테스트가 못 주는 것이 정확히 이 두 가지다.

여유가 있으면 `AND`/`OR` 중첩과 `LitNull` 변환도 같은 방식으로 덮어라.
</details>

**2.** `"<>"`를 `CompareOp.LT`로 잘못 매핑해봐라. `DbEngineTest` 5개 중 몇 개가 깨지나?

<details><summary>답</summary>

**실측: 하나도 안 깨진다. 5개 전부 통과한다.**

이유는 단순하다 — **`DbEngineTest`의 어떤 질의도 `<>`를 쓰지 않는다.** 쓰는 연산자는 `>`, `=`, `>=`, `<=` 뿐이다.

이 사실이 뜻하는 바:

1. **이 클래스에는 사실상 테스트가 없다.** 13-02가 "검증은 `DbEngineTest` 경유"라고 적어뒀지만, 그 경유가 덮는 것은 실제로 쓰인 경로뿐이다.
2. **`<>`는 프로덕션에서 처음 실행된다.** 사용자가 `WHERE status <> 'done'`을 쓰는 순간 조용히 틀린 답이 나온다 — `status < 'done'`으로 해석되어 사전순 비교가 된다.
3. **에러가 안 난다.** `CompareOp.LT`도 유효한 값이라 컴파일도 실행도 멀쩡하다.

§4에서 "end-to-end 테스트는 어디가 틀렸는지 알려주지 않는다"고 적었는데, 실측해보니 더 나빴다 — **틀렸다는 것조차 알려주지 않는다.** 커버리지가 없는 코드 경로는 테스트가 있는 것처럼 보여도 없는 것이다.
</details>

**3.** `AND`/`OR` 재귀 변환에서 좌우를 바꿔봐라. 결과가 달라지는 SQL이 있나? 실행 비용은?

<details><summary>답</summary>

**결과는 달라지지 않는다.** `AND`와 `OR`은 교환법칙이 성립한다 — `a AND b`와 `b AND a`는 논리적으로 같다. `Expression.Logical.evaluate`도 양쪽을 다 평가한 뒤 합치므로 순서에 무관하다.

**그런데 비용은 달라질 수 있다.** 두 가지 이유로:

1. **short-circuit이 있다면** — 우리 코드에는 **없다**:
   ```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
   val l = left.evaluate(tuple) as? Boolean ?: return null
   val r = right.evaluate(tuple) as? Boolean ?: return null   // ← l이 false여도 평가한다
   return if (op == AND) l && r else l || r
   ```
   Kotlin의 `&&`는 short-circuit이지만 **이미 양쪽을 다 계산한 뒤**라 아무 소용이 없다. `AND`에서 왼쪽이 false면 오른쪽을 안 봐도 되는데 보고 있다.

2. **선택도가 다르면** — `WHERE 흔한조건 AND 희귀조건`에서 희귀한 쪽을 먼저 평가하면 대부분의 행이 첫 조건에서 탈락한다. short-circuit이 있다는 전제 하에 **순서가 곧 성능**이 된다.

즉 이 과제의 진짜 답은 **"지금은 비용이 안 달라진다. 그게 문제다."** short-circuit을 넣고 나서야 순서가 의미를 갖고, 그때 비로소 옵티마이저가 조건 순서를 재배치할 이유가 생긴다.
</details>

**4.** AST에 `ORDER BY`가 추가된다면 이 번역기의 어디에 무엇을 넣어야 하나?

<details><summary>답</summary>

`toLogicalPlan`의 마지막에 한 겹 더 감싼다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
fun toLogicalPlan(statement: Statement.Select): LogicalPlan {
    var plan: LogicalPlan = LogicalPlan.Scan(statement.table)
    if (statement.where != null)   plan = LogicalPlan.FilterNode(plan, translateExpr(statement.where))
    if (statement.columns != null) plan = LogicalPlan.ProjectNode(plan, statement.columns)
    if (statement.orderBy != null) plan = LogicalPlan.SortNode(plan, statement.orderBy)   // 추가
    return plan
}
```

순서가 `Project` **뒤**인 것에 주의. SQL 의미상 정렬은 결과 집합에 대해 일어난다.

그리고 `LogicalPlan`에 `SortNode`를 추가하는 순간:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
private fun buildPhysical(plan: LogicalPlan): PhysicalPlan = when (plan) {
    is LogicalPlan.Scan -> …
    is LogicalPlan.FilterNode -> …
    is LogicalPlan.ProjectNode -> …
    // ← 컴파일 에러: 'when' expression must be exhaustive
}
```

**`SimpleOptimizer`가 컴파일되지 않는다.** sealed class이기 때문이다.

이게 06-02·12-01에서 sealed를 고른 이유의 실물이다 — **새 노드를 추가하면 그것을 다뤄야 하는 모든 곳이 컴파일 에러로 손을 든다.** enum + `else ->` 였다면 조용히 무시되고 정렬이 안 되는 채로 돌았을 것이다.

"컴파일 에러가 난다"가 여기서는 **결함이 아니라 기능**이다.
</details>

## 6. 다음 한계

이제 SQL 문자열 → 토큰 → AST → LogicalPlan → PhysicalPlan → 실행까지 부품이 다 있다. 그런데 **그 부품들을 순서대로 불러주는 사람이 없다.**

→ **14-00 DbEngine** — 첫 end-to-end 입구.
