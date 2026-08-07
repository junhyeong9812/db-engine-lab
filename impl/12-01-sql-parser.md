# impl/12-01 — SQL Parser (Lexer + Recursive Descent Parser)

> **종류**: 세션형
> **상위 단계**: `docs/stages/12-sql-parser.md`
> **코드 정본**: git `5505edc` — "complete: 21 stages + 12 보강 (120/120 tests)"
> **이 세션의 범위**: SQL 문자열을 AST로 바꾼다. 손으로 쓴 재귀 하강 파서 — 생성기 없이.
> **작성 파일**:
> - 신규 패키지: `src/main/kotlin/com/dbenginelab/sql/`
> - 신규: `sql/Token.kt` · `sql/Lexer.kt` · `sql/Ast.kt` · `sql/Parser.kt`
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/sql/ParserTest.kt`
> **검증**: `ParserTest` 5 PASSED
> **예상 타이핑 시간**: 70분 (파일 4개)

---

## 0. 참조

- 주 참조: SimpleDB `Parser` (단순한 편).
- **핵심 설계 결정 근거 — 왜 ANTLR을 안 쓰는가**: 문법 파일을 쓰고 코드를 생성하면 파서가 **블랙박스**가 된다. 이 프로젝트의 목적은 동작하는 파서를 갖는 것이 아니라 **파싱이 무엇인지 아는 것**이므로, 손으로 쓴다. 문법이 커지면 그때 생성기로 옮기는 것이 옳다.
- **AST는 sealed class** — 06-02의 `Expression`과 같은 이유다. 문법은 닫힌 집합이고, 새 노드가 생기면 이를 다루는 모든 `when`이 컴파일 에러를 내주는 편이 낫다.

## 1. 만족시킬 invariant

- **CI-1**: 유효한 SQL은 일관된 AST로 변환된다.
- **CI-2**: 잘못된 SQL은 **명확한 에러**를 낸다 — 조용히 이상한 AST를 만들지 않는다.

## 2. 의존성

- 없음 (독립 패키지). LogicalPlan과의 연결은 13-02.

## 3. 문제 정의 (TDD step 1)

지금까지 질의는 코드였다. `SELECT name FROM users WHERE age > 28` 이라는 **문자열**을 받으려면 두 단계가 필요하다:

1. **Lexer(어휘 분석)** — 문자열을 토큰 열로 자른다. `SELECT` `name` `FROM` `users` `WHERE` `age` `>` `28`. 여기서 결정할 것들이 나온다: 대소문자를 구분하나(키워드는 안 함), 문자열 리터럴의 따옴표 처리, 숫자와 식별자의 경계.
2. **Parser(구문 분석)** — 토큰 열을 트리로 만든다. **재귀 하강**은 문법 규칙 하나를 함수 하나로 옮기는 방식이다. `parseSelect()`가 `parseExpression()`을 부르고, 그것이 다시 `parseComparison()`을 부른다 — 함수 호출 구조가 곧 문법 구조다.

우선순위 문제가 여기서 처음 나온다. `a = 1 OR b = 2 AND c = 3`은 어떻게 묶이나? `AND`가 `OR`보다 강하게 묶여야 한다. 재귀 하강에서 이건 **함수의 호출 순서**로 표현된다 — `parseOr()`이 `parseAnd()`를 부르고, `parseAnd()`가 `parseComparison()`을 부르면 자동으로 그렇게 된다. 이 구조를 손으로 확인하는 것이 이번 세션의 핵심이다.

## 4. 실패 테스트 (TDD step 2)

```kotlin
// src/test/kotlin/com/dbenginelab/sql/ParserTest.kt @ 5505edc
// Lexer.kt — char-by-char tokenize
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
```

**예상 실패**: **컴파일 실패** — `Unresolved reference: Lexer`, `Parser`, `Ast` 관련 심볼들.

## 5. 구현 코드 (TDD step 3 — make it pass)

### 5.1 `Token.kt`

```kotlin
// src/main/kotlin/com/dbenginelab/sql/Token.kt @ 5505edc
package com.dbenginelab.sql

enum class TokenType {
    SELECT, FROM, WHERE, AND, OR, NOT, INSERT, INTO, VALUES, CREATE, TABLE, DROP,
    INT, BIGINT, STRING_T, NOT_T, NULL_T, PRIMARY, KEY,
    IDENT, NUMBER, STRING_LIT,
    COMMA, LPAREN, RPAREN, STAR, SEMICOLON,
    EQ, NE, LT, LE, GT, GE, EOF,
}

data class Token(val type: TokenType, val text: String, val pos: Int)
```

### 5.2 `Lexer.kt` — 문자열 → 토큰

```kotlin
// src/main/kotlin/com/dbenginelab/sql/Lexer.kt @ 5505edc
// Lexer.kt — char-by-char tokenize
package com.dbenginelab.sql

class Lexer(private val source: String) {

    // Q: keyword 비교가 lowercase?
    private val keywords = mapOf(
    // <details><summary>A</summary>
    // SQL keyword case-insensitive (SELECT = select). identifier만 case-preserve (text 원본).
    // </details>
        "select" to TokenType.SELECT, "from" to TokenType.FROM, "where" to TokenType.WHERE,
        "and" to TokenType.AND, "or" to TokenType.OR, "not" to TokenType.NOT,
        "insert" to TokenType.INSERT, "into" to TokenType.INTO, "values" to TokenType.VALUES,
        "create" to TokenType.CREATE, "table" to TokenType.TABLE, "drop" to TokenType.DROP,
        "int" to TokenType.INT, "bigint" to TokenType.BIGINT, "string" to TokenType.STRING_T,
        "null" to TokenType.NULL_T, "primary" to TokenType.PRIMARY, "key" to TokenType.KEY,
    )

    private var pos = 0

    fun tokenize(): List<Token> {
        val tokens = mutableListOf<Token>()
        while (pos < source.length) {
            val c = source[pos]
            when {
                c.isWhitespace() -> pos++
                c.isLetter() || c == '_' -> tokens.add(readIdent())
                c.isDigit() || (c == '-' && pos + 1 < source.length && source[pos + 1].isDigit())
                    -> tokens.add(readNumber())
                c == '\'' -> tokens.add(readString())
                c == ',' -> { tokens.add(Token(TokenType.COMMA, ",", pos)); pos++ }
                c == '(' -> { tokens.add(Token(TokenType.LPAREN, "(", pos)); pos++ }
                c == ')' -> { tokens.add(Token(TokenType.RPAREN, ")", pos)); pos++ }
                c == '*' -> { tokens.add(Token(TokenType.STAR, "*", pos)); pos++ }
                c == ';' -> { tokens.add(Token(TokenType.SEMICOLON, ";", pos)); pos++ }
                c == '=' -> { tokens.add(Token(TokenType.EQ, "=", pos)); pos++ }
                c == '<' -> {
                    if (pos + 1 < source.length && source[pos + 1] == '=') {
                        tokens.add(Token(TokenType.LE, "<=", pos)); pos += 2
                    } else if (pos + 1 < source.length && source[pos + 1] == '>') {
                        tokens.add(Token(TokenType.NE, "<>", pos)); pos += 2
                    } else { tokens.add(Token(TokenType.LT, "<", pos)); pos++ }
                }
                c == '>' -> {
                    if (pos + 1 < source.length && source[pos + 1] == '=') {
                        tokens.add(Token(TokenType.GE, ">=", pos)); pos += 2
                    } else { tokens.add(Token(TokenType.GT, ">", pos)); pos++ }
                }
                c == '!' && pos + 1 < source.length && source[pos + 1] == '=' -> {
                    tokens.add(Token(TokenType.NE, "!=", pos)); pos += 2
                }
                else -> error("unexpected character '$c' at pos $pos")
            }
        }
        tokens.add(Token(TokenType.EOF, "", pos))
        return tokens
    }

    private fun readIdent(): Token {
        val start = pos
        while (pos < source.length && (source[pos].isLetterOrDigit() || source[pos] == '_')) pos++
        val text = source.substring(start, pos)
        val type = keywords[text.lowercase()] ?: TokenType.IDENT
        return Token(type, text, start)
    }

    private fun readNumber(): Token {
        val start = pos
        if (source[pos] == '-') pos++
        while (pos < source.length && source[pos].isDigit()) pos++
        return Token(TokenType.NUMBER, source.substring(start, pos), start)
    }

    private fun readString(): Token {
        val start = pos
        pos++
        val sb = StringBuilder()
        while (pos < source.length && source[pos] != '\'') { sb.append(source[pos]); pos++ }
        require(pos < source.length) { "unterminated string at pos $start" }
        pos++
        return Token(TokenType.STRING_LIT, sb.toString(), start)
    }
}
```

### 5.3 `Ast.kt` — 문법의 모양

```kotlin
// src/main/kotlin/com/dbenginelab/sql/Ast.kt @ 5505edc
// Ast.kt
package com.dbenginelab.sql

import com.dbenginelab.catalog.ColumnDef

sealed class Statement {
    data class Select(val columns: List<String>?, val table: String, val where: SqlExpr?) : Statement()
    data class Insert(val table: String, val values: List<SqlExpr>) : Statement()
    data class CreateTable(val name: String, val columns: List<ColumnDef>, val primaryKey: List<String>?) : Statement()
    data class DropTable(val name: String) : Statement()
}

sealed class SqlExpr {
    data class Col(val name: String) : SqlExpr()
    data class LitNumber(val value: Long) : SqlExpr()
    data class LitString(val value: String) : SqlExpr()
    object LitNull : SqlExpr()
    data class Compare(val left: SqlExpr, val op: String, val right: SqlExpr) : SqlExpr()
    data class And(val left: SqlExpr, val right: SqlExpr) : SqlExpr()
    data class Or(val left: SqlExpr, val right: SqlExpr) : SqlExpr()
}
```

### 5.4 `Parser.kt` — 토큰 → AST

**함수 호출 구조가 곧 연산자 우선순위**라는 점을 확인하며 쳐라.

```kotlin
// src/main/kotlin/com/dbenginelab/sql/Parser.kt @ 5505edc
// Parser.kt — recursive descent (핵심 부분)
package com.dbenginelab.sql

import com.dbenginelab.catalog.ColumnDef
import com.dbenginelab.catalog.Type

class Parser(private val tokens: List<Token>) {
    private var pos = 0

    fun parseStatement(): Statement {
        val s = when (peek().type) {
            TokenType.SELECT -> parseSelect()
            TokenType.INSERT -> parseInsert()
            TokenType.CREATE -> parseCreate()
            TokenType.DROP -> parseDrop()
            else -> error("unexpected token ${peek()}")
        }
        if (peek().type == TokenType.SEMICOLON) advance()
        return s
    }

    private fun parseSelect(): Statement.Select {
        expect(TokenType.SELECT)
        val columns: List<String>? = if (peek().type == TokenType.STAR) {
            advance(); null
        } else {
            val cols = mutableListOf(expect(TokenType.IDENT).text)
            while (peek().type == TokenType.COMMA) { advance(); cols.add(expect(TokenType.IDENT).text) }
            cols
        }
        expect(TokenType.FROM)
        val table = expect(TokenType.IDENT).text
        val where = if (peek().type == TokenType.WHERE) { advance(); parseExpr() } else null
        return Statement.Select(columns, table, where)
    }

    private fun parseInsert(): Statement.Insert {
        expect(TokenType.INSERT); expect(TokenType.INTO)
        val table = expect(TokenType.IDENT).text
        expect(TokenType.VALUES); expect(TokenType.LPAREN)
        val values = mutableListOf(parsePrimary())
        while (peek().type == TokenType.COMMA) { advance(); values.add(parsePrimary()) }
        expect(TokenType.RPAREN)
        return Statement.Insert(table, values)
    }

    private fun parseCreate(): Statement.CreateTable {
        expect(TokenType.CREATE); expect(TokenType.TABLE)
        val name = expect(TokenType.IDENT).text
        expect(TokenType.LPAREN)
        val cols = mutableListOf<ColumnDef>()
        var pk: List<String>? = null
        cols.add(parseColumnDef())
        while (peek().type == TokenType.COMMA) {
            advance()
            if (peek().type == TokenType.PRIMARY) {
                advance(); expect(TokenType.KEY); expect(TokenType.LPAREN)
                val pkCols = mutableListOf(expect(TokenType.IDENT).text)
                while (peek().type == TokenType.COMMA) { advance(); pkCols.add(expect(TokenType.IDENT).text) }
                expect(TokenType.RPAREN)
                pk = pkCols
            } else {
                cols.add(parseColumnDef())
            }
        }
        expect(TokenType.RPAREN)
        return Statement.CreateTable(name, cols, pk)
    }

    private fun parseColumnDef(): ColumnDef {
        val name = expect(TokenType.IDENT).text
        val type = when (peek().type) {
            TokenType.INT -> { advance(); Type.INT }
            TokenType.BIGINT -> { advance(); Type.BIGINT }
            TokenType.STRING_T -> { advance(); Type.STRING }
            else -> error("expected column type, got ${peek()}")
        }
        var nullable = true
        if (peek().type == TokenType.NOT) {
            advance(); expect(TokenType.NULL_T); nullable = false
        }
        return ColumnDef(name, type, nullable)
    }

    private fun parseDrop(): Statement.DropTable {
        expect(TokenType.DROP); expect(TokenType.TABLE)
        val name = expect(TokenType.IDENT).text
        return Statement.DropTable(name)
    }

    // Q: parseOr → parseAnd → parseCompare 순서 — 왜 OR가 가장 바깥?
    private fun parseExpr(): SqlExpr = parseOr()
    // <details><summary>A</summary>
    // 연산자 우선순위 — OR 가장 낮음 (loose bind, 바깥), AND 중간, Compare 가장 tight. expression tree에서 OR가 root에 가까움.
    // </details>
    private fun parseOr(): SqlExpr {
        var left = parseAnd()
        while (peek().type == TokenType.OR) { advance(); left = SqlExpr.Or(left, parseAnd()) }
        return left
    }
    private fun parseAnd(): SqlExpr {
        var left = parseCompare()
        while (peek().type == TokenType.AND) { advance(); left = SqlExpr.And(left, parseCompare()) }
        return left
    }
    private fun parseCompare(): SqlExpr {
        val left = parsePrimary()
        return when (peek().type) {
            TokenType.EQ, TokenType.NE, TokenType.LT, TokenType.LE, TokenType.GT, TokenType.GE -> {
                val op = peek().text; advance()
                SqlExpr.Compare(left, op, parsePrimary())
            }
            else -> left
        }
    }
    private fun parsePrimary(): SqlExpr = when (peek().type) {
        TokenType.NUMBER -> SqlExpr.LitNumber(advance().text.toLong())
        TokenType.STRING_LIT -> SqlExpr.LitString(advance().text)
        TokenType.NULL_T -> { advance(); SqlExpr.LitNull }
        TokenType.IDENT -> SqlExpr.Col(advance().text)
        TokenType.LPAREN -> { advance(); val e = parseExpr(); expect(TokenType.RPAREN); e }
        else -> error("unexpected token in expression: ${peek()}")
    }

    private fun peek(): Token = tokens[pos]
    private fun advance(): Token = tokens[pos++]
    private fun expect(type: TokenType): Token {
        val t = peek()
        require(t.type == type) { "expected $type but got ${t.type} ('${t.text}') at pos ${t.pos}" }
        return advance()
    }
}
```

## 6. 검증 테스트 (TDD step 4 — green)

테스트 파일은 §4에서 저장한 것이 그대로 최종본이다.

```bash
./gradlew test --tests 'com.dbenginelab.sql.ParserTest'
```

**기대 결과**: `ParserTest` **5 PASSED**

invariant 대응:
- **CI-1** ← `SELECT star` · `SELECT col WHERE compound` · `INSERT VALUES` · `CREATE TABLE with PK` · `DROP TABLE`
- **CI-2** ← 이 5개로는 **검증되지 않는다.** 잘못된 SQL을 넣는 테스트가 하나도 없다 → §7 과제 1번.

## 7. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

**1.** `SELECT FROM WHERE` 처럼 망가진 SQL을 넣어봐라. 명확한 에러인가, 이상한 AST인가, `IndexOutOfBounds`인가?

<details><summary>답</summary>

**대개 `IllegalStateException`이나 `error(...)` 메시지가 나온다** — `Parser`가 토큰 종류를 확인하며 진행하다가 기대와 다르면 `error()`를 부르기 때문이다. 다만 **메시지의 품질은 자리마다 다르다.**

토큰이 아예 떨어지면 `peek()`이 리스트 끝을 넘어 `IndexOutOfBoundsException`이 날 수도 있다. 그건 "명확한 에러"가 아니다 — 사용자에게 "SQL 어디가 잘못됐다"를 못 알려준다.

**CI-2를 검증하는 테스트가 하나도 없다.** `ParserTest` 5개는 전부 정상 SQL이다. 직접 써야 할 것:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
@Test fun `잘못된 SQL은 명확한 에러`() {
    listOf(
        "SELECT FROM WHERE",
        "SELECT * FROM",             // 테이블명 없음
        "INSERT INTO t VALUES",      // 값 없음
        "SELECT * FROM t WHERE",     // 조건 없음
        "",                          // 빈 문자열
    ).forEach { sql ->
        val e = runCatching { Parser(Lexer(sql).tokenize()).parseStatement() }.exceptionOrNull()
        assertTrue(e != null, "should fail: $sql")
        assertTrue(e !is IndexOutOfBoundsException, "메시지 없는 실패: $sql")
    }
}
```

마지막 단언이 핵심이다 — **"터진다"가 아니라 "이해 가능한 이유로 터진다"** 를 요구한다. 파서에서 에러 품질은 기능이다.
</details>

**2.** `a = 1 OR b = 2 AND c = 3`을 파싱해 AST를 출력해봐라. `AND`가 먼저 묶였나? 코드의 어느 부분이 그것을 보장하나?

<details><summary>답</summary>

`AND`가 먼저 묶인다:

```
Or(
  Compare(a, EQ, 1),
  And(Compare(b, EQ, 2), Compare(c, EQ, 3))
)
```

보장하는 것은 **함수의 호출 방향**이다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
private fun parseExpr(): SqlExpr = parseOr()
private fun parseOr(): SqlExpr {
    var left = parseAnd()                              // ← OR는 AND를 부른다
    while (peek().type == OR) { advance(); left = Or(left, parseAnd()) }
}
private fun parseAnd(): SqlExpr {
    var left = parseCompare()                          // ← AND는 Compare를 부른다
    while (peek().type == AND) { advance(); left = And(left, parseCompare()) }
}
```

`parseOr`이 피연산자를 얻으려고 `parseAnd`를 부르므로, **`parseAnd`가 자기 몫을 다 먹고 나서야** `parseOr`이 결과를 받는다. `b = 2 AND c = 3`이 통째로 하나의 피연산자가 되는 것이다.

규칙을 한 줄로: **나중에 호출되는 함수일수록 더 강하게 묶는다.** 우선순위를 하나 더 넣고 싶으면(예: `NOT`) `parseAnd`와 `parseCompare` 사이에 함수를 하나 끼우면 된다 — **문법 구조가 곧 코드 구조**라는 재귀 하강의 장점이 이것이다.
</details>

**3.** `parseOr`이 `parseAnd` 대신 `parseCompare`를 부르게 바꿔라(우선순위 붕괴). 어떤 SQL에서 결과가 달라지나?

<details><summary>답</summary>

**실측: `SELECT col WHERE compound`가 실패한다** (5개 중 1개).

`a = 1 OR b = 2 AND c = 3`이 이렇게 바뀐다:

```
정상:   Or(a=1, And(b=2, c=3))       — "a거나, (b이고 c)"
붕괴:   And(Or(a=1, b=2), c=3)       — "(a거나 b)이고, c"
```

`OR`이 `AND`를 건너뛰고 `Compare`만 먹으므로 `a=1 OR b=2`가 먼저 묶이고, 남은 `AND c=3`이 그 위를 감싼다.

**실행하면 어떤 행이 잘못 나오나** — `a=1, b=0, c=0`인 행으로 확인해봐라:

```
정상:  a=1 → OR의 왼쪽이 참 → 통과 ✓
붕괴:  (a=1 OR b=2) 참, c=3 거짓 → AND 전체가 거짓 → 탈락 ✗
```

**있어야 할 행이 사라진다.** 그리고 SQL은 문법적으로 완벽히 유효하므로 아무 에러도 안 난다 — 조용히 틀린 답이 나온다.

이 프로젝트에서 반복해 만난 "조용히 틀림"의 또 다른 사례이고, 이번에는 **연산자 우선순위**가 원인이다. 그래서 SQL을 쓸 때 애매하면 괄호를 치라는 조언이 나온다.
</details>

**4.** 문자열 리터럴 안에 따옴표가 들어간 경우(`'it''s'`)를 넣어봐라. Lexer가 처리하나?

<details><summary>답</summary>

**처리하지 못한다.** `Lexer`의 문자열 스캔은 여는 따옴표부터 **다음 따옴표까지**를 문자열로 보고 끝낸다. `'it''s'`를 만나면:

```
'it'  → 문자열 "it" 으로 잘림
's'   → 그 뒤 s' 가 남아 이상한 토큰이 되거나 에러
```

고칠 자리는 Lexer의 문자열 처리 루프다 — 닫는 따옴표를 만났을 때 **바로 다음 문자도 따옴표인지** 확인해서, 그렇다면 문자열이 끝난 게 아니라 이스케이프된 따옴표 하나로 취급하고 계속 읽어야 한다.

그리고 이건 **16-01 논리 백업과 짝을 이룬다.** `Backup`은 덤프할 때 `'`를 `''`로 이스케이프해서 내보낸다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
is String -> "'${v.replace("'", "''")}'"
```

**내보낼 때는 이스케이프하는데 읽어들일 때는 해석하지 못한다.** 즉 우리가 만든 덤프를 우리 파서가 다시 못 읽는다. 백업-복원 왕복이 깨지는 실제 결함이고, 16-01 과제 1번에서 반대편으로 다시 만난다.
</details>

**5.** 파서에 `ORDER BY`를 추가하려면 어떤 함수를 어디에 넣어야 하나? 구현하지 말고 위치와 시그니처만 정해봐라.

<details><summary>답</summary>

**Lexer**: `ORDER`·`BY`·`ASC`·`DESC` 키워드를 `TokenType`에 추가.

**Ast**: `Statement.Select`에 필드 추가.

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
data class Select(
    val columns: List<String>?,
    val table: String,
    val where: SqlExpr?,
    val orderBy: List<OrderKey>? = null,      // 추가
)
data class OrderKey(val column: String, val descending: Boolean = false)
```

**Parser**: `parseSelect()` 안, `WHERE` 절 파싱 **뒤에** 넣는다. SQL 문법상 `ORDER BY`가 `WHERE` 다음이기 때문이다.

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
private fun parseOrderBy(): List<OrderKey>?   // ORDER 토큰이 없으면 null
```

여기서 중요한 점 — **`parseExpr` 계열에 넣지 않는다.** `ORDER BY`는 표현식이 아니라 **절(clause)** 이라 우선순위 사슬과 무관하다. 재귀 하강에서 "표현식 우선순위 사슬"과 "문장 구조"는 별개 층위다.

그리고 이걸 추가하면 **파서만으로 끝나지 않는다:**
- `LogicalPlan`에 `SortNode` 추가 → `SimpleOptimizer`의 `when`이 **컴파일 에러**를 낸다(sealed class의 값어치)
- `executor`에 `Sort` 연산자 추가 — 그런데 정렬은 **모든 행을 모아야** 하므로 지금까지의 스트리밍 `Sequence` 모델이 처음으로 깨진다(blocking operator)

**한 기능이 다섯 계층을 건드린다.** 13-02가 왜 별도 세션인지도 여기서 보인다.
</details>

## 8. 다음 한계

AST는 만들어졌지만 **아무도 그것을 실행하지 못한다.** 단계 11의 옵티마이저는 `LogicalPlan`을 받는데, 파서는 `Ast`를 뱉는다. 둘을 잇는 것이 없다.

→ **13-02 Translator**가 그 갭을 메우고, **14-00 DbEngine**이 전체를 한 줄로 묶는다.
