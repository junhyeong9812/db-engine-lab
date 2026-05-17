package com.dbenginelab.catalog

/**
 * Stage 19 보강 (X3): Schema version tracking.
 *
 * 각 TableSchema의 변경 이력을 별도 파일에 누적. OnlineDdl이 schema 변경 시
 * append. Recovery / migration 시 version 비교로 호환성 판단.
 *
 * Format (텍스트):
 *   v1 2026-05-16 12:00:00 CREATE TABLE users (...)
 *   v2 2026-05-16 13:00:00 ADD COLUMN age INT
 */
data class SchemaChange(
    val version: Int,
    val timestamp: Long,
    val description: String,
)

class SchemaVersionLog(private val path: String) {
    private val changes: MutableList<SchemaChange> = mutableListOf()

    init {
        load()
    }

    fun currentVersion(): Int = changes.lastOrNull()?.version ?: 0

    fun record(description: String): SchemaChange {
        val change = SchemaChange(
            version = currentVersion() + 1,
            timestamp = System.currentTimeMillis(),
            description = description,
        )
        changes.add(change)
        appendLine(change)
        return change
    }

    fun history(): List<SchemaChange> = changes.toList()

    private fun load() {
        val file = java.io.File(path)
        if (!file.exists()) return
        file.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val parts = line.split('\t', limit = 3)
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
        file.parentFile?.mkdirs()
        file.appendText("${change.version}\t${change.timestamp}\t${change.description}\n")
    }
}
