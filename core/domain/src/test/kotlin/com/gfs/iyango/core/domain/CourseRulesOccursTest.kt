package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.Timetable
import com.gfs.iyango.core.model.WeekType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * [CourseRules.occursInWeek]、[CourseRules.weekOfDate]、[CourseRules.tableApplies] 测试。
 */
class CourseRulesOccursTest {

    private fun course(
        startWeek: Int = 1,
        endWeek: Int = 10,
        weekType: WeekType = WeekType.EVERY,
        selectedWeeks: List<Int> = emptyList(),
        cycleLength: Int = 1,
        cycleWeek: Int = 0,
        enabled: Boolean = true,
        overrideWeek: Int = 0,
    ): Course = Course(
        id = 1,
        name = "测试课",
        dayOfWeek = 1,
        startWeek = startWeek,
        endWeek = endWeek,
        weekType = weekType,
        selectedWeeks = selectedWeeks,
        cycleLength = cycleLength,
        cycleWeek = cycleWeek,
        enabled = enabled,
        overrideOfId = if (overrideWeek > 0) 99L else 0L,
        overrideWeek = overrideWeek,
    )

    // ---------------------------------------------------------- occursInWeek

    @Test
    fun `每周课程按起止周边界判定`() {
        val c = course(startWeek = 3, endWeek = 5)
        assertFalse(CourseRules.occursInWeek(c, 2))
        assertTrue(CourseRules.occursInWeek(c, 3))
        assertTrue(CourseRules.occursInWeek(c, 4))
        assertTrue(CourseRules.occursInWeek(c, 5))
        assertFalse(CourseRules.occursInWeek(c, 6))
    }

    @Test
    fun `单周课程只在奇数周发生`() {
        val c = course(weekType = WeekType.ODD)
        assertTrue(CourseRules.occursInWeek(c, 1))
        assertFalse(CourseRules.occursInWeek(c, 2))
        assertTrue(CourseRules.occursInWeek(c, 3))
        assertFalse(CourseRules.occursInWeek(c, 4))
    }

    @Test
    fun `双周课程只在偶数周发生`() {
        val c = course(weekType = WeekType.EVEN)
        assertFalse(CourseRules.occursInWeek(c, 1))
        assertTrue(CourseRules.occursInWeek(c, 2))
        assertFalse(CourseRules.occursInWeek(c, 3))
        assertTrue(CourseRules.occursInWeek(c, 4))
    }

    @Test
    fun `selectedWeeks 非空时只看列表内周次`() {
        val c = course(startWeek = 1, endWeek = 5, selectedWeeks = listOf(2, 4))
        assertTrue(CourseRules.occursInWeek(c, 2))
        assertFalse(CourseRules.occursInWeek(c, 3))
        assertTrue(CourseRules.occursInWeek(c, 4))
        assertFalse(CourseRules.occursInWeek(c, 5))
        // 空列表 = 不额外筛选。
        val unfiltered = course(startWeek = 1, endWeek = 2)
        assertTrue(CourseRules.occursInWeek(unfiltered, 1))
        assertTrue(CourseRules.occursInWeek(unfiltered, 2))
    }

    @Test
    fun `轮换 cycleLength3 cycleWeek2 落在第 2 5 8 周`() {
        val c = course(cycleLength = 3, cycleWeek = 2)
        assertFalse(CourseRules.occursInWeek(c, 1))
        assertTrue(CourseRules.occursInWeek(c, 2))
        assertFalse(CourseRules.occursInWeek(c, 3))
        assertFalse(CourseRules.occursInWeek(c, 4))
        assertTrue(CourseRules.occursInWeek(c, 5))
        assertTrue(CourseRules.occursInWeek(c, 8))
        assertFalse(CourseRules.occursInWeek(c, 9))
    }

    @Test
    fun `停用课程任何周都不发生`() {
        val c = course(enabled = false, startWeek = 1, endWeek = 10)
        assertFalse(CourseRules.occursInWeek(c, 3))
    }

    @Test
    fun `单次调整课程只在目标周发生`() {
        val c = course(overrideWeek = 4)
        assertFalse(CourseRules.occursInWeek(c, 2))
        assertTrue(CourseRules.occursInWeek(c, 4))
        assertFalse(CourseRules.occursInWeek(c, 5))
    }

    @Test
    fun `单双周与轮换可叠加过滤`() {
        // 双周 + selectedWeeks 里只有奇数周 → 永不发生。
        val c = course(weekType = WeekType.EVEN, selectedWeeks = listOf(1, 3))
        assertFalse(CourseRules.occursInWeek(c, 1))
        assertFalse(CourseRules.occursInWeek(c, 2))
    }

    // ---------------------------------------------------------- weekOfDate

    /** 锚点为周三 2026-01-07 的学期，共 20 周。 */
    private val semester = Semester(
        id = 1,
        name = "2026 春",
        startDate = LocalDate.of(2026, 1, 7),
        totalWeeks = 20,
    )

    @Test
    fun `起始日为周三时按 floor 天数差除 7 归周`() {
        assertEquals(1, CourseRules.weekOfDate(semester, LocalDate.of(2026, 1, 7)))
        // 锚点后 6 天（下周二）仍是第 1 周。
        assertEquals(1, CourseRules.weekOfDate(semester, LocalDate.of(2026, 1, 13)))
        // 锚点后 7 天进入第 2 周。
        assertEquals(2, CourseRules.weekOfDate(semester, LocalDate.of(2026, 1, 14)))
    }

    @Test
    fun `锚点之前的天数向下取整得第 0 周`() {
        // -1 天：floor(-1/7) = -1 → 周 0（不是截断除法的周 1）。
        assertEquals(0, CourseRules.weekOfDate(semester, LocalDate.of(2026, 1, 6)))
        assertEquals(0, CourseRules.weekOfDate(semester, LocalDate.of(2026, 1, 1)))
        assertEquals(0, CourseRules.weekOfDate(semester, LocalDate.of(2025, 12, 31)))
        // -8 天：floor(-8/7) = -2 → 周 -1，钳制为 0。
        assertEquals(0, CourseRules.weekOfDate(semester, LocalDate.of(2025, 12, 30)))
    }

    @Test
    fun `学期范围外返回 0`() {
        // 第 20 周最后一天：锚点 + 139 天 = 2026-05-26。
        assertEquals(20, CourseRules.weekOfDate(semester, LocalDate.of(2026, 5, 26)))
        // 锚点 + 140 天 = 第 21 周，越界。
        assertEquals(0, CourseRules.weekOfDate(semester, LocalDate.of(2026, 5, 27)))
    }

    @Test
    fun `跨年学期按日期差归周`() {
        val winter = Semester(
            id = 2,
            name = "2026-2027 学年上",
            startDate = LocalDate.of(2026, 12, 28),
            totalWeeks = 20,
        )
        assertEquals(1, CourseRules.weekOfDate(winter, LocalDate.of(2026, 12, 28)))
        assertEquals(1, CourseRules.weekOfDate(winter, LocalDate.of(2027, 1, 3)))
        assertEquals(2, CourseRules.weekOfDate(winter, LocalDate.of(2027, 1, 4)))
    }

    @Test
    fun `学期为 null 返回 0`() {
        assertEquals(0, CourseRules.weekOfDate(null, LocalDate.of(2026, 1, 7)))
    }

    // ---------------------------------------------------------- tableApplies

    @Test
    fun `autoEnabled 关闭的课表恒生效`() {
        val table = Timetable(id = 1, autoEnabled = false, enabledDays = emptyList())
        assertTrue(CourseRules.tableApplies(table, 1, 3))
    }

    @Test
    fun `autoEnabled 课表按星期与轮换生效`() {
        val table = Timetable(id = 1, autoEnabled = true, enabledDays = listOf(1, 3, 5), cycleLength = 2, cycleWeek = 1)
        assertTrue(CourseRules.tableApplies(table, 1, 1))
        assertFalse(CourseRules.tableApplies(table, 2, 1))
        assertFalse(CourseRules.tableApplies(table, 3, 2))
        assertTrue(CourseRules.tableApplies(table, 3, 3))
    }
}
