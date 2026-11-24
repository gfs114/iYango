package com.gfs.iyango.core.designsystem.component

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.liquidglass.GlassBackdropHost
import com.gfs.iyango.core.designsystem.liquidglass.LocalGlassBackdrop
import com.gfs.iyango.core.designsystem.theme.IYangoTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
class GlassScaffoldTest {
    @get:Rule val compose = createComposeRule()

    @Test fun scrollingBody_changesFinalFixedHeaderPixels_andKeepsL0ForBody() {
        lateinit var scroll: androidx.compose.foundation.lazy.LazyListState
        compose.setContent {
            IYangoTheme {
                GlassBackdropHost(Modifier.size(360.dp, 360.dp).testTag("page")) {
                    val original = LocalGlassBackdrop.current
                    scroll = rememberLazyListState()
                    GlassScaffold(
                        containerColor = Color.Transparent,
                        contentWindowInsets = WindowInsets(0, 0, 0, 24),
                        topBar = { GlassTopBar("", Modifier.testTag("bar"), windowInsets = WindowInsets(0, 24, 0, 0)) },
                    ) { inner ->
                        assertSame("Body cards must retain L0 instead of sampling their own recording", original, LocalGlassBackdrop.current)
                        LazyColumn(
                            Modifier.fillMaxSize().testTag("list"),
                            state = scroll,
                            contentPadding = PaddingValues(top = inner.calculateTopPadding(), bottom = inner.calculateBottomPadding()),
                        ) {
                            items(12) { index ->
                                Box(Modifier.fillParentMaxWidth().height(100.dp).background(Color.Red).testTag("row-$index"))
                            }
                        }
                        assertEquals(24.dp, inner.calculateBottomPadding())
                    }
                }
            }
        }
        val header = compose.onNodeWithTag("bar").getUnclippedBoundsInRoot()
        assertEquals(84f, header.bottom.value, .5f)
        assertEquals(header.bottom, compose.onNodeWithTag("row-0").getUnclippedBoundsInRoot().top)
        val before = capture("scroll-before")
        compose.runOnIdle { runBlocking { scroll.scrollToItem(0, 84) } }
        val after = capture("scroll-after")
        assertEquals("The fixed header must keep its position", header, compose.onNodeWithTag("bar").getUnclippedBoundsInRoot())
        assertTrue("Body must scroll behind the fixed header", compose.onNodeWithTag("row-0").getUnclippedBoundsInRoot().top < header.bottom)
        val initial = before.toPixelMap()[180, 30]
        val scrolled = after.toPixelMap()[180, 30]
        assertTrue("A red row behind the header must change final pixels: before=$initial after=$scrolled", scrolled.red > .85f && scrolled.green < .55f && scrolled.blue < .55f)
        assertTrue(initial.green - scrolled.green > .2f)
    }

    @Test fun opaquePageBackground_blocksPreviousPage_evenInsideRecordedLayer() {
        compose.setContent {
            IYangoTheme {
                GlassBackdropHost(
                    modifier = Modifier.size(360.dp, 240.dp).testTag("page"),
                    background = { Box(Modifier.fillMaxSize().background(Color.Green)) },
                ) {
                    GlassScaffold(
                        containerColor = Color(0xFF14283C),
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        topBar = { GlassTopBar("", windowInsets = WindowInsets(0, 0, 0, 0)) },
                    ) { }
                }
            }
        }
        val image = capture("opaque-page").toPixelMap()
        assertEquals(Color(0xFF14283C), image[180, 180])
        val header = image[180, 30]
        assertTrue("Header must sample this page's opaque blue background, not the previous green page", header.blue > header.green)
    }

    @Test fun fontScale_remeasuresHeaderAndScrollableLeadingPaddingTogether() {
        var scale by mutableStateOf(1f)
        compose.setContent {
            IYangoTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    GlassScaffold(
                        modifier = Modifier.size(360.dp, 360.dp).testTag("page"),
                        contentWindowInsets = WindowInsets(0, 0, 0, 24),
                        topBar = {
                            GlassTopBar("课程详情", modifier = Modifier.testTag("bar"), windowInsets = WindowInsets(0, 24, 0, 0))
                        },
                    ) { inner ->
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(inner)) {
                            Box(Modifier.size(100.dp).testTag("first"))
                        }
                    }
                }
            }
        }
        val before = compose.onNodeWithTag("bar").getUnclippedBoundsInRoot()
        assertEquals(before.bottom, compose.onNodeWithTag("first").getUnclippedBoundsInRoot().top)
        compose.runOnIdle { scale = 2f }
        val after = compose.onNodeWithTag("bar").getUnclippedBoundsInRoot()
        assertTrue(after.bottom - after.top > before.bottom - before.top)
        assertEquals(after.bottom, compose.onNodeWithTag("first").getUnclippedBoundsInRoot().top)
    }

    @Test fun headerTouchBarrier_blocksHiddenItems_andPreservesExplicitActions() {
        lateinit var scroll: androidx.compose.foundation.lazy.LazyListState
        var titleEnabled by mutableStateOf(false)
        var hiddenClicks = 0
        var visibleClicks = 0
        var titleClicks = 0
        var backClicks = 0
        var saveClicks = 0
        compose.setContent {
            IYangoTheme {
                scroll = rememberLazyListState()
                GlassScaffold(
                    modifier = Modifier.size(360.dp, 360.dp),
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    topBar = {
                        GlassTopBar(
                            title = "Header",
                            modifier = Modifier.testTag("bar"),
                            windowInsets = WindowInsets(0, 24, 0, 0),
                            onTitleClick = if (titleEnabled) ({ titleClicks++ }) else null,
                            navigationIcon = {
                                GlassTopBarIconButton({ backClicks++ }, Modifier.testTag("back")) { Text("B") }
                            },
                            actions = { GlassTopBarTextButton("Save", { saveClicks++ }, Modifier.testTag("save")) },
                        )
                    },
                ) { inner ->
                    LazyColumn(Modifier.fillMaxSize().testTag("list"), state = scroll, contentPadding = inner) {
                        items(5) { index ->
                            Box(Modifier.fillParentMaxWidth().height(200.dp).testTag("touch-row-$index").clickable {
                                if (index == 0) hiddenClicks++ else visibleClicks++
                            })
                        }
                    }
                }
            }
        }
        compose.runOnIdle { runBlocking { scroll.scrollToItem(0, 84) } }
        compose.onNodeWithTag("bar").performTouchInput {
            click(Offset(180f, 10f)) // Empty status-bar/background area.
            click(Offset(100f, 54f)) // Non-clickable title text area.
        }
        compose.runOnIdle { assertEquals("Occluded list items must not receive header taps", 0, hiddenClicks) }
        compose.onNodeWithTag("back").performTouchInput { click() }
        compose.onNodeWithTag("save").performTouchInput { click() }
        compose.runOnIdle { titleEnabled = true }
        compose.onNodeWithTag("bar").performTouchInput { click(Offset(100f, 54f)) }
        compose.onNodeWithTag("list").performTouchInput { click(Offset(180f, 250f)) }
        compose.runOnIdle {
            assertEquals(0, hiddenClicks)
            assertEquals(1, titleClicks)
            assertEquals(1, backClicks)
            assertEquals(1, saveClicks)
            assertEquals(1, visibleClicks)
        }
    }

    private fun capture(name: String): androidx.compose.ui.graphics.ImageBitmap {
        val image = compose.onNodeWithTag("page").captureToImage()
        val output = File("../../build/diagnostics/top-bar-transparency-20261029/$name.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        return image
    }
}
