package com.gfs.iyango.core.designsystem.liquidglass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlin.math.abs
import kotlin.math.roundToInt

private val SliderTrackHeight = 6.dp
private val SliderThumbWidth = 40.dp
private val SliderThumbHeight = 24.dp
private val SliderContainerHeight = 40.dp
private val TrackCapsuleShape = Capsule()
private val ThumbCapsuleShape = Capsule()

private fun fractionOf(value: Float, valueRange: ClosedFloatingPointRange<Float>): Float {
    val span = valueRange.endInclusive - valueRange.start
    if (span <= 0f) return 0f
    return ((value - valueRange.start) / span).coerceIn(0f, 1f)
}

private fun snapSliderValue(
    rawValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
): Float {
    val coerced = rawValue.coerceIn(valueRange.start, valueRange.endInclusive)
    if (steps <= 0) return coerced
    val span = valueRange.endInclusive - valueRange.start
    if (span <= 0f) return valueRange.start
    val intervals = steps + 1
    val stepIndex = (((coerced - valueRange.start) / span) * intervals).roundToInt().coerceIn(0, intervals)
    return (valueRange.start + span * (stepIndex.toFloat() / intervals.toFloat()))
        .coerceIn(valueRange.start, valueRange.endInclusive)
}

/**
 * AndroidLiquidGlass 液态玻璃滑块（对齐 Kyant0 LiquidSlider）：
 * - 6dp 胶囊轨道 + 激活进度条录入 [trackBackdrop]；
 * - 40x24dp 悬浮玻璃滑块采样宿主背景与轨道，按压或拖动时膨胀 1.25x 并变为高透放大镜透镜（含深度光学与色散）；
 * - 支持直接点击轨道定位、拖拽阻尼反馈、`steps` 离散吸附，以及完整 `progressSemantics` / `SetProgress` 无障碍契约。
 */
@Composable
fun LiquidSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    accentColor: Color = MaterialTheme.colorScheme.primary,
) {
    val coercedValue = value.coerceIn(valueRange.start, valueRange.endInclusive)
    val externalFraction = fractionOf(coercedValue, valueRange)

    val latestValue by rememberUpdatedState(coercedValue)
    val latestRange by rememberUpdatedState(valueRange)
    val latestSteps by rememberUpdatedState(steps)
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestOnFinished by rememberUpdatedState(onValueChangeFinished)

    val motionEnabled = LocalMotionEnabled.current
    val isLight = isGlassLightTheme()
    val tier = rememberGlassTier()
    val spec = LocalGlassPalette.current.control
    val hostBackdrop = rememberGlassFallbackBackdrop()
    val trackBackdrop = rememberLayerBackdrop()
    val scope = rememberCoroutineScope()
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val density = LocalDensity.current
    val thumbWidthPx = with(density) { SliderThumbWidth.toPx() }
    val halfThumbPx = thumbWidthPx * 0.5f

    var usableWidthPx by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableFloatStateOf(0f) } // > 0 when actively dragging/tapping

    fun fractionAtX(xPx: Float): Float {
        val raw = (xPx - halfThumbPx) / usableWidthPx.coerceAtLeast(1f)
        return (if (isLtr) raw else 1f - raw).coerceIn(0f, 1f)
    }

    fun emitFraction(rawFraction: Float): Float {
        val span = latestRange.endInclusive - latestRange.start
        val raw = latestRange.start + span * rawFraction.coerceIn(0f, 1f)
        val snapped = snapSliderValue(raw, latestRange, latestSteps)
        latestOnValueChange(snapped)
        return fractionOf(snapped, latestRange)
    }

    val dragAnim = remember(scope, isLtr) {
        DampedDragAnimation(
            animationScope = scope,
            initialValue = externalFraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.25f,
            onDragStarted = { position ->
                dragging = 1f
                val currentCenterX = halfThumbPx + usableWidthPx * (if (isLtr) targetValue else 1f - targetValue)
                val touchedThumb = abs(position.x - currentCenterX) <= halfThumbPx * 1.15f
                if (!touchedThumb) {
                    val snappedFraction = emitFraction(fractionAtX(position.x))
                    updateValue(snappedFraction)
                }
            },
            onDragStopped = { _ ->
                dragging = 0f
                latestOnFinished?.invoke()
            },
            onDragCancelled = {
                dragging = 0f
                val resetFraction = fractionOf(latestValue, latestRange)
                updateValue(resetFraction)
            },
            onDrag = { _, dragAmount ->
                val signedX = if (isLtr) dragAmount.x else -dragAmount.x
                val nextRaw = (targetValue + signedX / usableWidthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
                val snappedFraction = emitFraction(nextRaw)
                updateValue(if (latestSteps > 0) snappedFraction else nextRaw)
            },
        )
    }

    SideEffect {
        dragAnim.updateMotionPolicy(motionEnabled)
    }

    LaunchedEffect(externalFraction) {
        if (dragging <= 0f && abs(dragAnim.targetValue - externalFraction) > 0.001f) {
            dragAnim.snapValue(externalFraction)
        }
    }

    val semanticsMod = Modifier
        .minimumInteractiveComponentSize()
        .progressSemantics(coercedValue, valueRange, steps)
        .semantics(mergeDescendants = true) {
            if (!enabled) {
                disabled()
            } else {
                setProgress { target ->
                    val snapped = snapSliderValue(target, latestRange, latestSteps)
                    if (snapped != latestValue) {
                        latestOnValueChange(snapped)
                        latestOnFinished?.invoke()
                        true
                    } else {
                        false
                    }
                }
            }
        }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .then(semanticsMod)
            .then(if (enabled) dragAnim.modifier else Modifier)
            .height(SliderContainerHeight)
            .graphicsLayer { alpha = if (enabled) 1f else 0.48f },
        contentAlignment = Alignment.CenterStart,
    ) {
        val totalWidthPx = with(density) { maxWidth.toPx() }
        val computedUsable = (totalWidthPx - thumbWidthPx).coerceAtLeast(1f)
        SideEffect {
            usableWidthPx = computedUsable
        }

        val trackBg = if (isLight) Color(0x28787880) else Color(0x45787880)
        val activeColor = accentColor
        val displayFraction = dragAnim.value.coerceIn(0f, 1f)

        // 轨道层
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SliderTrackHeight)
                .then(if (tier == GlassTier.BASIC) Modifier else Modifier.layerBackdrop(trackBackdrop))
                .clip(TrackCapsuleShape)
                .background(trackBg),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(
                        ((halfThumbPx + computedUsable * displayFraction) / totalWidthPx.coerceAtLeast(1f))
                            .coerceIn(0f, 1f),
                    )
                    .height(SliderTrackHeight)
                    .clip(TrackCapsuleShape)
                    .background(activeColor),
            )
            if (steps in 1..24) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    val count = steps + 2
                    val dotRadius = 1.5.dp.toPx()
                    for (i in 1 until count - 1) {
                        val f = i.toFloat() / (count - 1).toFloat()
                        val dotX = halfThumbPx + computedUsable * (if (isLtr) f else 1f - f)
                        drawCircle(
                            color = if (f <= displayFraction) Color.White.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.45f),
                            radius = dotRadius,
                            center = Offset(dotX, size.height * 0.5f),
                        )
                    }
                }
            }
        }

        // 悬浮玻璃滑块：组合树固定不变，仅在绘制阶段的 effects 内按 pressProgress 挂载模糊与色散透镜。
        val thumbOffsetMod = Modifier.offset {
            val f = if (isLtr) displayFraction else 1f - displayFraction
            IntOffset(x = (computedUsable * f).roundToInt(), y = 0)
        }
        if (tier == GlassTier.BASIC) {
            Box(
                modifier = thumbOffsetMod
                    .size(SliderThumbWidth, SliderThumbHeight)
                    .clip(ThumbCapsuleShape)
                    .background(Color.White)
                    .border(0.6.dp, Color.Black.copy(alpha = 0.12f), ThumbCapsuleShape),
            )
        } else {
            val combinedBackdrop = rememberCombinedBackdrop(hostBackdrop, trackBackdrop)
            Box(
                modifier = thumbOffsetMod
                    .size(SliderThumbWidth, SliderThumbHeight)
                    .drawBackdrop(
                        backdrop = combinedBackdrop,
                        shape = { ThumbCapsuleShape },
                        effects = {
                            val progress = dragAnim.pressProgress
                            if (progress > 0.01f) {
                                if (spec.blurDp > 0.01f) {
                                    blur((spec.blurDp * 0.25f).dp.toPx())
                                }
                                if (tier == GlassTier.FULL && spec.lensWidthDp > 0.01f && spec.lensHeightDp > 0.01f) {
                                    lens(
                                        refractionHeight = lerp(spec.lensWidthDp * 0.33f, spec.lensWidthDp * 0.83f, progress).dp.toPx(),
                                        refractionAmount = lerp(spec.lensHeightDp * 0.33f, spec.lensHeightDp * 0.67f, progress).dp.toPx(),
                                        depthEffect = true,
                                        chromaticAberration = true,
                                    )
                                }
                            }
                        },
                        highlight = {
                            if (spec.edgeAlpha > 0.004f && spec.edgeWidthDp > 0.01f) {
                                val progress = dragAnim.pressProgress
                                Highlight(
                                    width = spec.edgeWidthDp.dp,
                                    blurRadius = spec.edgeBlurDp.dp,
                                    alpha = lerp(spec.edgeAlpha, (spec.edgeAlpha * 2f).coerceAtMost(0.9f), progress),
                                    style = HighlightStyle.Default,
                                )
                            } else {
                                null
                            }
                        },
                        shadow = {
                            val progress = dragAnim.pressProgress
                            Shadow(
                                radius = lerp(4f, 10f, progress).dp,
                                alpha = lerp(0.18f, 0.30f, progress),
                            )
                        },
                        innerShadow = {
                            val progress = dragAnim.pressProgress
                            if (progress > 0.01f) {
                                InnerShadow(
                                    radius = (4f * progress).dp,
                                    alpha = 0.35f * progress,
                                )
                            } else {
                                null
                            }
                        },
                        layerBlock = {
                            scaleX = dragAnim.scaleX
                            scaleY = dragAnim.scaleY
                            val velocity = (dragAnim.velocity / 10f).coerceIn(-0.18f, 0.18f)
                            scaleX /= 1f - velocity * 0.5f
                            scaleY *= 1f - velocity * 0.25f
                        },
                        onDrawSurface = {
                            val progress = dragAnim.pressProgress
                            val fillAlpha = if (tier == GlassTier.FULL) {
                                lerp(0.94f, 0.08f, progress)
                            } else {
                                lerp(0.95f, 0.68f, progress)
                            }
                            drawRect(Color.White.copy(alpha = fillAlpha))
                        },
                    ),
            )
        }
    }
}
