package com.gfs.iyango.core.domain

/**
 * 壁纸明暗分析（能力移植自鸿蒙参考实现 `WallpaperTone.ets`）。
 *
 * 导入壁纸后自动分析平均亮度，并据此给出"对比度适配"遮罩初值，
 * 使界面文字与背景的对比度满足无障碍要求（图标/标题 ≥ 3:1，正文 ≥ 4.5:1）。
 *
 * 原理：以 WCAG 相对亮度近似计算。遮罩层是主题背景色（浅色模式接近白、
 * 深色模式接近黑），混合后的背景亮度 `Leff = L·(1-a) + Lbg·a`：
 * - 浅色模式（深色正文，L≈0.02）：要求 `Leff ≥ 0.30`（约 4:1 对比）；
 * - 深色模式（浅色正文，L≈0.93）：要求 `Leff ≤ 0.17`。
 * 由上式解出遮罩不透明度下限，再夹进页面滑杆的取值区间。
 *
 * 纯 JVM 实现：平台层负责解码并把降采样像素以 ARGB IntArray 传入。
 */
object WallpaperTone {

    /** 亮度分析失败（空像素表等）时的标记值。 */
    const val LUMINANCE_UNKNOWN: Float = -1f

    /** 遮罩建议值的下限（与设置页滑杆一致，不为 0 避免完全裸背景）。 */
    const val SCRIM_FLOOR: Float = 0.10f

    /** 遮罩建议值的上限（与设置页滑杆一致）。 */
    const val SCRIM_CEILING: Float = 0.80f

    /** 分析失败时回落的默认遮罩（与 AppPreferences 默认一致）。 */
    const val DEFAULT_SCRIM: Float = 0.18f

    /** 抽样像素平均相对亮度（Rec.709 权重，0..1）；空表返回 [LUMINANCE_UNKNOWN]。 */
    fun averageLuminance(pixels: IntArray): Float {
        if (pixels.isEmpty()) {
            return LUMINANCE_UNKNOWN
        }
        var sum = 0.0
        for (pixel in pixels) {
            val r = (pixel ushr 16) and 0xFF
            val g = (pixel ushr 8) and 0xFF
            val b = pixel and 0xFF
            sum += (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
        }
        val average = (sum / pixels.size).toFloat()
        return if (average.isFinite()) average else LUMINANCE_UNKNOWN
    }

    /**
     * 由平均亮度给出遮罩不透明度建议（0..1）。
     *
     * @param luminance [averageLuminance] 的结果；无效值回落 [DEFAULT_SCRIM]。
     * @param darkTheme 当前是否深色主题（遮罩层颜色随之反转）。
     */
    fun recommendedScrim(luminance: Float, darkTheme: Boolean): Float {
        if (!luminance.isFinite() || luminance < 0f || luminance > 1f) {
            return DEFAULT_SCRIM
        }
        return if (darkTheme) {
            // 遮罩层 ≈ 深色背景（亮度 0.05），浅色正文要求 Leff ≤ 0.17。
            val overlayLuma = 0.05f
            val target = 0.17f
            if (luminance <= target) {
                SCRIM_FLOOR
            } else {
                ((luminance - target) / (luminance - overlayLuma)).coerceIn(SCRIM_FLOOR, SCRIM_CEILING)
            }
        } else {
            // 遮罩层 ≈ 浅色背景（亮度 0.95），深色正文要求 Leff ≥ 0.30。
            val overlayLuma = 0.95f
            val target = 0.30f
            if (luminance >= target) {
                SCRIM_FLOOR
            } else {
                ((target - luminance) / (overlayLuma - luminance)).coerceIn(SCRIM_FLOOR, SCRIM_CEILING)
            }
        }
    }

    /**
     * 从壁纸降采样 ARGB 像素中提取最具代表性的主强调色（`#RRGGBB`）。
     *
     * 过滤极灰、纯黑、纯白像素后按色相 10° 分箱（共 36 桶），以饱和度与中等明度偏好加权，
     * 返回规范化明暗对比度后的 `#RRGGBB`；若无有效彩色像素则返回 null。
     */
    fun extractDominantAccentHex(pixels: IntArray): String? {
        if (pixels.isEmpty()) return null
        val binWeight = DoubleArray(36)
        val binSat = DoubleArray(36)
        val binVal = DoubleArray(36)
        val binHueSum = DoubleArray(36)
        var anyChromatic = false

        for (pixel in pixels) {
            val r = ((pixel ushr 16) and 0xFF) / 255f
            val g = ((pixel ushr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f
            val maxC = maxOf(r, g, b)
            val minC = minOf(r, g, b)
            val delta = maxC - minC
            if (maxC <= 0.16f || maxC >= 0.96f || delta <= 0.001f) continue
            val sat = delta / maxC
            if (sat < 0.14f) continue

            anyChromatic = true
            var hue = when (maxC) {
                r -> 60f * (((g - b) / delta) % 6f)
                g -> 60f * (((b - r) / delta) + 2f)
                else -> 60f * (((r - g) / delta) + 4f)
            }
            if (hue < 0f) hue += 360f

            val bin = ((hue / 10f).toInt()).coerceIn(0, 35)
            val weight = (sat * (1.0 - kotlin.math.abs(maxC - 0.55))).coerceAtLeast(0.01)
            binWeight[bin] += weight
            binHueSum[bin] += hue * weight
            binSat[bin] += sat * weight
            binVal[bin] += maxC * weight
        }

        if (!anyChromatic) return null
        var bestBin = 0
        var bestScore = -1.0
        for (i in 0 until 36) {
            val prev = (i + 35) % 36
            val next = (i + 1) % 36
            val smoothed = binWeight[i] + 0.35 * (binWeight[prev] + binWeight[next])
            if (smoothed > bestScore && binWeight[i] > 0.0) {
                bestScore = smoothed
                bestBin = i
            }
        }
        val w = binWeight[bestBin]
        if (w <= 0.0) return null

        val avgHue = ((binHueSum[bestBin] / w).toFloat()).coerceIn(0f, 359.9f)
        val avgSat = ((binSat[bestBin] / w).toFloat()).coerceIn(0.48f, 0.78f)
        val avgVal = ((binVal[bestBin] / w).toFloat()).coerceIn(0.42f, 0.72f)
        val (rOut, gOut, bOut) = hsvToRgb(avgHue, avgSat, avgVal)
        return String.format("#%02X%02X%02X", rOut, gOut, bOut)
    }

    private fun hsvToRgb(h: Float, s: Float, v: Float): Triple<Int, Int, Int> {
        val c = v * s
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (r1, g1, b1) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Triple(
            ((r1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255),
            ((g1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255),
            ((b1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255),
        )
    }
}
