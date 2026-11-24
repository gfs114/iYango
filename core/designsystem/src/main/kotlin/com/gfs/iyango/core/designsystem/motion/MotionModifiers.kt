package com.gfs.iyango.core.designsystem.motion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** Draws press feedback from the existing click target's interaction source. */
@Composable
fun Modifier.motionPress(source: InteractionSource, enabled: Boolean = true): Modifier {
    if (!enabled) return this
    val pressed by source.collectIsPressedAsState()
    if (!LocalMotionEnabled.current) {
        val focused by source.collectIsFocusedAsState()
        return if (pressed || focused) graphicsLayer { alpha = if (pressed) 0.88f else 0.92f } else this
    }
    val scale = animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = tween(if (pressed) AppMotion.Press else AppMotion.Release),
        label = "press",
    )
    return graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}
