package com.gfs.iyango.feature.main.schedule

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 课表周网格的全部几何与排版数学（ui-baseline §3〔参 ScheduleLayoutMetrics〕）。
 *
 * 本文件是**纯 Kotlin**：不依赖任何 Compose/Android 类型，单位统一为 dp 数值（Float），
 * 便于 JUnit 直接验证。网格的表头、时间轴、格线与课程块全部从 [ScheduleGridMetrics]
 * 读数，任何组件不得自带尺寸，避免各处派生漂移。
 *
 * 数值出处（ui-baseline §3）：时间轴列宽 40、表头高 45；日列宽 =(容器宽-40)/天数，
 * 最小 40；行高默认 77（自适应范围 44-77，设置上限 80）；段数 12（MAX 20）；
 * 课程块定位 x=40+(day-1)*colW+0.5、y=(startSection-1)*rowH+0.5；
 * 块宽=colW-1、块高=rowH*rowSpan-1；块内 padding 4、圆角 4。
 */
object ScheduleGridMath {

    // ---------------- 固定框架尺寸 ----------------

    /** 时间轴（节次标签）列宽。 */
    const val TIME_RAIL_WIDTH_DP: Float = 40f

    /** 星期表头高度。 */
    const val HEADER_HEIGHT_DP: Float = 45f

    /** 周天数。 */
    const val DAYS: Int = 7

    /** 默认节次段数。 */
    const val DEFAULT_SECTION_COUNT: Int = 12

    /** 段数上限（防止坏导入生成无界网格）。 */
    const val MAX_SECTION_COUNT: Int = 20

    /** 日列最小宽度（窄于此改为水平滚动而不是压缩）。[放大格子] 只加高不加宽，列宽保持原值。 */
    const val MIN_DAY_COLUMN_WIDTH_DP: Float = 40f

    /** 行高下限（可读最小值）。[放大格子] 44 → 72dp：课程块更高，可容纳完整教室行。 */
    const val MIN_SECTION_HEIGHT_DP: Float = 72f

    /** 行高上限（设置滑条上限）。 */
    const val MAX_SECTION_HEIGHT_DP: Float = 80f

    /** 自适应行高上限：超过参考默认值会把节次时间拉得过散。[放大格子] 77 → 112。 */
    const val AUTO_ROW_CAP_DP: Float = 112f

    /** 无测量结果前的回退行高。[放大格子] 77 → 96。 */
    const val FALLBACK_SECTION_HEIGHT_DP: Float = 96f

    /** 相邻课程块之间的间隙（由两侧各分一半），ui-baseline 的 ±0.5 定位来源。 */
    const val CARD_GAP_DP: Float = 1f

    /** 课程块圆角回退值。 */
    const val CARD_RADIUS_DP: Float = 4f

    /** 课程块内边距。 */
    const val CARD_PADDING_DP: Float = 4f

    // ---------------- 课程卡密度档位 ----------------

    /** 日列宽度达到该值时，课程卡可显示名称+教室。[放大格子] 只加高不加宽，档位阈值保持原值。 */
    const val COMPACT_FROM_DP: Float = 88f

    /** 日列宽度达到该值时，课程卡可再显示教师与周标记。 */
    const val FULL_FROM_DP: Float = 132f

    /** 课程名字号（紧凑/完整档）。 */
    const val NAME_FONT_FULL_SP: Int = 13

    /** 课程名字号（最窄档）。 */
    const val NAME_FONT_NARROW_SP: Int = 11

    /** 课程卡次级文字行字号。 */
    const val META_FONT_SP: Int = 10

    /** 非本周（被周规则跳过）课程块的填充降权透明度。 */
    const val OFF_WEEK_OPACITY: Float = 0.38f

    // ---------------- 周选择半模态 ----------------

    /** 单个周芯片宽。 */
    const val WEEK_PICKER_CHIP_WIDTH_DP: Float = 56f

    /** 单个周芯片高。 */
    const val WEEK_PICKER_CHIP_HEIGHT_DP: Float = 68f

    /** 单个芯片占用的横向格宽（芯片 + 间距），换列计算的基数。 */
    const val WEEK_PICKER_CELL_WIDTH_DP: Float = 64f

    /** 单个芯片占用的一行高（芯片 + 底部间距），行高估算基数。 */
    const val WEEK_PICKER_CELL_HEIGHT_DP: Float = 76f

    /** 芯片内密度点边长。 */
    const val WEEK_PICKER_DOT_SIZE_DP: Float = 3f

    /** 芯片滚动区高度上限。 */
    const val WEEK_PICKER_SCROLL_MAX_HEIGHT_DP: Float = 480f

    /** 周选择器最多展示的周数（与页面加载上限一致）。 */
    const val WEEK_PICKER_MAX_WEEKS: Int = 30

    // ---------------- 当前时间指示器 ----------------

    /** 指示器药丸标签宽（挂进时间轴的横向预留）。 */
    const val NOW_LINE_LABEL_WIDTH_DP: Float = 34f

    /** 指示器行高（药丸高度，用于把横线居中在计算偏移上）。 */
    const val NOW_LINE_HEIGHT_DP: Float = 14f

    // ---------------- 纯函数 ----------------

    /**
     * 段数收敛：<1 回退默认 12，超过 [MAX_SECTION_COUNT] 截断到 20。
     */
    fun sectionCountFor(source: String, maxCourseSection: Int, maxSlotSection: Int): Int =
        ScheduleSectionPolicy.count(source, maxCourseSection, maxSlotSection, MAX_SECTION_COUNT)

    fun clampSectionCount(sectionCount: Int): Int = when {
        sectionCount < 1 -> DEFAULT_SECTION_COUNT
        sectionCount > MAX_SECTION_COUNT -> MAX_SECTION_COUNT
        else -> sectionCount
    }

    /**
     * 日列宽：先保证最小可读宽度，再把剩余宽度均分给 [days] 天（默认 7 天）。
     */
    fun dayColumnWidthFor(containerWidth: Float, railWidth: Float, days: Int = DAYS): Float {
        val count = days.coerceIn(1, DAYS)
        val usable = containerWidth - railWidth
        if (!containerWidth.isFinite() || usable <= 0f) {
            return MIN_DAY_COLUMN_WIDTH_DP
        }
        return max(MIN_DAY_COLUMN_WIDTH_DP, usable / count)
    }

    /**
     * 自适应行高：填满可视高度，向上封顶 [AUTO_ROW_CAP_DP]、向下保底 [MIN_SECTION_HEIGHT_DP]；
     * 容器高度不可用（未测量）时回退默认 77。
     */
    fun sectionHeightFor(containerHeight: Float, sectionCount: Int, headerHeight: Float): Float {
        if (!containerHeight.isFinite() || containerHeight <= 0f) {
            return FALLBACK_SECTION_HEIGHT_DP
        }
        val rows = max(1, sectionCount)
        val fill = (containerHeight - headerHeight) / rows
        if (fill < MIN_SECTION_HEIGHT_DP) {
            return MIN_SECTION_HEIGHT_DP
        }
        return min(fill, AUTO_ROW_CAP_DP)
    }

    /**
     * 课程卡密度档位：<88 仅课名；88-131 名称+教室；≥132 再加教师与周标记。
     */
    fun densityFor(dayColumnWidth: Float): CourseCardDensity = when {
        dayColumnWidth >= FULL_FROM_DP -> CourseCardDensity.FULL
        dayColumnWidth >= COMPACT_FROM_DP -> CourseCardDensity.COMPACT
        else -> CourseCardDensity.NAME_ONLY
    }

    /**
     * 课程名行数：最窄列只放课名时给 3 行，其余 2 行。
     */
    fun nameMaxLines(density: CourseCardDensity): Int =
        if (density == CourseCardDensity.NAME_ONLY) 3 else 2

    /**
     * 教室行的行数预算：卡片内高减去两行课名与全部竞争行（可选时间/教师/周标记）后，
     * 按次级行高折算；下限 2 行（压缩行保底）、上限 6 行。
     *
     * @param cardHeight 卡片总高（dp）。
     * @param cardPadding 卡片内边距（dp）。
     * @param nameFontSize 课程名字号（sp）。
     * @param metaFontSize 次级行字号（sp）。
     * @param rivalLines 教室行之后将要打印的竞争行数量（时间/教师/周标记）。
     */
    fun roomMaxLines(
        cardHeight: Float,
        cardPadding: Float,
        nameFontSize: Int,
        metaFontSize: Int,
        rivalLines: Int,
    ): Int {
        val inner = cardHeight - cardPadding * 2f
        val nameBudget = 2 * nameFontSize * 1.4f
        val metaLine = metaFontSize * 1.4f
        val afford = floor((inner - nameBudget - rivalLines * metaLine) / metaLine).toInt()
        if (afford < 2) {
            return 2
        }
        return min(afford, 6)
    }

    /**
     * 行高是否足以在卡片上打出"正在上课"状态词（跨节且行高 ≥ 44+12）。
     */
    fun showsStatusWord(rowSpan: Int, sectionHeight: Float): Boolean =
        rowSpan > 1 && sectionHeight >= MIN_SECTION_HEIGHT_DP + 12f

    /**
     * 当前时间指示器的纵向偏移：在当前节次内按分钟线性插值；不在任何节次内
     * （间隙/课前/课后/坏数据）返回 null 表示不绘制。
     *
     * @param nowMinute 当前时刻的当日分钟数。
     * @param startMinutes 按节次 1..n 的开始分钟（null=不可用）。
     * @param endMinutes 按节次 1..n 的结束分钟（null=不可用）。
     * @param sectionHeight 行高（dp）。
     */
    fun nowLineOffset(
        nowMinute: Int,
        startMinutes: List<Int?>,
        endMinutes: List<Int?>,
        sectionHeight: Float,
    ): Float? {
        if (startMinutes.size != endMinutes.size || startMinutes.isEmpty()) {
            return null
        }
        for (index in startMinutes.indices) {
            val start = startMinutes[index]
            val end = endMinutes[index]
            // 坏数据（缺时间或 end<=start）不可能包含当前时刻。
            if (start == null || end == null || end <= start) {
                continue
            }
            if (nowMinute > end) {
                continue
            }
            if (nowMinute < start) {
                return null
            }
            val ratio = (nowMinute - start).toFloat() / (end - start)
            return (index + ratio) * sectionHeight
        }
        return null
    }

    // ---------------- 周选择器布局 ----------------

    /**
     * 周芯片按 [ScheduleGridMath.WEEK_PICKER_CELL_WIDTH_DP] 自适应换列：
     * usable = sheetWidth - 32（左右 padding），至少 1 列。
     */
    fun weekPickerColumnsFor(sheetWidth: Float): Int {
        val usable = sheetWidth - 32f
        if (!usable.isFinite() || usable < WEEK_PICKER_CELL_WIDTH_DP) {
            return 1
        }
        return floor(usable / WEEK_PICKER_CELL_WIDTH_DP).toInt().coerceAtLeast(1)
    }

    /**
     * 周芯片行数：totalWeeks 先截到 [WEEK_PICKER_MAX_WEEKS]，再按列数向上取整（至少 1 行）。
     */
    fun weekPickerRows(totalWeeks: Int, columns: Int): Int {
        val total = totalWeeks.coerceIn(0, WEEK_PICKER_MAX_WEEKS)
        val cols = columns.coerceAtLeast(1)
        return max(1, ceil(total.toDouble() / cols).toInt())
    }
}

/**
 * 课程卡密度档位（ui-baseline §3〔参 CourseCardDensity〕）。
 */
enum class CourseCardDensity {
    /** 仅课程名（手机窄列）。 */
    NAME_ONLY,

    /** 名称 + 教室。 */
    COMPACT,

    /** 名称 + 教室 + 教师 + 周标记。 */
    FULL,
}

/**
 * 一次布局计算的完整度量：列宽、行高、卡位与总尺寸。
 *
 * 由测量到的容器尺寸与段数重建；重建是纯算术，无数据库访问。
 *
 * @property containerWidth 容器宽度（dp）。
 * @property containerHeight 容器高度（dp；0 = 未测量，行高回退默认）。
 * @property sectionCount 网格段数（已收敛）。
 * @property dayCount 展示天数（5 = 隐藏周末，7 = 全部）。
 * @property fixedRowHeightDp 固定行高；0 = 按可用窗口自适应。
 * @property minRowHeightDp 自适应行高下限（设置项）。
 */
data class ScheduleGridMetrics(
    val containerWidth: Float,
    val containerHeight: Float,
    val sectionCount: Int,
    val dayCount: Int = ScheduleGridMath.DAYS,
    val fixedRowHeightDp: Float = 0f,
    val minRowHeightDp: Float = ScheduleGridMath.MIN_SECTION_HEIGHT_DP,
) {
    /** 时间轴列宽。 */
    val railWidth: Float = ScheduleGridMath.TIME_RAIL_WIDTH_DP

    /** 星期表头高度。 */
    val headerHeight: Float = ScheduleGridMath.HEADER_HEIGHT_DP

    /** 展示天数（收敛到 1..7）。 */
    val visibleDayCount: Int = dayCount.coerceIn(1, ScheduleGridMath.DAYS)

    /** 行高：固定值优先，否则自适应并遵守设置下限。 */
    val sectionHeight: Float =
        if (fixedRowHeightDp > 0f) {
            fixedRowHeightDp.coerceIn(24f, ScheduleGridMath.MAX_SECTION_HEIGHT_DP)
        } else {
            max(
                minRowHeightDp.coerceIn(24f, ScheduleGridMath.MAX_SECTION_HEIGHT_DP),
                ScheduleGridMath.sectionHeightFor(containerHeight, sectionCount, headerHeight),
            )
        }

    /** 日列宽。 */
    val dayColumnWidth: Float =
        ScheduleGridMath.dayColumnWidthFor(containerWidth, railWidth, visibleDayCount)

    /** 课程卡密度档位。 */
    val density: CourseCardDensity = ScheduleGridMath.densityFor(dayColumnWidth)

    /** 日区（格线区，不含时间轴）宽度。 */
    val dayAreaWidth: Float = dayColumnWidth * visibleDayCount

    /** 时间轴 + 日区总宽。 */
    val totalWidth: Float = railWidth + dayAreaWidth

    /** 表头 + 格线区总高。 */
    val totalHeight: Float = headerHeight + sectionHeight * sectionCount

    /** 是否需要在容器内水平滚动。 */
    val scrollsHorizontally: Boolean = totalWidth > containerWidth + 0.5f

    /** 课程卡宽度：列宽减去与邻块的间隙。 */
    val cardWidth: Float = dayColumnWidth - ScheduleGridMath.CARD_GAP_DP

    /** 课程卡高：跨 [rowSpan] 节的高度（跨度让 3-4 节大课正好两倍高）。 */
    fun cardHeight(rowSpan: Int): Float {
        val rows = max(1, rowSpan)
        return sectionHeight * rows - ScheduleGridMath.CARD_GAP_DP
    }

    /** 课程卡横向定位（含时间轴，ui-baseline：x = 40+(day-1)*colW+0.5）。 */
    fun cardLeft(dayOfWeek: Int): Float {
        val day = max(1, dayOfWeek)
        return railWidth + (day - 1) * dayColumnWidth + ScheduleGridMath.CARD_GAP_DP / 2f
    }

    /** 课程卡纵向定位（ui-baseline：y = (startSection-1)*rowH+0.5）。 */
    fun cardTop(startSection: Int): Float {
        val row = max(1, startSection)
        return (row - 1) * sectionHeight + ScheduleGridMath.CARD_GAP_DP / 2f
    }
}

/**
 * 同一 (星期, 起始节次) 格子内的冲突分组（ui-baseline §3〔参 ConflictGroup〕）。
 *
 * 组内只画第一块 + 数量徽章；点击详情可翻页。分组为纯函数，输入顺序不影响结果：
 * 输出按星期、起始节次升序稳定排列。
 */
data class CellGroup<T>(val dayOfWeek: Int, val startSection: Int, val items: List<T>)

/** 冲突分组纯函数。 */
object CellGrouping {

    /**
     * 把 [items] 按 (星期, 起始节次) 分组。
     *
     * @param dayOf 元素的星期（1=周一 … 7=周日）。
     * @param startOf 元素的起始节次。
     * @param order 组内排序键（可选；用于把真实块排在幽灵块前、真实块按开始时间排序）。
     */
    fun <T> group(
        items: List<T>,
        dayOf: (T) -> Int,
        startOf: (T) -> Int,
        order: Comparator<T>? = null,
    ): List<CellGroup<T>> {
        if (items.isEmpty()) {
            return emptyList()
        }
        val buckets = LinkedHashMap<Pair<Int, Int>, MutableList<T>>()
        for (item in items) {
            val key = dayOf(item) to startOf(item)
            buckets.getOrPut(key) { mutableListOf() }.add(item)
        }
        return buckets.entries
            .sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .map { (key, bucket) ->
                CellGroup(
                    dayOfWeek = key.first,
                    startSection = key.second,
                    items = if (order == null) bucket.toList() else bucket.sortedWith(order),
                )
            }
    }
}
