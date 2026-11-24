package com.gfs.iyango.platform.android
import com.gfs.iyango.core.domain.CourseOccurrence
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.Exam
import com.gfs.iyango.core.model.ExamStatus
import com.gfs.iyango.core.model.Semester
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
class WidgetPresentationTest {
    private val today = LocalDate.of(2026, 11, 5)
    @Test fun capacityRespectsActualWrappedHeightAndTwelveSlots() {
        assertEquals(2, WidgetPresentation.capacity(45, listOf(20, 25, 20)))
        assertEquals(0, WidgetPresentation.capacity(19, listOf(20)))
        assertEquals(12, WidgetPresentation.capacity(1000, List(20) { 20 }))
    }
    @Test fun datesRestoreOnlyOnSameAnchorAndStayWithinWeek() {
        assertEquals(today.plusDays(3), WidgetPresentation.selectedDate(today, today.plusDays(3).toEpochDay(), today.toEpochDay()))
        assertEquals(today, WidgetPresentation.selectedDate(today.plusDays(1), today.toEpochDay(), today.toEpochDay()).minusDays(1))
        assertEquals(today.plusDays(7), WidgetPresentation.navigate(today, today.plusDays(7), 1))
        assertEquals(today.minusDays(7), WidgetPresentation.navigate(today, today.minusDays(7), -1))
        assertEquals(today, WidgetPresentation.selectedDate(today, Long.MAX_VALUE, today.toEpochDay()))
    }

    /** 文档 4.2：宽度格子数换算（110→1、180→2、250→3、300→4）。 */
    @Test fun resolveCellSpanFollowsWakeUpWidthFormula() {
        assertEquals(1, WidgetPresentation.resolveCellSpan(110))
        assertEquals(1, WidgetPresentation.resolveCellSpan(100))
        assertEquals(2, WidgetPresentation.resolveCellSpan(180))
        assertEquals(2, WidgetPresentation.resolveCellSpan(150))
        assertEquals(3, WidgetPresentation.resolveCellSpan(250))
        assertEquals(3, WidgetPresentation.resolveCellSpan(200))
        assertEquals(4, WidgetPresentation.resolveCellSpan(300))
        assertEquals(4, WidgetPresentation.resolveCellSpan(260))
    }

    /** 文档 6.2：学期状态与空视图文案对照表。 */
    @Test fun semesterTextsFollowWakeUpCopyTable() {
        // 尚未开学：倒计时文案
        val notStarted = Semester(id = 1, timetableId = 1, name = "2026春", startDate = LocalDate.of(2026, 12, 1), totalWeeks = 18)
        val state = WidgetPresentation.semesterState(notStarted, today, today)
        assertTrue(state.notStarted)
        assertEquals("距离开学还有 26 天", WidgetPresentation.emptyMessage(state, today, today, modern = false, weekView = false))
        assertEquals("距离开学\n还有 26 天", WidgetPresentation.emptyMessage(state, today, today, modern = true, weekView = false))

        // 学期内：第X周 与 今日/明日无课文案
        val inTerm = Semester(id = 1, timetableId = 1, name = "2026秋", startDate = LocalDate.of(2026, 10, 5), totalWeeks = 18)
        val weekState = WidgetPresentation.semesterState(inTerm, today, today)
        assertEquals(5, weekState.currentWeek)
        assertEquals("今天没有课哦", WidgetPresentation.emptyMessage(weekState, today, today, modern = false, weekView = false))
        assertEquals("(〃'▽'〃)\n今天没有课啦", WidgetPresentation.emptyMessage(weekState, today, today, modern = true, weekView = false))
        assertEquals("明天没有课哦", WidgetPresentation.emptyMessage(weekState, today.plusDays(1), today, modern = false, weekView = false))
        assertEquals("(〃'▽'〃)\n明天没有课啦", WidgetPresentation.emptyMessage(weekState, today.plusDays(1), today, modern = true, weekView = false))
        // 周视图：本周/下周无课
        assertEquals("本周没有课哦", WidgetPresentation.emptyMessage(weekState, today, today, modern = false, weekView = true))
        assertEquals("下周没有课哦", WidgetPresentation.emptyMessage(weekState, today.plusDays(7), today, modern = false, weekView = true))

        // 学期已结束
        val ended = Semester(id = 1, timetableId = 1, name = "2025春", startDate = LocalDate.of(2026, 1, 5), totalWeeks = 4)
        val endedState = WidgetPresentation.semesterState(ended, today, today)
        assertTrue(endedState.ended)
        assertEquals("学期已经结束啦", WidgetPresentation.emptyMessage(endedState, today, today, modern = false, weekView = false))
        assertEquals("(〃'▽'〃)\n学期已经结束啦", WidgetPresentation.emptyMessage(endedState, today, today, modern = true, weekView = false))

        // 今日有课但已全部结束（区别于今日原本无课）
        assertEquals(
            "(〃'▽'〃)\n今日课程已结束",
            WidgetPresentation.emptyMessage(weekState, today, today, modern = true, weekView = false, allCoursesEndedToday = true),
        )
        assertEquals(
            "今日课程已结束",
            WidgetPresentation.emptyMessage(weekState, today, today, modern = false, weekView = false, allCoursesEndedToday = true),
        )
    }

    @Test fun headerSubtitleAndDualColumnTitlesAndTeacherCompactionFollowReferenceDesign() {
        val inTerm = Semester(id = 1, timetableId = 1, name = "2026秋", startDate = LocalDate.of(2026, 10, 5), totalWeeks = 18)
        val state = WidgetPresentation.semesterState(inTerm, today, today)
        assertEquals(" · 第5周 · 今日3节", WidgetPresentation.headerSubtitle(state, today, today, remainingCount = 3, totalCount = 3, weekView = false))
        assertEquals(" · 第5周 · 剩2节", WidgetPresentation.headerSubtitle(state, today, today, remainingCount = 2, totalCount = 3, weekView = false))
        assertEquals(" · 第5周 · 今日已结课", WidgetPresentation.headerSubtitle(state, today, today, remainingCount = 0, totalCount = 3, weekView = false))
        assertEquals(" · 第5周", WidgetPresentation.headerSubtitle(state, today, today, remainingCount = 2, totalCount = 3, weekView = true))

        assertEquals("今天 11.05 周四", WidgetPresentation.columnHeaderTitle(today, today))
        assertEquals("明天 11.06 周五", WidgetPresentation.columnHeaderTitle(today.plusDays(1), today))
        assertEquals("11.08 周日", WidgetPresentation.columnHeaderTitle(today.plusDays(3), today))

        val item = CourseOccurrence(
            "c1",
            Course(id = 1, name = "高数", startSection = 1, endSection = 2),
            today,
            startMinute = 8 * 60,
            endMinute = 9 * 60 + 40,
            isOverride = false,
        )
        assertEquals("1-2节", WidgetPresentation.courseStatusBadge(item, today, nowMinute = 8 * 60 + 30))
        assertEquals("上课中", WidgetPresentation.courseStatusBadge(item, today, nowMinute = 8 * 60 + 30, includeLiveStatus = true))

        assertEquals("张老师", WidgetPresentation.compactTeachers("张老师"))
        assertEquals("张老师、李老师", WidgetPresentation.compactTeachers("张老师, 李老师"))
        assertEquals("张老师、李老师等3人", WidgetPresentation.compactTeachers("张老师、李老师、王老师"))
    }

    @Test fun resolveNextClassPillCoversInClassUpcomingTomorrowAndEmpty() {
        val ongoing = CourseOccurrence(
            "c1",
            Course(id = 1, name = "高等数学", teacher = "张老师", classroom = "明理楼 A101", color = "#3B82F6", startSection = 1, endSection = 2),
            today,
            startMinute = 8 * 60,
            endMinute = 9 * 60 + 40,
            isOverride = false,
        )
        val upcoming = CourseOccurrence(
            "c2",
            Course(id = 2, name = "数据结构", teacher = "李老师", classroom = "信息楼 B301", color = "#10B981", startSection = 3, endSection = 4),
            today,
            startMinute = 10 * 60,
            endMinute = 11 * 60 + 40,
            isOverride = false,
        )
        val inClassState = WidgetPresentation.resolveNextClassPill(
            todayOccurrences = listOf(ongoing, upcoming),
            tomorrowOccurrences = emptyList(),
            now = today.atTime(9, 10),
        )
        assertTrue(inClassState.isInProgress)
        assertEquals("高等数学", inClassState.courseName)
        assertEquals("上课中", inClassState.badgeText)
        assertTrue(inClassState.subtitle.contains("距下课 30 分钟"))

        val upcomingState = WidgetPresentation.resolveNextClassPill(
            todayOccurrences = listOf(ongoing, upcoming),
            tomorrowOccurrences = emptyList(),
            now = today.atTime(9, 45),
        )
        assertFalse(upcomingState.isInProgress)
        assertEquals("数据结构", upcomingState.courseName)
        assertEquals("15分后", upcomingState.badgeText)

        val tomorrowState = WidgetPresentation.resolveNextClassPill(
            todayOccurrences = listOf(ongoing),
            tomorrowOccurrences = listOf(upcoming.copy(date = today.plusDays(1))),
            now = today.atTime(18, 0),
        )
        assertEquals("数据结构", tomorrowState.courseName)
        assertEquals("明天", tomorrowState.badgeText)

        val endedState = WidgetPresentation.resolveNextClassPill(
            todayOccurrences = listOf(ongoing),
            tomorrowOccurrences = emptyList(),
            now = today.atTime(18, 0),
        )
        assertFalse(endedState.hasCourse)
        assertEquals("已结课", endedState.badgeText)
    }

    @Test fun resolveUpcomingExamsFiltersSortsAndMarksUrgent() {
        val now = today.atTime(9, 0)
        val exams = listOf(
            Exam(id = 1, subject = "已取消考试", date = today.plusDays(1).toString(), status = ExamStatus.CANCELLED),
            Exam(id = 2, subject = "已结束考试", date = today.minusDays(1).toString(), status = ExamStatus.SCHEDULED),
            Exam(id = 3, subject = "英语六级", date = today.plusDays(10).toString(), startTime = "09:00", endTime = "11:20", location = "教A-201", seat = "15"),
            Exam(id = 4, subject = "高等数学期末", date = today.plusDays(2).toString(), startTime = "09:00", endTime = "11:00", location = "明理楼101", seat = "8"),
        )
        val result = WidgetPresentation.resolveUpcomingExams(exams, now, limit = 3)
        assertEquals(2, result.size)
        assertEquals("高等数学期末", result[0].title)
        assertEquals("2天", result[0].countdownChip)
        assertTrue(result[0].isUrgent)
        assertTrue(result[0].locationText.contains("8号座"))

        assertEquals("英语六级", result[1].title)
        assertFalse(result[1].isUrgent)
    }
}
