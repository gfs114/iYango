package com.gfs.iyango.feature.main.grades

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.model.GradeRecord
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 浮层互斥与返回优先级（方案 §13.1 U15/U16/U18）：
 * 说明面板返回只关面板；非活动标签无浮层无返回拦截；账号变化关闭旧浮层。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers="w393dp-h852dp-mdpi")
class GradesOverlayUiTest {
    @get:Rule val compose=createComposeRule()

    private val rows = (1..4).map { index ->
        GradeRecord(id = index.toLong(), courseName = "课程 $index", scoreText = "$index",
            scoreValue = index.toDouble(), credit = 2.0)
    }

    @Test fun `statistics info sheet closes without leaving selection mode`() {
        val state = mutableStateOf(
            GradesUiState(
                connectionId = "a",
                accountKey = "account",
                records = rows,
                selectionMode = true,
                selectedRecordIds = setOf(1L, 4L),
            ),
        )
        lateinit var dispatcher: androidx.activity.OnBackPressedDispatcher
        compose.setContent { IYangoThemePreview {
            dispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            GradesPageLayout(state.value, 0.dp, {}, {},
                onExitSelection = { state.value = state.value.copy(selectionMode = false) })
        } }
        // 说明面板入口在固定计算栏内。
        compose.onNodeWithTag("grades-statistics-info").performClick()
        compose.onNodeWithText("统计说明").assertIsDisplayed()
        compose.onNodeWithText("纳入 2 条 排除 0 条").assertIsDisplayed()
        compose.onAllNodesWithText("课程 1").onLast().assertIsDisplayed()
        compose.onAllNodesWithText("课程 4").onLast().assertIsDisplayed()
        compose.runOnIdle { assertFalse("浮层打开时页面返回被禁用", dispatcher.hasEnabledCallbacks()) }
        compose.onNodeWithText("关闭").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("统计说明").assertDoesNotExist()
        compose.onNodeWithText("纳入 2 条 排除 0 条").assertDoesNotExist()
        compose.runOnIdle { assertTrue("关闭浮层后仍是自选模式", dispatcher.hasEnabledCallbacks()) }
    }

    @Test fun `inactive main tab hides the bar and the term sheet`() {
        val active = mutableStateOf(false)
        val state = mutableStateOf(
            GradesUiState(connectionId = "a", accountKey = "account", records = rows, selectionMode = true),
        )
        lateinit var dispatcher: androidx.activity.OnBackPressedDispatcher
        compose.setContent { IYangoThemePreview {
            dispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            CompositionLocalProvider(com.gfs.iyango.feature.main.LocalMainTabActive provides active.value) {
                GradesPageLayout(state.value, 0.dp, {})
            }
        } }
        compose.onNodeWithTag("grades-selection-bar").assertDoesNotExist()
        compose.runOnIdle { assertFalse(dispatcher.hasEnabledCallbacks()) }
        compose.runOnIdle { active.value = true }
        compose.waitForIdle()
        compose.onNodeWithTag("grades-term-selector").performClick()
        compose.runOnIdle { active.value = false }
        compose.waitForIdle()
        compose.onNodeWithText("选择学期").assertDoesNotExist()
    }

    @Test fun `account change closes stale overlays`() {
        val state = mutableStateOf(
            GradesUiState(connectionId = "a", accountKey = "account", records = rows, selectionMode = true),
        )
        compose.setContent { IYangoThemePreview { GradesPageLayout(state.value, 0.dp, {}) } }
        compose.onNodeWithTag("grades-term-selector").performClick()
        compose.onNodeWithText("选择学期").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(accountKey = "other") }
        compose.waitForIdle()
        compose.onNodeWithText("选择学期").assertDoesNotExist()
    }
}
