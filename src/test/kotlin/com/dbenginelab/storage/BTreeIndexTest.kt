package com.dbenginelab.storage

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BTreeIndexTest {

    @Test
    fun `insert 후 search로 같은 값 찾는다`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("btree.db").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 16).use { bp ->
                val idx = BTreeIndex(pf, bp)
                idx.insert(42L, 1000L)
                idx.insert(100L, 2000L)
                idx.insert(7L, 3000L)

                assertEquals(1000L, idx.search(42L))
                assertEquals(2000L, idx.search(100L))
                assertEquals(3000L, idx.search(7L))
            }
        }
    }

    @Test
    fun `존재하지 않는 key는 null 반환`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("btree.db").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 16).use { bp ->
                val idx = BTreeIndex(pf, bp)
                idx.insert(1L, 10L)
                assertNull(idx.search(999L))
            }
        }
    }

    @Test
    fun `정렬 안 된 순서로 insert 해도 정렬된 상태 유지`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("btree.db").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 16).use { bp ->
                val idx = BTreeIndex(pf, bp)
                val keys = listOf(50L, 10L, 80L, 30L, 70L, 20L, 60L, 90L, 40L)
                keys.forEachIndexed { i, k -> idx.insert(k, (i * 1000).toLong()) }

                for ((i, k) in keys.withIndex()) {
                    assertEquals((i * 1000).toLong(), idx.search(k), "key=$k")
                }
            }
        }
    }

    @Test
    fun `duplicate key insert는 IllegalArgumentException`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("btree.db").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 16).use { bp ->
                val idx = BTreeIndex(pf, bp)
                idx.insert(5L, 100L)
                assertThrows<IllegalArgumentException> { idx.insert(5L, 200L) }
            }
        }
    }

    @Test
    fun `reopen 후에도 데이터 보존 (BufferPool flush)`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("btree.db").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 16).use { bp ->
                val idx = BTreeIndex(pf, bp)
                idx.insert(1L, 10L)
                idx.insert(2L, 20L)
                idx.insert(3L, 30L)
                idx.close()
            }
        }
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 16).use { bp ->
                val idx = BTreeIndex(pf, bp)
                assertEquals(10L, idx.search(1L))
                assertEquals(20L, idx.search(2L))
                assertEquals(30L, idx.search(3L))
                assertEquals(3, idx.size())
            }
        }
    }

    @Test
    fun `leaf full 후 insert는 UnsupportedOperationException (stage 3-2에서 split 도입)`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("btree.db").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 16).use { bp ->
                val idx = BTreeIndex(pf, bp)
                for (k in 1..BTreePage.MAX_ENTRIES) {
                    idx.insert(k.toLong(), (k * 10).toLong())
                }
                assertEquals(BTreePage.MAX_ENTRIES, idx.size())
                assertThrows<UnsupportedOperationException> {
                    idx.insert((BTreePage.MAX_ENTRIES + 1).toLong(), 0L)
                }
            }
        }
    }
}
