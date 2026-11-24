package com.gfs.iyango.feature.main

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextLayoutResult
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.Timetable
import com.gfs.iyango.feature.main.grades.GradesHeader
import com.gfs.iyango.feature.main.grades.GradesPageLayout
import com.gfs.iyango.feature.main.grades.GradesUiState
import com.gfs.iyango.feature.main.mine.MineScreen
import com.gfs.iyango.feature.main.schedule.*
import com.gfs.iyango.feature.main.today.TodayPageLayout
import com.gfs.iyango.feature.main.today.TodayReadyContent
import com.gfs.iyango.feature.main.today.TodayUiState
import java.time.LocalDate
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real page/header layouts and callbacks; the shared material is tested in designsystem. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainTopBarsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `today header remains fixed across loading empty and error and account acts immediately`() {
        val state = mutableStateOf<TodayUiState>(TodayUiState.FirstLoading)
        var accounts = 0
        compose.setContent { IYangoThemePreview {
            Box(Modifier.size(393.dp, 700.dp)) {
                TodayPageLayout(state.value, onProfileClick = { accounts++ }) { innerPadding ->
                    Box(Modifier.fillMaxSize().padding(innerPadding).testTag("today-body"))
                }
            }
        } }
        val initial = compose.onNodeWithTag("today-body").fetchSemanticsNode().boundsInRoot.top
        val date = LocalDate.of(2026, 10, 29)
        val ready = TodayUiState.Ready(Timetable(name = "课表"), Semester(startDate = date), 8, date, emptyList(),
            date.plusDays(1), emptyList(), emptySet(), java.time.YearMonth.from(date), emptyList(), emptyList())
        listOf(TodayUiState.NoTimetable, TodayUiState.Error("断网"), ready).forEach { next ->
            compose.runOnIdle { state.value = next }
            compose.onNodeWithText("今日").assertIsDisplayed()
            assertEquals(initial, compose.onNodeWithTag("today-body").fetchSemanticsNode().boundsInRoot.top, .5f)
        }
        compose.onNodeWithContentDescription("教务账号").performClick()
        compose.runOnIdle { assertEquals(1, accounts) }
    }

    @Test fun `large font grows today header and keeps account target above the body`() {
        val scale = mutableStateOf(1f)
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, scale.value)) {
            IYangoThemePreview { Box(Modifier.size(320.dp, 700.dp)) {
                TodayPageLayout(TodayUiState.FirstLoading, onProfileClick = {}) { innerPadding ->
                    Box(Modifier.fillMaxSize().padding(innerPadding).testTag("today-body"))
                }
            } }
        } }
        val normalTop = compose.onNodeWithTag("today-body").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { scale.value = 1.8f }
        val largeTop = compose.onNodeWithTag("today-body").fetchSemanticsNode().boundsInRoot.top
        assertTrue(largeTop > normalTop)
        val account = compose.onNodeWithContentDescription("教务账号").fetchSemanticsNode().boundsInRoot
        assertTrue(account.height >= 48f && account.width >= 48f)
        assertTrue(account.bottom <= largeTop)
        compose.onNodeWithText("今日").assertIsDisplayed()
    }

    @Test fun `mine title stays above scrolling groups`() {
        compose.setContent { IYangoThemePreview {
            Box(Modifier.size(393.dp, 700.dp).testTag("mine-page")) { MineScreen() }
        } }
        // The default-start-tab settings also contain "我的". Locate the heading itself.
        val title = compose.onNode(hasText("我的") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
            useUnmergedTree = true)
        val before = title.fetchSemanticsNode().boundsInRoot
        val paragraphs = mutableListOf<TextLayoutResult>()
        title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(paragraphs) }
        val paragraph = paragraphs.single()
        assertEquals("Two normal-size title glyphs must stay on one line", 1, paragraph.lineCount)
        assertFalse("Header title must not overflow its allocated width", paragraph.didOverflowWidth)
        assertFalse("Header title must not be ellipsized", paragraph.isLineEllipsized(0))
        val glyphWidth = paragraph.getLineRight(0) - paragraph.getLineLeft(0)
        assertTrue("Native 20sp Chinese title must have real glyph width; bounds=$before, glyphWidth=$glyphWidth",
            glyphWidth >= 32f && before.width >= glyphWidth - .5f)
        compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }
        title.assertIsDisplayed()
        assertEquals(before, title.fetchSemanticsNode().boundsInRoot)
        val image = compose.onNodeWithTag("mine-page").captureToImage().asAndroidBitmap()
        val output = File("../../build/diagnostics/top-bars-20261029/mine-fixed-header.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `today ready list scrolls into fixed header band`() {
        val date = LocalDate.of(2026, 10, 29)
        val ready = TodayUiState.Ready(Timetable(name = "课表"), Semester(startDate = date), 8, date, emptyList(),
            date.plusDays(1), emptyList(), emptySet(), java.time.YearMonth.from(date), emptyList(), emptyList())
        compose.setContent { IYangoThemePreview { Box(Modifier.size(393.dp, 700.dp)) {
            TodayPageLayout(ready, onProfileClick = {}) { innerPadding ->
                TodayReadyContent(ready, bottomPadding = 96.dp, topPadding = innerPadding.calculateTopPadding(),
                    paletteLight = emptyList(), paletteDark = emptyList(), onCourseClick = {}, onQuickTile = {},
                    onOpenSchedule = {}, onOpenExams = {}, onProfileClick = {})
            }
        } } }
        val header = compose.onNodeWithText("今日").fetchSemanticsNode().boundsInRoot
        val scroll = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        assertEquals(0f, scroll.fetchSemanticsNode().boundsInRoot.top, .5f)
        scroll.performScrollToIndex(1)
        scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 40f) }
        val section = compose.onNodeWithText("今日课程").fetchSemanticsNode().boundsInRoot
        assertTrue("List content moves into the fixed header band", section.top < header.bottom + 24f)
        assertEquals(header, compose.onNodeWithText("今日").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun `grades filters scroll behind header without submitting a new query`() {
        var selected = ""
        var refreshes = 0
        val state = GradesUiState(connectionId = "account", loggedIn = true,
            terms = listOf(com.gfs.iyango.core.model.GradeTerm("2026", "2026 秋季")),
            records = (1..12).map { com.gfs.iyango.core.model.GradeRecord(id = it.toLong(),
                courseName = "测试课程 $it", scoreText = "90", scoreValue = 90.0, credit = 2.0) })
        compose.setContent { IYangoThemePreview { Box(Modifier.size(393.dp, 700.dp)) {
            GradesPageLayout(state, bottomPadding = 96.dp, onOpenSchool = {},
                onSelectRange = { selected = it }, onSelectSort = {},
                onRefresh = { refreshes++ }, onDismissError = {}, onMarkRead = {},
                onIncludeOptionalChange = {}, onSelect = {})
        } } }
        val header = compose.onNodeWithText("成绩").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("grades-term-selector").performClick()
        compose.onNodeWithText("选择学期").assertIsDisplayed()
        compose.onNodeWithTag("grades-term-option-2026").performClick()
        compose.runOnIdle { assertEquals("2026", selected); assertEquals(0, refreshes) }
        compose.onNodeWithText("选择学期").assertDoesNotExist()
        compose.onNodeWithText("查询").assertDoesNotExist()
        val scroll = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        assertEquals(0f, scroll.fetchSemanticsNode().boundsInRoot.top, .5f)
        scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 64f) }
        val filterTop = compose.onNodeWithText("学期").fetchSemanticsNode().boundsInRoot.top
        assertTrue("The real filter item enters the top bar band", filterTop < header.bottom + 24f)
        assertEquals(header, compose.onNodeWithText("成绩").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { assertEquals(0, refreshes) }
    }

    @Test fun `mine scroll viewport extends behind fixed header`() {
        compose.setContent { IYangoThemePreview {
            Box(Modifier.size(393.dp, 700.dp)) { MineScreen() }
        } }
        val title = compose.onNode(hasText("我的") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
            useUnmergedTree = true)
        val titleBounds = title.fetchSemanticsNode().boundsInRoot
        val scroll = compose.onNode(hasScrollAction())
        assertEquals("The scroll viewport must include the area behind the top bar", 0f,
            scroll.fetchSemanticsNode().boundsInRoot.top, .5f)
        scroll.performTouchInput { swipeUp(startY = height * .7f, endY = height * .4f, durationMillis = 600) }
        assertEquals(titleBounds, title.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun `expired grades account keeps relogin action`() {
        var accounts = 0
        compose.setContent { IYangoThemePreview {
            GradesHeader(GradesUiState(connectionId = "account", maskedAccount = "2024****01"), { accounts++ })
        } }
        compose.onNodeWithText("重新登录").performClick()
        compose.runOnIdle { assertEquals(1, accounts) }
    }

    @Test fun `schedule scroll viewport extends behind header while initial grid keeps its old geometry`() {
        val start = LocalDate.of(2026, 10, 26)
        val course = com.gfs.iyango.core.model.Course(id = 10, name = "课表滚动内容", dayOfWeek = 1,
            startSection = 1, endSection = 2)
        val data = WeekScheduleData("课表", semester = Semester(startDate = start),
            totalWeeks = 20, currentWeek = 8, displayedWeek = 8, flipDirection = WeekFlipDirection.JUMP,
            weekStart = start, today = start, now = start.atTime(9, 0), sectionCount = 12,
            cells = listOf(WeekScheduleData.GridCellGroup(1, 1,
                listOf(WeekScheduleData.GridBlock(course, null, 2, false)))),
            timeSlots = emptyList(), courses = listOf(course), weekDayCounts = emptyList())
        compose.setContent { IYangoThemePreview { Box(Modifier.size(393.dp, 852.dp)) {
            ScheduleContent(ScheduleUiState.Ready(data), onPreviousWeek = {}, onNextWeek = {}, onPickWeek = {},
                onBackToCurrentWeek = {}, onRetry = {}, onDeleteCourse = { _, _, _, _ -> },
                onApplyAdjustment = { _, _, _, _, _, _, _, _ -> })
        } } }
        val lattice = compose.onNodeWithTag("schedule-lattice-8").fetchSemanticsNode().boundsInRoot
        val headerHeight = lattice.top - ScheduleGridMath.HEADER_HEIGHT_DP
        val original = ScheduleGridMetrics(393f, 852f - headerHeight, 12)
        val courseBounds = compose.onNodeWithTag("schedule-course-10").fetchSemanticsNode().boundsInRoot
        assertEquals("Initial course height must retain the original header-below viewport metrics",
            original.sectionHeight * 2 - ScheduleGridMath.CARD_GAP_DP, courseBounds.height, 1f)
        val scroll = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        assertEquals("Grid scroll viewport includes the top bar band", 0f,
            scroll.fetchSemanticsNode().boundsInRoot.top, .5f)
        scroll.performTouchInput { swipeUp(startY = height * .6f, endY = height * .4f, durationMillis = 600) }
        val after = compose.onNodeWithTag("schedule-course-10").fetchSemanticsNode().boundsInRoot
        assertTrue("Course content must scroll into the fixed header band", after.top < headerHeight)
        compose.onNodeWithText("第8周 周一").assertIsDisplayed()
        compose.onNodeWithText("2026/10/26").assertIsDisplayed()
    }

    @Test fun `schedule drag excludes fixed header band in root coordinates after scrolling`() {
        val start = LocalDate.of(2026, 10, 26)
        val course = com.gfs.iyango.core.model.Course(id = 20, name = "拖动课程", dayOfWeek = 1,
            startSection = 4, endSection = 5)
        val data = WeekScheduleData("课表", semester = Semester(startDate = start),
            totalWeeks = 20, currentWeek = 8, displayedWeek = 8, flipDirection = WeekFlipDirection.JUMP,
            weekStart = start, today = start, now = start.atTime(9, 0), sectionCount = 11,
            cells = listOf(WeekScheduleData.GridCellGroup(1, 4,
                listOf(WeekScheduleData.GridBlock(course, com.gfs.iyango.core.domain.CourseOccurrence("drag", course, start, null, null, false), 2, false)))),
            timeSlots = emptyList(), courses = listOf(course), weekDayCounts = emptyList())
        val drops = mutableListOf<ScheduleDropSlot?>()
        val staged = mutableStateOf<Int?>(null)
        val topInset = mutableStateOf(80)
        compose.setContent { IYangoThemePreview { Box(Modifier.size(393.dp, 440.dp).padding(top = 40.dp)) {
            ScheduleWeekGrid(data, com.gfs.iyango.core.model.ScheduleDisplaySettings(rowHeightDp = 50),
                hasWallpaper = false, edgeToEdge = true, topInset = topInset.value.dp, stagedSpan = staged.value,
                onGroupClick = {}, onDragStart = { true }, onDrop = { drops += it })
        } } }
        val scroll = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 120f) }
        val viewport = scroll.fetchSemanticsNode().boundsInRoot
        assertEquals(40f, viewport.top, .5f)
        val headerBottom = viewport.top + 80f
        fun dropAt(rootY: Float) {
            val source = compose.onNodeWithTag("schedule-course-20").fetchSemanticsNode().boundsInRoot.center
            scroll.performTouchInput {
                down(source - viewport.topLeft); advanceEventTime(700); moveBy(androidx.compose.ui.geometry.Offset.Zero)
                moveTo(androidx.compose.ui.geometry.Offset(source.x - viewport.left, rootY - viewport.top)); up()
            }
        }
        dropAt(headerBottom - 1f)
        compose.runOnIdle { assertEquals(listOf<ScheduleDropSlot?>(null), drops) }
        dropAt(headerBottom + 30f)
        compose.runOnIdle { assertEquals(ScheduleDropSlot(1, 2, 3), drops.last()); staged.value = 2 }
        scroll.performTouchInput { click(androidx.compose.ui.geometry.Offset(80f, headerBottom - 1f - viewport.top)) }
        compose.runOnIdle { assertNull("Staged taps also reject the header band", drops.last()) }
        scroll.performTouchInput { click(androidx.compose.ui.geometry.Offset(80f, headerBottom + 30f - viewport.top)) }
        compose.runOnIdle { assertEquals(ScheduleDropSlot(1, 3, 4), drops.last()); topInset.value = 120 }
        // A taller fixed title excludes a formerly visible point without changing week/span.
        scroll.performTouchInput { click(androidx.compose.ui.geometry.Offset(80f, headerBottom + 30f - viewport.top)) }
        compose.runOnIdle { assertNull("Staged placement observes a newly measured title inset", drops.last()) }
        scroll.performTouchInput { click(androidx.compose.ui.geometry.Offset(80f, headerBottom + 70f - viewport.top)) }
        compose.runOnIdle { assertEquals(ScheduleDropSlot(1, 3, 4), drops.last()) }
    }

    @Test fun `schedule title opens picker with current week and keeps add and all menu actions`() {
        val start = LocalDate.of(2026, 10, 26)
        val data = WeekScheduleData("课表", semester = Semester(name = "2026 秋季", startDate = start.minusWeeks(7)),
            totalWeeks = 20, currentWeek = 7, displayedWeek = 8, flipDirection = WeekFlipDirection.JUMP,
            weekStart = start, today = start.minusWeeks(1), now = start.atTime(9, 0), sectionCount = 11,
            cells = emptyList(), timeSlots = emptyList(), courses = emptyList(), weekDayCounts = emptyList())
        var picked = 0
        var added = 0
        var openedAi = 0
        val state = mutableStateOf(ScheduleUiState.Ready(data))
        compose.setContent { IYangoThemePreview { Box(Modifier.size(393.dp, 700.dp)) {
            ScheduleContent(state.value, onPreviousWeek = {}, onNextWeek = {
                state.value = ScheduleUiState.Ready(data.copy(displayedWeek = 9, weekStart = start.plusWeeks(1)))
            },
                onPickWeek = { picked = it }, onBackToCurrentWeek = { picked = 7 }, onRetry = {},
                onDeleteCourse = { _, _, _, _ -> }, onApplyAdjustment = { _, _, _, _, _, _, _, _ -> },
                onAddCourse = { added++ }, onOpenAiImport = { openedAi++ })
        } } }
        compose.onNodeWithContentDescription("选择周次").assertDoesNotExist()
        compose.onNodeWithText("2026/10/19").assertIsDisplayed()
        compose.onNodeWithText("第8周 周一").performClick()
        compose.onNodeWithText("选择学期与周次").assertIsDisplayed()
        compose.onNodeWithText("回到本周").performClick()
        compose.runOnIdle { assertEquals(7, picked) }
        compose.onNodeWithContentDescription("AI 拍图导入").assertDoesNotExist()
        compose.onNodeWithText("AI").assertDoesNotExist()
        compose.onNodeWithContentDescription("添加课程").performClick()
        compose.runOnIdle { assertEquals(1, added) }
        compose.onNodeWithContentDescription("更多操作").performClick()
        listOf("Ai导入", "显示设置", "上一周", "下一周", "回到本周", "恢复本周调整", "临时整天课表").forEach {
            compose.onNodeWithText(it).assertExists()
        }
        compose.onNodeWithText("Ai导入").performClick()
        compose.runOnIdle { assertEquals(1, openedAi) }
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("下一周").performClick()
        compose.onNodeWithText("第9周 周一").assertIsDisplayed()
    }
}
