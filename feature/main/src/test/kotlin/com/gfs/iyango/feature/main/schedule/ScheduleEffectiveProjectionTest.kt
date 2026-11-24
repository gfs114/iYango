package com.gfs.iyango.feature.main.schedule

import com.gfs.iyango.core.domain.*
import com.gfs.iyango.core.model.*
import java.time.LocalDate
import org.junit.Test
import org.junit.Assert.*

class ScheduleEffectiveProjectionTest {
    private val date = LocalDate.of(2026, 10, 5)
    private val semester = Semester(startDate = date, totalWeeks = 4)
    private val course = Course(id = 2, timetableId = 2, name = "Even", dayOfWeek = 1, startSection = 1, endSection = 2,
        startWeek = 1, endWeek = 4, weekType = WeekType.EVEN)
    private val day = EffectiveScheduleDay(date, Timetable(id = 2, autoEnabled = true), semester, baseCourses = listOf(course))
    @Test fun `ghost follows each selected table and skips unmatched or temporary days`() {
        assertEquals(listOf(course), ScheduleEffectiveProjection.offWeekCourses(listOf(day)))
        assertTrue(ScheduleEffectiveProjection.offWeekCourses(listOf(day.copy(isTemporaryDay = true))).isEmpty())
        assertTrue(ScheduleEffectiveProjection.offWeekCourses(listOf(day.copy(timetable = day.timetable!!.copy(enabledDays = listOf(2))))).isEmpty())
    }
    @Test fun `density counts final effective instances including moved anchors`() {
        val occurrence = CourseOccurrence("A", course, date.plusWeeks(1), null, null, false, date, true)
        val days = listOf(day, day.copy(date = date.plusWeeks(1), occurrences = listOf(occurrence, occurrence.copy(occurrenceId = "B"))))
        val counts = ScheduleEffectiveProjection.weekDayCounts(days, date, 4)
        assertEquals(0, counts[0][0])
        assertEquals(2, counts[1][0])
    }
    @Test fun `official root does not crop selected local day past eleven`() {
        val table=Timetable(id=1,source="YGU")
        val local=day.copy(baseCourses=emptyList(), occurrences=listOf(CourseOccurrence("local",course.copy(startSection=14,endSection=15),date,840,885,false)))
        val data=buildScheduleWeekData(table,semester,emptyList(),emptyList(),{_,_,_,_->emptyList()},WeekFlipDirection.JUMP,date.atTime(8,0),listOf(local),1)
        assertEquals(15,data.sectionCount)
    }
    @Test fun `projection uses day specific bells for actual clock interval`() {
        val first=listOf(TimeSlot(section=1,startTime="08:00",endTime="09:00"))
        val second=listOf(TimeSlot(section=1,startTime="10:00",endTime="11:00"))
        val c=course.copy(startSection=1,endSection=1)
        val occurrence=CourseOccurrence("day",c,date,630,660,false)
        val cell=WeekScheduleData.GridCellGroup(1,1,listOf(WeekScheduleData.GridBlock(c,occurrence,1,false)))
        val result=ScheduleDisplayProjection.project(listOf(cell),false,7,first) { second }
        assertEquals(0.5f,result.single().topRow,0.001f)
        assertEquals(0.5f,result.single().heightRows,0.001f)
    }

    private fun temporaryLayout(source: String, withCourse: Boolean): WeekScheduleData {
        val owner = Timetable(id=1,source="YGU")
        val temporary = EffectiveScheduleDay(date,owner,semester,
            occurrences=if(withCourse) listOf(CourseOccurrence("temporary",course.copy(startSection=14,endSection=14),date,null,null,false,isTemporary=true)) else emptyList(),
            isTemporaryDay=true, timeSlots=listOf(TimeSlot(section=1)),
            displayTimeSlots=listOf(TimeSlot(section=14)), displaySource=source)
        return buildScheduleWeekData(owner,semester,emptyList(),emptyList(),{_,_,_,_->emptyList()},
            WeekFlipDirection.JUMP,date.atTime(8,0),listOf(temporary),1)
    }
    @Test fun `official owner uses temporary local source section fourteen`() {
        assertEquals(14,temporaryLayout("LOCAL",true).sectionCount)
    }
    @Test fun `official temporary source keeps official eleven section layout`() {
        assertEquals(11,temporaryLayout("YGU",true).sectionCount)
    }
    @Test fun `empty temporary local source still expands to display slots`() {
        assertEquals(14,temporaryLayout("LOCAL",false).sectionCount)
    }

    @Test fun `sectionToPeriodRow maps sections 1 to 12 into 5 big periods`() {
        assertEquals(0, ScheduleEffectiveProjection.sectionToPeriodRow(0))
        assertEquals(0, ScheduleEffectiveProjection.sectionToPeriodRow(1))
        assertEquals(0, ScheduleEffectiveProjection.sectionToPeriodRow(2))
        assertEquals(1, ScheduleEffectiveProjection.sectionToPeriodRow(3))
        assertEquals(1, ScheduleEffectiveProjection.sectionToPeriodRow(4))
        assertEquals(2, ScheduleEffectiveProjection.sectionToPeriodRow(5))
        assertEquals(2, ScheduleEffectiveProjection.sectionToPeriodRow(6))
        assertEquals(3, ScheduleEffectiveProjection.sectionToPeriodRow(7))
        assertEquals(3, ScheduleEffectiveProjection.sectionToPeriodRow(8))
        assertEquals(4, ScheduleEffectiveProjection.sectionToPeriodRow(9))
        assertEquals(4, ScheduleEffectiveProjection.sectionToPeriodRow(10))
        assertEquals(4, ScheduleEffectiveProjection.sectionToPeriodRow(12))
    }

    @Test fun `weekSlotMasks builds 5-bit period mask including multi-period spanning courses`() {
        val singleCourse = course.copy(startSection = 1, endSection = 2)
        val spanningCourse = course.copy(id = 3, startSection = 2, endSection = 5)
        val monWeek1 = day.copy(
            date = date,
            occurrences = listOf(CourseOccurrence("S1", singleCourse, date, null, null, false)),
        )
        val wedWeek2Date = date.plusWeeks(1).plusDays(2)
        val wedWeek2 = day.copy(
            date = wedWeek2Date,
            occurrences = listOf(CourseOccurrence("S2", spanningCourse, wedWeek2Date, null, null, false)),
        )
        val masks = ScheduleEffectiveProjection.weekSlotMasks(listOf(monWeek1, wedWeek2), date, 4)
        assertEquals(4, masks.size)
        assertEquals(1 shl 0, masks[0][0])
        assertEquals(0, masks[0][1])
        assertEquals((1 shl 0) or (1 shl 1) or (1 shl 2), masks[1][2])
        assertEquals(0, masks[2][0])
    }

}
