package com.gfs.iyango.feature.main.grades

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.theme.IYangoTheme
import com.gfs.iyango.core.model.GradeTerm
import java.io.File
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GradeTermRangeUiTest {
    @get:Rule val compose = createComposeRule()
    private val terms = listOf("2025-2026-2", "2025-2026-1", "2024-2025-2", "2024-2025-1").map { GradeTerm(it, it) }

    @Test fun `range query below the count refreshes the selected interval and closes the sheet`() {
        var selected: String? = null
        val requests = mutableListOf<String>()
        val state = mutableStateOf(GradesUiState(connectionId = "a", accountKey = "student", loggedIn = true, terms = terms))
        compose.setContent { IYangoTheme(darkTheme = true, accentHex = "#ECA46C") {
            GradesPageLayout(state.value, 0.dp, onSelectRange = {
                selected = it
                state.value = state.value.copy(draftRange = it, confirmedRange = it)
            }, onRefresh = {
                requests += state.value.confirmedRange
                state.value = state.value.copy(querying = true)
            })
        } }
        compose.onNodeWithText("查询").assertDoesNotExist()
        compose.onNodeWithTag("grades-term-selector").performClick()
        compose.onNodeWithText("选择后点击查询更新成绩").assertDoesNotExist()
        val header = compose.onNodeWithText("选择学期").fetchSemanticsNode().boundsInRoot
        val entry = compose.onNodeWithTag("grades-range-toggle").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val all = compose.onNodeWithTag("grades-term-option-all").fetchSemanticsNode().boundsInRoot
        assertTrue(header.bottom <= entry.top && entry.bottom <= all.top)
        compose.onNodeWithTag("grades-range-toggle").performClick()
        compose.onNodeWithTag("grades-range-end").performScrollTo().performClick()
        compose.onNodeWithTag("grades-range-end-option-2025-2026-1").performClick()
        compose.onNodeWithText("包含 3 个学期").assertIsDisplayed()
        compose.onNodeWithTag("grades-range-apply").assertDoesNotExist()
        val countBounds = compose.onNodeWithText("包含 3 个学期").fetchSemanticsNode().boundsInRoot
        val buttonBounds = compose.onNodeWithTag("grades-range-query").assertIsEnabled().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(buttonBounds.top >= countBounds.bottom)
        capture("range-picker")
        assertEquals("range:2024-2025-1..2025-2026-1", selected)
        assertTrue(requests.isEmpty())
        compose.onNodeWithTag("grades-range-query").performClick()
        compose.onNodeWithText("选择学期").assertDoesNotExist()
        compose.onNodeWithText("查询").assertDoesNotExist()
        assertEquals(listOf("range:2024-2025-1..2025-2026-1"), requests)
    }
    @Test fun `single semester range stays open after applying and close keeps the calculation`() {
        var selected: String? = null
        val state = mutableStateOf(GradesUiState(connectionId = "a", accountKey = "student", terms = terms))
        compose.setContent { IYangoTheme {
            GradesPageLayout(state.value, 0.dp, onSelectRange = {
                selected = it
                state.value = state.value.copy(draftRange = it, confirmedRange = it,
                    selectionRevision = state.value.selectionRevision + 1)
            })
        } }
        compose.onNodeWithTag("grades-term-selector").performClick()
        compose.onNodeWithTag("grades-range-toggle").performClick()
        compose.onNodeWithTag("grades-range-start").performScrollTo().performClick()
        compose.onNodeWithTag("grades-range-start-option-2025-2026-2").performClick()
        compose.onNodeWithText("包含 1 个学期").assertIsDisplayed()
        assertEquals("2025-2026-2", selected)
        compose.onNodeWithTag("grades-range-start").performScrollTo().performClick()
        compose.onNodeWithTag("grades-range-start-option-2024-2025-1").performClick()
        compose.onNodeWithTag("grades-range-end").performScrollTo().performClick()
        compose.onNodeWithTag("grades-range-end-option-2025-2026-1").performClick()
        compose.onNodeWithText("包含 3 个学期").assertIsDisplayed()
        compose.onNodeWithText("关闭").performScrollTo().performClick()
        compose.onNodeWithText("选择学期").assertDoesNotExist()
        assertEquals("range:2024-2025-1..2025-2026-1", selected)
        compose.onNodeWithTag("grades-term-selector").performClick()
        compose.onNodeWithText("包含 3 个学期").assertIsDisplayed()
        compose.onNodeWithTag("grades-range-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("grades-term-option-2024-2025-2").performScrollTo().performClick()
        assertEquals("2024-2025-2", selected)
    }
    @Test
    @Config(qualifiers = "w320dp-h568dp-mdpi")
    fun `compact large font range controls remain reachable`() {
        RuntimeEnvironment.setFontScale(2f)
        compose.setContent { IYangoTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                Box(Modifier.size(320.dp, 568.dp)) {
                    TermPickerSheet(GradesUiState(terms = terms, loggedIn = true,
                        draftRange = "range:2024-2025-1..2025-2026-1",
                        confirmedRange = "range:2024-2025-1..2025-2026-1"), {}, {})
                }
            }
        } }
        compose.onNodeWithTag("grades-range-start").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("grades-range-end").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("包含 3 个学期").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("grades-range-apply").assertDoesNotExist()
        compose.onNodeWithTag("grades-range-query").performScrollTo().assertIsDisplayed()
        capture("range-picker-large")
    }
    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("grades-term-picker-content").captureToImage().asAndroidBitmap()
        val file = File("../../build/diagnostics/grade-range-query-20261112/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
