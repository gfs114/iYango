package com.gfs.iyango.feature.main

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.LocalGlassPageBackground
import com.gfs.iyango.feature.main.settings.WallpaperLayer
import java.io.File
import java.io.FileOutputStream
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
class MainWallpaperVisibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun wallpaperRemainsVisibleBehindTabsAndBackPreview() {
        var selected by mutableIntStateOf(0)
        var visible by mutableStateOf(true)
        var preview by mutableStateOf<Int?>(null)
        val wallpaperColor = Color(0xFF2468AC)
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(background = Color.White)) {
                Box(Modifier.size(100.dp).background(wallpaperColor).testTag("wallpaper-root")) {
                    RetainedMainTabs(
                        selectedIndex = selected,
                        visible = visible,
                        previewIndex = preview,
                        animateSwitch = false,
                    ) { Box(Modifier.fillMaxSize()) }
                }
            }
        }
        fun assertWallpaperVisible() {
            val pixels = compose.onNodeWithTag("wallpaper-root").captureToImage().toPixelMap()
            assertEquals(wallpaperColor, pixels[pixels.width / 2, pixels.height / 2])
        }
        repeat(4) { index ->
            compose.runOnIdle { selected = index }
            assertWallpaperVisible()
        }
        compose.runOnIdle {
            visible = false
            preview = 0
        }
        assertWallpaperVisible()
    }

    @Test fun backPreview_usesOfficialScaleOutAndFadeIn_andRestoresOnCancel() {
        val input = DirectNavigationEventInput()
        fun backEvent(progress: Float) = NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT, progress = progress)
        var selected by mutableIntStateOf(3)
        val wallpaperColor = Color(0xFF2468AC)
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(background = Color.White)) {
                val owner = rememberNavigationEventDispatcherOwner(parent = null)
                DisposableEffect(owner) {
                    owner.navigationEventDispatcher.addInput(input)
                    onDispose { owner.navigationEventDispatcher.removeInput(input) }
                }
                CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                    val back = rememberBackPreviewState(motionEnabled = false)
                    NavigationBackHandler(
                        back.eventState,
                        isBackEnabled = selected != 0,
                        onBackCompleted = { back.complete(onCompleted = { selected = 0 }) },
                    )
                    Box(Modifier.size(100.dp).background(wallpaperColor).testTag("wallpaper-root")) {
                        RetainedMainTabs(
                            selectedIndex = selected,
                            visible = !back.isPreviewing,
                            previewIndex = if (back.isPreviewing) 0 else null,
                            foregroundModifier = Modifier.backPreviewLayer(back),
                            backgroundModifier = Modifier.backPreviewEnterLayer(back),
                            animateSwitch = false,
                        ) { index ->
                            when (index) {
                                0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(50.dp).background(Color.Red))
                                }
                                3 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                                    Box(Modifier.size(20.dp).background(Color.Green))
                                }
                                else -> Box(Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }

        compose.runOnIdle {
            input.backStarted(backEvent(0f))
        }
        val startPixels = compose.onNodeWithTag("wallpaper-root").captureToImage().toPixelMap()
        assertEquals(wallpaperColor, startPixels[startPixels.width / 2, startPixels.height / 2])
        assertEquals(Color.Green, startPixels[5, 5])

        compose.runOnIdle { input.backProgressed(backEvent(0.95f)) }
        val latePixels = compose.onNodeWithTag("wallpaper-root").captureToImage().toPixelMap()
        assertEquals(wallpaperColor, latePixels[5, 5])
        assertEquals(false, latePixels[latePixels.width / 2, latePixels.height / 2] == wallpaperColor)

        compose.runOnIdle { input.backProgressed(backEvent(1f)) }
        val fullPixels = compose.onNodeWithTag("wallpaper-root").captureToImage().toPixelMap()
        assertEquals(Color.Red, fullPixels[fullPixels.width / 2, fullPixels.height / 2])

        compose.runOnIdle { input.backCancelled() }
        compose.runOnIdle { assertEquals(3, selected) }
    }

    @Test fun secondaryPageGlassScaffold_reusesCachedWallpaperOnFirstFrame_withoutWhiteFlash() {
        val wallpaperColor = Color(0xFF2468AC)
        val tempFile = File.createTempFile("wallpaper_cache_test", ".png")
        try {
            val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                Canvas(this).drawColor(wallpaperColor.toArgb())
            }
            FileOutputStream(tempFile).use { stream ->
                source.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            source.recycle()

            var showSecondary by mutableStateOf(false)
            compose.setContent {
                MaterialTheme(colorScheme = lightColorScheme(background = Color.White)) {
                    val pageBackground: @Composable () -> Unit = {
                        WallpaperLayer(
                            path = tempFile.absolutePath,
                            scrim = 0f,
                            blur = 0f,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    CompositionLocalProvider(LocalGlassPageBackground provides pageBackground) {
                        Box(Modifier.size(100.dp).testTag("root-wallpaper")) {
                            pageBackground()
                            if (showSecondary) {
                                GlassScaffold(
                                    modifier = Modifier.fillMaxSize().testTag("secondary-page"),
                                    containerColor = Color.White,
                                ) {
                                    Box(Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }
            }

            compose.waitUntil(timeoutMillis = 3_000) {
                val rootPixels = compose.onNodeWithTag("root-wallpaper").captureToImage().toPixelMap()
                rootPixels[rootPixels.width / 2, rootPixels.height / 2] == wallpaperColor
            }

            // Remove the source file so secondary GlassScaffold can only render the wallpaper via memory cache.
            tempFile.delete()
            compose.runOnIdle { showSecondary = true }

            val secondaryPixels = compose.onNodeWithTag("root-wallpaper").captureToImage().toPixelMap()
            assertEquals(wallpaperColor, secondaryPixels[secondaryPixels.width / 2, secondaryPixels.height / 2])
        } finally {
            tempFile.delete()
        }
    }
}
