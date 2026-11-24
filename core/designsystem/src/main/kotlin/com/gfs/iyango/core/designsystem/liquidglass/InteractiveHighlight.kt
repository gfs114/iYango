// 移植自 Kyant0/AndroidLiquidGlass（Apache License 2.0）：
// app/src/commonMain/kotlin/com/kyant/backdrop/catalog/utils/InteractiveHighlight.kt
// app/src/commonMain/kotlin/com/kyant/backdrop/catalog/utils/DragGestureInspector.kt
// app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidButton.kt
// 顶栏和底栏共用按压高光及手势跟随。
package com.gfs.iyango.core.designsystem.liquidglass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.lerp
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.asComposeShader
import com.kyant.backdrop.isRuntimeShaderSupported
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * 跟随按压位置的局部高光与弹性进度，供液态玻璃按钮和可交互标题使用。
 */
@Stable
class InteractiveHighlight(
    val animationScope: CoroutineScope,
    val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset },
) {
    private val pressProgressAnimationSpec = spring(0.5f, 300f, 0.001f)
    private val positionAnimationSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)

    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val positionAnimation = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)
    private var motionEnabled by mutableStateOf(true)
    private var pressed by mutableStateOf(false)
    private var startPosition = Offset.Zero

    val pressProgress: Float get() = if (motionEnabled) pressProgressAnimation.value else if (pressed) 1f else 0f
    val offset: Offset get() = positionAnimation.value - startPosition

    fun updateMotionPolicy(enabled: Boolean) {
        if (motionEnabled == enabled) return
        motionEnabled = enabled
        if (!enabled) {
            val target = if (pressed) 1f else 0f
            animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
                pressProgressAnimation.snapTo(target)
                if (!pressed) positionAnimation.snapTo(startPosition)
            }
        }
    }

    private fun release() {
        if (!pressed) return
        pressed = false
        animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (motionEnabled) {
                launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
            } else {
                pressProgressAnimation.snapTo(0f)
                positionAnimation.snapTo(startPosition)
            }
        }
    }

    private val shader by lazy(LazyThreadSafetyMode.NONE) {
        if (isRuntimeShaderSupported()) {
            RuntimeShader(
                """
uniform float2 size;
layout(color) uniform half4 color;
uniform float radius;
uniform float2 position;

half4 main(float2 coord) {
    float dist = distance(coord, position);
    float intensity = smoothstep(radius, radius * 0.5, dist);
    return color * intensity;
}"""
            )
        } else {
            null
        }
    }

    private val shaderBrush by lazy(LazyThreadSafetyMode.NONE) {
        shader?.let { ShaderBrush(it.asComposeShader()) }
    }

    val modifier: Modifier =
        Modifier.drawWithContent {
            val progress = pressProgress
            if (progress > 0f) {
                val activeShader = shader
                val activeBrush = shaderBrush
                if (activeShader != null && activeBrush != null) {
                    drawRect(
                        Color.White.copy(0.08f * progress),
                        blendMode = BlendMode.Plus,
                    )
                    activeShader.apply {
                        val pos = position(size, positionAnimation.value)
                        setFloatUniform("size", size.width, size.height)
                        setColorUniform("color", Color.White.copy(0.15f * progress))
                        setFloatUniform("radius", size.minDimension * 1.5f)
                        setFloatUniform(
                            "position",
                            pos.x.fastCoerceIn(0f, size.width),
                            pos.y.fastCoerceIn(0f, size.height),
                        )
                    }
                    drawRect(
                        activeBrush,
                        blendMode = BlendMode.Plus,
                    )
                } else {
                    drawRect(
                        Color.White.copy(0.25f * progress),
                        blendMode = BlendMode.Plus,
                    )
                }
            }

            drawContent()
        }

    val gestureModifier: Modifier =
        Modifier.pointerInput(this@InteractiveHighlight) {
            try {
                inspectDragGestures(
                    onDragStart = { down ->
                        startPosition = down.position
                        pressed = true
                        animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
                            launch { positionAnimation.snapTo(startPosition) }
                            if (motionEnabled) {
                                launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
                            } else {
                                pressProgressAnimation.snapTo(1f)
                            }
                        }
                    },
                    onDragEnd = { release() },
                    onDragCancel = { release() },
                ) { change, _ ->
                    animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        positionAnimation.snapTo(change.position)
                    }
                }
            } finally {
                release()
            }
        }
}

/**
 * 创建与当前界面动效设置联动的 [InteractiveHighlight]，供液态玻璃按钮、胶囊、复选控件与标题共用。
 */
@Composable
fun rememberLiquidHighlight(): InteractiveHighlight {
    val scope = rememberCoroutineScope()
    val highlight = remember(scope) { InteractiveHighlight(scope) }
    val motionEnabled = LocalMotionEnabled.current
    SideEffect { highlight.updateMotionPolicy(motionEnabled) }
    return highlight
}

@Composable
internal fun rememberTopBarHighlight(): InteractiveHighlight = rememberLiquidHighlight()

/**
 * 原仓库 LiquidButton 的 layerBlock 按压放大与阻尼拖拽形变。
 */
fun GraphicsLayerScope.applyLiquidPressLayer(highlight: InteractiveHighlight) {
    val width = size.width
    val height = size.height
    if (width <= 0f || height <= 0f) return

    val progress = highlight.pressProgress
    val offset = highlight.offset
    if (progress <= 0.001f && offset == Offset.Zero) return

    val scale = lerp(1f, 1f + 4f.dp.toPx() / height, progress)

    val maxOffset = size.minDimension
    val initialDerivative = 0.05f
    translationX = maxOffset * tanh(initialDerivative * offset.x / maxOffset)
    translationY = maxOffset * tanh(initialDerivative * offset.y / maxOffset)

    val maxDragScale = 4f.dp.toPx() / height
    val offsetAngle = atan2(offset.y, offset.x)
    scaleX =
        scale +
            maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension) *
            (width / height).fastCoerceAtMost(1f)
    scaleY =
        scale +
            maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension) *
            (height / width).fastCoerceAtMost(1f)
}

internal suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    awaitEachGesture {
        val initialDown = awaitFirstDown(false, PointerEventPass.Initial)
        val down = awaitFirstDown(false)
        val drag = initialDown

        onDragStart(down)
        onDrag(drag, Offset.Zero)
        val upEvent = drag(
            pointerId = drag.id,
            onDrag = { onDrag(it, it.positionChange()) },
        )
        if (upEvent == null) {
            onDragCancel()
        } else {
            onDragEnd(upEvent)
        }
    }
}

private suspend inline fun AwaitPointerEventScope.drag(
    pointerId: PointerId,
    onDrag: (PointerInputChange) -> Unit,
): PointerInputChange? {
    val isPointerUp = currentEvent.changes.fastFirstOrNull { it.id == pointerId }?.pressed != true
    if (isPointerUp) return null
    var pointer = pointerId
    while (true) {
        val change = awaitDragOrUp(pointer) ?: return null
        if (change.isConsumed) return null
        if (change.changedToUpIgnoreConsumed()) return change
        onDrag(change)
        pointer = change.id
    }
}

private suspend inline fun AwaitPointerEventScope.awaitDragOrUp(
    pointerId: PointerId,
): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent()
        val dragEvent = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (dragEvent.changedToUpIgnoreConsumed()) {
            val otherDown = event.changes.fastFirstOrNull { it.pressed }
            if (otherDown == null) {
                return dragEvent
            } else {
                pointer = otherDown.id
            }
        } else {
            if (dragEvent.previousPosition != dragEvent.position) {
                return dragEvent
            }
        }
    }
}
