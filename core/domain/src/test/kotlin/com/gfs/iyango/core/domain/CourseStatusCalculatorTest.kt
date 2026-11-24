package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * [CourseStatusCalculator] 状态与进度测试。
 *
 * 基准课程：2026-03-04（周三）10:00-11:00，即分钟 600-660。
 */
class CourseStatusCalculatorTest {

    private val day: LocalDate = LocalDate.of(2026, 3, 4)
    private val START = 600
    private val END = 660

    private fun at(hour: Int, minute: Int, day: LocalDate = this.day): LocalDateTime =
        LocalDateTime.of(day, java.time.LocalTime.of(hour, minute))

    // ---------------------------------------------------------- resolve

    @Test
    fun `提前 30 分钟窗口的边界`() {
        assertEquals(CourseStatus.NOT_STARTED, CourseStatusCalculator.resolve(day, at(9, 29), START, END))
        assertEquals(CourseStatus.UPCOMING, CourseStatusCalculator.resolve(day, at(9, 30), START, END))
        assertEquals(CourseStatus.UPCOMING, CourseStatusCalculator.resolve(day, at(9, 59), START, END))
        assertEquals(CourseStatus.IN_PROGRESS, CourseStatusCalculator.resolve(day, at(10, 0), START, END))
    }

    @Test
    fun `进行中包含结束分钟`() {
        assertEquals(CourseStatus.IN_PROGRESS, CourseStatusCalculator.resolve(day, at(10, 30), START, END))
        assertEquals(CourseStatus.IN_PROGRESS, CourseStatusCalculator.resolve(day, at(11, 0), START, END))
        assertEquals(CourseStatus.FINISHED, CourseStatusCalculator.resolve(day, at(11, 1), START, END))
    }

    @Test
    fun `结束等于开始的课程按规格判为未开始`() {
        // 规格明确：end == start → NOT_STARTED（参考实现的 resolve 只挡 end < start，
        // 此处以规格为准，见实现说明中的冲突点）。
        assertEquals(CourseStatus.NOT_STARTED, CourseStatusCalculator.resolve(day, at(10, 0), START, START))
        assertEquals(CourseStatus.NOT_STARTED, CourseStatusCalculator.resolve(day, at(10, 0), 600, 590))
    }

    @Test
    fun `时间不可用判为未开始`() {
        assertEquals(CourseStatus.NOT_STARTED, CourseStatusCalculator.resolve(day, at(10, 30), null, END))
        assertEquals(CourseStatus.NOT_STARTED, CourseStatusCalculator.resolve(day, at(10, 30), START, null))
        assertEquals(CourseStatus.NOT_STARTED, CourseStatusCalculator.resolve(day, at(10, 30), null, null))
    }

    @Test
    fun `跨日按日期先后判定`() {
        assertEquals(CourseStatus.FINISHED, CourseStatusCalculator.resolve(day, at(8, 0, day.plusDays(1)), START, END))
        assertEquals(CourseStatus.NOT_STARTED, CourseStatusCalculator.resolve(day, at(23, 0, day.minusDays(1)), START, END))
    }

    // ---------------------------------------------------------- progress

    @Test
    fun `进度收敛在 0 到 1`() {
        assertEquals(0.0, CourseStatusCalculator.progress(day, at(9, 0), START, END), 1e-9)
        assertEquals(0.0, CourseStatusCalculator.progress(day, at(10, 0), START, END), 1e-9)
        assertEquals(0.5, CourseStatusCalculator.progress(day, at(10, 30), START, END), 1e-9)
        assertEquals(1.0, CourseStatusCalculator.progress(day, at(11, 0), START, END), 1e-9)
        assertEquals(1.0, CourseStatusCalculator.progress(day, at(11, 30), START, END), 1e-9)
        assertEquals(
            10.0 / 60.0,
            CourseStatusCalculator.progress(day, at(10, 10), START, END),
            1e-9,
        )
    }

    @Test
    fun `进度不可用情形返回 0`() {
        assertEquals(0.0, CourseStatusCalculator.progress(day.plusDays(1), at(10, 30), START, END), 1e-9)
        assertEquals(0.0, CourseStatusCalculator.progress(day, at(10, 30), null, END), 1e-9)
        assertEquals(0.0, CourseStatusCalculator.progress(day, at(10, 30), 600, 600), 1e-9)
    }

    // ---------------------------------------------------------- label / highlight

    @Test
    fun `文案与徽标`() {
        assertEquals("未开始", CourseStatusCalculator.label(CourseStatus.NOT_STARTED))
        assertEquals("即将开始", CourseStatusCalculator.label(CourseStatus.UPCOMING))
        assertEquals("正在上课", CourseStatusCalculator.label(CourseStatus.IN_PROGRESS))
        assertEquals("已结束", CourseStatusCalculator.label(CourseStatus.FINISHED))

        assertTrue(CourseStatusCalculator.isHighlighted(CourseStatus.IN_PROGRESS))
        assertTrue(CourseStatusCalculator.isHighlighted(CourseStatus.UPCOMING))
        assertFalse(CourseStatusCalculator.isHighlighted(CourseStatus.NOT_STARTED))
        assertFalse(CourseStatusCalculator.isHighlighted(CourseStatus.FINISHED))
    }

    @Test
    fun `常量窗口为 30 分钟`() {
        assertEquals(30, CourseStatusCalculator.UPCOMING_WINDOW_MINUTES)
    }
}
