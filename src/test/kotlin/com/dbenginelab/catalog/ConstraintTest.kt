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
