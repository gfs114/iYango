package com.gfs.iyango.core.designsystem.liquidglass

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.tokens.Dimens
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.RoundedRectangularShape

private fun materialTint(spec: GlassMaterialSpec, light: Boolean, tier: GlassTier): Color {
    val alpha = if (light) spec.tintAlphaLight else spec.tintAlphaDark
    val tierAlpha = when (tier) {
        GlassTier.BASIC -> if (light) spec.tier0AlphaLight else spec.tier0AlphaDark
        GlassTier.BLUR -> (alpha * GlassTokens.BLUR_TIER_TINT_GAIN).coerceAtMost(GlassTokens.BLUR_TIER_TINT_MAX)
        GlassTier.FULL -> alpha
    }
    return (if (light) GlassTokens.tintLight else GlassTokens.tintDark).copy(alpha = tierAlpha)
}

/** Fill for a moving copy of an untinted CONTENT card. Wallpaper remains visible through the tint. */
@Composable
fun glassContentFillColor(): Color {
    val tier = if (LocalGlassBackdrop.current == null) GlassTier.BASIC else rememberGlassTier()
    val spec = LocalGlassPalette.current.content
    val tint = materialTint(spec, isGlassLightTheme(), tier)
    return if (tier != GlassTier.BASIC && !LocalGlassHasWallpaper.current) {
        if (tint.alpha <= 0.004f) Color.Transparent else tint.compositeOver(MaterialTheme.colorScheme.background)
    } else {
        tint
    }
}

/**
 * 玻璃基础表面（阶段 1 公共底座）。
 *
 * 按 [material] 取 [GlassTokens] 集中参数：完整档色彩增强 + 模糊 + 折射透镜 + 细高光，
 * 模糊档去掉透镜并加深蒙层，基础档与无宿主场景回退主题实体表面。
 * 回退保持相同的圆角、颜色与内容语义（统一规范 §1.1）。
 *
 * 前景内容绘制在玻璃效果之上：正文、图标、状态色不被模糊或折射（§3.2）。
 *
 * @param shape 表面形状；折射要求 RoundedRectangularShape 或 CornerBasedShape。
 * @param material 材质类别，决定采样后的模糊、折射与蒙层取值。
 * @param allowRefraction 是否开启透镜折射。
 * @param includeShadow 是否保留外投影；嵌套在玻璃容器内的子按钮应设为 false。
 * @param tintColor 可选强调色着色（采用 BlendMode.Hue + 0.75f 叠加，对齐 LiquidButton）。
 * @param exportedBackdrop 可选导出图层，供“玻璃叠玻璃”场景下的内部子玻璃采样。
 * @param layerBlock 可选图层变换（按压缩放/阻尼位移），仅缩放外壳与内容而不缩放采样背景。
 * @param content 表面内容；默认内容色为 onSurface（着色玻璃为 White）。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Dimens.cardCorner),
    material: GlassMaterial = GlassMaterial.CONTENT,
    allowRefraction: Boolean = true,
    includeShadow: Boolean = false,
    tintColor: Color = Color.Unspecified,
    exportedBackdrop: LayerBackdrop? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = LocalGlassBackdrop.current
    val hasWallpaper = LocalGlassHasWallpaper.current
    val spec = LocalGlassPalette.current.spec(material)
    val isLight = isGlassLightTheme()
    val tier = rememberGlassTier()
    val tint = materialTint(spec, isLight, if (backdrop == null) GlassTier.BASIC else tier)
    val defaultContentColor = if (tintColor.isSpecified) {
        Color.White
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    if (backdrop == null || tier == GlassTier.BASIC) {
        val fallbackColor = if (tintColor.isSpecified) {
            tintColor.copy(alpha = 0.90f)
        } else {
            tint
        }
        Surface(
            modifier = if (layerBlock != null) modifier.graphicsLayer(layerBlock) else modifier,
            shape = shape,
            color = fallbackColor,
            contentColor = defaultContentColor,
        ) {
            Box {
                content()
            }
        }
        return
    }

    // P1 优化：无壁纸时 L0 仅为纯色主题基底，CONTENT 卡片对纯色做模糊与折射在视觉上与
    // 「主题基底叠加 tint 蒙层 + 细高光描边」完全等价，直接走轻量 Surface 分支避免列表每帧离屏渲染。
    if (material == GlassMaterial.CONTENT && !hasWallpaper && exportedBackdrop == null) {
        val themeBackground = MaterialTheme.colorScheme.background
        val contentSurfaceColor = when {
            tintColor.isSpecified -> tintColor.copy(alpha = 0.75f).compositeOver(themeBackground)
            tint.alpha <= 0.004f -> Color.Transparent
            else -> tint.compositeOver(themeBackground)
        }
        val edgeBorder = remember(spec.edgeWidthDp, spec.edgeAlpha, isLight) {
            if (spec.edgeAlpha <= 0.004f || spec.edgeWidthDp <= 0.01f) {
                null
            } else {
                val borderColor = if (isLight) {
                    Color.Black.copy(alpha = (spec.edgeAlpha * 0.25f).coerceAtMost(0.10f))
                } else {
                    Color.White.copy(alpha = spec.edgeAlpha.coerceAtMost(0.22f))
                }
                BorderStroke(spec.edgeWidthDp.dp, borderColor)
            }
        }
        Surface(
            modifier = if (layerBlock != null) modifier.graphicsLayer(layerBlock) else modifier,
            shape = shape,
            color = contentSurfaceColor,
            contentColor = defaultContentColor,
            border = edgeBorder,
            shadowElevation = if (includeShadow && contentSurfaceColor.alpha > 0.004f) 4.dp else 0.dp,
        ) {
            Box {
                content()
            }
        }
        return
    }

    val canRefract = tier == GlassTier.FULL &&
        allowRefraction &&
        (shape is RoundedRectangularShape || shape is CornerBasedShape)
    val hasEdge = spec.edgeAlpha > 0.004f && spec.edgeWidthDp > 0.01f
    val highlightSpec = remember(spec.edgeWidthDp, spec.edgeBlurDp, spec.edgeAlpha, hasEdge) {
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
    val shapeProvider = remember(shape) { { shape } }
    val effectsBlock = remember(
        spec.blurDp,
        spec.lensWidthDp,
        spec.lensHeightDp,
        spec.depthEffect,
        spec.chromaticAberration,
        canRefract,
    ) {
        val block: BackdropEffectScope.() -> Unit = {
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
        }
        block
    }
    val highlightProvider = remember(highlightSpec) { { highlightSpec } }
    val shadowProvider = remember(includeShadow, tint.alpha, hasEdge, spec.blurDp) {
        val showShadow = includeShadow &&
            (tint.alpha > 0.004f || hasEdge || spec.blurDp > 0.01f)
        if (showShadow) ({ Shadow.Default }) else null
    }
    val drawSurfaceBlock = remember(tintColor, tint) {
        val block: DrawScope.() -> Unit = {
            if (tintColor.isSpecified) {
                drawRect(tintColor, blendMode = BlendMode.Hue)
                drawRect(tintColor.copy(alpha = 0.75f))
            } else if (tint.alpha > 0.004f) {
                drawRect(tint)
            }
        }
        block
    }

    Box(
        modifier = modifier.drawBackdrop(
            backdrop = backdrop,
            shape = shapeProvider,
            effects = effectsBlock,
            highlight = highlightProvider,
            shadow = shadowProvider,
            layerBlock = layerBlock,
            exportedBackdrop = exportedBackdrop,
            onDrawSurface = drawSurfaceBlock,
        ),
    ) {
        CompositionLocalProvider(LocalContentColor provides defaultContentColor) {
            content()
        }
    }
}
