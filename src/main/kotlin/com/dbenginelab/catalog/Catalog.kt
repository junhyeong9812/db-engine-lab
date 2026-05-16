package com.dbenginelab.catalog

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Simple persistent catalog: stores table schemas in a single metadata file.
 *
 * On-disk format (per table):
 *   [4 bytes: name length][name bytes]
 *   [4 bytes: column count]
 *   repeat column count times:
 *     [4 bytes: column name length][column name bytes]
 *     [1 byte: type ordinal]
 *     [1 byte: nullable flag]
 *
 * The whole file is rewritten on each registerTable / dropTable. Simple but
 * incorrect under concurrent writers (single-thread assumption holds through stage 8).
 */
class Catalog(private val metaPath: String) {

    private val tables: MutableMap<String, TableSchema> = mutableMapOf()

    init {
        load()
    }

    fun registerTable(schema: TableSchema) {
        require(!tables.containsKey(schema.name)) { "table ${schema.name} already exists" }
        tables[schema.name] = schema
        save()
    }

    fun dropTable(name: String) {
        require(tables.containsKey(name)) { "table $name not found" }
        tables.remove(name)
        save()
    }

    fun getTable(name: String): TableSchema {
        return tables[name] ?: throw NoSuchElementException("table $name not found")
    }

    fun listTables(): List<String> = tables.keys.sorted()

    private fun load() {
        val file = File(metaPath)
        if (!file.exists() || file.length() == 0L) return
        RandomAccessFile(file, "r").use { raf ->
            DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(raf.fd))).use { dis ->
                while (dis.available() > 0) {
                    val schema = readSchema(dis)
                    tables[schema.name] = schema
                }
            }
        }
    }

    private fun save() {
        val file = File(metaPath)
        DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(file))).use { dos ->
            for (schema in tables.values) {
                writeSchema(dos, schema)
            }
            dos.flush()
        }
    }

    private fun writeSchema(dos: DataOutputStream, schema: TableSchema) {
        writeString(dos, schema.name)
        dos.writeInt(schema.columnCount)
        for (col in schema.columns) {
            writeString(dos, col.name)
            dos.writeByte(col.type.ordinal)
            dos.writeBoolean(col.nullable)
        }
        dos.writeInt(schema.constraints.size)
        for (c in schema.constraints) writeConstraint(dos, c)
    }

    private fun readSchema(dis: DataInputStream): TableSchema {
        val name = readString(dis)
        val colCount = dis.readInt()
        val cols = (0 until colCount).map {
            val colName = readString(dis)
            val type = Type.values()[dis.readByte().toInt()]
            val nullable = dis.readBoolean()
            ColumnDef(colName, type, nullable)
        }
        val constraintCount = dis.readInt()
        val constraints = (0 until constraintCount).map { readConstraint(dis) }
        return TableSchema(name, cols, constraints)
    }

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

    private fun readConstraint(dis: DataInputStream): Constraint {
        return when (dis.readByte().toInt()) {
            0 -> Constraint.PrimaryKey(readStringList(dis))
            1 -> Constraint.Unique(readStringList(dis))
            2 -> Constraint.ForeignKey(
                columns = readStringList(dis),
                refTable = readString(dis),
                refColumns = readStringList(dis),
            )
            else -> error("unknown constraint tag")
        }
    }

    private fun writeStringList(dos: DataOutputStream, list: List<String>) {
        dos.writeInt(list.size)
        list.forEach { writeString(dos, it) }
    }

    private fun readStringList(dis: DataInputStream): List<String> {
        val n = dis.readInt()
        return (0 until n).map { readString(dis) }
    }

    private fun writeString(dos: DataOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        dos.writeInt(bytes.size)
        dos.write(bytes)
    }

    private fun readString(dis: DataInputStream): String {
        val len = dis.readInt()
        val bytes = ByteArray(len)
        dis.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
