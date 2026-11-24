package com.gfs.iyango.core.designsystem.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gfs.iyango.core.designsystem.tokens.Dimens
import java.lang.Math.floorMod
import kotlin.math.abs

/**
 * 课程色四层配色：同一强调色在浅/深两套模式下的配套取色。
 *
 * - [fill]：饱和底色（列表圆点、选中态描边等）；
 * - [tint]：柔和洗色底（课表格子背景：tint 底 + ink 字）；
 * - [ink]：同色系深墨字（浅色对白底可读、深色对深底可读）；
 * - [inkSoft]：同色系次级墨字（tint 底上的辅助文字）。
 */
data class CourseSwatchColors(val fill: Color, val tint: Color, val ink: Color, val inkSoft: Color)

/**
 * 课程色板（ui-baseline §1.4）：12 色池 + 每色四层明暗配对。
 *
 * 分配规则：按 (课名+教师) 分组保色，新组按课程 id 从色池循环取色；
 * 已有 color 字段（#RRGGBB）的课程走 [resolve] 直接派生。
 */
object CourseSwatch {

    /** 12 个课程强调色（#RRGGBB），顺序与 ui-baseline §1.4 一致。 */
    val accents: List<String> = listOf(
        "#3E7BFA", // BLUE
        "#2BB673", // GREEN
        "#F2A03D", // AMBER
        "#F2634E", // RED
        "#8B6BF0", // PURPLE
        "#2BB2B2", // TEAL
        "#EE5A96", // PINK
        "#7A8794", // GREY
        "#5E6AD2", // INDIGO
        "#2E9FD9", // CYAN
        "#7C9B3F", // OLIVE
        "#B08050", // BROWN
    )

    /**
     * 由 #RRGGBB 强调色解析四层配色。
     *
     * 非法输入回退到色板首色（BLUE），保证永不崩溃。
     *
     * @param accentHex 课程自带颜色（#RRGGBB）。
     * @param dark 是否深色模式。
     */
    fun resolve(accentHex: String, dark: Boolean): CourseSwatchColors {
        val accent = parseHexColor(accentHex)
            ?: parseHexColor(accents.first())
            ?: Color(0xFF3E7BFA)
        return buildSwatch(accent, dark)
    }

    /**
     * 当前模式下生效的 12 色板：用户编辑的槽位优先，空槽回退到内置预设。
     *
     * @param paletteLight 浅色模式编辑值（不足 12 项按空槽处理）。
     * @param paletteDark 深色模式编辑值。
     * @param dark 是否深色模式。
     */
    fun effectivePalette(
        paletteLight: List<String>,
        paletteDark: List<String>,
        dark: Boolean,
    ): List<String> {
        val stored = if (dark) paletteDark else paletteLight
        return accents.mapIndexed { index, preset ->
            stored.getOrNull(index)?.takeIf { parseHexColor(it) != null } ?: preset
        }
    }

    /**
     * 已存颜色对应的色板槽位；-1 = 任意自定义色。
     *
     * 覆盖顺序：两套生效色板 → 旧版本内置色 → 内置预设，保证从旧配色升级后仍落到原槽位。
     */
    fun slotIndex(
        stored: String,
        paletteLight: List<String> = emptyList(),
        paletteDark: List<String> = emptyList(),
    ): Int {
        val normalized = stored.trim().uppercase()
        if (normalized.isEmpty()) return -1
        for (pool in listOf(
            effectivePalette(paletteLight, paletteDark, dark = false),
            effectivePalette(paletteLight, paletteDark, dark = true),
        )) {
            val index = pool.indexOfFirst { it.equals(normalized, ignoreCase = true) }
            if (index >= 0) return index
        }
        val legacy = legacyAccents.indexOfFirst { it.equals(normalized, ignoreCase = true) }
        if (legacy >= 0) return legacy
        return accents.indexOfFirst { it.equals(normalized, ignoreCase = true) }
    }

    /**
     * 按课程取色：color 非 null 且能解析为 #RRGGBB 时先映射到色板槽位（套用用户编辑色），
     * 匹配不到槽位则按自定义色派生；否则按 courseId 对 12 取模（floorMod，负 id 亦安全）
     * 从生效色池取色。
     *
     * @param color 课程自带颜色，可为 null。
     * @param courseId 课程 id，用于无色时确定性分配。
     * @param dark 是否深色模式。
     * @param paletteLight 浅色模式色板编辑值；空 = 全部使用内置预设。
     * @param paletteDark 深色模式色板编辑值。
     */
    fun forCourse(
        color: String?,
        courseId: Long,
        dark: Boolean,
        paletteLight: List<String> = emptyList(),
        paletteDark: List<String> = emptyList(),
    ): CourseSwatchColors {
        val pool = effectivePalette(paletteLight, paletteDark, dark)
        val stored = color?.takeIf { parseHexColor(it) != null }
        val hex = if (stored == null) {
            pool[floorMod(courseId, pool.size.toLong()).toInt()]
        } else {
            val slot = slotIndex(stored, paletteLight, paletteDark)
            if (slot >= 0) pool[slot] else stored
        }
        return resolve(hex, dark)
    }

    /** 旧版本内置色（与色板同序），读回时映射回原槽位。 */
    private val legacyAccents = listOf(
        "#0A59F7", "#1FBF7A", "#FF8C00", "#F5222D",
        "#8B5CF6", "#13C2C2", "#EB2F96", "#8C99A6",
    )
}

// ---------------- 四层派生（HSL 同明度关系） ----------------

/*
 * 以 BLUE 参考值反推的 HSL 明度关系（保持强调色自身色相）：
 *   亮色：fill L0.52/S×0.73（#2F63D9）、tint L0.89/S×0.98（#C9DCFE）、
 *         ink L0.22/S×0.72（#12325F）、inkSoft L0.45/S×0.50；
 *   深色：fill L0.38/S×0.65（#254A9E）、tint L0.225/S×0.56（#1B3358）、
 *         ink L0.92/S×1.05（#D6E4FF）、inkSoft L0.65/S×0.70。
 */
private const val FillLightL = 0.52f
private const val FillLightS = 0.73f
private const val TintLightL = 0.89f
private const val TintLightS = 0.98f
private const val InkLightL = 0.22f
private const val InkLightS = 0.72f
private const val InkSoftLightL = 0.45f
private const val InkSoftLightS = 0.50f

private const val FillDarkL = 0.38f
private const val FillDarkS = 0.65f
private const val TintDarkL = 0.225f
private const val TintDarkS = 0.56f
private const val InkDarkL = 0.92f
private const val InkDarkS = 1.05f
private const val InkSoftDarkL = 0.65f
private const val InkSoftDarkS = 0.70f

/**
 * 按上述 HSL 关系由强调色构建四层配色。
 */
private fun buildSwatch(accent: Color, dark: Boolean): CourseSwatchColors {
    val (hue, sat, _) = toHsl(accent)
    val s = sat.coerceIn(0f, 1f)
    return if (dark) {
        CourseSwatchColors(
            fill = fromHsl(hue, (s * FillDarkS).coerceAtMost(1f), FillDarkL),
            tint = fromHsl(hue, (s * TintDarkS).coerceAtMost(1f), TintDarkL),
            ink = fromHsl(hue, (s * InkDarkS).coerceAtMost(1f), InkDarkL),
            inkSoft = fromHsl(hue, (s * InkSoftDarkS).coerceAtMost(1f), InkSoftDarkL),
        )
    } else {
        CourseSwatchColors(
            fill = fromHsl(hue, (s * FillLightS).coerceAtMost(1f), FillLightL),
            tint = fromHsl(hue, (s * TintLightS).coerceAtMost(1f), TintLightL),
            ink = fromHsl(hue, (s * InkLightS).coerceAtMost(1f), InkLightL),
            inkSoft = fromHsl(hue, (s * InkSoftLightS).coerceAtMost(1f), InkSoftLightL),
        )
    }
}

/**
 * sRGB → HSL（h ∈ [0,360)，s/l ∈ [0,1]）。
 */
private fun toHsl(color: Color): Triple<Float, Float, Float> {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val l = (max + min) / 2f
    val s = if (delta == 0f) 0f else delta / (1f - abs(2f * l - 1f))
    val h = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta + 6f) % 6f)
        max == g -> 60f * ((b - r) / delta + 2f)
        else -> 60f * ((r - g) / delta + 4f)
    }
    return Triple(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
}

/**
 * HSL → sRGB Color。
 */
private fun fromHsl(h: Float, s: Float, l: Float): Color {
    val saturation = s.coerceIn(0f, 1f)
    val lightness = l.coerceIn(0f, 1f)
    val c = (1f - abs(2f * lightness - 1f)) * saturation
    val hp = (((h % 360f) + 360f) % 360f) / 60f
    val x = c * (1f - abs(hp % 2f - 1f))
    val rgb: Triple<Float, Float, Float> = when (hp.toInt()) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = lightness - c / 2f
    return Color(rgb.first + m, rgb.second + m, rgb.third + m)
}

// ---------------- 预览 ----------------

/** 色板预览通用内容：每色一行，tint 底 + 圆点(fill) + 主文(ink) + 辅文(inkSoft)。 */
@Composable
private fun CourseSwatchPreviewContent(dark: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Dimens.spacingL),
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingS),
    ) {
        CourseSwatch.accents.forEach { hex ->
            val swatch = CourseSwatch.resolve(hex, dark)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Dimens.courseCellCorner))
                    .background(swatch.tint)
                    .padding(horizontal = Dimens.spacingM, vertical = Dimens.spacingXs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(swatch.fill),
                )
                Spacer(modifier = Modifier.width(Dimens.spacingS))
                Text(
                    text = hex,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = swatch.ink,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "高等数学",
                    fontSize = 12.sp,
                    color = swatch.inkSoft,
                )
            }
        }
    }
}

/** 亮色色板预览。 */
@Preview(name = "CourseSwatch - 亮色", showBackground = true)
@Composable
private fun CourseSwatchLightPreview() {
    IYangoThemePreview(darkTheme = false) {
        Surface {
            CourseSwatchPreviewContent(dark = false)
        }
    }
}

/** 暗色色板预览。 */
@Preview(name = "CourseSwatch - 暗色", showBackground = true)
@Composable
private fun CourseSwatchDarkPreview() {
    IYangoThemePreview(darkTheme = true) {
        Surface {
            CourseSwatchPreviewContent(dark = true)
        }
    }
}
