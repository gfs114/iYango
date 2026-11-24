package com.gfs.iyango.feature.main.school

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.gfs.iyango.core.designsystem.motion.AppMotion
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled
import com.gfs.iyango.feature.main.blockInputWhenInactive

@Composable
internal fun Modifier.accountArrow(expanded: Boolean): Modifier {
    val target = if (expanded) 180f else 0f
    val angle = if (LocalMotionEnabled.current) animateFloatAsState(target, tween(AppMotion.Expand), label = "accountDisclosure")
    else rememberUpdatedState(target)
    return graphicsLayer { rotationZ = angle.value }
}

@Composable
internal fun AccountDisclosure(expanded: Boolean, content: @Composable () -> Unit) {
    if (LocalMotionEnabled.current) {
        AnimatedVisibility(expanded,
            enter = expandVertically(tween(AppMotion.Expand)) + fadeIn(tween(AppMotion.Expand)),
            exit = shrinkVertically(tween(AppMotion.Expand)) + fadeOut(tween(AppMotion.Release)),
        ) {
            val interaction = if (expanded) Modifier else Modifier
                .clearAndSetSemantics {}
                .focusProperties { onEnter = { cancelFocusChange() } }
                .focusGroup()
                .onPreviewKeyEvent { true }
                .blockInputWhenInactive(false)
            Box(interaction) { content() }
        }
    } else if (expanded) content()
}
