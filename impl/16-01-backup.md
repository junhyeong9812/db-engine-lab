# impl/16-01 — Logical Backup (한 줄 한 줄)

> **검증**: BackupTest 1 PASSED.
> 작성 파일:
> - 신규 패키지: `src/main/kotlin/com/dbenginelab/backup/`
> - 신규: Backup.kt (LogicalBackup)
> - 신규 테스트: BackupTest.kt

## 0. 참조
PostgreSQL pg_dump.

## 1. invariant
- dump → restore SQL 문 라인 정확.
- NULL → NULL keyword.
- String → single-quote escape.

## 2. Backup.kt — 한 줄 한 줄

```kotlin
package com.dbenginelab.backup                                       // 신규 backup 패키지

import com.dbenginelab.catalog.Catalog
import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.table.TableHeap
import java.io.*

class LogicalBackup {
    fun dump(catalog: Catalog, heaps: Map<String, TableHeap>, outPath: String) {
        BufferedWriter(FileWriter(outPath)).use { w ->
            for (tableName in catalog.listTables()) {
                val schema = catalog.getTable(tableName)
                val heap = heaps[tableName] ?: continue
                w.write(createTableStmt(schema)); w.newLine()        // CREATE TABLE 먼저
                for (tuple in heap.scan()) {
                    w.write(insertStmt(tuple)); w.newLine()           // 각 row를 INSERT로
                }
            }
        }
    }

    fun restore(inPath: String): List<String> {                      // Q: 왜 SQL parsing 안 함?
        val statements = mutableListOf<String>()
        BufferedReader(FileReader(inPath)).useLines { lines ->
            for (line in lines) if (line.isNotBlank()) statements.add(line)
        }
        return statements
        // <details><summary>A</summary>
        // restore = 라인 반환만. caller가 DbEngine.execute(stmt) 호출. 분리로 dump/restore 독립 테스트.
        // </details>
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
                null -> "NULL"                                        // NULL keyword
                // Q: single quote escape — ' → ''?
                is String -> "'${v.replace("'", "''")}'"
                else -> v.toString()
            }
            // <details><summary>A</summary>
            // SQL 표준 string literal = single quote. escape는 '' (double single). double quote는 identifier 인용 (PostgreSQL).
            // </details>
        }
        return "INSERT INTO ${tuple.schema.name} VALUES ($vals);"
    }
}
```

## 3. 검증 (1 PASSED)
- dump → restore SQL 라인 반환 + NULL/INSERT 패턴 검증

## 4. 깨뜨릴 과제
- 큰 STRING에 newline 포함 → 깨짐. escape 어떻게?
- BLOB/binary → base64?
- PITR (point-in-time recovery) — WAL 합치기?
