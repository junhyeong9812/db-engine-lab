# impl/19-02 — SchemaVersionLog (X3)

> **검증**: SchemaVersionTest 2 PASSED.
> 작성 파일:
> - 신규: `src/main/kotlin/com/dbenginelab/catalog/SchemaVersion.kt`

## 0. 보강 동기
codex X3: 컬럼 추가·삭제·NULL·variable-length·catalog version 부재. 우리는 schema version log 도입.

## 1. invariant
- record() 호출 시 version = currentVersion + 1, 자동 timestamp.
- 변경 이력 append-only — 옛 version 안 지움.
- reopen 시 파일에서 복원.

## 2. 코드 — 한 줄 한 줄

작성 위치: `src/main/kotlin/com/dbenginelab/catalog/SchemaVersion.kt`

```kotlin
package com.dbenginelab.catalog                                      // catalog 패키지

data class SchemaChange(                                              // 한 변경 이력
    val version: Int,                                                // 1부터 monotonic
    val timestamp: Long,
    val description: String,                                          // "CREATE TABLE ...", "ADD COLUMN ..."
)

class SchemaVersionLog(private val path: String) {
    private val changes: MutableList<SchemaChange> = mutableListOf()

    init {
        load()                                                       // 기존 파일 있으면 읽음
    }

    fun currentVersion(): Int = changes.lastOrNull()?.version ?: 0   // 비어있으면 0

    fun record(description: String): SchemaChange {
        val change = SchemaChange(
            version = currentVersion() + 1,                          // Q: 동시 record 시 race?
            timestamp = System.currentTimeMillis(),
            description = description,
        )
        // <details><summary>A</summary>
        // 학습 단순화 — single-thread 가정. multi-thread는 @Synchronized.
        // </details>
        changes.add(change)
        appendLine(change)                                            // 즉시 파일에 append
        return change
    }

    fun history(): List<SchemaChange> = changes.toList()              // 방어 복사

    private fun load() {
        val file = java.io.File(path)
        if (!file.exists()) return
        file.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val parts = line.split('\t', limit = 3)                   // tab-separated
            require(parts.size == 3) { "malformed schema version log line: $line" }
            changes.add(SchemaChange(
                version = parts[0].toInt(),
                timestamp = parts[1].toLong(),
                description = parts[2],
            ))
        }
    }

    private fun appendLine(change: SchemaChange) {
        val file = java.io.File(path)
        file.parentFile?.mkdirs()                                     // 디렉토리 보장
        file.appendText("${change.version}\t${change.timestamp}\t${change.description}\n")
    }
}
```

## 3. 검증 (2 PASSED)
- record + reopen → 복원
- 비어있으면 currentVersion=0

## 4. 깨뜨릴 과제
- description에 tab 포함 시? (현재는 깨짐 — escape 필요)
- migration 자동 application — recorded change를 catalog에 자동 적용?
- variable-length 컬럼 추가 시 기존 row 어떻게?
