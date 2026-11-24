package com.gfs.iyango.feature.main.schedule

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.domain.*
import com.gfs.iyango.core.model.*
import kotlinx.coroutines.CompletableDeferred
import java.time.LocalDate
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h1000dp-mdpi")
class SchedulePagerDragTest {
    @get:Rule val compose = createComposeRule()
    private val date = LocalDate.of(2026, 10, 5)
    private val table = Timetable(id = 1, name = "Table")
    private val semester = Semester(id = 1, timetableId = 1, startDate = date, totalWeeks = 4)
    private val slots = (1..12).map { TimeSlot(section = it) }
    private fun pages(custom: Boolean = false, temporary: Boolean = false, merged: Boolean = false, span: Int = 2): List<WeekScheduleData> {
        val days = (0..27).map { offset ->
            val d = date.plusDays(offset.toLong())
            val week = offset / 7 + 1
            val c = Course(id = week.toLong(), timetableId = 1, name = "Week$week", dayOfWeek = 1,
                startSection = 1, endSection = span, startWeek = week, endWeek = week,
                timeMode = if (custom) 1 else 0, startTime = if (custom) "08:00" else "", endTime = if (custom) "10:00" else "",
                groupKey = if (merged) "merged" else "")
            val courses = if (merged) listOf(c, c.copy(id = 50 + week.toLong(), startSection = 3, endSection = 4)) else listOf(c)
            EffectiveScheduleDay(d, table, semester,
                occurrences = if (d.dayOfWeek.value == 1) courses.map { CourseOccurrence("$week-${it.id}", it, d,
                    if (custom) 480 else null, if (custom) 600 else null, false, date.minusWeeks(1), isTemporary = temporary) } else emptyList(),
                isTemporaryDay = temporary, timeSlots = slots, baseCourses = courses)
        }
        return (1..4).map { buildScheduleWeekData(table, semester, slots, emptyList(), { _, _, _, count -> (1..count).map { TimeRailRow(it,it.toString()) } },
            WeekFlipDirection.JUMP, date.atTime(7, 0), days, it) }
    }
    private val lunchSlots = (1..12).map { section ->
        val start = if (section <= 4) 480 + (section - 1) * 60 else 840 + (section - 5) * 60
        TimeSlot(section = section, startTime = "%02d:%02d".format(start / 60, start % 60),
            endTime = "%02d:%02d".format((start + 45) / 60, (start + 45) % 60))
    }
    private val selected = mutableStateOf(1)
    private val active = mutableStateOf(true)
    private val root = mutableStateOf(1L)
    private var saves = 0
    private var clicks = 0
    private var picked = mutableListOf<Int>()
    private var saved: ScheduleDropCandidate? = null
    private val saveGate = CompletableDeferred<Unit>()
    private var cancelledSaves = 0
    private fun mount(width: Int = 500, custom: Boolean = false, temporary: Boolean = false, merged: Boolean = false, span: Int = 2, extras: Boolean = false, suspendSave: Boolean = false) {
        val weeks = pages(custom, temporary, merged, span).map { data ->
            if (!extras) data else data.copy(timeSlots = lunchSlots, effectiveDays = data.effectiveDays.map { it.copy(timeSlots = lunchSlots, displayTimeSlots = lunchSlots) },
                exams = listOf(ScheduleExam(Exam(id = 1, subject = "ExamTimed"), data.weekStart, 0f, 1f)))
        }
        compose.setContent {
            IYangoThemePreview {
                val all = weeks.map { it.copy(timetableId = root.value) }
                Box(Modifier.size(width.dp, 700.dp)) {
                    ScheduleWeeks(ScheduleUiState.Ready(all[selected.value - 1], all), ScheduleDisplaySettings(mergeAdjacent = merged, rowHeightDp = 50, showLunchBreak = extras, showExams = extras), false,
                        0.dp, active.value, { selected.value = it }, { _, w, _ -> picked += w; selected.value = w },
                        { _, _ -> clicks++ }, {}, { saves++; saved = it
                            if (suspendSave) try { saveGate.await() } finally { if (!saveGate.isCompleted) cancelledSaves++ }
                            null })
                }
            }
        }
    }
    @Test fun `pager renders actual neighboring week and follows external jumps without feedback`() {
        mount()
        compose.onNodeWithTag("schedule-week-pager").performTouchInput { swipeLeft() }
        compose.onNodeWithText("Week2").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, selected.value); assertEquals(listOf(2), picked) }
        compose.runOnIdle { selected.value = 4 }
        compose.onNodeWithText("Week4").assertIsDisplayed()
        compose.runOnIdle { selected.value = 1 }
        compose.onNodeWithText("Week1").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(2), picked) }
    }
    @Test fun `inactive and horizontally overflowing grids do not swipe weeks`() {
        mount(width = 319)
        compose.onNodeWithTag("schedule-week-pager").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(1, selected.value) }
        compose.runOnIdle { active.value = false }
        compose.onNodeWithTag("schedule-week-pager").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertTrue(picked.isEmpty()) }
    }
    @Test fun `pager is enabled when all seven minimum width days exactly fit the main grid`() {
        mount(width = 320)
        compose.onNodeWithTag("schedule-week-pager").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(2, selected.value); assertEquals(listOf(2), picked) }
    }
    @Test fun `long press drag cancel has no writes and resets floating state`() {
        mount()
        compose.onNodeWithText("Week1").performTouchInput {
            down(center); advanceEventTime(700); moveBy(Offset(20f, 20f)); cancel()
        }
        compose.onNodeWithText("取消调课").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, saves) }
    }
    @Test fun `long press stages cross week and confirmation keeps exact anchor`() {
        mount()
        compose.onNodeWithText("Week1").performTouchInput { longClick() }
        if (compose.onAllNodesWithText("返回暂存").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("返回暂存").performClick()
        compose.onNodeWithText("暂存到其他周").performClick()
        compose.onNodeWithTag("schedule-week-pager").performTouchInput { swipeLeft() }
        compose.onNodeWithText("Week2").assertIsDisplayed()
        // Course cards become inert while staged; tapping that actual lattice point places the source.
        compose.onNodeWithText("Week2").performTouchInput { click(center) }
        compose.onNodeWithText("确认临时调课").assertExists()
        compose.runOnIdle { assertEquals(0, saves) }
        compose.onNodeWithText("确认调课").performClick()
        compose.runOnIdle {
            assertEquals(1, saves)
            assertEquals(date.minusWeeks(1), saved!!.source.expected.occurrence.anchorDate)
            assertEquals(date.plusWeeks(1), saved!!.targetDay.date)
            assertEquals(1L, saved!!.source.expected.baseCourse.id)
        }
    }
    @Test fun `custom clocks require explicit target span before staging`() {
        mount(custom = true)
        compose.onNodeWithText("Week1").performTouchInput { longClick() }
        compose.onNodeWithText("选择目标课程长度").assertExists()
        compose.onNodeWithText("原钟点 08:00–10:00", substring = true).assertExists()
        compose.onNodeWithText("3节").performClick()
        compose.onNodeWithText("已暂存：Week1（3节）").assertExists()
        compose.runOnIdle { assertEquals(0, saves) }
    }
    @Test fun `temporary source cannot stage and root changes discard a staged source`() {
        mount(custom = true)
        compose.onNodeWithText("Week1").performTouchInput { longClick() }
        compose.onNodeWithText("2节").performClick()
        compose.runOnIdle { root.value = 99 }
        compose.onNodeWithText("取消调课").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, saves) }
    }
    @Test fun `temporary day long press cannot create a drag`() {
        mount(temporary = true)
        compose.onNodeWithText("Week1").performTouchInput { longClick() }
        compose.onNodeWithText("取消调课").assertDoesNotExist()
        compose.onNodeWithText("确认临时调课").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, saves) }
    }
    @Test fun `wide mode has permanent reachable week picker`() {
        mount(width = 900)
        compose.onNodeWithText("选择周次").assertIsDisplayed()
        compose.onAllNodesWithText("4").onFirst().performClick()
        compose.onNodeWithText("Week4").assertIsDisplayed()
    }
    @Test fun `merged real blocks select specific occurrence before staging`() {
        mount(merged = true)
        compose.onNodeWithText("Week1").performTouchInput { longClick() }
        compose.onNodeWithText("选择要调整的课程实例").assertExists()
        compose.onNodeWithText("Week1 $date 3–4节").performClick()
        compose.onNodeWithText("已暂存：Week1（2节）").assertExists()
        compose.runOnIdle { assertEquals(0, saves) }
    }
    @Test fun `ordinary click and vertical scroll do not capture a drag`() {
        mount()
        compose.onNodeWithText("Week1").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, clicks) }
        compose.onNodeWithText("Week1").performTouchInput { swipeUp() }
        compose.onNodeWithText("取消调课").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, saves) }
    }
    @Test fun `segmented long press preserves grab offset after recomposition and targets exact section`() {
        mount(span = 4)
        val card = compose.onNodeWithText("Week1").fetchSemanticsNode().boundsInRoot
        val start = card.center
        compose.onNodeWithTag("schedule-week-pager").performTouchInput {
            down(start); advanceEventTime(700); moveBy(Offset.Zero)
        }
        compose.onNodeWithText("正在拖动：Week1").assertIsDisplayed()
        // The overlay has recomposed. Move the pointer by two exact section heights.
        compose.onNodeWithTag("schedule-week-pager").performTouchInput {
            moveTo(start + Offset(0f, 100f)); up()
        }
        compose.onNodeWithText("确认临时调课").assertExists()
        compose.onNodeWithText("确认调课").performClick()
        compose.runOnIdle {
            assertEquals(ScheduleDropSlot(1,3,6), saved!!.slot)
            assertEquals(1, saves)
        }
    }
    @Test fun `release without moving a multi section course preserves original start`() {
        mount(span = 4)
        val start = compose.onNodeWithText("Week1").fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithTag("schedule-week-pager").performTouchInput { down(start); advanceEventTime(700); moveBy(Offset.Zero) }
        compose.onNodeWithText("正在拖动：Week1").assertIsDisplayed()
        compose.onNodeWithTag("schedule-week-pager").performTouchInput { up() }
        compose.onNodeWithText("确认调课").performClick()
        compose.runOnIdle { assertEquals(ScheduleDropSlot(1,1,4), saved!!.slot) }
    }
    @Test fun `external jump and current week win over an in flight pager fling`() {
        mount()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("schedule-week-pager").performTouchInput { swipeLeft(durationMillis = 100) }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { selected.value = 4 }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(4, selected.value) }
        compose.onNodeWithText("Week4").assertIsDisplayed()
        compose.onNodeWithTag("schedule-week-pager").performTouchInput { swipeRight(durationMillis = 100) }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { selected.value = 1 }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(1, selected.value) }
        compose.onNodeWithText("Week1").assertIsDisplayed()
        compose.mainClock.autoAdvance = true
    }
    @Test fun `wide sidebar exam header lunch and scroll use actual grid root coordinates`() {
        mount(width = 900, span = 4, extras = true)
        // Scroll from an empty day, then hold the source through a real recomposition.
        compose.onNodeWithTag("schedule-week-pager").performTouchInput {
            swipe(Offset(width - 20f, height - 40f), Offset(width - 20f, 200f), durationMillis = 200)
        }
        val lattice = compose.onNodeWithTag("schedule-lattice-1").fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithText("Week1").fetchSemanticsNode().boundsInRoot
        val pager = compose.onNodeWithTag("schedule-week-pager").fetchSemanticsNode().boundsInRoot
        val start = card.center - pager.topLeft
        val grab = card.center.y - lattice.top
        val dayWidth = (lattice.width - 40f) / 7
        val target = Offset(lattice.left + 40f + dayWidth * 1.5f, lattice.top + 218f + grab) - pager.topLeft
        compose.onNodeWithTag("schedule-week-pager").performTouchInput { down(start); advanceEventTime(700); moveBy(Offset.Zero) }
        compose.onNodeWithText("正在拖动：Week1").assertIsDisplayed()
        compose.onNodeWithTag("schedule-week-pager").performTouchInput { moveTo(target); up() }
        compose.onNodeWithText("确认调课").performClick()
        compose.runOnIdle {
            assertEquals(ScheduleDropSlot(2,5,8), saved!!.slot)
            assertEquals(date.plusDays(1), saved!!.targetDay.date)
        }
    }
    @Test fun `inactive tab discards a staged source without any write`() {
        mount(custom = true)
        compose.onNodeWithText("Week1").performTouchInput { longClick() }
        compose.onNodeWithText("2节").performClick()
        compose.onNodeWithText("取消调课").assertExists()
        compose.runOnIdle { active.value = false }
        compose.onNodeWithText("取消调课").assertDoesNotExist()
        compose.runOnIdle { active.value = true }
        compose.onNodeWithText("取消调课").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, saves) }
    }
    @Test fun `temporary day picker excludes selected date owner and allows root as source`() {
        val initial = pages().first()
        val b = Timetable(id = 2, name = "Owner B")
        val data = initial.copy(effectiveDays = initial.effectiveDays.map { if (it.date == date.plusDays(1)) it.copy(timetable = b) else it })
        compose.setContent { IYangoThemePreview { Box(Modifier.size(500.dp,700.dp)) {
            ScheduleContent(ScheduleUiState.Ready(data), onPreviousWeek = {}, onNextWeek = {}, onPickWeek = {},
                onBackToCurrentWeek = {}, onRetry = {}, onDeleteCourse = { _, _, _, _ -> },
                onApplyAdjustment = { _, _, _, _, _, _, _, _ -> }, timetables = listOf(table,b))
        } } }
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("临时整天课表").performClick()
        compose.onNodeWithText("${DayLabels.shortLabel(2)} 10/6").performClick()
        compose.onNodeWithText("所选日期课表：Owner B").assertExists()
        compose.onNodeWithText("Owner B").assertDoesNotExist()
        compose.onNodeWithText("Table").assertExists()
    }
    @Test fun `staged placement on exam lane reaches lattice instead of exam detail`() {
        mount(custom = true, extras = true)
        compose.onNodeWithText("Week1").performTouchInput { longClick() }
        compose.onNodeWithText("2节").performClick()
        compose.onAllNodesWithText("ExamTimed").onFirst().performTouchInput { click(center) }
        compose.onNodeWithText("确认临时调课").assertExists()
        compose.onNodeWithText("考试安排").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, saves) }
        compose.onNodeWithText("返回暂存").performClick()
        compose.onNodeWithText("取消调课").performClick()
        compose.onNodeWithText("取消调课").assertDoesNotExist()
    }
    @Test fun `floating bar blocks touch through and leaves last two sections reachable after scroll`() {
        mount(custom = true)
        val x = compose.onNodeWithText("Week1").fetchSemanticsNode().boundsInRoot.center.x
        compose.onNodeWithText("Week1").performTouchInput { longClick() }
        compose.onNodeWithText("1节").performClick()
        compose.onNodeWithText("已暂存：Week1（1节）").performTouchInput { click(center) }
        compose.onNodeWithText("确认临时调课").assertDoesNotExist()
        compose.onNodeWithTag("schedule-week-pager").performTouchInput {
            swipe(Offset(width-30f, height-160f), Offset(width-30f, 80f), durationMillis=200)
        }
        val bar = compose.onNodeWithTag("schedule-drag-bar").fetchSemanticsNode().boundsInRoot
        listOf(11,12).forEach { section ->
            val row = compose.onAllNodesWithText(section.toString()).onFirst().fetchSemanticsNode().boundsInRoot
            assertTrue("section $section hidden by bar", row.bottom < bar.top)
            compose.onNodeWithTag("schedule-week-pager").performTouchInput { click(Offset(x,row.center.y)) }
            compose.onNodeWithText("确认临时调课").assertExists()
            if (section == 11) compose.onNodeWithText("返回暂存").performClick() else compose.onNodeWithText("确认调课").performClick()
        }
        compose.runOnIdle { assertEquals(ScheduleDropSlot(1,12,12), saved!!.slot) }
    }
    @Test fun `busy confirmation submits once and root disposal cancels pending save`() {
        mount(suspendSave = true)
        compose.onNodeWithText("Week1").performTouchInput { longClick() }
        compose.onNodeWithText("确认调课").performClick()
        compose.onNodeWithText("保存中").assertIsNotEnabled()
        compose.onNodeWithText("保存中").performTouchInput { click(center); click(center) }
        compose.runOnIdle { assertEquals(1, saves); root.value = 99 }
        compose.onNodeWithText("保存中").assertDoesNotExist()
        compose.onNodeWithText("取消调课").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, saves); assertEquals(1, cancelledSaves) }
    }
    @Test fun `pager acknowledges a new return intent even when already on the same week`() {
        val weeks = pages()
        val ready = mutableStateOf(ScheduleUiState.Ready(weeks.first(), weeks))
        val positions = mutableListOf<SchedulePagePosition>()
        compose.setContent { IYangoThemePreview {
            Box(Modifier.size(500.dp, 700.dp)) {
                ScheduleWeeks(ready.value, ScheduleDisplaySettings(), false, 0.dp, true,
                    {}, { _, _, _ -> }, { _, _ -> }, {}, { null },
                    onPageSettled = { positions += it })
            }
        } }
        compose.runOnIdle {
            assertEquals(0L, positions.last().navigationVersion)
            ready.value = ready.value.copy(data = ready.value.data.copy(navigationVersion = 7))
        }
        compose.runOnIdle { assertEquals(SchedulePagePosition(ready.value.data.context, 1, 7), positions.last()) }
    }

    @Test fun `dock auto-hide suppresses during staged drag move and restores after cancel`() {
        val dockAutoHide = com.gfs.iyango.feature.main.liquidglass.DockAutoHideState()
        val weeks = pages(custom = true)
        compose.setContent {
            IYangoThemePreview {
                CompositionLocalProvider(
                    com.gfs.iyango.feature.main.liquidglass.LocalDockAutoHide provides dockAutoHide,
                ) {
                    Box(Modifier.size(500.dp, 700.dp)) {
                        ScheduleWeeks(
                            ScheduleUiState.Ready(weeks.first(), weeks),
                            ScheduleDisplaySettings(rowHeightDp = 50),
                            false,
                            0.dp,
                            true,
                            {},
                            { _, _, _ -> },
                            { _, _ -> },
                            {},
                            { null },
                        )
                    }
                }
            }
        }
        compose.runOnIdle { assertTrue(dockAutoHide.visible) }
        compose.onNodeWithText("Week1").performTouchInput { longClick() }
        compose.onNodeWithText("1节").performClick()
        compose.runOnIdle {
            assertFalse(dockAutoHide.visible)
            dockAutoHide.onScroll(120f)
            assertFalse("Dock must stay suppressed on upward scroll while staged", dockAutoHide.visible)
        }
        compose.onNodeWithText("取消调课").performClick()
        compose.runOnIdle { assertTrue(dockAutoHide.visible) }
    }

}
