package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.Course
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class TodayCourseTimingTest {
    private val date = LocalDate.of(2026, 11, 13)
    private fun occurrence(id: String, start: Int?, end: Int?, day: LocalDate = date) = CourseOccurrence(
        occurrenceId = id, course = Course(name = id), date = day, startMinute = start, endMinute = end, isOverride = false)
    @Test fun `start is included and exact end switches to adjacent class`() {
        val rows = listOf(occurrence("a", 540, 600), occurrence("b", 600, 660))
        val start = TodayCourseTiming.project(rows, date.atTime(9, 0))
        assertEquals("a", start.current?.occurrenceId)
        assertEquals(3600L, start.remainingSeconds)
        assertEquals(0.0, start.progress, 0.0)
        val boundary = TodayCourseTiming.project(rows, date.atTime(10, 0))
        assertEquals("b", boundary.current?.occurrenceId)
        assertNull(boundary.next)
    }
    @Test fun `overlaps use latest start then stable id and next really starts in future`() {
        val timing = TodayCourseTiming.project(listOf(occurrence("early", 480, 660), occurrence("b", 540, 630),
            occurrence("a", 540, 600), occurrence("future", 640, 680)), date.atTime(9, 30))
        assertEquals("a", timing.current?.occurrenceId)
        assertEquals("future", timing.next?.occurrenceId)
        assertEquals(3, timing.overlappingCount)
        assertEquals(0.5, timing.progress, 0.0)
    }
    @Test fun `unknown and invalid clocks do not obscure known class or fabricate countdown`() {
        val timing = TodayCourseTiming.project(listOf(occurrence("unknown", null, null), occurrence("bad", 650, 600),
            occurrence("known", 540, 600)), date.atTime(9, 59, 59))
        assertEquals("known", timing.current?.occurrenceId)
        assertEquals(1L, timing.remainingSeconds)
        assertEquals(2, timing.unknownTimeCount)
    }
    @Test fun `actual instance date and seven natural days bound future lookup`() {
        val timing = TodayCourseTiming.project(listOf(occurrence("yesterday", 540, 600, date.minusDays(1)),
            occurrence("outside", 540, 600, date.plusDays(7)), occurrence("last", 540, 600, date.plusDays(6))), date.atTime(10, 0))
        assertNull(timing.current)
        assertEquals("last", timing.next?.occurrenceId)
        assertNull(TodayCourseTiming.project(listOf(occurrence("outside", 540, 600, date.plusDays(7))), date.atTime(10, 0)).next)
    }
}
