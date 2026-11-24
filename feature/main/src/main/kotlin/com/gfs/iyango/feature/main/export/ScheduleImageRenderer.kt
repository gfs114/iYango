package com.gfs.iyango.feature.main.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import com.gfs.iyango.core.designsystem.theme.CourseSwatch
import com.gfs.iyango.core.domain.CourseOccurrence
import com.gfs.iyango.core.domain.CourseRules
import com.gfs.iyango.core.model.CourseInfoField
import com.gfs.iyango.core.model.DayLabels
import com.gfs.iyango.core.model.ScheduleDisplaySettings
import com.gfs.iyango.core.model.TimeSlot
import com.gfs.iyango.feature.main.schedule.ScheduleDisplayProjection
import com.gfs.iyango.feature.main.schedule.ScheduleAxisGeometry
import com.gfs.iyango.feature.main.schedule.ScheduleSectionPolicy
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 图片清晰度档位（每 dp 输出多少像素）。 */
enum class ExportResolution(val label: String, val pxPerDp: Float) {
    STANDARD("标准", 1.5f),
    HIGH("高清", 2.0f),
    ULTRA("超清", 2.5f),
}

/**
 * 一次周课表图片渲染请求。
 *
 * [occurrences] 必须是统一实例查询（含单双周/所选周/临时调整/停课/跨周移动）在该周
 * 的最终结果，渲染器不重新推导任何周规则。
 */
data class ScheduleImageRequest(
    val timetableName: String,
    val week: Int,
    val weekStart: LocalDate,
    val occurrences: List<CourseOccurrence>,
    val timeSlots: List<TimeSlot>,
    val display: ScheduleDisplaySettings = ScheduleDisplaySettings(),
    val showTeacher: Boolean = true,
    val showClassroom: Boolean = true,
    val resolution: ExportResolution = ExportResolution.STANDARD,
    val source: String = "",
    val displayDays: List<com.gfs.iyango.core.domain.EffectiveScheduleDay> = emptyList(),
)

private fun ScheduleImageRequest.slotsForDay(day: Int): List<TimeSlot> =
    displayDays.firstOrNull { it.date == weekStart.plusDays(day - 1L) }?.displayTimeSlots ?: timeSlots

/** 渲染结果；位图为 ARGB_8888，调用方负责在不再使用时让其被回收。 */
data class RenderedScheduleImage(
    val bitmap: Bitmap,
    val widthPx: Int,
    val heightPx: Int,
    val columnCount: Int,
    val sectionCount: Int,
)

/**
 * 完整周课表离屏渲染器（E04）。
 *
 * 与屏幕截图无关：按实际节次数、可见天数和最长文本重新计算画布尺寸，节次/周末列
 * 不会被滚动区域裁掉。颜色复用 F09 的 [CourseSwatch] 与课程配色覆盖，教师/教室开关
 * 优先于显示设置。
 *
 * 输出固定为浅色底（分享图片不跟随应用深色主题），同一份快照的尺寸是确定的。
 */
object ScheduleImageRenderer {

    /** 单边像素上限，避免异常设置生成系统无法分配的大图。 */
    const val MAX_DIMENSION_PX = 8192

    /** 总像素上限（ARGB_8888 约 96MB）。 */
    const val MAX_PIXELS = 24_000_000L

    /** 与课表页一致的默认/最大节次数；超出上限的异常数据不再继续放大画布。 */
    private const val DEFAULT_SECTION_COUNT = 12
    private const val MAX_SECTION_COUNT = 40

    private const val OUTER_PADDING_DP = 18f
    private const val TITLE_BLOCK_DP = 62f
    private const val HEADER_HEIGHT_DP = 46f
    private const val FOOTER_HEIGHT_DP = 22f
    private const val RAIL_WIDTH_DP = 44f
    private const val MIN_COLUMN_DP = 118f
    private const val MAX_COLUMN_DP = 320f
    private const val CARD_GAP_DP = 2f
    private const val CARD_PADDING_DP = 6f
    private const val ACCENT_STRIPE_DP = 3f

    private const val BACKGROUND = 0xFFF7F8FA.toInt()
    private const val SURFACE = 0xFFFFFFFF.toInt()
    private const val TITLE_COLOR = 0xFF1F2937.toInt()
    private const val SUBTITLE_COLOR = 0xFF6B7280.toInt()
    private const val GRID_COLOR = 0xFFE5E7EB.toInt()
    private const val HEADER_BACKGROUND = 0xFFEEF3FF.toInt()
    private const val HEADER_TEXT = 0xFF374151.toInt()

    private val DATE_RANGE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun render(request: ScheduleImageRequest): RenderedScheduleImage {
        val display = request.display.sanitized()
        val occurrences = request.occurrences
            .filter { it.course.dayOfWeek in 1..7 && it.course.startSection >= 1 }
        val dayCount = visibleDayCount(occurrences)
        return if (occurrences.isEmpty()) {
            renderEmpty(request, display, dayCount)
        } else {
            renderWeek(request, display, occurrences, dayCount)
        }
    }

    /** 周末有课就展示 7 列，否则只留工作日的 5 列，避免空列浪费分享尺寸。 */
    fun visibleDayCount(occurrences: List<CourseOccurrence>): Int =
        if (occurrences.any { it.course.dayOfWeek >= 6 }) 7 else 5

    private fun renderWeek(
        request: ScheduleImageRequest,
        display: ScheduleDisplaySettings,
        occurrences: List<CourseOccurrence>,
        dayCount: Int,
    ): RenderedScheduleImage {
        val nameSp = display.textSizeSp
        val metaSp = max(8f, nameSp - 2f)
        val rowHeightDp = max(
            52f,
            max(display.minRowHeightDp.toFloat(), nameSp * 3.4f),
        )
        val columnWidthDp = columnWidth(
            occurrences = occurrences,
            display = display,
            nameSp = nameSp,
            metaSp = metaSp,
            showTeacher = request.showTeacher,
            showClassroom = request.showClassroom,
        )
        val maxEnd = occurrences.maxOf { it.course.endSection }
        val sectionCount = ScheduleSectionPolicy.forDays(request.source,request.displayDays,maxEnd,request.timeSlots,MAX_SECTION_COUNT)
        val lunch = if(display.showLunchBreak) ScheduleAxisGeometry.commonLunchBoundary((1..7).map(request::slotsForDay)) else null
        val axisDp = ScheduleAxisGeometry(sectionCount,rowHeightDp,lunch)

        val contentWidthDp = RAIL_WIDTH_DP + dayCount * columnWidthDp
        val widthDp = OUTER_PADDING_DP * 2 + contentWidthDp
        val heightDp = OUTER_PADDING_DP * 2 + TITLE_BLOCK_DP + HEADER_HEIGHT_DP +
            axisDp.totalHeight + FOOTER_HEIGHT_DP
        val pixels = bitmapScale(widthDp, heightDp, request.resolution.pxPerDp)
        val bitmap = Bitmap.createBitmap(pixels.width, pixels.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)

        val scale = pixels.pxPerDp
        val left = px(OUTER_PADDING_DP, scale)
        drawHeaderText(canvas, request, left, px(OUTER_PADDING_DP, scale), scale)

        val gridTop = px(OUTER_PADDING_DP + TITLE_BLOCK_DP, scale)
        val gridLeft = left
        val headerHeight = px(HEADER_HEIGHT_DP, scale)
        val rowHeight = px(rowHeightDp, scale)
        val columnWidth = px(columnWidthDp, scale)
        val railWidth = px(RAIL_WIDTH_DP, scale)
        val gridWidth = railWidth + columnWidth * dayCount
        val axis = ScheduleAxisGeometry(sectionCount,rowHeight,lunch,px(18f,scale))
        val gridHeight = headerHeight + axis.totalHeight

        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        gridPaint.color = SURFACE
        canvas.drawRoundRect(
            RectF(gridLeft, gridTop, gridLeft + gridWidth, gridTop + gridHeight),
            px(10f, scale),
            px(10f, scale),
            gridPaint,
        )

        drawWeekHeader(canvas, request, display, gridLeft, gridTop, railWidth, columnWidth, headerHeight, dayCount, scale)
        drawRowBackground(
            canvas = canvas,
            gridLeft = gridLeft,
            gridTop = gridTop,
            railWidth = railWidth,
            columnWidth = columnWidth,
            rowHeight = rowHeight,
            axis = axis,
            headerHeight = headerHeight,
            sectionCount = sectionCount,
            dayCount = dayCount,
            display = display,
            scale = scale,
        )
        drawTimeSlots(canvas, request, gridLeft, gridTop, railWidth, headerHeight, rowHeight, axis, sectionCount, scale)
        drawCourses(
            canvas = canvas,
            request = request,
            display = display,
            gridLeft = gridLeft,
            gridTop = gridTop,
            railWidth = railWidth,
            columnWidth = columnWidth,
            rowHeight = rowHeight,
            axis = axis,
            headerHeight = headerHeight,
            dayCount = dayCount,
            scale = scale,
        )

        val footerPaint = textPaint(9f, false, SUBTITLE_COLOR, scale)
        drawTextTop(
            canvas,
            "由 iYango 生成 仅包含所选周的课程、教师与教室信息",
            left,
            gridTop + gridHeight + px(7f, scale),
            footerPaint,
        )
        return RenderedScheduleImage(
            bitmap = bitmap,
            widthPx = pixels.width,
            heightPx = pixels.height,
            columnCount = dayCount,
            sectionCount = sectionCount,
        )
    }

    private fun renderEmpty(
        request: ScheduleImageRequest,
        display: ScheduleDisplaySettings,
        dayCount: Int,
    ): RenderedScheduleImage {
        val widthDp = 640f
        val heightDp = 250f
        val pixels = bitmapScale(widthDp, heightDp, request.resolution.pxPerDp)
        val bitmap = Bitmap.createBitmap(pixels.width, pixels.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val scale = pixels.pxPerDp
        canvas.drawColor(BACKGROUND)
        drawHeaderText(canvas, request, px(OUTER_PADDING_DP, scale), px(OUTER_PADDING_DP, scale), scale)
        val hintPaint = textPaint(16f, true, SUBTITLE_COLOR, scale)
        val centerY = px(heightDp / 2f, scale)
        val hint = "本周没有课程安排"
        val hintWidth = hintPaint.measureText(hint)
        canvas.drawText(hint, (pixels.width - hintWidth) / 2f, centerY, hintPaint)
        return RenderedScheduleImage(
            bitmap = bitmap,
            widthPx = pixels.width,
            heightPx = pixels.height,
            columnCount = dayCount,
            sectionCount = 0,
        )
    }

    /** 标题只放课表名；不写账号/学号等无关个人信息。 */
    private fun drawHeaderText(
        canvas: Canvas,
        request: ScheduleImageRequest,
        x: Float,
        top: Float,
        scale: Float,
    ) {
        val titlePaint = textPaint(20f, true, TITLE_COLOR, scale)
        drawTextTop(
            canvas,
            request.timetableName.ifBlank { "我的课表" },
            x,
            top,
            titlePaint,
        )
    }

    private fun drawWeekHeader(
        canvas: Canvas,
        request: ScheduleImageRequest,
        display: ScheduleDisplaySettings,
        gridLeft: Float,
        gridTop: Float,
        railWidth: Float,
        columnWidth: Float,
        headerHeight: Float,
        dayCount: Int,
        scale: Float,
    ) {
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        backgroundPaint.color = parseColor(display.headerBackgroundHex, HEADER_BACKGROUND)
        canvas.drawRect(
            RectF(gridLeft, gridTop, gridLeft + railWidth + columnWidth * dayCount, gridTop + headerHeight),
            backgroundPaint,
        )
        val smallPaint = textPaint(8.5f, false, HEADER_TEXT, scale)
        drawTextCentered(canvas, "节次", gridLeft, gridTop + headerHeight / 2f, railWidth, smallPaint)

        val dayPaint = textPaint(11f, true, HEADER_TEXT, scale)
        val datePaint = textPaint(8f, false, SUBTITLE_COLOR, scale)
        for (day in 1..dayCount) {
            val centerX = gridLeft + railWidth + columnWidth * (day - 1) + columnWidth / 2f
            val weekdayFirst = display.headerOrder == com.gfs.iyango.core.model.ScheduleHeaderOrder.WEEKDAY_DATE
            val label = "周" + DayLabels.shortLabel(day)
            drawTextCentered(canvas, label, centerX, gridTop + headerHeight * (if (weekdayFirst) 0.34f else 0.72f), columnWidth, dayPaint)
            val date = request.weekStart.plusDays((day - 1).toLong())
            drawTextCentered(
                canvas,
                "${date.monthValue.toString().padStart(2, '0')}-${date.dayOfMonth.toString().padStart(2, '0')}",
                centerX,
                gridTop + headerHeight * (if (weekdayFirst) 0.72f else 0.34f),
                columnWidth,
                datePaint,
            )
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        linePaint.color = parseColor(display.gridLineHex, GRID_COLOR)
        linePaint.strokeWidth = px(1f, scale)
        canvas.drawLine(gridLeft, gridTop + headerHeight, gridLeft + railWidth + columnWidth * dayCount, gridTop + headerHeight, linePaint)
    }

    private fun drawRowBackground(
        canvas: Canvas,
        gridLeft: Float,
        gridTop: Float,
        railWidth: Float,
        columnWidth: Float,
        rowHeight: Float,
        axis: ScheduleAxisGeometry,
        headerHeight: Float,
        sectionCount: Int,
        dayCount: Int,
        display: ScheduleDisplaySettings,
        scale: Float,
    ) {
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        linePaint.color = parseColor(display.gridLineHex, GRID_COLOR)
        linePaint.strokeWidth = px(1f, scale)
        val railPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        railPaint.color = 0xFFFAFAFB.toInt()
        val railRight = gridLeft + railWidth
        val gridRight = railRight + columnWidth * dayCount
        val gridBottom = gridTop + headerHeight + axis.totalHeight
        canvas.drawRect(RectF(gridLeft, gridTop + headerHeight, railRight, gridBottom), railPaint)
        if (display.showGridLines) {
            for (line in axis.gridLines) {
                val y = gridTop + headerHeight + line
                canvas.drawLine(gridLeft, y, gridRight, y, linePaint)
            }
            axis.lunchAfter?.let { boundary ->
                val top = gridTop + headerHeight + axis.sectionBottom(boundary)
                drawTextCentered(canvas, "午休", railRight + columnWidth * dayCount / 2f,
                    top + axis.lunchHeight * 0.7f, columnWidth * dayCount, textPaint(8f,false,SUBTITLE_COLOR,scale))
            }
            for (day in 0..dayCount) {
                val x = railRight + columnWidth * day
                canvas.drawLine(x, gridTop, x, gridBottom, linePaint)
            }
        }
    }

    private fun drawTimeSlots(
        canvas: Canvas,
        request: ScheduleImageRequest,
        gridLeft: Float,
        gridTop: Float,
        railWidth: Float,
        headerHeight: Float,
        rowHeight: Float,
        axis: ScheduleAxisGeometry,
        sectionCount: Int,
        scale: Float,
    ) {
        val sectionPaint = textPaint(9.5f, true, HEADER_TEXT, scale)
        val timePaint = textPaint(7.5f, false, SUBTITLE_COLOR, scale)
        val rails = (1..7).map(request::slotsForDay)
        val shared = rails.map { rail -> rail.sortedBy { it.section }.map { Triple(it.section,it.startTime,it.endTime) } }.distinct().size == 1
        val slotsBySection = rails.first().associateBy { it.section }
        for (section in 1..sectionCount) {
            val centerX = gridLeft + railWidth / 2f
            val top = gridTop + headerHeight + axis.sectionTop(section)
            drawTextCentered(canvas, section.toString(), centerX, top + rowHeight * 0.36f, railWidth, sectionPaint)
            val start = if(shared) slotsBySection[section]?.startTime.orEmpty() else "按日"
            if (start.isNotBlank()) {
                drawTextCentered(canvas, start, centerX, top + rowHeight * 0.72f, railWidth, timePaint)
            }
        }
    }

    private fun drawCourses(
        canvas: Canvas,
        request: ScheduleImageRequest,
        display: ScheduleDisplaySettings,
        gridLeft: Float,
        gridTop: Float,
        railWidth: Float,
        columnWidth: Float,
        rowHeight: Float,
        axis: ScheduleAxisGeometry,
        headerHeight: Float,
        dayCount: Int,
        scale: Float,
    ) {
        val cells = request.occurrences.map { occurrence ->
            val course = occurrence.course
            com.gfs.iyango.feature.main.schedule.WeekScheduleData.GridCellGroup(course.dayOfWeek, course.startSection,
                listOf(com.gfs.iyango.feature.main.schedule.WeekScheduleData.GridBlock(course, occurrence,
                    (course.endSection - course.startSection + 1).coerceAtLeast(1), false)))
        }
        ScheduleDisplayProjection.project(cells, false, dayCount, request.timeSlots, request::slotsForDay).forEach { cell ->
            val occurrence = cell.primary.occurrence ?: return@forEach
            val cardWidth = columnWidth / cell.laneCount
            val cardLeft = gridLeft + railWidth + columnWidth * (cell.dayOfWeek - 1) +
                px(CARD_GAP_DP / 2f, scale) + cardWidth * cell.laneIndex
            val cardTop = gridTop + headerHeight + axis.rowToY(cell.topRow) + px(CARD_GAP_DP / 2f, scale)
            drawCard(canvas, occurrence, display, request.showTeacher, request.showClassroom,
                cardLeft, cardTop, cardWidth - px(CARD_GAP_DP, scale),
                ((axis.rowToY(cell.topRow + cell.heightRows,true)-axis.rowToY(cell.topRow)) - px(CARD_GAP_DP, scale)).coerceAtLeast(1f), scale)
        }
    }

    private fun drawCard(
        canvas: Canvas,
        occurrence: CourseOccurrence,
        display: ScheduleDisplaySettings,
        showTeacher: Boolean,
        showClassroom: Boolean,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        scale: Float,
    ) {
        if (width <= 0f || height <= 0f) return
        val course = occurrence.course
        val override = display.courseColorOverrides[
            ScheduleDisplayProjection.courseColorKey(course)
        ]
        val swatch = CourseSwatch.forCourse(
            color = override ?: course.color,
            courseId = course.id,
            dark = false,
            paletteLight = display.paletteLight,
            paletteDark = display.paletteDark,
        )
        val alpha = (display.cellAlpha.coerceIn(0.1f, 1f) * 255).toInt()
        val cardRect = RectF(left, top, left + width, top + height)
        val radius = px(display.cornerRadiusDp.toFloat(), scale)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        fillPaint.color = swatch.tint.toArgb()
        fillPaint.alpha = alpha
        canvas.drawRoundRect(cardRect, radius, radius, fillPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        borderPaint.style = Paint.Style.STROKE
        borderPaint.strokeWidth = px(0.8f, scale)
        borderPaint.color = swatch.fill.toArgb()
        borderPaint.alpha = (80 * alpha / 255f).toInt().coerceAtLeast(30)
        canvas.drawRoundRect(cardRect, radius, radius, borderPaint)

        val stripeWidth = px(ACCENT_STRIPE_DP, scale)
        val stripePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        stripePaint.color = swatch.fill.toArgb()
        canvas.drawRoundRect(
            RectF(left + px(1.5f, scale), top + px(3f, scale), left + px(1.5f, scale) + stripeWidth, top + height - px(3f, scale)),
            stripeWidth / 2f,
            stripeWidth / 2f,
            stripePaint,
        )

        val padding = px(CARD_PADDING_DP, scale)
        val textLeft = left + padding + stripeWidth + px(3f, scale)
        val textWidth = left + width - padding - textLeft
        if (textWidth <= px(8f, scale)) return
        val innerTop = top + padding
        val innerHeight = height - padding * 2f
        if (innerHeight <= 0f) return

        val nameText = course.name.ifBlank { "未命名课程" }
        val metaLines = buildList {
            display.courseInfoOrder.forEach { field ->
                when (field) {
                    CourseInfoField.CLASSROOM -> if (showClassroom && course.classroom.isNotBlank()) {
                        add(course.classroom)
                    }
                    CourseInfoField.TEACHER -> if (showTeacher && course.teacher.isNotBlank()) {
                        add(course.teacher)
                    }
                    CourseInfoField.TIME -> if (display.showTime) {
                        add(timeText(occurrence))
                    }
                }
            }
        }
        val metaPaint = textPaint(max(8f, display.textSizeSp - 2f), false, swatch.inkSoft.toArgb(), scale)
        val metaLineHeight = metaPaint.textSize * 1.25f
        val metaWrapped = metaLines.flatMap { wrapLimited(metaPaint, it, textWidth, maxLines = 2) }
        val metaHeight = metaWrapped.size * metaLineHeight
        val nameSpace = (innerHeight - metaHeight).coerceAtLeast(metaPaint.textSize)
        val (namePaint, nameLines) = fitName(
            text = nameText,
            color = swatch.ink.toArgb(),
            maxWidth = textWidth,
            maxHeight = nameSpace,
            startSize = display.textSizeSp * scale,
            scale = scale,
        )
        val nameLineHeight = namePaint.textSize * 1.22f
        val total = nameLines.size * nameLineHeight + metaHeight
        var y = innerTop + max(0f, (innerHeight - total) / 2f)
        nameLines.forEach { line ->
            drawTextTop(canvas, line, textLeft, y, namePaint)
            y += nameLineHeight
        }
        metaWrapped.forEach { line ->
            drawTextTop(canvas, line, textLeft, y, metaPaint)
            y += metaLineHeight
        }
    }

    private fun fitName(
        text: String,
        color: Int,
        maxWidth: Float,
        maxHeight: Float,
        startSize: Float,
        scale: Float,
    ): Pair<Paint, List<String>> {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = startSize
        }
        val minSize = 8f * scale
        var size = startSize
        while (true) {
            paint.textSize = size
            val lines = wrapLimited(paint, text, maxWidth, maxLines = Int.MAX_VALUE)
            val needed = lines.size * size * 1.22f
            if (needed <= maxHeight || size <= minSize) {
                if (needed <= maxHeight) return paint to lines
                val maxLines = max(1, floor(maxHeight / (size * 1.22f)).toInt())
                val clipped = if (lines.size <= maxLines) {
                    lines
                } else {
                    lines.take(maxLines).toMutableList().also { kept ->
                        if (kept.isNotEmpty()) kept[kept.lastIndex] = kept.last() + "…"
                    }
                }
                return paint to clipped
            }
            size = max(minSize, size - 0.5f * scale)
        }
    }

    private fun columnWidth(
        occurrences: List<CourseOccurrence>,
        display: ScheduleDisplaySettings,
        nameSp: Float,
        metaSp: Float,
        showTeacher: Boolean,
        showClassroom: Boolean,
    ): Float {
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = nameSp
        }
        val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = metaSp }
        val longest = occurrences.maxOfOrNull { occurrence ->
            val course = occurrence.course
            val values = buildList {
                add(namePaint.measureText(course.name.ifBlank { "未命名课程" }))
                if (showClassroom && course.classroom.isNotBlank()) {
                    add(metaPaint.measureText(course.classroom))
                }
                if (showTeacher && course.teacher.isNotBlank()) {
                    add(metaPaint.measureText(course.teacher))
                }
                if (display.showTime) add(metaPaint.measureText(timeText(occurrence)))
            }
            values.maxOrNull() ?: 0f
        } ?: 0f
        val padding = CARD_PADDING_DP * 2 + ACCENT_STRIPE_DP + 6f
        return (longest + padding).coerceIn(MIN_COLUMN_DP, MAX_COLUMN_DP)
    }

    private fun timeText(occurrence: CourseOccurrence): String {
        val start = occurrence.startMinute
        val end = occurrence.endMinute
        if (start == null || end == null || end <= start) return "时间待定"
        return "${CourseRules.clock(start)}-${CourseRules.clock(end)}"
    }

    /** 按实际需要的像素计算最终尺寸，超限时等比缩小而不是截断内容。 */
    private fun bitmapScale(widthDp: Float, heightDp: Float, requested: Float): PixelSize {
        var pxPerDp = requested
        var width = ceil(widthDp * pxPerDp).toInt().coerceAtLeast(1)
        var height = ceil(heightDp * pxPerDp).toInt().coerceAtLeast(1)
        val byDimension = min(
            MAX_DIMENSION_PX.toFloat() / width,
            MAX_DIMENSION_PX.toFloat() / height,
        )
        val byPixels = sqrt(MAX_PIXELS.toDouble() / (width.toDouble() * height)).toFloat()
        val factor = min(1f, min(byDimension, byPixels))
        if (factor < 1f) {
            pxPerDp *= factor
            width = ceil(widthDp * pxPerDp).toInt().coerceAtLeast(1)
            height = ceil(heightDp * pxPerDp).toInt().coerceAtLeast(1)
        }
        return PixelSize(width, height, pxPerDp)
    }

    private fun textPaint(sizeSp: Float, bold: Boolean, color: Int, scale: Float): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizeSp * scale
            this.color = color
            typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        }

    private fun drawTextTop(canvas: Canvas, text: String, x: Float, top: Float, paint: Paint) {
        canvas.drawText(text, x, top - paint.fontMetrics.ascent, paint)
    }

    private fun drawTextCentered(
        canvas: Canvas,
        text: String,
        centerX: Float,
        centerY: Float,
        maxWidth: Float,
        paint: Paint,
    ) {
        val width = paint.measureText(text)
        if (width <= maxWidth) {
            val metrics = paint.fontMetrics
            val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(text, centerX - width / 2f, baseline, paint)
        } else {
            val fitted = ellipsize(paint, text, maxWidth)
            val fittedWidth = paint.measureText(fitted)
            val metrics = paint.fontMetrics
            val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(fitted, centerX - fittedWidth / 2f, baseline, paint)
        }
    }

    /** 按字符折行（中文无空格），最多 [maxLines] 行，最后一行超长时补省略号。 */
    private fun wrapLimited(paint: Paint, text: String, maxWidth: Float, maxLines: Int): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var remaining = text
        while (remaining.isNotEmpty() && lines.size < maxLines) {
            val count = paint.breakText(remaining, true, maxWidth, null).coerceAtLeast(1)
            if (count >= remaining.length) {
                lines += remaining
                remaining = ""
            } else {
                var cut = count
                val lastSpace = remaining.lastIndexOf(' ', startIndex = count - 1)
                if (lastSpace > 0) cut = lastSpace + 1
                lines += remaining.substring(0, cut).trimEnd()
                remaining = remaining.substring(cut).trimStart()
            }
        }
        if (remaining.isNotEmpty() && lines.isNotEmpty()) {
            lines[lines.lastIndex] = ellipsize(paint, lines.last() + remaining, maxWidth)
        }
        return lines
    }

    private fun ellipsize(paint: Paint, text: String, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val ellipsisWidth = paint.measureText(ellipsis)
        val count = paint.breakText(text, true, maxWidth - ellipsisWidth, null).coerceAtLeast(1)
        return text.substring(0, count.coerceAtMost(text.length)) + ellipsis
    }

    private fun parseColor(hex: String, fallback: Int): Int =
        hex.takeIf { it.isNotBlank() }
            ?.let { runCatching { AndroidColor.parseColor(it) }.getOrNull() }
            ?: fallback

    private fun px(dp: Float, scale: Float): Float = dp * scale

    private data class PixelSize(val width: Int, val height: Int, val pxPerDp: Float)
}
