package com.gfs.iyango.feature.main

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.gfs.iyango.core.designsystem.theme.LocalTertiaryTextColor
import com.gfs.iyango.core.model.GrayTextMode

/**
 * Material3 无对应槽位的语义色补充（ui-baseline §1.2 表格直取），以及明暗模式探测。
 *
 * designsystem（core 模块）不可改动，因此这些"备查常量"落在 feature:main 内共用；
 * 数值与 ui-baseline §1.2 逐条一致，禁止改值。
 */

/** 浅色模式判定阈值：surface 相对亮度低于它视为深色主题。 */
private const val DarkLuminanceThreshold = 0.5f

/**
 * 当前主题是否为深色模式。
 *
 * 通过 surface 槽位亮度探测：IYangoTheme 的深色 surface（#1C1C1E）亮度远低于阈值，
 * 浅色 surface（#FFFFFF）为 1.0；预览中显式传入 darkTheme 时同样成立。
 */
@Composable
@ReadOnlyComposable
fun isDarkTheme(): Boolean = MaterialTheme.colorScheme.surface.luminance() < DarkLuminanceThreshold

/** warning 语义色（ui-baseline §1.2）：浅 #C38933 / 深 #E8B339。 */
@Composable
fun warningColor(): Color = if (isDarkTheme()) Color(0xFFE8B339) else Color(0xFFC38933)

/** success 语义色（ui-baseline §1.2）：浅 #21A675 / 深 #4CC38A。 */
@Composable
fun successColor(): Color = if (isDarkTheme()) Color(0xFF4CC38A) else Color(0xFF21A675)

/** 三级文字语义色（ui-baseline §1.2）：浅 #6D7781 / 深 #7C8591；受灰字增强模式覆盖。 */
@Composable
fun textTertiaryColor(): Color =
    LocalTertiaryTextColor.current ?: if (isDarkTheme()) Color(0xFF7C8591) else Color(0xFF6D7781)

internal fun parseCustomHexColor(hex: String): Color? {
    val clean = hex.trim().removePrefix("#")
    if (clean.length != 6) return null
    val rgb = clean.toLongOrNull(16) ?: return null
    return Color(0xFF000000L or rgb)
}

/** 根据灰字可读性模式解析 (secondaryTextOverride, tertiaryTextOverride)。 */
fun resolveGrayTextColors(
    mode: GrayTextMode,
    customHex: String,
    darkTheme: Boolean,
): Pair<Color?, Color?> = when (mode) {
    GrayTextMode.DEFAULT -> null to null
    GrayTextMode.AUTO_ENHANCED -> if (darkTheme) {
        Color(0xFFC8D0D8) to Color(0xFFA8B2BD)
    } else {
        Color(0xFF3A434D) to Color(0xFF4E5863)
    }
    GrayTextMode.HIGH_CONTRAST_DARK -> Color(0xFF111418) to Color(0xFF262C33)
    GrayTextMode.HIGH_CONTRAST_LIGHT -> Color(0xFFF5F7FA) to Color(0xFFDCE2E8)
    GrayTextMode.CUSTOM -> parseCustomHexColor(customHex)?.let { it to it.copy(alpha = 0.85f) }
        ?: (null to null)
}

/** 网格面板底色（ui-baseline §1.2 gridSurface）：浅 #FFFFFF / 深 #161618。 */
@Composable
fun gridSurfaceColor(): Color = if (isDarkTheme()) Color(0xFF161618) else Color(0xFFFFFFFF)

/** 今日列洗色（ui-baseline §1.2 gridTodayColumn）：浅 #F0F5FF / 深 #191F2B。 */
@Composable
fun gridTodayColumnColor(): Color = if (isDarkTheme()) Color(0xFF191F2B) else Color(0xFFF0F5FF)
