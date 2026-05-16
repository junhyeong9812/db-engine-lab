package com.dbenginelab.storage

import java.io.Closeable
import java.io.EOFException
import java.io.RandomAccessFile

class AppendOnlyFile(path: String) : Closeable {

    private val file: RandomAccessFile = RandomAccessFile(path, "rw")

    init {
        file.seek(file.length())
    }

    fun append(record: Record) {
        file.seek(file.length())
        file.writeInt(record.key.size)
        file.write(record.key)
        file.writeInt(record.value.size)
        file.write(record.value)
    }

    fun flush() {
        file.fd.sync()
    }

    fun scanAll(): List<Record> {
        file.seek(0)
        val result = mutableListOf<Record>()
        while (file.filePointer < file.length()) {
            val recordStart = file.filePointer
            try {
                val keyLen = file.readInt()
                val key = ByteArray(keyLen)
                file.readFully(key)
                val valueLen = file.readInt()
                val value = ByteArray(valueLen)
                file.readFully(value)
                result.add(Record(key, value))
            } catch (e: EOFException) {
                throw StorageError.UnexpectedEof(
                    expectedBytes = -1,
                    gotBytes = (file.length() - recordStart).toInt()
                )
            }
        }
        return result
    }

    override fun close() {
        file.close()
    }
}
