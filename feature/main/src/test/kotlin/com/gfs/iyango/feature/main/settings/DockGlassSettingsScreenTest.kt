package com.gfs.iyango.feature.main.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.domain.DockGlassSettings
import com.gfs.iyango.core.domain.ThemeMode
import com.gfs.iyango.core.domain.ThemeSettings
import com.gfs.iyango.feature.main.mine.MineAppearanceScreen
import com.gfs.iyango.feature.main.mine.MineDestination
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28]) // 与 LiquidBottomTabsTest 一致，在 Robolectric 下不依赖真机 RuntimeShader 实现。
class DockGlassSettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun simpleMode_showsFourBlurLensEdgeParamsWithEffectNotesPreviewAndPreservesAdvancedOnReset() {
        var current by mutableStateOf(
            ThemeSettings(
                accentHex = "#336699",
                themeMode = ThemeMode.DARK,
                dockSurfaceAlphaLight = 0.12f,
                dockSurfaceAlphaDark = 0.25f,
                dockGlass = DockGlassSettings(
                    blurDp = 11f,
                    lensWidthDp = 24f,
                    legacyTier0AlphaLight = 0.70f,
                    indicatorSurfaceAlpha = 0.08f,
                ),
            ),
        )

        compose.setContent {
            IYangoThemePreview {
                CompositionLocalProvider(LocalMotionEnabled provides false) {
                    DockGlassSettingsScreen(
                        themeSettings = current,
                        onThemeSettingsChange = { current = it },
                        fullMode = false,
                        onBack = {},
                    )
                }
            }
        }

        // 顶部对应效果图与 5 项透明度移除校验
        compose.onNodeWithTag("dock-glass-preview").assertExists()
        compose.onNodeWithTag("dock-glass-slider-dockSurfaceAlphaLight").assertDoesNotExist()
        compose.onNodeWithTag("dock-glass-slider-dockSurfaceAlphaDark").assertDoesNotExist()
        compose.onNodeWithTag("dock-glass-slider-legacyTier0AlphaLight").assertDoesNotExist()
        compose.onNodeWithTag("dock-glass-slider-legacyTier0AlphaDark").assertDoesNotExist()
        compose.onNodeWithTag("dock-glass-slider-indicatorSurfaceAlpha").assertDoesNotExist()

        // 简化版展示模糊/透镜宽/透镜高/基础条高光 4 项及最左 0 刻度、效果备注
        compose.onAllNodesWithText("0").onFirst().assertExists()
        compose.onNodeWithText("调小至 0 = 去掉背景模糊，背后内容完全清晰", substring = true).performScrollTo().assertExists()
        compose.onNodeWithTag("dock-glass-slider-lensWidthDp").performScrollTo().assertExists()
        compose.onNodeWithTag("dock-glass-slider-lensHeightDp").performScrollTo().assertExists()
        compose.onNodeWithTag("dock-glass-slider-edgeAlphaBase").performScrollTo().assertExists()
        compose.onNodeWithTag("dock-glass-slider-blurDp")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.runOnIdle {
            assertEquals(0f, current.dockGlass.blurDp, 1e-4f)
        }

        // 切换至数值输入模式后预填当前值
        compose.onNodeWithText("数值输入").performScrollTo().performClick()
        compose.onNodeWithTag("dock-glass-input-blurDp").performScrollTo().assertTextContains("0")
        compose.onNodeWithTag("dock-glass-input-lensWidthDp").performScrollTo().assertTextContains("24")
        compose.onNodeWithTag("dock-glass-input-lensHeightDp").performScrollTo().assertTextContains("14")
        compose.onNodeWithTag("dock-glass-input-edgeAlphaBase").performScrollTo().assertTextContains("0.22")

        // 简化版恢复默认只恢复这 4 项，不破坏蒙层与开发者模式其他参数
        compose.onNodeWithText("恢复默认").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(2f, current.dockGlass.blurDp, 0f)
            assertEquals(12f, current.dockGlass.lensWidthDp, 0f)
            assertEquals(14f, current.dockGlass.lensHeightDp, 0f)
            assertEquals(0.22f, current.dockGlass.edgeAlphaBase, 0f)
            assertEquals(0.12f, current.dockSurfaceAlphaLight, 0f)
            assertEquals(0.25f, current.dockSurfaceAlphaDark, 0f)
            assertEquals(0.70f, current.dockGlass.legacyTier0AlphaLight, 0f)
            assertEquals(0.08f, current.dockGlass.indicatorSurfaceAlpha, 0f)
            assertEquals("#336699", current.accentHex)
            assertEquals(ThemeMode.DARK, current.themeMode)
        }
        compose.onNodeWithTag("dock-glass-input-blurDp").performScrollTo().assertTextContains("2")
        compose.onNodeWithTag("dock-glass-input-lensWidthDp").performScrollTo().assertTextContains("12")
        compose.onNodeWithText("恢复默认").assertIsNotEnabled()
    }

    @Test
    fun fullMode_numericPrefillsAllProjectDefaultsAndShowsEffectDescriptions() {
        compose.setContent {
            IYangoThemePreview {
                CompositionLocalProvider(LocalMotionEnabled provides false) {
                    DockGlassSettingsScreen(
                        themeSettings = ThemeSettings(),
                        onThemeSettingsChange = {},
                        fullMode = true,
                        onBack = {},
                    )
                }
            }
        }

        compose.onNodeWithText("底栏液态玻璃（完整调参）").assertExists()
        compose.onNodeWithText("数值输入").performScrollTo().performClick()

        val expectedPrefills = listOf(
            "blurDp" to "2",
            "lensWidthDp" to "12",
            "lensHeightDp" to "14",
            "dockSurfaceAlphaLight" to "0",
            "dockSurfaceAlphaDark" to "0",
            "legacyTier0AlphaLight" to "0.55",
            "legacyTier0AlphaDark" to "0.35",
            "edgeWidthDp" to "0.5",
            "edgeBlurDp" to "0.6",
            "edgeAlphaBase" to "0.22",
            "edgeAlphaHidden" to "0.25",
            "edgeAlphaIndicator" to "0.35",
            "indicatorLensWidthDp" to "8",
            "indicatorLensHeightDp" to "10",
            "indicatorSurfaceAlpha" to "0.02",
            "indicatorSurfacePressedAlpha" to "0.02",
            "indicatorShadowRadiusDp" to "12",
            "indicatorShadowAlpha" to "0.3",
            "indicatorInnerRadiusDp" to "4",
            "indicatorInnerAlpha" to "0.4",
        )
        expectedPrefills.forEach { (key, expectedText) ->
            compose.onNodeWithTag("dock-glass-input-$key")
                .performScrollTo()
                .assertTextContains(expectedText)
        }
        compose.onNodeWithText("调小至 0 = 去掉背景模糊，背后内容完全清晰", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("开启后按压或拖动指示器时", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("恢复默认").assertIsNotEnabled()
    }

    @Test
    fun fullMode_numericUpdatesTargetFieldPreservingOtherThemeFields_andRejectsInvalidInput() {
        var current by mutableStateOf(
            ThemeSettings(
                accentHex = "#336699",
                tintBackground = true,
                themeMode = ThemeMode.DARK,
            ),
        )
        val emissions = mutableListOf<ThemeSettings>()

        compose.setContent {
            IYangoThemePreview {
                CompositionLocalProvider(LocalMotionEnabled provides false) {
                    DockGlassSettingsScreen(
                        themeSettings = current,
                        onThemeSettingsChange = {
                            current = it
                            emissions += it
                        },
                        initialMode = DockGlassInputMode.NUMERIC,
                        fullMode = true,
                        onBack = {},
                    )
                }
            }
        }

        val blurField = compose.onNodeWithTag("dock-glass-input-blurDp")
        blurField.performScrollTo().performTextReplacement("8.5")
        compose.runOnIdle {
            assertEquals(8.5f, current.dockGlass.blurDp, 1e-4f)
            assertEquals("#336699", current.accentHex)
            assertEquals(true, current.tintBackground)
            assertEquals(ThemeMode.DARK, current.themeMode)
        }

        val countAfterValid = emissions.size
        blurField.performTextReplacement("999")
        compose.onNodeWithText("请输入 0 ~ 24 dp 之间的有效数值").assertExists()
        compose.runOnIdle {
            assertEquals(countAfterValid, emissions.size)
            assertEquals(8.5f, current.dockGlass.blurDp, 1e-4f)
        }

        blurField.performTextReplacement("abc")
        compose.onNodeWithText("请输入 0 ~ 24 dp 之间的有效数值").assertExists()
        compose.runOnIdle {
            assertEquals(countAfterValid, emissions.size)
            assertEquals(8.5f, current.dockGlass.blurDp, 1e-4f)
        }
    }

    @Test
    fun fullMode_modeSwitchingAndResetDefaults_syncValuesAndPreserveNonDockThemeFields() {
        var current by mutableStateOf(
            ThemeSettings(
                accentHex = "#AABBCC",
                themeMode = ThemeMode.LIGHT,
                dockSurfaceAlphaLight = 0.15f,
                dockGlass = DockGlassSettings(blurDp = 11f, lensWidthDp = 24f),
            ),
        )

        compose.setContent {
            IYangoThemePreview {
                CompositionLocalProvider(LocalMotionEnabled provides false) {
                    DockGlassSettingsScreen(
                        themeSettings = current,
                        onThemeSettingsChange = { current = it },
                        fullMode = true,
                        onBack = {},
                    )
                }
            }
        }

        compose.onNodeWithText("恢复默认").assertIsEnabled()
        compose.onNodeWithText("数值输入").performScrollTo().performClick()
        compose.onNodeWithTag("dock-glass-input-blurDp")
            .performScrollTo()
            .assertTextContains("11")
        compose.onNodeWithTag("dock-glass-input-lensWidthDp")
            .performScrollTo()
            .assertTextContains("24")

        compose.onNodeWithText("拖动调节").performScrollTo().performClick()
        compose.onNodeWithTag("dock-glass-slider-blurDp").performScrollTo().assertExists()

        compose.onNodeWithText("数值输入").performScrollTo().performClick()
        compose.onNodeWithText("恢复默认").performClick()

        compose.runOnIdle {
            assertEquals(DockGlassSettings(), current.dockGlass)
            assertEquals(0f, current.dockSurfaceAlphaLight, 0f)
            assertEquals(0f, current.dockSurfaceAlphaDark, 0f)
            assertEquals("#AABBCC", current.accentHex)
            assertEquals(ThemeMode.LIGHT, current.themeMode)
        }
        compose.onNodeWithTag("dock-glass-input-blurDp")
            .performScrollTo()
            .assertTextContains("2")
        compose.onNodeWithTag("dock-glass-input-lensWidthDp")
            .performScrollTo()
            .assertTextContains("12")
        compose.onNodeWithText("恢复默认").assertIsNotEnabled()
    }

    @Test
    fun mineAppearanceScreen_containsAndNavigatesToDockGlassRoute() {
        val visited = mutableListOf<String>()
        compose.setContent {
            IYangoThemePreview {
                MineAppearanceScreen(
                    onBack = {},
                    onNavigate = { visited += it.route },
                )
            }
        }

        compose.onNodeWithText("底栏液态玻璃").assertDoesNotExist()
        compose.onNodeWithText("液态玻璃").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(MineDestination.LIQUID_GLASS.route), visited)
        }
    }
}
