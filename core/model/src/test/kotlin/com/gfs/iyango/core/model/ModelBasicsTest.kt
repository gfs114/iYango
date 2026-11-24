package com.gfs.iyango.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 枚举编码与星期文案测试。
 */
class ModelBasicsTest {

    @Test
    fun `WeekType 编码往返`() {
        assertEquals(0, WeekType.EVERY.code)
        assertEquals(1, WeekType.ODD.code)
        assertEquals(2, WeekType.EVEN.code)
        assertEquals(WeekType.EVERY, WeekType.fromCode(0))
        assertEquals(WeekType.ODD, WeekType.fromCode(1))
        assertEquals(WeekType.EVEN, WeekType.fromCode(2))
        assertThrows(IllegalArgumentException::class.java) { WeekType.fromCode(3) }
    }

    @Test
    fun `DayLabels 完整与单字文案`() {
        assertEquals("星期一", DayLabels.label(1))
        assertEquals("星期日", DayLabels.label(7))
        assertEquals("", DayLabels.label(0))
        assertEquals("", DayLabels.label(8))
        assertEquals("一", DayLabels.shortLabel(1))
        assertEquals("日", DayLabels.shortLabel(7))
        assertEquals("", DayLabels.shortLabel(0))
        assertEquals("", DayLabels.shortLabel(9))
    }

    @Test
    fun `Course 默认值与参考实现一致`() {
        val course = Course()
        assertEquals(0L, course.id)
        assertEquals(1, course.dayOfWeek)
        assertEquals("#3E7BFA", course.color)
        assertEquals(0.0, course.credit, 0.0)
        assertTrue(course.enabled)
        assertFalse(course.timeUnconfirmed)
        assertEquals(1, course.cycleLength)
        assertEquals(0, course.cycleWeek)
        assertEquals(0L, course.overrideOfId)
        assertEquals(0, course.overrideWeek)
        assertEquals(0, course.timeMode)
    }

    @Test
    fun `Timetable 默认值与参考实现一致`() {
        val table = Timetable()
        assertEquals("LOCAL", table.source)
        assertFalse(table.autoEnabled)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), table.enabledDays)
        assertEquals(1, table.cycleLength)
        assertEquals(0, table.cycleWeek)
    }
}
