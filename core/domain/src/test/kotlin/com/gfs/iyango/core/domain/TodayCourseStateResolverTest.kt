package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.Course
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TodayCourseStateResolver] 纯函数测试：无课、课前、课间、课中、结束后与
 * 时间待定/停用课程被忽略的口径。
 */
class TodayCourseStateResolverTest {

    private companion object {
        val DATE: LocalDate = LocalDate.of(2026, 10, 26)
    }

    private fun occurrence(
        id: String,
        startMinute: Int?,
        endMinute: Int? = startMinute?.plus(45),
        enabled: Boolean = true,
    ): CourseOccurrence = CourseOccurrence(
        occurrenceId = id,
        course = Course(name = "课程$id", enabled = enabled),
        date = DATE,
        startMinute = startMinute,
        endMinute = endMinute,
        isOverride = false,
    )

    private fun nowAt(hour: Int, minute: Int = 0): LocalDateTime =
        LocalDateTime.of(DATE, LocalTime.of(hour, minute))

    @Test
    fun `当天没有时间确定的课程时返回无课`() {
        assertEquals(TodayCourseState.NoClass, TodayCourseStateResolver.resolve(emptyList(), nowAt(10)))
        assertEquals(
            TodayCourseState.NoClass,
            TodayCourseStateResolver.resolve(listOf(occurrence("a", null)), nowAt(10)),
        )
    }

    @Test
    fun `时间待定与停用的课程被忽略只统计有效节次`() {
        val state = TodayCourseStateResolver.resolve(
            listOf(
                occurrence("a", null),
                occurrence("b", 480, 555, enabled = false),
                occurrence("c", 540),
                occurrence("d", 660),
            ),
            nowAt(10),
        )
        assertEquals(2, (state as TodayCourseState.Upcoming).total)
    }

    @Test
    fun `课前返回即将开始与节次上下文`() {
        val state = TodayCourseStateResolver.resolve(
            listOf(occurrence("a", 540), occurrence("b", 660)),
            nowAt(8, 30),
        ) as TodayCourseState.Upcoming
        assertEquals("a", state.next.occurrenceId)
        assertEquals(1, state.index)
        assertEquals(2, state.total)
        assertEquals(LocalDateTime.of(DATE, LocalTime.of(9, 0)), state.startAt)
    }

    @Test
    fun `课间返回下一节`() {
        val state = TodayCourseStateResolver.resolve(
            listOf(occurrence("a", 480, 525), occurrence("b", 540)),
            nowAt(8, 50),
        ) as TodayCourseState.Upcoming
        assertEquals("b", state.next.occurrenceId)
        assertEquals(2, state.index)
        assertEquals(2, state.total)
        assertEquals(LocalDateTime.of(DATE, LocalTime.of(9, 0)), state.startAt)
    }

    @Test
    fun `课中返回当前节与下一节预告`() {
        val state = TodayCourseStateResolver.resolve(
            listOf(occurrence("a", 480, 525), occurrence("b", 540, 585)),
            nowAt(8, 10),
        ) as TodayCourseState.InClass
        assertEquals("a", state.current.occurrenceId)
        assertEquals("b", state.next?.occurrenceId)
        assertEquals(1, state.index)
        assertEquals(2, state.total)
        assertEquals(LocalDateTime.of(DATE, LocalTime.of(8, 45)), state.endAt)
    }

    @Test
    fun `最后一节结束后返回已结束`() {
        val state = TodayCourseStateResolver.resolve(
            listOf(occurrence("a", 480, 525), occurrence("b", 540, 585)),
            nowAt(10),
        )
        assertEquals(TodayCourseState.Finished(2), state)
    }

    @Test
    fun `已开始但无结束时刻的课不阻塞已结束判定`() {
        // 锁定口径：无有效结束时刻的课不参与课中匹配（与提醒渲染退场策略一致）。
        val state = TodayCourseStateResolver.resolve(
            listOf(occurrence("a", 480, endMinute = null), occurrence("b", 540, 585)),
            nowAt(10),
        )
        assertEquals(TodayCourseState.Finished(2), state)
    }

    @Test
    fun `结束不晚于开始的脏数据按无效结束处理`() {
        val state = TodayCourseStateResolver.resolve(
            listOf(occurrence("a", 480, 450), occurrence("b", 540, 585)),
            nowAt(8, 10),
        ) as TodayCourseState.Upcoming
        assertEquals("b", state.next.occurrenceId)
        // 脏数据不计入课中匹配，但仍计入当天节次总数。
        assertEquals(2, state.total)
    }
}
