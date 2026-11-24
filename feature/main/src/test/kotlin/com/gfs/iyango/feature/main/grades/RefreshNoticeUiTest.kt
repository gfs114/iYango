package com.gfs.iyango.feature.main.grades

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.model.GradeRecord
import com.gfs.iyango.feature.main.LocalMainTabActive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
class RefreshNoticeUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `refresh outcome is visible over a scrolled list and can be shown again`() {
        val state = mutableStateOf(GradesUiState(connectionId = "test", accountKey = "test", loggedIn = true,
            records = (1..50).map { GradeRecord(id = it.toLong(), courseName = "课程 $it", scoreText = "80") }))
        compose.mainClock.autoAdvance = false
        compose.setContent { IYangoThemePreview {
            GradesPageLayout(state.value, 96.dp,
                onClearMessage = { state.value = state.value.copy(message = "") })
        } }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("grades-list").performScrollToIndex(40)
        compose.runOnIdle { state.value = state.value.copy(message = "刷新成功") }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("refresh-notice-capsule").assertIsDisplayed()
        compose.onNodeWithText("刷新成功").assertIsDisplayed()
        val notice = compose.onNodeWithTag("refresh-notice-capsule").fetchSemanticsNode().boundsInRoot
        assertTrue("Result must remain near the top after scrolling", notice.bottom < 250f)
        compose.mainClock.advanceTimeBy(3_200)
        compose.runOnIdle { assertEquals("", state.value.message) }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("refresh-notice-capsule").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(message = "刷新成功") }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("刷新成功").assertIsDisplayed()
    }

    @Test fun `inactive tab consumes late feedback instead of showing it on return`() {
        val active = mutableStateOf(false)
        val state = mutableStateOf(GradesUiState(connectionId = "test", accountKey = "test", message = "刷新失败"))
        compose.setContent { CompositionLocalProvider(LocalMainTabActive provides active.value) {
            IYangoThemePreview {
                GradesPageLayout(state.value, 96.dp,
                    onClearMessage = { state.value = state.value.copy(message = "") })
            }
        } }
        compose.runOnIdle { assertEquals("", state.value.message); active.value = true }
        compose.onNodeWithText("刷新失败").assertDoesNotExist()
    }
}
