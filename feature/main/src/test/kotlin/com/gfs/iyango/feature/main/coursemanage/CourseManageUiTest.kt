package com.gfs.iyango.feature.main.coursemanage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.domain.ScheduleDataPort
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.Timetable
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CourseManageUiTest {
    @get:Rule val compose = createComposeRule()
    private val course = Course(id = 1, timetableId = 1, name = "数学", groupKey = "math", teacher = "张老师", endWeek = 16)

    private fun show(width: Int = 393, fontScale: Float = 1f, onEdit: (Long) -> Unit = {}): CourseManageViewModel {
        val model = CourseManageViewModel(
            proxy<TimetableRepositoryPort> { flowOf(Timetable(id = 1, name = "本学期")) },
            proxy<ScheduleDataPort> { name -> when (name) {
                "observeCourses" -> flowOf(listOf(course, course.copy(id = 2, dayOfWeek = 3, classroom = "实验楼", groupKey = "separate-sync-key"),
                    course.copy(id = 3, name = "英语", groupKey = "english")))
                "observeSemester" -> flowOf(null as Semester?)
                else -> error(name)
            } },
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                IYangoThemePreview { Box(Modifier.size(width.dp, 850.dp)) {
                    CourseManageScreen(onBack = {}, onEditCourse = onEdit, viewModel = model)
                } }
            }
        }
        return model
    }

    @Test fun `normal phone uses two columns and grouped sheet edits chosen arrangement`() {
        var edited = 0L
        show(onEdit = { edited = it })
        val math = compose.onNodeWithText("数学").fetchSemanticsNode().boundsInRoot
        val english = compose.onNodeWithText("英语").fetchSemanticsNode().boundsInRoot
        assertEquals(math.top, english.top, 1f)
        assertTrue(math.right < english.left)
        compose.onAllNodesWithText("张老师").assertCountEquals(2)
        compose.onNodeWithText("实验楼", substring = true).assertDoesNotExist()
        compose.onNodeWithText("周三 第 1 节").assertDoesNotExist()
        compose.onNodeWithText("数学").performClick()
        compose.onNodeWithText("选择要编辑的时段", substring = true).assertDoesNotExist()
        compose.onAllNodesWithText("编辑此时段").assertCountEquals(2)
        compose.onNodeWithText("实验楼", substring = true).assertIsDisplayed()
        val week = compose.onAllNodesWithText("第 1–16 周", useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInRoot
        val time = compose.onNodeWithText("周一 第 1 节", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Week label must appear above weekday and time", week.bottom <= time.top)
        compose.onNodeWithText("周三 第 1 节").performClick()
        compose.runOnIdle { assertEquals(2L, edited) }
    }

    @Test fun `large text uses one column and batch actions remain reachable`() {
        val model = show(width = 320, fontScale = 1.5f)
        val grid = compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("搜索课程 / 教师 / 教室")))
        grid.performScrollToNode(hasText("数学"))
        compose.onNodeWithText("数学").performTouchInput { longClick() }
        compose.onNodeWithText("已选 1 门").assertIsDisplayed()
        compose.onNodeWithText("复制课程").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("删除课程").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { assertEquals(setOf(1L, 2L), (model.state.value as CourseManageUiState.Ready).selectedIds) }
        compose.onNodeWithText("完成").performClick()
        grid.performScrollToNode(hasText("英语"))
        val english = compose.onNodeWithText("英语").fetchSemanticsNode().boundsInRoot
        assertTrue(english.right <= 320f)
        compose.onNodeWithText("英语").assertIsDisplayed()
    }

    @Test fun `filter panel updates results and reset restores courses`() {
        show()
        compose.onNodeWithText("周次 / 星期").performClick()
        compose.onNodeWithText("周三").performClick()
        val result = compose.onNodeWithText("查看 1 门课程")
        result.performScrollTo().performClick()
        compose.onNodeWithText("找到 1 门课程").assertIsDisplayed()
        compose.onNodeWithText("英语").assertDoesNotExist()
        compose.onNodeWithText("重置筛选").performClick()
        compose.onNodeWithText("英语").assertExists()
    }

    @Test fun `single arrangement also opens course details before editing`() {
        var edited = 0L
        show(onEdit = { edited = it })
        compose.onNodeWithText("英语").performClick()
        compose.onNodeWithText("选择要编辑的时段", substring = true).assertDoesNotExist()
        compose.onNodeWithText("编辑此时段").assertIsDisplayed()
        assertEquals(0L, edited)
        compose.onNodeWithText("编辑此时段").performClick()
        compose.runOnIdle { assertEquals(3L, edited) }
    }

    private inline fun <reified T> proxy(crossinline call: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> call(method.name) } as T
}
