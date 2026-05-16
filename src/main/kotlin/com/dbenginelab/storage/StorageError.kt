package com.dbenginelab.storage

sealed class StorageError(message: String) : RuntimeException(message) {
    class CorruptRecord(offset: Long, reason: String)
        : StorageError("offset=$offset: $reason")

    class UnexpectedEof(expectedBytes: Int, gotBytes: Int)
        : StorageError("expected $expectedBytes bytes, got $gotBytes (partial write?)")

    class PageNotFound(id: PageId)
        : StorageError("page not found: $id")

    class PageNotInPool(id: PageId)
        : StorageError("page not in buffer pool: $id")

    class AllPagesPinned(capacity: Int)
        : StorageError("all $capacity pages are pinned, cannot evict")
}
