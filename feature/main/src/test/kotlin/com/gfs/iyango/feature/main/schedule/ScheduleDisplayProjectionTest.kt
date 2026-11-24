package com.gfs.iyango.feature.main.schedule

import com.gfs.iyango.core.model.Course
import com.gfs.iyango.feature.main.schedule.WeekScheduleData.GridBlock
import com.gfs.iyango.feature.main.schedule.WeekScheduleData.GridCellGroup
import org.junit.Assert.*
import org.junit.Test

class ScheduleDisplayProjectionTest {
    private fun block(id: Long, start: Int, end: Int, ghost: Boolean = false) = GridBlock(
        Course(id = id, name = "C$id", dayOfWeek = 1, startSection = start, endSection = end),
        null, end - start + 1, ghost,
    )
    @Test fun `same start different lengths remain individually visible`() {
        val result = ScheduleDisplayProjection.project(listOf(GridCellGroup(1, 1, listOf(block(1, 1, 2), block(2, 1, 4)))), false, 7)
        assertEquals(2, result.size)
        assertEquals(listOf(2, 4), result.map { it.rowSpan })
    }
    @Test fun `ghost does not create a real conflict`() {
        val result = ScheduleDisplayProjection.project(listOf(GridCellGroup(1, 1, listOf(block(1, 1, 2), block(2, 1, 2, true)))), false, 7)
        assertEquals(1, result.first { !it.primary.isOffWeek }.conflictCount)
    }
    @Test fun `overlap across start sections uses separate lanes`() {
        val result = ScheduleDisplayProjection.project(listOf(
            GridCellGroup(1, 1, listOf(block(1, 1, 3))), GridCellGroup(1, 2, listOf(block(2, 2, 4)))), false, 7)
        assertEquals(listOf(2, 2), result.map { it.laneCount })
        assertEquals(setOf(0, 1), result.map { it.laneIndex }.toSet())
    }
    @Test fun `three adjacent segments merge while retaining every source block`() {
        val blocks = (1..3).map { section -> block(section.toLong(), section, section).let { it.copy(course = it.course.copy(name = "Same")) } }
        val result = ScheduleDisplayProjection.project(blocks.map { GridCellGroup(1, it.course.startSection, listOf(it)) }, true, 7)
        assertEquals(1, result.size)
        assertEquals(3, result.single().blocks.size)
        assertEquals(3, result.single().rowSpan)
    }
    @Test fun `official table has eleven rows while local table expands`() {
        assertEquals(11, ScheduleGridMath.sectionCountFor("YGU", 12, 12))
        assertEquals(16, ScheduleGridMath.sectionCountFor("LOCAL", 16, 14))
        assertEquals(20, ScheduleGridMath.sectionCountFor("LOCAL", 30, 12))
    }
    @Test fun `custom times inside known section get proportional rows but break times retain sections`() {
        val base = block(1, 3, 4)
        val date = java.time.LocalDate.of(2026, 10, 5)
        fun timed(start: Int, end: Int) = base.copy(occurrence = com.gfs.iyango.core.domain.CourseOccurrence("A", base.course, date, start, end, false))
        val slots = listOf(com.gfs.iyango.core.model.TimeSlot(section = 1, startTime = "08:00", endTime = "09:00"))
        val mapped = ScheduleDisplayProjection.project(listOf(GridCellGroup(1, 3, listOf(timed(495, 525)))), false, 7, slots).single()
        assertEquals(0.25f, mapped.topRow, 0.001f)
        assertEquals(0.5f, mapped.heightRows, 0.001f)
        val fallback = ScheduleDisplayProjection.project(listOf(GridCellGroup(1, 3, listOf(timed(600, 660)))), false, 7, slots).single()
        assertEquals(2f, fallback.topRow, 0.001f)
        assertEquals(2f, fallback.heightRows, 0.001f)
    }

}
