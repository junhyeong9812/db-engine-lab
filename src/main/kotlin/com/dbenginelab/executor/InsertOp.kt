package com.dbenginelab.executor

import com.dbenginelab.catalog.Tuple
import com.dbenginelab.table.TableHeap

/**
 * Imperative insert (not an Operator that returns tuples — returns inserted count).
 * In stage 6, mutation operators are kept simple as direct calls; richer DML operator
 * model is added with the SQL layer (stage 12).
 */
class InsertOp(private val heap: TableHeap) {
    fun insertOne(tuple: Tuple) {
        heap.insert(tuple)
    }

    fun insertMany(tuples: Iterable<Tuple>): Int {
        var count = 0
        for (t in tuples) {
            heap.insert(t)
            count++
        }
        return count
    }
}
