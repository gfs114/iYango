package com.gfs.iyango.core.designsystem.liquidglass

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangularShape

private val DefaultChipShape: Shape = Capsule()

/**
 * 液态玻璃筛选/选择胶囊（替代 Material 3 FilterChip）：
 * - 选中态：主题强调色染色液态玻璃 + 清晰高亮描边与高对比内容色；
 * - 未选中态：轻量磨砂折射胶囊 + 细边；
 * - 按压跟随 `InteractiveHighlight` 与弹性形变，保留 `Role.Checkbox` 可选语义。
 */
@Composable
fun LiquidFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    shape: Shape = DefaultChipShape,
    selectedTintColor: Color = MaterialTheme.colorScheme.primary,
) {
    val tier = rememberGlassTier()
    val isLight = isGlassLightTheme()
    val hasWallpaper = LocalGlassHasWallpaper.current
    val spec = LocalGlassPalette.current.control
    val highlight = rememberLiquidHighlight()
    val pressLayerBlock = remember(highlight) {
        val block: GraphicsLayerScope.() -> Unit = { applyLiquidPressLayer(highlight) }
        block
    }

    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        selected -> Color.White
        else -> MaterialTheme.colorScheme.onSurface
    }
    val borderStroke = when {
        selected -> BorderStroke(1.dp, selectedTintColor.copy(alpha = if (enabled) 0.85f else 0.35f))
        spec.edgeAlpha <= 0.004f -> BorderStroke(
            0.75.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 0.22f else 0.10f),
        )
        isLight -> BorderStroke(0.75.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 0.55f else 0.25f))
        else -> BorderStroke(0.75.dp, Color.White.copy(alpha = if (enabled) 0.22f else 0.10f))
    }

    val innerRow: @Composable () -> Unit = {
        CompositionLocalProvider(
            LocalContentColor provides contentColor,
            LocalTextStyle provides MaterialTheme.typography.labelLarge,
        ) {
            Row(
                modifier = Modifier
                    .then(if (enabled) highlight.modifier else Modifier)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (leadingIcon != null) leadingIcon()
                label()
                if (trailingIcon != null) trailingIcon()
            }
        }
    }

    val baseMod = modifier
        .defaultMinSize(minHeight = 36.dp)
        .clip(shape)
        .selectable(
            selected = selected,
            enabled = enabled,
            role = Role.Checkbox,
            onClick = onClick,
        )
        .then(if (enabled) highlight.gestureModifier else Modifier)

    if (tier == GlassTier.BASIC || !hasWallpaper) {
        val neutralAlpha = if (tier == GlassTier.BASIC) {
            if (isLight) spec.tier0AlphaLight else spec.tier0AlphaDark
        } else {
            if (isLight) spec.tintAlphaLight else spec.tintAlphaDark
        }
        val fallbackBg = when {
            selected -> selectedTintColor.copy(alpha = if (enabled) 0.88f else 0.42f)
            neutralAlpha <= 0.004f -> Color.Transparent
            isLight -> GlassTokens.tintLight.copy(alpha = if (enabled) neutralAlpha else neutralAlpha * 0.5f)
            else -> GlassTokens.tintDark.copy(alpha = if (enabled) neutralAlpha else neutralAlpha * 0.5f)
        }
        Surface(
            modifier = baseMod
                .then(if (enabled) Modifier.graphicsLayer(pressLayerBlock) else Modifier),
            shape = shape,
            color = fallbackBg,
            contentColor = contentColor,
            border = borderStroke,
        ) {
            Box(contentAlignment = Alignment.Center) {
                innerRow()
            }
        }
        return
    }

    val backdrop = rememberGlassFallbackBackdrop()

    val canRefract = tier == GlassTier.FULL &&
        (shape is RoundedRectangularShape || shape is CornerBasedShape)
    val shapeProvider = remember(shape) { { shape } }
    val neutralTint = (if (isLight) GlassTokens.tintLight else GlassTokens.tintDark)
        .copy(alpha = if (isLight) spec.tintAlphaLight else spec.tintAlphaDark)
    val hasEdge = spec.edgeAlpha > 0.004f && spec.edgeWidthDp > 0.01f
    val highlightSpec = remember(hasEdge, spec.edgeWidthDp, spec.edgeBlurDp, spec.edgeAlpha) {
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
    }

    Box(
        modifier = baseMod
            .drawBackdrop(
                backdrop = backdrop,
                shape = shapeProvider,
                effects = {
                    // 仅在绘制阶段按需启用模糊与折射：静止滚动时不挂载 RenderEffect，且绝不在组合阶段切换节点导致点击手势中断。
                    if (highlight.pressProgress > 0.01f) {
                        if (spec.blurDp > 0.01f) {
                            blur(spec.blurDp.dp.toPx())
                        }
                        if (canRefract && spec.lensWidthDp > 0.01f && spec.lensHeightDp > 0.01f) {
                            lens(
                                refractionHeight = (spec.lensWidthDp * 0.5f).dp.toPx(),
                                refractionAmount = (spec.lensHeightDp * 0.5f).dp.toPx(),
                                depthEffect = spec.depthEffect,
                                chromaticAberration = spec.chromaticAberration,
                            )
                        }
                    }
                },
                highlight = { highlightSpec },
                layerBlock = if (enabled) pressLayerBlock else null,
                onDrawSurface = {
                    if (selected) {
                        drawRect(selectedTintColor, blendMode = BlendMode.Hue)
                        drawRect(selectedTintColor.copy(alpha = if (enabled) 0.78f else 0.35f))
                    } else if (neutralTint.alpha > 0.004f) {
                        drawRect(
                            neutralTint.copy(
                                alpha = if (enabled) neutralTint.alpha else (neutralTint.alpha * 0.5f),
                            ),
                        )
                    }
                },
            )
            .clip(shape)
            .border(borderStroke, shape),
        contentAlignment = Alignment.Center,
    ) {
        innerRow()
    }
}
