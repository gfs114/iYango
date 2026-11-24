package com.gfs.iyango.feature.main.today

import com.gfs.iyango.core.domain.*
import com.gfs.iyango.core.model.*
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class TodayDetailContextTest {
    @Test fun `future or temporary day uses its actual semester and display bells for details and adjustment range`() {
        val date = LocalDate.of(2026, 11, 13)
        val ready = TodayUiState.Ready(Timetable(), null, 0, date, emptyList(), date.plusDays(1), emptyList(),
            emptySet(), YearMonth.from(date), listOf(TimeSlot(section = 1)), emptyList())
        val future = date.plusDays(3)
        val semester = Semester(startDate = date.minusWeeks(4))
        val displaySlots = (1..13).map { TimeSlot(section = it) }
        val context = todayDetailContext(EffectiveScheduleDay(future, semester = semester, timeSlots = emptyList(),
            displayTimeSlots = displaySlots, isTemporaryDay = true), ready, Course(endSection = 2))
        assertEquals(displaySlots, context.timeSlots)
        assertEquals(13, context.sectionCount)
        assertEquals(semester, context.semester)
        assertEquals(CourseRules.weekOfDate(semester, future), context.week)
    }
}
