package com.gfs.iyango.feature.main.today

import com.gfs.iyango.core.domain.*
import com.gfs.iyango.core.model.Course
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class HomeCountdownTextTest {
    private val date = LocalDate.of(2026, 11, 13)
    @Test fun `countdown uses stable seconds hours and future date detail`() {
        val now = date.atTime(9, 0, 1)
        fun timing(day: LocalDate, start: Int) = TodayCourseTiming.project(listOf(CourseOccurrence("a", Course(name = "数学"), day, start, start + 45, false)), now)
        assertEquals("00:59", homeCountdownText(timing(date, 541)))
        assertEquals("1小时59分钟", homeCountdownText(timing(date, 660)))
        assertEquals("2天后", homeCountdownText(timing(date.plusDays(2), 540)))
        assertEquals("未来7天暂无已知课程", homeTimingTitle(TodayCourseTiming.project(emptyList(), now)))
    }
    @Test fun `current long class retains minute and second precision`() {
        val current = CourseOccurrence("long", Course(name = "实验"), date, 540, 720, false)
        assertEquals("120:00", homeCountdownText(TodayCourseTiming.project(listOf(current), date.atTime(10, 0))))
    }
}
