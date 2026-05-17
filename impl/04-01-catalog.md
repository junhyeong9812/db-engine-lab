# impl/04-01 — Type / Schema / Catalog / Tuple (한 줄 한 줄)

> **검증**: CatalogTest 8 PASSED.
> 작성 파일:
> - 신규 디렉토리: `src/main/kotlin/com/dbenginelab/catalog/`
> - 신규: Type.kt, ColumnDef.kt, TableSchema.kt, Tuple.kt, Catalog.kt
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/catalog/CatalogTest.kt`

## 0. 참조
- SimpleDB `Catalog`, `TupleDesc`, `Tuple`, `Type`.
- BusTub `catalog`, `column`, `schema`.

## 1. invariant
- Type encode → decode round-trip.
- Tuple encode → decode (with NULL) round-trip.
- NOT NULL 컬럼에 null insert 거부.
- 타입 불일치 거부.
- Catalog persist → reopen → schema 복원.

## 2. Type.kt — 한 줄 한 줄

```kotlin
package com.dbenginelab.catalog                                      // 신규 catalog 패키지
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

// Q: 왜 enum이 sealed보다 적절?
enum class Type {
    // <details><summary>A</summary>
    // 타입 확장 가능 (DECIMAL, DATE, TIMESTAMP...) — sealed의 "닫힌" 의미와 다름. enum + when handler가 자연.
    // </details>
    INT, BIGINT, STRING;

    fun encode(value: Any?, buffer: ByteBuffer) {                    // 값 → bytes
        when (this) {
            INT -> buffer.putInt(value as Int)                       // 4 bytes
            BIGINT -> buffer.putLong(value as Long)                  // 8 bytes
            STRING -> {
                val bytes = (value as String).toByteArray(StandardCharsets.UTF_8)
                buffer.putInt(bytes.size)                            // length-prefix
                buffer.put(bytes)
            }
        }
    }

    fun decode(buffer: ByteBuffer): Any = when (this) {              // bytes → 값
        INT -> buffer.int
        BIGINT -> buffer.long
        STRING -> {
            val len = buffer.int
            val bytes = ByteArray(len); buffer.get(bytes)
            String(bytes, StandardCharsets.UTF_8)
        }
    }

    fun fixedSize(): Int = when (this) {                             // -1 = variable
        INT -> 4; BIGINT -> 8; STRING -> -1
    }
}
```

## 3. ColumnDef.kt — 한 줄 한 줄

```kotlin
package com.dbenginelab.catalog

// Q: data class OK?
data class ColumnDef(
    val name: String,
    val type: Type,
    val nullable: Boolean = true,
)
// <details><summary>A</summary>
// immutable 값 객체 — 자동 equals/hashCode 의미 맞음. Page와 달리 mutable byte 아님.
// </details>
```

## 4. TableSchema.kt — 한 줄 한 줄

```kotlin
package com.dbenginelab.catalog

data class TableSchema(
    val name: String,
    val columns: List<ColumnDef>,
    val constraints: List<Constraint> = emptyList(),                  // 단계 5에서 추가
) {
    init {
        require(columns.isNotEmpty()) { "table $name must have at least one column" }
        // Q: 중복 컬럼명 검사 — toSet().size 비교가 왜 동작?
        require(columns.map { it.name }.toSet().size == columns.size) {
            "duplicate column names in table $name"
        }
        // <details><summary>A</summary>
        // toSet은 중복 제거 — 원본 size와 다르면 중복 있음. O(N) 검사.
        // </details>
        validateConstraints()
    }

    val columnCount: Int get() = columns.size

    fun columnIndex(name: String): Int {
        val idx = columns.indexOfFirst { it.name == name }
        require(idx >= 0) { "column $name not found in table ${this.name}" }
        return idx
    }

    fun column(name: String): ColumnDef = columns[columnIndex(name)]

    fun primaryKey(): Constraint.PrimaryKey? =
        constraints.filterIsInstance<Constraint.PrimaryKey>().firstOrNull()

    private fun validateConstraints() { /* 단계 5 참조 */ }
}
```

## 5. Tuple.kt — 한 줄 한 줄

```kotlin
package com.dbenginelab.catalog
import java.nio.ByteBuffer

class Tuple(val schema: TableSchema, val values: List<Any?>) {

    init {
        require(values.size == schema.columnCount)
        for ((i, col) in schema.columns.withIndex()) {
            val v = values[i]
            if (v == null) require(col.nullable) { "column ${col.name} NOT NULL but null" }
            else requireTypeMatches(col, v)
        }
    }

    fun get(columnName: String): Any? = values[schema.columnIndex(columnName)]

    fun encode(): ByteArray {                                         // tuple → bytes
        val n = schema.columnCount
        val bitmapSize = (n + 7) / 8                                 // Q: NULL bitmap size 계산?
        // <details><summary>A</summary>
        // ceil(N/8) bytes — 각 bit가 한 컬럼의 NULL 여부.
        // </details>
        val estimated = bitmapSize + values.sumOf { v ->
            if (v == null) 0
            else when (v) {
                is Int -> 4; is Long -> 8
                is String -> 4 + v.toByteArray(Charsets.UTF_8).size
                else -> error("unsupported runtime type: ${v::class.simpleName}")
            }
        }
        val buf = ByteBuffer.allocate(estimated)
        val bitmap = ByteArray(bitmapSize)
        for ((i, v) in values.withIndex()) {
            // Q: NULL이면 bit set — 어떤 비트?
            if (v == null) bitmap[i / 8] = (bitmap[i / 8].toInt() or (1 shl (i % 8))).toByte()
            // <details><summary>A</summary>
            // i/8 = byte 위치, i%8 = bit 위치. 1 << (i%8) = 해당 bit만 1. or로 set.
            // </details>
        }
        buf.put(bitmap)                                              // bitmap 먼저
        for ((i, v) in values.withIndex()) {                         // 그 다음 non-null 값들
            if (v != null) schema.columns[i].type.encode(v, buf)
        }
        return buf.array().copyOf(buf.position())                    // 실제 쓴 만큼만
    }

    companion object {
        fun decode(schema: TableSchema, bytes: ByteArray): Tuple {
            val n = schema.columnCount
            val bitmapSize = (n + 7) / 8
            val buf = ByteBuffer.wrap(bytes)
            val bitmap = ByteArray(bitmapSize); buf.get(bitmap)
            val values = mutableListOf<Any?>()
            for (i in 0 until n) {
                val isNull = (bitmap[i / 8].toInt() shr (i % 8)) and 1 == 1
                if (isNull) values.add(null)
                else values.add(schema.columns[i].type.decode(buf))
            }
            return Tuple(schema, values)
        }

        private fun requireTypeMatches(col: ColumnDef, v: Any) {
            val ok = when (col.type) {
                Type.INT -> v is Int
                Type.BIGINT -> v is Long
                Type.STRING -> v is String
            }
            require(ok) { "column ${col.name} expects ${col.type} but got ${v::class.simpleName}" }
        }
    }
}
```

## 6. Catalog.kt — persistence (요약 + Q/A)

```kotlin
package com.dbenginelab.catalog
import java.io.*

class Catalog(private val metaPath: String) {
    private val tables: MutableMap<String, TableSchema> = mutableMapOf()
    init { load() }                                                  // reopen 시 자동 복원

    fun registerTable(schema: TableSchema) {
        require(!tables.containsKey(schema.name))                    // 중복 거부
        tables[schema.name] = schema
        save()                                                       // Q: 매번 전체 재기록?
    }
    // <details><summary>A</summary>
    // 단순화 — 단일 thread 가정. 매번 전체 rewrite. multi-table 많으면 incremental 필요.
    // </details>

    fun dropTable(name: String) {
        require(tables.containsKey(name))
        tables.remove(name); save()
    }

    fun getTable(name: String): TableSchema = tables[name] ?: throw NoSuchElementException("table $name not found")
    fun listTables(): List<String> = tables.keys.sorted()

    private fun load() { /* binary read — TableSchema sequence */ }
    private fun save() { /* binary write */ }
}
```

## 7. 검증 (8 PASSED)
- Type encode·decode (INT, BIGINT, STRING)
- 중복 컬럼명 거부
- Tuple round-trip with NULL
- NOT NULL에 null 거부
- 타입 불일치 거부
- Catalog persist + reopen
- 중복 테이블 거부
- dropTable persist

## 8. 다음 한계
- Schema만으론 무결성 부족 → **단계 5 Constraints**.
