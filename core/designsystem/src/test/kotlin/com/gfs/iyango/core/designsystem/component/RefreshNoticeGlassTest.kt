package com.gfs.iyango.core.designsystem.component

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.liquidglass.GlassBackdropHost
import com.gfs.iyango.core.designsystem.liquidglass.LocalGlassBackdrop
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled
import com.gfs.iyango.core.designsystem.theme.IYangoTheme
import com.kyant.backdrop.backdrops.LayerBackdrop
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
class RefreshNoticeGlassTest {
    @get:Rule val compose = createComposeRule()

    @Test fun noticeSamplesItsBackdropAndKeepsTheSelectedThemeHue() {
        var background by mutableStateOf(Color.White)
        compose.setContent {
            IYangoTheme(accentHex = "#2563EB") {
                GlassBackdropHost(
                    modifier = Modifier.size(360.dp, 200.dp).testTag("page"),
                    hasWallpaper = true,
                    background = { Box(Modifier.fillMaxSize().background(background)) },
                ) {
                    RefreshNoticeCapsule("刷新成功", durationMillis = 0)
                }
            }
        }
        val light = capture("light-backdrop").toPixelMap()
        compose.runOnIdle { background = Color.Black }
        val dark = capture("dark-backdrop").toPixelMap()
        val before = light[light.width / 2, light.height - 8]
        val after = dark[dark.width / 2, dark.height - 8]
        assertTrue("Backdrop changes must reach the notice: $before -> $after", before.green - after.green > .1f)
        assertTrue("The selected blue accent must remain visible", before.blue > before.red && after.blue > after.red)
    }

    @Test fun overlaySeesTheBodyButNeverAppearsInTheRecordedBody() {
        lateinit var recorded: LayerBackdrop
        compose.setContent {
            IYangoTheme {
                GlassScaffold(
                    modifier = Modifier.size(200.dp).testTag("page"),
                    topBar = { recorded = LocalTopBarBackdrop.current as LayerBackdrop },
                    overlay = {
                        assertSame(recorded, LocalGlassBackdrop.current)
                        Box(Modifier.size(40.dp).background(Color.Green))
                    },
                ) {
                    assertNull("Body glass must not receive its own recording", LocalGlassBackdrop.current)
                    Box(Modifier.fillMaxSize().background(Color.Red))
                }
            }
        }
        val visible = compose.onNodeWithTag("page").captureToImage().toPixelMap()
        assertEquals(Color.Green, visible[10, 10])
        val body = runBlocking { recorded.graphicsLayer.toImageBitmap() }.toPixelMap()
        assertEquals("The overlay must stay outside its sampling source", Color.Red, body[10, 10])
    }

    @Test @Config(sdk = [28]) fun legacyFallbackKeepsTheMessageAndFollowsThemeChanges() {
        var accent by mutableStateOf("#2563EB")
        compose.setContent {
            IYangoTheme(accentHex = accent) {
                Box(Modifier.size(360.dp, 200.dp).background(Color.Black)) {
                    RefreshNoticeCapsule("刷新成功", durationMillis = 0)
                }
            }
        }
        compose.onNodeWithText("刷新成功").assertIsDisplayed()
        val blue = capture("fallback-blue").toPixelMap()
        compose.runOnIdle { accent = "#FFDD00" }
        val yellow = capture("fallback-yellow").toPixelMap()
        val before = blue[blue.width / 2, blue.height - 8]
        val after = yellow[yellow.width / 2, yellow.height - 8]
        assertTrue(before.blue > before.red)
        assertTrue(after.red > .85f && after.blue < .1f)
    }

    @Test fun staticNoticeBlocksTapsInsideTheCapsuleAndLeavesThePageUsable() {
        var clicks = 0
        compose.setContent {
            IYangoTheme {
                GlassScaffold(
                    modifier = Modifier.size(360.dp, 200.dp).testTag("page"),
                    overlay = { RefreshNoticeCapsule("刷新成功", durationMillis = 0) },
                ) { Box(Modifier.fillMaxSize().clickable { clicks++ }) }
            }
        }
        compose.onNodeWithTag("refresh-notice-capsule").performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, clicks) }
        compose.onNodeWithTag("page").performTouchInput { click(androidx.compose.ui.geometry.Offset(10f, 150f)) }
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test @Config(sdk = [28]) fun togglingMotionPreservesTheReadingTimeAndDropsAnExitingNotice() {
        compose.mainClock.autoAdvance = false
        var message by mutableStateOf<String?>("刷新成功")
        var motionEnabled by mutableStateOf(true)
        var dismissals = 0
        compose.setContent {
            CompositionLocalProvider(LocalMotionEnabled provides motionEnabled) {
                RefreshNoticeCapsule(message, onDismiss = { dismissals++; message = null }, durationMillis = 400)
            }
        }
        compose.onNodeWithText("刷新成功").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(80)
        compose.runOnIdle { androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { motionEnabled = false } }
        compose.mainClock.advanceTimeBy(176)
        compose.onNodeWithText("刷新成功").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, dismissals)
            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { motionEnabled = true }
        }
        compose.mainClock.advanceTimeUntil(240) { dismissals == 1 }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(1, dismissals) }
        compose.onNodeWithText("刷新成功").assertExists()
        compose.runOnIdle { androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { motionEnabled = false } }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("刷新成功").assertDoesNotExist()
    }

    private fun capture(name: String): androidx.compose.ui.graphics.ImageBitmap {
        val image = compose.onNodeWithTag("refresh-notice-capsule").captureToImage()
        val output = File("../../build/diagnostics/refresh-notice-glass-20261112/$name.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        return image
    }
}
