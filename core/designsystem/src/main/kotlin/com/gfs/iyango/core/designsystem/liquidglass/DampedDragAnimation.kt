// 移植自 Kyant0/AndroidLiquidGlass（Apache License 2.0）：
// app/src/commonMain/kotlin/com/kyant/backdrop/catalog/utils/DampedDragAnimation.kt (kmp 分支)
// 改动：包名升级至 core:designsystem；awaitFrame() 替换为等价的 withFrameNanos { }；
// 同步记录手势目标、区分点按与拖拽，并为取消提供独立回调。
package com.gfs.iyango.core.designsystem.liquidglass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 阻尼拖拽弹性动画控制器，供液态底栏、开关（LiquidToggle）与滑块（LiquidSlider）共用。
 */
class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: DampedDragAnimation.(wasDragged: Boolean) -> Unit,
    val onDragCancelled: DampedDragAnimation.() -> Unit,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
) {

    private val valueAnimationSpec =
        spring(1f, 1000f, visibilityThreshold)
    private val velocityAnimationSpec =
        spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec =
        spring(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec =
        spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec =
        spring(0.7f, 250f, 0.001f)

    private val valueAnimation =
        Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation =
        Animatable(0f, 5f)
    private val pressProgressAnimation =
        Animatable(0f, 0.001f)
    private val scaleXAnimation =
        Animatable(initialScale, 0.001f)
    private val scaleYAnimation =
        Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()

    private val velocityTracker = VelocityTracker()

    // Keep the requested target synchronous with pointer input, including a MOVE/UP
    // pair dispatched before the animation coroutine gets its next frame.
    private var requestedValue by mutableFloatStateOf(initialValue)
    private var pressed by mutableStateOf(false)
    private var motionEnabled by mutableStateOf(true)
    private var releaseJob: Job? = null

    val value: Float get() = if (motionEnabled) valueAnimation.value else requestedValue
    val progress: Float get() = (value - valueRange.start) / (valueRange.endInclusive - valueRange.start)
    val targetValue: Float get() = requestedValue
    private val targetProgress: Float get() = if (pressed) 1f else 0f
    private val targetScale: Float get() = if (pressed) pressedScale else initialScale
    val pressProgress: Float get() = if (motionEnabled) pressProgressAnimation.value else targetProgress
    val scaleX: Float get() = if (motionEnabled) scaleXAnimation.value else targetScale
    val scaleY: Float get() = if (motionEnabled) scaleYAnimation.value else targetScale
    val velocity: Float get() = if (motionEnabled) velocityAnimation.value else 0f

    val modifier: Modifier = Modifier.pointerInput(this@DampedDragAnimation) {
        var dragDistance = Offset.Zero
        var dragging = false
        try {
            inspectDragGestures(
                onDragStart = { down ->
                    dragging = true
                    dragDistance = Offset.Zero
                    onDragStarted(down.position)
                    press()
                },
                onDragEnd = {
                    dragging = false
                    onDragStopped(dragDistance.getDistance() > viewConfiguration.touchSlop)
                    release()
                },
                onDragCancel = {
                    dragging = false
                    onDragCancelled()
                    release()
                },
            ) { change, dragAmount ->
                dragDistance += dragAmount
                if (abs(dragDistance.x) > viewConfiguration.touchSlop && abs(dragDistance.x) >= abs(dragDistance.y)) {
                    change.consume()
                }
                onDrag(size, dragAmount)
            }
        } finally {
            if (dragging) {
                onDragCancelled()
                release()
            }
        }
    }

    fun updateMotionPolicy(enabled: Boolean) {
        if (motionEnabled == enabled) return
        releaseJob?.cancel()
        velocityTracker.resetTracking()
        val progress = targetProgress
        val scale = targetScale
        // Align both when disabling and when resuming after direct, unanimated pointer updates.
        animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            valueAnimation.snapTo(requestedValue)
            velocityAnimation.snapTo(0f)
            pressProgressAnimation.snapTo(progress)
            scaleXAnimation.snapTo(scale)
            scaleYAnimation.snapTo(scale)
        }
        motionEnabled = enabled
    }

    fun press() {
        releaseJob?.cancel()
        velocityTracker.resetTracking()
        pressed = true
        if (!motionEnabled) return
        if (
            pressProgressAnimation.targetValue == 1f &&
            scaleXAnimation.targetValue == pressedScale &&
            scaleYAnimation.targetValue == pressedScale
        ) {
            return
        }
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        pressed = false
        releaseJob?.cancel()
        if (!motionEnabled) return
        releaseJob = animationScope.launch {
            withFrameNanos { }
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { valueAnimation.value }
                    .filter { abs(it - valueAnimation.targetValue) < threshold }
                    .first()
            }
            if (!motionEnabled) return@launch
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    fun updateValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        requestedValue = targetValue
        if (!motionEnabled) return
        animationScope.launch {
            launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) { updateVelocity() } }
        }
    }

    fun snapValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        requestedValue = targetValue
        animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            valueAnimation.snapTo(targetValue)
        }
    }

    fun animateToValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        requestedValue = targetValue
        if (!motionEnabled) {
            pressed = false
            return
        }
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) }
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                }
                release()
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(
            Clock.System.now().toEpochMilliseconds(),
            Offset(value, 0f),
        )
        val targetVelocity = velocityTracker.calculateVelocity().x / (valueRange.endInclusive - valueRange.start)
        if (abs(targetVelocity - velocityAnimation.targetValue) >= 0.05f) {
            animationScope.launch { velocityAnimation.animateTo(targetVelocity, velocityAnimationSpec) }
        }
    }
}
