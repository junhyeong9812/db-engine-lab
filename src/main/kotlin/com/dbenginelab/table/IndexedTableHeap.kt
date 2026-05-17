package com.dbenginelab.table

import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.storage.BTreeIndex
import java.io.Closeable

/**
 * Stage 6 보강 (X1): TableHeap + 자동 BTreeIndex 유지.
 *
 * insert(tuple)이 heap.insert + (PK 컬럼 기준) index.insert 를 한 계약으로.
 * findByKey(key)는 BTree로 빠른 lookup.
 *
 * Limitations:
 *  - Single PK column index만 (composite PK는 후속).
 *  - BIGINT PK only (BTreeIndex가 Long key — stage 3 BTree 한계).
 *  - DELETE/UPDATE 미지원 (TableHeap 자체가 미지원).
 */
class IndexedTableHeap(
    val heap: TableHeap,
    val index: BTreeIndex,
    private val pkColumnName: String,
) : Closeable {

    init {
        val col = heap.schema.column(pkColumnName)
        require(!col.nullable) { "PK column $pkColumnName must be NOT NULL" }
        require(col.type == com.dbenginelab.catalog.Type.BIGINT) {
            "stage 6 IndexedTableHeap only supports BIGINT PK (got ${col.type})"
        }
    }

    val schema: TableSchema get() = heap.schema

    /** Atomic-ish insert: validates uniqueness via index, then writes heap and index. */
    fun insert(tuple: Tuple) {
        require(tuple.schema == heap.schema)
        val key = tuple.get(pkColumnName) as Long
        // Q: 왜 index search를 heap insert 전에?
        if (index.search(key) != null) {
            throw ConstraintViolation("PK $pkColumnName=$key already exists in index")
        }
        heap.insert(tuple)
        // pseudo-row-id: insertion order (heap doesn't expose real row id at stage 6).
        // For learning, the value stored in the index is the row count after insert.
        index.insert(key, heap.rowCount().toLong())
    }

    fun findByKey(key: Long): Tuple? {
        val pos = index.search(key) ?: return null
        // Position is just for verification; we scan to find the matching tuple.
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
