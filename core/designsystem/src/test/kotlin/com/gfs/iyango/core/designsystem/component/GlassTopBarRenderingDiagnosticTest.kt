package com.gfs.iyango.core.designsystem.component

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.liquidglass.GlassBackdropHost
import com.gfs.iyango.core.designsystem.liquidglass.LocalGlassBackdrop
import com.gfs.iyango.core.designsystem.liquidglass.rememberGlassTier
import com.gfs.iyango.core.designsystem.theme.IYangoTheme
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Local rendering evidence, not GPU/device acceptance. The foreground green backing prevents
 * visible wallpaper alone from being mistaken for a successfully sampled backdrop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassTopBarRenderingDiagnosticTest {
    @get:Rule val compose = createComposeRule()

    private data class Scene(val dark: Boolean, val host: Boolean, val pattern: Int)

    @Test fun finalPixels_hostThemeAndPatternMatrix_withLibraryEffectControls() {
        var scene by mutableStateOf(Scene(dark = false, host = true, pattern = 0))
        var tier = ""
        compose.setContent {
            IYangoTheme(darkTheme = scene.dark) {
                tier = rememberGlassTier().name
                if (scene.host) {
                    GlassBackdropHost(
                        modifier = Modifier.size(360.dp, 220.dp).testTag("scene"),
                        background = { Pattern(scene.pattern) },
                    ) { DiagnosticForeground() }
                } else {
                    Box(Modifier.size(360.dp, 220.dp).testTag("scene")) {
                        Pattern(scene.pattern)
                        DiagnosticForeground()
                    }
                }
            }
        }
        val output = File("../../build/diagnostics/top-bar-transparency-20261029").apply { mkdirs() }
        val report = mutableListOf("scene,tier,row,leftArgb,rightArgb")
        for (dark in listOf(false, true)) {
            for (host in listOf(true, false)) {
                for (pattern in 0..2) {
                    val next = Scene(dark, host, pattern)
                    compose.runOnIdle { scene = next }
                    val image = compose.onNodeWithTag("scene").captureToImage()
                    val name = "${if (dark) "dark" else "light"}-${if (host) "host" else "no-host"}-pattern-$pattern"
                    File(output, "$name.png").outputStream().use {
                        image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    val pixels = image.toPixelMap()
                    for ((row, y) in listOf("production-topbar" to 30, "library-no-effects" to 80, "library-blur" to 120, "foreground-control" to 180)) {
                        report += "$name,$tier,$row,${pixels[90, y].toArgb().toUInt().toString(16)},${pixels[270, y].toArgb().toUInt().toString(16)}"
                    }
                    // The final image must include the foreground backing. A missing root draw
                    // invalidates every comparison rather than silently becoming a glass result.
                    assertEquals(Foreground, pixels[180, 180])
                }
            }
        }
        File(output, "pixel-matrix.csv").writeText(report.joinToString("\n") + "\n")
        println(report.joinToString("\n"))
    }

    @Composable private fun Pattern(pattern: Int) {
        val themeBase = MaterialTheme.colorScheme.background
        Canvas(Modifier.fillMaxSize()) {
            if (pattern == 0) drawRect(themeBase)
            else {
                val left = if (pattern == 1) Color.Red else Color.Blue
                val right = if (pattern == 1) Color.Blue else Color.Red
                drawRect(left, size = Size(size.width / 2, size.height))
                drawRect(right, topLeft = Offset(size.width / 2, 0f), size = Size(size.width / 2, size.height))
            }
        }
    }

    @Composable private fun DiagnosticForeground() {
        Column(Modifier.fillMaxSize().background(Foreground)) {
            GlassTopBar("", windowInsets = WindowInsets(0, 0, 0, 0))
            val backdrop = LocalGlassBackdrop.current
            if (backdrop != null) {
                Box(Modifier.fillMaxWidth().height(40.dp).drawPlainBackdrop(
                    backdrop = backdrop,
                    shape = { RectangleShape },
                    effects = {},
                ))
                Box(Modifier.fillMaxWidth().height(40.dp).drawPlainBackdrop(
                    backdrop = backdrop,
                    shape = { RectangleShape },
                    effects = { blur(14.dp.toPx()) },
                ))
            }
        }
    }

    private companion object { val Foreground = Color(0xFF22AA44) }
}
