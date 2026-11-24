package com.gfs.iyango.core.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 主题色预设：6 组浅/深成对的强调色（对应 ui-baseline §1.3）。
 *
 * @param label 中文展示名，供设置页直接展示。
 * @param light 浅色模式下的 #RRGGBB 强调色。
 * @param dark 深色模式下的 #RRGGBB 强调色。
 */
enum class AccentPreset(val label: String, val light: String, val dark: String) {
    CLASSIC("经典橙", "#C2410C", "#F08C4A"),
    AZURE("远峰蓝", "#2563EB", "#5B9BFF"),
    FOREST("松石绿", "#0F8A5F", "#3DD68C"),
    VIOLET("暮山紫", "#6D4AE0", "#A78BFA"),
    SAKURA("樱语粉", "#DB2777", "#F472B6"),
    LAKE("湖光青", "#0E8FA8", "#2AC9DB");
}

/**
 * 品牌色三元组：强调色 + 强调色上的文字墨色 + 15% 洗色底。
 *
 * 派生规则（ui-baseline §1.3）：
 * - onBrand：按 YIQ（0.2126R + 0.7152G + 0.0722B，0-255 尺度）判定，
 *   ≥150 用黑墨（浅色 #182431 / 深色 #0D0D0D），否则白墨 #FFFFFF；
 * - brandSoft：accent 以 15% 透明度洗色。
 */
data class BrandColors(val accent: Color, val onBrand: Color, val brandSoft: Color)

/**
 * 品牌色 CompositionLocal：由 [IYangoTheme] 统一提供。
 *
 * 默认值取经典橙浅色派生，保证未包主题时读值不崩溃（预览/工具场景兜底）。
 */
val LocalBrand: ProvidableCompositionLocal<BrandColors> = staticCompositionLocalOf {
    brandColorsOf(accentPresetColor(AccentPreset.CLASSIC, darkTheme = false), darkTheme = false)
}

/** 便捷读取当前品牌色，等价于 `LocalBrand.current`。 */
@Composable
fun rememberBrand(): BrandColors = LocalBrand.current

// ---------------- 内部派生逻辑（同包内使用，不对外承诺） ----------------

/** YIQ 黑墨阈值（0-255 尺度）。 */
private const val YiqInkThreshold = 150f

/** YIQ 各通道权重。 */
private const val YiqRedWeight = 0.2126f
private const val YiqGreenWeight = 0.7152f
private const val YiqBlueWeight = 0.0722f

/** brandSoft 洗色透明度（≈ 0x26 / 255）。 */
private const val BrandSoftAlpha = 0.15f

/** 浅色模式黑墨。 */
private val InkOnBrandLight = Color(0xFF182431)

/** 深色模式黑墨。 */
private val InkOnBrandDark = Color(0xFF0D0D0D)

/**
 * 解析 #RRGGBB 颜色，容忍大小写与 # 前缀缺失；非法输入返回 null。
 */
internal fun parseHexColor(hex: String): Color? {
    val normalized = hex.trim().removePrefix("#").uppercase()
    if (normalized.length != 6) return null
    val value = normalized.toLongOrNull(16) ?: return null
    return Color(0xFF000000L or value)
}

/**
 * 取预设强调色：深色用 preset.dark，浅色用 preset.light。
 *
 * 预设常量必然合法，末级兜底仅防手改常量导致的意外。
 */
internal fun accentPresetColor(preset: AccentPreset, darkTheme: Boolean): Color =
    parseHexColor(if (darkTheme) preset.dark else preset.light)
        ?: parseHexColor(preset.light)
        ?: Color(0xFFC2410C)

/**
 * 由强调色派生品牌色三元组（YIQ 选墨 + 15% 洗色底）。
 */
internal fun brandColorsOf(accent: Color, darkTheme: Boolean): BrandColors {
    val yiq = YiqRedWeight * accent.red * 255f +
        YiqGreenWeight * accent.green * 255f +
        YiqBlueWeight * accent.blue * 255f
    val onBrand = if (yiq >= YiqInkThreshold) {
        if (darkTheme) InkOnBrandDark else InkOnBrandLight
    } else {
        Color.White
    }
    return BrandColors(
        accent = accent,
        onBrand = onBrand,
        brandSoft = accent.copy(alpha = BrandSoftAlpha),
    )
}
