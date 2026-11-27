package com.gfs.iyango.feature.main.schedule

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gfs.iyango.core.designsystem.theme.CourseSwatch
import com.gfs.iyango.core.domain.CourseReminderPlanner
import com.gfs.iyango.core.domain.CourseRules
import com.gfs.iyango.core.model.DayLabels
import com.gfs.iyango.core.model.ScheduleDisplaySettings
import com.gfs.iyango.core.model.ScheduleHeaderOrder
import com.gfs.iyango.feature.main.coursedetail.rememberCourseDetailSource
import com.gfs.iyango.feature.main.gridSurfaceColor
import com.gfs.iyango.feature.main.gridTodayColumnColor
import com.gfs.iyango.feature.main.isDarkTheme
import com.gfs.iyango.feature.main.liquidglass.dockAutoHideAware
import com.gfs.iyango.feature.main.textTertiaryColor
import java.util.Locale

data class TimelineMinuteRange(
    val startMinute: Int,
    val endMinute: Int,
) {
    val spanMinutes: Int get() = (endMinute - startMinute).coerceAtLeast(60)
}

object TimelineGeometry {
    private const val DEFAULT_START = 8 * 60
    private const val DEFAULT_END = 22 * 60

    fun computeMinuteRange(items: List<Pair<Int, Int>>): TimelineMinuteRange {
        if (items.isEmpty()) return TimelineMinuteRange(DEFAULT_START, DEFAULT_END)
        val minStart = items.minOf { it.first }.coerceIn(0, 24 * 60)
        val maxEnd = items.maxOf { it.second }.coerceIn(0, 24 * 60)
        val flooredStart = ((minOf(DEFAULT_START, minStart)) / 60) * 60
        val ceiledEnd = (((maxOf(DEFAULT_END, maxEnd) + 59) / 60) * 60).coerceAtMost(24 * 60)
        return TimelineMinuteRange(
            startMinute = flooredStart.coerceIn(0, 23 * 60),
            endMinute = ceiledEnd.coerceAtLeast(flooredStart + 60).coerceAtMost(24 * 60),
        )
    }

    fun minuteToOffsetDp(minute: Int, rangeStart: Int, dpPerMinute: Float): Float =
        (minute - rangeStart).coerceAtLeast(0) * dpPerMinute
}

private data class TimelineCourseCard(
    val cell: ScheduleDisplayProjection.RenderCell,
    val dayOfWeek: Int,
    val startMinute: Int,
    val endMinute: Int,
    val laneIndex: Int = 0,
    val laneCount: Int = 1,
)

private val TimelineCompactStyle = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    letterSpacing = 0.sp,
)

/**
 * 按真实分钟比例排列的周课表时间轴视图。
 */
@Composable
fun ScheduleTimelineView(
    data: WeekScheduleData,
    display: ScheduleDisplaySettings,
    hasWallpaper: Boolean,
    edgeToEdge: Boolean = false,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
    knownWidth: Dp = Dp.Unspecified,
    knownHeight: Dp = Dp.Unspecified,
    onGroupClick: (ScheduleDisplayProjection.RenderCell) -> Unit,
) {
    val customBackground = remember(display.backgroundColorHex) {
        timelineParseHexOrNull(display.backgroundColorHex)
    }
    val surfaceColor = (customBackground ?: gridSurfaceColor())
        .copy(alpha = if (hasWallpaper) 0.86f else 1f)

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .then(if (edgeToEdge) Modifier else Modifier.padding(horizontal = 16.dp, vertical = 8.dp)),
        shape = if (edgeToEdge) RectangleShape else RoundedCornerShape(16.dp),
        color = surfaceColor,
    ) {
        if (edgeToEdge && knownWidth != Dp.Unspecified && knownHeight != Dp.Unspecified) {
            TimelineBody(
                data = data,
                display = display,
                containerWidth = knownWidth.value,
                topInset = topInset,
                bottomInset = bottomInset,
                onGroupClick = onGroupClick,
            )
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                TimelineBody(
                    data = data,
                    display = display,
                    containerWidth = maxWidth.value,
                    topInset = topInset,
                    bottomInset = bottomInset,
                    onGroupClick = onGroupClick,
                )
            }
        }
    }
}

@Composable
private fun TimelineBody(
    data: WeekScheduleData,
    display: ScheduleDisplaySettings,
    containerWidth: Float,
    topInset: Dp,
    bottomInset: Dp,
    onGroupClick: (ScheduleDisplayProjection.RenderCell) -> Unit,
) {
    val visibleDays = if (display.showWeekends) ScheduleGridMath.DAYS else 5
    val railWidth = ScheduleGridMath.TIME_RAIL_WIDTH_DP
    val dayWidth = ((containerWidth - railWidth) / visibleDays)
        .coerceAtLeast(ScheduleGridMath.MIN_DAY_COLUMN_WIDTH_DP)
    val totalWidth = railWidth + dayWidth * visibleDays
    val headerHeight = 44f
    val dpPerMinute = 1.45f

    val entries = remember(data.cells, data.timeSlots, data.effectiveDays, display.showOffWeek, visibleDays) {
        buildTimelineCards(data, display.showOffWeek, visibleDays)
    }
    val range = remember(entries) {
        TimelineGeometry.computeMinuteRange(entries.map { it.startMinute to it.endMinute })
    }
    val canvasHeightDp = range.spanMinutes * dpPerMinute

    val horizontalScroll = rememberScrollState()
    val verticalScroll = rememberScrollState()
    val dark = isDarkTheme()
    val gridColor = remember(display.gridLineHex) {
        timelineParseHexOrNull(display.gridLineHex)
    } ?: MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val headerBg = remember(display.headerBackgroundHex) {
        timelineParseHexOrNull(display.headerBackgroundHex)
    }
    val primaryColor = MaterialTheme.colorScheme.primary

    Column(
        Modifier
            .fillMaxSize()
            .horizontalScroll(horizontalScroll)
            .padding(top = topInset),
    ) {
        // 星期表头（含今日高亮与节假日「假」「调」标识）
        Row(
            modifier = Modifier
                .width(totalWidth.dp)
                .height(headerHeight.dp)
                .background(headerBg ?: Color.Transparent),
        ) {
            Spacer(Modifier.width(railWidth.dp))
            (1..visibleDays).forEach { day ->
                val isToday = data.showsToday && data.todayDay == day
                val date = data.weekStart.plusDays((day - 1).toLong())
                val holiday = if (data.datesKnown) data.holidays[date] else null
                Column(
                    modifier = Modifier
                        .width(dayWidth.dp)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = if (!data.datesKnown || display.headerOrder == ScheduleHeaderOrder.WEEKDAY_DATE) {
                            DayLabels.shortLabel(day)
                        } else {
                            "${date.monthValue}/${date.dayOfMonth}"
                        },
                        fontSize = display.headerTextSizeSp.sp,
                        style = TimelineCompactStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                        color = if (isToday) primaryColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = if (!data.datesKnown) {
                                ""
                            } else if (display.headerOrder == ScheduleHeaderOrder.WEEKDAY_DATE) {
                                "${date.monthValue}/${date.dayOfMonth}"
                            } else {
                                DayLabels.shortLabel(day)
                            },
                            fontSize = (display.headerTextSizeSp - 3f).coerceAtLeast(8f).sp,
                            style = TimelineCompactStyle,
                            color = textTertiaryColor(),
                        )
                        if (holiday != null) {
                            val badgeColor = if (holiday.isOffDay) Color(0xFF16A34A) else Color(0xFFD97706)
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = badgeColor.copy(alpha = 0.16f),
                            ) {
                                Text(
                                    text = if (holiday.isOffDay) "假" else "调",
                                    fontSize = 8.sp,
                                    lineHeight = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = badgeColor,
                                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // 纵向可滚动时间轴画布
        Box(
            modifier = Modifier
                .width(totalWidth.dp)
                .weight(1f)
                .dockAutoHideAware()
                .verticalScroll(verticalScroll),
        ) {
            Column(Modifier.width(totalWidth.dp)) {
                Box(
                    Modifier
                        .width(totalWidth.dp)
                        .height(canvasHeightDp.dp),
                ) {
                    // 今日整列底色与网格刻度线
                    Canvas(Modifier.fillMaxSize()) {
                        val railPx = railWidth.dp.toPx()
                        val dayPx = dayWidth.dp.toPx()
                        val endX = size.width

                        if (data.showsToday && data.todayDay in 1..visibleDays) {
                            val todayLeft = railPx + (data.todayDay - 1) * dayPx
                            drawRect(
                                color = Color.Transparent,
                                topLeft = Offset(todayLeft, 0f),
                            )
                        }
                        if (display.showGridLines) {
                            var min = range.startMinute
                            while (min <= range.endMinute) {
                                val y = TimelineGeometry.minuteToOffsetDp(min, range.startMinute, dpPerMinute).dp.toPx()
                                drawLine(
                                    color = gridColor,
                                    start = Offset(railPx, y),
                                    end = Offset(endX, y),
                                    strokeWidth = display.gridLineWidthDp.dp.toPx(),
                                )
                                min += 60
                            }
                            for (day in 0 until visibleDays) {
                                val x = railPx + day * dayPx
                                drawLine(
                                    color = gridColor,
                                    start = Offset(x, 0f),
                                    end = Offset(x, size.height),
                                    strokeWidth = display.gridLineWidthDp.dp.toPx(),
                                )
                            }
                        }
                    }

                    if (data.showsToday && data.todayDay in 1..visibleDays) {
                        Box(
                            Modifier
                                .offset(x = (railWidth + (data.todayDay - 1) * dayWidth).dp)
                                .width(dayWidth.dp)
                                .fillMaxHeight()
                                .background(gridTodayColumnColor()),
                        )
                    }

                    // 左侧整点钟点轴
                    var tick = range.startMinute
                    while (tick < range.endMinute) {
                        val yDp = TimelineGeometry.minuteToOffsetDp(tick, range.startMinute, dpPerMinute)
                        val label = String.format(Locale.US, "%02d:00", tick / 60)
                        Text(
                            text = label,
                            fontSize = 10.sp,
                            color = textTertiaryColor(),
                            modifier = Modifier
                                .offset(x = 4.dp, y = (yDp + 2f).dp)
                                .width((railWidth - 6f).dp),
                        )
                        tick += 60
                    }

                    // 课程块（按分钟精确高度定位）
                    entries.forEach { card ->
                        val block = card.cell.primary
                        val topDp = TimelineGeometry.minuteToOffsetDp(card.startMinute, range.startMinute, dpPerMinute)
                        val heightDp = ((card.endMinute - card.startMinute).coerceAtLeast(20) * dpPerMinute)
                        val laneWidth = (dayWidth - 4f) / card.laneCount.coerceAtLeast(1)
                        val leftDp = railWidth + (card.dayOfWeek - 1) * dayWidth + 2f + card.laneIndex * laneWidth

                        val overrideHex = display.courseColorOverrides[CourseReminderPlanner.reminderKey(block.course)]
                        val swatch = CourseSwatch.forCourse(
                            color = overrideHex ?: block.course.color,
                            courseId = block.course.id,
                            dark = dark,
                            paletteLight = display.paletteLight,
                            paletteDark = display.paletteDark,
                        )
                        val cardAlpha = if (block.isOffWeek) display.offWeekAlpha else display.cellAlpha
                        val shape = RoundedCornerShape(display.cornerRadiusDp.dp)
                        val detailSource = rememberCourseDetailSource(
                            course = block.course,
                            occurrenceId = block.occurrence?.occurrenceId,
                            color = swatch.fill,
                            titleColor = swatch.ink,
                            titleStyle = MaterialTheme.typography.labelMedium,
                            radius = display.cornerRadiusDp.dp,
                        )

                        Box(
                            modifier = Modifier
                                .offset(x = leftDp.dp, y = topDp.dp)
                                .size(width = (laneWidth - 1f).coerceAtLeast(24f).dp, height = heightDp.dp)
                                .alpha(cardAlpha)
                                .then(detailSource.cardModifier)
                                .clip(shape)
                                .background(swatch.fill.copy(alpha = display.cellAlpha))
                                .then(
                                    if (display.borderWidthDp > 0f) {
                                        val borderColor = if (display.borderUseCourseColor) {
                                            swatch.ink
                                        } else {
                                            timelineParseHexOrNull(display.borderColorHex) ?: swatch.ink
                                        }
                                        Modifier.border(display.borderWidthDp.dp, borderColor, shape)
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable {
                                    detailSource.select()
                                    onGroupClick(card.cell)
                                }
                                .padding(horizontal = 4.dp, vertical = 3.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = block.course.name,
                                    modifier = detailSource.titleModifier,
                                    color = swatch.ink,
                                    fontSize = display.textSizeSp.sp,
                                    lineHeight = (display.textSizeSp * 1.18f).sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (display.showClassroom && block.course.classroom.isNotBlank() && heightDp >= 38f) {
                                    Text(
                                        text = if (display.showAtPrefix) "@${block.course.classroom}" else block.course.classroom,
                                        color = swatch.ink.copy(alpha = 0.85f),
                                        fontSize = (display.textSizeSp - 2f).coerceAtLeast(8f).sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (display.showTime && heightDp >= 54f) {
                                    val timeStr = String.format(
                                        Locale.US,
                                        "%02d:%02d-%02d:%02d",
                                        card.startMinute / 60,
                                        card.startMinute % 60,
                                        card.endMinute / 60,
                                        card.endMinute % 60,
                                    )
                                    Text(
                                        text = timeStr,
                                        color = swatch.ink.copy(alpha = 0.78f),
                                        fontSize = 8.sp,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }

                    // 当前时刻线
                    if (data.showsToday && data.todayDay in 1..visibleDays) {
                        val nowMinute = data.now.hour * 60 + data.now.minute
                        if (nowMinute in range.startMinute..range.endMinute) {
                            val nowY = TimelineGeometry.minuteToOffsetDp(nowMinute, range.startMinute, dpPerMinute)
                            val leftX = railWidth + (data.todayDay - 1) * dayWidth
                            Box(
                                Modifier
                                    .offset(x = leftX.dp, y = nowY.dp)
                                    .size(width = dayWidth.dp, height = 2.dp)
                                    .background(primaryColor),
                            )
                        }
                    }
                }
                if (bottomInset > 0.dp) {
                    Spacer(Modifier.height(bottomInset))
                }
            }
        }
    }
}

private fun buildTimelineCards(
    data: WeekScheduleData,
    showOffWeek: Boolean,
    visibleDays: Int,
): List<TimelineCourseCard> {
    val raw = mutableListOf<TimelineCourseCard>()
    data.cells.forEach { group ->
        if (group.dayOfWeek !in 1..visibleDays) return@forEach
        val slotsBySection = data.slotsForDay(group.dayOfWeek).associateBy { it.section }
        val blocks = if (showOffWeek) group.blocks else group.blocks.filterNot { it.isOffWeek }
        blocks.forEach { block ->
            val occ = block.occurrence
            val resolvedStart = occ?.startMinute
                ?: CourseRules.minutes(block.course.startTime)
                ?: slotsBySection[block.course.startSection]?.let { CourseRules.minutes(it.startTime) }
                ?: (8 * 60 + (block.course.startSection - 1) * 55)
            val resolvedEnd = occ?.endMinute
                ?: CourseRules.minutes(block.course.endTime)
                ?: slotsBySection[block.course.endSection]?.let { CourseRules.minutes(it.endTime) }
                ?: (resolvedStart + block.rowSpan.coerceAtLeast(1) * 45)
            val safeEnd = resolvedEnd.coerceAtLeast(resolvedStart + 25)
            raw += TimelineCourseCard(
                cell = ScheduleDisplayProjection.RenderCell(
                    dayOfWeek = group.dayOfWeek,
                    startSection = block.course.startSection,
                    rowSpan = block.rowSpan,
                    blocks = listOf(block),
                ),
                dayOfWeek = group.dayOfWeek,
                startMinute = resolvedStart,
                endMinute = safeEnd,
            )
        }
    }
    // 同一天重叠时间自动分列
    return raw.groupBy { it.dayOfWeek }.values.flatMap { dayList ->
        val sorted = dayList.sortedWith(compareBy({ it.startMinute }, { it.endMinute }))
        val placed = mutableListOf<TimelineCourseCard>()
        sorted.forEach { current ->
            val overlapping = placed.filter { other ->
                other.startMinute < current.endMinute && current.startMinute < other.endMinute
            }
            val usedLanes = overlapping.map { it.laneIndex }.toSet()
            var lane = 0
            while (lane in usedLanes) lane += 1
            placed += current.copy(laneIndex = lane)
        }
        placed.map { card ->
            val maxLane = placed.filter { other ->
                other.startMinute < card.endMinute && card.startMinute < other.endMinute
            }.maxOfOrNull { it.laneIndex } ?: 0
            card.copy(laneCount = maxLane + 1)
        }
    }
}

private fun timelineParseHexOrNull(hex: String): Color? {
    val clean = hex.trim()
    if (!clean.startsWith("#")) return null
    return runCatching { Color(android.graphics.Color.parseColor(clean)) }.getOrNull()
}
