package com.gfs.iyango.core.designsystem.liquidglass

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.motion.AppMotion
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

private val CheckboxBoxSize = 22.dp
private val CheckboxShape = RoundedRectangle(6.dp)
private val RadioSize = 22.dp
private val RadioShape = Capsule()

/**
 * 液态玻璃复选框（替代 Material 3 Checkbox）：
 * 当 `onCheckedChange == null` 时作为纯视觉指示器渲染（不附加 `toggleable` 与额外热区，
 * 避免与外层整行点击叠加触发两次）。
 */
@Composable
fun LiquidCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accentColor: Color = MaterialTheme.colorScheme.primary,
) {
    val motionEnabled = LocalMotionEnabled.current
    val isLight = isGlassLightTheme()
    val tier = rememberGlassTier()
    val backdrop = rememberGlassFallbackBackdrop()
    val spec = LocalGlassPalette.current.control
    val interactive = onCheckedChange != null
    val highlight = if (interactive) rememberLiquidHighlight() else null
    val pressLayerBlock = remember(highlight) {
        if (highlight != null) {
            val block: GraphicsLayerScope.() -> Unit = { applyLiquidPressLayer(highlight) }
            block
        } else {
            null
        }
    }

    val targetProgress = if (checked) 1f else 0f
    val checkProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(if (motionEnabled) AppMotion.Content else 0),
        label = "liquidCheckbox",
    )

    val boxBorderColor by animateColorAsState(
        targetValue = when {
            checked -> accentColor
            isLight -> MaterialTheme.colorScheme.outline
            else -> Color.White.copy(alpha = 0.36f)
        },
        animationSpec = tween(if (motionEnabled) AppMotion.Content else 0),
        label = "liquidCheckboxBorder",
    )

    val containerModifier = if (onCheckedChange != null) {
        modifier
            .minimumInteractiveComponentSize()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            )
            .then(if (enabled && highlight != null) highlight.gestureModifier else Modifier)
    } else {
        modifier
    }

    val neutralTint = (if (isLight) GlassTokens.tintLight else GlassTokens.tintDark)
        .copy(alpha = if (isLight) spec.tintAlphaLight else spec.tintAlphaDark)
    val hasWallpaper = LocalGlassHasWallpaper.current
    val useGlassBackdrop = tier != GlassTier.BASIC && hasWallpaper && interactive

    Box(
        modifier = containerModifier.graphicsLayer { alpha = if (enabled) 1f else 0.45f },
        contentAlignment = Alignment.Center,
    ) {
        val hasEdge = spec.edgeAlpha > 0.004f && spec.edgeWidthDp > 0.01f
        val boxMod = if (!useGlassBackdrop) {
            Modifier
                .size(CheckboxBoxSize)
                .then(if (enabled && pressLayerBlock != null) Modifier.graphicsLayer(pressLayerBlock) else Modifier)
        } else {
            Modifier
                .size(CheckboxBoxSize)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { CheckboxShape },
                    effects = {
                        if (highlight != null && highlight.pressProgress > 0.01f) {
                            if (spec.blurDp > 0.01f) {
                                blur(spec.blurDp.dp.toPx())
                            }
                            if (tier == GlassTier.FULL && spec.lensWidthDp > 0.01f && spec.lensHeightDp > 0.01f) {
                                lens(
                                    refractionHeight = (spec.lensWidthDp / 3f).dp.toPx(),
                                    refractionAmount = (spec.lensHeightDp / 3f).dp.toPx(),
                                )
                            }
                        }
                    },
                    highlight = {
                        if (hasEdge) {
                            Highlight(
                                width = spec.edgeWidthDp.dp,
                                blurRadius = spec.edgeBlurDp.dp,
                                alpha = spec.edgeAlpha,
                                style = HighlightStyle.Default,
                            )
                        } else {
                            null
                        }
                    },
                    layerBlock = if (enabled) pressLayerBlock else null,
                    onDrawSurface = {
                        if (checkProgress > 0.01f) {
                            drawRect(accentColor, blendMode = BlendMode.Hue)
                            drawRect(accentColor.copy(alpha = 0.85f * checkProgress))
                        } else if (neutralTint.alpha > 0.004f) {
                            drawRect(neutralTint)
                        }
                    },
                )
        }

        val fallbackBg = if (!useGlassBackdrop) {
            if (checkProgress > 0.01f) {
                accentColor.copy(alpha = 0.88f * checkProgress)
            } else if (neutralTint.alpha > 0.004f) {
                neutralTint
            } else {
                Color.Transparent
            }
        } else {
            Color.Transparent
        }

        Surface(
            modifier = boxMod.clip(CheckboxShape),
            shape = CheckboxShape,
            color = fallbackBg,
            border = BorderStroke(1.25.dp, boxBorderColor),
        ) {
            Box(
                modifier = if (enabled && highlight != null) highlight.modifier else Modifier,
                contentAlignment = Alignment.Center,
            ) {
                if (checkProgress > 0.01f) {
                    Canvas(modifier = Modifier.size(CheckboxBoxSize)) {
                        val w = size.width
                        val h = size.height
                        val p = checkProgress
                        val path = Path().apply {
                            val startX = w * 0.24f
                            val startY = h * 0.52f
                            val midX = w * 0.44f
                            val midY = h * 0.72f
                            val endX = w * 0.78f
                            val endY = h * 0.30f
                            moveTo(startX, startY)
                            if (p <= 0.45f) {
                                val seg = p / 0.45f
                                lineTo(
                                    startX + (midX - startX) * seg,
                                    startY + (midY - startY) * seg,
                                )
                            } else {
                                lineTo(midX, midY)
                                val seg = (p - 0.45f) / 0.55f
                                lineTo(
                                    midX + (endX - midX) * seg,
                                    midY + (endY - midY) * seg,
                                )
                            }
                        }
                        drawPath(
                            path = path,
                            color = Color.White.copy(alpha = p),
                            style = Stroke(
                                width = 2.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 液态玻璃单选圆钮（替代 Material 3 RadioButton）：
 * 当 `onClick == null` 时同样不附带 `selectable` 与额外内边距，保留外层整行单选语义。
 */
@Composable
fun LiquidRadioButton(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accentColor: Color = MaterialTheme.colorScheme.primary,
) {
    val motionEnabled = LocalMotionEnabled.current
    val isLight = isGlassLightTheme()
    val tier = rememberGlassTier()
    val backdrop = rememberGlassFallbackBackdrop()
    val spec = LocalGlassPalette.current.control
    val interactive = onClick != null
    val highlight = if (interactive) rememberLiquidHighlight() else null
    val pressLayerBlock = remember(highlight) {
        if (highlight != null) {
            val block: GraphicsLayerScope.() -> Unit = { applyLiquidPressLayer(highlight) }
            block
        } else {
            null
        }
    }

    val dotRadius by animateDpAsState(
        targetValue = if (selected) 5.dp else 0.dp,
        animationSpec = tween(if (motionEnabled) AppMotion.Content else 0),
        label = "liquidRadioDot",
    )
    val ringColor by animateColorAsState(
        targetValue = when {
            selected -> accentColor
            isLight -> MaterialTheme.colorScheme.outline
            else -> Color.White.copy(alpha = 0.36f)
        },
        animationSpec = tween(if (motionEnabled) AppMotion.Content else 0),
        label = "liquidRadioRing",
    )

    val containerModifier = if (onClick != null) {
        modifier
            .minimumInteractiveComponentSize()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .then(if (enabled && highlight != null) highlight.gestureModifier else Modifier)
    } else {
        modifier
    }

    val neutralTint = (if (isLight) GlassTokens.tintLight else GlassTokens.tintDark)
        .copy(alpha = if (isLight) spec.tintAlphaLight else spec.tintAlphaDark)
    val hasWallpaper = LocalGlassHasWallpaper.current
    val useGlassBackdrop = tier != GlassTier.BASIC && hasWallpaper && interactive

    Box(
        modifier = containerModifier.graphicsLayer { alpha = if (enabled) 1f else 0.45f },
        contentAlignment = Alignment.Center,
    ) {
        val hasEdge = spec.edgeAlpha > 0.004f && spec.edgeWidthDp > 0.01f
        val baseMod = if (!useGlassBackdrop) {
            Modifier
                .size(RadioSize)
                .then(if (enabled && pressLayerBlock != null) Modifier.graphicsLayer(pressLayerBlock) else Modifier)
        } else {
            Modifier
                .size(RadioSize)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RadioShape },
                    effects = {
                        if (highlight != null && highlight.pressProgress > 0.01f) {
                            if (spec.blurDp > 0.01f) {
                                blur(spec.blurDp.dp.toPx())
                            }
                            if (tier == GlassTier.FULL && spec.lensWidthDp > 0.01f && spec.lensHeightDp > 0.01f) {
                                lens(
                                    refractionHeight = (spec.lensWidthDp / 3f).dp.toPx(),
                                    refractionAmount = (spec.lensHeightDp / 3f).dp.toPx(),
                                )
                            }
                        }
                    },
                    highlight = {
                        if (hasEdge) {
                            Highlight(
                                width = spec.edgeWidthDp.dp,
                                blurRadius = spec.edgeBlurDp.dp,
                                alpha = spec.edgeAlpha,
                                style = HighlightStyle.Default,
                            )
                        } else {
                            null
                        }
                    },
                    layerBlock = if (enabled) pressLayerBlock else null,
                    onDrawSurface = {
                        if (neutralTint.alpha > 0.004f) {
                            drawRect(neutralTint)
                        }
                        if (selected) {
                            drawRect(accentColor.copy(alpha = 0.14f))
                        }
                    },
                )
        }

        val fallbackBg = if (!useGlassBackdrop) {
            if (selected) accentColor.copy(alpha = 0.14f) else if (neutralTint.alpha > 0.004f) neutralTint else Color.Transparent
        } else {
            Color.Transparent
        }

        Surface(
            modifier = baseMod.clip(RadioShape),
            shape = RadioShape,
            color = fallbackBg,
            border = BorderStroke(if (selected) 2.dp else 1.25.dp, ringColor),
        ) {
            Canvas(
                modifier = Modifier
                    .size(RadioSize)
                    .then(if (enabled && highlight != null) highlight.modifier else Modifier),
            ) {
                if (dotRadius > 0.dp) {
                    drawCircle(
                        color = accentColor,
                        radius = dotRadius.toPx(),
                        center = Offset(size.width * 0.5f, size.height * 0.5f),
                    )
                }
            }
        }
    }
}
