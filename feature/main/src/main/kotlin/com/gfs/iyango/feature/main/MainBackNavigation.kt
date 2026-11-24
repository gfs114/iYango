package com.gfs.iyango.feature.main

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.NavigationEventState
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import com.gfs.iyango.core.designsystem.motion.AppMotion
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled

internal val PredictiveBackDecelerate = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)
private val PredictiveBackCornerShape = RoundedCornerShape(28.dp)
private val PredictiveBackOppositeMargin = 8.dp
private val PredictiveBackShadowElevation = 12.dp
private const val PredictiveBackScaleDelta = 0.12f
private const val PredictiveBackCommitExtraShiftFraction = 0.06f
private const val PredictiveBackScrimMaxAlpha = 0.16f

internal val LocalPredictiveBackEnabled = compositionLocalOf { true }

/** Only the small handler observes native progress; page transforms read it in Draw. */
internal class BackPreviewState(
    val eventState: NavigationEventState<NavigationEventInfo>,
    private val scope: CoroutineScope,
    private val motionEnabled: () -> Boolean,
    private val previewEnabled: () -> Boolean = { true },
) {
    internal val motion = Animatable(0f)
    internal val commitMotion = Animatable(0f)
    private var completing by mutableStateOf(false)
    private var hardwareCompletionDispatched = false
    internal var swipeEdge = NavigationEvent.EDGE_LEFT
    val isPreviewing by derivedStateOf {
        eventState.transitionState is NavigationEventTransitionState.InProgress ||
            completing || motion.value > 0f || commitMotion.value > 0f
    }
    val progress: Float get() = motion.value
    val commitProgress: Float get() = commitMotion.value

    internal suspend fun followNativeEvents() {
        snapshotFlow { eventState.transitionState to previewEnabled() }.collectLatest { (transition, previewAllowed) ->
            if (!completing) {
                when (transition) {
                    is NavigationEventTransitionState.InProgress -> {
                        if (previewAllowed) {
                            swipeEdge = transition.latestEvent.swipeEdge
                            motion.snapTo(transition.latestEvent.progress)
                        } else {
                            motion.snapTo(0f)
                        }
                    }
                    NavigationEventTransitionState.Idle -> settleTo(0f)
                }
            }
        }
    }

    internal fun complete(restoreBeforeCompletion: Boolean = false, onCompleted: () -> Unit) {
        if (completing || hardwareCompletionDispatched) return
        // A hardware/three-button Back has no rendered gesture to settle.
        if (progress <= 0f) {
            // A second event can arrive before the old handler's enabled state recomposes.
            hardwareCompletionDispatched = true
            scope.launch { withFrameNanos { }; hardwareCompletionDispatched = false }
            onCompleted()
            return
        }
        completing = true
        scope.launch {
            try {
                if (restoreBeforeCompletion) {
                    settleTo(0f)
                } else {
                    animateCommitTo(1f)
                }
                onCompleted()
                motion.snapTo(0f)
                commitMotion.snapTo(0f)
            } finally {
                completing = false
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun settleTo(target: Float) {
        if (motion.value == target) return
        snapshotFlow(motionEnabled).mapLatest { enabled ->
            if (enabled) motion.animateTo(target, tween(AppMotion.Content)) else motion.snapTo(target)
        }.first()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun animateCommitTo(target: Float) {
        if (commitMotion.value == target) return
        snapshotFlow(motionEnabled).mapLatest { enabled ->
            if (enabled) commitMotion.animateTo(target, tween(AppMotion.Content)) else commitMotion.snapTo(target)
        }.first()
    }
}

@Composable
internal fun rememberBackPreviewState(
    motionEnabled: Boolean = LocalMotionEnabled.current,
    previewEnabled: Boolean = LocalPredictiveBackEnabled.current,
): BackPreviewState {
    val events = rememberNavigationEventState<NavigationEventInfo>(currentInfo = NavigationEventInfo.None)
    val scope = rememberCoroutineScope()
    val enabled by rememberUpdatedState(motionEnabled)
    val predictiveEnabled by rememberUpdatedState(previewEnabled)
    val state = remember(events, scope) { BackPreviewState(events, scope, { enabled }, { predictiveEnabled }) }
    LaunchedEffect(state) { state.followNativeEvents() }
    return state
}

@Composable
internal fun EditorDiscardBackHandler(state: BackPreviewState, enabled: Boolean, onDiscardRequested: () -> Unit) {
    NavigationBackHandler(
        state.eventState,
        isBackEnabled = enabled,
        onBackCompleted = { state.complete(restoreBeforeCompletion = true, onCompleted = onDiscardRequested) },
    )
}

/** Shared Draw-phase predictive back transform scaling down to 0.88 and shifting away from the swiped edge. */
internal fun GraphicsLayerScope.applyPredictiveBackCardTransform(
    progress: Float,
    swipeEdge: Int,
    commitProgress: Float = 0f,
    cardStyle: Boolean = false,
) {
    val p = progress.coerceIn(0f, 1f)
    val c = commitProgress.coerceIn(0f, 1f)
    if (p <= 0f && c <= 0f) return
    val eased = PredictiveBackDecelerate.transform(p)
    val scale = (1f - eased * PredictiveBackScaleDelta) * (1f - c * 0.04f)
    scaleX = scale
    scaleY = scale
    val halfShrinkPx = size.width * eased * PredictiveBackScaleDelta * 0.5f
    val oppositeMarginPx = PredictiveBackOppositeMargin.toPx() * eased
    val baseShiftPx = (halfShrinkPx - oppositeMarginPx).coerceAtLeast(0f)
    val direction = if (swipeEdge == NavigationEvent.EDGE_RIGHT) -1f else 1f
    translationX = direction * (baseShiftPx + size.width * PredictiveBackCommitExtraShiftFraction * c)
    if (c > 0f) alpha = (1f - c).coerceIn(0f, 1f)
    if (cardStyle) {
        shape = PredictiveBackCornerShape
        clip = true
        shadowElevation = PredictiveBackShadowElevation.toPx() * eased * (1f - c)
    }
}

/** Keeps an entering opaque page at full opacity underneath while attenuating a Draw-phase scrim. */
internal fun Modifier.predictiveBackEnterScrim(
    progressProvider: () -> Float,
    commitProgressProvider: () -> Float = { 0f },
): Modifier = drawWithContent {
    drawContent()
    val p = progressProvider().coerceIn(0f, 1f)
    val c = commitProgressProvider().coerceIn(0f, 1f)
    if (p > 0f || c > 0f) {
        val eased = PredictiveBackDecelerate.transform(p)
        val scrimAlpha = PredictiveBackScrimMaxAlpha * (1f - eased * 0.65f) * (1f - c)
        if (scrimAlpha > 0.001f) drawRect(Color.Black.copy(alpha = scrimAlpha))
    }
}

internal fun Modifier.backPreviewLayer(
    state: BackPreviewState,
    cardStyle: Boolean = false,
): Modifier = graphicsLayer {
    applyPredictiveBackCardTransform(state.progress, state.swipeEdge, state.commitProgress, cardStyle = cardStyle)
}

internal fun Modifier.backPreviewEnterLayer(
    state: BackPreviewState,
    opaqueForeground: Boolean = false,
): Modifier = if (opaqueForeground) {
    predictiveBackEnterScrim({ state.progress }, { state.commitProgress })
} else {
    graphicsLayer {
        val eased = PredictiveBackDecelerate.transform(state.progress.coerceIn(0f, 1f))
        val c = state.commitProgress.coerceIn(0f, 1f)
        alpha = (eased + c * (1f - eased)).coerceIn(0f, 1f)
    }
}

internal fun Modifier.blockInputWhenInactive(active: Boolean): Modifier = pointerInput(active) {
    if (!active) awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}
