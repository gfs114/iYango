package com.gfs.iyango.platform.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import com.gfs.iyango.core.domain.CourseOccurrence
import java.io.File
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * WakeUp 风格离屏 Canvas 渲染引擎（文档第五章）。
 *
 * - 经典日视图：把每节课绘制为带圆角、透明度、边框与节次/时间/地点/教师信息的 [Bitmap]；
 * - 周视图整表：把整周课程绘制为单张二维网格 [Bitmap]，超限时按 [SLICE_HEIGHT_PX] 纵向切片；
 * - 自定义背景：按目标尺寸降采样解码，单张内存 <= [MAX_BACKGROUND_BYTES]。
 *
 * 纯函数式绘制，不读取任何存储；调用方负责提供渲染输入。
 */
object WakeUpCanvasRenderer {

    /** 背景图降采样上限（文档 5.3：最高不超过 720 × 1280）。 */
    const val MAX_BACKGROUND_WIDTH = 720
    const val MAX_BACKGROUND_HEIGHT = 1280

    /** 单张背景图内存上限（文档 5.3：<= 1.2MB）。 */
    const val MAX_BACKGROUND_BYTES = (1.2f * 1024 * 1024).toInt()

    /** 周视图绘制宽度上限（文档 5.3：上限 1080px）。 */
    const val MAX_GRID_WIDTH_PX = 1080

    /** 单片位图高度（文档 5.3：每片 800px）。 */
    const val SLICE_HEIGHT_PX = 800

    /** 超过该高度的整周长图必须切片（文档 5.3：1600px 阈值）。 */
    const val MAX_UNSLICED_HEIGHT_PX = 1600

    /** 单片位图字节预算（验收 8.2.5：切片 allocationByteCount < 1.5MB，留出余量）。 */
    const val MAX_SLICE_BYTES = (1.4f * 1024 * 1024).toInt()

    private const val DEFAULT_COURSE_COLOR = "#3E7BFA"

    /** 解析课程颜色（#RRGGBB / #AARRGGBB），非法时回退默认蓝。 */
    fun courseColor(hex: String): Int = runCatching {
        Color.parseColor(hex.ifBlank { DEFAULT_COURSE_COLOR })
    }.getOrDefault(Color.parseColor(DEFAULT_COURSE_COLOR))

    /**
     * 自定义背景图降采样解码（文档 5.3）。
     *
     * 先 `inJustDecodeBounds` 读宽高，再按显示尺寸计算 `inSampleSize`；
     * 无透明需求时使用 RGB_565，保证单张内存 <= [MAX_BACKGROUND_BYTES]。
     */
    fun loadBackgroundBitmap(file: File, targetWidth: Int = MAX_BACKGROUND_WIDTH, targetHeight: Int = MAX_BACKGROUND_HEIGHT): Bitmap? {
        if (!file.exists() || file.length() <= 0) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth || bounds.outHeight / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        // 极端宽高比兜底：按面积二次缩放，确保字节预算不被突破（留 2% 余量抵消取整上偏）。
        if (bitmap.allocationByteCount <= MAX_BACKGROUND_BYTES) return bitmap
        val scale = max(0.1f, 0.98f * kotlin.math.sqrt(MAX_BACKGROUND_BYTES.toFloat() / bitmap.allocationByteCount))
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /**
     * 经典日视图单节课程位图（文档 5.1 TodayColorfulService）。
     *
     * 圆角 = [radiusDp]，底色 = 课程色（不透明度 = [itemAlpha]/100），边框 = [strokeColor]，
     * 内容自上而下：节次+课程名（粗体）、时间、地点（可选 @ 前缀）、教师。
     */
    fun renderClassicCourseBitmap(
        widthPx: Int,
        heightPx: Int,
        density: Float,
        courseHex: String,
        name: String,
        teacher: String,
        classroom: String,
        sectionLabel: String,
        timeText: String,
        radiusDp: Int,
        itemAlpha: Int,
        strokeColor: Int,
        showColor: Boolean,
        courseTextColor: Int,
        showTime: Boolean,
        showLocation: Boolean,
        showRoomPrefix: Boolean,
        showTeacher: Boolean,
        itemCenterHorizontal: Boolean,
        itemCenterVertical: Boolean,
        textSizeSp: Int,
    ): Bitmap {
        val width = widthPx.coerceAtLeast(1)
        val height = heightPx.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        fun dp(value: Int) = value * density
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = applyAlpha(courseColor(courseHex), itemAlpha)
        }
        val radius = dp(radiusDp)
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, background)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, dp(1))
            color = strokeColor
        }
        if (showColor && Color.alpha(strokeColor) > 0) {
            canvas.drawRoundRect(rect.inset(max(0.5f, stroke.strokeWidth / 2)), radius, radius, stroke)
        }
        val lines = buildList {
            add((if (sectionLabel.isBlank()) "" else "$sectionLabel ").trim() + name)
            if (showTime && timeText.isNotBlank()) add(timeText)
            if (showLocation && classroom.isNotBlank()) add(if (showRoomPrefix) "@$classroom" else classroom)
            if (showTeacher && teacher.isNotBlank()) add(teacher)
        }
        drawTextLines(canvas, lines, width, height, density, courseTextColor, textSizeSp, itemCenterHorizontal, itemCenterVertical)
        return bitmap
    }

    /**
     * 周视图整表网格位图（文档 5.1 ScheduleAppWidgetService）。
     *
     * 列 = 周一..周日（showSat/showSun 控制末两列），行 = 实际最大节次；
     * 每门课按 (星期, 节次) 定位绘制圆角块；showGrid 时叠加网格线（useDottedLine 为虚线）。
     */
    fun renderWeekGridBitmap(
        widthPx: Int,
        occurrences: List<CourseOccurrence>,
        density: Float,
        itemHeightDp: Int,
        radiusDp: Int,
        itemAlpha: Int,
        otherWeekCourseAlpha: Int,
        textSizeSp: Int,
        courseTextColor: Int,
        strokeColor: Int,
        showSat: Boolean,
        showSun: Boolean,
        showLocation: Boolean,
        showRoomPrefix: Boolean,
        showTeacher: Boolean,
        showTime: Boolean,
        showGrid: Boolean,
        useDottedLine: Boolean,
        showOtherWeekCourse: Boolean,
    ): Bitmap {
        val width = widthPx.coerceAtLeast(1).coerceAtMost(MAX_GRID_WIDTH_PX)
        val visibleDays = listOf(1, 2, 3, 4, 5) + listOfNotNull(6.takeIf { showSat }, 7.takeIf { showSun })
        val columnCount = visibleDays.size.coerceAtLeast(1)
        val columnWidth = width / columnCount.toFloat()
        val rowHeight = (itemHeightDp * density).roundToInt().coerceAtLeast(1)
        val maxSection = occurrences.maxOfOrNull { it.course.endSection }?.coerceIn(1, 24) ?: 8
        val height = rowHeight * maxSection
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (showGrid) {
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = max(1f, density)
                color = 0x1F000000
                if (useDottedLine) pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
            }
            for (index in 0..columnCount) {
                val x = (columnWidth * index).coerceAtMost(width.toFloat())
                canvas.drawLine(x, 0f, x, height.toFloat(), line)
            }
            for (row in 0..maxSection) {
                canvas.drawLine(0f, (row * rowHeight).toFloat(), width.toFloat(), (row * rowHeight).toFloat(), line)
            }
        }

        val body = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, density)
            color = strokeColor
        }
        val radius = radiusDp * density
        val grouped = occurrences.groupBy { it.date.dayOfWeek.value }
        for ((day, dayCourses) in grouped) {
            val column = visibleDays.indexOf(day)
            if (column < 0) continue
            for (occurrence in dayCourses.sortedWith(compareBy({ it.course.startSection }, { it.course.endSection }))) {
                val start = (occurrence.course.startSection - 1).coerceAtLeast(0)
                val span = (occurrence.course.endSection - occurrence.course.startSection + 1).coerceAtLeast(1)
                val left = columnWidth * column + density
                val top = start * rowHeight + density
                val right = (columnWidth * (column + 1) - density).coerceAtLeast(left + 1)
                val bottom = ((start + span) * rowHeight - density).coerceAtMost(height.toFloat())
                val color = courseColor(occurrence.course.color)
                body.color = applyAlpha(color, itemAlpha)
                val rect = RectF(left, top, right, bottom)
                canvas.drawRoundRect(rect, radius, radius, body)
                if (Color.alpha(strokeColor) > 0) {
                    canvas.drawRoundRect(rect.inset(stroke.strokeWidth / 2), radius, radius, stroke)
                }
                val lines = buildList {
                    add(occurrence.course.name)
                    if (showLocation && occurrence.course.classroom.isNotBlank()) {
                        add(if (showRoomPrefix) "@${occurrence.course.classroom}" else occurrence.course.classroom)
                    }
                    if (showTeacher && occurrence.course.teacher.isNotBlank()) add(occurrence.course.teacher)
                }
                drawTextLines(
                    canvas, lines,
                    (right - left).roundToInt(), (bottom - top).roundToInt(),
                    density, courseTextColor, textSizeSp,
                    horizontalCenter = true, verticalCenter = true,
                    offsetX = left.roundToInt(), offsetY = top.roundToInt(),
                )
            }
        }
        return bitmap
    }

    /**
     * 纵向切片（文档 5.3）：高度与字节预算均达标时返回原图；
     * 否则按 [SLICE_HEIGHT_PX] 与 [MAX_SLICE_BYTES]（随位图宽度自适应）切为 2~3 片，
     * 每片远低于 Binder 阈值。
     */
    fun sliceVertical(source: Bitmap, sliceHeightPx: Int = SLICE_HEIGHT_PX): List<Bitmap> {
        if (source.height <= MAX_UNSLICED_HEIGHT_PX && source.allocationByteCount <= MAX_SLICE_BYTES) {
            return listOf(source)
        }
        val budgetHeight = (MAX_SLICE_BYTES / (source.width.coerceAtLeast(1) * 4)).coerceAtLeast(1)
        val sliceHeight = sliceHeightPx.coerceAtMost(budgetHeight)
        val slices = ceil(source.height / sliceHeight.toFloat()).toInt().coerceAtLeast(2)
        val result = (0 until slices).map { index ->
            val top = index * sliceHeight
            val bottom = (top + sliceHeight).coerceAtMost(source.height)
            Bitmap.createBitmap(source, 0, top, source.width, bottom - top)
        }
        if (result.none { it === source } && !source.isRecycled) {
            source.recycle()
        }
        return result
    }

    private fun applyAlpha(color: Int, alphaPercent: Int): Int {
        val alpha = (255 * alphaPercent.coerceIn(0, 100) / 100f).roundToInt()
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    private fun drawTextLines(
        canvas: Canvas,
        lines: List<String>,
        width: Int,
        height: Int,
        density: Float,
        textColor: Int,
        textSizeSp: Int,
        horizontalCenter: Boolean,
        verticalCenter: Boolean,
        offsetX: Int = 0,
        offsetY: Int = 0,
    ) {
        val visible = lines.filter { it.isNotBlank() }
        if (visible.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = textColor }
        val textHeight = (textSizeSp * density * 1.25f).roundToInt()
        val total = visible.size * textHeight
        var y = when {
            verticalCenter -> offsetY + ((height - total) / 2f).roundToInt() + textHeight * 0.8f
            else -> offsetY + textHeight * 0.9f
        }
        for (line in visible) {
            paint.textSize = textSizeSp * density
            paint.isFakeBoldText = line === visible.first()
            val x = when {
                horizontalCenter -> offsetX + (width - paint.measureText(line)) / 2f
                else -> offsetX + 4f * density
            }.coerceAtLeast(offsetX + 1f)
            canvas.drawText(line, x, y, paint)
            y += textHeight
        }
    }

    private fun RectF.inset(amount: Float): RectF = RectF(left + amount, top + amount, right - amount, bottom - amount)
}
