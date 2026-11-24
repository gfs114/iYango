package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.GradeRecord
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.TimeSlot
import com.gfs.iyango.core.model.WeekType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SemesterSummaryCalculatorTest {

    private val timeSlots = listOf(
        TimeSlot(section = 1, startTime = "08:00", endTime = "08:45"),
        TimeSlot(section = 2, startTime = "08:55", endTime = "09:40"),
        TimeSlot(section = 3, startTime = "10:00", endTime = "10:45"),
        TimeSlot(section = 4, startTime = "10:55", endTime = "11:40"),
        TimeSlot(section = 5, startTime = "14:00", endTime = "14:45"),
        TimeSlot(section = 6, startTime = "14:55", endTime = "15:40"),
        TimeSlot(section = 7, startTime = "16:00", endTime = "16:45"),
        TimeSlot(section = 8, startTime = "16:55", endTime = "17:40"),
    )

    @Test
    fun `extractBuilding extracts building name from common classroom formats`() {
        assertEquals("明理楼", SemesterSummaryCalculator.extractBuilding("@明理楼 A101"))
        assertEquals("信息楼", SemesterSummaryCalculator.extractBuilding("信息楼B301"))
        assertEquals("1号教学楼", SemesterSummaryCalculator.extractBuilding("1号教学楼 204"))
        assertEquals(
            "人工智能与大数据实验教学中心",
            SemesterSummaryCalculator.extractBuilding("人工智能与大数据实验教学中心 A302"),
        )
        assertEquals("东区第一公共教学楼", SemesterSummaryCalculator.extractBuilding("东区第一公共教学楼 A201"))
        assertEquals("田径场", SemesterSummaryCalculator.extractBuilding("田径场"))
        assertEquals("", SemesterSummaryCalculator.extractBuilding("  "))
    }

    @Test
    fun `calculate aggregates total sections, early eight days, full days, busiest weekday and grades`() {
        val semester = Semester(
            id = 1,
            name = "2026-2027-1",
            startDate = LocalDate.of(2026, 9, 7),
            totalWeeks = 4,
            isCurrent = true,
        )
        val courses = listOf(
            // 周一 1-2 节 全周 4 周，早八 + 明理楼
            Course(
                id = 1,
                name = "高等数学",
                classroom = "明理楼 A101",
                dayOfWeek = 1,
                startSection = 1,
                endSection = 2,
                startWeek = 1,
                endWeek = 4,
                weekType = WeekType.EVERY,
            ),
            // 周一 3-4, 5-6, 7-8 节（前 2 周）触发满课（当天 4 次课 8 节）
            Course(
                id = 2,
                name = "大学物理",
                classroom = "明理楼 B202",
                dayOfWeek = 1,
                startSection = 3,
                endSection = 4,
                startWeek = 1,
                endWeek = 2,
                weekType = WeekType.EVERY,
            ),
            Course(
                id = 3,
                name = "数据结构",
                classroom = "信息楼 301",
                dayOfWeek = 1,
                startSection = 5,
                endSection = 6,
                startWeek = 1,
                endWeek = 2,
                weekType = WeekType.EVERY,
            ),
            Course(
                id = 4,
                name = "大学英语",
                classroom = "外语楼 105",
                dayOfWeek = 1,
                startSection = 7,
                endSection = 8,
                startWeek = 1,
                endWeek = 2,
                weekType = WeekType.EVERY,
            ),
            // 周三 1-2 节 单周（第 1、3 周），早八 2 天
            Course(
                id = 5,
                name = "高等数学",
                classroom = "明理楼 A102",
                dayOfWeek = 3,
                startSection = 1,
                endSection = 2,
                startWeek = 1,
                endWeek = 4,
                weekType = WeekType.ODD,
            ),
            // 停用课程不应计入
            Course(
                id = 6,
                name = "已停用课程",
                classroom = "体育馆",
                dayOfWeek = 5,
                startSection = 1,
                endSection = 2,
                startWeek = 1,
                endWeek = 4,
                enabled = false,
            ),
        )
        val grades = listOf(
            GradeRecord(courseName = "高等数学", credit = 4.0, scoreValue = 90.0, scoreText = "90", gradePoint = 4.0),
            GradeRecord(courseName = "数据结构", credit = 3.0, scoreValue = 80.0, scoreText = "80", gradePoint = 3.0),
        )

        val report = SemesterSummaryCalculator.calculate(
            timetableName = "默认课表",
            semester = semester,
            courses = courses,
            timeSlots = timeSlots,
            gradeRecords = grades,
        )

        assertEquals("2026-2027-1", report.semesterTitle)
        assertEquals(4, report.totalWeeks)
        assertEquals(4, report.distinctCourseCount)
        assertEquals(12, report.totalSessions)
        assertEquals(24, report.totalSections)
        assertEquals(6, report.earlyEightDays) // 周一4天 + 周三2天
        assertEquals(2, report.fullClassDays)  // 第1、2周的周一
        assertEquals(4, report.maxSessionsInOneDay)
        assertEquals(1, report.busiestWeekdayIndex)
        assertEquals("周一", report.busiestWeekdayLabel)
        assertEquals("明理楼", report.topBuilding)
        assertEquals(8, report.topBuildingVisits)
        assertEquals(listOf(10, 0, 2, 0, 0, 0, 0), report.weekdaySessionCounts)
        assertEquals("高等数学", report.topCourses.first().name)
        assertEquals(12, report.topCourses.first().sections)
        assertEquals(7.0, report.earnedCredits!!, 1e-4)
        assertEquals((4.0 * 4.0 + 3.0 * 3.0) / 7.0, report.averageGpa!!, 1e-4)
    }

    @Test
    fun `calculate filters grades strictly for the selected semester`() {
        val semester = Semester(
            id = 1,
            name = "2026年秋",
            startDate = LocalDate.of(2026, 9, 7),
            totalWeeks = 4,
            isCurrent = true,
        )
        val multiSemesterGrades = listOf(
            GradeRecord(
                semester = "2026-2027-1",
                courseName = "数据结构",
                credit = 3.0,
                scoreValue = 90.0,
                scoreText = "90",
                gradePoint = 4.0,
            ),
            GradeRecord(
                semester = "2025-2026-2",
                courseName = "高等数学",
                credit = 4.0,
                scoreValue = 65.0,
                scoreText = "65",
                gradePoint = 1.5,
            ),
        )

        val currentReport = SemesterSummaryCalculator.calculate(
            timetableName = "默认课表",
            semester = semester,
            courses = emptyList(),
            timeSlots = timeSlots,
            gradeRecords = multiSemesterGrades,
            schoolTerm = "2026-2027-1",
        )
        assertEquals(1, currentReport.gradeCourseCount)
        assertEquals(3.0, currentReport.earnedCredits!!, 1e-4)
        assertEquals(4.0, currentReport.averageGpa!!, 1e-4)

        val otherTermReport = SemesterSummaryCalculator.calculate(
            timetableName = "2025-2026-2",
            semester = semester.copy(name = "2025-2026-2"),
            courses = emptyList(),
            timeSlots = timeSlots,
            gradeRecords = multiSemesterGrades,
            schoolTerm = "2025-2026-2",
        )
        assertEquals(1, otherTermReport.gradeCourseCount)
        assertEquals(4.0, otherTermReport.earnedCredits!!, 1e-4)
        assertEquals(1.5, otherTermReport.averageGpa!!, 1e-4)

        val missingTermReport = SemesterSummaryCalculator.calculate(
            timetableName = "2024-2025-1",
            semester = semester.copy(name = "2024-2025-1"),
            courses = emptyList(),
            timeSlots = timeSlots,
            gradeRecords = multiSemesterGrades,
            schoolTerm = "2024-2025-1",
        )
        assertEquals(0, missingTermReport.gradeCourseCount)
        assertNull(missingTermReport.earnedCredits)
        assertNull(missingTermReport.averageGpa)
    }

    @Test
    fun `calculate handles empty courses and grades without failing`() {
        val report = SemesterSummaryCalculator.calculate(
            timetableName = "空课表",
            semester = null,
            courses = emptyList(),
            timeSlots = emptyList(),
            gradeRecords = emptyList(),
        )
        assertEquals("空课表", report.semesterTitle)
        assertEquals(0, report.totalSessions)
        assertEquals(0, report.totalSections)
        assertEquals("暂无", report.busiestWeekdayLabel)
        assertEquals("暂无记录", report.topBuilding)
        assertNull(report.earnedCredits)
        assertNull(report.averageGpa)
    }
}
