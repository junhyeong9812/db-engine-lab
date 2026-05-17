package com.dbenginelab.sql

enum class TokenType {
    SELECT, FROM, WHERE, AND, OR, NOT, INSERT, INTO, VALUES, CREATE, TABLE, DROP,
    INT, BIGINT, STRING_T, NOT_T, NULL_T, PRIMARY, KEY,
    IDENT, NUMBER, STRING_LIT,
    COMMA, LPAREN, RPAREN, STAR, SEMICOLON,
    EQ, NE, LT, LE, GT, GE, EOF,
}

data class Token(val type: TokenType, val text: String, val pos: Int)
