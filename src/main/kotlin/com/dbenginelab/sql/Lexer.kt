package com.dbenginelab.sql

class Lexer(private val source: String) {

    private val keywords = mapOf(
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
