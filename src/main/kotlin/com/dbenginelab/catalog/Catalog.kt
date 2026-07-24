package com.dbenginelab.catalog

import java.io.DataInputStream
import java.io.DataOutputStream

class Catalog(private val metaPath: String) {
    private val tables: MutableMap<String, TableSchema> = mutableMapOf()
    init { load() }

    fun registerTable(schema: TableSchema) {
        require(!tables.containsKey(schema.name))
        tables[schema.name] = schema
        save()
    }

    fun dropTable(name: String) {
        require(tables.containsKey(name))
        tables.remove(name); save()
    }

    fun getTable(name: String): TableSchema = tables[name] ?: throw NoSuchElementException("table $name not found")
    fun listTables(): List<String> = tables.keys.sorted()

    private fun load() {}
    private fun save() {}

    private fun writeConstraint(dos: DataOutputStream, c: Constraint) {
        when (c) {
            is Constraint.PrimaryKey -> {
                dos.writeByte(0)
                writeStringList(dos, c.columns)
            }
            is Constraint.Unique -> {
                dos.writeByte(1)
                writeStringList(dos, c.columns)
            }
            is Constraint.ForeignKey -> {
                dos.writeByte(2)
                writeStringList(dos, c.columns)
                writeString(dos, c.refTable)
                writeStringList(dos, c.refColumns)
            }
        }
    }

    private fun readConstraint(dis: DataInputStream): Constraint =
        when (dis.readByte().toInt()) {
            0 -> Constraint.PrimaryKey(readStringList(dis))
            1 -> Constraint.Unique(readStringList(dis))
            2 -> Constraint.ForeignKey(
                columns = readStringList(dis),
                refTable = readString(dis),
                refColumns = readStringList(dis),
            )
            else -> error("unknown constraint tag")
        }

    private fun writeString(dos: DataOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        dos.writeInt(bytes.size)
        dos.write(bytes)
    }

    private fun readString(dis: DataInputStream): String {
        val bytes = ByteArray(dis.readInt())
        dis.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun writeStringList(dos: DataOutputStream, list: List<String>) {
        dos.writeInt(list.size)
        list.forEach { writeString(dos, it) }
    }

    private fun readStringList(dis: DataInputStream): List<String> =
        List(dis.readInt()) { readString(dis) }
}