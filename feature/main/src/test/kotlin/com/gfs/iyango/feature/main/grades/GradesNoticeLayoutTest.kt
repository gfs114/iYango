package com.gfs.iyango.feature.main.grades

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.theme.IYangoTheme
import com.gfs.iyango.core.model.GradeRecord
import com.gfs.iyango.core.model.GradeStatistics
import java.io.File
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GradesNoticeLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `switch sits beside the elective label and info stays at the trailing edge`() {
        var infoClicks = 0
        var checked by mutableStateOf(false)
        compose.setContent { IYangoTheme(darkTheme = true, accentHex = "#ECA46C") {
            Surface { Box(Modifier.width(361.dp).testTag("card")) {
                GradeStatisticsCard(GradeStatistics(weightedAverage = 89.23, averageGradePoint = 3.92,
                    totalCredits = 28.3, includedCount = 13, excludedCount = 1), checked,
                    { checked = it }, {}, { infoClicks++ }, true)
            } }
        } }
        capture("normal")
        val label = compose.onNodeWithText("统计包含公选课").fetchSemanticsNode().boundsInRoot
        val info = compose.onNodeWithTag("grades-statistics-info").fetchSemanticsNode().boundsInRoot
        val toggle = compose.onNode(isToggleable()).fetchSemanticsNode().boundsInRoot
        assertTrue("Switch must be adjacent to its label", toggle.left >= label.right && toggle.left - label.right <= 12f)
        assertTrue("Info must be last on the row", info.left >= toggle.right)
        compose.onNodeWithTag("grades-statistics-info").performClick()
        assertEquals(1, infoClicks)
        assertFalse(checked)
        compose.onNode(isToggleable()).performClick().assertIsOn()
        // The compact drawing must retain the original finger-sized hit area.
        compose.onNode(isToggleable()).performTouchInput { click(Offset(center.x, height - 2f)) }.assertIsOff()
        compose.onNode(isToggleable()).performClick().assertIsOn()
        capture("checked")
    }
    @Test fun `large text keeps the label info button and switch reachable`() {
        compose.setContent { IYangoTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                Surface { Box(Modifier.width(288.dp).testTag("card")) {
                    GradeStatisticsCard(GradeStatistics(), false, {}, {}, {}, true)
                } }
            }
        } }
        compose.onNodeWithText("统计包含公选课").assertIsDisplayed()
        compose.onNodeWithTag("grades-statistics-info").assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsDisplayed()
        val info = compose.onNodeWithTag("grades-statistics-info").fetchSemanticsNode().boundsInRoot
        val toggle = compose.onNode(isToggleable()).fetchSemanticsNode().boundsInRoot
        assertTrue("Wrapped controls must leave the help at the right", toggle.right <= info.left)
        capture("large-text")
    }
    @Test fun `expired refresh shows one actionable banner even if an old error is still present`() {
        var logins = 0
        compose.setContent { IYangoTheme {
            GradesPageLayout(GradesUiState(connectionId = "a", needsLogin = true,
                error = "登录状态已失效，请返回教务登录后重试",
                records = listOf(GradeRecord(id = 1, courseName = "课程"))), 0.dp, onOpenSchool = { logins++ })
        } }
        compose.onNodeWithText("登录已过期，当前显示 全部学期 的本机缓存").assertIsDisplayed()
        compose.onNodeWithText("知道了").assertDoesNotExist()
        compose.onNodeWithText("去登录").performClick()
        assertEquals(1, logins)
    }
    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("card").captureToImage().asAndroidBitmap()
        val file = File("../../build/diagnostics/grades-review-20261112/$name.png")
        file.parentFile.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
