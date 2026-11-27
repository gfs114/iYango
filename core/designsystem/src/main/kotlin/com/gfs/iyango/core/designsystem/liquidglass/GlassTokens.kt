package com.gfs.iyango.core.designsystem.liquidglass

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * 玻璃材质类别（全项目统一规范 §3.1 的四类呈现）。
 *
 * 类别只决定取哪一组集中参数；逐组件微调不在此处发生，
 * 避免各 Screen 复制一套难以统一调整的常量。
 */
enum class GlassMaterial {
    /** 导航玻璃：底栏、侧栏、顶部栏、浮动操作栏。 */
    NAVIGATION,

    /** 内容玻璃：分组卡、资料卡、列表卡、统计面板、网格外框。 */
    CONTENT,

    /** 控件玻璃：按钮底座、选择胶囊、开关、滑块、输入框底座。 */
    CONTROL,

    /** 浮层玻璃：Dialog、Sheet、菜单、Snackbar、提示条。 */
    OVERLAY,
}

/**
 * 单类玻璃的集中材质参数。
 *
 * @param blurDp 背景模糊半径。
 * @param lensWidthDp 折射透镜宽。
 * @param lensHeightDp 折射透镜高。
 * @param edgeWidthDp 细高光边宽。
 * @param edgeBlurDp 细高光边模糊半径。
 * @param edgeAlpha 细高光边不透明度。
 * @param tintAlphaLight 亮色主题蒙层不透明度（完整档）。
 * @param tintAlphaDark 暗色主题蒙层不透明度（完整档）。
 * @param tier0AlphaLight 基础档（无模糊/折射）亮色实体底色不透明度。
 * @param tier0AlphaDark 基础档暗色实体底色不透明度。
 */
data class GlassMaterialSpec(
    val blurDp: Float,
    val lensWidthDp: Float,
    val lensHeightDp: Float,
    val edgeWidthDp: Float,
    val edgeBlurDp: Float,
    val edgeAlpha: Float,
    val tintAlphaLight: Float,
    val tintAlphaDark: Float,
    val tier0AlphaLight: Float,
    val tier0AlphaDark: Float,
    val depthEffect: Boolean = false,
    val chromaticAberration: Boolean = false,
)

/**
 * 玻璃材质令牌（唯一定义点）。调参只改这里；明暗差异经
 * [isGlassLightTheme] 取值，能力档差异在 [GlassSurface] 内换算。
 *
 * 数值基线说明：
 * - 导航玻璃参考既有 Dock 参数域，但蒙层取偏可读档（侧栏承载文字标签）；
 * - 内容玻璃优先正文可读性（普通正文对比度 ≥ 4.5:1 的验收目标），折射弱于导航；
 * - 浮层玻璃层级与遮罩最强，供 Dialog/Sheet 使用；
 * - 基础档取近实体底色，保证旧系统可读（对齐 Dock 的 tier 0 策略）。
 */
object GlassTokens {

    /** 导航玻璃参数（对齐标准 Apple 液态玻璃：8dp 模糊 + lens(16, 24) + 轻遮罩）。 */
    val Navigation = GlassMaterialSpec(
        blurDp = 8f,
        lensWidthDp = 16f,
        lensHeightDp = 24f,
        edgeWidthDp = 0.5f,
        edgeBlurDp = 0.25f,
        edgeAlpha = 0.85f,
        tintAlphaLight = 0.36f,
        tintAlphaDark = 0.34f,
        tier0AlphaLight = 0.55f,
        tier0AlphaDark = 0.35f,
    )

    /** 内容玻璃参数（标准液态玻璃：8dp 模糊 + 1:2 轻折射 + 清晰高光）。 */
    val Content = GlassMaterialSpec(
        blurDp = 8f,
        lensWidthDp = 14f,
        lensHeightDp = 28f,
        edgeWidthDp = 0.5f,
        edgeBlurDp = 0.25f,
        edgeAlpha = 0.72f,
        tintAlphaLight = 0.46f,
        tintAlphaDark = 0.38f,
        tier0AlphaLight = 0.95f,
        tier0AlphaDark = 0.88f,
    )

    /** 控件玻璃参数（对齐 Kyant0 LiquidButton：2dp 轻模糊 + lens(12, 24)）。 */
    val Control = GlassMaterialSpec(
        blurDp = 2f,
        lensWidthDp = 12f,
        lensHeightDp = 24f,
        edgeWidthDp = 0.5f,
        edgeBlurDp = 0.25f,
        edgeAlpha = 0.90f,
        tintAlphaLight = 0.30f,
        tintAlphaDark = 0.26f,
        tier0AlphaLight = 0.90f,
        tier0AlphaDark = 0.80f,
    )

    /** 浮层玻璃参数（对齐 Kyant0 DialogContent：16dp 模糊 + lens(20, 40) + depthEffect）。 */
    val Overlay = GlassMaterialSpec(
        blurDp = 16f,
        lensWidthDp = 20f,
        lensHeightDp = 40f,
        edgeWidthDp = 0.6f,
        edgeBlurDp = 0.3f,
        edgeAlpha = 0.85f,
        tintAlphaLight = 0.60f,
        tintAlphaDark = 0.42f,
        tier0AlphaLight = 0.96f,
        tier0AlphaDark = 0.90f,
        depthEffect = true,
    )

    /** 按类别取参数。 */
    fun spec(material: GlassMaterial): GlassMaterialSpec = when (material) {
        GlassMaterial.NAVIGATION -> Navigation
        GlassMaterial.CONTENT -> Content
        GlassMaterial.CONTROL -> Control
        GlassMaterial.OVERLAY -> Overlay
    }

    /**
     * 玻璃 tint 底色：亮色用纸白、暗色用纯黑。
     * 暗色必须是“比背景更暗”的黑玻璃，而不是发灰的色板（与 Dock 玻璃结论一致）。
     */
    val tintLight: Color = Color(0xFFFAFAFA)
    val tintDark: Color = Color.Black

    /** 模糊档（有模糊、无折射透镜）蒙层增益，参考 Dock 既有降级。 */
    const val BLUR_TIER_TINT_GAIN = 1.4f

    /** 模糊档蒙层增益上限。 */
    const val BLUR_TIER_TINT_MAX = 0.8f
}

/** 可动态配置的全局四类液态玻璃参数集合。 */
data class GlassPalette(
    val navigation: GlassMaterialSpec = GlassTokens.Navigation,
    val content: GlassMaterialSpec = GlassTokens.Content,
    val control: GlassMaterialSpec = GlassTokens.Control,
    val overlay: GlassMaterialSpec = GlassTokens.Overlay,
) {
    /** 按类别取当前生效的材质参数。 */
    fun spec(material: GlassMaterial): GlassMaterialSpec = when (material) {
        GlassMaterial.NAVIGATION -> navigation
        GlassMaterial.CONTENT -> content
        GlassMaterial.CONTROL -> control
        GlassMaterial.OVERLAY -> overlay
    }
}

/** 运行时生效的全局四类液态玻璃材质面板，默认取 [GlassTokens] 基线。 */
val LocalGlassPalette = compositionLocalOf { GlassPalette() }

/**
 * 明暗判定：按主题背景亮度取值，与 LiquidBottomTabs 的判定保持一致，
 * ThemeMode 跟随系统时自动随系统深浅色切换。
 */
@Composable
fun isGlassLightTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() >= 0.5f
