package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.TimeSlot
import com.gfs.iyango.core.model.Timetable
import com.gfs.iyango.core.model.WeekType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * [ResolveScheduleOccurrences] 用例测试。
 *
 * 场景学期：锚点周一 2026-03-02，共 20 周；第 1 周为 2026-03-02（周一）..2026-03-08（周日）。
 */
class ResolveScheduleOccurrencesTest {

    private val semester = Semester(
        id = 1,
        timetableId = 1,
        name = "2026 春",
        startDate = LocalDate.of(2026, 3, 2),
        totalWeeks = 20,
    )
    private val timetable = Timetable(id = 1, name = "测试课表")
    private val slots = listOf(
        TimeSlot(id = 1, timetableId = 1, section = 1, startTime = "08:00", endTime = "08:45"),
        TimeSlot(id = 2, timetableId = 1, section = 2, startTime = "08:55", endTime = "09:40"),
    )
    private val week1 = LocalDate.of(2026, 3, 2)..LocalDate.of(2026, 3, 8)

    // ---------------------------------------------------------- 基本解析

    @Test
    fun `一周内解析出节次课_日期课与时间待定课`() {
        val math = Course(
            id = 1,
            timetableId = 1,
            name = "高等数学",
            dayOfWeek = 1,
            startSection = 1,
            endSection = 2,
            startWeek = 1,
            endWeek = 20,
        )
        val oneOff = Course(
            id = 2,
            timetableId = 1,
            name = "单次活动",
            // 故意错误的 dayOfWeek，应被 date 的星期（周五=5）覆盖。
            dayOfWeek = 1,
            startSection = 1,
            endSection = 1,
            startWeek = 1,
            endWeek = 20,
            timeMode = 2,
            date = "2026-03-06",
            startTime = "10:00",
            endTime = "11:00",
        )
        val unconfirmed = Course(
            id = 3,
            timetableId = 1,
            name = "时间待定课",
            dayOfWeek = 2,
            startSection = 1,
            endSection = 1,
            startWeek = 1,
            endWeek = 20,
            timeUnconfirmed = true,
        )
        val occurrences = ResolveScheduleOccurrences.invoke(
            timetable, semester, slots, listOf(math, oneOff, unconfirmed), week1,
        )
        assertEquals(3, occurrences.size)
        // 按日期排序。
        assertEquals(listOf("高等数学", "时间待定课", "单次活动"), occurrences.map { it.course.name })
        assertEquals(LocalDate.of(2026, 3, 2), occurrences[0].date)
        assertEquals(LocalDate.of(2026, 3, 3), occurrences[1].date)
        assertEquals(LocalDate.of(2026, 3, 6), occurrences[2].date)

        // 节次课：第 1 节开始 08:00=480，第 2 节结束 09:40=580。
        assertEquals(480, occurrences[0].startMinute)
        assertEquals(580, occurrences[0].endMinute)
        assertFalse(occurrences[0].isOverride)

        // 时间待定课：显式 null，不静默套默认作息（作息表本身是可用的）。
        assertNull(occurrences[1].startMinute)
        assertNull(occurrences[1].endMinute)

        // 日期课：星期被 date 覆盖为周五，自定义钟点生效。
        assertEquals(5, occurrences[2].course.dayOfWeek)
        assertEquals(600, occurrences[2].startMinute)
        assertEquals(660, occurrences[2].endMinute)
        assertFalse(occurrences[2].isOverride)
    }

    @Test
    fun `occurrenceId 与规格公式逐字一致`() {
        val math = Course(
            id = 1,
            timetableId = 1,
            name = "高等数学",
            dayOfWeek = 1,
            startSection = 1,
            endSection = 2,
            startWeek = 1,
            endWeek = 20,
        )
        val occurrences = ResolveScheduleOccurrences.invoke(timetable, semester, slots, listOf(math), week1)
        assertEquals(1, occurrences.size)
        // SHA-256("c:1|2026-03-02") 前 16 位十六进制。
        assertEquals("d7a355fa76838770", occurrences[0].occurrenceId)
    }

    @Test
    fun `未持久化课程使用 g 键派生身份`() {
        val draft = Course(
            id = 0,
            timetableId = 1,
            name = "高等数学",
            dayOfWeek = 1,
            startSection = 1,
            endSection = 2,
            startWeek = 1,
            endWeek = 20,
        )
        val occurrences = ResolveScheduleOccurrences.invoke(timetable, semester, slots, listOf(draft), week1)
        // SHA-256("g:1:1:1:2:高等数学|2026-03-02") 前 16 位十六进制。
        assertEquals("16edfdc07e6ff596", occurrences[0].occurrenceId)
    }

    // ---------------------------------------------------------- override 稳定性

    @Test
    fun `单次调整前后 occurrenceId 保持一致`() {
        val base = Course(
            id = 10,
            timetableId = 1,
            name = "大学英语",
            dayOfWeek = 1,
            startSection = 1,
            endSection = 1,
            startWeek = 1,
            endWeek = 20,
        )
        // 调整前：原课程自身的实例。
        val before = ResolveScheduleOccurrences.invoke(timetable, semester, slots, listOf(base), week1)
        assertEquals(1, before.size)
        val beforeId = before[0].occurrenceId
        // SHA-256("c:10|2026-03-02") 前 16 位。
        assertEquals("5f2f4718335b5e72", beforeId)
        assertEquals(480, before[0].startMinute)
        assertEquals(525, before[0].endMinute)
        assertFalse(before[0].isOverride)

        // 调整后：第 1 周换成 14:00-15:30 的 override，实例身份不变。
        val override = Course(
            id = 11,
            timetableId = 1,
            name = "大学英语(调)",
            dayOfWeek = 1,
            startSection = 1,
            endSection = 1,
            startWeek = 1,
            endWeek = 20,
            timeMode = 1,
            startTime = "14:00",
            endTime = "15:30",
            overrideOfId = 10,
            overrideWeek = 1,
        )
        val after = ResolveScheduleOccurrences.invoke(timetable, semester, slots, listOf(base, override), week1)
        assertEquals(1, after.size)
        assertTrue(after[0].isOverride)
        assertEquals(11L, after[0].course.id)
        assertEquals(beforeId, after[0].occurrenceId)
        assertEquals(840, after[0].startMinute)
        assertEquals(930, after[0].endMinute)

        // 第 2 周不受影响，仍出原课程。
        val week2 = LocalDate.of(2026, 3, 9)..LocalDate.of(2026, 3, 15)
        val secondWeek = ResolveScheduleOccurrences.invoke(
            timetable, semester, slots, listOf(base, override), week2,
        )
        assertEquals(listOf(10L), secondWeek.map { it.course.id })
        assertFalse(secondWeek[0].isOverride)
    }

    // ---------------------------------------------------------- 复核与排序

    @Test
    fun `occursInWeek 复核会拦下不满足单双周的日期课`() {
        // 2026-03-09 是第 2 周（偶数周），单周课程即使指定当天也不发生。
        val oddOneOff = Course(
            id = 2,
            timetableId = 1,
            name = "单周活动",
            dayOfWeek = 1,
            startSection = 1,
            endSection = 1,
            startWeek = 1,
            endWeek = 20,
            weekType = WeekType.ODD,
            timeMode = 2,
            date = "2026-03-09",
        )
        val occurrences = ResolveScheduleOccurrences.invoke(
            timetable, semester, slots, listOf(oddOneOff),
            LocalDate.of(2026, 3, 9)..LocalDate.of(2026, 3, 9),
        )
        assertEquals(0, occurrences.size)
    }

    @Test
    fun `同日按 startMinute 排序且时间未知排在最后`() {
        val math = Course(
            id = 1,
            timetableId = 1,
            name = "高等数学",
            dayOfWeek = 1,
            startSection = 1,
            endSection = 2,
            startWeek = 1,
            endWeek = 20,
        )
        val later = Course(
            id = 5,
            timetableId = 1,
            name = "第二节起的课",
            dayOfWeek = 1,
            startSection = 2,
            endSection = 2,
            startWeek = 1,
            endWeek = 20,
        )
        val unknown = Course(
            id = 4,
            timetableId = 1,
            name = "无作息课",
            dayOfWeek = 1,
            startSection = 5,
            endSection = 5,
            startWeek = 1,
            endWeek = 20,
        )
        val occurrences = ResolveScheduleOccurrences.invoke(
            timetable, semester, slots, listOf(unknown, later, math),
            LocalDate.of(2026, 3, 2)..LocalDate.of(2026, 3, 2),
        )
        // 高数 08:00=480 → 第二节课 08:55=535 → 无作息 null 排最后。
        assertEquals(listOf("高等数学", "第二节起的课", "无作息课"), occurrences.map { it.course.name })
        assertEquals(480, occurrences[0].startMinute)
        assertEquals(535, occurrences[1].startMinute)
        assertNull(occurrences[2].startMinute)
    }

    @Test
    fun `学期外日期不产生实例`() {
        val math = Course(
            id = 1,
            timetableId = 1,
            name = "高等数学",
            dayOfWeek = 1,
            startSection = 1,
            endSection = 2,
            startWeek = 1,
            endWeek = 20,
        )
        // 学期后（第 23 周）。
        val after = ResolveScheduleOccurrences.invoke(
            timetable, semester, slots, listOf(math),
            LocalDate.of(2026, 8, 3)..LocalDate.of(2026, 8, 9),
        )
        assertEquals(0, after.size)
        // 学期前（锚点前一周）。
        val before = ResolveScheduleOccurrences.invoke(
            timetable, semester, slots, listOf(math),
            LocalDate.of(2026, 2, 23)..LocalDate.of(2026, 3, 1),
        )
        assertEquals(0, before.size)
        // 学期为 null。
        val noSemester = ResolveScheduleOccurrences.invoke(timetable, null, slots, listOf(math), week1)
        assertEquals(0, noSemester.size)
    }
}
