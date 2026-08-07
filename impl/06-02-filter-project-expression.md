# impl/06-02 — Filter + Project + Expression

> **종류**: 세션형
> **상위 단계**: `docs/stages/06-query-api.md`
> **코드 정본**: git `5505edc` — "complete: 21 stages + 12 보강 (120/120 tests)"
> **이 세션의 범위**: SQL `WHERE`와 `SELECT col, col`의 기초 — 표현식(Expression)과 그것을 쓰는 연산자 둘.
> **작성 파일**:
> - 신규: `src/main/kotlin/com/dbenginelab/executor/Expression.kt`
> - 신규: `src/main/kotlin/com/dbenginelab/executor/Filter.kt`
> - 신규: `src/main/kotlin/com/dbenginelab/executor/Project.kt`
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/executor/FilterProjectTest.kt`
> **검증**: `FilterProjectTest` 3 PASSED
> **예상 타이핑 시간**: 45분

---

## 0. 참조

- 주 참조: SimpleDB `Filter`, `Project`, `Predicate`.
- 대조 참조: BusTub `filter_executor`, `projection_executor`.
- **핵심 설계 결정 근거**: `Expression`은 `sealed class`가 맞다. 표현식 트리는 **닫힌 문법**이기 때문이다 — 종류가 늘어나도 파서·평가기가 모두 알아야 하므로 `when`이 빠짐없이 처리하도록 컴파일러가 강제해주는 편이 낫다. (04-01의 `Type`을 enum으로 둔 것과 대조해보라.)

## 1. 만족시킬 invariant

- **CI-1**: `Filter(child, pred)`는 child의 tuple 중 pred가 true인 것만 통과시킨다.
- **CI-2**: `Project(child, cols)`는 행 수를 바꾸지 않고 컬럼만 좁힌다.
- **CI-3**: pred 결과가 null(UNKNOWN)이면 **false로 취급한다** (SQL 3치 논리의 단순화).

## 2. 의존성

- 이전 세션: `impl/06-01-table-seqscan.md` (`Operator`, `SeqScan`, `TableHeap`, `InsertOp`)
- `catalog.Tuple`, `catalog.TableSchema`

## 3. 문제 정의 (TDD step 1)

06-01의 `SeqScan`은 전부 다 준다. `SELECT * FROM users` 하나뿐이다. `WHERE age > 28`을 표현하려면 두 가지가 필요하다:

1. **조건을 값으로 표현하는 법** — `age > 28`은 코드가 아니라 **데이터**여야 한다. 그래야 파서가 만들고, 옵티마이저가 들여다보고, 실행기가 평가할 수 있다. 그게 `Expression` 트리다.
2. **그 조건을 쓰는 연산자** — `Filter`는 child를 감싸고 통과 여부만 결정한다. `Project`는 컬럼을 좁힌다. 둘 다 `Operator`를 구현하므로 서로 감쌀 수 있다.

세 번째로, 피해 갈 수 없는 문제가 하나 나온다 — **NULL**. `age`가 null인 행에서 `age > 28`은 참도 거짓도 아니다. SQL은 이것을 UNKNOWN이라는 세 번째 값으로 다룬다. 우리는 `evaluate`가 `null`을 반환하게 두고, `Filter`에서 `== true`로 비교해 UNKNOWN을 자동으로 탈락시킨다. **`!= false`가 아니라 `== true`인 것이 핵심이다.**

## 4. 실패 테스트 (TDD step 2)

```kotlin
// src/test/kotlin/com/dbenginelab/executor/FilterProjectTest.kt @ 5505edc
package com.dbenginelab.executor

import com.dbenginelab.catalog.ColumnDef
import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.catalog.Type
import com.dbenginelab.storage.BufferPool
import com.dbenginelab.storage.PagedFile
import com.dbenginelab.table.TableHeap
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

class FilterProjectTest {

    private val schema = TableSchema(
        name = "users",
        columns = listOf(
            ColumnDef("id", Type.BIGINT, nullable = false),
            ColumnDef("name", Type.STRING, nullable = false),
            ColumnDef("age", Type.INT, nullable = true),
        ),
    )

    @Test
    fun `Filter age greater than 28`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("f.data").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                InsertOp(heap).insertMany(listOf(
                    Tuple(schema, listOf(1L, "Alice", 30)),
                    Tuple(schema, listOf(2L, "Bob", 25)),
                    Tuple(schema, listOf(3L, "Charlie", 35)),
                    Tuple(schema, listOf(4L, "Dave", null)),
                ))
                val filtered = Filter(SeqScan(heap), Expression.gt(Expression.col("age"), Expression.lit(28)))
                val r = filtered.iterator().toList()
                assertEquals(2, r.size)
                assertEquals(setOf(30, 35), r.map { it.get("age") }.toSet())
            }
        }
    }

    @Test
    fun `Project id and name only`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("p.data").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                InsertOp(heap).insertOne(Tuple(schema, listOf(1L, "A", 10)))
                val p = Project(SeqScan(heap), listOf("id", "name"))
                val r = p.iterator().toList()
                assertEquals(2, p.outputSchema.columnCount)
                assertEquals("A", r[0].get("name"))
            }
        }
    }

    @Test
    fun `Filter then Project chain`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("fp.data").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                InsertOp(heap).insertMany(listOf(
                    Tuple(schema, listOf(1L, "A", 10)),
                    Tuple(schema, listOf(2L, "B", 20)),
                ))
                val chain = Project(
                    Filter(SeqScan(heap), Expression.eq(Expression.col("id"), Expression.lit(2L))),
                    listOf("name"),
                )
                val r = chain.iterator().toList()
                assertEquals(1, r.size)
                assertEquals("B", r[0].get("name"))
            }
        }
    }
}
```

**예상 실패**: **컴파일 실패** — `Unresolved reference: Expression`, `Filter`, `Project`.

## 5. 구현 코드 (TDD step 3 — make it pass)

### 5.1 `Expression.kt` — 조건을 값으로

```kotlin
// src/main/kotlin/com/dbenginelab/executor/Expression.kt @ 5505edc
package com.dbenginelab.executor

import com.dbenginelab.catalog.Tuple

sealed class Expression {
    abstract fun evaluate(tuple: Tuple): Any?

    data class ColumnRef(val name: String) : Expression() {
        override fun evaluate(tuple: Tuple): Any? = tuple.get(name)
    }

    data class Literal(val value: Any?) : Expression() {
        override fun evaluate(tuple: Tuple): Any? = value
    }

    enum class CompareOp { EQ, NE, LT, LE, GT, GE }

    data class Compare(val left: Expression, val op: CompareOp, val right: Expression) : Expression() {
        override fun evaluate(tuple: Tuple): Any? {
            val l = left.evaluate(tuple)
            val r = right.evaluate(tuple)
            // Q: l 또는 r 이 null이면 false가 아니라 null을 반환하는 이유?
            if (l == null || r == null) return null
            // <details><summary>A</summary>
            //
            // SQL three-valued logic: NULL과 비교는 UNKNOWN. Filter에서 `== true` 비교가 UNKNOWN을 자동으로 false로 안전 처리.
            // </details>
            val cmp = compareValues(l, r)
            return when (op) {
                CompareOp.EQ -> cmp == 0
                CompareOp.NE -> cmp != 0
                CompareOp.LT -> cmp < 0
                CompareOp.LE -> cmp <= 0
                CompareOp.GT -> cmp > 0
                CompareOp.GE -> cmp >= 0
            }
        }

        private fun compareValues(a: Any, b: Any): Int = when {
            a is Int && b is Int -> a.compareTo(b)
            a is Long && b is Long -> a.compareTo(b)
            a is Int && b is Long -> a.toLong().compareTo(b)
            a is Long && b is Int -> a.compareTo(b.toLong())
            a is String && b is String -> a.compareTo(b)
            else -> error("incomparable types: ${a::class.simpleName} vs ${b::class.simpleName}")
        }
    }

    enum class LogicalOp { AND, OR }

    data class Logical(val left: Expression, val op: LogicalOp, val right: Expression) : Expression() {
        override fun evaluate(tuple: Tuple): Any? {
            val l = left.evaluate(tuple) as? Boolean ?: return null
            val r = right.evaluate(tuple) as? Boolean ?: return null
            return if (op == LogicalOp.AND) l && r else l || r
        }
    }

    data class Not(val expr: Expression) : Expression() {
        override fun evaluate(tuple: Tuple): Any? {
            val v = expr.evaluate(tuple) as? Boolean ?: return null
            return !v
        }
    }

    companion object {
        fun col(name: String): Expression = ColumnRef(name)
        fun lit(value: Any?): Expression = Literal(value)
        fun eq(l: Expression, r: Expression): Expression = Compare(l, CompareOp.EQ, r)
        fun lt(l: Expression, r: Expression): Expression = Compare(l, CompareOp.LT, r)
        fun gt(l: Expression, r: Expression): Expression = Compare(l, CompareOp.GT, r)
        fun and(l: Expression, r: Expression): Expression = Logical(l, LogicalOp.AND, r)
        fun or(l: Expression, r: Expression): Expression = Logical(l, LogicalOp.OR, r)
    }
}
```

### 5.2 `Filter.kt`

```kotlin
// src/main/kotlin/com/dbenginelab/executor/Filter.kt @ 5505edc
package com.dbenginelab.executor

import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple

class Filter(private val child: Operator, private val predicate: Expression) : Operator {
    override val outputSchema: TableSchema = child.outputSchema
    override fun iterator(): Sequence<Tuple> = child.iterator().filter { tuple ->
        // Q: 왜 `== true` 비교? Boolean 직접 쓰면 안 되나?
        predicate.evaluate(tuple) == true
        // <details><summary>A</summary>
        //
        // evaluate는 Any? 반환 (null/Boolean). null (UNKNOWN) 을 false로 안전 처리하려면 `== true` 필수.
        // </details>
    }
}
```

### 5.3 `Project.kt`

```kotlin
// src/main/kotlin/com/dbenginelab/executor/Project.kt @ 5505edc
package com.dbenginelab.executor

import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple

class Project(private val child: Operator, private val columnNames: List<String>) : Operator {
    override val outputSchema: TableSchema
    private val indices: IntArray

    init {
        val childCols = child.outputSchema.columns
        val keptCols = columnNames.map { name ->
            childCols.firstOrNull { it.name == name }
                ?: error("Project: column $name not in child schema")
        }
        outputSchema = TableSchema(name = "${child.outputSchema.name}_projected", columns = keptCols)
        // Q: 왜 indices를 init에서 미리 계산?
        indices = IntArray(columnNames.size) { i -> child.outputSchema.columnIndex(columnNames[i]) }
        // <details><summary>A</summary>
        //
        // iterator()마다 columnIndex 호출하면 O(N) 매 row마다. init에서 한 번에 IntArray로 캐싱.
        // </details>
    }

    override fun iterator(): Sequence<Tuple> = child.iterator().map { srcTuple ->
        val newValues = indices.map { srcTuple.values[it] }
        Tuple(outputSchema, newValues)
    }
}
```

## 6. 검증 테스트 (TDD step 4 — green)

테스트 파일은 §4에서 저장한 것이 그대로 최종본이다.

```bash
./gradlew test --tests 'com.dbenginelab.executor.FilterProjectTest'
```

**기대 결과**: `FilterProjectTest` **3 PASSED**

invariant 대응:
- **CI-1** ← `Filter age greater than 28`
- **CI-2** ← `Project id and name only`
- (합성) ← `Filter then Project chain` — `Operator` 인터페이스를 만든 값을 여기서 확인한다

**CI-3(NULL → false)은 이 3개로 직접 검증되지 않는다.** age가 null인 행이 테스트 데이터에 있다면 `Filter age greater than 28`이 간접적으로 덮지만, 그것을 확인하는 것은 §7 과제 1번이다.

## 7. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

**1.** `Filter`의 `predicate.evaluate(tuple) == true`를 `!= false`로 바꿔라. 어떤 데이터에서 결과가 달라지나?

<details><summary>답</summary>

**실측: `Filter age greater than 28`이 실패한다** (3개 중 1개).

테스트 데이터에 `Dave`가 `age = null`로 들어있는 것이 함정이다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
Tuple(schema, listOf(4L, "Dave", null))
```

`age > 28`을 평가하면 `Compare`가 `null`을 돌려준다(피연산자가 null이므로 UNKNOWN).

```
== true  → null == true  → false → Dave 탈락 → 2건 ✓
!= false → null != false → true  → Dave 통과 → 3건 ✗
```

**`null`은 `false`가 아니다.** 그래서 "false가 아닌 것"과 "true인 것"이 다른 집합이 된다. UNKNOWN을 통과시키면 **나이를 모르는 사람이 "28살보다 많다"에 걸려 나온다.**

SQL의 `WHERE age = NULL`이 아무 행도 반환하지 않는 이유가 같다 — `null = null`조차 UNKNOWN이고, `WHERE`는 **true인 행만** 통과시킨다. 그래서 null을 찾으려면 `IS NULL`이라는 **별도 연산자**가 필요하다. `IS NULL`은 비교가 아니라 "이 값이 UNKNOWN인가"를 묻는 것이라 항상 true/false를 돌려준다.

우리 코드에는 `IS NULL`이 없다. `Expression`에 어떤 노드를 추가해야 할지 생각해봐라.
</details>

**2.** `Project`의 `indices` 캐싱을 지우고 `iterator()` 안에서 매번 `columnIndex`를 부르게 바꿔라. 100만 행에서 비용 차이는?

<details><summary>답</summary>

**결과는 완전히 같다.** 테스트로는 절대 안 잡힌다.

`columnIndex`는 선형 탐색이다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
fun columnIndex(name: String): Int {
    val idx = columns.indexOfFirst { it.name == name }   // O(컬럼 수)
```

```
캐싱 있음: init에서 (고른 컬럼 수 × 전체 컬럼 수)회 → 3 × 3 = 9회
캐싱 없음: 행마다 반복 → 1,000,000 × 3 × 3 = 900만 회 문자열 비교
```

행 수에 비례해 늘어난다. 컬럼이 50개인 넓은 테이블이면 더 심해진다.

핵심은 **"행마다 반복되는 일"과 "한 번만 하면 되는 일"을 가르는 것**이다. 스키마는 질의 실행 중에 변하지 않으므로 컬럼 위치는 한 번만 계산하면 된다. 이런 걸 실행 계획 **준비(prepare) 단계**로 빼는 것이 DB 실행기의 기본 구조이고, `init`에 둔 것이 그 축소판이다.

`Filter`의 `predicate`도 같은 성격이다 — 표현식 트리를 매 행마다 다시 만들지 않고 한 번 만들어 재사용한다.
</details>

**3.** `Filter` → `Project` 와 `Project` → `Filter` — 결과는 같은가? 항상 같은가? 어느 쪽이 빠른가?

<details><summary>답</summary>

**항상 같지는 않다. 애초에 불가능한 경우가 있다.**

```sql
SELECT name FROM users WHERE age > 28
```

`Project(["name"])`를 먼저 하면 결과 tuple에 `age`가 없다. 그 다음 `Filter(age > 28)`은 `tuple.get("age")`에서 **컬럼을 못 찾아 터진다.**

즉 순서를 바꿀 수 있는 건 **Filter가 참조하는 컬럼이 Project 결과에 남아있을 때**뿐이다. (`SELECT age FROM users WHERE age > 28` 같은 경우)

바꿀 수 있을 때는 **Filter 먼저가 유리하다** — 행 수를 먼저 줄이면 뒤 연산이 처리할 양이 준다. 100만 행 중 100행만 남는다면 Project가 하는 일이 1만 분의 1로 준다.

이 변환을 **predicate pushdown**이라 하고, 옵티마이저의 대표적인 최적화다. 단계 11의 `SimpleOptimizer`는 아직 이걸 하지 않는다 — `LogicalPlan`을 그대로 물리 연산자로 옮길 뿐이다. **최적화할 자리가 남아있다는 뜻**이고, 직접 넣어보면 옵티마이저가 무엇을 하는 물건인지 확실해진다.
</details>

**4.** `Expression.Not(Literal(null))`의 평가 결과는? 먼저 예측하고 확인해라.

<details><summary>답</summary>

**`null`이다** (UNKNOWN).

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
data class Not(val expr: Expression) : Expression() {
    override fun evaluate(tuple: Tuple): Any? {
        val v = expr.evaluate(tuple) as? Boolean ?: return null   // ← 여기서 걸림
        return !v
    }
}
```

`Literal(null).evaluate()`가 `null`을 돌려주고, `as? Boolean`이 `null`이 되어 그대로 `return null`.

이게 SQL 3치 논리와 정확히 일치한다 — **`NOT UNKNOWN = UNKNOWN`.** 모르는 것을 부정해도 여전히 모른다.

여기서 흔한 착각을 하나 짚자면: `NOT (age > 28)`이 `age <= 28`과 같다고 생각하기 쉬운데, **`age`가 null이면 둘 다 UNKNOWN이라 어느 쪽도 통과하지 않는다.** 즉

```sql
SELECT * FROM users WHERE age > 28
UNION
SELECT * FROM users WHERE NOT (age > 28)
```

이 둘을 합쳐도 **전체 행이 나오지 않는다.** null인 행은 양쪽 다 빠진다. SQL을 쓰다가 "행 수가 안 맞는데?" 하는 상황의 대표적인 원인이다.
</details>

## 8. 다음 한계

`WHERE`와 컬럼 선택이 생겼지만, **단계 5에서 선언한 제약은 여전히 아무도 강제하지 않는다.** 같은 PK를 두 번 넣어도 통과한다.

→ **06-03 ConstraintValidator**.
