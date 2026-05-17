# impl/12-01 — SQL Parser (한 줄 한 줄)

> **검증**: ParserTest 5 PASSED.
> 작성 파일:
> - 신규 패키지: `src/main/kotlin/com/dbenginelab/sql/`
> - 신규: Token.kt, Lexer.kt, Ast.kt, Parser.kt
> - 신규 테스트: ParserTest.kt

## 0. 참조
- SimpleDB `Parser` (단순).

## 1. invariant
- valid SQL → 일관 AST.
- invalid SQL → 명확 에러.

## 2. 핵심 결정
- Hand-written recursive descent (ANTLR 미사용).
- Sealed AST.
- Subset: SELECT/INSERT/CREATE/DROP.
- Keyword case-insensitive.

## 3. Token.kt + Lexer.kt — 한 줄 한 줄

```kotlin
// Token.kt
package com.dbenginelab.sql                                          // 신규 sql 패키지

enum class TokenType {
    SELECT, FROM, WHERE, AND, OR, NOT, INSERT, INTO, VALUES, CREATE, TABLE, DROP,
    INT, BIGINT, STRING_T, NOT_T, NULL_T, PRIMARY, KEY,
    IDENT, NUMBER, STRING_LIT,
    COMMA, LPAREN, RPAREN, STAR, SEMICOLON,
    EQ, NE, LT, LE, GT, GE, EOF,
}
data class Token(val type: TokenType, val text: String, val pos: Int)
```

```kotlin
// Lexer.kt — char-by-char tokenize
package com.dbenginelab.sql

class Lexer(private val source: String) {

    // Q: keyword 비교가 lowercase?
    private val keywords = mapOf(
        "select" to TokenType.SELECT, "from" to TokenType.FROM, "where" to TokenType.WHERE,
        "and" to TokenType.AND, "or" to TokenType.OR, "not" to TokenType.NOT,
        // ... (전체 keyword 매핑)
    )
    // <details><summary>A</summary>
    // SQL keyword case-insensitive (SELECT = select). identifier만 case-preserve (text 원본).
    // </details>

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
                // ... (단일 문자 토큰들 + <=, <>, >=, != 처리)
                else -> error("unexpected character '$c' at pos $pos")
            }
        }
        tokens.add(Token(TokenType.EOF, "", pos))
        return tokens
    }

    private fun readIdent(): Token { /* ... */ TODO() }
    private fun readNumber(): Token { /* ... */ TODO() }
    private fun readString(): Token { /* ... */ TODO() }
}
```

## 4. Ast.kt + Parser.kt — 한 줄 한 줄

```kotlin
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

```kotlin
// Parser.kt — recursive descent (핵심 부분)
package com.dbenginelab.sql
import com.dbenginelab.catalog.ColumnDef
import com.dbenginelab.catalog.Type

class Parser(private val tokens: List<Token>) {
    private var pos = 0

    fun parseStatement(): Statement = when (peek().type) {
        TokenType.SELECT -> parseSelect()
        TokenType.INSERT -> parseInsert()
        TokenType.CREATE -> parseCreate()
        TokenType.DROP -> parseDrop()
        else -> error("unexpected token ${peek()}")
    }

    // Q: parseOr → parseAnd → parseCompare 순서 — 왜 OR가 가장 바깥?
    private fun parseExpr(): SqlExpr = parseOr()
    private fun parseOr(): SqlExpr {
        var left = parseAnd()
        while (peek().type == TokenType.OR) { advance(); left = SqlExpr.Or(left, parseAnd()) }
        return left
    }
    private fun parseAnd(): SqlExpr { /* ... */ TODO() }
    private fun parseCompare(): SqlExpr { /* ... */ TODO() }
    private fun parsePrimary(): SqlExpr { /* ... */ TODO() }
    // <details><summary>A</summary>
    // 연산자 우선순위 — OR 가장 낮음 (loose bind, 바깥), AND 중간, Compare 가장 tight. expression tree에서 OR가 root에 가까움.
    // </details>

    private fun peek(): Token = tokens[pos]
    private fun advance(): Token = tokens[pos++]
    private fun expect(type: TokenType): Token {
        val t = peek()
        require(t.type == type) { "expected $type but got ${t.type} ('${t.text}') at pos ${t.pos}" }
        return advance()
    }
}
```

## 5. 검증 (5 PASSED)
- SELECT *
- SELECT col WHERE compound (AND, >=)
- INSERT VALUES
- CREATE TABLE with PK
- DROP TABLE

## 6. 깨뜨릴 과제
- `SELECT * FROM t WHERE a=1 AND b=2 OR c=3` AST 그려보기 (AND > OR tight)
- cartesian (`FROM t1, t2`) — 우리 parser는?
- error 위치 (`pos`) 활용 친화 메시지?

## 7. 다음 한계
- AST→LogicalPlan translator 없음 → **13-02 Translator (C1 보강)**.
