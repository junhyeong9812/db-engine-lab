# impl/04-01 — Type / ColumnDef / TableSchema / Tuple / Catalog

> **종류**: 세션형
> **상위 단계**: `docs/stages/04-schema-catalog.md`
> **코드 정본**: git `b329403` — "stage 4: catalog (Type, ColumnDef, TableSchema, Tuple, Catalog) - 28 tests"
> **이 세션의 범위**: 바이트 더미에 **의미**를 붙인다. 여기서부터 "행"과 "컬럼"이 생긴다.
> **작성 파일**:
> - 신규 패키지: `src/main/kotlin/com/dbenginelab/catalog/`
> - 신규: `Type.kt` · `ColumnDef.kt` · `TableSchema.kt` · `Tuple.kt` · `Catalog.kt`
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/catalog/CatalogTest.kt`
> **검증**: `CatalogTest` 8 PASSED · 전체 누적 28 PASSED
> **예상 타이핑 시간**: 70분 (파일 5개 — 두 번에 나눠 쳐도 된다)

---

## 0. 참조

- 주 참조: SimpleDB `Catalog`, `TupleDesc`, `Tuple`, `Type`.
- 대조 참조: BusTub `catalog`, `column`, `schema`.
- **핵심 설계 결정 근거**: `Type`을 sealed class가 아니라 **enum**으로 둔다. 타입은 앞으로 늘어날 것(DECIMAL·DATE·TIMESTAMP)이고, sealed의 "닫힌 집합"이라는 의미와 맞지 않는다. enum + `when` 핸들러가 자연스럽다.

## 1. 만족시킬 invariant

- **I-1**: `Type.encode` → `decode` round-trip이 값을 보존한다 (INT·BIGINT·STRING).
- **I-2**: `Tuple.encode` → `decode` round-trip이 **NULL을 포함해** 값을 보존한다.
- **I-3**: 스키마 위반을 생성 시점에 거부한다 — NOT NULL 컬럼에 null, 타입 불일치, 중복 컬럼명.
- **I-4**: `Catalog`가 persist → reopen 후 같은 schema를 복원한다.

## 2. 의존성

- 이전 세션: 단계 3까지의 storage 계층(이번 세션은 아직 그 위에 얹지 않는다 — `Catalog`는 자기 메타 파일을 따로 쓴다).

## 3. 문제 정의 (TDD step 1)

단계 3까지 우리가 저장할 수 있는 것은 `Long → Long` 뿐이다. 실제 DB는 `(id BIGINT, name STRING, age INT)` 같은 행을 저장한다.

바이트 더미를 행으로 만들려면 세 가지가 필요하다:

1. **타입** — 이 4바이트가 `Int`인지, 앞의 4바이트가 뒤 문자열의 길이인지. `Type.encode/decode`가 그 규칙이다.
2. **NULL 표현** — `age`가 비어 있다는 것을 어떻게 바이트로 적나? 값 자리를 비우면 그 뒤 컬럼의 위치가 밀린다. 그래서 **NULL bitmap**을 맨 앞에 둔다. 컬럼 N개면 `ceil(N/8)` 바이트, 각 비트가 한 컬럼의 NULL 여부다.
3. **스키마의 영속성** — 테이블 정의 자체가 재시작 후에도 남아야 한다. 스키마를 잃으면 데이터 파일은 해석 불가능한 바이트 더미로 돌아간다.

3번이 이번 세션에서 가장 조용히 위험한 부분이다. `Catalog.save/load`가 없거나 불완전하면 **테스트는 통과하는데 reopen에서만 깨진다.**

## 4. 실패 테스트 (TDD step 2)

```kotlin
// src/test/kotlin/com/dbenginelab/catalog/CatalogTest.kt @ b329403
package com.dbenginelab.catalog

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatalogTest {

    private fun userSchema() = TableSchema(
        name = "users",
        columns = listOf(
            ColumnDef("id", Type.BIGINT, nullable = false),
            ColumnDef("name", Type.STRING, nullable = false),
            ColumnDef("age", Type.INT, nullable = true),
        ),
    )

    @Test
    fun `Type encode·decode round-trip (INT, BIGINT, STRING)`() {
        val buf = java.nio.ByteBuffer.allocate(1024)
        Type.INT.encode(42, buf)
        Type.BIGINT.encode(1234567890L, buf)
        Type.STRING.encode("hello-한글", buf)
        buf.flip()
        assertEquals(42, Type.INT.decode(buf))
        assertEquals(1234567890L, Type.BIGINT.decode(buf))
        assertEquals("hello-한글", Type.STRING.decode(buf))
    }

    @Test
    fun `TableSchema 중복 컬럼명 거부`() {
        assertThrows<IllegalArgumentException> {
            TableSchema("t", listOf(ColumnDef("x", Type.INT), ColumnDef("x", Type.INT)))
        }
    }

    @Test
    fun `Tuple encode·decode round-trip (with NULL)`() {
        val schema = userSchema()
        val tuple = Tuple(schema, listOf(100L, "Alice", null))
        val bytes = tuple.encode()
        val decoded = Tuple.decode(schema, bytes)
        assertEquals(tuple, decoded)
        assertNull(decoded.get("age"))
        assertEquals("Alice", decoded.get("name"))
    }

    @Test
    fun `NOT NULL 컬럼에 null insert 거부`() {
        val schema = userSchema()
        assertThrows<IllegalArgumentException> {
            Tuple(schema, listOf(null, "Bob", 30))
        }
    }

    @Test
    fun `타입 불일치 거부`() {
        val schema = userSchema()
        assertThrows<IllegalArgumentException> {
            Tuple(schema, listOf("not-a-long", "Bob", 30))
        }
    }

    @Test
    fun `Catalog persist 후 reopen하면 같은 schema 복원`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("catalog.meta").toString()
        val schema = userSchema()
        Catalog(path).apply {
            registerTable(schema)
            assertEquals(listOf("users"), listTables())
        }
        // reopen
        val cat2 = Catalog(path)
        val restored = cat2.getTable("users")
        assertEquals(schema, restored)
        assertContentEquals(schema.columns, restored.columns)
    }

    @Test
    fun `같은 이름 테이블 중복 등록 거부`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("catalog.meta").toString()
        val cat = Catalog(path)
        cat.registerTable(userSchema())
        assertThrows<IllegalArgumentException> { cat.registerTable(userSchema()) }
    }

    @Test
    fun `dropTable 후 reopen해도 사라진 상태 유지`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("catalog.meta").toString()
        Catalog(path).apply {
            registerTable(userSchema())
            dropTable("users")
        }
        val cat2 = Catalog(path)
        assertEquals(emptyList(), cat2.listTables())
    }
}
```

**예상 실패**: **컴파일 실패** — `Unresolved reference: Type`, `ColumnDef`, `TableSchema`, `Tuple`, `Catalog`.

## 5. 구현 코드 (TDD step 3 — make it pass)

### 5.1 `Type.kt`

```kotlin
// src/main/kotlin/com/dbenginelab/catalog/Type.kt @ b329403
package com.dbenginelab.catalog

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

enum class Type {
    INT,
    BIGINT,
    STRING;

    fun encode(value: Any?, buffer: ByteBuffer) {
        when (this) {
            INT -> buffer.putInt(value as Int)
            BIGINT -> buffer.putLong(value as Long)
            STRING -> {
                val bytes = (value as String).toByteArray(StandardCharsets.UTF_8)
                buffer.putInt(bytes.size)
                buffer.put(bytes)
            }
        }
    }

    fun decode(buffer: ByteBuffer): Any = when (this) {
        INT -> buffer.int
        BIGINT -> buffer.long
        STRING -> {
            val len = buffer.int
            val bytes = ByteArray(len)
            buffer.get(bytes)
            String(bytes, StandardCharsets.UTF_8)
        }
    }

    /** Fixed encoded size, or -1 if variable (STRING). */
    fun fixedSize(): Int = when (this) {
        INT -> 4
        BIGINT -> 8
        STRING -> -1
    }
}
```

### 5.2 `ColumnDef.kt`

```kotlin
// src/main/kotlin/com/dbenginelab/catalog/ColumnDef.kt @ b329403
package com.dbenginelab.catalog

data class ColumnDef(
    val name: String,
    val type: Type,
    val nullable: Boolean = true,
)
```

`data class`가 맞는 자리다. 01-01의 `Record`(ByteArray 때문에 일반 class)와 02-01의 `PageId`(값 객체라 data class)를 다시 떠올려봐라 — 기준은 **자동 `equals`가 참인 말을 하는가**다.

### 5.3 `TableSchema.kt`

```kotlin
// src/main/kotlin/com/dbenginelab/catalog/TableSchema.kt @ b329403
package com.dbenginelab.catalog

data class TableSchema(
    val name: String,
    val columns: List<ColumnDef>,
) {
    init {
        require(columns.isNotEmpty()) { "table $name must have at least one column" }
        require(columns.map { it.name }.toSet().size == columns.size) {
            "duplicate column names in table $name"
        }
    }

    val columnCount: Int get() = columns.size

    fun columnIndex(name: String): Int {
        val idx = columns.indexOfFirst { it.name == name }
        require(idx >= 0) { "column $name not found in table ${this.name}" }
        return idx
    }

    fun column(name: String): ColumnDef = columns[columnIndex(name)]
}
```

이 시점의 `TableSchema`에는 **제약(constraint) 개념이 아직 없다.** 컬럼 목록과 이름뿐이다. 단계 5에서 `constraints` 필드와 `validateConstraints()`가 이 클래스에 추가되고, 그때 이 파일을 다시 친다(`05-01-constraints.md`).

### 5.4 `Tuple.kt`

```kotlin
// src/main/kotlin/com/dbenginelab/catalog/Tuple.kt @ b329403
package com.dbenginelab.catalog

import java.nio.ByteBuffer

/**
 * In-memory tuple. Values list size must match schema.columnCount.
 * NULL values are represented as `null` in the list.
 * On-disk encoding:
 *   [null bitmap (ceil(N/8) bytes)] [encoded non-null values in column order]
 */
class Tuple(val schema: TableSchema, val values: List<Any?>) {

    init {
        require(values.size == schema.columnCount) {
            "values size ${values.size} != schema columns ${schema.columnCount}"
        }
        for ((i, col) in schema.columns.withIndex()) {
            val v = values[i]
            if (v == null) {
                require(col.nullable) { "column ${col.name} is NOT NULL but got null" }
            } else {
                requireTypeMatches(col, v)
            }
        }
    }

    fun get(columnName: String): Any? = values[schema.columnIndex(columnName)]

    fun encode(): ByteArray {
        val n = schema.columnCount
        val bitmapSize = (n + 7) / 8
        // Estimate buffer size: bitmap + each value's max size. STRING handled below.
        val estimated = bitmapSize + values.sumOf { v ->
            if (v == null) 0
            else when (v) {
                is Int -> 4
                is Long -> 8
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
        buf.put(bitmap)
        for ((i, v) in values.withIndex()) {
            if (v != null) schema.columns[i].type.encode(v, buf)
        }
        return buf.array().copyOf(buf.position())
    }

    companion object {
        fun decode(schema: TableSchema, bytes: ByteArray): Tuple {
            val n = schema.columnCount
            val bitmapSize = (n + 7) / 8
            val buf = ByteBuffer.wrap(bytes)
            val bitmap = ByteArray(bitmapSize)
            buf.get(bitmap)
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
            require(ok) {
                "column ${col.name} expects ${col.type} but got ${v::class.simpleName} (value=$v)"
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Tuple) return false
        return schema == other.schema && values == other.values
    }

    override fun hashCode(): Int = 31 * schema.hashCode() + values.hashCode()

    override fun toString(): String = "Tuple(${schema.name}, $values)"
}
```

### 5.5 `Catalog.kt` — 스키마의 영속성

여기가 이 세션의 심장이다. `save()`와 `load()`는 **테이블 정의를 바이트로 굽고 되살리는 코드**이고, 문자열 인코딩 규칙(4바이트 길이 프리픽스 + UTF-8)이 여기서 확정되어 이후 단계 내내 재사용된다.

```kotlin
// src/main/kotlin/com/dbenginelab/catalog/Catalog.kt @ b329403
package com.dbenginelab.catalog

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Simple persistent catalog: stores table schemas in a single metadata file.
 *
 * On-disk format (per table):
 *   [4 bytes: name length][name bytes]
 *   [4 bytes: column count]
 *   repeat column count times:
 *     [4 bytes: column name length][column name bytes]
 *     [1 byte: type ordinal]
 *     [1 byte: nullable flag]
 *
 * The whole file is rewritten on each registerTable / dropTable. Simple but
 * incorrect under concurrent writers (single-thread assumption holds through stage 8).
 */
class Catalog(private val metaPath: String) {

    private val tables: MutableMap<String, TableSchema> = mutableMapOf()

    init {
        load()
    }

    fun registerTable(schema: TableSchema) {
        require(!tables.containsKey(schema.name)) { "table ${schema.name} already exists" }
        tables[schema.name] = schema
        save()
    }

    fun dropTable(name: String) {
        require(tables.containsKey(name)) { "table $name not found" }
        tables.remove(name)
        save()
    }

    fun getTable(name: String): TableSchema {
        return tables[name] ?: throw NoSuchElementException("table $name not found")
    }

    fun listTables(): List<String> = tables.keys.sorted()

    private fun load() {
        val file = File(metaPath)
        if (!file.exists() || file.length() == 0L) return
        RandomAccessFile(file, "r").use { raf ->
            DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(raf.fd))).use { dis ->
                while (dis.available() > 0) {
                    val schema = readSchema(dis)
                    tables[schema.name] = schema
                }
            }
        }
    }

    private fun save() {
        val file = File(metaPath)
        DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(file))).use { dos ->
            for (schema in tables.values) {
                writeSchema(dos, schema)
            }
            dos.flush()
        }
    }

    private fun writeSchema(dos: DataOutputStream, schema: TableSchema) {
        writeString(dos, schema.name)
        dos.writeInt(schema.columnCount)
        for (col in schema.columns) {
            writeString(dos, col.name)
            dos.writeByte(col.type.ordinal)
            dos.writeBoolean(col.nullable)
        }
    }

    private fun readSchema(dis: DataInputStream): TableSchema {
        val name = readString(dis)
        val colCount = dis.readInt()
        val cols = (0 until colCount).map {
            val colName = readString(dis)
            val type = Type.values()[dis.readByte().toInt()]
            val nullable = dis.readBoolean()
            ColumnDef(colName, type, nullable)
        }
        return TableSchema(name, cols)
    }

    private fun writeString(dos: DataOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        dos.writeInt(bytes.size)
        dos.write(bytes)
    }

    private fun readString(dis: DataInputStream): String {
        val len = dis.readInt()
        val bytes = ByteArray(len)
        dis.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
```

## 6. 검증 테스트 (TDD step 4 — green)

테스트 파일은 §4에서 저장한 것이 그대로 최종본이다.

```bash
./gradlew test --tests 'com.dbenginelab.catalog.CatalogTest'
```

**기대 결과**: `CatalogTest` **8 PASSED** · 전체 누적 **28 PASSED**

invariant 대응:
- **I-1** ← `Type encode·decode round-trip (INT, BIGINT, STRING)`
- **I-2** ← `Tuple encode·decode round-trip (with NULL)`
- **I-3** ← `TableSchema 중복 컬럼명 거부` · `NOT NULL 컬럼에 null insert 거부` · `타입 불일치 거부`
- **I-4** ← `Catalog persist 후 reopen하면 같은 schema 복원` · `dropTable 후 reopen해도 사라진 상태 유지`
- (중복 방지) ← `같은 이름 테이블 중복 등록 거부`

**`Catalog persist 후 reopen` 이 실패하고 `table users not found`가 나온다면** `save()`/`load()`를 끝까지 치지 않은 것이다. 두 메서드는 구현이 있어야 하는 자리이지 자리표시자가 아니다.

## 7. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

**1.** `Tuple.encode`에서 NULL bitmap을 빼고 값만 쓰도록 바꿔라. **NULL이 하나 섞이는 순간 무엇이 어떻게 어긋나는지** 디코딩 결과를 찍어봐라.

<details><summary>답</summary>

`encode`는 **null인 값을 아예 쓰지 않는다**는 점이 핵심이다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
for ((i, v) in values.withIndex()) {
    if (v != null) schema.columns[i].type.encode(v, buf)   // null이면 0바이트
}
```

bitmap이 없으면 `decode`는 **어느 컬럼이 건너뛰어졌는지 알 방법이 없다.** 컬럼 순서대로 타입에 맞춰 읽어 나가므로:

```
schema: (id BIGINT, name STRING, age INT)
값:     (100L,      "Alice",     null)

bitmap 있을 때 encode: [bitmap=0b100][100L 8B]["Alice" 4+5B]         → decode 정상
bitmap 뺐을 때 encode:              [100L 8B]["Alice" 4+5B]         → age 자리에 아무것도 없음
                                                                       decode가 4바이트를 더 읽으려다
                                                                       BufferUnderflowException
```

NULL이 **중간**에 있으면 더 나쁘다. `(100L, null, 30)`이면 `name`을 읽으려고 4바이트 길이를 읽는데 거기엔 `30`의 바이트가 들어있다 → 엉뚱한 길이로 문자열을 읽으려 하고, **예외가 날 수도 있고 쓰레기 문자열이 나올 수도 있다.**

정리하면 — 가변 개수의 필드를 건너뛰려면 **"무엇을 건너뛰었는지"를 별도로 적어야 한다.** 그게 bitmap이고, 01-01의 length-prefix와 같은 종류의 장치다(경계를 데이터 밖에서 알려주기).
</details>

**2.** bitmap의 `1 shl (i % 8)`을 `1 shl i`로 바꿔라. 9번째 컬럼에서 무슨 일이 일어나나?

<details><summary>답</summary>

**실측: `CatalogTest` 8개가 전부 통과한다.** 테스트 스키마가 `(id, name, age)` 3컬럼뿐이라 `i`가 0·1·2이고, 그 범위에선 `i % 8 == i`라 **바뀐 게 없기 때문**이다.

9번째 컬럼(`i = 8`)에서 이렇게 된다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
bitmap[i / 8] = (bitmap[i / 8].toInt() or (1 shl i)).toByte()
//     ↑ bitmap[1]                          ↑ 1 shl 8 = 256 = 0b1_0000_0000
//                                            .toByte()로 자르면 하위 8비트만 → 0
```

**비트가 조용히 사라진다.** `or 0`은 아무것도 안 하므로 bitmap[1]은 0인 채로 남는다.

그런데 `decode` 쪽은 안 고쳤으니 여전히 `shr (i % 8)`로 읽는다 → `(0 shr 0) and 1 == 0` → **"null이 아니다"**로 판단 → 쓰이지도 않은 값을 읽으려 한다 → 1번 과제와 같은 붕괴.

이 문제의 성질을 봐라 — **인코더와 디코더의 규칙이 어긋났는데 컴파일러도 테스트도 침묵한다.** 잡으려면 컬럼 9개 이상에 NULL이 섞인 테이블을 만드는 테스트를 **직접 추가해야** 한다. 실제 DB의 인코딩 코드가 property-based test(무작위 스키마·무작위 값으로 round-trip 검증)를 쓰는 이유가 이것이다.
</details>

**3.** `Catalog.save()`를 `registerTable`에서만 부르고 `dropTable`에서는 부르지 않도록 고쳐라. 어느 테스트가 잡아내는가?

<details><summary>답</summary>

**실측: `dropTable 후 reopen해도 사라진 상태 유지` 하나가 실패한다** (8개 중 1개).

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
Catalog(path).apply {
    registerTable(userSchema())
    dropTable("users")          // 메모리에서만 지워짐
}
val cat2 = Catalog(path)        // reopen → 파일에는 아직 users가 있다
assertEquals(emptyList(), cat2.listTables())   // ✗ ["users"]가 나온다
```

`dropTable`은 `tables.remove(name)`으로 **메모리 맵만** 고친다. `save()`가 없으면 파일은 `registerTable` 시점의 내용 그대로다.

reopen이 없었다면 이 테스트도 통과했을 것이다. 02-02 과제 1번(dirty page flush)과 **완전히 같은 구조의 결함**이고, 같은 방식으로 잡힌다 — **메모리와 디스크의 불일치는 reopen만이 드러낸다.**

이 프로젝트에서 reopen 테스트가 반복해서 나오는 이유를 여기서 다시 확인하게 된다.
</details>

**4.** STRING의 길이 프리픽스를 4바이트가 아니라 2바이트로 줄이면 어떤 문자열에서 깨지나? 몇 글자부터인가?

<details><summary>답</summary>

`putInt` → `putShort`로 바꾸면 표현 범위가 `Int`(약 21억)에서 **`Short`(-32,768 ~ 32,767)** 로 줄어든다.

- **ASCII**: 1자 = 1바이트 → **32,768자**부터 깨진다.
- **한글**: UTF-8에서 1자 = 3바이트 → **약 10,923자**부터.

깨지는 방식이 고약하다. `bytes.size`가 32,768이면 `toShort()`가 **-32,768로 뒤집힌다.** 그리고 decode에서:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
val len = buffer.short.toInt()   // -32768
val bytes = ByteArray(len)       // NegativeArraySizeException
```

**요란하게 실패하니 그나마 낫다.** 만약 길이가 65,536(= 2¹⁶)이었다면 `toShort()`가 정확히 0이 되어 **빈 문자열이 조용히 반환된다** — 데이터가 있는데 없다고 하는 쪽이 훨씬 위험하다.

여기서 볼 것은 "몇 자냐"보다 **"경계를 넘었을 때 요란하게 실패하는가, 조용히 틀리는가"** 다. 같은 오버플로인데 입력값에 따라 둘 다 나온다. 01-01 과제 4번(길이 프리픽스 손상)에서 본 것과 같은 갈림이다.
</details>

## 8. 다음 한계

스키마는 **모양**만 강제한다. "id는 BIGINT이고 null이 아니다"까지는 막지만, **"id는 유일해야 한다"는 막지 못한다.** 같은 id를 가진 행 두 개가 아무 저항 없이 들어간다.

→ **단계 5 Constraints**. PK·UNIQUE·FK를 스키마에 붙이고, `TableSchema`가 자리만 잡아둔 `validateConstraints()`를 채운다.
