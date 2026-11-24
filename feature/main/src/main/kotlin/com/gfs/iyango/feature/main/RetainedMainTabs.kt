package com.gfs.iyango.feature.main

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import com.gfs.iyango.core.designsystem.motion.AppMotion
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.rememberLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner

/** Window-backed content (Dialog/Popup) must also honor the host's visibility. */
internal val LocalMainTabActive = compositionLocalOf { true }

/**
 * Keep only visited pages. Unplaced pages retain their composition and measurement cache,
 * but cannot draw, receive input or expose semantics. Each page has its own NavBackStackEntry
 * for Hilt ViewModels and saved state; its lifecycle stops collections while hidden.
 */
@Composable
internal fun RetainedMainTabs(
    selectedIndex: Int,
    visible: Boolean = true,
    modifier: Modifier = Modifier,
    previewIndex: Int? = null,
    prewarmVisitedMask: Int = 0,
    foregroundModifier: Modifier = Modifier,
    backgroundModifier: Modifier = Modifier,
    animateSwitch: Boolean = true,
    content: @Composable (Int) -> Unit,
) {
    require(selectedIndex in 0..3)
    var visitedMask by rememberSaveable { mutableIntStateOf(0) }
    var premeasuredMask by remember { mutableIntStateOf(0) }
    require(previewIndex == null || previewIndex in 0..3)
    val visited = visitedMask or prewarmVisitedMask or (1 shl selectedIndex) or (previewIndex?.let { 1 shl it } ?: 0)
    SideEffect { visitedMask = visited }
    val currentContent by rememberUpdatedState(content)
    val focusManager = LocalFocusManager.current
    val previousVisibility = remember { arrayOf<Any>(selectedIndex, visible) }
    SideEffect {
        if (previousVisibility[0] != selectedIndex || previousVisibility[1] != visible) {
            focusManager.clearFocus(force = true)
            previousVisibility[0] = selectedIndex
            previousVisibility[1] = visible
        }
    }
    Layout(
        modifier = modifier.fillMaxSize(),
        content = {
            repeat(4) { index ->
                if (visited and (1 shl index) != 0) key(index) {
                    val active = visible && selectedIndex == index
                    val previewing = !visible && previewIndex != null && (index == selectedIndex || index == previewIndex)
                    val isBackgroundPreview = !visible && previewIndex != null && previewIndex != selectedIndex && index == previewIndex
                    // 页签切换动画：仅对即将显示的页做淡入，隐藏页仍然只是不绘制。
                    val targetAlpha = if (active || previewing) 1f else 0f
                    val contentAlpha = if (animateSwitch && !previewing) animateFloatAsState(
                        targetValue = targetAlpha,
                        animationSpec = tween(AppMotion.Content),
                        label = "mainTabAlpha",
                    ) else rememberUpdatedState(targetAlpha)
                    val lifecycleOwner = rememberLifecycleOwner(
                        maxLifecycle = when {
                            active -> Lifecycle.State.RESUMED
                            previewing -> Lifecycle.State.STARTED
                            else -> Lifecycle.State.CREATED
                        },
                    )
                    val backDispatcher = rememberNavigationEventDispatcherOwner(enabled = active)
                    CompositionLocalProvider(
                        LocalMotionEnabled provides (LocalMotionEnabled.current && active),
                        LocalLifecycleOwner provides lifecycleOwner,
                        LocalMainTabActive provides active,
                        LocalNavigationEventDispatcherOwner provides backDispatcher,
                    ) {
                        val controller = rememberNavController()
                        Box(
                            Modifier.layoutId(index)
                                .focusProperties {
                                    onEnter = { if (!active) cancelFocusChange() }
                                }
                                .focusGroup()
                                .then(if (active) Modifier else Modifier.clearAndSetSemantics {})
                                .then(
                                    when {
                                        index == selectedIndex -> foregroundModifier
                                        isBackgroundPreview -> backgroundModifier
                                        else -> Modifier
                                    },
                                )
                                .graphicsLayer { alpha = contentAlpha.value }
                                .blockInputWhenInactive(active),
                        ) {
                            NavHost(
                                navController = controller,
                                startDestination = "content",
                                enterTransition = { EnterTransition.None },
                                exitTransition = { ExitTransition.None },
                            ) {
                                composable("content") { currentContent(index) }
                            }
                        }
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val background = if (!visible && previewIndex != null && previewIndex != selectedIndex) {
            measurables.firstOrNull { it.layoutId == previewIndex }?.measure(constraints)
        } else null
        val placeable = if (visible || previewIndex != null) {
            measurables.firstOrNull { it.layoutId == selectedIndex }?.measure(constraints)
        } else null
        if (prewarmVisitedMask != 0) {
            var newlyMeasured = premeasuredMask or (1 shl selectedIndex)
            for (index in 0..3) {
                val bit = 1 shl index
                if ((prewarmVisitedMask and bit) != 0 && (newlyMeasured and bit) == 0) {
                    measurables.firstOrNull { it.layoutId == index }?.measure(constraints)
                    newlyMeasured = newlyMeasured or bit
                }
            }
            if (newlyMeasured != premeasuredMask) {
                premeasuredMask = newlyMeasured
            }
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            background?.placeRelative(0, 0)
            placeable?.placeRelative(0, 0)
        }
    }
}
