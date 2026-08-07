# impl/11-01 — Statistics + SimpleOptimizer

> **종류**: 세션형
> **상위 단계**: `docs/stages/11-optimizer.md`
> **코드 정본**: git `5505edc` — "complete: 21 stages + 12 보강 (120/120 tests)"
> **이 세션의 범위**: 통계를 모으고, 그 통계로 **논리 계획을 물리 계획으로** 바꾼다.
> **작성 파일**:
> - 신규 패키지: `src/main/kotlin/com/dbenginelab/optimizer/`
> - 신규: `optimizer/Statistics.kt` · `optimizer/SimpleOptimizer.kt`
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/optimizer/OptimizerTest.kt`
> **검증**: `OptimizerTest` 2 PASSED
> **예상 타이핑 시간**: 45분

---

## 0. 참조

- 주 참조: SimpleDB `JoinOptimizer` (lab6).
- 대조 참조: BusTub `optimizer`.
- **차이 채택 여부**: 조인 순서 최적화(가장 큰 주제)는 **채택 안 함.** 우리는 조인 자체가 없다. 이번 세션의 목표는 "옵티마이저가 무엇을 하는 물건인가"를 최소 형태로 겪는 것이다.

## 1. 만족시킬 invariant

- **CI-1**: `Statistics`는 행 수와 컬럼별 distinct 개수를 정확히 센다.
- **CI-2**: 동등 조건의 선택도(selectivity) = `1 / distinct`.
- **CI-3**: LogicalPlan → PhysicalPlan 변환 후 **결과가 같다.**

CI-3이 옵티마이저의 유일한 절대 규칙이다. **더 빠르게 만드는 것은 목표지만, 결과를 바꾸는 것은 버그다.**

## 2. 의존성

- `impl/06-02-filter-project-expression.md` (`Operator`, `Expression`)
- `impl/06-01-table-seqscan.md` (`TableHeap`, `SeqScan`)

## 3. 문제 정의 (TDD step 1)

`SELECT * FROM users WHERE id = 42`를 실행하는 방법은 여러 개다:
- 전부 훑으면서 `id = 42`인 것만 고른다 (SeqScan + Filter)
- 인덱스로 42를 찾아 그 행만 읽는다 (IndexScan)

**둘의 결과는 같고 비용은 100만 배 다를 수 있다.** 어느 쪽을 고를지 판단하는 것이 옵티마이저다.

판단하려면 데이터에 대해 뭔가 알아야 한다. 최소한의 정보가 두 개다:
- **행 수** — 전부 훑으면 몇 건인가.
- **컬럼별 distinct 개수** — `id = 42`가 몇 건을 반환할 것 같은가. distinct가 100만이면 1건, distinct가 2면 50만 건이다. `1 / distinct`가 그 추정치(selectivity)다.

여기서 중요한 것: **이건 추정이다.** 통계가 낡으면 옵티마이저는 틀린 계획을 고른다. 그래서 실제 DB에 `ANALYZE` 명령이 있고, 운영에서 "통계가 오래돼서 느려졌다"는 사고가 반복된다.

## 4. 실패 테스트 (TDD step 2)

```kotlin
// src/test/kotlin/com/dbenginelab/optimizer/OptimizerTest.kt @ 5505edc
package com.dbenginelab.optimizer

import com.dbenginelab.catalog.ColumnDef
import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.catalog.Type
import com.dbenginelab.executor.Expression
import com.dbenginelab.executor.InsertOp
import com.dbenginelab.storage.BufferPool
import com.dbenginelab.storage.PagedFile
import com.dbenginelab.table.TableHeap
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OptimizerTest {
    private val schema = TableSchema(
        name = "users",
        columns = listOf(
            ColumnDef("id", Type.BIGINT, nullable = false),
            ColumnDef("name", Type.STRING, nullable = false),
        ),
    )

    @Test fun `Statistics ANALYZE 정확한 distinct count`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("s.data").toString()
        PagedFile(path).use { pf -> BufferPool(pf, 16).use { bp ->
            val heap = TableHeap(schema, pf, bp)
            val ins = InsertOp(heap)
            ins.insertOne(Tuple(schema, listOf(1L, "A")))
            ins.insertOne(Tuple(schema, listOf(2L, "A")))
            ins.insertOne(Tuple(schema, listOf(3L, "B")))
            val stats = StatisticsCollector.analyze("users", heap)
            assertEquals(3, stats.rowCount)
            assertEquals(2L, stats.perColumnDistinct["name"])
            assertEquals(0.5, stats.equalitySelectivity("name"))
        }}
    }

    @Test fun `Optimizer가 PhysicalPlan으로 변환 + 실행`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("o.data").toString()
        PagedFile(path).use { pf -> BufferPool(pf, 16).use { bp ->
            val heap = TableHeap(schema, pf, bp)
            InsertOp(heap).insertMany((1..10).map { Tuple(schema, listOf(it.toLong(), "n$it")) })
            val logical = LogicalPlan.ProjectNode(
                LogicalPlan.FilterNode(
                    LogicalPlan.Scan("users"),
                    Expression.eq(Expression.col("id"), Expression.lit(5L)),
                ),
                listOf("name"),
            )
            val opt = SimpleOptimizer({ heap }, { StatisticsCollector.analyze("users", heap) })
            val physical = opt.optimize(logical)
            val results = physical.root.iterator().toList()
            assertEquals(1, results.size)
            assertEquals("n5", results[0].get("name"))
            assertTrue(physical.cost.total > 0)
        }}
    }
}
```

**예상 실패**: **컴파일 실패** — `Unresolved reference: Statistics`, `SimpleOptimizer`.

## 5. 구현 코드 (TDD step 3 — make it pass)

### 5.1 `Statistics.kt` — 데이터에 대해 아는 것

```kotlin
// src/main/kotlin/com/dbenginelab/optimizer/Statistics.kt @ 5505edc
package com.dbenginelab.optimizer

import com.dbenginelab.table.TableHeap

data class Statistics(
    val tableName: String,
    val rowCount: Long,
    val perColumnDistinct: Map<String, Long>,
) {
    fun equalitySelectivity(column: String): Double {
        val distinct = perColumnDistinct[column] ?: return 1.0
        return if (distinct == 0L) 1.0 else 1.0 / distinct
    }
}

object StatisticsCollector {
    fun analyze(name: String, heap: TableHeap): Statistics {
        var rowCount = 0L
        val distinctSets = heap.schema.columns.associate { it.name to mutableSetOf<Any?>() }
        for (tuple in heap.scan()) {
            rowCount++
            for (col in heap.schema.columns) distinctSets[col.name]!!.add(tuple.get(col.name))
        }
        return Statistics(name, rowCount, distinctSets.mapValues { it.value.size.toLong() })
    }
}
```

### 5.2 `SimpleOptimizer.kt` — 논리에서 물리로

```kotlin
// src/main/kotlin/com/dbenginelab/optimizer/SimpleOptimizer.kt @ 5505edc
package com.dbenginelab.optimizer

import com.dbenginelab.executor.Expression
import com.dbenginelab.executor.Filter
import com.dbenginelab.executor.Operator
import com.dbenginelab.executor.Project
import com.dbenginelab.executor.SeqScan
import com.dbenginelab.table.TableHeap

sealed class LogicalPlan {
    data class Scan(val table: String) : LogicalPlan()
    data class FilterNode(val child: LogicalPlan, val predicate: Expression) : LogicalPlan()
    data class ProjectNode(val child: LogicalPlan, val columns: List<String>) : LogicalPlan()
}

data class PhysicalCost(val io: Double, val cpu: Double) {
    val total: Double get() = io + cpu
}

class SimpleOptimizer(
    private val tableLookup: (String) -> TableHeap,
    private val statisticsLookup: (String) -> Statistics? = { null },
) {
    data class PhysicalPlan(val root: Operator, val cost: PhysicalCost)

    fun optimize(logical: LogicalPlan): PhysicalPlan = buildPhysical(logical)

    private fun buildPhysical(plan: LogicalPlan): PhysicalPlan = when (plan) {
        is LogicalPlan.Scan -> {
            val heap = tableLookup(plan.table)
            val stats = statisticsLookup(plan.table)
            val rowCount = stats?.rowCount ?: 1000L
            PhysicalPlan(SeqScan(heap), PhysicalCost(io = rowCount.toDouble(), cpu = rowCount * 0.1))
        }
        is LogicalPlan.FilterNode -> {
            val child = buildPhysical(plan.child)
            val sel = estimateSelectivity(plan.predicate, plan.child)
            val rowsOut = child.cost.io * sel
            val cost = PhysicalCost(io = child.cost.io, cpu = child.cost.cpu + rowsOut * 0.05)
            PhysicalPlan(Filter(child.root, plan.predicate), cost)
        }
        is LogicalPlan.ProjectNode -> {
            val child = buildPhysical(plan.child)
            val cost = PhysicalCost(io = child.cost.io, cpu = child.cost.cpu + child.cost.io * 0.02)
            PhysicalPlan(Project(child.root, plan.columns), cost)
        }
    }

    private fun estimateSelectivity(expr: Expression, child: LogicalPlan): Double {
        // Q: EQ만 정밀? LT/GT는 default 0.3?
        if (expr is Expression.Compare && expr.op == Expression.CompareOp.EQ) {
        // <details><summary>A</summary>
        // EQ는 distinct count로 정밀. LT/GT는 histogram 필요 — 단계 11+ 후속.
        // </details>
            val colName = (expr.left as? Expression.ColumnRef)?.name
                ?: (expr.right as? Expression.ColumnRef)?.name
            val table = baseTable(child)
            if (colName != null && table != null) {
                statisticsLookup(table)?.let { return it.equalitySelectivity(colName) }
            }
        }
        return 0.3
    }

    private fun baseTable(plan: LogicalPlan): String? = when (plan) {
        is LogicalPlan.Scan -> plan.table
        is LogicalPlan.FilterNode -> baseTable(plan.child)
        is LogicalPlan.ProjectNode -> baseTable(plan.child)
    }
}
```

## 6. 검증 테스트 (TDD step 4 — green)

테스트 파일은 §4에서 저장한 것이 그대로 최종본이다.

```bash
./gradlew test --tests 'com.dbenginelab.optimizer.OptimizerTest'
```

**기대 결과**: `OptimizerTest` **2 PASSED**

invariant 대응:
- **CI-1**, **CI-2** ← `Statistics ANALYZE 정확한 distinct count`
- **CI-3** ← `Optimizer가 PhysicalPlan으로 변환 + 실행`

**테스트가 2개뿐이라는 점을 눈여겨봐라.** 옵티마이저는 "결과가 같다"는 것 말고는 자동으로 검증하기 어려운 영역이다 — 계획이 더 나은지는 성능 측정의 문제이고, 그건 단위 테스트가 답하지 못한다.

## 7. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

**1.** `Statistics`를 만든 뒤 행을 10만 건 더 넣고, **다시 만들지 않은 채** 계획을 세워라. 추정치와 실제가 얼마나 벌어지나?

<details><summary>답</summary>

`Statistics`는 **만든 시점의 스냅샷**이다. 갱신하는 코드가 어디에도 없다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
object StatisticsCollector {
    fun analyze(name: String, heap: TableHeap): Statistics { … }   // 부를 때만 갱신
}
```

50건일 때 만들고 10만 건을 더 넣으면 옵티마이저는 여전히 **`rowCount = 50`** 으로 계산한다. 비용 추정이 **2000배** 틀린다.

운영에서 나타나는 증상:

- **"어제까지 빠르던 쿼리가 갑자기 느려졌다."** 데이터가 늘어 인덱스를 타야 하는데, 옵티마이저는 여전히 "테이블이 작으니 풀스캔이 싸다"고 판단한다.
- 반대도 있다 — 통계상 행이 많아 인덱스를 탔는데 실제로는 대부분 삭제되어 풀스캔이 나았을 수도.
- **대량 적재(bulk load) 직후**가 가장 위험하다. 통계는 적재 전 상태인데 데이터는 백만 배가 되어 있다.

그래서 실제 DB에는 `ANALYZE` 명령이 있고(우리도 `DbEngine.analyze`가 있다), PostgreSQL은 autovacuum이 통계도 함께 갱신한다. **"대량 적재 후 ANALYZE"** 가 운영 체크리스트에 반드시 들어가는 이유다.
</details>

**2.** distinct = 1인 컬럼에 동등 조건을 걸면 selectivity는? distinct = N이면? 각각 어떤 실행 방법이 유리한가?

<details><summary>답</summary>

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
fun equalitySelectivity(column: String): Double {
    val distinct = perColumnDistinct[column] ?: return 1.0
    return if (distinct == 0L) 1.0 else 1.0 / distinct
}
```

| distinct | selectivity | 의미 | 유리한 방법 |
|---|---|---|---|
| 1 | `1/1 = 1.0` | **전체 행이 조건에 맞는다** | 풀스캔. 인덱스를 타면 모든 행을 인덱스 경유로 읽어 오히려 느리다 |
| N (전부 다름) | `1/N ≈ 0` | **한 행만 맞는다** | 인덱스. 100만 행 중 1행을 위해 전체를 읽을 이유가 없다 |

**핵심은 "인덱스가 항상 빠르지 않다"**는 것이다. 인덱스 조회는 `인덱스 탐색 → 행 위치 → 그 행 읽기`인데, 맞는 행이 많으면 이 왕복이 **무작위 접근**이 되어 순차 풀스캔보다 느려진다.

경험칙으로 **선택도가 대략 5~10%를 넘으면 풀스캔이 유리**하다고 본다(디스크 특성에 따라 다르다). 옵티마이저가 하는 판단이 정확히 이것이고, 그래서 `distinct` 통계가 필요하다.

성별 컬럼(distinct = 2)에 인덱스를 걸어도 안 쓰이는 이유가 여기 있다.
</details>

**3.** `1 / distinct`는 **값이 고르게 분포한다**고 가정한다. 90%가 한 값에 몰려 있으면 얼마나 틀리나?

<details><summary>답</summary>

`status` 컬럼에 값이 `active`(90만 행), `pending`(5만), `done`(5만) 이라고 하자. distinct = 3이므로:

```
추정 selectivity = 1/3 ≈ 33%      → 어떤 값이든 33만 행이 나올 것이라고 본다
실제:  status='active'  → 90만 행 (2.7배 과소추정)
       status='done'    →  5만 행 (6.6배 과대추정)
```

**같은 컬럼인데 값에 따라 정반대로 틀린다.** 그래서 `WHERE status='done'`이면 인덱스가 유리한데 풀스캔을 고르고, `WHERE status='active'`면 반대로 고른다.

실제 DB의 해법이 **히스토그램**이다 — 컬럼 값의 분포를 구간별로 나눠 저장한다. PostgreSQL은 `pg_statistic`에 두 가지를 둔다:

- **MCV**(most common values) — 자주 나오는 값과 그 빈도를 따로 목록으로. `active`가 90%라는 것을 그대로 안다.
- **히스토그램** — 나머지 값들의 분포를 구간으로 근사.

그러면 `status='active'`의 선택도를 MCV에서 직접 읽어 90%로 추정한다.

**균등 분포 가정은 옵티마이저의 가장 흔한 오판 원인**이고, 현실 데이터는 거의 항상 치우쳐 있다(멱법칙 분포).
</details>

**4.** 옵티마이저가 결과를 바꾸는 변환을 만들어봐라 — `Filter`를 통째로 빼먹는 최적화. 어느 테스트가 잡나?

<details><summary>답</summary>

**실측: `Optimizer가 PhysicalPlan으로 변환 + 실행`이 실패한다** (2개 중 1개).

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
// 바꾼 뒤: Filter를 안 만들고 자식을 그대로 통과
PhysicalPlan(child.root, cost)
```

테스트가 `WHERE id = 5`로 1건을 기대하는데 10건이 전부 나온다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
val results = physical.root.iterator().toList()
assertEquals(1, results.size)      // ← 10이 나온다
```

**이 테스트가 CI-3(결과 불변)의 유일한 방어선**이다. 옵티마이저는 정의상 "계획을 바꾸는" 물건이라, 바꾸다가 **의미까지 바꾸는** 실수를 하기 쉽다. 그리고 그 실수는 성능 문제가 아니라 **틀린 답**으로 나타난다 — 훨씬 나쁘다.

실무에서 옵티마이저를 검증하는 방법이 이 원리의 확장이다:

- 같은 질의를 **최적화 켜고/끄고** 각각 실행해 결과를 대조한다(PostgreSQL의 `enable_indexscan = off` 같은 스위치가 이 용도로도 쓰인다).
- 무작위 질의를 생성해 두 경로의 결과를 비교한다(**differential testing**).

테스트가 2개뿐인 이 세션에서, 그중 하나가 이걸 지키고 있다는 점을 확인해둬라.
</details>

## 8. 다음 한계

계획을 세울 수 있게 됐지만 **입력이 여전히 코드다.** 사람이 `Filter(SeqScan(heap), Expression.gt(...))`를 손으로 조립해야 한다. SQL 문자열을 받는 입구가 없다.

→ **단계 12 SQL Parser**.
