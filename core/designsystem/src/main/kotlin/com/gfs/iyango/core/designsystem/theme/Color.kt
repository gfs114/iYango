package com.gfs.iyango.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ---------------- baseline §1.2 语义色（浅/深成对，单一事实来源） ----------------
// 供下方基础配色方案与 IYangoTheme 的显式槽位赋值共用；Material3 无对应槽位的
// 语义（success/warning、textTertiary、gridSurface 等）保留常量备查，运行时按需直取。

/** 应用背景。 */
internal val SemanticAppBackgroundLight = Color(0xFFF2F1F6)
internal val SemanticAppBackgroundDark = Color(0xFF0D0D0D)

/** 卡片表面。 */
internal val SemanticSurfaceLight = Color(0xFFFFFFFF)
internal val SemanticSurfaceDark = Color(0xFF1C1C1E)

/** 次级表面（快捷入口容器、输入框底等 surfaceAlt）。 */
internal val SemanticSurfaceAltLight = Color(0xFFF3F6FB)
internal val SemanticSurfaceAltDark = Color(0xFF26262A)

/** 一级文字。 */
internal val SemanticTextPrimaryLight = Color(0xFF182431)
internal val SemanticTextPrimaryDark = Color(0xFFE6E6E6)

/** 二级文字。 */
internal val SemanticTextSecondaryLight = Color(0xFF5E6670)
internal val SemanticTextSecondaryDark = Color(0xFF9BA3AD)

/** 三级文字（Material3 无对应槽位，供需要处直接引用）。 */
internal val SemanticTextTertiaryLight = Color(0xFF6D7781)
internal val SemanticTextTertiaryDark = Color(0xFF7C8591)

/** 边框。 */
internal val SemanticBorderLight = Color(0xFFDCE3EC)
internal val SemanticBorderDark = Color(0xFF33333A)

/** 分隔线。 */
internal val SemanticDividerLight = Color(0xFFE6EBF2)
internal val SemanticDividerDark = Color(0xFF2A2A2F)

/** 错误色（onError 取深色错误底上的深墨字，保证可读）。 */
internal val SemanticErrorLight = Color(0xFFE84026)
internal val SemanticErrorDark = Color(0xFFFF6B5B)
internal val SemanticOnErrorLight = Color(0xFFFFFFFF)
internal val SemanticOnErrorDark = Color(0xFF2B0503)

/**
 * 亮色配色方案。
 *
 * 主色取 iYango 品牌蓝 #3E7BFA（即参考项目默认课程色）；主色容器与文字色由主色向浅/深两端
 * 调和得到，保证容器上的文字对比度。secondary 取中性的蓝灰，tertiary 取参考色板的紫色。
 * background/surface/surfaceVariant/outline/error 等语义槽位对齐 ui-baseline §1.2；
 * 运行时 IYangoTheme 会按当前强调色重派生 primary 系槽位与染背景。
 */
val IYangoLightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF3E7BFA),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8E3FF),
    onPrimaryContainer = Color(0xFF0A2E6E),
    secondary = Color(0xFF565E71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF141B2C),
    tertiary = Color(0xFF705BA8),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEBDDFF),
    onTertiaryContainer = Color(0xFF291345),
    error = SemanticErrorLight,
    onError = SemanticOnErrorLight,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = SemanticAppBackgroundLight,
    onBackground = SemanticTextPrimaryLight,
    surface = SemanticSurfaceLight,
    onSurface = SemanticTextPrimaryLight,
    surfaceVariant = SemanticSurfaceAltLight,
    onSurfaceVariant = SemanticTextSecondaryLight,
    outline = SemanticBorderLight,
    outlineVariant = SemanticDividerLight,
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2E3036),
    inverseOnSurface = Color(0xFFF0F0F7),
    inversePrimary = Color(0xFFAEC6FF),
    surfaceTint = Color(0xFF3E7BFA),
)

/**
 * 暗色配色方案。
 *
 * 主色提亮一档为 #6B9BFF，保证深色背景上的可读性；背景/表面取 baseline §1.2 的深色底
 * （#0D0D0D / #1C1C1E 附近），inversePrimary 回落为亮色方案的主色深档。
 */
val IYangoDarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF6B9BFF),
    onPrimary = Color(0xFF003271),
    primaryContainer = Color(0xFF1B4AAB),
    onPrimaryContainer = Color(0xFFD8E3FF),
    secondary = Color(0xFFBEC6DC),
    onSecondary = Color(0xFF283141),
    secondaryContainer = Color(0xFF3E4758),
    onSecondaryContainer = Color(0xFFDAE2F9),
    tertiary = Color(0xFFD4BBFF),
    onTertiary = Color(0xFF3F2075),
    tertiaryContainer = Color(0xFF563C8D),
    onTertiaryContainer = Color(0xFFEBDDFF),
    error = SemanticErrorDark,
    onError = SemanticOnErrorDark,
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = SemanticAppBackgroundDark,
    onBackground = SemanticTextPrimaryDark,
    surface = SemanticSurfaceDark,
    onSurface = SemanticTextPrimaryDark,
    surfaceVariant = SemanticSurfaceAltDark,
    onSurfaceVariant = SemanticTextSecondaryDark,
    outline = SemanticBorderDark,
    outlineVariant = SemanticDividerDark,
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE1E2E8),
    inverseOnSurface = Color(0xFF2E3036),
    inversePrimary = Color(0xFF2F63D9),
    surfaceTint = Color(0xFF6B9BFF),
)

/**
 * 课程色板：课程块与圆点标记从该色板取色。
 *
 * 基准取自参考项目的 12 色课程色板（蓝/绿/橙/红/紫/青/粉/靛/橄榄/棕/灰），并保留
 * 早期版本的亮蓝 #0A59F7，共 13 色。颜色顺序即默认取色顺序，首个为品牌蓝。
 * 四层明暗配套的新实现见 [CourseSwatch]。
 */
object CoursePalette {

    /** 全部课程色，只读列表。 */
    val colors: List<Color> = listOf(
        Color(0xFF3E7BFA), // 蓝：品牌主色（参考项目默认课程色）
        Color(0xFF0A59F7), // 亮蓝：早期版本色值
        Color(0xFF2BB673), // 绿
        Color(0xFFF2A03D), // 橙
        Color(0xFFF2634E), // 红
        Color(0xFF8B6BF0), // 紫
        Color(0xFF2BB2B2), // 青绿
        Color(0xFF2E9FD9), // 天青
        Color(0xFFEE5A96), // 粉
        Color(0xFF5E6AD2), // 靛蓝
        Color(0xFF7C9B3F), // 橄榄绿
        Color(0xFFB08050), // 棕
        Color(0xFF7A8794), // 灰
    )

    /**
     * 按下标循环取色。
     *
     * 越界自动回绕到色板头部；负数下标同样安全（先归一到非负再取模），结果总落在色板内。
     */
    fun paletteColor(index: Int): Color {
        val size = colors.size
        return colors[((index % size) + size) % size]
    }
}
