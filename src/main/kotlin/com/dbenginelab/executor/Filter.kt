package com.dbenginelab.executor

import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple

class Filter(private val child: Operator, private val predicate: Expression) : Operator {
    override val outputSchema: TableSchema = child.outputSchema
    override fun iterator(): Sequence<Tuple> = child.iterator().filter { tuple ->
        predicate.evaluate(tuple) == true
    }
}
