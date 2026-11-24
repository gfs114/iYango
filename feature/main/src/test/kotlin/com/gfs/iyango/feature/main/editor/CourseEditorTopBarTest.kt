package com.gfs.iyango.feature.main.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.domain.AppSettingsPort
import com.gfs.iyango.core.domain.ScheduleDataPort
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.DisplaySettingsBundle
import com.gfs.iyango.core.model.Timetable
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual form and navigation clicks exercise the relocated submit action. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CourseEditorTopBarTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `top save submits the edited form once and leaves after success`() {
        val writes = mutableListOf<List<Course>>()
        val model = model(writes)
        var backs = 0
        compose.setContent { IYangoThemePreview { CourseEditorScreen(onBack = { backs++ }, viewModel = model) } }

        compose.onNodeWithText("课程名称（必填）").performTextInput("测试课程")
        compose.onNodeWithText("共 1 条上课安排").assertIsDisplayed()
        compose.onAllNodesWithText("保存").assertCountEquals(1)
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil { writes.size == 1 && backs == 1 }
        compose.runOnIdle {
            assertEquals("测试课程", writes.single().single().name)
            assertEquals(1, backs)
        }
    }

    @Test fun `undo redo and dirty back keep the original form and leave guard`() {
        val writes = mutableListOf<List<Course>>()
        val model = model(writes)
        var backs = 0
        compose.setContent { IYangoThemePreview { CourseEditorScreen(onBack = { backs++ }, viewModel = model) } }

        compose.onNodeWithText("课程名称（必填）").performTextInput("未保存课程")
        compose.onNodeWithText("撤销").performClick()
        compose.runOnIdle { assertEquals("", model.form.value.name) }
        compose.onNodeWithText("重做").performClick()
        compose.runOnIdle { assertEquals("未保存课程", model.form.value.name) }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("放弃修改？").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        compose.runOnIdle {
            assertEquals(0, backs)
            assertTrue(writes.isEmpty())
            assertEquals("未保存课程", model.form.value.name)
        }
    }

    @Test fun `top save preserves conflict confirmation before any write`() {
        val writes = mutableListOf<List<Course>>()
        val existing = Course(id = 2, timetableId = 1, name = "已有课程", dayOfWeek = 1,
            startSection = 1, endSection = 2, startWeek = 1, endWeek = 20)
        val model = model(writes, listOf(existing))
        var backs = 0
        compose.setContent { IYangoThemePreview { CourseEditorScreen(onBack = { backs++ }, viewModel = model) } }

        compose.onNodeWithText("课程名称（必填）").performTextInput("冲突课程")
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText("冲突提示").assertIsDisplayed()
        compose.runOnIdle { assertTrue(writes.isEmpty()) }
        compose.onNodeWithText("继续添加").performClick()
        compose.waitUntil { writes.size == 1 && backs == 1 }
        compose.runOnIdle { assertEquals("冲突课程", writes.single().single().name) }
    }

    @Test fun `editor body gaps cover the previous page during back preview`() {
        val model = model(mutableListOf())
        val previousPage = Color.Magenta
        var expectedBackground = Color.Unspecified
        val input = DirectNavigationEventInput()
        compose.setContent {
            val owner = rememberNavigationEventDispatcherOwner(parent = null)
            DisposableEffect(owner) {
                owner.navigationEventDispatcher.addInput(input)
                onDispose { owner.navigationEventDispatcher.removeInput(input) }
            }
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                IYangoThemePreview {
                    expectedBackground = MaterialTheme.colorScheme.background
                    Box(Modifier.size(393.dp, 700.dp).background(previousPage).testTag("editor-frame")) {
                        CourseEditorScreen(onBack = {}, viewModel = model)
                    }
                }
            }
        }
        // 进入脏状态后 EditorDiscardBackHandler 才会启用，预览层才接收真实手势事件。
        compose.onNodeWithText("课程名称（必填）").performTextInput("脏草稿")

        val summary = compose.onNodeWithText("共 1 条上课安排").fetchSemanticsNode().boundsInRoot
        val gapY = (summary.bottom + 6).toInt()
        val idlePixels = compose.onNodeWithTag("editor-frame").captureToImage().toPixelMap()
        assertEquals("The previous page must not show through a form gap when idle",
            expectedBackground.toArgb(), idlePixels[idlePixels.width / 2, gapY].toArgb())

        compose.runOnIdle {
            input.backStarted(NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT, progress = 0f))
            input.backProgressed(NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT, progress = 0.6f))
        }
        val summaryDuring = compose.onNodeWithText("共 1 条上课安排").fetchSemanticsNode().boundsInRoot
        val gapYDuring = (summaryDuring.bottom + 6).toInt()
        val pixels = compose.onNodeWithTag("editor-frame").captureToImage().toPixelMap()
        assertEquals("The previous page must not show through a form gap at progress=0.6",
            expectedBackground.toArgb(), pixels[pixels.width / 2, gapYDuring].toArgb())
        assertEquals("Preview must reveal the previous page outside the scaled editor",
            previousPage.toArgb(), pixels[2, gapYDuring].toArgb())
    }

    @Test fun `form scrolls behind the fixed title while undo redo stay above the bottom inset`() {
        val model = model(mutableListOf())
        compose.setContent { IYangoThemePreview {
            Box(Modifier.size(393.dp, 700.dp)) { CourseEditorScreen(onBack = {}, viewModel = model) }
        } }
        val title = compose.onNodeWithText("新建课程")
        val field = compose.onNodeWithText("课程名称（必填）")
        val titleBefore = title.fetchSemanticsNode().boundsInRoot
        val undoBefore = compose.onNodeWithText("撤销").fetchSemanticsNode().boundsInRoot
        val fieldBefore = field.fetchSemanticsNode().boundsInRoot
        assertTrue("The initial form must avoid the title", fieldBefore.top > titleBefore.bottom)
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("共 1 条上课安排")))
            .performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
                scroll(0f, fieldBefore.top - titleBefore.top - 4f)
            }
        val fieldAfter = field.fetchSemanticsNode().boundsInRoot
        assertTrue("The actual form must enter the fixed header's vertical area", fieldAfter.top < titleBefore.bottom)
        assertTrue("The field remains inside the viewport behind the header", fieldAfter.bottom > titleBefore.top)
        assertEquals(titleBefore, title.fetchSemanticsNode().boundsInRoot)
        assertEquals(undoBefore, compose.onNodeWithText("撤销").fetchSemanticsNode().boundsInRoot)
        compose.onAllNodesWithText("保存").assertCountEquals(1)
    }

    private fun model(writes: MutableList<List<Course>>, existing: List<Course> = emptyList()) = CourseEditorViewModel(
        proxy<TimetableRepositoryPort> { name, _ ->
            when (name) {
                "observeCurrent" -> flowOf(Timetable(id = 1, name = "测试课表", source = "LOCAL"))
                else -> error("Unexpected timetable call: $name")
            }
        },
        proxy<ScheduleDataPort> { name, args ->
            when (name) {
                "observeCourses" -> flowOf(existing)
                "saveCourses" -> {
                    @Suppress("UNCHECKED_CAST")
                    val courses = args!![0] as List<Course>
                    writes += courses
                    courses.map { 1L }
                }
                else -> error("Unexpected data call: $name")
            }
        },
        proxy<AppSettingsPort> { name, _ ->
            if (name == "getDisplaySettings") flowOf(DisplaySettingsBundle())
            else error("Unexpected settings call: $name")
        },
        SavedStateHandle(),
    )

    private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args)
        } as T
}
