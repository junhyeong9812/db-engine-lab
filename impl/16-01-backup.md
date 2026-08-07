# impl/16-01 — Logical Backup (SQL 덤프 · 복원)

> **종류**: 세션형
> **상위 단계**: `docs/stages/16-backup.md`
> **코드 정본**: git `5505edc` — "complete: 21 stages + 12 보강 (120/120 tests)"
> **이 세션의 범위**: 데이터를 **사람이 읽을 수 있는 SQL 문장**으로 뽑고 되넣는다.
> **작성 파일**:
> - 신규 패키지: `src/main/kotlin/com/dbenginelab/backup/`
> - 신규: `backup/Backup.kt`
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/backup/BackupTest.kt`
> **검증**: `BackupTest` 1 PASSED
> **예상 타이핑 시간**: 30분

---

## 0. 참조

- `pg_dump`의 개념을 최소 형태로.
- **08-02의 물리 백업(`PhysicalBackup`)과의 차이**: 물리 백업은 **파일을 그대로 복사**한다 — 빠르지만 같은 엔진·같은 버전에서만 복원된다. 논리 백업은 **SQL 문장으로 뽑는다** — 느리지만 사람이 읽을 수 있고 다른 시스템으로 옮길 수 있다. 실제 운영은 **둘 다** 쓴다.

## 1. 만족시킬 invariant

- **CI-1**: dump → restore 후 데이터가 동일하다.

## 2. 의존성

- `impl/14-00-db-engine.md` (`DbEngine` — 덤프는 SELECT로 읽고 복원은 INSERT로 넣는다)

## 3. 문제 정의 (TDD step 1)

08-02에서 물리 백업을 만들었는데 왜 또 백업인가? 물리 백업은 **바이트 복사**라 이런 상황에 무력하다:

- 실수로 지운 테이블 **하나만** 되살리고 싶다 → 물리 백업은 전체 복원뿐이다.
- 백업 파일 안에 무엇이 들어있는지 보고 싶다 → 바이트 더미다.
- 다른 DB로 옮기고 싶다 → page 형식이 다르니 불가능하다.

논리 백업은 이 셋을 다 해결한다. 대신 느리고(모든 행을 SQL로 문자열화), 복원 시 인덱스를 다시 만들어야 한다.

주의할 점 하나 — **문자열 이스케이프**다. `name`이 `O'Brien`이면 덤프한 SQL이 `INSERT ... VALUES ('O'Brien')`이 되어 깨진다. 이걸 놓치면 **평소엔 잘 되다가 특정 데이터에서만** 백업이 망가진다. 백업이 망가지는 것은 백업이 없는 것보다 나쁘다 — 있다고 믿기 때문이다.

## 4. 실패 테스트 (TDD step 2)

```kotlin
// src/test/kotlin/com/dbenginelab/backup/BackupTest.kt @ 5505edc
package com.dbenginelab.backup

import com.dbenginelab.catalog.Catalog
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
import kotlin.test.assertTrue

class BackupTest {
    @Test fun `dump restore SQL 라인`(@TempDir tempDir: Path) {
        val schema = TableSchema("users", listOf(
            ColumnDef("id", Type.BIGINT, nullable = false),
            ColumnDef("name", Type.STRING, nullable = true),
        ))
        val catPath = tempDir.resolve("c.meta").toString()
        val dataPath = tempDir.resolve("u.data").toString()
        val dumpPath = tempDir.resolve("dump.sql").toString()
        val catalog = Catalog(catPath).apply { registerTable(schema) }
        PagedFile(dataPath).use { pf -> BufferPool(pf, 16).use { bp ->
            val heap = TableHeap(schema, pf, bp)
            heap.insert(Tuple(schema, listOf(1L, "Alice")))
            heap.insert(Tuple(schema, listOf(2L, null)))
            LogicalBackup().dump(catalog, mapOf("users" to heap), dumpPath)
        }}
        val stmts = LogicalBackup().restore(dumpPath)
        assertTrue(stmts.any { it.startsWith("CREATE TABLE") })
        assertTrue(stmts.any { it.contains("INSERT INTO users VALUES (1") })
        assertTrue(stmts.any { it.contains("NULL") })
    }
}
```

**예상 실패**: **컴파일 실패** — `Unresolved reference: LogicalBackup`.

## 5. 구현 코드 (TDD step 3 — make it pass)

```kotlin
// src/main/kotlin/com/dbenginelab/backup/Backup.kt @ 5505edc
package com.dbenginelab.backup

import com.dbenginelab.catalog.Catalog
import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.table.TableHeap
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.FileReader
import java.io.FileWriter

class LogicalBackup {
    fun dump(catalog: Catalog, heaps: Map<String, TableHeap>, outPath: String) {
        BufferedWriter(FileWriter(outPath)).use { w ->
            for (tableName in catalog.listTables()) {
                val schema = catalog.getTable(tableName)
                val heap = heaps[tableName] ?: continue
                w.write(createTableStmt(schema)); w.newLine()
                for (tuple in heap.scan()) {
                    w.write(insertStmt(tuple)); w.newLine()
                }
            }
        }
    }

    fun restore(inPath: String): List<String> {
        val statements = mutableListOf<String>()
        BufferedReader(FileReader(inPath)).useLines { lines ->
            for (line in lines) if (line.isNotBlank()) statements.add(line)
        }
        return statements
    }

    private fun createTableStmt(schema: TableSchema): String {
        val cols = schema.columns.joinToString(", ") { c ->
            val nn = if (!c.nullable) " NOT NULL" else ""
            "${c.name} ${c.type.name}$nn"
        }
        return "CREATE TABLE ${schema.name} ($cols);"
    }

    private fun insertStmt(tuple: Tuple): String {
        val vals = tuple.values.joinToString(", ") { v ->
            when (v) {
                null -> "NULL"
                // Q: single quote escape — ' → ''?
                is String -> "'${v.replace("'", "''")}'"
                // <details><summary>A</summary>
                // SQL 표준 string literal = single quote. escape는 '' (double single). double quote는 identifier 인용 (PostgreSQL).
                // </details>
                else -> v.toString()
            }
        }
        return "INSERT INTO ${tuple.schema.name} VALUES ($vals);"
    }
}
```

## 6. 검증 테스트 (TDD step 4 — green)

```bash
./gradlew test --tests 'com.dbenginelab.backup.BackupTest'
```

**기대 결과**: `BackupTest` **1 PASSED** (`dump restore SQL 라인`)

**테스트가 하나뿐이다.** invariant도 하나뿐이니 형식상 맞지만, 백업처럼 **실패했을 때 대가가 큰 기능**에 테스트 하나는 명백히 부족하다. §7 과제 1·2번이 그 빈자리를 직접 메우는 문제다.

## 7. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

**1.** 값에 작은따옴표가 들어간 행(`O'Brien`)을 넣고 dump → restore 해봐라. 깨지나?

<details><summary>답</summary>

**dump 쪽은 정상이다.** 이스케이프 코드가 있다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
is String -> "'${v.replace("'", "''")}'"     // O'Brien → 'O''Brien'
```

**실측: 이 이스케이프를 지워도 `BackupTest` 1개가 그대로 통과한다** — 테스트 데이터(`"Alice"`)에 따옴표가 없기 때문이다. 검증되지 않는 코드다.

그런데 진짜 문제는 **restore 쪽**이다. `LogicalBackup.restore`는 파일을 줄 단위로 읽어 **문자열 목록을 돌려줄 뿐**이고, 그 SQL을 실행하는 것은 12-01의 파서다. 그리고 12-01 과제 4번에서 확인했듯 **우리 Lexer는 `''` 이스케이프를 해석하지 못한다.**

```
dump:    INSERT INTO t VALUES ('O''Brien');    ← 올바르게 이스케이프됨
restore: Lexer가 'O' 에서 문자열을 끊는다      ← 깨진다
```

**우리가 만든 백업을 우리가 못 읽는다.** 왕복(round-trip)이 성립하지 않는다.

잡는 테스트:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
heap.insert(Tuple(schema, listOf(1L, "O'Brien")))
LogicalBackup().dump(catalog, mapOf("t" to heap), dumpPath)
val stmts = LogicalBackup().restore(dumpPath)
// 여기서 끝내지 말고 실제로 실행해봐야 한다
stmts.forEach { db.execute(it) }        // ← 여기서 터진다
```

**핵심 교훈**: 백업 테스트는 dump 결과의 문자열을 보는 것으로 끝내면 안 된다. **restore까지 실행해서 데이터가 같은지** 확인해야 왕복이 검증된다. 지금 테스트는 `assertTrue(stmts.any { it.startsWith("CREATE TABLE") })` 수준이라 그 절반만 본다.
</details>

**2.** NULL이 든 행을 dump 해봐라. `NULL`로 나오나 빈 문자열로 나오나? restore 후 유지되나?

<details><summary>답</summary>

dump는 올바르다 — `null -> "NULL"`로 따옴표 없이 출력한다. `BackupTest`도 `assertTrue(stmts.any { it.contains("NULL") })`로 확인한다.

문제는 **restore 경로**다. 이 SQL을 다시 파싱하려면 파서가 `NULL` 키워드를 리터럴로 인식해야 한다. `SqlExpr.LitNull`이 AST에 정의되어 있으니 `WHERE` 절에서는 다룰 수 있는데, **`INSERT ... VALUES` 자리에서도 되는지는 별도 확인이 필요하다.**

직접 해봐라 — `INSERT INTO t VALUES (1, NULL)`을 `db.execute`에 넣어보면 답이 나온다.

여기서 볼 것은 04-01부터 이어진 사슬이다:

```
04-01: NULL bitmap으로 "값 없음"과 "빈 값"을 구분      ✓
06-02: Expression이 UNKNOWN을 null로 전파              ✓
14-01: DataRow가 NULL을 별도로 표현                     ✓
16-01: dump가 NULL을 따옴표 없이 출력                   ✓
  ↑ 여기까지 지켜온 구분이
12-01: 파서가 그것을 되읽을 수 있는가?                  ← 확인 필요
```

**계층 하나만 끊겨도 전체가 무의미해진다.** 왕복 테스트가 있어야 이 사슬이 통째로 검증된다.
</details>

**3.** 100만 행 테이블을 dump하면 메모리가 어떻게 되나? 코드가 전부 모으나, 스트리밍하나?

<details><summary>답</summary>

**dump는 스트리밍이다** — 잘 되어 있다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
BufferedWriter(FileWriter(outPath)).use { w ->
    for (tuple in heap.scan()) {       // Sequence — 한 건씩
        w.write(insertStmt(tuple)); w.newLine()
    }
}
```

행 하나씩 읽어 바로 쓴다. 100만 행이든 1억 행이든 메모리는 일정하다.

**restore는 스트리밍이 아니다:**

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
fun restore(inPath: String): List<String> {
    val statements = mutableListOf<String>()      // ← 전부 메모리에
    BufferedReader(FileReader(inPath)).useLines { lines ->
        for (line in lines) if (line.isNotBlank()) statements.add(line)
    }
    return statements
}
```

`useLines`로 읽기는 게으르게 하는데 **결과를 `List`에 다 모아서 돌려준다.** 100만 행이면 SQL 문자열 100만 개가 힙에 쌓인다. 한 줄에 100바이트만 잡아도 100MB다.

01-01의 `scanAll`, 03-03의 `rangeScan`과 **같은 형태의 문제**가 세 번째로 나온다. 고치는 방향도 같다 — `List<String>` 대신 `Sequence<String>`을 돌려주거나, 아예 `restore(path) { stmt -> … }`처럼 콜백을 받는 것.

**dump는 스트리밍인데 restore는 아니라는 비대칭**을 눈여겨봐라. 쓰는 쪽만 신경 쓰고 읽는 쪽을 놓치는 것은 흔한 실수다.
</details>

**4.** dump 중에 다른 트랜잭션이 데이터를 바꾸면 백업은 **어느 시점의 스냅샷**인가?

<details><summary>답</summary>

**어느 시점도 아니다.** 일관성이 없다.

`heap.scan()`이 순차적으로 훑는 동안 데이터가 바뀌면, 앞부분은 변경 전, 뒷부분은 변경 후가 섞인다:

```
dump 시작 → users 테이블 앞부분 기록
            (이 사이 트랜잭션이 계좌이체: A -100, B +100)
          → users 테이블 뒷부분 기록
결과: A의 차감은 반영 안 됐고 B의 증가는 반영된 백업
     → 복원하면 돈이 100 늘어나 있다
```

**존재한 적 없는 상태**의 백업이 만들어진다. 이것을 **비일관 백업(inconsistent backup)** 이라 하고, 백업 중 가장 위험한 종류다 — 파일은 멀쩡해 보이고 복원도 성공하는데 데이터가 틀렸다.

단계 10의 **MVCC snapshot**이 정확히 이걸 푼다. dump 시작 시 snapshot을 하나 잡고 **그 시점 기준으로만 읽으면**, 도중에 무엇이 커밋되든 백업은 한 시점을 담는다.

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
val snapshot = snapshotProvider.begin()
for (tuple in mvccHeap.scanAt(snapshot)) { … }   // 이런 API가 필요하다
```

`pg_dump`가 실제로 이렇게 동작한다 — 트랜잭션을 열고 `REPEATABLE READ`로 전체를 읽는다. 그래서 **덤프 중에도 서비스가 계속 돌 수 있다.** 락으로 막는 방식이었다면 백업 시간 내내 쓰기가 멈춘다.

MVCC가 "읽기가 쓰기를 막지 않는다"는 성질이 **백업이라는 긴 읽기**에서 가장 큰 값어치를 낸다.
</details>

**5.** 복원 시 제약 위반이 나면 어떻게 되나? 중간까지 넣고 멈추나, 전부 되돌리나? 어느 쪽이 옳은가?

<details><summary>답</summary>

`restore`는 SQL 문자열 목록만 돌려주므로 **실행 정책은 호출자에게 달려 있다.** 지금은 아무 정책도 없다 — 한 줄씩 실행하다 실패하면 거기서 멈추고, **앞서 넣은 것은 그대로 남는다.**

**전부 되돌리는 쪽이 옳다.** 이유:

부분 복원된 DB는 **아무도 그 상태를 모른다.** 100만 행 중 40만 행만 들어간 테이블은 비어 있는 것보다 나쁘다 — 사람이 "복원됐다"고 착각하고 서비스를 열 수 있기 때문이다. 실패는 **명확해야** 한다.

그리고 복원은 재시도 가능해야 하는데, 부분 상태가 남아 있으면 재시도할 때 PK 중복이 나서 **다시 실패한다.** 되돌려야 깨끗한 재시도가 가능하다.

구현하려면 **07-01 `WorkUnit` 또는 08-01 `Transaction`으로 전체를 감싸면 된다** — 이 프로젝트에 이미 재료가 있다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
val tx = txManager.begin()
try { stmts.forEach { tx.execute(it) }; tx.commit() }
catch (e: Exception) { tx.abort(); throw e }
```

다만 현실의 대용량 복원은 트랜잭션 하나로 묶으면 로그가 감당 못 하므로, **배치 단위 커밋 + 실패 지점 기록 + 그 지점부터 재개**로 타협한다. `pg_restore`의 `--exit-on-error`와 `--single-transaction` 옵션이 이 선택지를 그대로 노출한 것이다.
</details>

## 8. 다음 한계

백업은 있지만 **DB가 지금 어떤 상태인지 관찰할 방법이 없다.** 느린 질의가 무엇인지, 초당 몇 건을 처리하는지 아무도 모른다.

→ **단계 17 Metrics**.
