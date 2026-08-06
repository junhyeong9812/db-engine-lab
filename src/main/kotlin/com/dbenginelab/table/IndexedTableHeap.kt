package com.dbenginelab.table

import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.storage.BTreeIndex
import java.io.Closeable

class IndexedTableHeap(
    val heap: TableHeap,
    val index: BTreeIndex,
    private val pkColumnName: String,
) : Closeable {

    init {
        val col = heap.schema.column(pkColumnName)
        require(!col.nullable) {"PK column $pkColumnName must be NOT NULL"}
        require(col.type == com.dbenginelab.catalog.Type.BIGINT) {
            "stage 6 IndexedTableHeap only supports BIGINT PK (got ${col.type})"
        }
    }

    val schema: TableSchema get() = heap.schema

    fun insert(tuple: Tuple) {
        require(tuple.schema == heap.schema)
        val key = tuple.get(pkColumnName) as Long
        if (index.search(key) != null) {
            throw ConstraintViolation("PK $pkColumnName=$key already exists in index")
        }
        heap.insert(tuple)
        index.insert(key, heap.rowCount().toLong())
    }

    fun findByKey(key: Long): Tuple? {
        val pos = index.search(key) ?: return null
        var i = 0L
        for (tuple in heap.scan()) {
            i++
            if (i == pos) return tuple
        }
        return null
    }

    fun rowCount(): Int = heap.rowCount()

    override fun close() {
        heap.close()
        index.close()
    }
}