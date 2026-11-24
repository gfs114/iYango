package com.gfs.iyango.feature.main.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.domain.GlobalGlassSettings
import com.gfs.iyango.core.domain.LiquidGlassPreset
import com.gfs.iyango.core.domain.LiquidGlassShareCodec
import com.gfs.iyango.core.domain.ThemeSettings
import com.gfs.iyango.feature.main.mine.MineAppearanceScreen
import com.gfs.iyango.feature.main.mine.MineDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiquidGlassSettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun switchCategories_andEditParamsToZeroViaSliderAndNumericInput() {
        var current by mutableStateOf(ThemeSettings())

        compose.setContent {
            IYangoThemePreview {
                LiquidGlassSettingsScreen(
                    themeSettings = current,
                    onThemeSettingsChange = { current = it },
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag("liquid-glass-preview").assertIsDisplayed()
        compose.onNodeWithText("恢复默认").assertIsNotEnabled()

        // Switch to 卡片内容 (CONTENT) and drag slider to 0f.
        compose.onNodeWithTag("liquid-glass-cat-CONTENT").performScrollTo().performClick()
        compose.onNodeWithTag("liquid-glass-slider-CONTENT-tintAlphaLight")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.onNodeWithTag("liquid-glass-slider-CONTENT-edgeWidthDp")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1.5f) }
        compose.onNodeWithTag("liquid-glass-slider-CONTENT-edgeBlurDp")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(2.0f) }
        compose.onNodeWithTag("liquid-glass-depth-toggle-CONTENT")
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag("liquid-glass-chromatic-toggle-CONTENT")
            .performScrollTo()
            .performClick()
        compose.runOnIdle {
            assertEquals(0f, current.globalGlass.content.tintAlphaLight, 1e-4f)
            assertEquals(1.5f, current.globalGlass.content.edgeWidthDp, 1e-4f)
            assertEquals(2.0f, current.globalGlass.content.edgeBlurDp, 1e-4f)
            assertTrue(current.globalGlass.content.depthEffect)
            assertTrue(current.globalGlass.content.chromaticAberration)
        }
        compose.onNodeWithText("恢复默认").assertIsEnabled()

        // Sync CONTENT layer to all 4 global categories.
        compose.onNodeWithTag("liquid-glass-sync-all-four-btn").performScrollTo().performClick()
        compose.runOnIdle {
            val expectedLayer = current.globalGlass.content
            assertEquals(expectedLayer, current.globalGlass.navigation)
            assertEquals(expectedLayer, current.globalGlass.control)
            assertEquals(expectedLayer, current.globalGlass.overlay)
        }

        // Switch to 数值输入 mode and set blurDp & edgeAlpha to 0 on 控件 (CONTROL).
        compose.onNodeWithTag("liquid-glass-cat-CONTROL").performScrollTo().performClick()
        compose.onNodeWithText("数值输入").performScrollTo().performClick()
        compose.onNodeWithTag("liquid-glass-input-CONTROL-blurDp")
            .performScrollTo()
            .performTextReplacement("0")
        compose.onNodeWithTag("liquid-glass-input-CONTROL-edgeAlpha")
            .performScrollTo()
            .performTextReplacement("0")

        compose.runOnIdle {
            assertEquals(0f, current.globalGlass.control.blurDp, 1e-4f)
            assertEquals(0f, current.globalGlass.control.edgeAlpha, 1e-4f)
        }
    }

    @Test
    fun quickClearCurrent_clearAllFour_andResetDefaultsWorkAccurately() {
        var current by mutableStateOf(ThemeSettings())

        compose.setContent {
            IYangoThemePreview {
                LiquidGlassSettingsScreen(
                    themeSettings = current,
                    onThemeSettingsChange = { current = it },
                    onBack = {},
                )
            }
        }

        // Clear only NAVIGATION.
        compose.onNodeWithText("当前分类全透明").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(GlobalGlassSettings.ClearPreset.navigation, current.globalGlass.navigation)
            assertEquals(GlobalGlassSettings.DefaultContent, current.globalGlass.content)
        }

        // Clear all four global categories at once.
        compose.onNodeWithText("全部四类全透明").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(GlobalGlassSettings.ClearPreset, current.globalGlass)
        }

        // Switch to OVERLAY and verify numeric field displays "0", then reset current category.
        compose.onNodeWithText("数值输入").performScrollTo().performClick()
        compose.onNodeWithTag("liquid-glass-cat-OVERLAY").performScrollTo().performClick()
        compose.onNodeWithTag("liquid-glass-input-OVERLAY-blurDp")
            .performScrollTo()
            .assertTextContains("0")

        compose.onNodeWithText("恢复本类默认").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(GlobalGlassSettings.DefaultOverlay, current.globalGlass.overlay)
            assertEquals(GlobalGlassSettings.ClearPreset.content, current.globalGlass.content)
        }

        // Reset all defaults via top bar button.
        compose.onNodeWithText("恢复默认").performClick()
        compose.runOnIdle {
            assertEquals(GlobalGlassSettings(), current.globalGlass)
        }
    }

    @Test
    fun mineAppearanceScreen_containsAndNavigatesToLiquidGlassRoute() {
        val visited = mutableListOf<String>()
        compose.setContent {
            IYangoThemePreview {
                MineAppearanceScreen(
                    onBack = {},
                    onNavigate = { visited += it.route },
                )
            }
        }

        compose.onNodeWithText("液态玻璃").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(MineDestination.LIQUID_GLASS.route), visited)
        }
    }

    @Test
    fun dockCategory_exposesFullDockParams_chromaticToggle_andZeroClear() {
        var current by mutableStateOf(ThemeSettings())

        compose.setContent {
            IYangoThemePreview {
                LiquidGlassSettingsScreen(
                    themeSettings = current,
                    onThemeSettingsChange = { current = it },
                    initialCategory = LiquidGlassCategory.DOCK,
                    onBack = {},
                )
            }
        }

        // Starts directly on DOCK category with full 3 groups and chromatic aberration toggle.
        compose.onNodeWithText("基础玻璃").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("边缘高光").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("指示器与阴影").performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag("liquid-glass-chromatic-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("liquid-glass-slider-DOCK-indicatorShadowAlpha")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.onNodeWithTag("liquid-glass-slider-DOCK-edgeAlphaIndicator")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }

        compose.runOnIdle {
            assertEquals(false, current.dockGlass.indicatorChromaticAberration)
            assertEquals(0f, current.dockGlass.indicatorShadowAlpha, 1e-4f)
            assertEquals(0f, current.dockGlass.edgeAlphaIndicator, 1e-4f)
        }

        compose.onNodeWithText("当前分类全透明").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(0f, current.dockSurfaceAlphaLight, 1e-4f)
            assertEquals(0f, current.dockSurfaceAlphaDark, 1e-4f)
            assertEquals(0f, current.dockGlass.blurDp, 1e-4f)
            assertEquals(0f, current.dockGlass.edgeAlphaHidden, 1e-4f)
            assertEquals(0f, current.dockGlass.indicatorSurfaceAlpha, 1e-4f)
            assertEquals(0f, current.dockGlass.indicatorInnerAlpha, 1e-4f)
            assertEquals(0f, current.dockGlass.legacyTier0AlphaLight, 1e-4f)
        }
    }

    @Test
    fun builtInAndCustomPresets_applySaveAndDeleteCorrectly() {
        var current by mutableStateOf(ThemeSettings())

        compose.setContent {
            IYangoThemePreview {
                LiquidGlassSettingsScreen(
                    themeSettings = current,
                    onThemeSettingsChange = { current = it },
                    onBack = {},
                )
            }
        }

        val crystal = LiquidGlassPreset.BuiltIns.first { it.id == "crystal" }
        compose.onNodeWithTag("liquid-glass-preset-crystal").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(crystal.globalGlass, current.globalGlass)
            assertEquals(crystal.dockGlass, current.dockGlass)
        }

        compose.onNodeWithTag("liquid-glass-save-preset-btn").performScrollTo().performClick()
        compose.onNodeWithTag("liquid-glass-preset-name-input")
            .performScrollTo()
            .performTextReplacement("夜景模式")
        compose.onNodeWithText("保存").performScrollTo().performClick()

        val savedId = compose.runOnIdle {
            assertEquals(1, current.savedGlassPresets.size)
            assertEquals("夜景模式", current.savedGlassPresets.first().name)
            assertEquals(crystal.globalGlass, current.savedGlassPresets.first().globalGlass)
            current.savedGlassPresets.first().id
        }

        val frosted = LiquidGlassPreset.BuiltIns.first { it.id == "frosted" }
        compose.onNodeWithTag("liquid-glass-preset-frosted").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(frosted.globalGlass, current.globalGlass)
        }

        compose.onNodeWithTag("liquid-glass-preset-$savedId").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(crystal.globalGlass, current.globalGlass)
        }

        compose.onNodeWithContentDescription("删除预设 夜景模式").performClick()
        compose.runOnIdle {
            assertTrue(current.savedGlassPresets.isEmpty())
        }
    }

    @Test
    fun shareCode_copyAndImportDialogAppliesPresetAndRejectsInvalidCode() {
        var current by mutableStateOf(ThemeSettings())

        compose.setContent {
            IYangoThemePreview {
                LiquidGlassSettingsScreen(
                    themeSettings = current,
                    onThemeSettingsChange = { current = it },
                    onBack = {},
                )
            }
        }

        // Copy button encodes current configuration without error.
        compose.onNodeWithTag("liquid-glass-copy-code-btn").performScrollTo().performClick()

        // Open inline import editor and reject invalid code.
        compose.onNodeWithTag("liquid-glass-import-code-btn").performScrollTo().performClick()
        compose.onNodeWithTag("liquid-glass-import-input").performScrollTo().performTextReplacement("bad-code")
        compose.onNodeWithTag("liquid-glass-import-confirm-btn").performScrollTo().performClick()
        compose.onNodeWithText("未找到有效的液态玻璃分享口令").performScrollTo().assertIsDisplayed()

        // Import valid crystal preset share code embedded in chat text.
        val crystal = LiquidGlassPreset.BuiltIns.first { it.id == "crystal" }
        val shareCode = LiquidGlassShareCodec.encode(crystal.copy(name = "冰晶分享"))
        compose.onNodeWithTag("liquid-glass-import-input")
            .performScrollTo()
            .performTextReplacement("快试试这个：$shareCode 很好看")
        compose.onNodeWithTag("liquid-glass-import-confirm-btn").performScrollTo().performClick()

        compose.runOnIdle {
            assertEquals(crystal.globalGlass, current.globalGlass)
            assertEquals(crystal.dockGlass, current.dockGlass)
            assertEquals(1, current.savedGlassPresets.size)
            assertEquals("冰晶分享", current.savedGlassPresets.first().name)
        }
    }
}
