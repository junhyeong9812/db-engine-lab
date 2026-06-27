package com.dbenginelab.storage

sealed class StorageError(message: String) : RuntimeException(message) {

    class CorruptRecord(offset: Long, reason: String)
        : StorageError("offset=$offset: $reason")

    class UnexpectedEof(expectedByte: Int, gotBytes: Int)
        : StorageError("expected $expectedByte bytes, got $gotBytes (partial write?)")
}