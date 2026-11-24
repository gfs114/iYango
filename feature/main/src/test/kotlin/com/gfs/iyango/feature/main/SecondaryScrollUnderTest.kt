package com.gfs.iyango.feature.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.domain.ScheduleDataPort
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.TimeSlot
import com.gfs.iyango.feature.main.coursemanage.CourseManageScreen
import com.gfs.iyango.feature.main.coursemanage.CourseManageViewModel
import com.gfs.iyango.feature.main.coursemanage.CourseManageUiState
import com.gfs.iyango.core.model.Timetable
import com.gfs.iyango.feature.main.semester.SemesterManageScreen
import com.gfs.iyango.feature.main.semester.SemesterManageViewModel
import java.lang.reflect.Proxy
import java.time.LocalDate
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real lazy content verifies the scaffold padding is part of the scrolling item area. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SecondaryScrollUnderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `semester cards can pass behind the fixed title and still open their editor`() {
        val model = SemesterManageViewModel(
            proxy<TimetableRepositoryPort> { method ->
                when (method) {
                    "observeCurrent" -> flowOf(Timetable(id = 1, name = "测试课表"))
                    else -> error("Unexpected timetable call: $method")
                }
            },
            proxy<ScheduleDataPort> { method ->
                when (method) {
                    "observeAllSemesters" -> flowOf(List(12) { Semester(id = it + 1L, timetableId = 1, name = "学期${it + 1}", startDate = LocalDate.of(2026, 9, 1)) })
                    "observeTimeSlots" -> flowOf(emptyList<TimeSlot>())
                    else -> error("Unexpected data call: $method")
                }
            },
        )
        compose.setContent { IYangoThemePreview {
            Box(Modifier.size(393.dp, 700.dp)) { SemesterManageScreen(onBack = {}, viewModel = model) }
        } }
        val title = compose.onNodeWithText("学期管理")
        val card = compose.onNodeWithText("学期1")
        card.assertIsDisplayed()
        val titleBefore = title.fetchSemanticsNode().boundsInRoot
        val cardBefore = card.fetchSemanticsNode().boundsInRoot
        assertTrue(cardBefore.top > titleBefore.bottom)
        compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
            scroll(0f, cardBefore.top - titleBefore.top - 4f)
        }
        val cardAfter = card.fetchSemanticsNode().boundsInRoot
        assertTrue("The lazy card must reach the title's vertical area", cardAfter.top < titleBefore.bottom)
        assertTrue(cardAfter.bottom > titleBefore.top)
        assertEquals(titleBefore, title.fetchSemanticsNode().boundsInRoot)
        compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -1000f) }
        compose.onAllNodesWithContentDescription("编辑").onFirst().performClick()
        compose.onNodeWithText("编辑学期").assertIsDisplayed()
    }

    @Test fun `course search filters and batch selection scroll with the rows without losing callbacks`() {
        val model = CourseManageViewModel(
            proxy<TimetableRepositoryPort> { method ->
                if (method == "observeCurrent") flowOf(Timetable(id = 1, name = "测试课表"))
                else error("Unexpected timetable call: $method")
            },
            proxy<ScheduleDataPort> { method ->
                when (method) {
                    "observeSemester" -> flowOf(Semester(id = 1, timetableId = 1, totalWeeks = 20, startDate = LocalDate.of(2026, 9, 1)))
                    "observeCourses" -> flowOf(List(12) { Course(id = it + 1L, timetableId = 1, name = "课程${it + 1}") })
                    else -> error("Unexpected data call: $method")
                }
            },
        )
        compose.setContent { IYangoThemePreview {
            Box(Modifier.size(393.dp, 700.dp)) { CourseManageScreen(onBack = {}, viewModel = model) }
        } }
        val title = compose.onNodeWithText("课程管理")
        val search = compose.onNodeWithText("搜索课程 / 教师 / 教室")
        search.assertIsDisplayed()
        val titleBefore = title.fetchSemanticsNode().boundsInRoot
        val searchBefore = search.fetchSemanticsNode().boundsInRoot
        val list = compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("搜索课程 / 教师 / 教室")))
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, searchBefore.top - titleBefore.top - 4f) }
        assertTrue("The filter header must scroll into the fixed title area", search.fetchSemanticsNode().boundsInRoot.top < titleBefore.bottom)
        assertEquals(titleBefore, title.fetchSemanticsNode().boundsInRoot)
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -1000f) }
        compose.onNodeWithText("全选当前 12 门").performClick()
        compose.onNodeWithText("已选 12 门").assertIsDisplayed()
        compose.runOnIdle { assertEquals(12, (model.state.value as CourseManageUiState.Ready).selectedCount) }
        search.performTextInput("课程12")
        compose.waitUntil { (model.state.value as CourseManageUiState.Ready).rows.size == 1 }
        compose.onNode(hasText("课程12") and hasText("未填写教师")).assertExists()
    }

    private inline fun <reified T> proxy(crossinline call: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> call(method.name) } as T
}
