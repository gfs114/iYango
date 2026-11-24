package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.*
import java.time.LocalDate
import org.junit.Test
import org.junit.Assert.*

class SectionMoveTest {
    private val source = LocalDate.of(2026, 10, 5)
    private val course = Course(id = 1, timetableId = 1, name = "School", dayOfWeek = 1,
        startSection = 1, endSection = 2, startTime = "08:00", endTime = "09:40")
    private val slots = listOf(TimeSlot(section = 5, startTime = "14:00", endTime = "14:45"),
        TimeSlot(section = 6, startTime = "14:55", endTime = "15:40"),
        TimeSlot(section = 7, startTime = "16:00", endTime = "16:45"))
    private fun moved(start: Int, end: Int, clockStart: String = "", clockEnd: String = "", unknown: Boolean = false): CourseOccurrence {
        val move = MoveOccurrence.create(1, 1, source.toString(), source.plusDays(1), start, end, clockStart, clockEnd)
        return ApplyOccurrenceAdjustments.apply(emptyList(), listOf(course.copy(timeUnconfirmed = unknown)), slots,
            listOf(move), emptyList(), emptyMap(), source..source.plusDays(7)).single()
    }
    @Test fun `section move clears old official clock including second move of same anchor`() {
        val first = moved(5, 6)
        val second = moved(7, 7)
        assertEquals(840, first.startMinute)
        assertEquals(940, first.endMinute)
        assertEquals(960, second.startMinute)
        assertEquals(first.occurrenceId, second.occurrenceId)
        assertEquals(source, second.anchorDate)
    }
    @Test fun `explicit target clock wins and unconfirmed official times remain unknown`() {
        assertEquals(900, moved(5, 6, "15:00", "16:00").startMinute)
        assertNull(moved(5, 6, unknown = true).startMinute)
    }
}
