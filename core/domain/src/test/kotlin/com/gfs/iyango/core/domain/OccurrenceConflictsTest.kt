package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.Course
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class OccurrenceConflictsTest {
    @Test fun `exclude original anchor only and retain another occurrence of same course`() {
        val date = LocalDate.of(2026, 10, 5)
        val course = Course(id = 1, name = "A", dayOfWeek = 1, startSection = 1, endSection = 2, startTime = "08:00", endTime = "09:00")
        val a = CourseOccurrence("anchorA", course, date, 480, 540, false, date.minusWeeks(1), true)
        val b = a.copy(occurrenceId = "anchorB", anchorDate = date.minusWeeks(2))
        assertEquals(listOf(b), OccurrenceConflicts.find(course, emptyList(), listOf(a, b), a.occurrenceId))
    }
    @Test fun `actual clock interval has priority over equal sections`() {
        val course = Course(id = 1, name = "A", startSection = 1, endSection = 2, startTime = "08:00", endTime = "09:00")
        val occurrence = CourseOccurrence("B", course, LocalDate.of(2026, 10, 5), 540, 600, false)
        assertTrue(OccurrenceConflicts.find(course, emptyList(), listOf(occurrence), null).isEmpty())
        assertEquals(1, OccurrenceConflicts.find(course, emptyList(), listOf(occurrence.copy(startMinute = 539)), null).size)
    }
    @Test fun `target owner and temporary coverage must permit source table write`() {
        val date = LocalDate.of(2026, 10, 5)
        val table = com.gfs.iyango.core.model.Timetable(id = 1)
        val semester = com.gfs.iyango.core.model.Semester(startDate = date)
        val day = EffectiveScheduleDay(date, table, semester)
        assertNull(AdjustmentTargetPolicy.warning(day, 1))
        assertNotNull(AdjustmentTargetPolicy.warning(day, 2))
        assertNotNull(AdjustmentTargetPolicy.warning(day.copy(isTemporaryDay = true), 1))
        assertNotNull(AdjustmentTargetPolicy.warning(day.copy(timetable = table.copy(autoEnabled = true, enabledDays = listOf(2))), 1))
    }

}
