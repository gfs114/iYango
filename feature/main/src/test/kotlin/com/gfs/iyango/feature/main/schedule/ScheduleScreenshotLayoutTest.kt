package com.gfs.iyango.feature.main.schedule

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.domain.TimeRailRow
import com.gfs.iyango.core.model.*
import java.io.File
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual Compose paragraphs and clipping bounds at the screenshot's phone width. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScheduleScreenshotLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val start = LocalDate.of(2026, 10, 26)
    private val settings = ScheduleDisplaySettings(rowHeightDp = 50)
    private val output = File("../../build/diagnostics/schedule-screenshot-fix-20261027").apply { mkdirs() }
    private fun data(): WeekScheduleData {
        val courses = listOf(
            Course(id = 1, name = "单片机原理与应用", dayOfWeek = 2, startSection = 1, endSection = 2, classroom = "教研楼A301"),
            Course(id = 2, name = "计算机网络基础实验", dayOfWeek = 3, startSection = 3, endSection = 3, classroom = "实验楼B405"),
            Course(id = 3, name = "最后一节", dayOfWeek = 4, startSection = 11, endSection = 11, classroom = "A101"),
        )
        return WeekScheduleData("课表", timetableId = 1, semester = Semester(startDate = start),
            totalWeeks = 16, currentWeek = 8, displayedWeek = 8, flipDirection = WeekFlipDirection.JUMP,
            weekStart = start, today = start.plusDays(1), now = start.atTime(13, 8), sectionCount = 11,
            cells = courses.map { WeekScheduleData.GridCellGroup(it.dayOfWeek, it.startSection,
                listOf(WeekScheduleData.GridBlock(it, null, it.endSection - it.startSection + 1, false))) },
            timeSlots = emptyList(), courses = courses, weekDayCounts = emptyList(),
            timeRail = listOf(TimeRailRow(1, "1", "08:10", "08:55"),
                TimeRailRow(3, "3", southStart = "10:05", northStart = "10:20"),
                TimeRailRow(11, "11", "20:10", "20:55")))
    }

    private fun layout(text: String): Pair<androidx.compose.ui.semantics.SemanticsNode, TextLayoutResult> {
        val node = compose.onNodeWithText(text, useUnmergedTree = true)
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return node.fetchSemanticsNode() to results.single()
    }

    private fun recordImage(name: String) {
        val bitmap = compose.onNodeWithTag("phone").captureToImage().asAndroidBitmap()
        val phase = System.getenv("SCHEDULE_SCREENSHOT_PHASE") ?: "after"
        File(output, "$phase-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `time rail glyph lines stay inside small default and screenshot rows at normal and large font scale`() {
        val scale = mutableStateOf(1f)
        val rowHeight = mutableStateOf(50)
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, scale.value)) {
            IYangoThemePreview(darkTheme = false) { Box(Modifier.size(393.dp, 700.dp).testTag("phone")) {
                ScheduleWeekGrid(data(), settings.copy(rowHeightDp = rowHeight.value), false, readOnly = true, onGroupClick = {})
            } }
        } }
        val failures = mutableListOf<String>()
        File(output, "${System.getenv("SCHEDULE_SCREENSHOT_PHASE") ?: "after"}-text-layout.txt").writeText("")
        for (height in listOf(50, 44, 56)) for (fontScale in listOf(1f, 1.3f)) {
            compose.runOnIdle { scale.value = fontScale; rowHeight.value = height }
            recordImage("rail-$height-scale-$fontScale")
            val latticeTop = compose.onNodeWithTag("schedule-lattice-8").fetchSemanticsNode().boundsInRoot.top
            listOf("1" to 1, "08:10" to 1, "08:55" to 1, "3" to 3, "南 10:05" to 3, "北 10:20" to 3).forEach { (text, section) ->
                val (node, result) = layout(text)
                val top = node.positionInRoot.y + result.getLineTop(0)
                val bottom = node.positionInRoot.y + result.getLineBottom(0)
                val rowTop = latticeTop + (section - 1) * height
                val rowBottom = rowTop + height
                val evidence = "$height $fontScale $text: line=[$top,$bottom] row=[$rowTop,$rowBottom] measured=${result.size} lineRight=${result.getLineRight(0)} visualOverflow=${result.hasVisualOverflow}"
                File(output, "${System.getenv("SCHEDULE_SCREENSHOT_PHASE") ?: "after"}-text-layout.txt").appendText("$evidence\n")
                // hasVisualOverflow also flags subpixel paragraph-height rounding;
                // assert actual rendered line bounds and the available clip instead.
                if (top < rowTop - .5f || bottom > rowBottom + .5f || result.getLineBottom(0) > result.size.height + .5f ||
                    result.getLineRight(0) > result.size.width + .5f) failures += evidence
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        listOf(DayLabels.shortLabel(1), "10/26").forEach { text ->
            val (node, result) = layout(text)
            val headerBottom = compose.onNodeWithTag("schedule-lattice-8").fetchSemanticsNode().boundsInRoot.top
            assertTrue("header line stays above lattice: $text", node.positionInRoot.y + result.getLineBottom(0) <= headerBottom + .5f)
            assertTrue("header line fully measured: $text", result.getLineBottom(0) <= result.size.height + .5f)
        }
    }

    @Test fun `phone cards reserve visible classroom lines below long titles including a single section`() {
        val scale = mutableStateOf(1f)
        val display = mutableStateOf(settings)
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, scale.value)) {
            IYangoThemePreview(darkTheme = false) { Box(Modifier.size(393.dp, 700.dp).testTag("phone")) {
                ScheduleWeekGrid(data(), display.value, false, readOnly = true, onGroupClick = {})
            } }
        } }
        assertEquals("settings preview keeps its container inset", 24f,
            compose.onNodeWithTag("schedule-lattice-8").fetchSemanticsNode().boundsInRoot.left, .5f)
        for (height in listOf(44, 50, 56)) for (fontScale in listOf(1f, 1.3f)) {
            compose.runOnIdle { scale.value = fontScale; display.value = display.value.copy(rowHeightDp = height) }
            listOf("单片机原理与应用" to "@教研楼A301", "计算机网络基础实验" to "@实验楼B405").forEach { (name, room) ->
                val (titleNode, titleLayout) = layout(name)
                val (roomNode, roomLayout) = layout(room)
                assertTrue("room must follow title: $fontScale $name", roomNode.positionInRoot.y >= titleNode.positionInRoot.y + titleLayout.getLineBottom(titleLayout.lineCount - 1) - .5f)
                assertFalse("classroom glyphs clipped: $fontScale $room", roomLayout.hasVisualOverflow && !roomLayout.isLineEllipsized(roomLayout.lineCount - 1))
                assertTrue("classroom has a full first line: $fontScale $room", roomLayout.size.height >= roomLayout.getLineBottom(0) - .5f)
                val cardId = if (name == "单片机原理与应用") 1 else 2
                val card = compose.onNodeWithTag("schedule-course-$cardId").fetchSemanticsNode().boundsInRoot
                assertTrue("all classroom lines fit card: $fontScale $room", roomNode.positionInRoot.y + roomLayout.getLineBottom(roomLayout.lineCount - 1) <= card.bottom - 3.5f)
            }
            recordImage("classrooms-$height-scale-$fontScale")
        }
        compose.runOnIdle { display.value = display.value.copy(showClassroom = false) }
        compose.onNodeWithText("@教研楼A301", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun `main grid fills intermediate viewport and last section scrolls completely above navigation reserve`() {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) {
            IYangoThemePreview(darkTheme = false) { Box(Modifier.size(393.dp, 500.dp).testTag("phone")) {
                ScheduleWeeks(ScheduleUiState.Ready(data()), settings, false, bottomInset = 84.dp, active = true,
                    onPick = {}, onPagerWeek = { _, _, _ -> }, onGroupClick = { _, _ -> }, onInteractionLocked = {}, onConfirmMove = { null })
            } }
        } }
        val lattice = compose.onNodeWithTag("schedule-lattice-8")
        val bounds = lattice.fetchSemanticsNode().boundsInRoot
        assertEquals("main grid left edge", 0f, bounds.left, .5f)
        assertEquals("main grid right edge", 393f, bounds.right, .5f)
        lattice.performTouchInput { swipeUp() }
        val (node, result) = layout("20:55")
        assertTrue("last clock line above navigation", node.positionInRoot.y + result.getLineBottom(0) <= 416.5f)
        assertTrue("last clock line fully measured", result.size.height >= result.getLineBottom(0) - .5f)
        recordImage("last-section-scrolled")
    }

    @Test fun `main page retains its operation header and adaptive grid extends beneath navigation`() {
        val scale = mutableStateOf(1f)
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, scale.value)) {
            IYangoThemePreview(darkTheme = false) { Box(Modifier.size(393.dp, 852.dp).testTag("phone")) {
                ScheduleContent(ScheduleUiState.Ready(data()), bottomPadding = 84.dp,
                    display = ScheduleDisplaySettings(), onPreviousWeek = {}, onNextWeek = {}, onPickWeek = {},
                    onBackToCurrentWeek = {}, onRetry = {}, onDeleteCourse = { _, _, _, _ -> },
                    onApplyAdjustment = { _, _, _, _, _, _, _, _ -> })
            } }
        } }
        for (fontScale in listOf(1f, 1.3f)) {
            compose.runOnIdle { scale.value = fontScale }
            compose.onNodeWithContentDescription("添加课程").assertIsDisplayed()
            val lattice = compose.onNodeWithTag("schedule-lattice-8").fetchSemanticsNode().boundsInRoot
            assertEquals(0f, lattice.left, .5f)
            assertEquals(393f, lattice.right, .5f)
            assertEquals("grid extends beneath navigation", 852f, lattice.bottom, 1f)
            val roomText = "@实验楼B405"
            val (roomNode, roomLayout) = layout(roomText)
            assertFalse("room number B405 must not be ellipsized at $fontScale", roomLayout.isLineEllipsized(roomLayout.lineCount - 1))
            assertEquals("all room characters visible at $fontScale", roomText.length, roomLayout.getLineEnd(roomLayout.lineCount - 1, visibleEnd = true))
            val card = compose.onNodeWithTag("schedule-course-2").fetchSemanticsNode().boundsInRoot
            assertTrue("B405 line remains inside card at $fontScale", roomNode.positionInRoot.y + roomLayout.getLineBottom(roomLayout.lineCount - 1) <= card.bottom - 3.5f)
            recordImage("main-page-scale-$fontScale")
        }
    }
}
