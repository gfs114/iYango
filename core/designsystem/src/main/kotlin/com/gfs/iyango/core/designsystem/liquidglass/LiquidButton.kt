package com.gfs.iyango.core.designsystem.liquidglass

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangularShape

private val DefaultButtonShape: Shape = Capsule()
private val DefaultButtonPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
private val CompactButtonMinWidth = 64.dp
private val DefaultButtonMinHeight = 40.dp

/**
 * 液态玻璃主操作按钮（对齐 Kyant0 LiquidButton）：
 * 主操作采用主题强调色（或危险色 `tintColor`）染色玻璃、`InteractiveHighlight` 按压发光与阻尼放大；
 * 无宿主或低版本自动走清晰主题回退。
 */
@Composable
fun LiquidButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = DefaultButtonShape,
    tintColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = Color.White,
    contentPadding: PaddingValues = DefaultButtonPadding,
    content: @Composable RowScope.() -> Unit,
) {
    LiquidButtonImpl(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        tintColor = tintColor,
        surfaceTintColor = Color.Unspecified,
        border = null,
        contentColor = contentColor,
        contentPadding = contentPadding,
        content = content,
    )
}

/**
 * 液态玻璃次级描边按钮：透明磨砂玻璃底座 + 细描边 + 按压流光。
 */
@Composable
fun LiquidOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = DefaultButtonShape,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    contentPadding: PaddingValues = DefaultButtonPadding,
    content: @Composable RowScope.() -> Unit,
) {
    LiquidButtonImpl(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        tintColor = Color.Unspecified,
        surfaceTintColor = Color.Unspecified,
        border = BorderStroke(1.dp, borderColor.copy(alpha = if (enabled) 0.72f else 0.35f)),
        contentColor = contentColor,
        contentPadding = contentPadding,
        content = content,
    )
}

/**
 * 液态玻璃柔和染色按钮：弱强调色漫反射玻璃底座，适合次要提交/分组动作。
 */
@Composable
fun LiquidTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = DefaultButtonShape,
    tintColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = tintColor,
    contentPadding: PaddingValues = DefaultButtonPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val resolvedTint = if (tintColor.isSpecified) tintColor else MaterialTheme.colorScheme.primary
    LiquidButtonImpl(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        tintColor = Color.Unspecified,
        surfaceTintColor = resolvedTint.copy(alpha = 0.16f),
        border = BorderStroke(0.6.dp, resolvedTint.copy(alpha = if (enabled) 0.32f else 0.14f)),
        contentColor = contentColor,
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
private fun LiquidButtonImpl(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    shape: Shape,
    tintColor: Color,
    surfaceTintColor: Color,
    border: BorderStroke?,
    contentColor: Color,
    contentPadding: PaddingValues,
    content: @Composable RowScope.() -> Unit,
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
    val resolvedContentColor = if (enabled) {
        contentColor
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    val hasPrimaryTint = tintColor.isSpecified && enabled

    val interactiveMod = modifier
        .defaultMinSize(minWidth = CompactButtonMinWidth, minHeight = DefaultButtonMinHeight)
        .clip(shape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .then(if (enabled) highlight.gestureModifier else Modifier)

    val rowContent: @Composable () -> Unit = {
        CompositionLocalProvider(
            LocalContentColor provides resolvedContentColor,
            LocalTextStyle provides MaterialTheme.typography.labelLarge,
        ) {
            Row(
                modifier = Modifier
                    .then(if (enabled) highlight.modifier else Modifier)
                    .padding(contentPadding),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }

    val hasOpticalEffect = hasWallpaper &&
        (spec.blurDp > 0.01f || (tier == GlassTier.FULL && spec.lensWidthDp > 0.01f && spec.lensHeightDp > 0.01f))
    if (tier == GlassTier.BASIC || !hasOpticalEffect) {
        val fallbackBg = when {
            hasPrimaryTint -> tintColor.copy(alpha = if (tier == GlassTier.BASIC) 1f else 0.88f)
            surfaceTintColor.isSpecified -> surfaceTintColor
            else -> {
                val base = if (isLight) GlassTokens.tintLight else GlassTokens.tintDark
                val a = if (tier == GlassTier.BASIC) {
                    if (isLight) spec.tier0AlphaLight else spec.tier0AlphaDark
                } else {
                    if (isLight) spec.tintAlphaLight else spec.tintAlphaDark
                }
                if (a <= 0.004f) Color.Transparent else base.copy(alpha = a)
            }
        }
        Surface(
            modifier = interactiveMod
                .then(if (enabled) Modifier.graphicsLayer(pressLayerBlock) else Modifier),
            shape = shape,
            color = if (fallbackBg.alpha <= 0.004f) Color.Transparent else fallbackBg.copy(alpha = if (enabled) fallbackBg.alpha else 0.42f),
            contentColor = resolvedContentColor,
            border = border,
        ) {
            Box(contentAlignment = Alignment.Center) {
                rowContent()
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
        modifier = interactiveMod
            .drawBackdrop(
                backdrop = backdrop,
                shape = shapeProvider,
                effects = {
                    if (spec.blurDp > 0.01f) {
                        blur(spec.blurDp.dp.toPx())
                    }
                    if (canRefract && spec.lensWidthDp > 0.01f && spec.lensHeightDp > 0.01f) {
                        lens(
                            refractionHeight = spec.lensWidthDp.dp.toPx(),
                            refractionAmount = spec.lensHeightDp.dp.toPx(),
                            depthEffect = spec.depthEffect,
                            chromaticAberration = spec.chromaticAberration,
                        )
                    }
                },
                highlight = { highlightSpec },
                layerBlock = if (enabled) pressLayerBlock else null,
                onDrawSurface = {
                    when {
                        hasPrimaryTint -> {
                            drawRect(tintColor, blendMode = BlendMode.Hue)
                            drawRect(tintColor.copy(alpha = 0.78f))
                        }
                        surfaceTintColor.isSpecified -> {
                            if (neutralTint.alpha > 0.004f) drawRect(neutralTint)
                            drawRect(surfaceTintColor.copy(alpha = if (enabled) surfaceTintColor.alpha else 0.08f))
                        }
                        neutralTint.alpha > 0.004f -> {
                            drawRect(
                                neutralTint.copy(
                                    alpha = if (enabled) neutralTint.alpha else (neutralTint.alpha * 0.5f),
                                ),
                            )
                        }
                    }
                },
            )
            .clip(shape)
            .then(if (border != null) Modifier.border(border, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        rowContent()
    }
}
