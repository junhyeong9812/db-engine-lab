# impl/05-01 — Constraints (PK / Unique / FK 정의와 영속화)

> **종류**: 세션형
> **상위 단계**: `docs/stages/05-constraints.md`
> **코드 정본**: git `df0ef78` — "stage 5: constraints (PK/Unique/FK definition + persistence) - 33 tests"
> **이 세션의 범위**: 제약을 **선언하고 저장**한다. 실제로 데이터를 검사하는 것은 아직 아니다(단계 6-3).
> **작성 파일**:
> - 신규: `src/main/kotlin/com/dbenginelab/catalog/Constraint.kt`
> - 수정: `src/main/kotlin/com/dbenginelab/catalog/TableSchema.kt` — `constraints` 필드 + `validateConstraints()`
> - 수정: `src/main/kotlin/com/dbenginelab/catalog/Catalog.kt` — constraint 영속화
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/catalog/ConstraintTest.kt`
> **검증**: `ConstraintTest` 5 PASSED · 전체 누적 33 PASSED
> **예상 타이핑 시간**: 50분

---

## 0. 참조

- **참조 부재**: SimpleDB·BusTub 모두 constraint 처리가 약하다. PostgreSQL의 constraint 모델을 개념 참조로 삼되 코드는 자체 설계다.
- **핵심 설계 결정 근거**: `Constraint`는 `sealed class`가 맞다. PK·Unique·FK는 이 단계에서 **진짜로 닫힌 집합**이기 때문이다. CHECK 제약은 표현식 평가(단계 6-2)에 의존하므로 여기 넣지 않는다 — 넣었다면 sealed가 거짓말이 됐을 것이다.

## 1. 만족시킬 invariant

- **CI-1**: PK로 지정된 컬럼은 NOT NULL이어야 한다 (스키마 생성 시점에 강제).
- **CI-2**: 한 테이블에 PK는 하나뿐이다.
- **CI-3**: 존재하지 않는 컬럼을 참조하는 제약은 거부된다.
- **CI-4**: 제약이 persist → reopen 후 정확히 복원된다 (복합 키 포함).

## 2. 의존성

- 이전 세션: `impl/04-01-catalog.md` (`TableSchema`, `Catalog`, `ColumnDef`)

## 3. 문제 정의 (TDD step 1)

04-01의 스키마는 **모양**만 안다. `id BIGINT NOT NULL`까지는 강제하지만, 같은 `id` 값을 가진 행 두 개는 아무 저항 없이 들어간다.

이번 세션은 그 규칙을 **선언할 수 있게** 만든다. 주의 — **선언까지만이다.** 실제로 insert를 막는 검사기(`ConstraintValidator`)는 단계 6-3에서 만든다. 지금 만드는 것은:

1. **제약의 표현** — PK·Unique·FK를 값 객체로.
2. **선언 자체의 정합성 검사** — 없는 컬럼을 PK로 지정하거나, PK를 두 개 달거나, nullable 컬럼을 PK로 삼는 것을 **스키마를 만드는 순간** 거부한다.
3. **영속화** — 제약도 스키마의 일부이므로 `Catalog`가 저장하고 복원해야 한다. 여기서 `Constraint`의 종류를 바이트로 구분하기 위해 **tag byte** 방식이 등장한다.

2번이 중요하다. "PK 컬럼은 NOT NULL"은 데이터 검사가 아니라 **스키마 검사**다. 잘못된 스키마는 아예 만들어지지 않게 하는 편이, 나중에 데이터마다 검사하는 것보다 싸고 확실하다.

## 4. 실패 테스트 (TDD step 2)

```kotlin
// src/test/kotlin/com/dbenginelab/catalog/ConstraintTest.kt @ df0ef78
package com.dbenginelab.catalog

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

class ConstraintTest {

    @Test
    fun `PrimaryKey가 NOT NULL 컬럼만 허용`() {
        assertThrows<IllegalArgumentException> {
            TableSchema(
                name = "users",
                columns = listOf(
                    ColumnDef("id", Type.BIGINT, nullable = true),  // nullable!
                    ColumnDef("name", Type.STRING, nullable = false),
                ),
                constraints = listOf(Constraint.PrimaryKey(listOf("id"))),
            )
        }
    }

    @Test
    fun `PrimaryKey는 한 테이블에 하나만`() {
        assertThrows<IllegalArgumentException> {
            TableSchema(
                name = "users",
                columns = listOf(
                    ColumnDef("id", Type.BIGINT, nullable = false),
                    ColumnDef("email", Type.STRING, nullable = false),
                ),
                constraints = listOf(
                    Constraint.PrimaryKey(listOf("id")),
                    Constraint.PrimaryKey(listOf("email")),
                ),
            )
        }
    }

    @Test
    fun `존재하지 않는 컬럼을 PK로 지정 시 거부`() {
        assertThrows<IllegalArgumentException> {
            TableSchema(
                name = "users",
                columns = listOf(ColumnDef("id", Type.BIGINT, nullable = false)),
                constraints = listOf(Constraint.PrimaryKey(listOf("missing"))),
            )
        }
    }

    @Test
    fun `Constraints persist 후 reopen하면 복원`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("catalog.meta").toString()
        val schema = TableSchema(
            name = "orders",
            columns = listOf(
                ColumnDef("id", Type.BIGINT, nullable = false),
                ColumnDef("user_id", Type.BIGINT, nullable = false),
                ColumnDef("code", Type.STRING, nullable = false),
            ),
            constraints = listOf(
                Constraint.PrimaryKey(listOf("id")),
                Constraint.Unique(listOf("code")),
                Constraint.ForeignKey(listOf("user_id"), "users", listOf("id")),
            ),
        )
        Catalog(path).registerTable(schema)
        val restored = Catalog(path).getTable("orders")
        assertEquals(schema.constraints, restored.constraints)
        assertEquals(schema.primaryKey(), restored.primaryKey())
    }

    @Test
    fun `복합 PK도 정상 동작`() {
        val schema = TableSchema(
            name = "user_role",
            columns = listOf(
                ColumnDef("user_id", Type.BIGINT, nullable = false),
                ColumnDef("role_id", Type.BIGINT, nullable = false),
            ),
            constraints = listOf(Constraint.PrimaryKey(listOf("user_id", "role_id"))),
        )
        assertEquals(listOf("user_id", "role_id"), schema.primaryKey()?.columns)
    }
}
```

**예상 실패**: **컴파일 실패** — `Unresolved reference: Constraint`, 그리고 `TableSchema(... constraints = ...)` 인자를 아직 받지 않으므로 생성자 불일치.

## 5. 구현 코드 (TDD step 3 — make it pass)

### 5.1 `Constraint.kt` (신규)

```kotlin
// src/main/kotlin/com/dbenginelab/catalog/Constraint.kt @ df0ef78
package com.dbenginelab.catalog

/**
 * Schema-level constraints. Declared on TableSchema; actual data-level enforcement
 * happens at mutation time (stage 6 query operator + stage 7 transaction).
 *
 * sealed because the set is closed at this stage: PrimaryKey, Unique, ForeignKey.
 * CHECK is deferred until stage 6 (needs Expression).
 */
// Q: 왜 sealed? 단계 5에서 진짜 닫힌 집합인가?
sealed class Constraint {
// <details><summary>A</summary>
// PK/Unique/FK는 단계 5에서 진짜 닫힌 집합. CHECK는 단계 6 expression 의존이라 별도. sealed가 적합.
// </details>
    /** PRIMARY KEY (one or more columns). Implies UNIQUE + NOT NULL. */
    data class PrimaryKey(val columns: List<String>) : Constraint() {
        init { require(columns.isNotEmpty()) { "PrimaryKey must have at least one column" } }
    }

    /** UNIQUE (one or more columns). NULLs are allowed and treated as distinct. */
    data class Unique(val columns: List<String>) : Constraint() {
        init { require(columns.isNotEmpty()) { "Unique must have at least one column" } }
    }

    /** FOREIGN KEY (columns in this table) → (refTable.refColumns). RESTRICT only. */
    data class ForeignKey(
        val columns: List<String>,
        val refTable: String,
        val refColumns: List<String>,
    ) : Constraint() {
        init {
            require(columns.size == refColumns.size && columns.isNotEmpty()) {
                "ForeignKey columns and refColumns must match in count"
            }
        }
    }
}
```

### 5.2 `TableSchema.kt` (수정 — 전문)

04-01에서 친 파일에 `constraints` 필드와 `validateConstraints()`가 들어온다. **파일 전체를 다시 싣는 이유**는 생성자 시그니처가 바뀌기 때문이다 — 부분만 고치면 어디를 고쳐야 하는지 헷갈린다.

```kotlin
// src/main/kotlin/com/dbenginelab/catalog/TableSchema.kt @ df0ef78
package com.dbenginelab.catalog

data class TableSchema(
    val name: String,
    val columns: List<ColumnDef>,
    val constraints: List<Constraint> = emptyList(),
) {
    init {
        require(columns.isNotEmpty()) { "table $name must have at least one column" }
        require(columns.map { it.name }.toSet().size == columns.size) {
            "duplicate column names in table $name"
        }
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

    private fun validateConstraints() {
        val pks = constraints.filterIsInstance<Constraint.PrimaryKey>()
        require(pks.size <= 1) { "table $name has more than one PRIMARY KEY" }
        for (constraint in constraints) {
            when (constraint) {
                is Constraint.PrimaryKey -> {
                    constraint.columns.forEach { c ->
                        val col = column(c)
                        // Q: PK column이 nullable이면 왜 거부?
                        require(!col.nullable) {
                        // <details><summary>A</summary>
                        // schema validation은 다른 테이블 알 수 없음 (TableSchema 단일). 단계 6 mutation 시 validator가 검증.
                        // </details>
                            "PRIMARY KEY column $c must be NOT NULL in table $name"
                        }
                    }
                }
                is Constraint.Unique -> {
                    constraint.columns.forEach { c -> columnIndex(c) /* throws if missing */ }
                }
                is Constraint.ForeignKey -> {
                    constraint.columns.forEach { c -> columnIndex(c) }
                }
            }
        }
    }
}
```

### 5.3 `Catalog.kt` (수정 — 전문)

제약을 바이트로 굽는 부분이 추가된다. 종류를 구분하는 **tag byte**(PK=1, Unique=2, FK=3 식)를 먼저 쓰고 그 뒤에 내용을 쓴다. 문자열은 04-01에서 정한 규칙(4바이트 길이 + UTF-8)을 그대로 쓴다.

```kotlin
// src/main/kotlin/com/dbenginelab/catalog/Catalog.kt @ df0ef78
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
        dos.writeInt(schema.constraints.size)
        for (c in schema.constraints) writeConstraint(dos, c)
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
        val constraintCount = dis.readInt()
        val constraints = (0 until constraintCount).map { readConstraint(dis) }
        return TableSchema(name, cols, constraints)
    }

    private fun writeConstraint(dos: DataOutputStream, c: Constraint) {
        when (c) {
            is Constraint.PrimaryKey -> {
                dos.writeByte(0)
                writeStringList(dos, c.columns)
            }
            is Constraint.Unique -> {
                dos.writeByte(1)
                writeStringList(dos, c.columns)
            }
            is Constraint.ForeignKey -> {
                dos.writeByte(2)
                writeStringList(dos, c.columns)
                writeString(dos, c.refTable)
                writeStringList(dos, c.refColumns)
            }
        }
    }

    private fun readConstraint(dis: DataInputStream): Constraint {
        return when (dis.readByte().toInt()) {
            0 -> Constraint.PrimaryKey(readStringList(dis))
            1 -> Constraint.Unique(readStringList(dis))
            2 -> Constraint.ForeignKey(
                columns = readStringList(dis),
                refTable = readString(dis),
                refColumns = readStringList(dis),
            )
            else -> error("unknown constraint tag")
        }
    }

    private fun writeStringList(dos: DataOutputStream, list: List<String>) {
        dos.writeInt(list.size)
        list.forEach { writeString(dos, it) }
    }

    private fun readStringList(dis: DataInputStream): List<String> {
        val n = dis.readInt()
        return (0 until n).map { readString(dis) }
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
./gradlew test --tests 'com.dbenginelab.catalog.*'
```

**기대 결과**: `ConstraintTest` **5 PASSED** · `CatalogTest` 8 PASSED (04-01 것이 계속 통과해야 한다) · 전체 누적 **33 PASSED**

invariant 대응:
- **CI-1** ← `PrimaryKey가 NOT NULL 컬럼만 허용`
- **CI-2** ← `PrimaryKey는 한 테이블에 하나만`
- **CI-3** ← `존재하지 않는 컬럼을 PK로 지정 시 거부`
- **CI-4** ← `Constraints persist 후 reopen하면 복원` · `복합 PK도 정상 동작`

**`Constraints persist 후 reopen` 이 `table orders not found`로 실패한다면** `Catalog`의 저장·복원 경로를 끝까지 치지 않은 것이다.

## 7. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

**1.** `validateConstraints()`에서 "PK 컬럼은 NOT NULL" 검사를 지워라. 어느 테스트가 잡나? 그 검사가 없으면 런타임에 무엇이 잘못되나?

<details><summary>답</summary>

`PrimaryKey가 NOT NULL 컬럼만 허용` 하나가 실패한다. 그 테스트는 nullable 컬럼을 PK로 지정하고 `assertThrows<IllegalArgumentException>`을 기대하는데, `require`가 사라졌으니 스키마가 그냥 만들어진다.

런타임에 무엇이 무너지는지가 더 중요하다 — **유일성 보장이 사라진다.**

06-03의 `validateUniqueColumns`를 보면:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
val newValues = columns.map { tuple.get(it) }
if (newValues.any { it == null }) return     // ← NULL이면 검사 자체를 건너뛴다
```

PK 값이 null이면 이 줄에서 **검사 없이 통과**한다. 즉 `id`가 null인 행을 **몇 개든** 넣을 수 있다. "행을 유일하게 식별한다"는 PK의 정의가 무너진다.

여기서 배울 것은 **검사가 어디에 있어야 하는가**다. "PK는 NOT NULL"을 데이터 검사(06-03)에 넣으면 매 insert마다 확인해야 하고, 실수로 빠뜨리면 위와 같은 구멍이 난다. **스키마를 만드는 시점**에 한 번 막으면 그 뒤로는 null이 올 수 없다는 것이 보장된다 — 싸고 확실하다.
</details>

**2.** tag byte 없이 제약을 저장하도록 바꿔라. reopen 후 Unique가 PK로 둔갑하는 시나리오를 만들어봐라.

<details><summary>답</summary>

저장 형식이 `[개수][컬럼목록][컬럼목록]…`처럼 종류 표시 없이 나열되면, 읽는 쪽은 **순서에 의존해 종류를 추측**할 수밖에 없다.

```
저장:  orders = PK(id), Unique(code), FK(user_id → users.id)
       → [3][["id"]][["code"]][["user_id"]]     종류 정보 없음

복원:  "첫 번째는 PK, 나머지는 Unique" 같은 규칙을 만들면
       → PK(id), Unique(code), Unique(user_id)   ← FK가 Unique로 둔갑
```

또는 제약을 하나만 선언한 테이블에서 `Unique(code)`가 첫 번째이므로 **PK로 읽힌다.** 그러면 `schema.primaryKey()`가 엉뚱한 값을 돌려주고, 06-04 `IndexedTableHeap`이 그 컬럼으로 인덱스를 만든다.

`Constraints persist 후 reopen하면 복원` 테스트가 이걸 잡는다 — `assertEquals(schema.constraints, restored.constraints)`는 **타입까지 비교**하기 때문이다(`Constraint.Unique`와 `Constraint.PrimaryKey`는 다른 클래스).

tag byte는 05-01·08-01·14-01에서 세 번 나오는 패턴이다: **여러 종류를 한 스트림에 섞어 쓸 때는 종류를 먼저 적는다.** 안 적으면 순서라는 암묵적 규칙에 의존하게 되고, 그 규칙은 코드 어디에도 검사되지 않는다.
</details>

**3.** `Constraint.PrimaryKey`를 `data class`가 아니라 일반 class로 바꾸면 어느 테스트가 깨지나? 왜인가?

<details><summary>답</summary>

`Constraints persist 후 reopen하면 복원`이 깨진다.

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
assertEquals(schema.constraints, restored.constraints)
assertEquals(schema.primaryKey(), restored.primaryKey())
```

`data class`를 떼면 `equals`가 **참조 비교**로 돌아간다. `restored`는 파일에서 새로 만든 **다른 객체**이므로 내용이 같아도 `false`다.

01-01의 `Record`와 정반대 결론이라는 점이 흥미롭다:

| | 자동 `equals` | 판정 |
|---|---|---|
| `Record(key: ByteArray, …)` | `ByteArray`가 참조 비교라 **거짓말을 한다** | data class **부적합** |
| `Constraint.PrimaryKey(columns: List<String>)` | `List<String>`이 내용 비교라 **참말을 한다** | data class **적합** |

기준은 "값 객체인가"가 아니라 **"자동 `equals`가 참인 말을 하는가"** 다. `List`·`String`·`Int`만 담고 있으면 그렇고, `ByteArray`가 하나라도 있으면 아니다. 08-01의 `LogRecord.InsertRow`가 `data class`이면서 `equals`를 손으로 다시 쓴 것도 같은 이유다 — data class의 편의는 원하지만 `ByteArray` 필드가 있어서.
</details>

**4.** FK가 참조하는 테이블이 아직 없을 때 스키마를 만들면? 지금 코드는 막는가 — 막지 않는다면 그게 옳은 선택인가?

<details><summary>답</summary>

**막지 않는다.** `validateConstraints()`가 FK에 대해 하는 일은 이것뿐이다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
is Constraint.ForeignKey -> {
    constraint.columns.forEach { c -> columnIndex(c) }   // 내 테이블의 컬럼만 확인
}
```

`refTable`이 실제로 존재하는지는 **보지 않는다.** `Catalog`를 참조하지 않으니 볼 수도 없다.

**이건 옳은 선택이다.** 순환 참조를 생각해보면 바로 나온다:

```sql
CREATE TABLE users  (id, favorite_order_id → orders.id)
CREATE TABLE orders (id, user_id           → users.id)
```

**어느 쪽을 먼저 만들어도 상대가 아직 없다.** 생성 시점에 존재를 강제하면 이 스키마는 영원히 만들 수 없다. 실제 DB도 같은 이유로 FK 대상 검사를 미루거나(`DEFERRABLE`), `ALTER TABLE ADD CONSTRAINT`로 나중에 붙이게 한다.

대신 **검사는 데이터를 넣는 시점으로 미뤄진다.** 06-03의 `validateForeignKey`가 `refTable`을 못 찾으면:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
val refHeap = foreignKeyLookup(fk.refTable)
    ?: throw ConstraintViolation("FOREIGN KEY refTable ${fk.refTable} not available")
```

여기서 터진다. 다만 이 예외는 **데이터 위반이 아니라 설정 문제**인데 같은 `ConstraintViolation`을 쓰고 있다 — 06-03에서 다시 만나는 지점이다.
</details>

## 8. 다음 한계

제약을 **선언**했지만 **강제하지는 않는다.** 지금 상태로 같은 PK 값을 가진 행을 두 번 insert하면 그대로 들어간다. 스키마는 "그러면 안 된다"고 적어뒀을 뿐이다.

→ **단계 6-3 `ConstraintValidator`**. 실제 데이터를 훑어 중복을 잡는다. 그리고 그 검사를 매번 풀스캔으로 하면 느리다는 문제가 곧바로 따라온다 → **06-04 IndexedTableHeap**.
