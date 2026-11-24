package com.gfs.iyango.feature.main.schedule

import com.gfs.iyango.core.domain.*
import com.gfs.iyango.core.model.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ScheduleInteractionTest {
    private val date = LocalDate.of(2026, 10, 5)
    private val axis = ScheduleAxisGeometry(11, 50f, 4)
    @Test fun `drop excludes rail outside lunch hidden days and overflowing span`() {
        assertNull(scheduleDropAt(39f, 20f, 40f, 60f, 5, axis, 2))
        assertNull(scheduleDropAt(340f, 20f, 40f, 60f, 5, axis, 2))
        assertNull(scheduleDropAt(41f, -1f, 40f, 60f, 5, axis, 2))
        assertNull(scheduleDropAt(41f, 205f, 40f, 60f, 5, axis, 2))
        assertNull(scheduleDropAt(41f, 550f, 40f, 60f, 5, axis, 2))
        assertEquals(ScheduleDropSlot(2, 5, 6), scheduleDropAt(101f, 219f, 40f, 60f, 5, axis, 2))
    }
    @Test fun `wide sidebar requires sufficient remaining grid width`() {
        assertFalse(showWeekSidebar(600f, 7))
        assertTrue(showWeekSidebar(850f, 7))
        assertFalse(showWeekSidebar(500f, 5))
    }
    @Test fun `restore only selects anchors visible through actual selected owner on either date`() {
        val monA = EffectiveScheduleDay(date, Timetable(id = 1))
        val tueB = EffectiveScheduleDay(date.plusDays(1), Timetable(id = 2))
        val hidden = CancelOccurrence.create(2, 1, date.toString())
        val visible = MoveOccurrence.create(2, 2, date.minusWeeks(1).toString(), date.plusDays(1), 1, 2)
        val outside = CancelOccurrence.create(2, 3, date.plusWeeks(1).toString())
        assertEquals(listOf(visible), adjustmentsToRestore(listOf(hidden, visible, outside), listOf(monA, tueB)))
    }
    @Test fun `capture preserves moved occurrence anchor and excludes ghosts and temporary instances`() {
        val c = Course(id = 2, timetableId = 9, startSection = 3, endSection = 4)
        val o = CourseOccurrence("instance", c, date, 600, 690, false, date.minusWeeks(1), isMoved = true)
        val d = EffectiveScheduleDay(date, Timetable(id = 9), occurrences = listOf(o), baseCourses = listOf(c))
        val b = WeekScheduleData.GridBlock(c, o, 2, false)
        assertEquals(date.minusWeeks(1), captureDragExpected(b, listOf(d))!!.occurrence.anchorDate)
        assertNull(captureDragExpected(b.copy(occurrence = null, isOffWeek = true), listOf(d)))
        assertNull(captureDragExpected(b.copy(occurrence = o.copy(isTemporary = true)), listOf(d)))
    }
    @Test fun `official course with clocks remains draggable when source bells prove exact sections`() {
        val slots = listOf(TimeSlot(section=1,startTime="08:00",endTime="08:45"),
            TimeSlot(section=2,startTime="09:00",endTime="09:45"))
        val c = Course(id=1,timetableId=1,startSection=1,endSection=2,startTime="08:00",endTime="09:45")
        val o = CourseOccurrence("YGU",c,date,480,585,false)
        val d = EffectiveScheduleDay(date,Timetable(id=1,source="YGU"),occurrences=listOf(o),timeSlots=slots,baseCourses=listOf(c))
        assertEquals(2,dragSectionSpan(WeekScheduleData.GridBlock(c,o,2,false),listOf(d)))
        val custom = c.copy(timeMode=1,endSection=1,endTime="10:00")
        val customOccurrence = o.copy(course=custom,endMinute=600)
        val customDay = d.copy(occurrences=listOf(customOccurrence),baseCourses=listOf(custom))
        assertNull(dragSectionSpan(WeekScheduleData.GridBlock(custom,customOccurrence,1,false),listOf(customDay)))
        assertNull(dragSectionSpan(WeekScheduleData.GridBlock(c,o,2,false),listOf(d.copy(displayTimeSlots=emptyList()))))
    }
}
