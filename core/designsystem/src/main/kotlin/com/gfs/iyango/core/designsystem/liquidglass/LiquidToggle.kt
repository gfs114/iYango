package com.gfs.iyango.core.designsystem.liquidglass

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.gfs.iyango.core.designsystem.motion.AppMotion
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

private val TrackWidth = 64.dp
private val TrackHeight = 28.dp
private val ThumbWidth = 40.dp
private val ThumbHeight = 24.dp
private val TrackInset = 2.dp
private val TrackTravelDistance = TrackWidth - ThumbWidth - TrackInset * 2
private val ThumbShape = Capsule()
private val TrackShape = Capsule()

/**
 * AndroidLiquidGlass 液态玻璃开关（对齐 Kyant0 LiquidToggle）：
 * - 64x28dp 胶囊轨道录入局部 [trackBackdrop]；
 * - 40x24dp 胶囊滑块结合宿主背景与轨道图层做实时模糊、折射（含色散）与内外阴影；
 * - 按压或拖拽时滑块变为高透透镜玻璃并随 [DampedDragAnimation] 弹性形变；
 * - Android 8–11（[GlassTier.BASIC]）自动回退清爽胶囊开关，保留完整无障碍与点击/拖拽语义。
 */
@Composable
fun LiquidToggle(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accentColor: Color = MaterialTheme.colorScheme.primary,
) {
    val latestChecked by rememberUpdatedState(checked)
    val latestOnCheckedChange by rememberUpdatedState(onCheckedChange)
    val motionEnabled = LocalMotionEnabled.current
    val isLight = isGlassLightTheme()
    val tier = rememberGlassTier()
    val spec = LocalGlassPalette.current.control
    val hostBackdrop = rememberGlassFallbackBackdrop()
    val trackBackdrop = rememberLayerBackdrop()
    val scope = rememberCoroutineScope()
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val density = LocalDensity.current
    val travelPx = with(density) { TrackTravelDistance.toPx() }.coerceAtLeast(1f)

    val dragAnim = remember(scope, travelPx, isLtr) {
        DampedDragAnimation(
            animationScope = scope,
            initialValue = if (checked) 1f else 0f,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.15f,
            onDragStarted = {},
            onDragStopped = { wasDragged ->
                val callback = latestOnCheckedChange
                if (callback != null) {
                    val next = if (wasDragged) targetValue >= 0.5f else !latestChecked
                    animateToValue(if (next) 1f else 0f)
                    if (next != latestChecked) {
                        callback(next)
                    }
                } else {
                    animateToValue(if (latestChecked) 1f else 0f)
                }
            },
            onDragCancelled = {
                animateToValue(if (latestChecked) 1f else 0f)
            },
            onDrag = { _, dragAmount ->
                if (latestOnCheckedChange != null) {
                    val signedX = if (isLtr) dragAmount.x else -dragAmount.x
                    updateValue((targetValue + signedX / travelPx).coerceIn(0f, 1f))
                }
            },
        )
    }

    SideEffect {
        dragAnim.updateMotionPolicy(motionEnabled)
    }

    LaunchedEffect(checked) {
        val target = if (checked) 1f else 0f
        if (abs(dragAnim.targetValue - target) > 0.01f) {
            dragAnim.animateToValue(target)
        }
    }

    val offTrackColor = if (isLight) {
        Color(0x2E787880)
    } else {
        Color(0x47787880)
    }
    val onTrackColor = accentColor.copy(alpha = if (enabled) 0.92f else 0.45f)
    val animatedBasicTrackColor by animateColorAsState(
        targetValue = if (checked) onTrackColor else offTrackColor,
        animationSpec = tween(if (motionEnabled) AppMotion.Content else 0),
        label = "liquidToggleBasicTrack",
    )

    val interactive = enabled && onCheckedChange != null
    val semanticsModifier = Modifier
        .minimumInteractiveComponentSize()
        .semantics(mergeDescendants = true) {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
            if (!enabled) {
                disabled()
            } else if (onCheckedChange != null) {
                onClick {
                    latestOnCheckedChange?.invoke(!latestChecked)
                    true
                }
            }
        }

    Box(
        modifier = modifier
            .then(semanticsModifier)
            .then(if (interactive) dragAnim.modifier else Modifier)
            .graphicsLayer { alpha = if (enabled) 1f else 0.48f },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(TrackWidth, TrackHeight),
            contentAlignment = Alignment.CenterStart,
        ) {
            // 轨道层：仅录制轨道底色进 trackBackdrop，避免滑块自采样。
            val trackBgModifier = if (tier == GlassTier.BASIC) {
                Modifier.background(animatedBasicTrackColor, TrackShape)
            } else {
                Modifier
                    .layerBackdrop(trackBackdrop)
                    .background(
                        lerp(offTrackColor, onTrackColor, dragAnim.value.coerceIn(0f, 1f)),
                        TrackShape,
                    )
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(TrackShape)
                    .then(trackBgModifier)
                    .border(
                        width = 0.6.dp,
                        color = if (isLight) {
                            Color.Black.copy(alpha = 0.08f)
                        } else {
                            Color.White.copy(alpha = 0.16f)
                        },
                        shape = TrackShape,
                    ),
            )

            // 滑块层：组合树保持恒定，仅在绘制阶段的 effects 内按 pressProgress 挂载模糊与色散透镜。
            val thumbOffsetMod = Modifier.offset {
                val fraction = dragAnim.value.coerceIn(0f, 1f)
                val xFraction = if (isLtr) fraction else 1f - fraction
                val xPx = (TrackInset.toPx() + travelPx * xFraction).roundToInt()
                IntOffset(x = xPx, y = 0)
            }

            if (tier == GlassTier.BASIC) {
                Box(
                    modifier = thumbOffsetMod
                        .padding(vertical = TrackInset)
                        .size(ThumbWidth, ThumbHeight)
                        .clip(ThumbShape)
                        .background(Color.White)
                        .border(0.5.dp, Color.Black.copy(alpha = 0.08f), ThumbShape),
                )
            } else {
                val combinedBackdrop = rememberCombinedBackdrop(hostBackdrop, trackBackdrop)
                Box(
                    modifier = thumbOffsetMod
                        .size(ThumbWidth, ThumbHeight)
                        .drawBackdrop(
                            backdrop = combinedBackdrop,
                            shape = { ThumbShape },
                            effects = {
                                val progress = dragAnim.pressProgress
                                if (progress > 0.01f) {
                                    if (spec.blurDp > 0.01f) {
                                        blur(lerp(spec.blurDp, 0.5f.coerceAtMost(spec.blurDp), progress).dp.toPx())
                                    }
                                    if (tier == GlassTier.FULL && spec.lensWidthDp > 0.01f && spec.lensHeightDp > 0.01f) {
                                        lens(
                                            refractionHeight = lerp(spec.lensWidthDp * 0.25f, spec.lensWidthDp * 0.5f, progress).dp.toPx(),
                                            refractionAmount = lerp(spec.lensHeightDp * 0.25f, spec.lensHeightDp * 0.5f, progress).dp.toPx(),
                                            depthEffect = false,
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
                                        alpha = lerp(spec.edgeAlpha, (spec.edgeAlpha * 1.8f).coerceAtMost(0.85f), progress),
                                        style = HighlightStyle.Default,
                                    )
                                } else {
                                    null
                                }
                            },
                            shadow = {
                                val progress = dragAnim.pressProgress
                                Shadow(
                                    radius = lerp(4f, 8f, progress).dp,
                                    alpha = lerp(0.18f, 0.28f, progress),
                                )
                            },
                            innerShadow = {
                                val progress = dragAnim.pressProgress
                                if (progress > 0.01f) {
                                    InnerShadow(
                                        radius = (3f * progress).dp,
                                        alpha = 0.35f * progress,
                                    )
                                } else {
                                    null
                                }
                            },
                            layerBlock = {
                                scaleX = dragAnim.scaleX
                                scaleY = dragAnim.scaleY
                            },
                            onDrawSurface = {
                                val progress = dragAnim.pressProgress
                                val fillAlpha = if (tier == GlassTier.FULL) {
                                    lerp(0.94f, 0.14f, progress)
                                } else {
                                    lerp(0.95f, 0.72f, progress)
                                }
                                drawRect(Color.White.copy(alpha = fillAlpha))
                            },
                        ),
                )
            }
        }
    }
}
