package com.dbenginelab.storage

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class AppendOnlyFileTest {

    @Test
    fun `I-1 append 후 reopen하면 모든 record를 순서대로 다시 읽는다`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("data.log").toString()
        val r1 = Record("k1".toByteArray(), "v1".toByteArray())
        val r2 = Record("longer-key-2".toByteArray(), "value-2".toByteArray())
        val r3 = Record("k3".toByteArray(), "v3".toByteArray())

        AppendOnlyFile(path).use { writer ->
            writer.append(r1); writer.append(r2);writer.append(r3)
            writer.flush()
        }

        AppendOnlyFile(path).use { reader ->
            val records: List<Record> = reader.scanAll()
            assertEquals(3, records.size, "record 개수 일치")
            assertContentEquals(r1.key, records[0].key)
            assertContentEquals(r2.key, records[1].key)
            assertContentEquals(r3.key, records[2].key)
        }

    }

    private class Record(val key: ByteArray, val value: ByteArray)
}
