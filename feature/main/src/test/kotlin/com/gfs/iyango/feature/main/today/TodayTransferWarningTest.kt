package com.gfs.iyango.feature.main.today

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.domain.BuildingTransferDetector
import com.gfs.iyango.core.domain.CourseOccurrence
import com.gfs.iyango.core.domain.CourseStatus
import com.gfs.iyango.core.domain.TodayCourseTiming
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.Timetable
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TodayTransferWarningTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `HomeTimingCard and TodayReadyContent display cross-building rush pill`() {
        val date = LocalDate.of(2026, 11, 13)
        val first = CourseOccurrence(
            occurrenceId = "occ-1",
            course = Course(id = 1, name = "高等数学", classroom = "明理楼 A101"),
            date = date,
            startMinute = 8 * 60,
            endMinute = 9 * 60 + 40,
            isOverride = false,
        )
        val second = CourseOccurrence(
            occurrenceId = "occ-2",
            course = Course(id = 2, name = "大学物理", classroom = "信息楼 B202"),
            date = date,
            startMinute = 10 * 60,
            endMinute = 11 * 60 + 40,
            isOverride = false,
        )
        val occurrences = listOf(first, second)
        val timing = TodayCourseTiming.project(occurrences, date.atTime(9, 0))
        val transfers = BuildingTransferDetector.detectForDay(occurrences)
        val rows = listOf(
            OccurrenceRow(first, CourseStatus.IN_PROGRESS, transfers[first.occurrenceId]),
            OccurrenceRow(second, CourseStatus.UPCOMING, transfers[second.occurrenceId]),
        )
        val ready = TodayUiState.Ready(
            timetable = Timetable(id = 1, name = "默认课表", isCurrent = true),
            semester = null,
            week = 10,
            today = date,
            todayRows = rows,
            tomorrow = date.plusDays(1),
            tomorrowRows = emptyList(),
            busyDates = setOf(date),
            month = YearMonth.from(date),
            timeSlots = emptyList(),
            detailCourses = emptyList(),
            now = date.atTime(9, 0),
            timing = timing,
        )

        compose.setContent {
            IYangoThemePreview {
                TodayReadyContent(
                    state = ready,
                    bottomPadding = 0.dp,
                    topPadding = 0.dp,
                    paletteLight = emptyList(),
                    paletteDark = emptyList(),
                    onCourseClick = {},
                    onQuickTile = {},
                    onOpenSchedule = {},
                    onOpenExams = {},
                    onProfileClick = {},
                )
            }
        }
        compose.onNodeWithText("换楼：明理楼 → 信息楼（间隔20分钟）").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("换楼：明理楼 → 信息楼").performScrollTo().assertIsDisplayed()
    }
}
