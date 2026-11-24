package com.gfs.iyango.feature.main.grades

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
 * 长列表布局几何断言（方案 §13.3）：
 * - 滚到列表末尾后固定计算栏仍可见，最后一张卡片不进入计算栏区域；
 * - 方案任务 0 的合成样本数据在界面上的统计期望值；
 * - 普通/自选模式切换共用列表状态，不跳回顶部。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers="w393dp-h852dp-mdpi")
class GradesLayoutUiTest {
    @get:Rule val compose=createComposeRule()

    private val longRows = (1..100).map { index ->
        GradeRecord(id = index.toLong(), courseName = "长列表课程 $index", scoreText = "$index",
            scoreValue = index.toDouble(), credit = 2.0)
    }

    /** 方案任务 0 样本：长课程名、缺失绩点、文本成绩、重复名称、成绩变化、零学分。 */
    private val sampleRows = listOf(
        GradeRecord(id = 1, courseName = "软件工程综合应用与开发实践课程", credit = 2.0,
            scoreText = "80", gradePoint = 3.0, attribute = "必修", semester = "2026-2027-1"),
        GradeRecord(id = 2, courseName = "软件工程综合应用与开发实践课程", credit = 1.0,
            scoreText = "100", gradePoint = null, attribute = "必修", semester = "2026-2027-1"),
        GradeRecord(id = 3, courseName = "艺术鉴赏", credit = 2.0,
            scoreText = "合格", attribute = "选修", semester = "2026-2027-1"),
        GradeRecord(id = 4, courseName = "计算机网络", credit = 3.0,
            scoreText = "85", gradePoint = 3.5, isModified = true,
            previousScoreText = "80", semester = "2026-2027-1"),
        GradeRecord(id = 5, courseName = "实践环节", credit = 0.0,
            scoreText = "90", semester = "2026-2027-1"),
    )

    @Test fun `selection bar stays visible and clear of the last card after a long scroll`() {
        val state = mutableStateOf(
            GradesUiState(connectionId = "a", accountKey = "account", records = longRows, selectionMode = true),
        )
        compose.setContent { IYangoThemePreview { GradesPageLayout(state.value, 96.dp) } }
        // 按记录 key 滚到末尾，不依赖筛选/提示条目数量。
        compose.onNodeWithTag("grades-list").performScrollToKey(100L)
        compose.onNodeWithTag("grades-selection-bar").assertIsDisplayed()
        compose.onNodeWithText("退出").assertIsDisplayed()
        val last = compose.onNodeWithTag("grade-record-100").fetchSemanticsNode().boundsInRoot
        val bar = compose.onNodeWithTag("grades-selection-bar").fetchSemanticsNode().boundsInRoot
        assertTrue("最后一张卡片底部不得进入计算栏区域", last.bottom <= bar.top)
    }

    @Test fun `plan sample dataset shows expected selection statistics`() {
        val state = mutableStateOf(
            GradesUiState(
                connectionId = "a", accountKey = "account", records = sampleRows,
                selectionMode = true, selectedRecordIds = setOf(1, 2),
            ),
        )
        compose.setContent { IYangoThemePreview {
            GradesPageLayout(state.value, 96.dp,
                onToggleSelection = { id, _ ->
                    state.value = state.value.copy(
                        selectedRecordIds = state.value.selectedRecordIds.let { if (id in it) it - id else it + id },
                    )
                },
                onSelectAll = { state.value = state.value.copy(selectedRecordIds = sampleRows.map { it.id }.toSet()) },
            )
        } }
        // 只选记录 1、2：加权平均 86.67，平均绩点 3.00，统计学分 3，纳入 2、排除 0。
        compose.onNodeWithText("86.67").assertIsDisplayed()
        compose.onNodeWithText("3.00").assertIsDisplayed()
        compose.onNodeWithText("3").assertIsDisplayed()
        compose.onNodeWithText("纳入 2 条 排除 0 条").assertDoesNotExist()
        compose.onNodeWithTag("grades-statistics-info").performClick()
        compose.onNodeWithText("纳入 2 条 排除 0 条").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        // 全选：加权平均 85.83，平均绩点 3.30，统计学分 6，纳入 3、排除 2。
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("85.83").assertIsDisplayed()
        compose.onNodeWithText("3.30").assertIsDisplayed()
        compose.onNodeWithText("6").assertIsDisplayed()
        compose.onNodeWithText("纳入 3 条 排除 2 条").assertDoesNotExist()
        compose.onNodeWithTag("grades-statistics-info").performClick()
        compose.onNodeWithText("纳入 3 条 排除 2 条").assertIsDisplayed()
    }

    @Test fun `mode switch keeps the shared list scroll position`() {
        val state = mutableStateOf(
            GradesUiState(connectionId = "a", accountKey = "account", records = longRows),
        )
        compose.setContent { IYangoThemePreview {
            GradesPageLayout(state.value, 96.dp,
                onBeginSelection = { state.value = state.value.copy(selectionMode = true) },
                onExitSelection = { state.value = state.value.copy(selectionMode = false) },
            )
        } }
        compose.onNodeWithTag("grades-list").performScrollToNode(hasTestTag("grade-record-50"))
        val beforeTop = compose.onNodeWithTag("grade-record-50").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { state.value = state.value.copy(selectionMode = true) }
        compose.waitForIdle()
        compose.onNodeWithTag("grades-selection-bar").assertIsDisplayed()
        val inSelectionTop = compose.onNodeWithTag("grade-record-50").fetchSemanticsNode().boundsInRoot.top
        assertEquals("进入自选不改变列表滚动位置", beforeTop, inSelectionTop, 2f)
        compose.runOnIdle { state.value = state.value.copy(selectionMode = false) }
        compose.waitForIdle()
        val afterExitTop = compose.onNodeWithTag("grade-record-50").fetchSemanticsNode().boundsInRoot.top
        assertEquals("退出自选不改变列表滚动位置", beforeTop, afterExitTop, 2f)
    }

    @Test fun `level grades including a cached pass use the same scores in all calculation views`() {
        val records = listOf(
            GradeRecord(id = 1, courseName = "课程一", attribute = "必修", credit = 2.0, scoreText = "优秀", scoreValue = 95.0),
            GradeRecord(id = 2, courseName = "课程二", attribute = "必修", credit = 1.0, scoreText = "及格", scoreValue = 60.0, gradePoint = 1.5),
            GradeRecord(id = 3, courseName = "课程三", attribute = "必修", credit = 1.0, scoreText = "不及格", scoreValue = 0.0),
        )
        val state = mutableStateOf(GradesUiState(connectionId = "a", accountKey = "account", records = records))
        compose.setContent { IYangoThemePreview { GradesPageLayout(state.value, 0.dp,
            onBeginSelection = { state.value = state.value.copy(selectionMode = true) },
            onSelectAll = { state.value = state.value.copy(selectedRecordIds = setOf(1, 2, 3)) }) } }
        compose.onNodeWithText("63.75").assertIsDisplayed()
        compose.onNodeWithText("2.63").assertIsDisplayed()
        compose.onNodeWithText("纳入 3 条 排除 0 条").assertDoesNotExist()
        compose.onNodeWithTag("grades-statistics-info").performClick()
        compose.onNodeWithText("纳入 3 条 排除 0 条").assertIsDisplayed()
        compose.onNodeWithText("2 学分 必修 换算绩点 4.50").assertIsDisplayed()
        compose.onNodeWithText("1 学分 必修 绩点 1.50").assertIsDisplayed()
        compose.onNodeWithText("1 学分 必修 换算绩点 0.00").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("自选算分").performClick()
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("63.75").assertIsDisplayed()
        compose.onNodeWithText("2.63").assertIsDisplayed()
        compose.onNodeWithText("纳入 3 条 排除 0 条").assertDoesNotExist()
        compose.onNodeWithTag("grades-statistics-info").performClick()
        compose.onNodeWithText("纳入 3 条 排除 0 条").assertIsDisplayed()
        compose.onNodeWithText("优秀 (95)").assertIsDisplayed()
        compose.onNodeWithText("及格 (65)").assertIsDisplayed()
        compose.onNodeWithText("不及格 (0)").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("grades-attribute-selector").performScrollTo().performClick()
        compose.onAllNodesWithText("63.75").onLast().assertIsDisplayed()
        compose.onAllNodesWithText("2.63").onLast().assertIsDisplayed()
    }

    @Test fun `school short level grades appear in term statistics and included course details`() {
        val records = listOf(
            GradeRecord(id = 1, semester = "2025-2026-2", courseName = "思政课社会实践（二）",
                credit = 1.0, scoreText = "优", gradePoint = 4.5, attribute = "必修"),
            GradeRecord(id = 2, semester = "2025-2026-2", courseName = "劳动教育",
                credit = 1.0, scoreText = "优", gradePoint = 4.5, attribute = "必修"),
            GradeRecord(id = 3, semester = "2025-2026-2", courseName = "数字成绩课程",
                credit = 2.0, scoreText = "90", gradePoint = 4.0, attribute = "必修"),
            GradeRecord(id = 4, semester = "2025-2026-2", courseName = "公选课程",
                credit = 2.0, scoreText = "优", gradePoint = 4.5, attribute = "公选"),
        )
        val state = GradesUiState(connectionId = "a", accountKey = "account", records = records,
            confirmedRange = "2025-2026-2", draftRange = "2025-2026-2")
        compose.setContent { IYangoThemePreview { GradesPageLayout(state, 0.dp) } }
        compose.onNodeWithText("92.50").assertIsDisplayed()
        compose.onNodeWithText("4.25").assertIsDisplayed()
        compose.onNodeWithTag("grades-statistics-info").performClick()
        compose.onNodeWithText("纳入 3 条 排除 1 条").assertIsDisplayed()
        compose.onAllNodesWithText("思政课社会实践（二）").onLast().assertIsDisplayed()
        compose.onAllNodesWithText("劳动教育").onLast().assertIsDisplayed()
        compose.onAllNodesWithText("优 (95)").assertCountEquals(2)
    }
}
