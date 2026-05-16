package com.dbenginelab.executor

import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple

/**
 * Volcano-model operator. Producers implement [iterator]; consumers consume the
 * Sequence by `iterator()` or operator chaining.
 *
 * Output schema may differ from input (Project) so each operator exposes its own.
 */
interface Operator {
    val outputSchema: TableSchema
    fun iterator(): Sequence<Tuple>
}
