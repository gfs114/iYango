package com.gfs.iyango.core.designsystem.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.tokens.Dimens

/** 染背景透明度：背景 = accent 与基底按 8% 混合（baseline §1.3）。 */
private const val TintBackgroundAlpha = 0.08f

/** 三级文字覆盖色（用于灰字可读性增强，null 时走各端默认）。 */
val LocalTertiaryTextColor = staticCompositionLocalOf<Color?> { null }

/**
 * iYango 应用主题：组装品牌配色方案与排版，并通过 [LocalBrand] 提供品牌色。
 *
 * 语义槽位（background/surface/surfaceAlt/text/error/border/divider）按
 * ui-baseline §1.2 的浅深表显式赋值；primary 系槽位由强调色派生。
 *
 * @param darkTheme 是否使用暗色主题，默认跟随系统深色模式。
 * @param accentHex 自定义强调色（#RRGGBB）；非 null 时优先使用，
 *   为 null 或非法时按 darkTheme 取 [AccentPreset.CLASSIC] 的浅/深档。
 * @param tintBackground 是否将 accent 以 8% 混入应用背景（染背景开关）。
 * @param fontFamily 全局自定义字体族（null 时保持系统默认）。
 * @param fontScaleFactor 全局字号缩放倍率（0.85..1.30）。
 * @param secondaryTextColorOverride 二级灰字（onSurfaceVariant）覆盖色。
 * @param tertiaryTextColorOverride 三级灰字覆盖色。
 * @param content 主题作用域内的内容。
 */
@Composable
fun IYangoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accentHex: String? = null,
    tintBackground: Boolean = false,
    fontFamily: FontFamily? = null,
    fontScaleFactor: Float = 1.0f,
    secondaryTextColorOverride: Color? = null,
    tertiaryTextColorOverride: Color? = null,
    content: @Composable () -> Unit,
) {
    val accent = accentHex?.let { parseHexColor(it) }
        ?: accentPresetColor(AccentPreset.CLASSIC, darkTheme)
    val brand = brandColorsOf(accent, darkTheme)
    val colorScheme = buildColorScheme(darkTheme, accent, brand, tintBackground, secondaryTextColorOverride)
    val typography = buildIYangoTypography(fontFamily, fontScaleFactor)
    CompositionLocalProvider(
        LocalBrand provides brand,
        LocalTertiaryTextColor provides tertiaryTextColorOverride,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content,
        )
    }
}

/**
 * 组装配色方案：在基础方案上显式覆盖 baseline §1.2 的语义槽位与品牌槽位。
 */
private fun buildColorScheme(
    darkTheme: Boolean,
    accent: Color,
    brand: BrandColors,
    tintBackground: Boolean,
    secondaryTextColorOverride: Color? = null,
): ColorScheme {
    val base = if (darkTheme) IYangoDarkColorScheme else IYangoLightColorScheme
    val appBackground =
        if (darkTheme) SemanticAppBackgroundDark else SemanticAppBackgroundLight
    val background = if (tintBackground) {
        accent.copy(alpha = TintBackgroundAlpha).compositeOver(appBackground)
    } else {
        appBackground
    }
    val textPrimary = if (darkTheme) SemanticTextPrimaryDark else SemanticTextPrimaryLight
    return base.copy(
        // 品牌槽位：accent / onBrand / brandSoft
        primary = accent,
        onPrimary = brand.onBrand,
        primaryContainer = brand.brandSoft,
        onPrimaryContainer = brand.onBrand,
        surfaceTint = accent,
        // 语义槽位：baseline §1.2 浅深表
        background = background,
        onBackground = textPrimary,
        surface = if (darkTheme) SemanticSurfaceDark else SemanticSurfaceLight,
        onSurface = textPrimary,
        surfaceVariant = if (darkTheme) SemanticSurfaceAltDark else SemanticSurfaceAltLight,
        onSurfaceVariant = secondaryTextColorOverride
            ?: if (darkTheme) SemanticTextSecondaryDark else SemanticTextSecondaryLight,
        outline = if (darkTheme) SemanticBorderDark else SemanticBorderLight,
        outlineVariant = if (darkTheme) SemanticDividerDark else SemanticDividerLight,
        error = if (darkTheme) SemanticErrorDark else SemanticErrorLight,
        onError = if (darkTheme) SemanticOnErrorDark else SemanticOnErrorLight,
    )
}

/**
 * 预览专用主题：固定明/暗模式供 @Preview 包裹内容，不参与运行时。
 *
 * @param darkTheme true 使用暗色配色，false 使用亮色配色。
 */
@Composable
fun IYangoThemePreview(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    IYangoTheme(darkTheme = darkTheme, content = content)
}

/** 亮色预览：横向展示完整课程色板。 */
@Preview(name = "色板 - 亮色", showBackground = true)
@Composable
private fun ThemePaletteLightPreview() {
    IYangoThemePreview(darkTheme = false) {
        Surface {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(Dimens.spacingL),
                horizontalArrangement = Arrangement.spacedBy(Dimens.spacingS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(CoursePalette.colors.size) { index ->
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(CoursePalette.paletteColor(index)),
                    )
                }
            }
        }
    }
}

/** 暗色预览：同上，验证暗色环境下的组装。 */
@Preview(name = "色板 - 暗色", showBackground = true)
@Composable
private fun ThemePaletteDarkPreview() {
    IYangoThemePreview(darkTheme = true) {
        Surface {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(Dimens.spacingL),
                horizontalArrangement = Arrangement.spacedBy(Dimens.spacingS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(CoursePalette.colors.size) { index ->
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(CoursePalette.paletteColor(index)),
                    )
                }
            }
        }
    }
}

/** 预设预览通用内容：每个预设一行，展示 accent 圆点 + 名称 + brandSoft 周次胶囊。 */
@Composable
private fun AccentPresetPreviewContent(darkTheme: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Dimens.spacingL),
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingM),
    ) {
        AccentPreset.entries.forEach { preset ->
            IYangoTheme(
                darkTheme = darkTheme,
                accentHex = if (darkTheme) preset.dark else preset.light,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(LocalBrand.current.accent),
                    )
                    Spacer(modifier = Modifier.width(Dimens.spacingS))
                    Text(
                        text = preset.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(LocalBrand.current.brandSoft)
                            .padding(horizontal = Dimens.spacingS, vertical = Dimens.spacingXs),
                    ) {
                        Text(
                            text = "第 3 周",
                            style = MaterialTheme.typography.labelMedium,
                            color = LocalBrand.current.accent,
                        )
                    }
                }
            }
        }
    }
}

/** 主题色预设切换预览（亮色）：6 组预设的 accent/onBrand/brandSoft 派生效果。 */
@Preview(name = "主题色预设 - 亮色", showBackground = true)
@Composable
private fun AccentPresetsLightPreview() {
    IYangoThemePreview(darkTheme = false) {
        Surface {
            AccentPresetPreviewContent(darkTheme = false)
        }
    }
}

/** 主题色预设切换预览（暗色）。 */
@Preview(name = "主题色预设 - 暗色", showBackground = true)
@Composable
private fun AccentPresetsDarkPreview() {
    IYangoThemePreview(darkTheme = true) {
        Surface {
            AccentPresetPreviewContent(darkTheme = true)
        }
    }
}
