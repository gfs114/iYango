package com.gfs.iyango.feature.main.schedule

import com.gfs.iyango.core.model.*
import java.time.LocalDateTime
import org.junit.Test
import org.junit.Assert.*

class TermBrowseProjectionTest {
    @Test fun `unknown date stays hidden while real school weeks and pending times remain`() {
        val ready = projectBrowsedTerm(SchoolTimetableData(termValue="old", courses=listOf(
            SchoolCourseData(name="历史课程",endWeek=26,timeUnconfirmed=true))), 1,
            LocalDateTime.of(2026,11,1,8,0), 4) { _,_,_,_->emptyList() }
        assertFalse(ready.data.datesKnown)
        assertTrue(ready.data.readOnly)
        assertEquals(0,ready.data.currentWeek)
        assertEquals(26,ready.data.totalWeeks)
        assertTrue(ready.data.exams.isEmpty())
        assertTrue(ready.data.cells.first().blocks.first().course.timeUnconfirmed)
        assertFalse(ready.data.showsToday)
    }
    @Test fun `representative semester projection bounded local timing`() {
        val source = SchoolTimetableData(
            termValue = "history", totalWeeks = 30,
            courses = (0 until 84).map { index -> SchoolCourseData(
                name = "Course $index", dayOfWeek = index % 7 + 1,
                startSection = index % 11 + 1, endSection = index % 11 + 1,
                startWeek = 1, endWeek = 30,
            ) },
        )
        val now = LocalDateTime.of(2026, 11, 1, 8, 0)
        repeat(3) { projectBrowsedTerm(source, 1, now, 1) { _, _, _, _ -> emptyList() } }
        var rails = 0
        val elapsed = kotlin.system.measureNanoTime {
            repeat(6) {
                val result = projectBrowsedTerm(source, 1, now, 1) { _, _, _, _ -> rails++; emptyList() }
                assertEquals(30, result.weeks.size)
                assertTrue(result.weeks.all { it.dataBlocksForTest() == 84 })
            }
        }
        println("TERM_PROJECTION_LOCAL_30_WEEKS_84_COURSES meanMs=" + elapsed / 6_000_000.0 + " rails=" + rails)
    }
    private fun WeekScheduleData.dataBlocksForTest() = cells.sumOf { it.blocks.size }

}
