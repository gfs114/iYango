package com.gfs.iyango.core.designsystem.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

val LocalMotionEnabled = compositionLocalOf { true }

object AppMotion {
    const val Press = 100
    const val Release = 160
    const val Content = 180
    const val Expand = 200
    const val Reorder = 220
    const val DialogEnter = 200
    const val DialogExit = 160
    const val PageEnter = 240
    const val PageExit = 200
    const val SchedulePull = 300
}

/** Replaces the data and semantics immediately. Only the new content's opacity changes. */
@Composable
fun Modifier.motionReveal(changeKey: Any?, durationMillis: Int = AppMotion.Content): Modifier {
    val opacity = rememberContentOpacity(changeKey, durationMillis)
    // Keep the steady state free of a drawing layer, including inside scrolling lists.
    val fading by remember(opacity) { derivedStateOf { opacity.value < 1f } }
    return if (fading) graphicsLayer { alpha = opacity.value } else this
}

@Composable
internal fun rememberContentOpacity(changeKey: Any?, durationMillis: Int = AppMotion.Content): State<Float> {
    val enabled = LocalMotionEnabled.current
    val lastKey = remember { arrayOf(changeKey) }
    val opacity = remember(changeKey, enabled) {
        Animatable(if (enabled && lastKey[0] != changeKey) .55f else 1f)
    }
    SideEffect { lastKey[0] = changeKey }
    LaunchedEffect(opacity) {
        if (opacity.value < 1f) opacity.animateTo(1f, tween(durationMillis))
    }
    return opacity.asState()
}
