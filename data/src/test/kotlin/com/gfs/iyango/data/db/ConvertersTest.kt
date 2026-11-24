package com.gfs.iyango.data.db

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 类型转换器纯 JVM 单测：LocalDate 与 List&lt;Int&gt; 的文本往返必须无损。
 */
class ConvertersTest {

    private val converters = Converters()

    @Test
    fun `LocalDate 与 ISO 文本往返无损`() {
        val date = LocalDate.of(2026, 9, 7)
        val text = converters.localDateToText(date)
        assertEquals("2026-09-07", text)
        assertEquals(date, converters.textToLocalDate(text))
    }

    @Test
    fun `LocalDate 边界日期往返无损`() {
        for (date in listOf(LocalDate.of(2026, 1, 1), LocalDate.of(2024, 2, 29), LocalDate.of(2026, 12, 31))) {
            assertEquals(date, converters.textToLocalDate(converters.localDateToText(date)))
        }
    }

    @Test
    fun `LocalDate null 与空文本归一为 null`() {
        assertNull(converters.localDateToText(null))
        assertNull(converters.textToLocalDate(null))
        assertNull(converters.textToLocalDate(""))
    }

    @Test
    fun `List Int 与逗号文本往返无损`() {
        val list = listOf(1, 3, 5)
        val text = converters.intListToText(list)
        assertEquals("1,3,5", text)
        assertEquals(list, converters.textToIntList(text))
    }

    @Test
    fun `单元素与含两位数列表往返无损`() {
        assertEquals(listOf(7), converters.textToIntList(converters.intListToText(listOf(7))))
        assertEquals(
            listOf(1, 10, 12),
            converters.textToIntList(converters.intListToText(listOf(1, 10, 12))),
        )
    }

    @Test
    fun `空列表往返无损且 null 归一为 null`() {
        assertEquals("", converters.intListToText(emptyList()))
        assertEquals(emptyList<Int>(), converters.textToIntList(converters.intListToText(emptyList())))
        assertNull(converters.intListToText(null))
        assertNull(converters.textToIntList(null))
    }
}
