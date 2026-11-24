package com.gfs.iyango.feature.main.today

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.LiveRegionMode
import com.gfs.iyango.core.domain.*
import com.gfs.iyango.core.model.*
import java.time.LocalDate
import java.time.YearMonth
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeCustomizationUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `home settings entry is available with no timetable`() {
        var opens = 0
        compose.setContent { IYangoThemePreview { TodayPageLayout(TodayUiState.NoTimetable, {}, onOpenHomeSettings = { opens++ }) {} } }
        compose.onNodeWithContentDescription("首页设置").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }
    @Test fun `odd shortcuts retain equal cell widths and dispatch stable ids`() {
        var selected: String? = null
        compose.setContent { IYangoThemePreview { Box(Modifier.width(393.dp)) {
            QuickTiles(listOf("grades", "courses", "semester")) { selected = it }
        } } }
        val first = compose.onNodeWithText("成绩").fetchSemanticsNode().boundsInRoot.width
        val last = compose.onNodeWithText("学期管理").fetchSemanticsNode().boundsInRoot.width
        assertEquals(first, last, 0.1f)
        compose.onNodeWithText("学期管理").performClick()
        compose.runOnIdle { assertEquals("semester", selected) }
    }
    @Test fun `zero selected shortcuts stays empty`() {
        compose.setContent { IYangoThemePreview { QuickTiles(emptyList()) {} } }
        compose.onNodeWithText("未设置快捷入口", substring = true).assertIsDisplayed()
        compose.onNodeWithText("成绩").assertDoesNotExist()
    }
    @Test fun `only meaningful status title is a live region and countdown stays on demand`() {
        val date = LocalDate.of(2026, 11, 13)
        val occurrence = CourseOccurrence("a", Course(name = "数学", classroom = "教学楼101"), date, 541, 600, false)
        val timing = TodayCourseTiming.project(listOf(occurrence), date.atTime(9, 0, 1))
        compose.setContent { IYangoThemePreview { HomeTimingCard(timing) } }
        compose.onNodeWithContentDescription("下一门课程，数学").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithText("00:59").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        compose.onNodeWithText("教学楼101").assertIsDisplayed()
    }
    @Test fun `all hidden modules show only clear editing guidance`() {
        val date = LocalDate.of(2026, 11, 13)
        val ready = TodayUiState.Ready(Timetable(), null, 0, date, emptyList(), date.plusDays(1), emptyList(),
            emptySet(), YearMonth.from(date), emptyList(), emptyList())
        compose.setContent { IYangoThemePreview { TodayReadyContent(ready, 0.dp, 0.dp, emptyList(), emptyList(),
            {}, {}, {}, {}, {}, home = HomePreferences(hiddenModules = HomeModule.entries.map { it.id }.toSet())) } }
        compose.onNodeWithText("首页模块已全部隐藏", substring = true).assertIsDisplayed()
        compose.onNodeWithText("今日课程").assertDoesNotExist()
        compose.onNodeWithText("快捷入口").assertDoesNotExist()
    }

    @Test fun `saving a reordered layout shows its new first module`() {
        val date = LocalDate.of(2026, 11, 13)
        val ready = TodayUiState.Ready(Timetable(), null, 0, date, emptyList(), date.plusDays(1), emptyList(),
            emptySet(), YearMonth.from(date), emptyList(), emptyList())
        var home by mutableStateOf(HomePreferences())
        compose.setContent { IYangoThemePreview {
            Box(Modifier.height(190.dp)) {
                TodayReadyContent(ready, 0.dp, 0.dp, emptyList(), emptyList(), {}, {}, {}, {}, {}, home)
            }
        } }
        compose.onNodeWithText("同学", substring = true).assertIsDisplayed()
        compose.runOnIdle { home = home.copy(moduleOrder = listOf("shortcuts", "calendar", "today", "profile", "exams", "tomorrow")) }
        compose.onNodeWithText("快捷入口").assertIsDisplayed()
        compose.onNodeWithText("成绩").assertIsDisplayed()
        compose.onNodeWithText("同学", substring = true).assertDoesNotExist()
    }

    @Test fun `same course ticks interpolate but replacement clock jumps and disabling snap immediately`() {
        val date = LocalDate.of(2026, 11, 13)
        val math = CourseOccurrence("math", Course(name = "数学"), date, 540, 541, false)
        val lab = math.copy(occurrenceId = "lab", course = Course(name = "实验"))
        fun at(course: CourseOccurrence, second: Int) =
            TodayCourseTiming.project(listOf(course), date.atTime(9, 0, second))
        var timing by mutableStateOf(at(math, 20))
        var enabled by mutableStateOf(true)
        var opened: String? = null
        compose.setContent {
            IYangoThemePreview {
                CompositionLocalProvider(LocalMotionEnabled provides enabled) {
                    HomeTimingCard(timing) { opened = timing.current?.occurrenceId }
                }
            }
        }
        fun progress() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo), useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        compose.mainClock.autoAdvance = false
        assertEquals(20 / 60f, progress(), .0001f)
        compose.runOnIdle { Snapshot.withMutableSnapshot { timing = at(math, 21) } }
        // The first frame delivers the new state to composition. Let Android finish
        // publishing its updated semantics after each frame before sampling progress.
        val samples = mutableListOf<Float>()
        repeat(12) {
            if (samples.none { it > 20 / 60f && it < 21 / 60f }) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                samples += progress()
            }
        }
        assertTrue("progress frames = $samples", samples.any { it > 20 / 60f && it < 21 / 60f })
        compose.runOnIdle { Snapshot.withMutableSnapshot { enabled = false } }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(21 / 60f, progress(), .0001f)
        compose.runOnIdle { Snapshot.withMutableSnapshot { enabled = true; timing = at(lab, 45) } }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(.75f, progress(), .0001f)
        compose.onNodeWithText("数学").assertDoesNotExist()
        compose.onNodeWithText("实验").performClick()
        compose.runOnIdle {
            assertEquals("lab", opened)
            Snapshot.withMutableSnapshot { timing = at(lab, 3) }
        }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(.05f, progress(), .0001f)
    }

    @Test fun `handle follows finger with motion off and cancel lands on current accessible order`() {
        var draft by mutableStateOf(HomePreferences())
        compose.setContent {
            IYangoThemePreview {
                CompositionLocalProvider(LocalMotionEnabled provides false) {
                    HomeSettingsContent(HomeEditorState(loaded = true, draft = draft),
                        onBack = {}, onSave = {}, onRestoreDefaults = {}, onToggleModule = {},
                        onMoveModule = { id, distance ->
                            val order = draft.moduleOrder.toMutableList()
                            val from = order.indexOf(id)
                            order.add(from + distance, order.removeAt(from))
                            draft = draft.copy(moduleOrder = order)
                        },
                        onToggleShortcut = {}, onMoveShortcut = { _, _ -> }, preview = {})
                }
            }
        }
        fun handle(name: String) = compose.onNodeWithContentDescription("拖动$name")
        val firstY = handle("资料卡").fetchSemanticsNode().boundsInRoot.center.y
        val nextY = handle("今日课程").fetchSemanticsNode().boundsInRoot.center.y
        val step = nextY - firstY
        val root = compose.onRoot()
        val start = handle("资料卡").fetchSemanticsNode().boundsInRoot.center - root.fetchSemanticsNode().boundsInRoot.topLeft
        root.performTouchInput { down(start); advanceEventTime(700); moveBy(Offset.Zero) }
        root.performTouchInput { moveBy(Offset(0f, step / 3f)) }
        assertEquals(firstY + step / 3f, handle("资料卡").fetchSemanticsNode().boundsInRoot.center.y, 2f)
        root.performTouchInput { moveTo(start + Offset(0f, step + 4f)) }
        compose.runOnIdle { assertEquals(listOf("today", "profile"), draft.moduleOrder.take(2)) }
        root.performTouchInput { cancel() }
        assertEquals(nextY, handle("资料卡").fetchSemanticsNode().boundsInRoot.center.y, 2f)
        compose.onNodeWithContentDescription("上移资料卡").performClick()
        compose.runOnIdle { assertEquals(listOf("profile", "today"), draft.moduleOrder.take(2)) }
        compose.onNodeWithContentDescription("上移资料卡").assertIsNotEnabled()
    }
}
