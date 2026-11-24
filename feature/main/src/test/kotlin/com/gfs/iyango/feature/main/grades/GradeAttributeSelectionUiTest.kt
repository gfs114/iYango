package com.gfs.iyango.feature.main.grades

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.theme.IYangoTheme
import com.gfs.iyango.core.model.GradeRecord
import com.gfs.iyango.core.model.GradeTerm
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GradeAttributeSelectionUiTest {
    @get:Rule val compose = createComposeRule()
    private val rows = listOf(
        GradeRecord(id = 1, courseName = "课程一", attribute = "必修", credit = 2.0, scoreValue = 80.0),
        GradeRecord(id = 2, courseName = "课程二", attribute = "必修", credit = 1.0, scoreValue = 100.0),
        GradeRecord(id = 3, courseName = "课程三", attribute = "选修", credit = 1.0, scoreValue = 60.0),
        GradeRecord(id = 4, courseName = "课程四", credit = 1.0, scoreValue = 90.0),
    )

    @Test fun `attribute picker reflects manual partial selection and closes without exiting selection`() {
        val state = mutableStateOf(GradesUiState(connectionId = "a", accountKey = "student",
            records = rows, selectionMode = true, selectedRecordIds = setOf(1)))
        compose.setContent { IYangoTheme(darkTheme = true, accentHex = "#ECA46C") { GradesPageLayout(state.value, 0.dp) } }
        capture("selection-bar", "grades-selection-bar")
        compose.onNodeWithTag("grades-attribute-selector").performScrollTo().performClick()
        compose.onNodeWithText("课程属性").assertIsDisplayed()
        compose.onNodeWithTag("grades-attribute-option-必修").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Indeterminate))
        compose.onNodeWithTag("grades-attribute-option-选修").assertIsOff()
        compose.onNodeWithText("未标注").assertIsDisplayed()
        capture("attribute-picker")
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("课程属性").assertDoesNotExist()
        compose.onNodeWithTag("grades-selection-bar").assertIsDisplayed()
        compose.onNodeWithText("已选 1 条").assertIsDisplayed()
    }

    @Test fun `multiple attributes update the displayed calculation and manual rows stay editable`() {
        val state = mutableStateOf(GradesUiState(connectionId = "a", accountKey = "student",
            records = rows, selectionMode = true))
        val calls = mutableListOf<String>()
        compose.setContent { IYangoTheme { GradesPageLayout(state.value, 0.dp,
            onToggleAttributeSelection = { attribute, context ->
                assertEquals(state.value.selectionContext, context)
                calls += attribute
                val ids = when (attribute) { "必修" -> setOf(1L, 2L); "选修" -> setOf(3L); else -> emptySet() }
                state.value = state.value.copy(selectedRecordIds = state.value.selectedRecordIds + ids)
            }, onToggleSelection = { id, _ ->
                state.value = state.value.copy(selectedRecordIds = state.value.selectedRecordIds - id)
            }) } }
        compose.onNodeWithTag("grades-attribute-selector").performScrollTo().performClick()
        compose.onNodeWithTag("grades-attribute-option-必修").performClick().assertIsOn()
        compose.onAllNodesWithText("86.67").onLast().assertIsDisplayed()
        compose.onNodeWithTag("grades-attribute-option-选修").performClick().assertIsOn()
        compose.onAllNodesWithText("80.00").onLast().assertIsDisplayed()
        assertEquals(listOf("必修", "选修"), calls)
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("grade-record-2").performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("grades-attribute-selector").performScrollTo().performClick()
        compose.onNodeWithTag("grades-attribute-option-必修").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Indeterminate))
        compose.onNodeWithTag("grades-attribute-option-选修").assertIsOn()
    }

    @Test fun `account changes close the picker while cache updates keep the current selection workflow`() {
        val state = mutableStateOf(GradesUiState(connectionId = "a", accountKey = "student",
            records = rows, selectionMode = true))
        compose.setContent { IYangoTheme { GradesPageLayout(state.value, 0.dp) } }
        compose.onNodeWithTag("grades-attribute-selector").performScrollTo().performClick()
        compose.runOnIdle { state.value = state.value.copy(accountKey = "other") }
        compose.onNodeWithText("课程属性").assertDoesNotExist()
        compose.onNodeWithTag("grades-attribute-selector").performScrollTo().performClick()
        compose.runOnIdle { state.value = state.value.copy(selectionRevision = 10) }
        compose.onNodeWithText("课程属性").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(selectionMode = false) }
        compose.onNodeWithText("课程属性").assertDoesNotExist()
    }

    @Test fun `semester switches inside the attribute sheet keep it open with the new selection context`() {
        val state = mutableStateOf(GradesUiState(connectionId = "a", accountKey = "student",
            terms = listOf(GradeTerm("2024-2025-1"), GradeTerm("2024-2025-2")),
            records = rows, selectionMode = true, selectedRecordIds = setOf(1, 2)))
        var context: GradeSelectionContext? = null
        compose.setContent { IYangoTheme { GradesPageLayout(state.value, 0.dp,
            onSelectRange = { value -> state.value = state.value.copy(confirmedRange = value, draftRange = value,
                records = listOf(rows[1]), selectedRecordIds = setOf(2), selectionRevision = 10) },
            onToggleAttributeSelection = { _, selectedContext -> context = selectedContext }) } }
        compose.onNodeWithTag("grades-attribute-selector").performScrollTo().performClick()
        compose.onNodeWithTag("grades-attribute-term-selector").performClick()
        compose.onNodeWithTag("grades-attribute-term-option-2024-2025-2").performClick()
        compose.onNodeWithText("课程属性").assertIsDisplayed()
        compose.onNodeWithTag("grades-attribute-option-必修").assertIsOn().performClick()
        assertEquals(state.value.selectionContext, context)
        compose.onAllNodesWithText("100.00").onLast().assertIsDisplayed()
        compose.onNodeWithText("关闭").performScrollTo().performClick()
        compose.onNodeWithTag("grades-selection-bar").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-mdpi")
    fun `large font attribute controls and long names remain reachable`() {
        RuntimeEnvironment.setFontScale(2f)
        val state = GradesUiState(connectionId = "a", accountKey = "student", selectionMode = true,
            records = rows + GradeRecord(id = 5, attribute = "通识教育公共选修课程属性", credit = 1.0, scoreValue = 90.0))
        compose.setContent { IYangoTheme { GradesPageLayout(state, 0.dp) } }
        compose.onNodeWithTag("grades-attribute-selector").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("grades-attribute-term-selector").performScrollTo().assertIsDisplayed()
        capture("attribute-term-large")
        compose.onNodeWithTag("grades-attribute-option-通识教育公共选修课程属性").performScrollTo().assertIsDisplayed()
        capture("attribute-picker-large")
        compose.onNodeWithText("关闭").performScrollTo().performClick()
        compose.onNodeWithTag("grades-selection-bar").assertIsDisplayed()
    }

    @Test fun `range selection returns directly to the attribute picker without leaving selection mode`() {
        val state = mutableStateOf(GradesUiState(connectionId = "a", accountKey = "student",
            records = rows, selectionMode = true, selectedRecordIds = setOf(1, 2),
            terms = listOf("2024-2025-1", "2024-2025-2", "2025-2026-1").map { GradeTerm(it, it) }))
        var refreshes = 0
        compose.setContent { IYangoTheme { GradesPageLayout(state.value, 0.dp,
            onSelectRange = { value -> state.value = state.value.copy(confirmedRange = value, draftRange = value,
                selectionRevision = state.value.selectionRevision + 1) }, onRefresh = { refreshes++ }) } }
        compose.onNodeWithTag("grades-attribute-selector").performScrollTo().performClick()
        compose.onNodeWithTag("grades-attribute-term-selector").performClick()
        compose.onNodeWithTag("grades-attribute-term-range").performScrollTo().performClick()
        compose.onNodeWithTag("grades-range-toggle").performClick()
        compose.onNodeWithTag("grades-range-end").performScrollTo().performClick()
        compose.onNodeWithTag("grades-range-end-option-2024-2025-2").performClick()
        compose.onNodeWithText("关闭").performScrollTo().performClick()
        compose.onNodeWithText("课程属性").assertIsDisplayed()
        compose.onNodeWithTag("grades-attribute-term-selector").assertTextContains("2024-2025-1 至 2024-2025-2")
        compose.onNodeWithTag("grades-attribute-option-必修").assertIsOn()
        capture("attribute-term-switch")
        compose.onNodeWithTag("grades-attribute-term-selector").performClick()
        compose.onNodeWithTag("grades-attribute-term-range").performScrollTo().performClick()
        compose.onNodeWithTag("grades-term-option-all").performScrollTo().performClick()
        compose.onNodeWithText("课程属性").assertIsDisplayed()
        assertEquals("", state.value.confirmedRange)
        assertTrue(state.value.selectionMode)
        assertEquals(0, refreshes)
    }

    private fun capture(name: String, tag: String = "grades-attribute-picker-content") {
        val file = File("../../build/diagnostics/grade-selection-terms-20261113/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
            .compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
