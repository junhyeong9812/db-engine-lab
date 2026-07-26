package com.dbenginelab.executor

import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple

interface Operator {
    val outputSchema: TableSchema
    fun iterator(): Sequence<Tuple>
}