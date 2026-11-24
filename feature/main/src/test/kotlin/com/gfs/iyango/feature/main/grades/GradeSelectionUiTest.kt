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
import com.gfs.iyango.core.model.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers="w320dp-h900dp-mdpi")
class GradeSelectionUiTest {
    @get:Rule val compose=createComposeRule()
    private val rows=listOf(
        GradeRecord(id=1,courseName="Duplicate",courseCode="SAME",credit=2.0,scoreText="80"),
        GradeRecord(id=2,courseName="Duplicate",courseCode="SAME",credit=1.0,scoreText="100"),
    )
    @Test fun `entry row toggle all clear exit recompute only selected grades and restore normal details`() {
        val state=mutableStateOf(GradesUiState(connectionId="a",accountKey="account",records=rows,selectionRevision=4))
        var details=0
        var toggles=0
        compose.setContent { IYangoThemePreview {
            GradesPageLayout(state.value,0.dp, onSelect = {details++},
                onBeginSelection={state.value=state.value.copy(selectionMode=true)},
                onToggleSelection={id,_ -> toggles++; state.value=state.value.copy(selectedRecordIds=state.value.selectedRecordIds.let { if(id in it) it-id else it+id })},
                onSelectAll={state.value=state.value.copy(selectedRecordIds=rows.map{it.id}.toSet())},
                onClearSelection={state.value=state.value.copy(selectedRecordIds=emptySet())},
                onExitSelection={state.value=state.value.copy(selectionMode=false,selectedRecordIds=emptySet())})
        } }
        compose.onNodeWithText("自选算分").performScrollTo().performClick()
        compose.onNodeWithTag("grades-selection-bar").assertIsDisplayed()
        compose.onNodeWithText("已选 0 条").assertIsDisplayed()
        compose.onNodeWithText("请选择课程").assertIsDisplayed()
        compose.onAllNodesWithText("—").assertCountEquals(3)
        compose.onNodeWithTag("grade-record-1").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("grade-record-2").performScrollTo().assertIsOff()
        compose.runOnIdle { assertEquals(1,toggles); assertEquals(0,details) }
        compose.onNodeWithText("已选 1 条").assertIsDisplayed()
        compose.onNodeWithText("80.00").assertIsDisplayed()
        compose.onNodeWithText("统计学分").assertIsDisplayed()
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("已选 2 条").assertIsDisplayed()
        compose.onNodeWithText("清空").performClick()
        compose.onNodeWithText("已选 0 条").assertIsDisplayed()
        compose.onNodeWithText("退出").performClick()
        compose.onNodeWithText("自选算分").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("grade-record-1").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1,details) }
    }
    @Test fun `selection controls remain reachable on narrow large font with offline cache`() {
        val state=mutableStateOf(GradesUiState(connectionId="a",accountKey="account",records=rows,selectionMode=true))
        compose.setContent { IYangoThemePreview {
            CompositionLocalProvider(LocalDensity provides Density(1f,1.6f)) {
                Box(Modifier.size(320.dp,800.dp)) {
                    GradesPageLayout(state.value,0.dp,
                        onSelectAll={state.value=state.value.copy(selectedRecordIds=rows.map{it.id}.toSet())},
                        onClearSelection={state.value=state.value.copy(selectedRecordIds=emptySet())},
                        onExitSelection={state.value=state.value.copy(selectionMode=false)})
                }
            }
        } }
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("已选 2 条").assertIsDisplayed()
        compose.onNodeWithText("清空").performClick()
        compose.onNodeWithText("退出").performClick()
        compose.onNodeWithText("自选算分").performScrollTo().assertIsDisplayed()
    }
    @Test fun `back exits selection only while the retained grades tab is active`() {
        val active=mutableStateOf(true)
        val state=mutableStateOf(GradesUiState(connectionId="a",accountKey="account",records=rows,selectionMode=true))
        lateinit var dispatcher: androidx.activity.OnBackPressedDispatcher
        var exits=0
        compose.setContent { IYangoThemePreview {
            dispatcher=androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            CompositionLocalProvider(com.gfs.iyango.feature.main.LocalMainTabActive provides active.value) {
                GradesPageLayout(state.value,0.dp,
                    onExitSelection={exits++; state.value=state.value.copy(selectionMode=false)})
            }
        } }
        compose.runOnIdle { assertTrue(dispatcher.hasEnabledCallbacks()); active.value=false }
        compose.runOnIdle { assertFalse(dispatcher.hasEnabledCallbacks()); active.value=true }
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(1,exits); assertFalse(state.value.selectionMode) }
    }

}
