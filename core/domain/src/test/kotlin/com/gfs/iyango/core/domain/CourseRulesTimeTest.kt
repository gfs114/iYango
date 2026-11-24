package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.TimeSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CourseRules.timeRange]、[CourseRules.slotsOverlap]、minutes/clock 测试。
 */
class CourseRulesTimeTest {

    private val slots = listOf(
        TimeSlot(id = 1, section = 1, startTime = "09:00", endTime = "09:45"),
        TimeSlot(id = 2, section = 2, startTime = "10:00", endTime = "10:45"),
    )

    // ---------------------------------------------------------- timeRange

    @Test
    fun `timeUnconfirmed 的节次课程返回空时间`() {
        val course = Course(name = "待定课", timeMode = 0, timeUnconfirmed = true)
        val range = CourseRules.timeRange(course, slots)
        assertEquals("", range.startTime)
        assertEquals("", range.endTime)
        assertEquals(course.startSection, range.section)
    }

    @Test
    fun `timeUnconfirmed 只作用于节次模式`() {
        // timeMode=1 的课程即使带待确认标记也使用自定义时间（对齐参考实现）。
        val course = Course(
            name = "自定义课",
            timeMode = 1,
            timeUnconfirmed = true,
            startTime = "14:00",
            endTime = "15:30",
        )
        val range = CourseRules.timeRange(course, slots)
        assertEquals("14:00", range.startTime)
        assertEquals("15:30", range.endTime)
    }

    @Test
    fun `课程自定义时间优先于作息表`() {
        val course = Course(name = "课", timeMode = 1, startTime = "08:30", endTime = "09:15")
        val range = CourseRules.timeRange(course, slots)
        assertEquals("08:30", range.startTime)
        assertEquals("09:15", range.endTime)

        // timeMode=0 但自带合法钟点时同样优先生效（对齐参考实现判定顺序）。
        val withClocks = Course(name = "课", timeMode = 0, startTime = "08:30", endTime = "09:15")
        val range2 = CourseRules.timeRange(withClocks, slots)
        assertEquals("08:30", range2.startTime)
        assertEquals("09:15", range2.endTime)
    }

    @Test
    fun `无自定义时间时回退到首节开始与末节结束`() {
        val course = Course(name = "课", startSection = 1, endSection = 2)
        val range = CourseRules.timeRange(course, slots)
        assertEquals("09:00", range.startTime)
        assertEquals("10:45", range.endTime)
    }

    @Test
    fun `缺失节次的一侧回退为空`() {
        val course = Course(name = "课", startSection = 2, endSection = 3)
        val range = CourseRules.timeRange(course, slots)
        assertEquals("10:00", range.startTime)
        assertEquals("", range.endTime)
    }

    // ---------------------------------------------------------- minutes / clock

    @Test
    fun `minutes 与 clock 互逆`() {
        assertEquals(480, CourseRules.minutes("08:00"))
        assertEquals(605, CourseRules.minutes("10:05"))
        assertEquals(0, CourseRules.minutes("00:00"))
        assertNull(CourseRules.minutes(""))
        assertNull(CourseRules.minutes("7:30"))
        assertNull(CourseRules.minutes("24:00"))
        assertNull(CourseRules.minutes("08:60"))

        assertEquals("08:00", CourseRules.clock(480))
        assertEquals("10:05", CourseRules.clock(605))
        assertEquals("00:00", CourseRules.clock(0))
        assertEquals("23:59", CourseRules.clock(23 * 60 + 59))
    }

    // ---------------------------------------------------------- slotsOverlap

    private fun timed(startSection: Int, endSection: Int, start: String, end: String): Course = Course(
        name = "课",
        startSection = startSection,
        endSection = endSection,
        timeMode = 1,
        startTime = start,
        endTime = end,
    )

    @Test
    fun `半开区间首尾相接不冲突`() {
        // A 09:00-10:00 与 B 10:00-11:00 不冲突。
        val a = timed(1, 1, "09:00", "10:00")
        val b = timed(2, 2, "10:00", "11:00")
        assertFalse(CourseRules.slotsOverlap(a, b, slots))
        assertFalse(CourseRules.slotsOverlap(b, a, slots))
    }

    @Test
    fun `半开区间交错即冲突`() {
        val a = timed(1, 1, "09:00", "10:00")
        val b = timed(2, 2, "09:30", "10:30")
        assertTrue(CourseRules.slotsOverlap(a, b, slots))
        assertTrue(CourseRules.slotsOverlap(b, a, slots))
    }

    @Test
    fun `同节次同钟点冲突`() {
        val a = timed(1, 1, "09:00", "09:45")
        val b = timed(1, 1, "09:00", "09:45")
        assertTrue(CourseRules.slotsOverlap(a, b, slots))
    }

    @Test
    fun `时间缺失退化为闭区间节次重叠`() {
        // 空作息表 → timeRange 双双为空 → 按节次闭区间判定。
        val a = Course(name = "课", startSection = 1, endSection = 2)
        val b = Course(name = "课2", startSection = 2, endSection = 3)
        assertTrue(CourseRules.slotsOverlap(a, b, emptyList()))
        // 不相邻的节次不重叠。
        val c = Course(name = "课", startSection = 1, endSection = 1)
        val d = Course(name = "课2", startSection = 2, endSection = 2)
        assertFalse(CourseRules.slotsOverlap(c, d, emptyList()))
        // 相邻节次在闭区间语义下重叠（与半开钟点语义不同，属退化行为）。
        val e = Course(name = "课", startSection = 1, endSection = 1)
        val f = Course(name = "课2", startSection = 1, endSection = 2)
        assertTrue(CourseRules.slotsOverlap(e, f, emptyList()))
    }

    @Test
    fun `isTimeUsable 判定`() {
        assertTrue(CourseRules.isTimeUsable(TimeSlot(section = 1, startTime = "08:00", endTime = "08:45")))
        assertFalse(CourseRules.isTimeUsable(TimeSlot(section = 1)))
        assertFalse(CourseRules.isTimeUsable(TimeSlot(section = 1, startTime = "8:00", endTime = "08:45")))
    }
}
