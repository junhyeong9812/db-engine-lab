package com.dbenginelab.sql

import com.dbenginelab.catalog.Type
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ParserTest {
    private fun parse(sql: String): Statement = Parser(Lexer(sql).tokenize()).parseStatement()

    @Test fun `SELECT star`() {
        val s = parse("SELECT * FROM users") as Statement.Select
        assertNull(s.columns); assertEquals("users", s.table)
    }

    @Test fun `SELECT col WHERE compound`() {
        val s = parse("SELECT id, name FROM users WHERE age >= 18 AND name = 'A'") as Statement.Select
        assertEquals(listOf("id", "name"), s.columns)
        assertNotNull(s.where)
        val and = s.where as SqlExpr.And
        val left = and.left as SqlExpr.Compare
        assertEquals(">=", left.op)
    }

    @Test fun `INSERT VALUES`() {
        val s = parse("INSERT INTO users VALUES (1, 'A', 30)") as Statement.Insert
        assertEquals("users", s.table); assertEquals(3, s.values.size)
        assertEquals(1L, (s.values[0] as SqlExpr.LitNumber).value)
    }

    @Test fun `CREATE TABLE with PK`() {
        val s = parse("""
            CREATE TABLE users (
              id BIGINT NOT NULL,
              name STRING NOT NULL,
              age INT,
              PRIMARY KEY (id)
            )
        """.trimIndent()) as Statement.CreateTable
        assertEquals(3, s.columns.size)
        assertEquals(Type.BIGINT, s.columns[0].type)
        assertEquals(false, s.columns[0].nullable)
        assertEquals(listOf("id"), s.primaryKey)
    }

    @Test fun `DROP TABLE`() {
        val s = parse("DROP TABLE users") as Statement.DropTable
        assertEquals("users", s.name)
    }
}
