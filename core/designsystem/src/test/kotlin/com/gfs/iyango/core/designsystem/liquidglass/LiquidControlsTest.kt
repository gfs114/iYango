package com.gfs.iyango.core.designsystem.liquidglass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.kyant.backdrop.Backdrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiquidControlsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun liquidToggle_clickAndSemanticsToggle_andRespectsDisabled() {
        var checked by mutableStateOf(false)
        var enabled by mutableStateOf(true)
        compose.setContent {
            IYangoThemePreview {
                GlassBackdropHost {
                    LiquidToggle(
                        checked = checked,
                        onCheckedChange = { checked = it },
                        enabled = enabled,
                        modifier = Modifier.testTag("toggle"),
                    )
                }
            }
        }

        val node = compose.onNodeWithTag("toggle")
        node.assertIsEnabled().assertIsOff().performClick()
        compose.runOnIdle { assertEquals(true, checked) }
        node.assertIsOn()

        node.performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(false, checked) }
        node.assertIsOff()

        compose.runOnIdle { enabled = false }
        node.assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(false, checked) }
    }

    @Test
    fun liquidSlider_setProgressSemantics_andStepsQuantization() {
        var value by mutableFloatStateOf(0f)
        var finishedCalls = 0
        var enabled by mutableStateOf(true)
        compose.setContent {
            IYangoThemePreview {
                GlassBackdropHost {
                    Box(Modifier.width(300.dp)) {
                        LiquidSlider(
                            value = value,
                            onValueChange = { value = it },
                            valueRange = 0f..1f,
                            steps = 3,
                            enabled = enabled,
                            onValueChangeFinished = { finishedCalls++ },
                            modifier = Modifier.testTag("slider"),
                        )
                    }
                }
            }
        }

        val slider = compose.onNodeWithTag("slider")
        slider.assertIsEnabled().performSemanticsAction(SemanticsActions.SetProgress) { it(0.28f) }
        compose.runOnIdle {
            assertEquals(0.25f, value, 1e-4f)
            assertEquals(1, finishedCalls)
        }

        compose.runOnIdle { enabled = false }
        slider.assertIsNotEnabled()
    }

    @Test
    fun liquidButtonsAndChip_triggerClickAndRespectEnabled() {
        var clicks = 0
        var selected by mutableStateOf(false)
        compose.setContent {
            IYangoThemePreview {
                GlassBackdropHost {
                    Column {
                        LiquidButton(onClick = { clicks++ }) { Text("主按钮") }
                        LiquidOutlinedButton(onClick = { clicks++ }) { Text("次按钮") }
                        LiquidTonalButton(onClick = { clicks++ }, enabled = false) { Text("禁用按钮") }
                        LiquidFilterChip(
                            selected = selected,
                            onClick = { selected = !selected },
                            label = { Text("筛选胶囊") },
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("主按钮").assertIsEnabled().performClick()
        compose.onNodeWithText("次按钮").assertIsEnabled().performClick()
        compose.onNodeWithText("禁用按钮").assertIsNotEnabled().performClick()
        compose.onNodeWithText("筛选胶囊").assertIsNotSelected().performClick()
        compose.onNodeWithText("筛选胶囊").assertIsSelected()
        compose.runOnIdle {
            assertEquals(2, clicks)
            assertEquals(true, selected)
        }
    }

    @Test
    fun liquidCheckboxAndRadioButton_interactiveAndPassiveModes() {
        var checked by mutableStateOf(false)
        var selected by mutableStateOf(false)
        compose.setContent {
            IYangoThemePreview {
                GlassBackdropHost {
                    Column {
                        LiquidCheckbox(
                            checked = checked,
                            onCheckedChange = { checked = it },
                            modifier = Modifier.testTag("cb-active"),
                        )
                        LiquidCheckbox(
                            checked = true,
                            onCheckedChange = null,
                            modifier = Modifier.testTag("cb-passive"),
                        )
                        LiquidRadioButton(
                            selected = selected,
                            onClick = { selected = !selected },
                            modifier = Modifier.testTag("radio-active"),
                        )
                        LiquidRadioButton(
                            selected = true,
                            onClick = null,
                            modifier = Modifier.testTag("radio-passive"),
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("cb-active").assertIsOff().performClick()
        compose.onNodeWithTag("cb-active").assertIsOn()
        compose.onNode(hasClickAction() and androidx.compose.ui.test.hasTestTag("cb-passive")).assertDoesNotExist()

        compose.onNodeWithTag("radio-active").assertIsNotSelected().performClick()
        compose.onNodeWithTag("radio-active").assertIsSelected()
        compose.onNode(hasClickAction() and androidx.compose.ui.test.hasTestTag("radio-passive")).assertDoesNotExist()
    }

    @Test
    fun liquidOutlinedTextField_preservesTextInputSemantics() {
        var text by mutableStateOf("")
        compose.setContent {
            IYangoThemePreview {
                GlassBackdropHost {
                    LiquidOutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("课程名") },
                        singleLine = true,
                        modifier = Modifier.testTag("input"),
                    )
                }
            }
        }

        compose.onNodeWithTag("input").performTextReplacement("高等数学")
        compose.runOnIdle { assertEquals("高等数学", text) }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun liquidOverlays_provideWindowLocalBackdropAndActionsWork() {
        var hostBackdrop: Backdrop? = null
        var dialogBackdrop: Backdrop? = null
        var sheetBackdrop: Backdrop? = null
        var menuBackdrop: Backdrop? = null
        var showDialog by mutableStateOf(true)
        var showSheet by mutableStateOf(false)
        var showMenu by mutableStateOf(false)
        var confirmed = 0
        var menuClicks = 0

        compose.setContent {
            IYangoThemePreview {
                GlassBackdropHost {
                    hostBackdrop = LocalGlassBackdrop.current
                    Box {
                        LiquidDropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            menuBackdrop = LocalGlassBackdrop.current
                            DropdownMenuItem(
                                text = { Text("菜单项一") },
                                onClick = {
                                    menuClicks++
                                    showMenu = false
                                },
                            )
                        }
                    }
                    if (showDialog) {
                        LiquidAlertDialog(
                            onDismissRequest = { showDialog = false },
                            title = { Text("弹窗标题") },
                            text = {
                                dialogBackdrop = LocalGlassBackdrop.current
                                Text("弹窗正文")
                            },
                            confirmButton = {
                                LiquidButton(onClick = {
                                    confirmed++
                                    showDialog = false
                                }) { Text("确认") }
                            },
                        )
                    }
                    if (showSheet) {
                        LiquidModalBottomSheet(
                            onDismissRequest = { showSheet = false },
                            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                        ) {
                            sheetBackdrop = LocalGlassBackdrop.current
                            Text("抽屉正文")
                        }
                    }
                }
            }
        }

        compose.onNodeWithText("弹窗标题").assertIsDisplayed()
        compose.runOnIdle {
            assertNotNull(hostBackdrop)
            assertNotNull(dialogBackdrop)
            assertNotSame(hostBackdrop, dialogBackdrop)
        }
        compose.onNodeWithText("确认").performClick()
        compose.runOnIdle {
            assertEquals(1, confirmed)
            showSheet = true
        }
        compose.onNodeWithText("抽屉正文").assertIsDisplayed()
        compose.runOnIdle {
            assertNotNull(sheetBackdrop)
            assertNotSame(hostBackdrop, sheetBackdrop)
            showSheet = false
            showMenu = true
        }
        compose.onNodeWithText("菜单项一").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertNotNull(menuBackdrop)
            assertNotSame(hostBackdrop, menuBackdrop)
            assertEquals(1, menuClicks)
        }
    }

    @Test
    fun zeroGlassPalette_rendersAllControlsAndSurfacesSafely() {
        val zeroSpec = GlassMaterialSpec(
            blurDp = 0f,
            lensWidthDp = 0f,
            lensHeightDp = 0f,
            edgeWidthDp = 0.5f,
            edgeBlurDp = 0.6f,
            edgeAlpha = 0f,
            tintAlphaLight = 0f,
            tintAlphaDark = 0f,
            tier0AlphaLight = 0f,
            tier0AlphaDark = 0f,
        )
        val zeroPalette = GlassPalette(
            navigation = zeroSpec,
            content = zeroSpec,
            control = zeroSpec,
            overlay = zeroSpec,
        )
        var clicked = 0
        compose.setContent {
            IYangoThemePreview {
                CompositionLocalProvider(LocalGlassPalette provides zeroPalette) {
                    GlassBackdropHost {
                        GlassSurface(material = GlassMaterial.CONTENT) {
                            Column {
                                LiquidButton(onClick = { clicked++ }) { Text("透明按钮") }
                                LiquidFilterChip(
                                    selected = false,
                                    onClick = {},
                                    label = { Text("透明胶囊") },
                                )
                                LiquidToggle(checked = true, onCheckedChange = {})
                                LiquidSlider(value = 0.5f, onValueChange = {})
                                LiquidOutlinedTextField(value = "abc", onValueChange = {})
                                LiquidDialogSurface { Text("透明浮层") }
                            }
                        }
                    }
                }
            }
        }

        compose.onNodeWithText("透明按钮").assertIsDisplayed().performClick()
        compose.onNodeWithText("透明胶囊").assertIsDisplayed()
        compose.onNodeWithText("透明浮层").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, clicked) }
    }
}
