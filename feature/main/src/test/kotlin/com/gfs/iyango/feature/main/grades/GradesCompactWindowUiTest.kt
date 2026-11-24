package com.gfs.iyango.feature.main.grades

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
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
 * 极矮窗口 / 2 倍字号（方案 §13.2）：计算栏内容高度封顶并允许栏内滚动，
 * 首行操作（已选/全选/清空/退出）始终可达，滚动后「说明」可到达。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers="w320dp-h568dp-mdpi")
class GradesCompactWindowUiTest {
    @get:Rule val compose=createComposeRule()

    @Test fun `bar caps its height keeps exit reachable and scrolls to the info entry`() {
        val rows = (1..8).map { index ->
            GradeRecord(id = index.toLong(), courseName = "课程 $index", scoreText = "$index",
                scoreValue = index.toDouble(), credit = 2.0)
        }
        val state = mutableStateOf(
            GradesUiState(connectionId = "a", accountKey = "account", records = rows, selectionMode = true),
        )
        compose.setContent { IYangoThemePreview {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                Box(Modifier.size(320.dp, 568.dp)) {
                    GradesPageLayout(state.value, 0.dp, {})
                }
            }
        } }
        val bar = compose.onNodeWithTag("grades-selection-bar").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        // 内容高度封顶约为窗口高度 40%（568 * 0.4 ≈ 227dp，mdpi 下 1dp ≈ 1px），留少量余量。
        assertTrue("计算栏高度超过约定上限", bar.height <= 568f * 0.4f + 24f)
        compose.onNodeWithText("已选 0 条").assertIsDisplayed()
        compose.onNodeWithText("全选").assertIsDisplayed()
        compose.onNodeWithText("清空").assertIsDisplayed()
        compose.onNodeWithText("退出").assertIsDisplayed()
        // 栏内纵向滚动后「说明」可达。
        compose.onNodeWithTag("grades-selection-bar").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithTag("grades-statistics-info").assertIsDisplayed()
    }
}
