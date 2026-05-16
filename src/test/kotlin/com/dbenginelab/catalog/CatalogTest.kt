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
