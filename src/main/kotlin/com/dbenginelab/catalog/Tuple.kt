package com.dbenginelab.catalog

import java.nio.ByteBuffer

/**
 * In-memory tuple. Values list size must match schema.columnCount.
 * NULL values are represented as `null` in the list.
 * On-disk encoding:
 *   [null bitmap (ceil(N/8) bytes)] [encoded non-null values in column order]
 */
class Tuple(val schema: TableSchema, val values: List<Any?>) {

    init {
        require(values.size == schema.columnCount) {
            "values size ${values.size} != schema columns ${schema.columnCount}"
        }
        for ((i, col) in schema.columns.withIndex()) {
            val v = values[i]
            if (v == null) {
                require(col.nullable) { "column ${col.name} is NOT NULL but got null" }
            } else {
                requireTypeMatches(col, v)
            }
        }
    }

    fun get(columnName: String): Any? = values[schema.columnIndex(columnName)]

    fun encode(): ByteArray {
        val n = schema.columnCount
        val bitmapSize = (n + 7) / 8
        // Estimate buffer size: bitmap + each value's max size. STRING handled below.
        val estimated = bitmapSize + values.sumOf { v ->
            if (v == null) 0
            else when (v) {
                is Int -> 4
                is Long -> 8
                is String -> 4 + v.toByteArray(Charsets.UTF_8).size
                else -> error("unsupported runtime type: ${v::class.simpleName}")
            }
        }
        val buf = ByteBuffer.allocate(estimated)
        val bitmap = ByteArray(bitmapSize)
        for ((i, v) in values.withIndex()) {
            if (v == null) bitmap[i / 8] = (bitmap[i / 8].toInt() or (1 shl (i % 8))).toByte()
        }
        buf.put(bitmap)
        for ((i, v) in values.withIndex()) {
            if (v != null) schema.columns[i].type.encode(v, buf)
        }
        return buf.array().copyOf(buf.position())
    }

    companion object {
        fun decode(schema: TableSchema, bytes: ByteArray): Tuple {
            val n = schema.columnCount
            val bitmapSize = (n + 7) / 8
            val buf = ByteBuffer.wrap(bytes)
            val bitmap = ByteArray(bitmapSize)
            buf.get(bitmap)
            val values = mutableListOf<Any?>()
            for (i in 0 until n) {
                val isNull = (bitmap[i / 8].toInt() shr (i % 8)) and 1 == 1
                if (isNull) values.add(null)
                else values.add(schema.columns[i].type.decode(buf))
            }
            return Tuple(schema, values)
        }

        private fun requireTypeMatches(col: ColumnDef, v: Any) {
            val ok = when (col.type) {
                Type.INT -> v is Int
                Type.BIGINT -> v is Long
                Type.STRING -> v is String
            }
            require(ok) {
                "column ${col.name} expects ${col.type} but got ${v::class.simpleName} (value=$v)"
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Tuple) return false
        return schema == other.schema && values == other.values
    }

    override fun hashCode(): Int = 31 * schema.hashCode() + values.hashCode()

    override fun toString(): String = "Tuple(${schema.name}, $values)"
}
