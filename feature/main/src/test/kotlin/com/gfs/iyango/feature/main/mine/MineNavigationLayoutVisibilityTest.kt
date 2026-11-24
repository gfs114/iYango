package com.gfs.iyango.feature.main.mine

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MineNavigationLayoutVisibilityTest {
    @get:Rule val compose = createComposeRule()

    private val hint = "自动：宽窗口使用侧边导航，窄窗口保持底部导航"

    private fun show(width: Int, height: Int) {
        compose.setContent {
            IYangoThemePreview {
                Box(Modifier.size(width.dp, height.dp)) { MineAppearanceScreen(onBack = {}) }
            }
        }
    }

    @Test
    @Config(qualifiers = "sw360dp-w393dp-h852dp-mdpi")
    fun `phone hides the navigation layout choice but keeps the rest of the section`() {
        show(393, 852)
        compose.onNodeWithText("导航布局").assertDoesNotExist()
        compose.onNodeWithText(hint).assertDoesNotExist()
        compose.onNodeWithText("自动").assertDoesNotExist()
        compose.onNodeWithText("底部").assertDoesNotExist()
        compose.onNodeWithText("侧边").assertDoesNotExist()
        compose.onNodeWithText("布局与启动").assertExists()
        compose.onNodeWithText("默认打开页面").assertExists()
        compose.onNodeWithText("界面动画").assertExists()
    }

    @Test
    @Config(qualifiers = "sw393dp-w852dp-h393dp-mdpi")
    fun `landscape phone hides the navigation layout choice`() {
        show(852, 393)
        compose.onNodeWithText("导航布局").assertDoesNotExist()
        compose.onNodeWithText(hint).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "sw600dp-w1280dp-h800dp-mdpi")
    fun `tablet keeps the navigation layout choice`() {
        show(1280, 800)
        compose.onNodeWithText("导航布局").assertExists()
        compose.onNodeWithText("自动").assertExists()
        compose.onNodeWithText("底部").assertExists()
        compose.onNodeWithText("侧边").assertExists()
        compose.onNodeWithText(hint).assertExists()
    }
}
