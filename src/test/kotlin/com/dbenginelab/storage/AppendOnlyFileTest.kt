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
            writer.append(r1)
            writer.append(r2)
            writer.append(r3)
            writer.flush()
        }

        AppendOnlyFile(path).use { reader ->
            val records: List<Record> = reader.scanAll()
            assertEquals(3, records.size, "record 개수 일치")
            assertContentEquals(r1.key, records[0].key)
            assertContentEquals(r1.value, records[0].value)
            assertContentEquals(r2.key, records[1].key)
            assertContentEquals(r2.value, records[1].value)
            assertContentEquals(r3.key, records[2].key)
            assertContentEquals(r3.value, records[2].value)
        }
    }

    @Test
    fun `I-2 빈 record(zero-length key·value)도 정상 처리`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("empty.log").toString()

        AppendOnlyFile(path).use { writer ->
            writer.append(Record(ByteArray(0), ByteArray(0)))
            writer.append(Record("k".toByteArray(), ByteArray(0)))
            writer.append(Record(ByteArray(0), "v".toByteArray()))
            writer.flush()
        }

        AppendOnlyFile(path).use { reader ->
            val records = reader.scanAll()
            assertEquals(3, records.size)
            assertEquals(0, records[0].key.size)
            assertEquals(0, records[0].value.size)
            assertEquals("k", String(records[1].key))
            assertEquals(0, records[1].value.size)
            assertEquals(0, records[2].key.size)
            assertEquals("v", String(records[2].value))
        }
    }

    @Test
    fun `I-1 append→scan→append 시퀀스도 모든 record 보존`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("interleaved.log").toString()

        AppendOnlyFile(path).use { f ->
            f.append(Record("a".toByteArray(), "1".toByteArray()))
            f.append(Record("b".toByteArray(), "2".toByteArray()))
            f.flush()

            val midScan = f.scanAll()
            assertEquals(2, midScan.size)

            f.append(Record("c".toByteArray(), "3".toByteArray()))
            f.flush()

            val finalScan = f.scanAll()
            assertEquals(3, finalScan.size)
            assertEquals("c", String(finalScan[2].key))
        }
    }
}
