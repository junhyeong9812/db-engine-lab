package com.dbenginelab.storage

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BufferPoolTest {

    @Test
    fun `newPage 후 같은 id로 fetch하면 같은 페이지`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("bp.db").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 4).use { bp ->
                val p1 = bp.newPage()
                p1.write(0, "x".toByteArray())
                bp.unpinPage(p1.id, isDirty = true)

                val p2 = bp.fetchPage(p1.id)
                assertContentEquals("x".toByteArray(), p2.read(0, 1))
                bp.unpinPage(p2.id, isDirty = false)
            }
        }
    }

    @Test
    fun `LRU eviction이 unpinned 페이지를 내보내고 dirty면 flush`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("bp.db").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 2).use { bp ->
                val a = bp.newPage()
                a.write(0, "A".toByteArray())
                bp.unpinPage(a.id, isDirty = true)

                val b = bp.newPage()
                b.write(0, "B".toByteArray())
                bp.unpinPage(b.id, isDirty = true)

                val c = bp.newPage()
                c.write(0, "C".toByteArray())
                bp.unpinPage(c.id, isDirty = true)

                assertEquals(2, bp.cachedPageCount())

                bp.flushAll()
            }
            PagedFile(path).use { pf2 ->
                val a2 = pf2.readPage(PageId(0, 0))
                assertContentEquals("A".toByteArray(), a2.read(0, 1))
                val c2 = pf2.readPage(PageId(0, 2))
                assertContentEquals("C".toByteArray(), c2.read(0, 1))
            }
        }
    }

    @Test
    fun `모든 페이지 pinned 상태에서 eviction 시도하면 AllPagesPinned`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("bp.db").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 2).use { bp ->
                bp.newPage()
                bp.newPage()
                assertFailsWith<StorageError.AllPagesPinned> {
                    bp.newPage()
                }
            }
        }
    }

    @Test
    fun `flushPage 후 reopen해서 같은 데이터 확인`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("bp.db").toString()
        val pid: PageId
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 4).use { bp ->
                val p = bp.newPage()
                pid = p.id
                p.write(0, "persist".toByteArray())
                bp.unpinPage(pid, isDirty = true)
                bp.flushPage(pid)
                pf.sync()
            }
        }
        PagedFile(path).use { pf ->
            val p = pf.readPage(pid)
            assertContentEquals("persist".toByteArray(), p.read(0, "persist".length))
        }
    }
}
