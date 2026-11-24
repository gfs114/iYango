package com.gfs.iyango.feature.main.schedule

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.domain.*
import com.gfs.iyango.core.model.*
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import java.time.LocalDate
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScheduleWeekGridTest {
    @get:Rule val compose = createComposeRule()
    private val date = LocalDate.of(2026, 10, 5)
    private val semester = Semester(id = 2, startDate = date)
    private val root = Timetable(id = 1, name = "Root")
    private fun data(): WeekScheduleData {
        val moved = Course(id = 2, timetableId = 9, name = "Moved", dayOfWeek = 1)
        val temporary = moved.copy(id = 3, name = "Temporary", dayOfWeek = 2)
        val ghost = moved.copy(id = 4, startSection = 5, endSection = 5, name = "Off week", startWeek = 1, endWeek = 16, weekType = WeekType.EVEN)
        val days = listOf(
            EffectiveScheduleDay(date, root.copy(id = 9), semester,
                listOf(CourseOccurrence("moved", moved, date, 480, 540, false, date.minusWeeks(1), isMoved = true)),
                baseCourses = listOf(moved, ghost)),
            EffectiveScheduleDay(date.plusDays(1), root, semester,
                listOf(CourseOccurrence("temp", temporary, date.plusDays(1), 540, 600, false, isTemporary = true)),
                isTemporaryDay = true))
        return buildScheduleWeekData(root, semester, emptyList(), emptyList(), { _, _, _, _ -> emptyList() },
            WeekFlipDirection.JUMP, date.atTime(7, 0), days, 1)
    }
    @Test fun `shared builder keeps selected-day moved and temporary identities`() {
        val blocks = data().cells.flatMap { it.blocks }
        assertEquals(setOf("moved", "temp"), blocks.mapNotNull { it.occurrence?.occurrenceId }.toSet())
        assertEquals(date.minusWeeks(1), blocks.first { it.occurrence?.isMoved == true }.occurrence!!.anchorDate)
        assertEquals(9L, blocks.first { it.occurrence?.isMoved == true }.course.timetableId)
        assertTrue(blocks.any { it.occurrence?.isTemporary == true })
        assertTrue(blocks.any { it.isOffWeek })
    }
    @Test fun `shared grid consumes off-week visibility and header order live`() {
        val settings = mutableStateOf(ScheduleDisplaySettings())
        compose.setContent {
            IYangoThemePreview(darkTheme = false) {
                Box(Modifier.size(500.dp, 700.dp)) {
                    ScheduleWeekGrid(data(), settings.value, false, readOnly = true, onGroupClick = {})
                }
            }
        }
        compose.onNodeWithText("Off week").assertExists()
        compose.onNodeWithText("10/5").assertExists()
        val weekdayBefore = compose.onNodeWithText(DayLabels.shortLabel(1)).fetchSemanticsNode().boundsInRoot.top
        val dateBefore = compose.onNodeWithText("10/5").fetchSemanticsNode().boundsInRoot.top
        assertTrue(weekdayBefore < dateBefore)
        compose.runOnIdle { settings.value = settings.value.copy(showOffWeek = false, headerOrder = ScheduleHeaderOrder.DATE_WEEKDAY) }
        val weekdayAfter = compose.onNodeWithText(DayLabels.shortLabel(1)).fetchSemanticsNode().boundsInRoot.top
        val dateAfter = compose.onNodeWithText("10/5").fetchSemanticsNode().boundsInRoot.top
        assertTrue(dateAfter < weekdayAfter)
        compose.onNodeWithText("Off week").assertDoesNotExist()
        compose.onNodeWithText("Moved").assertExists()
        compose.onNodeWithText("Temporary").assertExists()
    }
    @Test fun `retained tab and root table changes discard open detail snapshots`() {
        val active = mutableStateOf(true)
        val state = mutableStateOf<ScheduleUiState>(ScheduleUiState.Ready(data()))
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(com.gfs.iyango.feature.main.LocalMainTabActive provides active.value) {
                IYangoThemePreview {
                    ScheduleContent(state.value, onPreviousWeek = {}, onNextWeek = {}, onPickWeek = {},
                        onBackToCurrentWeek = {}, onRetry = {}, onDeleteCourse = { _, _, _, _ -> },
                        onApplyAdjustment = { _, _, _, _, _, _, _, _ -> })
                }
            }
        }
        compose.onNodeWithText("Moved").performClick()
        compose.onNodeWithText("调整课程").assertExists()
        compose.runOnIdle { active.value = false }
        compose.waitForIdle()
        compose.runOnIdle { active.value = true }
        compose.onNodeWithText("调整课程").assertDoesNotExist()
        compose.onNodeWithText("Moved").performClick()
        compose.onNodeWithText("调整课程").assertExists()
        compose.runOnIdle { state.value = ScheduleUiState.Ready(data().copy(timetableId = 99)) }
        compose.onNodeWithText("调整课程").assertDoesNotExist()
    }

    @Test fun `exam lane and courses have separate reachable click targets`() {
        var clicks=0
        val exam=ScheduleExam(Exam(id=10,subject="考试科目",date=date.toString(),location="A101",seat="12",note="带证件"),date,0f,1f)
        compose.setContent { IYangoThemePreview { Box(Modifier.size(500.dp,700.dp)) {
            ScheduleWeekGrid(data().copy(exams=listOf(exam)),ScheduleDisplaySettings(showExams=true),false,onGroupClick={clicks++})
        } } }
        compose.onNodeWithText("Moved").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(width.toFloat()-2f,height/2f))
        }
        compose.runOnIdle { assertEquals(1,clicks) }
        compose.onNodeWithText("考试 1").performClick()
        compose.onNodeWithText("考试安排").assertExists()
        compose.onNodeWithText("地点：A101\n座位：12\n备注：带证件").assertExists()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("考试科目").performTouchInput { click(center) }
        compose.onNodeWithText("考试安排").assertExists()
        compose.runOnIdle { assertEquals(1,clicks) }
    }
    @Test fun `empty week retains date only exam and hidden weekend entry`() {
        val exam=ScheduleExam(Exam(id=11,subject="周末考试",date=date.plusDays(5).toString()),date.plusDays(5))
        compose.setContent { IYangoThemePreview { Box(Modifier.size(500.dp,700.dp)) {
            ScheduleWeekGrid(data().copy(cells=emptyList(),exams=listOf(exam)),ScheduleDisplaySettings(showExams=true,showWeekends=false),false,onGroupClick={})
        } } }
        compose.onNodeWithText("${DayLabels.shortLabel(6)}考试 1").performClick()
        compose.onNodeWithText("考试安排").assertExists()
        compose.onNodeWithText("时间未定位到网格，请以原始安排为准").assertExists()
    }

    @Test fun `bottom dock overlays the full timetable viewport`() {
        compose.setContent { IYangoThemePreview {
            Box(Modifier.size(393.dp, 500.dp).testTag("schedule-test-root")) {
            ScheduleWeeks(
                ready = ScheduleUiState.Ready(data()),
                display = ScheduleDisplaySettings(),
                hasWallpaper = false,
                bottomInset = 84.dp,
                active = true,
                onPick = {},
                onPagerWeek = { _, _, _ -> },
                onGroupClick = { _, _ -> },
                onInteractionLocked = {},
                onConfirmMove = { null },
            )
        } } }

        val root = compose.onNodeWithTag("schedule-test-root").fetchSemanticsNode().boundsInRoot
        val pager = compose.onNodeWithTag("schedule-week-pager").fetchSemanticsNode().boundsInRoot
        assertEquals(root.bottom, pager.bottom, 0.5f)
    }

}
