package com.dbenginelab.executor

import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.table.TableHeap

class SeqScan(private val heap: TableHeap) : Operator {
    override val outputSchema: TableSchema = heap.schema
    override fun iterator(): Sequence<Tuple> = heap.scan()
}
