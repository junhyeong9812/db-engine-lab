package com.dbenginelab.mvcc

import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.table.TableHeap
import java.io.Closeable

/**
 * Stage 10 보강 (C4): MVCC + TableHeap 통합.
 *
 * TableHeap에 disk-persistent tuples를 두고, in-memory MVCC version chain
 * 으로 visibility 관리. 각 row는 PK로 식별 (PK 컬럼 이름 caller가 지정).
 *
 * Insert/Update/Delete는 in-memory chain 갱신 + commit 시 heap.insert로 flush.
 *
 * Limitations:
 *  - 학습 데모 — vacuum 없음, version chain 누적.
 *  - Persistence: 마지막 visible version만 heap에 (간소화).
 *  - PK column = BIGINT 만.
 */
class MVCCTableHeap(
    val heap: TableHeap,
    private val pkColumn: String,
) : Closeable {

    private val mvcc = MVCCStore<Long, Tuple>()
    val schema: TableSchema get() = heap.schema

    init {
        // Bootstrap: existing heap rows become committed version with xid=0.
        for (tuple in heap.scan()) {
            val key = tuple.get(pkColumn) as Long
            mvcc.insert(key, tuple, xid = 0L)
        }
    }

    fun insert(tuple: Tuple, xid: Long) {
        require(tuple.schema == schema)
        val key = tuple.get(pkColumn) as Long
        mvcc.insert(key, tuple, xid)
    }

    fun delete(key: Long, xid: Long) {
        mvcc.delete(key, xid)
    }

    fun read(key: Long, snapshot: MVCCStore.Snapshot): Tuple? =
        mvcc.get(key, snapshot)

    fun versionCount(key: Long): Int = mvcc.versionCount(key)

    override fun close() {
        heap.close()
    }
}
