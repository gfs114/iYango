package com.gfs.iyango.feature.main.coursemanage

import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.WeekType
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class CourseManageModelsTest {
    private val base = Course(id = 1, timetableId = 1, name = "数学", groupKey = "math", endWeek = 16)

    @Test fun `same course combines all arrangements despite teacher room week or grouping differences`() {
        val groups = managedCourses(listOf(base, base.copy(id = 2), base.copy(id = 3, groupKey = "other"),
            base.copy(id = 4, groupKey = "", teacher = "另一位教师", classroom = "实验室"),
            base.copy(id = 5, groupKey = "", startWeek = 8), base.copy(id = 6, timetableId = 2),
            base.copy(id = 7, overrideOfId = 1, overrideWeek = 1)))
        assertEquals(2, groups.size)
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L), groups.first { it.id == 1L }.ids)
    }

    @Test fun `a separately renamed arrangement remains its own course`() {
        assertEquals(2, managedCourses(listOf(base, base.copy(id = 2, name = "数学实验"))).size)
    }

    @Test fun `all filter predicates must match the same arrangement`() {
        val group = ManagedCourse(listOf(base.copy(teacher = "张老师", dayOfWeek = 1), base.copy(id = 2, teacher = "李老师", dayOfWeek = 3)))
        assertFalse(group.matches("张老师", 0, 3, null))
        assertTrue(group.matches("李老师", 0, 3, null))
        assertEquals(2, group.arrangements.size)
    }

    @Test fun `single date uses actual weekday and semester week`() {
        val group = ManagedCourse(listOf(base.copy(timeMode = 2, date = "2026-09-09", dayOfWeek = 1)))
        val semester = Semester(timetableId = 1, startDate = LocalDate.of(2026, 9, 7), totalWeeks = 16)
        assertTrue(group.matches("", 1, 3, semester))
        assertFalse(group.matches("", 2, 3, semester))
        assertFalse(group.matches("", 1, 1, semester))
    }

    @Test fun `disabled courses remain manageable but parity and cycles apply`() {
        val group = ManagedCourse(listOf(base.copy(enabled = false, weekType = WeekType.EVEN)))
        assertTrue(group.matches("", 2, 0, null))
        assertFalse(group.matches("", 1, 0, null))
    }

    @Test fun `labels distinguish clock time single dates and week rules`() {
        assertEquals("周一 09:00–10:00", arrangementTime(base.copy(timeMode = 1, startTime = "09:00", endTime = "10:00")))
        assertEquals("仅此一天", arrangementWeeks(base.copy(timeMode = 2)))
        assertEquals("第 2、4、8 周 双周 每 2 周的第 2 周",
            arrangementWeeks(base.copy(selectedWeeks = listOf(8, 2, 4), weekType = WeekType.EVEN, cycleLength = 2, cycleWeek = 2)))
    }
}
