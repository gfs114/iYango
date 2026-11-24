package com.gfs.iyango.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/** Material3 默认排版，作为微调基准，不引入自定义字体文件。 */
private val Default = Typography()

/**
 * iYango 排版规范（在 Material3 默认值基础上微调）：
 * - 标题 titleLarge：20sp / 600（SemiBold）；
 * - 次级标题 titleMedium：沿用 16sp，字重提升为 600；
 * - 正文 bodyMedium：15sp；
 * - 辅助文字 labelMedium / labelSmall：12sp；
 * 其余样式沿用 Material3 默认值。
 */
val IYangoTypography: Typography = Typography(
    titleLarge = Default.titleLarge.copy(
        fontSize = 20.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = Default.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyMedium = Default.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
    labelMedium = Default.labelMedium.copy(fontSize = 12.sp),
    labelSmall = Default.labelSmall.copy(fontSize = 12.sp),
)

private fun TextStyle.applyFontAndScale(fontFamily: FontFamily?, scale: Float): TextStyle = copy(
    fontFamily = fontFamily ?: this.fontFamily,
    fontSize = if (fontSize.isSp) fontSize * scale else fontSize,
    lineHeight = if (lineHeight.isSp) lineHeight * scale else lineHeight,
)

/** 根据自定义字体与全局字号缩放生成 [Typography]，未 customization 时直接返回 [IYangoTypography]。 */
fun buildIYangoTypography(
    fontFamily: FontFamily? = null,
    fontScaleFactor: Float = 1.0f,
): Typography {
    if (fontFamily == null && abs(fontScaleFactor - 1.0f) < 0.001f) {
        return IYangoTypography
    }
    val scale = fontScaleFactor.coerceIn(0.85f, 1.30f)
    return Typography(
        displayLarge = IYangoTypography.displayLarge.applyFontAndScale(fontFamily, scale),
        displayMedium = IYangoTypography.displayMedium.applyFontAndScale(fontFamily, scale),
        displaySmall = IYangoTypography.displaySmall.applyFontAndScale(fontFamily, scale),
        headlineLarge = IYangoTypography.headlineLarge.applyFontAndScale(fontFamily, scale),
        headlineMedium = IYangoTypography.headlineMedium.applyFontAndScale(fontFamily, scale),
        headlineSmall = IYangoTypography.headlineSmall.applyFontAndScale(fontFamily, scale),
        titleLarge = IYangoTypography.titleLarge.applyFontAndScale(fontFamily, scale),
        titleMedium = IYangoTypography.titleMedium.applyFontAndScale(fontFamily, scale),
        titleSmall = IYangoTypography.titleSmall.applyFontAndScale(fontFamily, scale),
        bodyLarge = IYangoTypography.bodyLarge.applyFontAndScale(fontFamily, scale),
        bodyMedium = IYangoTypography.bodyMedium.applyFontAndScale(fontFamily, scale),
        bodySmall = IYangoTypography.bodySmall.applyFontAndScale(fontFamily, scale),
        labelLarge = IYangoTypography.labelLarge.applyFontAndScale(fontFamily, scale),
        labelMedium = IYangoTypography.labelMedium.applyFontAndScale(fontFamily, scale),
        labelSmall = IYangoTypography.labelSmall.applyFontAndScale(fontFamily, scale),
    )
}
