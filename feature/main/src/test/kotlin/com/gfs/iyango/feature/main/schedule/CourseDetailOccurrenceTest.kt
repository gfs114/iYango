package com.gfs.iyango.feature.main.schedule

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled
import com.gfs.iyango.core.domain.CourseOccurrence
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.feature.main.RetainedMainTabs
import com.gfs.iyango.feature.main.coursedetail.CourseDetailDialog
import com.gfs.iyango.feature.main.coursedetail.CourseDetailSource
import com.gfs.iyango.feature.main.coursedetail.CourseDetailSources
import com.gfs.iyango.feature.main.coursedetail.LocalCourseDetailSources
import com.gfs.iyango.feature.main.coursedetail.courseDetailViewport
import com.gfs.iyango.feature.main.coursedetail.rememberCourseDetailSource
import java.time.LocalDate
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CourseDetailOccurrenceTest {
    @get:Rule val compose = createComposeRule()
    private val date = LocalDate.of(2026, 10, 5)
    private val course = Course(id = 7, name = "Same course")
    private val first = CourseOccurrence("A", course, date, 480, 540, false, date.minusWeeks(1), true)

    @Test fun `outside and repeated back dismiss once and disabling motion releases the exiting window`() {
        var enabled by mutableStateOf(true)
        var list by mutableStateOf(listOf(course))
        var closes = 0
        var edits = 0
        compose.setContent {
            CompositionLocalProvider(LocalMotionEnabled provides enabled) {
                CourseDetailDialog(list, emptyList(), 1,
                    onEdit = { edits++ }, onCopy = {}, onDelete = { _, _ -> }, onAdjust = {},
                    onDismiss = { closes++ })
            }
        }
        compose.onNodeWithText("编辑").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("courseDetailScrim").performTouchInput { click(Offset(1f, 1f)) }
        repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithText("编辑").assertDoesNotExist()
        compose.runOnIdle {
            @Suppress("DEPRECATION")
            org.robolectric.shadows.ShadowDialog.getLatestDialog().onBackPressed()
            assertEquals(0, closes)
            list = emptyList()
            enabled = false
        }
        repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, closes); assertEquals(0, edits) }
    }

    @Test fun `clicked title moves from its own card and detached source uses ordinary modal exit`() {
        var showCard by mutableStateOf(true)
        var closes = 0
        compose.setContent {
            val sources = remember { CourseDetailSources() }
            var selected by remember { mutableStateOf<CourseDetailSource?>(null) }
            CompositionLocalProvider(LocalCourseDetailSources provides sources, LocalMotionEnabled provides true,
                LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.fillMaxSize()) {
                    if (showCard) {
                        val source = rememberCourseDetailSource(course, first.occurrenceId,
                            MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer,
                            MaterialTheme.typography.bodyMedium, 12.dp)
                        Box(Modifier.size(136.dp, 64.dp).then(source.cardModifier)
                            .testTag("clickedCard").clickable { source.select(); selected = sources.takeClicked() }
                            .padding(8.dp)) { Text(course.name, source.titleModifier) }
                    }
                    if (selected != null) CourseDetailDialog(listOf(course), emptyList(), 1,
                        occurrenceEntries = listOf(first), source = selected,
                        onEdit = {}, onCopy = {}, onDelete = { _, _ -> }, onAdjust = {},
                        onDismiss = { selected = null; closes++ })
                }
            }
        }
        compose.mainClock.autoAdvance = false
        val clicked = compose.onNodeWithTag("clickedCard").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("clickedCard").performClick()
        repeat(3) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        fun movingTitle() = compose.onNodeWithTag("courseDetailMovingTitle", useUnmergedTree = true)
        val early = movingTitle().getUnclippedBoundsInRoot()
        assertTrue(early.left >= clicked.left - 24.dp)
        compose.mainClock.advanceTimeBy(64)
        val later = movingTitle().getUnclippedBoundsInRoot()
        assertTrue(later.top > early.top)
        compose.mainClock.advanceTimeBy(240)
        movingTitle().assertDoesNotExist()
        compose.onNodeWithText("编辑").assertIsDisplayed()
        compose.onNodeWithTag("courseDetailScrim").performTouchInput { click(Offset(1f, 1f)) }
        // The Android window and the host composition each receive a frame before the return tween starts.
        var returnFrames = 0
        while (compose.onAllNodesWithTag("courseDetailMovingTitle", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty() && returnFrames++ < 9) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        movingTitle().assertExists()
        compose.runOnIdle { showCard = false }
        repeat(3) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        movingTitle().assertDoesNotExist()
        compose.onNode(isDialog()).assertExists()
        compose.mainClock.advanceTimeBy(200)
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, closes) }
    }

    @Test fun `turning motion off during a button page scroll snaps to its target`() {
        var enabled by mutableStateOf(true)
        val other = course.copy(id = 8, name = "Other")
        compose.setContent {
            CompositionLocalProvider(LocalMotionEnabled provides enabled) {
                CourseDetailDialog(listOf(course, other), emptyList(), 1,
                    onEdit = {}, onCopy = {}, onDelete = { _, _ -> }, onAdjust = {}, onDismiss = {})
            }
        }
        val firstX = compose.onNodeWithText(course.name).getUnclippedBoundsInRoot().left
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("下一门").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { enabled = false }
        repeat(3) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithText("第 2 / 2 门").assertIsDisplayed()
        assertEquals(firstX, compose.onNodeWithText(other.name).getUnclippedBoundsInRoot().left)
    }

    @Test fun `a card partly behind fixed chrome falls back and repeated copy transfers ownership once`() {
        var copies = 0
        var dismisses = 0
        var topInset by mutableStateOf(0.dp)
        compose.setContent {
            val sources = remember { CourseDetailSources() }
            var selected by remember { mutableStateOf<CourseDetailSource?>(null) }
            CompositionLocalProvider(LocalCourseDetailSources provides sources) {
                Box(Modifier.fillMaxSize().courseDetailViewport(topInset, 0.dp)) {
                    val source = rememberCourseDetailSource(course, first.occurrenceId,
                        MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface,
                        MaterialTheme.typography.bodyLarge, 12.dp)
                    Box(Modifier.size(136.dp, 64.dp).then(source.cardModifier)
                        .testTag("partlyCoveredCard").clickable {
                            source.select()
                            selected = sources.takeClicked()
                        }) { Text(course.name, source.titleModifier) }
                    if (selected != null) CourseDetailDialog(listOf(course), emptyList(), 1,
                        occurrenceEntries = listOf(first), source = selected,
                        onEdit = {}, onCopy = { copies++ }, onDelete = { _, _ -> }, onAdjust = {},
                        onDismiss = { dismisses++ })
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("partlyCoveredCard").performClick()
        repeat(3) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithTag("courseDetailMovingTitle", useUnmergedTree = true).assertExists()
        compose.runOnIdle { topInset = 32.dp }
        repeat(3) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithTag("courseDetailMovingTitle", useUnmergedTree = true).assertDoesNotExist()
        val copy = compose.onNodeWithText("复制").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { copy(); copy() }
        repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, copies); assertEquals(0, dismisses) }
    }
    @Test fun `equal course rows return selected original occurrence anchor`() {
        val second = first.copy(occurrenceId = "B", anchorDate = date.minusWeeks(2))
        var selected: CourseOccurrence? = null
        compose.setContent {
            RetainedMainTabs(0) {
                CourseDetailDialog(courses = listOf(course, course), occurrenceEntries = listOf(first, second),
                    timeSlots = emptyList(), displayedWeek = 1,
                    onEdit = {}, onCopy = {}, onDelete = { _, _ -> }, onAdjust = {}, onDismiss = {},
                    onAdjustEntry = { _, occurrence -> selected = occurrence })
            }
        }
        compose.onNodeWithContentDescription("下一门").performClick()
        compose.onNodeWithText("调整课程").performClick()
        compose.runOnIdle { assertEquals(second, selected) }
    }
    @Test fun `temporary source allows copy and explicit restore but blocks source mutations`() {
        var restored: CourseOccurrence? = null
        val temporary = first.copy(isTemporary = true)
        compose.setContent {
            RetainedMainTabs(0) {
                CourseDetailDialog(courses = listOf(course), occurrenceEntries = listOf(temporary),
                    timeSlots = emptyList(), displayedWeek = 1,
                    onEdit = {}, onCopy = {}, onDelete = { _, _ -> }, onAdjust = {}, onDismiss = {},
                    onRestoreOccurrence = { restored = it })
            }
        }
        compose.onNodeWithText("编辑").assertIsNotEnabled()
        compose.onNodeWithText("删除").assertIsNotEnabled()
        compose.onNodeWithText("复制").assertIsEnabled()
        compose.onNodeWithText("恢复该日课表").performClick()
        compose.runOnIdle { assertEquals(temporary, restored) }
    }
    @Test fun `ghost retains definition actions but has no occurrence adjustment or cancellation`() {
        compose.setContent {
            RetainedMainTabs(0) {
                CourseDetailDialog(courses = listOf(course), occurrenceEntries = listOf(null),
                    timeSlots = emptyList(), displayedWeek = 1,
                    onEdit = {}, onCopy = {}, onDelete = { _, _ -> }, onAdjust = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText("调整课程").assertDoesNotExist()
        compose.onNodeWithText("编辑").assertIsEnabled()
        compose.onNodeWithText("复制").assertIsEnabled()
        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("仅取消本周这次安排").assertDoesNotExist()
        compose.onNodeWithText("删除这条安排的所有周次").assertIsDisplayed()
        compose.onNodeWithText("删除整门课的所有安排").assertIsDisplayed()
    }

    @Test fun `swipe binds adjust and copy actions to visible page`() {
        val secondCourse = course.copy(id = 8, name = "Other course")
        val second = first.copy(occurrenceId = "B", course = secondCourse, anchorDate = date.minusWeeks(2))
        var copied: Course? = null
        var adjusted: CourseOccurrence? = null
        var request by mutableStateOf(0)
        compose.setContent {
            RetainedMainTabs(0) {
                key(request) {
                CourseDetailDialog(listOf(course, secondCourse), emptyList(), 1,
                    occurrenceEntries = listOf(first, second), onEdit = {}, onCopy = { copied = it },
                    onDelete = { _, _ -> }, onAdjust = {}, onDismiss = {},
                    onAdjustEntry = { _, entry -> adjusted = entry })
                }
            }
        }
        compose.onNodeWithTag("courseDetailPager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("复制").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { request++ }
        compose.onNodeWithTag("courseDetailPager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("调整课程").performClick()
        compose.runOnIdle { assertEquals(secondCourse, copied); assertEquals(second, adjusted) }
    }
    @Test fun `swipe then delete preserves selected occurrence through confirmation`() {
        val second = first.copy(occurrenceId = "B", anchorDate = date.minusWeeks(2))
        var deleted: CourseOccurrence? = null
        compose.setContent {
            RetainedMainTabs(0) {
                CourseDetailDialog(listOf(course, course), emptyList(), 1,
                    occurrenceEntries = listOf(first, second), onEdit = {}, onCopy = {},
                    onDelete = { _, _ -> }, onAdjust = {}, onDismiss = {},
                    onDeleteEntry = { _, _, entry -> deleted = entry })
            }
        }
        compose.onNodeWithTag("courseDetailPager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("仅取消本周这次安排").performClick()
        // Invoke the retained pager button while confirmation is open to exercise snapshot ownership.
        compose.onNodeWithContentDescription("上一门").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("删除").onLast().performClick()
        compose.runOnIdle { assertEquals(second, deleted) }
    }
    @Test fun `occurrence time wins over unrelated root day slots`() {
        compose.setContent {
            RetainedMainTabs(0) {
                CourseDetailDialog(listOf(course), listOf(com.gfs.iyango.core.model.TimeSlot(section = 1,
                    startTime = "10:00", endTime = "11:00")), 1,
                    occurrenceEntries = listOf(first), onEdit = {}, onCopy = {}, onDelete = { _, _ -> },
                    onAdjust = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText("08:00 - 09:00", substring = true).assertExists()
    }

    @Test fun `swipe binds editor to visible definition`() {
        val other = course.copy(id = 8, name = "Other course")
        var edited: Course? = null
        compose.setContent {
            RetainedMainTabs(0) {
                CourseDetailDialog(listOf(course, other), emptyList(), 1,
                    occurrenceEntries = listOf(first, first.copy(occurrenceId = "B", course = other)),
                    onEdit = { edited = it }, onCopy = {}, onDelete = { _, _ -> },
                    onAdjust = {}, onDismiss = {})
            }
        }
        compose.onNodeWithTag("courseDetailPager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("编辑").performClick()
        compose.runOnIdle { assertEquals(other, edited) }
    }

    @Test fun `temporary unknown occurrence time never borrows known owner slots`() {
        val ownerSlots = listOf(com.gfs.iyango.core.model.TimeSlot(section = 1,
            startTime = "10:00", endTime = "11:00"))
        val unknown = first.copy(startMinute = null, endMinute = null, isTemporary = true)
        compose.setContent {
            RetainedMainTabs(0) {
                CourseDetailDialog(listOf(course.copy(startSection = 1, endSection = 1)), ownerSlots, 1,
                    occurrenceEntries = listOf(unknown), onEdit = {}, onCopy = {}, onDelete = { _, _ -> },
                    onAdjust = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText("时间待定", substring = true).assertExists()
        compose.onNodeWithText("10:00", substring = true).assertDoesNotExist()
    }
    @Test fun `moved occurrence displays current target sections instead of protocol source sections`() {
        val movedCourse = course.copy(startSection = 5, endSection = 6,
            sourceStartSection = 1, sourceEndSection = 2)
        val moved = first.copy(course = movedCourse, startMinute = 840, endMinute = 930, isMoved = true)
        compose.setContent {
            RetainedMainTabs(0) {
                CourseDetailDialog(listOf(movedCourse), emptyList(), 1,
                    occurrenceEntries = listOf(moved), onEdit = {}, onCopy = {}, onDelete = { _, _ -> },
                    onAdjust = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText("第 5 - 6 节", substring = true).assertExists()
        compose.onNodeWithText("14:00 - 15:30", substring = true).assertExists()
        compose.onNodeWithText("第 1 - 2 节", substring = true).assertDoesNotExist()
    }
    @Test fun `moved dated occurrence displays actual target date`() {
        val dated = course.copy(timeMode = 2, date = date.minusDays(1).toString())
        val moved = first.copy(course = dated, isMoved = true)
        compose.setContent {
            RetainedMainTabs(0) {
                CourseDetailDialog(listOf(dated), emptyList(), 1,
                    occurrenceEntries = listOf(moved), onEdit = {}, onCopy = {}, onDelete = { _, _ -> },
                    onAdjust = {}, onDismiss = {})
            }
        }
        compose.onAllNodesWithText(date.toString(), substring = true).assertCountEquals(2)
        compose.onNodeWithText(date.minusDays(1).toString(), substring = true).assertDoesNotExist()
    }

}
