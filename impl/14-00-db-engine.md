# impl/14-00 — DbEngine Facade (C2 보강)

> **검증**: DbEngineTest 5 PASSED. **첫 end-to-end entry point.**
> 보강: 기존 컴포넌트(Catalog/TableHeap/Optimizer/Parser)가 독립 — 사용자가 직접 묶어야 했음. 이 façade가 통합.

## 0. 참조 — H2 `org.h2.engine.Database` (단순화).

## 1. invariant
- CREATE TABLE → catalog + heap 자동 생성.
- INSERT → SQL literal → Tuple → heap.insert.
- SELECT → Lexer → Parser → Translator → Optimizer → Executor → Rows.
- reopen 후 catalog 자동 복원, 기존 테이블 자동 open.
- DROP TABLE → close heap + 파일 삭제.

## 2. 핵심 결정
- **Per-table PagedFile/BufferPool/TableHeap** 자동 관리 — 사용자가 직접 안 만들어도 됨.
- **QueryResult sealed class** — Rows/Updated/Created/Dropped.
- **literalValue 변환** — SqlExpr.LitNumber(Long) → schema 타입에 맞게 Int/Long 변환.
- **analyze(tableName)** — 명시 호출, optimizer가 stats 사용.
- Transaction은 미통합 (programmatic만 — stage 14+).

## 3. 코드 핵심

```kotlin
class DbEngine(private val dataDir: String) : Closeable {
    private val catalog: Catalog
    private val heaps: MutableMap<String, TableHeap> = mutableMapOf()
    // ... pagedFiles, bufferPools, stats

    init {
        File(dataDir).mkdirs()
        catalog = Catalog("$dataDir/catalog.meta")
        for (name in catalog.listTables()) openHeap(name)  // ← reopen 시 자동
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
        val optimizer = SimpleOptimizer({ heaps[it] ?: error("table $it not found") }, { stats[it] })
        val physical = optimizer.optimize(logical)
        val rows = physical.root.iterator().toList()
        val columns = physical.root.outputSchema.columns.map { it.name }
        return QueryResult.Rows(columns, rows.map { tuple -> tuple.values.toList() })
    }
    // ... executeInsert/Create/Drop
}
```

## 4. 검증 테스트 (5 PASSED)
- end-to-end CREATE + INSERT + SELECT * + SELECT WHERE + Project
- reopen 후 데이터 보존
- DROP TABLE 후 SELECT 실패
- analyze 후 optimizer 사용
- 복합 WHERE (AND OR)

## 5. 깨뜨릴 과제
- SELECT JOIN? (Translator + LogicalPlan 확장 필요)
- INSERT INTO ... SELECT? (subquery)
- Transaction in SQL (BEGIN/COMMIT)?

## 6. 다음 한계
- 단일 thread/session — multi-session entry는 ConnectionPool 통합 필요.
- Transaction 미통합 → stage 9+10 통합 (C3+X4) 후 facade 확장.
