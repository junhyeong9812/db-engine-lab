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

    private fun parseExpr(): SqlExpr = parseOr()
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
