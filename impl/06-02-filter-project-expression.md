# impl/06-02 — Filter + Project + Expression

> 상위: `docs/stages/06-query-api.md`
> 범위: SQL WHERE/SELECT의 기초 — Expression sealed class + Filter/Project operator.
> **검증**: FilterProjectTest 3 PASSED.

---

## 0. 참조 출처
- SimpleDB `Filter`, `Project`, `Predicate`.
- BusTub `filter_executor`, `projection_executor`.

## 1. 만족시킬 invariant
- **CI-1**: `Filter(pred, child)` 는 child의 tuple 중 pred 결과가 true 인 것만 통과.
- **CI-2**: `Project(cols, child)` 는 같은 row를 그대로 두고 컬럼만 좁힘.
- **CI-3**: pred 결과가 null (UNKNOWN) 이면 false 취급. (SQL three-valued logic 단순화)

## 2. 의존성
- 06-01 (Operator interface, SeqScan, TableHeap)
- catalog.Tuple

## 3. 문제 정의

단계 06-01의 SeqScan은 모든 row를 반환할 뿐, "특정 조건 만족 row만" 또는 "특정 컬럼만" 가져올 수 없다. WHERE / SELECT col,col 의미를 표현하려면 두 가지가 필요:
1. **Expression** — predicate (`age > 28`) 와 값 (`'Alice'`, `42`) 의 추상화.
2. **Filter / Project Operator** — Expression을 받아 child 결과를 변형.

## 4. 실패 테스트 (TDD step 2)

```kotlin
@Test
fun `Filter age greater than 28`(@TempDir tempDir: Path) {
    val path = tempDir.resolve("f.data").toString()
    PagedFile(path).use { pf ->
        BufferPool(pf, capacity = 16).use { bp ->
            val heap = TableHeap(schema, pf, bp)
            InsertOp(heap).insertMany(listOf(...))
            val filtered = Filter(SeqScan(heap), Expression.gt(Expression.col("age"), Expression.lit(28)))
            val r = filtered.iterator().toList()
            assertEquals(2, r.size)
        }
    }
}
```
→ `Unresolved reference: Expression / Filter / Project`. 5번에서 구현.

## 5. 구현 코드

### 5.1 Expression.kt — sealed class

```kotlin
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

### 5.2 Filter.kt

```kotlin
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

### 5.3 Project.kt

```kotlin
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

## 6. 검증 테스트 (TDD step 4)

`FilterProjectTest`:
- `Filter age greater than 28` — 4 row 중 age > 28 인 2개만
- `Project id and name only` — 컬럼 좁힘
- `Filter then Project chain` — operator 합성

## 7. 직접 깨뜨릴 과제
- 과제 1: `WHERE age = NULL` 이 항상 false 인 이유? `IS NULL` 과 차이는?
- 과제 2: Filter 다음 Project vs Project 다음 Filter — 결과는 같지만 어느 게 빠른가?
- 과제 3: `Expression.Not(Literal(null))` 의 evaluate 결과는?

## 8. 다음 한계
- Constraint 검증 안 됨 → **06-03 ConstraintValidator**.
