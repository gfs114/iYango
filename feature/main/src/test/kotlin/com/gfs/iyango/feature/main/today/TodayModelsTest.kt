package com.gfs.iyango.feature.main.today

import com.gfs.iyango.core.domain.CourseOccurrence
import com.gfs.iyango.core.domain.CourseStatus
import com.gfs.iyango.core.model.Course
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 今日页纯逻辑测试（[focusLabel] / [monthCalendar] / [greetingText] / [sortRows]）。
 */
class TodayModelsTest {

    private fun row(
        start: Int?,
        end: Int?,
        status: CourseStatus = CourseStatus.NOT_STARTED,
        name: String = "课程",
    ) = OccurrenceRow(
        occurrence = CourseOccurrence(
            occurrenceId = name,
            course = Course(id = name.hashCode().toLong(), name = name),
            date = LocalDate.of(2026, 10, 24),
            startMinute = start,
            endMinute = end,
            isOverride = false,
        ),
        status = status,
    )

    @Test
    fun `有进行中课程时标签为正在上课`() {
        assertEquals(
            "正在上课",
            focusLabel(
                listOf(
                    row(8 * 60, 9 * 60 + 40, CourseStatus.IN_PROGRESS),
                    row(10 * 60, 11 * 60 + 40),
                )
            ),
        )
    }

    @Test
    fun `无进行中有未开始时标签为下一门`() {
        assertEquals(
            "下一门",
            focusLabel(
                listOf(
                    row(8 * 60, 9 * 60 + 40, CourseStatus.FINISHED),
                    row(10 * 60, 11 * 60 + 40),
                )
            ),
        )
    }

    @Test
    fun `全部结束时标签为 null`() {
        assertNull(
            focusLabel(listOf(row(8 * 60, 9 * 60 + 40, CourseStatus.FINISHED))),
        )
    }

    @Test
    fun `时间缺失时标签为时间待确认`() {
        assertEquals(
            "时间待确认",
            focusLabel(
                listOf(
                    row(8 * 60, 9 * 60 + 40, CourseStatus.FINISHED),
                    row(null, null),
                )
            ),
        )
    }

    @Test
    fun `空列表返回 null`() {
        assertNull(focusLabel(emptyList()))
    }

    @Test
    fun `排序时间缺失排最后同名按名称`() {
        val sorted = sortRows(
            listOf(
                row(null, null, name = "自习"),
                row(14 * 60, 15 * 60 + 40, name = "B课"),
                row(8 * 60, 9 * 60 + 40, name = "A课"),
            )
        )
        assertEquals(listOf("A课", "B课", "自习"), sorted.map { it.occurrence.course.name })
    }

    @Test
    fun `月历矩阵首行对齐周一首格`() {
        // 2026-10-01 是周四：首行前 3 格为空。
        val calendar = monthCalendar(YearMonth.of(2026, 10), today = LocalDate.of(2026, 10, 24))
        assertEquals(3, calendar.weeks.first().count { it == null })
        assertEquals(LocalDate.of(2026, 10, 1), calendar.weeks.first()[3])
        assertEquals(7, calendar.weeks.first().size)
        assertEquals(LocalDate.of(2026, 10, 24), calendar.today)
    }

    @Test
    fun `月历today不在本月时为null`() {
        val calendar = monthCalendar(YearMonth.of(2026, 10), today = LocalDate.of(2026, 11, 2))
        assertNull(calendar.today)
    }

    @Test
    fun `问候语按时段划分`() {
        assertEquals("早上好", greetingText(6))
        assertEquals("上午好", greetingText(10))
        assertEquals("下午好", greetingText(15))
        assertEquals("晚上好", greetingText(22))
        assertEquals("晚上好", greetingText(2))
    }

    @Test
    fun `时间列文案与占位`() {
        val normal = row(8 * 60, 9 * 60 + 40)
        assertEquals("08:00", normal.startText)
        assertEquals("09:40", normal.endText)
        val unknown = row(null, null)
        assertEquals("--:--", unknown.startText)
    }

    @Test
    fun `中文日期文案`() {
        assertEquals("10月24日", chineseDateText(LocalDate.of(2026, 10, 24)))
        assertTrue(monthTitleText(YearMonth.of(2026, 10)).contains("2026"))
    }
}
