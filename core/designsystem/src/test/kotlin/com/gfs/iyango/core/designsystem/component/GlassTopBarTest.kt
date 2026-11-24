package com.gfs.iyango.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import com.gfs.iyango.core.designsystem.liquidglass.GlassBackdropHost
import com.gfs.iyango.core.designsystem.liquidglass.InteractiveHighlight
import com.gfs.iyango.core.designsystem.liquidglass.LocalGlassBackdrop
import com.gfs.iyango.core.designsystem.liquidglass.rememberTopBarHighlight
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled
import com.kyant.backdrop.Backdrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
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
class GlassTopBarTest {
    @get:Rule val compose = createComposeRule()

    private val DpRect.width get() = right - left
    private val DpRect.height get() = bottom - top

    @Test fun largeFont_expandsBarAndKeepsActionsReachable() {
        var back = 0
        var save = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    Box(Modifier.width(360.dp)) {
                        GlassTopBar(
                            title = "编辑课程",
                            modifier = Modifier.testTag("bar"),
                            windowInsets = WindowInsets(0, 0, 0, 0),
                            navigationIcon = {
                                GlassTopBarIconButton(onClick = { back++ }, modifier = Modifier.testTag("back")) {
                                    Text("返")
                                }
                            },
                            actions = {
                                GlassTopBarTextButton("保存", { save++ }, Modifier.testTag("save"))
                            },
                        )
                    }
                }
            }
        }
        val bar = compose.onNodeWithTag("bar").getUnclippedBoundsInRoot()
        val backBounds = compose.onNodeWithTag("back").getUnclippedBoundsInRoot()
        val saveBounds = compose.onNodeWithTag("save").getUnclippedBoundsInRoot()
        assertTrue("Two large text lines must grow beyond the 60dp minimum", bar.height > 60.dp)
        assertTrue(backBounds.width >= 48.dp && backBounds.height >= 48.dp)
        assertTrue(backBounds.right <= saveBounds.left)
        assertTrue(saveBounds.right <= bar.right)
        compose.onNodeWithTag("back").performTouchInput { click(Offset(1f, 1f)) }
        compose.onNodeWithTag("save").performClick()
        compose.runOnIdle { assertEquals(1, back); assertEquals(1, save) }
    }

    @Test fun explicitInset_isAddedOnceAndOverlayHasNoSystemInset() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                    Column {
                        GlassTopBar("页面", Modifier.testTag("bar"), windowInsets = WindowInsets(0, 24, 0, 0))
                        GlassOverlayHeader("浮层", Modifier.testTag("overlay"))
                    }
                }
            }
        }
        assertEquals(84f, compose.onNodeWithTag("bar").getUnclippedBoundsInRoot().height.value, .5f)
        assertEquals(60f, compose.onNodeWithTag("overlay").getUnclippedBoundsInRoot().height.value, .5f)
    }

    @Test fun titleActionAndLoadingState_preserveClickContract() {
        var titleClicks = 0
        var saveClicks = 0
        compose.setContent {
            MaterialTheme {
                GlassTopBar(
                    title = "第十周",
                    onTitleClick = { titleClicks++ },
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    actions = {
                        GlassTopBarTextButton("保存中", { saveClicks++ }, loading = true)
                    },
                )
            }
        }
        compose.onNodeWithText("第十周").assertIsEnabled().performClick()
        compose.onNodeWithText("保存中").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, titleClicks); assertEquals(0, saveClicks) }
    }

    @Test fun disablingDuringPressSnapsTheHighlightAndKeepsTheFingerOffset() {
        compose.mainClock.autoAdvance = false
        val motion = mutableStateOf(true)
        lateinit var highlight: InteractiveHighlight
        compose.setContent {
            CompositionLocalProvider(LocalMotionEnabled provides motion.value) {
                highlight = rememberTopBarHighlight()
                Box(Modifier.size(80.dp).testTag("control").then(highlight.gestureModifier))
            }
        }
        val control = compose.onNodeWithTag("control")
        control.performTouchInput { down(Offset(15f, 20f)) }
        compose.mainClock.advanceTimeBy(48)
        compose.runOnIdle {
            assertTrue(highlight.pressProgress > 0f && highlight.pressProgress < 1f)
            motion.value = false
        }
        compose.mainClock.advanceTimeByFrame()
        control.performTouchInput { moveTo(Offset(35f, 20f)) }
        compose.runOnIdle {
            assertEquals(1f, highlight.pressProgress, 0f)
            assertEquals(Offset(20f, 0f), highlight.offset)
        }
        control.performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(0f, highlight.pressProgress, 0f)
            assertEquals(Offset.Zero, highlight.offset)
        }
    }

    @Test fun overlayTitle_alignsWithContentInsidePaddedContainer() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp).padding(horizontal = 16.dp)) {
                    GlassOverlayHeader("浮层标题", contentPadding = PaddingValues(0.dp))
                    Text("正文内容")
                }
            }
        }
        val title = compose.onNodeWithText("浮层标题").getUnclippedBoundsInRoot()
        val body = compose.onNodeWithText("正文内容").getUnclippedBoundsInRoot()
        assertEquals(body.left.value, title.left.value, .5f)
    }

    @Test fun noActions_keepsFullTitleVisible() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                    Box(Modifier.width(320.dp)) {
                        GlassTopBar(
                            title = "我的",
                            windowInsets = WindowInsets(0, 0, 0, 0),
                            modifier = Modifier.testTag("bar"),
                        )
                    }
                }
            }
        }
        val title = compose.onNodeWithText("我的").assertIsDisplayed().getUnclippedBoundsInRoot()
        val bar = compose.onNodeWithTag("bar").getUnclippedBoundsInRoot()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("我的").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        println("title layout size=${layout.size}, constraints=${layout.layoutInput.constraints}, " +
            "paragraphWidth=${layout.multiParagraph.width}, intrinsicWidth=${layout.multiParagraph.maxIntrinsicWidth}, " +
            "lineLeft=${layout.getLineLeft(0)}, lineRight=${layout.getLineRight(0)}, ellipsized=${layout.isLineEllipsized(0)}")
        assertTrue("No-actions header must reserve usable text width", layout.layoutInput.constraints.maxWidth >= 280)
        assertEquals(1, layout.lineCount)
        assertTrue(
            "The complete title must fit: size=${layout.size}, paragraphHeight=${layout.multiParagraph.height}, " +
                "overflowWidth=${layout.didOverflowWidth}, overflowHeight=${layout.didOverflowHeight}",
            !layout.didOverflowWidth && !layout.didOverflowHeight,
        )
        assertTrue("The title must not be ellipsized", !layout.isLineEllipsized(0))
        val glyphWidth = layout.getLineRight(0) - layout.getLineLeft(0)
        assertTrue("Native Chinese title must have real glyph width: $glyphWidth", glyphWidth >= 32f)
        assertEquals(16f, title.left.value, .5f)
        assertTrue(title.right <= bar.right)
    }

    @Test fun standaloneOverlayTitle_usesSameDefaultInsetAsSheetBody() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp)) {
                    GlassOverlayHeader("选择周次")
                    Box(Modifier.padding(horizontal = 16.dp)) { Text("正文内容") }
                }
            }
        }
        val title = compose.onNodeWithText("选择周次").getUnclippedBoundsInRoot()
        val body = compose.onNodeWithText("正文内容").getUnclippedBoundsInRoot()
        assertEquals(body.left.value, title.left.value, .5f)
    }

    @Test fun childControls_receiveExportedTopBarBackdropAndDisabledTextButtonIgnoresClicks() {
        var hostBackdrop: Backdrop? = null
        var childBackdrop: Backdrop? = null
        var disabledClicks = 0
        compose.setContent {
            MaterialTheme {
                GlassBackdropHost(background = { Box(Modifier.width(360.dp)) }) {
                    hostBackdrop = LocalGlassBackdrop.current
                    GlassTopBar(
                        title = "课表",
                        windowInsets = WindowInsets(0, 24, 0, 0),
                        actions = {
                            childBackdrop = LocalGlassBackdrop.current
                            GlassTopBarTextButton(
                                text = "完成",
                                onClick = { disabledClicks++ },
                                enabled = false,
                            )
                        },
                    )
                }
            }
        }
        compose.onNodeWithText("完成").assertIsNotEnabled().performClick()
        compose.runOnIdle {
            assertNotNull("Host backdrop must be provided inside GlassBackdropHost", hostBackdrop)
            assertNotNull("Child controls must receive exported top bar backdrop", childBackdrop)
            assertNotSame(
                "Child controls inside GlassTopBar must sample the bar's exportedBackdrop (Glass on Glass)",
                hostBackdrop,
                childBackdrop,
            )
            assertEquals(0, disabledClicks)
        }
    }
}
