package com.gfs.iyango.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.FabPosition
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.gfs.iyango.core.designsystem.liquidglass.LocalGlassBackdrop
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/** Header-only sampling source. Body glass keeps L0 and never samples its own recording. */
internal val LocalTopBarBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * Optional L0 page wallpaper/backdrop renderer injected by the app shell so opaque secondary
 * [GlassScaffold] pages still display the global wallpaper under their cards while occluding
 * the previous route underneath.
 */
val LocalGlassPageBackground = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

/**
 * A fixed header over the current page's recorded background and body.
 *
 * Like [Scaffold], [content] occupies the full viewport; its padding includes the measured
 * top bar (including its own system insets), bottom bar or bottom window inset, and horizontal
 * window insets. Apply the top padding **inside** the scrollable content so it can scroll away:
 * use LazyColumn's contentPadding, or Modifier.verticalScroll(...).padding(innerPadding).
 * Applying Modifier.padding(innerPadding).verticalScroll(...) prevents header underlap.
 *
 * [containerColor] defaults to an opaque page background so a stacked destination continues
 * to occlude the previous page. Retained root tabs can explicitly use Color.Transparent to
 * preserve L0 wallpaper. Only this page's body is recorded; headers, snackbars, bottom bars
 * and floating actions remain outside that recording, avoiding feedback and stale-page text.
 * [contentWindowInsets] follows Material3 Scaffold's inset ownership contract.
 * [overlay] receives the same padding and samples background + body, outside their recording.
 */
@Composable
fun GlassScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    containerColor: Color = MaterialTheme.colorScheme.background,
    contentColor: Color = contentColorFor(containerColor),
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    overlay: @Composable (PaddingValues) -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val pageContentBackdrop = rememberLayerBackdrop()
    val backgroundBackdrop = LocalGlassBackdrop.current
    val headerBackdrop = if (backgroundBackdrop == null) pageContentBackdrop
        else rememberCombinedBackdrop(backgroundBackdrop, pageContentBackdrop)

    Scaffold(
        modifier = modifier,
        topBar = {
            CompositionLocalProvider(LocalTopBarBackdrop provides headerBackdrop) { topBar() }
        },
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
        floatingActionButtonPosition = floatingActionButtonPosition,
        containerColor = containerColor,
        contentColor = contentColor,
        contentWindowInsets = contentWindowInsets,
    ) { innerPadding ->
        // Record the body background as well as its content. Putting background before
        // layerBackdrop would leave it outside the recording and expose the previous page.
        val pageBackground = LocalGlassPageBackground.current
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().layerBackdrop(pageContentBackdrop).background(containerColor)) {
                if (containerColor.alpha >= 0.99f && pageBackground != null) {
                    pageBackground()
                }
                content(innerPadding)
            }
            CompositionLocalProvider(LocalGlassBackdrop provides headerBackdrop) {
                overlay(innerPadding)
            }
        }
    }
}
