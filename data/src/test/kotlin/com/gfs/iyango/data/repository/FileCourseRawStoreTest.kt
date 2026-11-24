package com.gfs.iyango.data.repository

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileCourseRawStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test fun `save load overwrite round-trip and invalid id handling`() = runTest {
        val dir = tempFolder.newFolder("course_raw")
        val store = FileCourseRawStore(dir)

        assertNull(store.loadHtml(0L))
        assertNull(store.loadHtml(1L))
        store.saveHtml(0L, "<html>ignored</html>", "2026-2027-1", 1000L)
        assertNull(store.loadHtml(0L))

        store.saveHtml(42L, "<html>first</html>", "2026-2027-1", 12345L)
        val first = store.loadHtml(42L)
        assertNotNull(first)
        assertEquals("<html>first</html>", first!!.html)
        assertEquals("2026-2027-1", first.term)
        assertEquals(12345L, first.savedAt)

        // 覆盖写入应原子替换并保留新内容与元数据
        store.saveHtml(42L, "<html>second</html>", "2026-2027-2", 67890L)
        val second = store.loadHtml(42L)
        assertNotNull(second)
        assertEquals("<html>second</html>", second!!.html)
        assertEquals("2026-2027-2", second.term)
        assertEquals(67890L, second.savedAt)
        val leftoverTemp = dir.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }
        assertTrue(leftoverTemp.isEmpty())
    }

    @Test fun `oversized html is capped cleanly within 2MB without splitting utf8 sequences`() = runTest {
        val dir = tempFolder.newFolder("course_raw_large")
        val store = FileCourseRawStore(dir)

        // 构造超过 2 MB 的 3 字节 UTF-8 多字节字符序列，加 1 字节前缀触发边界对齐
        val maxBytes = 2 * 1024 * 1024
        val prefix = "a"
        val repeatCount = (maxBytes / 3) + 100
        val oversized = prefix + "课".repeat(repeatCount)
        assertTrue(oversized.toByteArray(Charsets.UTF_8).size > maxBytes)

        store.saveHtml(7L, oversized, "2026-2027-1", 999L)
        val loaded = store.loadHtml(7L)
        assertNotNull(loaded)
        val actualBytes = loaded!!.html.toByteArray(Charsets.UTF_8)
        assertTrue(actualBytes.size <= maxBytes)
        assertFalse("UTF-8 character must not be torn at 2MB boundary", loaded.html.contains('\uFFFD'))
    }
}
