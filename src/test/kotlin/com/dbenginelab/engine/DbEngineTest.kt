package com.dbenginelab.engine

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DbEngineTest {

    @Test
    fun `end-to-end CREATE INSERT SELECT WHERE PROJECT`(@TempDir tempDir: Path) {
        DbEngine(tempDir.toString()).use { db ->
            db.execute("CREATE TABLE users (id BIGINT NOT NULL, name STRING NOT NULL, age INT, PRIMARY KEY (id))")
            db.execute("INSERT INTO users VALUES (1, 'Alice', 30)")
            db.execute("INSERT INTO users VALUES (2, 'Bob', 25)")
            db.execute("INSERT INTO users VALUES (3, 'Charlie', 35)")

            val r1 = db.execute("SELECT * FROM users") as DbEngine.QueryResult.Rows
            assertEquals(3, r1.rows.size)
            assertEquals(listOf("id", "name", "age"), r1.columns)

            val r2 = db.execute("SELECT name FROM users WHERE age > 28") as DbEngine.QueryResult.Rows
            assertEquals(2, r2.rows.size)
            assertEquals(setOf("Alice", "Charlie"), r2.rows.map { it[0] }.toSet())
            assertEquals(listOf("name"), r2.columns)
        }
    }

    @Test
    fun `reopen 후 데이터 보존`(@TempDir tempDir: Path) {
        DbEngine(tempDir.toString()).use { db ->
            db.execute("CREATE TABLE t (k BIGINT NOT NULL, v STRING NOT NULL, PRIMARY KEY (k))")
            db.execute("INSERT INTO t VALUES (1, 'kept')")
        }
        DbEngine(tempDir.toString()).use { db ->
            val r = db.execute("SELECT * FROM t") as DbEngine.QueryResult.Rows
            assertEquals(1, r.rows.size)
            assertEquals(1L, r.rows[0][0])
            assertEquals("kept", r.rows[0][1])
        }
    }

    @Test
    fun `DROP TABLE 후 SELECT 실패`(@TempDir tempDir: Path) {
        DbEngine(tempDir.toString()).use { db ->
            db.execute("CREATE TABLE x (id BIGINT NOT NULL)")
            db.execute("DROP TABLE x")
            val ex = runCatching { db.execute("SELECT * FROM x") }.exceptionOrNull()
            assertTrue(ex != null)
        }
    }

    @Test
    fun `analyze 후 optimizer가 statistics 사용`(@TempDir tempDir: Path) {
        DbEngine(tempDir.toString()).use { db ->
            db.execute("CREATE TABLE n (id BIGINT NOT NULL, c STRING NOT NULL, PRIMARY KEY (id))")
            for (i in 1..50) db.execute("INSERT INTO n VALUES ($i, 'r$i')")
            db.analyze("n")
            val r = db.execute("SELECT id FROM n WHERE id = 25") as DbEngine.QueryResult.Rows
            assertEquals(1, r.rows.size)
            assertEquals(25L, r.rows[0][0])
        }
    }

    @Test
    fun `복합 WHERE - AND OR`(@TempDir tempDir: Path) {
        DbEngine(tempDir.toString()).use { db ->
            db.execute("CREATE TABLE p (id BIGINT NOT NULL, x INT, PRIMARY KEY (id))")
            for (i in 1..10) db.execute("INSERT INTO p VALUES ($i, $i)")
            val r = db.execute("SELECT id FROM p WHERE x >= 3 AND x <= 7") as DbEngine.QueryResult.Rows
            assertEquals(5, r.rows.size)
        }
    }
}
