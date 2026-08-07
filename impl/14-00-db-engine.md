# impl/14-00 — DbEngine Facade (보강 C2)

> **종류**: 세션형 (지금까지의 부품을 처음으로 하나의 입구로 묶는다)
> **상위 단계**: `docs/stages/14-wire-protocol.md`
> **코드 정본**: git `5505edc` — "complete: 21 stages + 12 보강 (120/120 tests)"
> **이 세션의 범위**: SQL 문자열 하나를 넣으면 행이 나오는 **첫 end-to-end 입구.**
> **작성 파일**:
> - 신규 패키지: `src/main/kotlin/com/dbenginelab/engine/`
> - 신규: `engine/DbEngine.kt`
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/engine/DbEngineTest.kt`
> **검증**: `DbEngineTest` 5 PASSED
> **예상 타이핑 시간**: 50분

---

## 0. 참조

- H2의 `org.h2.engine.Database`를 크게 단순화.
- **보강 동기(C2)**: `Catalog`·`TableHeap`·`Optimizer`·`Parser`가 전부 독립 부품이라 **사용자가 직접 조립해야 했다.** 조립 순서를 아는 것이 곧 이 DB를 아는 것인데, 그 지식이 코드 어디에도 없었다.

## 1. 만족시킬 invariant

- **CI-1**: `CREATE TABLE` → catalog 등록 + heap 파일 자동 생성.
- **CI-2**: `INSERT` → SQL 리터럴 → `Tuple` → `heap.insert`.
- **CI-3**: `SELECT` → Lexer → Parser → Translator → Optimizer → Executor → 행.
- **CI-4**: reopen 후 catalog가 자동 복원되고 기존 테이블이 자동으로 열린다.
- **CI-5**: `DROP TABLE` → heap을 닫고 파일을 삭제한다.

## 2. 의존성

**지금까지 만든 거의 전부다.** — `catalog`(4·5), `table`(6), `executor`(6), `optimizer`(11), `sql`(12·13-02).

## 3. 문제 정의 (TDD step 1)

부품은 다 있는데 조립품이 없다. 사용자가 `SELECT name FROM users WHERE age > 28` 한 줄을 실행하려면 지금은 이렇게 해야 한다:

```
Lexer → Parser → Ast.Select → Translator → LogicalPlan
  → SimpleOptimizer → PhysicalPlan → iterator()
```

여기에 catalog에서 스키마를 찾고 heap을 열고 통계를 붙이는 일까지 얹힌다. **이 순서를 외우는 것이 사용법이 되어서는 안 된다.**

facade의 일은 그 순서를 한 곳에 적어두는 것이다. 그래서 이 파일을 읽는 것이 곧 **"이 DB가 질의를 어떻게 처리하는가"의 답**이 된다 — 이번 세션에서 가장 중요한 것은 코드를 치는 것이 아니라 `execute()`의 흐름을 눈으로 따라가는 것이다.

CI-4도 눈여겨봐라. reopen 시 catalog에 등록된 모든 테이블의 heap을 다시 열어야 한다. 안 그러면 재시작 후 첫 질의가 "테이블 없음"으로 실패한다.

## 4. 실패 테스트 (TDD step 2)

```kotlin
// src/test/kotlin/com/dbenginelab/engine/DbEngineTest.kt @ 5505edc
package com.dbenginelab.engine

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DbEngineTest {

    @Test
    fun `end-to-end CREATE INSERT SELECT WHERE PROJECT`(@TempDir tempDir: Path) {
        DbEngine(tempDir.toString()).use { db ->
            db.execute("CREATE TABLE users (id BIGINT NOT NULL, name STRING NOT NULL, age INT, PRIMARY KEY (id))")
            db.execute("INSERT INTO users VALUES (1, 'Alice', 30)")
            db.execute("INSERT INTO users VALUES (2, 'Bob', 25)")
            db.execute("INSERT INTO users VALUES (3, 'Charlie', 35)")

            val r1 = db.execute("SELECT * FROM users") as DbEngine.QueryResult.Rows
            assertEquals(3, r1.rows.size)
            assertEquals(listOf("id", "name", "age"), r1.columns)

            val r2 = db.execute("SELECT name FROM users WHERE age > 28") as DbEngine.QueryResult.Rows
            assertEquals(2, r2.rows.size)
            assertEquals(setOf("Alice", "Charlie"), r2.rows.map { it[0] }.toSet())
            assertEquals(listOf("name"), r2.columns)
        }
    }

    @Test
    fun `reopen 후 데이터 보존`(@TempDir tempDir: Path) {
        DbEngine(tempDir.toString()).use { db ->
            db.execute("CREATE TABLE t (k BIGINT NOT NULL, v STRING NOT NULL, PRIMARY KEY (k))")
            db.execute("INSERT INTO t VALUES (1, 'kept')")
        }
        DbEngine(tempDir.toString()).use { db ->
            val r = db.execute("SELECT * FROM t") as DbEngine.QueryResult.Rows
            assertEquals(1, r.rows.size)
            assertEquals(1L, r.rows[0][0])
            assertEquals("kept", r.rows[0][1])
        }
    }

    @Test
    fun `DROP TABLE 후 SELECT 실패`(@TempDir tempDir: Path) {
        DbEngine(tempDir.toString()).use { db ->
            db.execute("CREATE TABLE x (id BIGINT NOT NULL)")
            db.execute("DROP TABLE x")
            val ex = runCatching { db.execute("SELECT * FROM x") }.exceptionOrNull()
            assertTrue(ex != null)
        }
    }

    @Test
    fun `analyze 후 optimizer가 statistics 사용`(@TempDir tempDir: Path) {
        DbEngine(tempDir.toString()).use { db ->
            db.execute("CREATE TABLE n (id BIGINT NOT NULL, c STRING NOT NULL, PRIMARY KEY (id))")
            for (i in 1..50) db.execute("INSERT INTO n VALUES ($i, 'r$i')")
            db.analyze("n")
            val r = db.execute("SELECT id FROM n WHERE id = 25") as DbEngine.QueryResult.Rows
            assertEquals(1, r.rows.size)
            assertEquals(25L, r.rows[0][0])
        }
    }

    @Test
    fun `복합 WHERE - AND OR`(@TempDir tempDir: Path) {
        DbEngine(tempDir.toString()).use { db ->
            db.execute("CREATE TABLE p (id BIGINT NOT NULL, x INT, PRIMARY KEY (id))")
            for (i in 1..10) db.execute("INSERT INTO p VALUES ($i, $i)")
            val r = db.execute("SELECT id FROM p WHERE x >= 3 AND x <= 7") as DbEngine.QueryResult.Rows
            assertEquals(5, r.rows.size)
        }
    }
}
```

**예상 실패**: **컴파일 실패** — `Unresolved reference: DbEngine`.

## 5. 구현 코드 (TDD step 3 — make it pass)

```kotlin
// src/main/kotlin/com/dbenginelab/engine/DbEngine.kt @ 5505edc
package com.dbenginelab.engine

import com.dbenginelab.catalog.Catalog
import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.optimizer.SimpleOptimizer
import com.dbenginelab.optimizer.Statistics
import com.dbenginelab.optimizer.StatisticsCollector
import com.dbenginelab.sql.Lexer
import com.dbenginelab.sql.Parser
import com.dbenginelab.sql.Statement
import com.dbenginelab.sql.Translator
import com.dbenginelab.storage.BufferPool
import com.dbenginelab.storage.PagedFile
import com.dbenginelab.table.TableHeap
import java.io.Closeable
import java.io.File

/**
 * Stage 14 보강 (C2): End-to-end SQL DbEngine.
 *
 * SQL string → Lexer → Parser → (Translator | direct exec) → Optimizer → Executor → Result.
 *
 * Supported:
 *  - CREATE TABLE: catalog.registerTable + open heap
 *  - INSERT INTO ... VALUES (...): heap.insert
 *  - SELECT [* | cols] FROM table [WHERE expr]: optimizer + iterator
 *  - DROP TABLE: catalog.dropTable + close heap
 *
 * Not supported: JOINs, subqueries, aggregates, transactions through SQL
 * (transactions are programmatic via wal.TransactionManager — stage 14+ work).
 */
class DbEngine(private val dataDir: String) : Closeable {

    private val catalog: Catalog
    private val heaps: MutableMap<String, TableHeap> = mutableMapOf()
    private val pagedFiles: MutableMap<String, PagedFile> = mutableMapOf()
    private val bufferPools: MutableMap<String, BufferPool> = mutableMapOf()
    private val stats: MutableMap<String, Statistics> = mutableMapOf()

    init {
        File(dataDir).mkdirs()
        catalog = Catalog("$dataDir/catalog.meta")
        for (name in catalog.listTables()) openHeap(name)
    }

    sealed class QueryResult {
        data class Rows(val columns: List<String>, val rows: List<List<Any?>>) : QueryResult()
        data class Updated(val count: Int) : QueryResult()
        data class Created(val tableName: String) : QueryResult()
        data class Dropped(val tableName: String) : QueryResult()
    }

    fun execute(sql: String): QueryResult {
        val stmt = Parser(Lexer(sql).tokenize()).parseStatement()
        return when (stmt) {
            is Statement.Select -> executeSelect(stmt)
            is Statement.Insert -> executeInsert(stmt)
            is Statement.CreateTable -> executeCreate(stmt)
            is Statement.DropTable -> executeDrop(stmt)
        }
    }

    private fun executeSelect(stmt: Statement.Select): QueryResult.Rows {
        val logical = Translator.toLogicalPlan(stmt)
        val optimizer = SimpleOptimizer(
            tableLookup = { name -> heaps[name] ?: error("table $name not found") },
            statisticsLookup = { name -> stats[name] },
        )
        val physical = optimizer.optimize(logical)
        val rows = physical.root.iterator().toList()
        val columns = physical.root.outputSchema.columns.map { it.name }
        return QueryResult.Rows(columns, rows.map { tuple -> tuple.values.toList() })
    }

    private fun executeInsert(stmt: Statement.Insert): QueryResult.Updated {
        val heap = heaps[stmt.table] ?: error("table ${stmt.table} not found")
        val schema = heap.schema
        require(stmt.values.size == schema.columnCount) {
            "INSERT values count ${stmt.values.size} != schema columns ${schema.columnCount}"
        }
        val values = stmt.values.mapIndexed { i, expr ->
            literalValue(expr, schema, i)
        }
        heap.insert(Tuple(schema, values))
        return QueryResult.Updated(1)
    }

    private fun executeCreate(stmt: Statement.CreateTable): QueryResult.Created {
        val pk = stmt.primaryKey?.let {
            listOf(com.dbenginelab.catalog.Constraint.PrimaryKey(it))
        } ?: emptyList()
        val schema = TableSchema(stmt.name, stmt.columns, pk)
        catalog.registerTable(schema)
        openHeap(stmt.name)
        return QueryResult.Created(stmt.name)
    }

    private fun executeDrop(stmt: Statement.DropTable): QueryResult.Dropped {
        closeHeap(stmt.name)
        catalog.dropTable(stmt.name)
        File("$dataDir/${stmt.name}.data").delete()
        return QueryResult.Dropped(stmt.name)
    }

    fun analyze(tableName: String) {
        val heap = heaps[tableName] ?: error("table $tableName not found")
        stats[tableName] = StatisticsCollector.analyze(tableName, heap)
    }

    private fun openHeap(name: String) {
        val schema = catalog.getTable(name)
        val pf = PagedFile("$dataDir/$name.data")
        val bp = BufferPool(pf, capacity = 32)
        val heap = TableHeap(schema, pf, bp)
        pagedFiles[name] = pf
        bufferPools[name] = bp
        heaps[name] = heap
    }

    private fun closeHeap(name: String) {
        bufferPools.remove(name)?.close()
        pagedFiles.remove(name)?.close()
        heaps.remove(name)
        stats.remove(name)
    }

    private fun literalValue(expr: com.dbenginelab.sql.SqlExpr, schema: TableSchema, columnIndex: Int): Any? {
        val col = schema.columns[columnIndex]
        return when (expr) {
            is com.dbenginelab.sql.SqlExpr.LitNumber -> when (col.type) {
                com.dbenginelab.catalog.Type.INT -> expr.value.toInt()
                com.dbenginelab.catalog.Type.BIGINT -> expr.value
                else -> error("column ${col.name} expects ${col.type}, got number")
            }
            is com.dbenginelab.sql.SqlExpr.LitString -> {
                require(col.type == com.dbenginelab.catalog.Type.STRING) {
                    "column ${col.name} expects ${col.type}, got string"
                }
                expr.value
            }
            com.dbenginelab.sql.SqlExpr.LitNull -> null
            else -> error("INSERT VALUES must be literals (got ${expr::class.simpleName})")
        }
    }

    override fun close() {
        for (name in heaps.keys.toList()) closeHeap(name)
    }
}
```

## 6. 검증 테스트 (TDD step 4 — green)

테스트 파일은 §4에서 저장한 것이 그대로 최종본이다.

```bash
./gradlew test --tests 'com.dbenginelab.engine.DbEngineTest'
```

**기대 결과**: `DbEngineTest` **5 PASSED**

invariant 대응:
- **CI-1**, **CI-2**, **CI-3** ← `end-to-end CREATE INSERT SELECT WHERE PROJECT`
- **CI-4** ← `reopen 후 데이터 보존`
- **CI-5** ← `DROP TABLE 후 SELECT 실패`
- (옵티마이저 연결) ← `analyze 후 optimizer가 statistics 사용`
- (파서·번역기 연결) ← `복합 WHERE - AND OR`

**이 5개가 13-02 Translator의 유일한 검증이기도 하다.** 13-02 §5 과제 1번(단위 테스트 직접 작성)이 왜 필요한지 여기서 확인된다 — 이 중 하나가 깨지면 원인이 파서인지 번역기인지 옵티마이저인지 알 수 없다.

## 7. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

**1.** `execute()`의 단계 하나(Optimizer)를 건너뛰고 LogicalPlan을 직접 실행해봐라. 결과가 같은가? 같다면 옵티마이저는 지금 무엇을 하고 있나?

<details><summary>답</summary>

**결과가 완전히 같다.** `SimpleOptimizer.buildPhysical`이 하는 일은 논리 노드를 물리 연산자로 **1:1 치환**하는 것뿐이기 때문이다:

```
LogicalPlan.Scan        → SeqScan
LogicalPlan.FilterNode  → Filter
LogicalPlan.ProjectNode → Project
```

계획을 **고르지 않는다.** 대안이 하나뿐이니 고를 것이 없다. 비용(`PhysicalCost`)을 계산하긴 하는데 **아무 데도 쓰이지 않는다** — 비교할 상대가 없기 때문이다.

즉 지금의 옵티마이저는 **"자리만 잡아둔 상태"** 다. 진짜 최적화가 시작되려면 최소한 하나가 필요하다:

- **대안이 둘 이상** — 예를 들어 `IndexScan`이 있으면 `SeqScan`과 비용을 비교할 수 있다. 06-04의 `IndexedTableHeap`이 있으니 재료는 있는데 옵티마이저가 그걸 모른다.
- **또는 계획 재작성** — 06-02 과제 3번의 predicate pushdown처럼 순서를 바꾸는 것.

**이 사실을 아는 것이 이 과제의 핵심이다.** "옵티마이저가 있다"와 "최적화를 한다"는 다른 이야기이고, 지금은 전자까지다.
</details>

**2.** reopen 시 기존 테이블을 다시 여는 부분(`for (name in catalog.listTables()) openHeap(name)`)을 지워라. 어느 테스트가 잡나?

<details><summary>답</summary>

**실측: `reopen 후 데이터 보존`이 실패한다** (5개 중 1개).

문제는 **실패 메시지가 원인을 안 알려준다**는 것이다:

```
catalog에는 테이블 t가 등록되어 있다 (파일에서 복원됨)
그런데 heaps 맵은 비어 있다 (openHeap을 안 불렀으므로)
→ SELECT * FROM t → "테이블 없음" 류의 에러
```

사용자가 보는 메시지는 "테이블이 없다"인데 **catalog에는 분명히 있다.** 진짜 원인은 "메타데이터는 복원했는데 데이터 파일 핸들을 안 열었다"이고, 그 거리가 멀다.

이런 종류의 오류 메시지를 만나면 확인할 것이 하나 있다 — **"없다"고 말하는 주체가 누구인가.** `catalog.getTable()`이 던진 것인지 `heaps[name]`이 null이라 던진 것인지에 따라 원인이 정반대다. 진단 메시지가 그 둘을 구분해주면 몇 시간이 절약된다.

02-01 과제 4번(`checkRange`가 진단 가능성을 위해 존재)과 같은 주제다 — **에러 메시지의 품질은 기능이다.**
</details>

**3.** `DROP TABLE`에서 파일 삭제(`File("$dataDir/${stmt.name}.data").delete()`)를 지워라. 테스트는 통과하나?

<details><summary>답</summary>

**실측: 5개 전부 통과한다.**

`DROP TABLE 후 SELECT 실패` 테스트는 **catalog에서 사라졌는지만** 확인한다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
db.execute("DROP TABLE x")
val ex = runCatching { db.execute("SELECT * FROM x") }.exceptionOrNull()
assertTrue(ex != null)
```

파일이 남아 있어도 catalog에 없으니 SELECT는 실패한다. **테스트가 통과한다.**

남은 파일들이 만드는 문제:

1. **디스크가 조용히 찬다.** 임시 테이블을 만들고 지우기를 반복하는 워크로드라면 며칠 만에 디스크가 찬다. 그리고 `df`로 보면 공간이 없는데 DB는 "테이블이 없다"고 한다.
2. **더 위험한 것 — 같은 이름으로 다시 만들면?** `CREATE TABLE x`가 `openHeap("x")`를 부르는데, 그 파일이 이미 있으면 **옛 데이터가 그대로 살아난다.** 지웠다고 생각한 데이터가 되돌아온다.

2번이 진짜 결함이다. 이걸 잡는 테스트를 직접 써봐라:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
db.execute("CREATE TABLE x (id BIGINT NOT NULL)")
db.execute("INSERT INTO x VALUES (1)")
db.execute("DROP TABLE x")
db.execute("CREATE TABLE x (id BIGINT NOT NULL)")
val r = db.execute("SELECT * FROM x") as DbEngine.QueryResult.Rows
assertEquals(0, r.rows.size)      // ← 파일 삭제가 없으면 1이 나온다
```
</details>

**4.** 존재하지 않는 컬럼을 `SELECT`해봐라. 어느 단계에서 걸리나 — 파서인가, 번역기인가, 실행 시점인가?

<details><summary>답</summary>

**실행 시점**에 걸린다. 정확히는 `Project`의 `init`이다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
val keptCols = columnNames.map { name ->
    childCols.firstOrNull { it.name == name }
        ?: error("Project: column $name not in child schema")
}
```

파서는 컬럼 이름을 **문자열로 받을 뿐** 스키마를 모른다. 번역기도 마찬가지다 — `LogicalPlan.ProjectNode(plan, statement.columns)`에 이름을 그대로 넘긴다. **스키마를 아는 최초의 지점이 물리 계획을 만드는 시점**이고, 그래서 거기서 걸린다.

다행히 `Project`의 검사는 **`init`에 있어서 연산자를 만드는 순간** 터진다 — 100만 행을 훑다가 중간에 죽지 않는다(06-02에서 짚은 지점).

**더 일찍 걸리는 편이 나은 이유:**

1. **자원을 안 쓴다.** 계획 수립 전에 걸리면 heap을 열 필요도, 통계를 계산할 필요도 없다.
2. **에러 메시지가 정확해진다.** "질의의 3번째 컬럼 `nmae`이 테이블 `users`에 없다. `name`을 의도했나?" 같은 안내가 가능하다.
3. **prepared statement를 쓸 수 있다.** 질의를 미리 검증해두고 실행만 반복하려면 검증이 실행과 분리되어야 한다.

실제 DB는 이 단계를 **의미 분석(semantic analysis / binding)** 이라 부르고 파서와 옵티마이저 사이에 둔다. 파서는 문법만, binder가 이름을 실제 객체에 묶고, 옵티마이저는 그 다음이다. 우리 구조에는 그 층이 없어서 검사가 맨 끝으로 밀렸다.
</details>

## 8. 다음 한계

DB가 동작하지만 **같은 프로세스 안에서만** 쓸 수 있다. 다른 컴퓨터의 클라이언트가 접속할 방법이 없다.

→ **Phase B 시작. 14-01 Wire Protocol** — 바이트로 말하는 법부터.
