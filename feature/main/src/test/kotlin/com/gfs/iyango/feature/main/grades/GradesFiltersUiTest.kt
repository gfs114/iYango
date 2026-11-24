package com.gfs.iyango.feature.main.grades

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.model.GradeRecord
import com.gfs.iyango.core.model.GradeSort
import com.gfs.iyango.core.model.GradeTerm
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 学期选择即时生效，不发学校请求；排序本地生效；加载/离线/空列表状态可解释。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers="w393dp-h852dp-mdpi")
class GradesFiltersUiTest {
    @get:Rule val compose=createComposeRule()

    private val rows = (1..6).map { index ->
        GradeRecord(id = index.toLong(), courseName = "课程 $index", scoreText = "$index",
            scoreValue = index.toDouble(), credit = 2.0)
    }
    private val terms = listOf(GradeTerm("2026", "2026 秋季"), GradeTerm("2025", "2025 春季"))

    @Test fun rapidlyReplacingGradeResultsImmediatelyDropsTheOldActions() {
        val state = mutableStateOf(GradesUiState(connectionId = "a", accountKey = "account",
            loggedIn = true, terms = terms, error = "成绩获取失败"))
        compose.setContent { IYangoThemePreview { GradesPageLayout(state.value, 0.dp, {}) } }
        compose.onNodeWithText("知道了").assertExists()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { Snapshot.withMutableSnapshot { state.value = state.value.copy(error = "", querying = true) } }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("知道了").assertDoesNotExist()
        compose.onNodeWithText("正在查询成绩…").assertExists()
        compose.runOnIdle { Snapshot.withMutableSnapshot { state.value = state.value.copy(querying = false, records = rows.take(1)) } }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("正在查询成绩…").assertDoesNotExist()
        compose.onNodeWithText("课程 1").assertExists()
        compose.runOnIdle { Snapshot.withMutableSnapshot {
            state.value = state.value.copy(confirmedRange = "2025", records = rows.drop(1).take(1))
        } }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("课程 1").assertDoesNotExist()
        compose.onNodeWithText("课程 2").assertExists()
    }

    @Test fun `term selection applies immediately without a query button`() {
        val state = mutableStateOf(
            GradesUiState(connectionId = "a", accountKey = "account", loggedIn = true, terms = terms, records = rows),
        )
        var queries = 0
        compose.setContent { IYangoThemePreview {
            GradesPageLayout(state.value, 0.dp, {}, onSelectRange = { value ->
                state.value = state.value.copy(draftRange = value, confirmedRange = value)
            }, onRefresh = { queries++ })
        } }
        compose.onNodeWithTag("grades-term-selector").performClick()
        compose.onNodeWithText("选择学期").assertIsDisplayed()
        // 入口标签与面板选项各一个「全部学期」。
        compose.onAllNodesWithText("全部学期").assertCountEquals(2)
        compose.onNodeWithText("2026 秋季").assertIsDisplayed()
        compose.onNodeWithTag("grades-term-option-2026").performClick()
        compose.runOnIdle { assertEquals("2026", state.value.confirmedRange); assertEquals(0, queries) }
        compose.onNodeWithText("选择学期").assertDoesNotExist()
        compose.onNodeWithText("2026 秋季").assertIsDisplayed()
        compose.onNodeWithText("待查询", substring = true).assertDoesNotExist()
        compose.onNodeWithText("查询").assertDoesNotExist()
    }

    @Test fun `selecting back the confirmed range hides the pending hint`() {
        val state = mutableStateOf(
            GradesUiState(
                connectionId = "a", accountKey = "account", loggedIn = true,
                terms = terms, records = rows, draftRange = "2026", confirmedRange = "2026",
            ),
        )
        compose.setContent { IYangoThemePreview {
            GradesPageLayout(state.value, 0.dp, {}, onSelectRange = { value ->
                state.value = state.value.copy(draftRange = value, confirmedRange = value)
            })
        } }
        compose.onNodeWithTag("grades-term-selector").performClick()
        compose.onNodeWithTag("grades-term-option-2026").performClick()
        compose.onNodeWithText("待查询", substring = true).assertDoesNotExist()
    }

    @Test fun `refreshing disables the term selector without restoring a query button`() {
        val state = mutableStateOf(
            GradesUiState(connectionId = "a", accountKey = "account", loggedIn = true, terms = terms, records = rows, querying = true),
        )
        var queries = 0
        compose.setContent { IYangoThemePreview {
            GradesPageLayout(state.value, 0.dp, {}, onRefresh = { queries++ })
        } }
        compose.onNodeWithText("查询中…").assertDoesNotExist()
        compose.onNodeWithTag("grades-term-selector").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, queries) }
    }

    @Test fun `sort menu sorts locally without any query`() {
        val state = mutableStateOf(
            GradesUiState(connectionId = "a", accountKey = "account", loggedIn = true, terms = terms, records = rows),
        )
        var queries = 0
        compose.setContent { IYangoThemePreview {
            GradesPageLayout(state.value, 0.dp, {},
                onSelectSort = { sort -> state.value = state.value.copy(sort = sort) },
                onRefresh = { queries++ })
        } }
        compose.onNodeWithTag("grades-sort-selector").performClick()
        // 折叠入口短标签与菜单完整文案各一个「学校顺序」。
        compose.onAllNodesWithText("学校顺序").assertCountEquals(2)
        compose.onNodeWithText("分数从高到低").assertIsDisplayed()
        compose.onNodeWithText("学分从高到低").performClick()
        compose.runOnIdle { assertEquals(GradeSort.CREDIT_DESC, state.value.sort); assertEquals(0, queries) }
        compose.onNodeWithText("分数从高到低").assertDoesNotExist()
    }

    @Test fun `term sheet explains loading empty and offline cache states`() {
        val state = mutableStateOf(
            GradesUiState(
                connectionId = "a", accountKey = "account", loggedIn = false,
                loadingTerms = true, records = rows, draftRange = "2024",
            ),
        )
        compose.setContent { IYangoThemePreview { GradesPageLayout(state.value, 0.dp, {}) } }
        // 登录过期仍可打开面板选择本机缓存范围。
        compose.onNodeWithTag("grades-term-selector").performClick()
        compose.onNodeWithText("正在获取学校学期").assertIsDisplayed()
        compose.onNodeWithText("2024（本机缓存）").assertIsDisplayed()
        compose.onNodeWithTag("grades-term-option-2024").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("查询").assertDoesNotExist()
        // 学期列表为空且不在加载：解释性文案，不误报为无成绩。
        compose.runOnIdle { state.value = state.value.copy(loadingTerms = false) }
        compose.onNodeWithTag("grades-term-selector").performClick()
        compose.onNodeWithText("暂未取得学校学期列表").assertIsDisplayed()
    }
}
