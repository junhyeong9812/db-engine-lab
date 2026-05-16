package com.dbenginelab.storage

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PagedFileTest {

    @Test
    fun `allocatePage 후 readPage하면 zero-filled page`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("paged.db").toString()
        PagedFile(path).use { pf ->
            val pid = pf.allocatePage()
            assertEquals(0, pid.pageNumber)
            val page = pf.readPage(pid)
            assertContentEquals(ByteArray(Page.PAGE_SIZE), page.rawData())
        }
    }

    @Test
    fun `writePage 후 reopen하면 같은 내용 read`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("paged.db").toString()
        val pid: PageId
        PagedFile(path).use { pf ->
            pid = pf.allocatePage()
            val page = pf.readPage(pid)
            val payload = "hello-page".toByteArray()
            page.write(0, payload)
            pf.writePage(page)
            pf.sync()
        }
        PagedFile(path).use { pf ->
            val page = pf.readPage(pid)
            assertContentEquals("hello-page".toByteArray(), page.read(0, "hello-page".length))
        }
    }

    @Test
    fun `존재하지 않는 page 읽으면 PageNotFound`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("paged.db").toString()
        PagedFile(path).use { pf ->
            assertFailsWith<StorageError.PageNotFound> {
                pf.readPage(PageId(0, 99))
            }
        }
    }

    @Test
    fun `pageCount는 allocate 횟수와 일치`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("paged.db").toString()
        PagedFile(path).use { pf ->
            assertEquals(0, pf.pageCount())
            pf.allocatePage()
            pf.allocatePage()
            pf.allocatePage()
            assertEquals(3, pf.pageCount())
        }
    }
}
