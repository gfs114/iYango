package com.gfs.iyango.feature.main.summary

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.gfs.iyango.core.domain.SemesterSummaryReport
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class PosterStyle(val label: String) {
    FROSTED_LIGHT("浅色琉璃"),
    AURORA_DARK("深色极光"),
    WALLPAPER("当前壁纸"),
}

/**
 * 学期数据卡片液态玻璃海报离屏渲染器。
 *
 * 使用 1080px 宽固定画布 + 分层软阴影 + 多色光斑散焦底图 + 高光折射描边卡片，
 * 纯软件 Canvas 绘制以兼容桌面/Robolectric 测试与全部 Android 版本。
 */
object SemesterSummaryPosterRenderer {

    private const val WIDTH = 1080
    private const val MARGIN = 60f
    private val CARD_WIDTH = WIDTH - MARGIN * 2

    private val WEEKDAY_SHORT = listOf("一", "二", "三", "四", "五", "六", "日")

    fun render(
        report: SemesterSummaryReport,
        style: PosterStyle,
        accentArgb: Int,
        wallpaperPath: String? = null,
    ): Bitmap {
        val hasGrades = report.earnedCredits != null || report.averageGpa != null || report.weightedAverage != null
        val topCoursesCount = report.topCourses.size.coerceAtLeast(1)

        val headerTop = 76f
        val headerHeight = 228f
        val kpiTop = headerTop + headerHeight + 34f
        val row1Height = 166f
        val row2Height = 156f
        val buildingCardHeight = 164f
        val kpiGap = 24f
        val kpiTotalHeight = row1Height + kpiGap + row2Height + kpiGap + buildingCardHeight

        val gradeTop = kpiTop + kpiTotalHeight + 34f
        val gradeHeight = if (hasGrades) 196f else 0f

        val chartTop = if (hasGrades) gradeTop + gradeHeight + 34f else kpiTop + kpiTotalHeight + 34f
        val chartHeight = 356f

        val coursesTop = chartTop + chartHeight + 34f
        val coursesHeight = 108f + topCoursesCount * 76f

        val totalHeight = (coursesTop + coursesHeight + 132f).roundToInt().coerceIn(1380, 2200)
        val bitmap = Bitmap.createBitmap(WIDTH, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val isDark = style != PosterStyle.FROSTED_LIGHT
        drawBackground(canvas, WIDTH, totalHeight, style, accentArgb, wallpaperPath)

        val titleColor = if (isDark) 0xFFF8FAFC.toInt() else 0xFF0F172A.toInt()
        val secondaryColor = if (isDark) 0xFFCBD5E1.toInt() else 0xFF475569.toInt()
        val resolvedAccent = adjustAccentForSurface(accentArgb, isDark)

        // 1. Header Liquid Glass Card
        val headerRect = RectF(MARGIN, headerTop, WIDTH - MARGIN, headerTop + headerHeight)
        drawGlassCard(canvas, headerRect, 40f, isDark, resolvedAccent)

        val pillRect = RectF(headerRect.left + 44f, headerRect.top + 40f, headerRect.left + 324f, headerRect.top + 88f)
        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(resolvedAccent, if (isDark) 0x3D else 0x22)
        }
        canvas.drawRoundRect(pillRect, 24f, 24f, pillPaint)
        val pillTextPaint = textPaint(22f, true, resolvedAccent)
        canvas.drawText("iYango · 学期数据", pillRect.left + 26f, pillRect.centerY() + 8f, pillTextPaint)

        val titlePaint = textPaint(48f, true, titleColor)
        canvas.drawText(
            truncate(report.semesterTitle, titlePaint, CARD_WIDTH - 88f),
            headerRect.left + 44f,
            headerRect.top + 150f,
            titlePaint,
        )
        val subtitlePaint = textPaint(26f, false, secondaryColor)
        canvas.drawText(
            "共 ${report.totalWeeks} 个教学周 · 在修 ${report.distinctCourseCount} 门课程",
            headerRect.left + 44f,
            headerRect.top + 196f,
            subtitlePaint,
        )

        // 2. KPI Glass Grid (Row 1: 2 cols, Row 2: 3 cols) + Standalone Building Card
        val row1TileWidth = (CARD_WIDTH - kpiGap) / 2f
        val row1Kpis = listOf(
            Triple("总课时（节）", "${report.totalSections}", "共上课 ${report.totalSessions} 次"),
            Triple(
                "课程节奏",
                if (report.totalWeeks > 0) String.format(Locale.US, "%.1f 节/周", report.totalSections.toFloat() / report.totalWeeks) else "0.0 节/周",
                "共 ${report.totalWeeks} 个教学周",
            ),
        )
        row1Kpis.forEachIndexed { col, (label, bigValue, hint) ->
            val left = MARGIN + col * (row1TileWidth + kpiGap)
            val rect = RectF(left, kpiTop, left + row1TileWidth, kpiTop + row1Height)
            drawGlassCard(canvas, rect, 32f, isDark, resolvedAccent)
            canvas.drawText(label, rect.left + 32f, rect.top + 48f, textPaint(24f, false, secondaryColor))
            canvas.drawText(bigValue, rect.left + 32f, rect.top + 106f, textPaint(48f, true, if (col == 0) resolvedAccent else titleColor))
            canvas.drawText(hint, rect.left + 32f, rect.top + 142f, textPaint(21f, false, withAlpha(secondaryColor, 0xCC)))
        }

        val row2Top = kpiTop + row1Height + kpiGap
        val row2TileWidth = (CARD_WIDTH - kpiGap * 2) / 3f
        val row2Kpis = listOf(
            Triple("早八天数", "${report.earlyEightDays}", "晨光早起"),
            Triple("满课天数", "${report.fullClassDays}", "最高 ${report.maxSessionsInOneDay} 节/日"),
            Triple("最忙星期", report.busiestWeekdayLabel, "课量峰值日"),
        )
        row2Kpis.forEachIndexed { col, (label, bigValue, hint) ->
            val left = MARGIN + col * (row2TileWidth + kpiGap)
            val rect = RectF(left, row2Top, left + row2TileWidth, row2Top + row2Height)
            drawGlassCard(canvas, rect, 30f, isDark, resolvedAccent)
            canvas.drawText(label, rect.left + 26f, rect.top + 44f, textPaint(22f, false, secondaryColor))
            canvas.drawText(bigValue, rect.left + 26f, rect.top + 100f, textPaint(44f, true, titleColor))
            canvas.drawText(hint, rect.left + 26f, rect.top + 134f, textPaint(20f, false, withAlpha(secondaryColor, 0xCC)))
        }

        val buildingTop = row2Top + row2Height + kpiGap
        val buildingRect = RectF(MARGIN, buildingTop, WIDTH - MARGIN, buildingTop + buildingCardHeight)
        drawGlassCard(canvas, buildingRect, 34f, isDark, resolvedAccent)
        canvas.drawText("最常去教学楼", buildingRect.left + 36f, buildingRect.top + 48f, textPaint(26f, true, titleColor))
        val visitsText = if (report.topBuildingVisits > 0) "累计前往 ${report.topBuildingVisits} 次" else "暂无教室记录"
        val visitsPaint = textPaint(22f, false, if (report.topBuildingVisits > 0) resolvedAccent else secondaryColor)
        val visitsW = visitsPaint.measureText(visitsText)
        canvas.drawText(visitsText, buildingRect.right - 36f - visitsW, buildingRect.top + 48f, visitsPaint)

        val maxBuildingW = buildingRect.width() - 72f
        val buildingPaint = fitSingleLineTextPaint(
            text = report.topBuilding,
            maxWidth = maxBuildingW,
            color = titleColor,
        )
        val buildingText = if (buildingPaint.measureText(report.topBuilding) <= maxBuildingW) {
            report.topBuilding
        } else {
            truncate(report.topBuilding, buildingPaint, maxBuildingW)
        }
        canvas.drawText(buildingText, buildingRect.left + 36f, buildingRect.top + 116f, buildingPaint)

        // 3. Optional GPA & Credits Strip
        if (hasGrades) {
            val gradeRect = RectF(MARGIN, gradeTop, WIDTH - MARGIN, gradeTop + gradeHeight)
            drawGlassCard(canvas, gradeRect, 34f, isDark, resolvedAccent)
            canvas.drawText("学业成绩快照", gradeRect.left + 36f, gradeRect.top + 48f, textPaint(26f, true, titleColor))
            canvas.drawText("统计 ${report.gradeCourseCount} 门已出分课程", gradeRect.right - 280f, gradeRect.top + 48f, textPaint(22f, false, secondaryColor))

            val colW = (CARD_WIDTH - 72f) / 3f
            val metrics = listOf(
                "平均绩点 GPA" to (report.averageGpa?.let { String.format(Locale.US, "%.2f", it) } ?: "--"),
                "已修学分" to (report.earnedCredits?.let { formatCredit(it) } ?: "--"),
                "加权均分" to (report.weightedAverage?.let { String.format(Locale.US, "%.1f", it) } ?: "--"),
            )
            metrics.forEachIndexed { idx, (label, value) ->
                val x = gradeRect.left + 36f + idx * colW
                canvas.drawText(value, x, gradeRect.top + 124f, textPaint(44f, true, if (idx == 0) resolvedAccent else titleColor))
                canvas.drawText(label, x, gradeRect.top + 162f, textPaint(22f, false, secondaryColor))
            }
        }

        // 4. Weekday Distribution Glass Card
        val chartRect = RectF(MARGIN, chartTop, WIDTH - MARGIN, chartTop + chartHeight)
        drawGlassCard(canvas, chartRect, 34f, isDark, resolvedAccent)
        canvas.drawText("周一至周日上课分布（次）", chartRect.left + 36f, chartRect.top + 54f, textPaint(28f, true, titleColor))

        val maxWeekday = report.weekdaySessionCounts.maxOrNull()?.coerceAtLeast(1) ?: 1
        val barAreaLeft = chartRect.left + 52f
        val barAreaRight = chartRect.right - 52f
        val barSlotWidth = (barAreaRight - barAreaLeft) / 7f
        val barWidth = 42f
        val barMaxHeight = 165f
        val barBaseY = chartRect.bottom - 64f
        val barBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isDark) 0x1FFFFFFF else 0x140F172A
        }

        report.weekdaySessionCounts.take(7).forEachIndexed { dayIdx, count ->
            val cx = barAreaLeft + dayIdx * barSlotWidth + barSlotWidth / 2f
            val bgBar = RectF(cx - barWidth / 2f, barBaseY - barMaxHeight, cx + barWidth / 2f, barBaseY)
            canvas.drawRoundRect(bgBar, 21f, 21f, barBgPaint)

            if (count > 0) {
                val fillH = (count.toFloat() / maxWeekday * barMaxHeight).coerceAtLeast(28f)
                val fillRect = RectF(cx - barWidth / 2f, barBaseY - fillH, cx + barWidth / 2f, barBaseY)
                val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        fillRect.left,
                        fillRect.top,
                        fillRect.left,
                        fillRect.bottom,
                        resolvedAccent,
                        withAlpha(resolvedAccent, 0x99),
                        Shader.TileMode.CLAMP,
                    )
                }
                canvas.drawRoundRect(fillRect, 21f, 21f, barPaint)
                val countText = "$count"
                val countPaint = textPaint(21f, true, titleColor)
                val cw = countPaint.measureText(countText)
                canvas.drawText(countText, cx - cw / 2f, fillRect.top - 10f, countPaint)
            }
            val dayLabel = "周${WEEKDAY_SHORT[dayIdx]}"
            val dlPaint = textPaint(22f, false, secondaryColor)
            val dlW = dlPaint.measureText(dayLabel)
            canvas.drawText(dayLabel, cx - dlW / 2f, chartRect.bottom - 26f, dlPaint)
        }

        // 5. Top 3 Courses Glass Card
        val coursesRect = RectF(MARGIN, coursesTop, WIDTH - MARGIN, coursesTop + coursesHeight)
        drawGlassCard(canvas, coursesRect, 34f, isDark, resolvedAccent)
        canvas.drawText("本学期课时 Top 课程", coursesRect.left + 36f, coursesRect.top + 54f, textPaint(28f, true, titleColor))

        if (report.topCourses.isEmpty()) {
            canvas.drawText("当前课表暂无课程数据", coursesRect.left + 36f, coursesRect.top + 124f, textPaint(25f, false, secondaryColor))
        } else {
            report.topCourses.forEachIndexed { idx, stat ->
                val rowY = coursesRect.top + 114f + idx * 76f
                val dotColor = runCatching { Color.parseColor(stat.colorHex) }.getOrDefault(resolvedAccent)
                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dotColor }
                canvas.drawCircle(coursesRect.left + 48f, rowY - 9f, 11f, dotPaint)

                val namePaint = textPaint(27f, true, titleColor)
                val rightText = "${stat.sections} 节 · ${stat.sessions} 次"
                val rightPaint = textPaint(24f, false, secondaryColor)
                val rightWidth = rightPaint.measureText(rightText)
                val maxNameWidth = coursesRect.width() - 120f - rightWidth
                canvas.drawText(
                    truncate(stat.name, namePaint, maxNameWidth),
                    coursesRect.left + 76f,
                    rowY,
                    namePaint,
                )
                canvas.drawText(rightText, coursesRect.right - 36f - rightWidth, rowY, rightPaint)
            }
        }

        // 6. Footer Watermark
        val footerText = "Generated by iYango · 仅基于本地课表与缓存数据统计"
        val footerPaint = textPaint(22f, false, withAlpha(secondaryColor, 0xB8))
        val fw = footerPaint.measureText(footerText)
        canvas.drawText(footerText, (WIDTH - fw) / 2f, totalHeight - 46f, footerPaint)

        return bitmap
    }

    private fun drawBackground(
        canvas: Canvas,
        width: Int,
        height: Int,
        style: PosterStyle,
        accentArgb: Int,
        wallpaperPath: String?,
    ) {
        if (style == PosterStyle.WALLPAPER && !wallpaperPath.isNullOrBlank()) {
            val loaded = loadBlurredWallpaper(wallpaperPath, width, height)
            if (loaded != null) {
                canvas.drawBitmap(loaded, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
                loaded.recycle()
                val scrim = Paint().apply {
                    shader = LinearGradient(
                        0f,
                        0f,
                        0f,
                        height.toFloat(),
                        intArrayOf(0x660B1120, 0x800B1120.toInt(), 0x990B1120.toInt()),
                        floatArrayOf(0f, 0.5f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                }
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
                return
            }
        }

        val isDark = style != PosterStyle.FROSTED_LIGHT
        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                if (isDark) intArrayOf(0xFF0A101F.toInt(), 0xFF131D33.toInt(), 0xFF0B1324.toInt())
                else intArrayOf(0xFFEAF2FF.toInt(), 0xFFF5F8FF.toInt(), 0xFFFDF2F8.toInt()),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), basePaint)

        val orbColors = if (isDark) {
            listOf(
                Triple(withAlpha(accentArgb, 0x66), width * 0.18f, height * 0.14f) to 520f,
                Triple(0x5538BDF8, width * 0.88f, height * 0.38f) to 560f,
                Triple(0x4D818CF8, width * 0.24f, height * 0.78f) to 580f,
            )
        } else {
            listOf(
                Triple(withAlpha(accentArgb, 0x45), width * 0.16f, height * 0.12f) to 520f,
                Triple(0x4060A5FA, width * 0.86f, height * 0.42f) to 560f,
                Triple(0x3CF472B6, width * 0.22f, height * 0.82f) to 540f,
            )
        }
        orbColors.forEach { (triple, radius) ->
            val (color, cx, cy) = triple
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx, cy, radius, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            }
            canvas.drawCircle(cx, cy, radius, paint)
        }
    }

    private fun drawGlassCard(
        canvas: Canvas,
        rect: RectF,
        radius: Float,
        isDark: Boolean,
        accentArgb: Int,
    ) {
        // 软外投影（分层半透明圆角矩形，纯 Canvas 跨平台稳定渲染）
        for (step in 1..3) {
            val spread = step * 4f
            val shadowRect = RectF(
                rect.left - spread * 0.4f,
                rect.top + step * 3f,
                rect.right + spread * 0.4f,
                rect.bottom + step * 5f,
            )
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (isDark) withAlpha(0xFF000000.toInt(), 0x1F / step) else withAlpha(0xFF0F172A.toInt(), 0x10 / step)
            }
            canvas.drawRoundRect(shadowRect, radius + spread * 0.3f, radius + spread * 0.3f, shadowPaint)
        }

        // 玻璃半透底面渐变 + 极浅强调色浸润
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                rect.left,
                rect.top,
                rect.right,
                rect.bottom,
                if (isDark) {
                    intArrayOf(
                        withAlpha(0xFF1E293B.toInt(), 0xB8),
                        withAlpha(blendColor(0xFF141E30.toInt(), accentArgb, 0.08f), 0xA6),
                    )
                } else {
                    intArrayOf(
                        withAlpha(0xFFFFFFFF.toInt(), 0xD8),
                        withAlpha(blendColor(0xFFF8FAFF.toInt(), accentArgb, 0.05f), 0xBA),
                    )
                },
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        // 液态玻璃标志性高光斜边描边（左上强反射，右下柔和收敛）
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.4f
            shader = LinearGradient(
                rect.left,
                rect.top,
                rect.right,
                rect.bottom,
                if (isDark) intArrayOf(0x66FFFFFF, 0x1AFFFFFF, 0x38FFFFFF)
                else intArrayOf(0xE6FFFFFF.toInt(), 0x66FFFFFF, 0xB3FFFFFF.toInt()),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(rect, radius, radius, borderPaint)
    }

    private fun loadBlurredWallpaper(path: String, targetW: Int, targetH: Int): Bitmap? = runCatching {
        val file = File(path)
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sample = max(1, min(bounds.outWidth / 270, bounds.outHeight / 420))
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null

        val srcAspect = decoded.width.toFloat() / decoded.height
        val dstAspect = targetW.toFloat() / targetH
        val cropRect = if (srcAspect > dstAspect) {
            val cropW = (decoded.height * dstAspect).roundToInt().coerceIn(1, decoded.width)
            val x = (decoded.width - cropW) / 2
            Rect(x, 0, x + cropW, decoded.height)
        } else {
            val cropH = (decoded.width / dstAspect).roundToInt().coerceIn(1, decoded.height)
            val y = (decoded.height - cropH) / 2
            Rect(0, y, decoded.width, y + cropH)
        }

        // 先缩到极小尺寸再放大，在纯软件 Canvas 上实现丝滑壁纸虚化散焦效果
        val smallW = 72
        val smallH = (smallW / dstAspect).roundToInt().coerceAtLeast(16)
        val tiny = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
        Canvas(tiny).drawBitmap(
            decoded,
            cropRect,
            Rect(0, 0, smallW, smallH),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        decoded.recycle()
        tiny
    }.getOrNull()

    private fun fitSingleLineTextPaint(text: String, maxWidth: Float, color: Int): Paint {
        for (size in listOf(42f, 34f, 28f)) {
            val candidate = textPaint(size, true, color)
            if (candidate.measureText(text) <= maxWidth) return candidate
        }
        return textPaint(26f, true, color)
    }

    private fun textPaint(sizePx: Float, bold: Boolean, color: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePx
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

    private fun truncate(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) {
            end--
        }
        return text.substring(0, end) + "…"
    }

    private fun formatCredit(value: Double): String {
        val rounded = value.roundToInt()
        return if (kotlin.math.abs(value - rounded) < 0.05) "$rounded" else String.format(Locale.US, "%.1f", value)
    }

    private fun adjustAccentForSurface(argb: Int, isDark: Boolean): Int {
        val r = Color.red(argb)
        val g = Color.green(argb)
        val b = Color.blue(argb)
        val luma = (r * 299 + g * 587 + b * 114) / 1000
        return when {
            isDark && luma < 120 -> blendColor(argb, Color.WHITE, 0.38f)
            !isDark && luma > 190 -> blendColor(argb, 0xFF1E3A8A.toInt(), 0.42f)
            else -> argb or 0xFF000000.toInt()
        }
    }

    private fun withAlpha(argb: Int, alpha: Int): Int =
        (argb and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

    private fun blendColor(base: Int, overlay: Int, ratio: Float): Int {
        val inv = 1f - ratio
        val r = (Color.red(base) * inv + Color.red(overlay) * ratio).roundToInt().coerceIn(0, 255)
        val g = (Color.green(base) * inv + Color.green(overlay) * ratio).roundToInt().coerceIn(0, 255)
        val b = (Color.blue(base) * inv + Color.blue(overlay) * ratio).roundToInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }
}
