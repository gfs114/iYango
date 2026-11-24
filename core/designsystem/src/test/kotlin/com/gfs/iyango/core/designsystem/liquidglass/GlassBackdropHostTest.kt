package com.gfs.iyango.core.designsystem.liquidglass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassBackdropHostTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyHost_paintsThemeBaseAndTracksThemeChanges() {
        var background by mutableStateOf(Color(0xFF102030))
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = background)) {
                // A contrasting window underneath reveals transparent holes in the host.
                Box(Modifier.size(100.dp).background(Color.Magenta)) {
                    GlassBackdropHost(Modifier.fillMaxSize().testTag("host")) { }
                }
            }
        }
        assertCenterColor("host", Color(0xFF102030))
        compose.runOnIdle { background = Color(0xFF303F4F) }
        assertCenterColor("host", Color(0xFF303F4F))
    }

    @Test fun wallpaperAndForeground_areVisibleInTheirOwnLayers() {
        val wallpaper = Color(0xFF2468AC)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color.Black)) {
                GlassBackdropHost(
                    modifier = Modifier.size(100.dp).testTag("host"),
                    background = { Box(Modifier.fillMaxSize().background(wallpaper)) },
                ) {
                    Box(Modifier.size(20.dp).background(Color.Green))
                }
            }
        }
        val pixels = compose.onNodeWithTag("host").captureToImage().toPixelMap()
        assertEquals(wallpaper, pixels[pixels.width / 2, pixels.height / 2])
        assertEquals(Color.Green, pixels[1, 1])
    }

    @Test fun recordedBackground_containsWallpaperAndExcludesForeground() {
        lateinit var recordedBackdrop: LayerBackdrop
        val wallpaper = Color(0xFF2468AC)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color.Black)) {
                GlassBackdropHost(
                    modifier = Modifier.size(100.dp).testTag("host"),
                    background = { Box(Modifier.fillMaxSize().background(wallpaper)) },
                ) {
                    recordedBackdrop = LocalGlassBackdrop.current as LayerBackdrop
                    Box(Modifier.fillMaxSize().background(Color.Green))
                }
            }
        }
        // Force a real draw before reading the recorded layer, then inspect the recording
        // directly rather than inferring its contents from the visible wallpaper underneath.
        assertCenterColor("host", Color.Green)
        val pixels = runBlocking { recordedBackdrop.graphicsLayer.toImageBitmap() }.toPixelMap()
        assertEquals(wallpaper, pixels[pixels.width / 2, pixels.height / 2])
    }

    @Test fun surfaceWithoutHost_preservesThemeFallbackTint() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color.Black)) {
                Box(Modifier.size(100.dp).background(Color.Magenta)) {
                    GlassSurface(
                        modifier = Modifier.fillMaxSize().testTag("fallback"),
                        shape = RectangleShape,
                        material = GlassMaterial.NAVIGATION,
                    ) { }
                }
            }
        }
        val pixels = compose.onNodeWithTag("fallback").captureToImage().toPixelMap()
        val color = pixels[pixels.width / 2, pixels.height / 2]
        // Navigation's BASIC fallback aligns with DockGlass.LEGACY_TIER0_ALPHA_DARK (.35 black over Magenta = .65).
        assertEquals(1f, color.alpha, .01f)
        assertEquals(.65f, color.red, .02f)
        assertEquals(0f, color.green, .01f)
        assertEquals(.65f, color.blue, .02f)
    }

    private fun assertCenterColor(tag: String, expected: Color) {
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        assertEquals(expected, pixels[pixels.width / 2, pixels.height / 2])
    }
}
