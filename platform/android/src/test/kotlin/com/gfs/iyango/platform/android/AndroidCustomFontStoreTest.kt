package com.gfs.iyango.platform.android

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidCustomFontStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test fun `isValidFontHeader accepts ttf otf true ttcf and rejects invalid bytes`() {
        assertTrue(isValidFontHeader(byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x00)))
        assertTrue(isValidFontHeader("OTTO\u0000".toByteArray(Charsets.US_ASCII)))
        assertTrue(isValidFontHeader("true\u0000".toByteArray(Charsets.US_ASCII)))
        assertTrue(isValidFontHeader("ttcf\u0000".toByteArray(Charsets.US_ASCII)))

        assertFalse(isValidFontHeader(byteArrayOf(0x00, 0x01, 0x00)))
        assertFalse(isValidFontHeader("\u0089PNG".toByteArray(Charsets.ISO_8859_1)))
        assertFalse(isValidFontHeader("<htm".toByteArray(Charsets.US_ASCII)))
    }

    @Test fun `resolve prevents traversal and delete removes stored font file`() = runBlocking {
        val dir = tempFolder.newFolder("custom_fonts")
        val outside = File(tempFolder.root, "outside.ttf").apply { writeBytes(byteArrayOf(0, 1, 0, 0)) }
        val valid = File(dir, "font_100.ttf").apply { writeBytes(byteArrayOf(0, 1, 0, 0)) }
        val store = AndroidCustomFontStore(dir)

        assertNull(store.resolve(""))
        assertNull(store.resolve("../outside.ttf"))
        assertNull(store.resolve(outside.absolutePath))
        assertNull(store.resolve("missing.ttf"))
        assertEquals(valid.absolutePath, store.resolve("font_100.ttf"))

        store.delete("../outside.ttf")
        assertTrue("Path traversal must not delete files outside root", outside.isFile)

        store.delete("font_100.ttf")
        assertFalse(valid.exists())
        assertNull(store.resolve("font_100.ttf"))
    }
}
