package com.dbenginelab.table

import com.dbenginelab.catalog.ColumnDef
import com.dbenginelab.catalog.Constraint
import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.catalog.Type
import com.dbenginelab.storage.BufferPool
import com.dbenginelab.storage.PagedFile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ConstraintValidatorTest {

    private fun userSchema() = TableSchema(
        name = "users",
        columns = listOf(
            ColumnDef("id", Type.BIGINT, nullable = false),
            ColumnDef("email", Type.STRING, nullable = false),
        ),
        constraints = listOf(
            Constraint.PrimaryKey(listOf("id")),
            Constraint.Unique(listOf("email")),
        ),
    )

    @Test
    fun `중복 PK 거부`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("u.data").toString()
        val schema = userSchema()
        PagedFile(path).use { pf -> BufferPool(pf, 16).use { bp ->
            val heap = TableHeap(schema, pf, bp)
            val v = ConstraintValidator(heap)
            val t1 = Tuple(schema, listOf(1L, "a@x.com"))
            v.validateInsert(t1); heap.insert(t1)
            assertThrows<ConstraintViolation> {
                v.validateInsert(Tuple(schema, listOf(1L, "b@x.com")))
            }
        }}
    }

    @Test
    fun `중복 Unique 거부`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("u.data").toString()
        val schema = userSchema()
        PagedFile(path).use { pf -> BufferPool(pf, 16).use { bp ->
            val heap = TableHeap(schema, pf, bp)
            val v = ConstraintValidator(heap)
            val t1 = Tuple(schema, listOf(1L, "a@x.com"))
            v.validateInsert(t1); heap.insert(t1)
            assertThrows<ConstraintViolation> {
                v.validateInsert(Tuple(schema, listOf(2L, "a@x.com")))
            }
        }}
    }

    @Test
    fun `FK 통과·위반`(@TempDir tempDir: Path) {
        val uPath = tempDir.resolve("u.data").toString()
        val oPath = tempDir.resolve("o.data").toString()
        val users = userSchema()
        val orders = TableSchema(
            name = "orders",
            columns = listOf(
                ColumnDef("oid", Type.BIGINT, nullable = false),
                ColumnDef("user_id", Type.BIGINT, nullable = false),
            ),
            constraints = listOf(Constraint.ForeignKey(listOf("user_id"), "users", listOf("id"))),
        )
        PagedFile(uPath).use { upf -> BufferPool(upf, 16).use { ubp ->
            PagedFile(oPath).use { opf -> BufferPool(opf, 16).use { obp ->
                val userHeap = TableHeap(users, upf, ubp)
                val orderHeap = TableHeap(orders, opf, obp)
                userHeap.insert(Tuple(users, listOf(10L, "u@x.com")))
                val v = ConstraintValidator(orderHeap) { name -> if (name == "users") userHeap else null }
                v.validateInsert(Tuple(orders, listOf(1L, 10L)))
                assertThrows<ConstraintViolation> { v.validateInsert(Tuple(orders, listOf(2L, 99L))) }
            }}
        }}
    }
}
