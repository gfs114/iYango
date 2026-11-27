@file:OptIn(ExperimentalMaterial3Api::class)

package com.gfs.iyango.feature.main.schedule

import com.gfs.iyango.feature.main.coursedetail.rememberCourseDetailSource

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gfs.iyango.core.designsystem.theme.BrandColors
import com.gfs.iyango.core.designsystem.theme.CourseSwatch
import com.gfs.iyango.feature.main.liquidglass.dockAutoHideAware
import com.gfs.iyango.core.designsystem.theme.rememberBrand
import com.gfs.iyango.core.domain.CourseOccurrence
import com.gfs.iyango.core.domain.CourseRules
import com.gfs.iyango.core.domain.CourseStatus
import com.gfs.iyango.core.domain.CourseStatusCalculator
import com.gfs.iyango.core.domain.TimeRailRow
import com.gfs.iyango.core.model.CellTextAlign
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.CourseInfoField
import com.gfs.iyango.core.model.DayLabels
import com.gfs.iyango.core.model.ScheduleDisplaySettings
import kotlin.math.ceil
import com.gfs.iyango.feature.main.gridSurfaceColor
import com.gfs.iyango.feature.main.gridTodayColumnColor
import com.gfs.iyango.feature.main.isDarkTheme
import com.gfs.iyango.feature.main.textTertiaryColor

private val PageHorizontalPadding = 16.dp
private val CompactGridTextStyle = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    letterSpacing = 0.sp,
)

/**
 * 主课表可使用 [edgeToEdge] 填满实际可用区域；设置预览保留卡片容器。
 * 自适应行高，需要时纵向滚动；列放不下时横向滚动。周分页由页面承载。
 *
 * [bottomInset] 只增加滚动内容尾部留白（例如暂存调课浮条），不改变视口或行高。
 */
@Composable
fun ScheduleWeekGrid(
    data: WeekScheduleData,
    display: ScheduleDisplaySettings,
    hasWallpaper: Boolean,
    readOnly: Boolean = false,
    bottomInset: Dp = 0.dp,
    onGroupClick: (ScheduleDisplayProjection.RenderCell) -> Unit,
    interactionsEnabled: Boolean = true,
    stagedSpan: Int? = null,
    blockedRootBounds: Rect? = null,
    onDragStart: (ScheduleDisplayProjection.RenderCell) -> Boolean = { false },
    onDrop: (ScheduleDropSlot?) -> Unit = {},
    onDragCancel: () -> Unit = {},
    edgeToEdge: Boolean = false,
    topInset: Dp = 0.dp,
    knownWidth: Dp = Dp.Unspecified,
    knownHeight: Dp = Dp.Unspecified,
) {
    val brand = rememberBrand()
    val visibleDays = if (display.showWeekends) ScheduleGridMath.DAYS else 5
    val customBackground = remember(display.backgroundColorHex) {
        parseHexOrNull(display.backgroundColorHex)
    }
    // F10：有课表背景时卡片略透，让壁纸参与但仍保持文字可读。
    val surfaceColor = (customBackground ?: gridSurfaceColor())
        .copy(alpha = if (hasWallpaper) 0.86f else 1f)

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .then(if (edgeToEdge) Modifier else Modifier.padding(horizontal = PageHorizontalPadding, vertical = 8.dp)),
        shape = if (edgeToEdge) RectangleShape else RoundedCornerShape(16.dp),
        color = surfaceColor,
    ) {
        if (edgeToEdge && knownWidth != Dp.Unspecified && knownHeight != Dp.Unspecified) {
            // P2 优化：外层 ScheduleWeeks 已在 HorizontalPager 外完成容器测量，此处直接复用已知宽高，
            // 消除每个周次分页内部的 BoxWithConstraints（SubcomposeLayout）子组合开销。
            val containerWidth = knownWidth.value
            val containerHeight = (knownHeight - topInset).value.coerceAtLeast(0f)
            val metrics = remember(
                containerWidth,
                containerHeight,
                data.sectionCount,
                visibleDays,
                display.rowHeightDp,
                display.minRowHeightDp,
            ) {
                ScheduleGridMetrics(
                    containerWidth = containerWidth,
                    containerHeight = containerHeight,
                    sectionCount = data.sectionCount,
                    dayCount = visibleDays,
                    fixedRowHeightDp = display.rowHeightDp.toFloat(),
                    minRowHeightDp = display.minRowHeightDp.toFloat(),
                )
            }
            ScheduleGridBody(
                data = data, readOnly = readOnly, display = display, metrics = metrics,
                brand = brand, bottomInset = bottomInset, topInset = topInset, onGroupClick = onGroupClick,
                interactionsEnabled = interactionsEnabled, stagedSpan = stagedSpan, blockedRootBounds = blockedRootBounds,
                onDragStart = onDragStart, onDrop = onDrop, onDragCancel = onDragCancel,
            )
        } else {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (edgeToEdge) Modifier else Modifier.padding(8.dp)),
            ) {
                val containerWidth = maxWidth.value
                // Preserve the old below-header viewport for adaptive row sizing. Only the
                // scroll viewport grows; its leading inset can move behind the fixed header.
                val containerHeight = (maxHeight - topInset).value.coerceAtLeast(0f)
                val metrics = remember(
                    containerWidth,
                    containerHeight,
                    data.sectionCount,
                    visibleDays,
                    display.rowHeightDp,
                    display.minRowHeightDp,
                ) {
                    ScheduleGridMetrics(
                        containerWidth = containerWidth,
                        containerHeight = containerHeight,
                        sectionCount = data.sectionCount,
                        dayCount = visibleDays,
                        fixedRowHeightDp = display.rowHeightDp.toFloat(),
                        minRowHeightDp = display.minRowHeightDp.toFloat(),
                    )
                }

                ScheduleGridBody(
                    data = data, readOnly = readOnly, display = display, metrics = metrics,
                    brand = brand, bottomInset = bottomInset, topInset = topInset, onGroupClick = onGroupClick,
                    interactionsEnabled = interactionsEnabled, stagedSpan = stagedSpan, blockedRootBounds = blockedRootBounds,
                    onDragStart = onDragStart, onDrop = onDrop, onDragCancel = onDragCancel,
                )
            }
        }
    }
}

/**
 * 网格主体：星期表头（45dp）+ 格线区（今日列 wash / 格线 / 课程块 / 时间指示器）；
 * 所有拖拽落点使用实际格线区坐标。
 */
@Composable
private fun ScheduleGridBody(
    readOnly: Boolean,
    data: WeekScheduleData,
    display: ScheduleDisplaySettings,
    metrics: ScheduleGridMetrics,
    brand: BrandColors,
    bottomInset: Dp,
    topInset: Dp,
    onGroupClick: (ScheduleDisplayProjection.RenderCell) -> Unit,
    interactionsEnabled: Boolean = true,
    stagedSpan: Int? = null,
    blockedRootBounds: Rect? = null,
    onDragStart: (ScheduleDisplayProjection.RenderCell) -> Boolean = { false },
    onDrop: (ScheduleDropSlot?) -> Unit = {},
    onDragCancel: () -> Unit = {},
) {
    val axis = ScheduleAxisGeometry(
        sectionCount = metrics.sectionCount,
        rowHeight = metrics.sectionHeight,
        lunchAfter = if (display.showLunchBreak) data.lunchBoundary() else null,
        eveningAfter = if (display.showEveningBreak) data.eveningBoundary() else null,
    )
    val exams = if (display.showExams) data.exams else emptyList()
    var examDay by remember(data.timetableId, data.displayedWeek) { mutableStateOf<Int?>(null) }
    val examWidth: (Int) -> Float = { day -> if (exams.any { it.date.dayOfWeek.value == day && it.topRow != null })
        (metrics.dayColumnWidth * 0.3f).coerceAtMost(38f) else 0f }
    val active = com.gfs.iyango.feature.main.LocalMainTabActive.current
    val detailsEnabled = active && !readOnly && interactionsEnabled && stagedSpan == null
    androidx.compose.runtime.LaunchedEffect(detailsEnabled) { if (!detailsEnabled) examDay = null }
    if (active && examDay != null) ExamDayDialog(exams.filter { it.date.dayOfWeek.value == examDay }) { examDay = null }
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val renderCells = remember(data.cells, data.effectiveDays, data.timeSlots, display.mergeAdjacent, display.showOffWeek, metrics.visibleDayCount) {
        ScheduleDisplayProjection.project(
            cells = if (display.showOffWeek) data.cells else data.cells.map { it.copy(blocks = it.blocks.filterNot { block -> block.isOffWeek }) }.filter { it.blocks.isNotEmpty() },
            mergeAdjacent = display.mergeAdjacent,
            visibleDays = metrics.visibleDayCount,
            timeSlots = data.timeSlots,
            dayTimeSlots = data::slotsForDay,
        )
    }

    val density = LocalDensity.current
    val layoutCache = LocalScheduleGridLayoutCache.current ?: remember { ScheduleGridLayoutCache() }
    val sharedMeasurer = LocalScheduleTextMeasurer.current ?: rememberTextMeasurer(cacheSize = 64)
    var latticeCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var viewportCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var dragPosition by remember { mutableStateOf<Offset?>(null) }
    var grabOffsetY by remember { mutableStateOf<Float?>(null) }
    var capturedSpan by remember { mutableStateOf<Int?>(null) }
    val latestSpan by rememberUpdatedState(stagedSpan)
    val latestDrop by rememberUpdatedState(onDrop)
    val latestDragStart by rememberUpdatedState(onDragStart)
    fun slotAtRoot(root: Offset, span: Int?, grabOffset: Float): ScheduleDropSlot? {
        val viewport = viewportCoordinates?.boundsInRoot() ?: return null
        // This is a fixed viewport exclusion, independent of the lattice's scroll offset.
        if (!viewport.contains(root) || root.y < viewport.top + with(density) { topInset.toPx() } ||
            blockedRootBounds?.contains(root) == true) return null
        val coordinates = latticeCoordinates?.takeIf { it.isAttached } ?: return null
        val pointer = root - coordinates.localToRoot(Offset.Zero)
        if (axis.sectionAtY(with(density) { pointer.y.toDp().value }) == null) return null
        val local = pointer - Offset(0f, grabOffset)
        return scheduleDropAt(with(density) { local.x.toDp().value }, with(density) { local.y.toDp().value },
            metrics.railWidth, metrics.dayColumnWidth, metrics.visibleDayCount, axis, span ?: return null)
    }
    fun dragSlotAtRoot(root: Offset) = slotAtRoot(root, capturedSpan ?: latestSpan, grabOffsetY ?: 0f)
    // Pointer coroutines can outlive title/viewport changes. Read the current geometry
    // and exclusion bounds; staged placement uses the confirmed length and no drag grab offset.
    val stagedSlotAtRoot by rememberUpdatedState<(Offset) -> ScheduleDropSlot?> { root ->
        slotAtRoot(root, latestSpan, 0f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { viewportCoordinates = it }
            // [Dock 滚动自动收起] 纵向滚动方向上报（上滑收起、下滑弹出）。
            .dockAutoHideAware()
            // 始终挂载纵向滚动：内容未超高时也向父级分发嵌套滚动，保证下拉刷新可用。
            .verticalScroll(verticalScroll),
        contentAlignment = if (metrics.scrollsHorizontally) {
            Alignment.TopStart
        } else {
            Alignment.TopCenter
        },
    ) {
        Column(
            modifier = Modifier
                .width(metrics.totalWidth.dp)
                .padding(top = topInset)
                .then(
                    if (metrics.scrollsHorizontally) {
                        Modifier.horizontalScroll(horizontalScroll)
                    } else {
                        Modifier
                    },
                ),
        ) {
            WeekDayHeader(data = data, display = display, metrics = metrics)
            if (exams.isNotEmpty()) Row {
                Spacer(Modifier.width(metrics.railWidth.dp))
                (1..metrics.visibleDayCount).forEach { day ->
                    val count = exams.count { it.date.dayOfWeek.value == day }
                    Text(if (count > 0) "考试 $count" else "", fontSize = 10.sp,
                        modifier = Modifier.width(metrics.dayColumnWidth.dp).height(26.dp)
                            .then(if (detailsEnabled && count > 0) Modifier.clickable { examDay = day } else Modifier), textAlign = TextAlign.Center)
                }
            }
            if (metrics.visibleDayCount < 7 && exams.any { it.date.dayOfWeek.value > metrics.visibleDayCount }) Row {
                (metrics.visibleDayCount + 1..7).forEach { day ->
                    val count = exams.count { it.date.dayOfWeek.value == day }
                    if (count > 0) Text("${DayLabels.shortLabel(day)}考试 $count", fontSize = 11.sp,
                        modifier = Modifier.padding(4.dp).then(if (detailsEnabled) Modifier.clickable { examDay = day } else Modifier))
                }
            }
            Box(
                modifier = Modifier
                    .width(metrics.totalWidth.dp)
                    .height(axis.totalHeight.dp)
                    .testTag("schedule-lattice-${data.displayedWeek}")
                    .onGloballyPositioned { latticeCoordinates = it }
                    .then(if (!readOnly && stagedSpan != null && interactionsEnabled) Modifier.pointerInput(data.weekStart, stagedSpan) {
                        detectTapGestures { local ->
                            val root = latticeCoordinates?.takeIf { it.isAttached }?.localToRoot(local)
                            latestDrop(root?.let(stagedSlotAtRoot))
                        }
                    } else Modifier),
            ) {
                if (!readOnly && !interactionsEnabled) dragPosition?.let(::dragSlotAtRoot)?.let { slot ->
                    Box(Modifier.offset(x = metrics.cardLeft(slot.dayOfWeek).dp, y = axis.sectionTop(slot.startSection).dp)
                        .size(metrics.dayColumnWidth.dp, (axis.sectionBottom(slot.endSection) - axis.sectionTop(slot.startSection)).dp)
                        .border(2.dp, MaterialTheme.colorScheme.primary))
                }
                // 今日列 wash（ui-baseline §3：gridTodayColumn 整列底色）。
                if (data.showsToday && data.todayDay in 1..metrics.visibleDayCount) {
                    Box(
                        modifier = Modifier
                            .offset(
                                x = (metrics.railWidth + (data.todayDay - 1) * metrics.dayColumnWidth).dp,
                            )
                            .width(metrics.dayColumnWidth.dp)
                            .fillMaxHeight()
                            .background(gridTodayColumnColor()),
                    )
                }
                if (display.showGridLines) {
                    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.11f)
                    val effectiveLineStyle = when {
                        display.gridLineStyle != com.gfs.iyango.core.model.GridLineStyle.SOLID -> display.gridLineStyle
                        display.gridLineDashed -> com.gfs.iyango.core.model.GridLineStyle.DASHED
                        else -> com.gfs.iyango.core.model.GridLineStyle.SOLID
                    }
                    androidx.compose.foundation.Canvas(modifier = Modifier.matchParentSize()) {
                        val strokePx = display.gridLineWidthDp.coerceIn(0.5f, 4f).dp.toPx()
                        val pathEffect = when (effectiveLineStyle) {
                            com.gfs.iyango.core.model.GridLineStyle.SOLID -> null
                            com.gfs.iyango.core.model.GridLineStyle.DASHED ->
                                androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()), 0f)
                            com.gfs.iyango.core.model.GridLineStyle.DOTTED ->
                                androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx()), 0f)
                        }
                        val railPx = metrics.railWidth.dp.toPx()
                        val endX = size.width
                        for (s in 1 until axis.sectionCount) {
                            val y = axis.sectionBottom(s).dp.toPx()
                            drawLine(
                                color = gridColor,
                                start = Offset(railPx, y),
                                end = Offset(endX, y),
                                strokeWidth = strokePx,
                                pathEffect = pathEffect,
                            )
                            if (axis.lunchAfter == s || axis.eveningAfter == s) {
                                val afterBreakY = axis.sectionTop(s + 1).dp.toPx()
                                drawLine(
                                    color = gridColor,
                                    start = Offset(railPx, afterBreakY),
                                    end = Offset(endX, afterBreakY),
                                    strokeWidth = strokePx,
                                    pathEffect = pathEffect,
                                )
                            }
                        }
                        for (day in 0 until metrics.visibleDayCount) {
                            val x = (metrics.railWidth + day * metrics.dayColumnWidth).dp.toPx()
                            drawLine(
                                color = gridColor,
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = strokePx,
                                pathEffect = pathEffect,
                            )
                        }
                    }
                }
                axis.lunchAfter?.takeIf { it in 1 until axis.sectionCount }?.let { after ->
                    Text("午休", fontSize = 10.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.offset(x=metrics.railWidth.dp,y=axis.sectionBottom(after).dp)
                            .size(metrics.dayAreaWidth.dp,axis.lunchHeight.dp))
                }
                axis.eveningAfter?.takeIf { it in 1 until axis.sectionCount && it != axis.lunchAfter }?.let { after ->
                    Text(
                        "晚休",
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .offset(x = metrics.railWidth.dp, y = axis.sectionBottom(after).dp)
                            .size(metrics.dayAreaWidth.dp, axis.eveningHeight.dp),
                    )
                }
                TimeRailColumn(rows = data.displayRail(metrics.visibleDayCount), metrics = metrics, axis = axis)
                // [自适应高度] 每块下方同一天连续空闲的节数：教室文字放不下时块可以向下
                // 占用这些空白节次加高（不覆盖其他课程）。
                val occupiedRows = remember(renderCells, data.sectionCount) {
                    Array(ScheduleGridMath.DAYS + 1) { BooleanArray(data.sectionCount) }.also { occ ->
                        renderCells.forEach { c ->
                            if (c.dayOfWeek !in 1..ScheduleGridMath.DAYS) return@forEach
                            val start = c.topRow.toInt().coerceAtLeast(0)
                            val end = (ceil(c.topRow + c.heightRows).toInt() - 1)
                                .coerceAtMost(data.sectionCount - 1)
                            for (r in start..end) {
                                if (r in 0 until data.sectionCount) occ[c.dayOfWeek][r] = true
                            }
                        }
                    }
                }
                fun freeRowsBelow(cell: ScheduleDisplayProjection.RenderCell): Int {
                    var r = ceil(cell.topRow + cell.heightRows).toInt().coerceAtLeast(0)
                    var count = 0
                    while (r < data.sectionCount && !occupiedRows[cell.dayOfWeek][r]) {
                        count++
                        r++
                    }
                    return count
                }
                renderCells.forEach { cell ->
                    CourseBlockView(
                        cell = cell,
                        freeRowsBelow = freeRowsBelow(cell),
                        axis = axis,
                        courseWidth = metrics.dayColumnWidth - examWidth(cell.dayOfWeek),
                        data = data,
                        display = display,
                        metrics = metrics,
                        onClick = { onGroupClick(cell) },
                        clickable = !readOnly && interactionsEnabled && stagedSpan == null && active,
                        handlePointer = !readOnly && (stagedSpan == null || !interactionsEnabled),
                        dragEnabled = !data.readOnly && !readOnly && stagedSpan == null && interactionsEnabled && active &&
                            cell.blocks.any { it.occurrence != null && !it.isOffWeek && !it.occurrence.isTemporary },
                        onDragStart = {
                            grabOffsetY = null
                            capturedSpan = cell.primary.course.endSection - cell.primary.course.startSection + 1
                            latestDragStart(cell)
                        },
                        onDragPosition = { root ->
                            if (grabOffsetY == null) latticeCoordinates?.takeIf { it.isAttached }?.let {
                                grabOffsetY = root.y - it.localToRoot(Offset(0f, with(density) { axis.sectionTop(cell.primary.course.startSection).dp.toPx() })).y
                            }
                            dragPosition = root
                        },
                        onDragEnd = { latestDrop(dragPosition?.let(::dragSlotAtRoot)); dragPosition = null; capturedSpan = null; grabOffsetY = null },
                        onDragCancel = { dragPosition = null; capturedSpan = null; grabOffsetY = null; onDragCancel() },
                    )
                }
                exams.filter { it.topRow != null && it.date.dayOfWeek.value in 1..metrics.visibleDayCount }.groupBy { it.date }.forEach { (_, items) ->
                    items.forEachIndexed { index, item ->
                        val day = item.date.dayOfWeek.value
                        val width = examWidth(day) / items.size
                        Text(item.exam.subject, fontSize = 9.sp, color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.offset(
                                x = (metrics.railWidth + day * metrics.dayColumnWidth - examWidth(day) + index * width).dp,
                                y = axis.rowToY(item.topRow!!).dp)
                                .size(width.coerceAtLeast(1f).dp,(axis.rowToY(item.bottomRow!!,true)-axis.rowToY(item.topRow)).coerceAtLeast(1f).dp)
                                .background(MaterialTheme.colorScheme.tertiaryContainer)
                                .then(if (detailsEnabled) Modifier.clickable { examDay = day } else Modifier))
                    }
                }
                NowLineView(data = data, metrics = metrics, brand = brand, axis = axis)
            }
            Spacer(modifier = Modifier.height(bottomInset))
        }
    }
}

/** 星期表头（高 45dp）：时间轴空格 + 可见星期 + M/d；表头底色可配置。 */
@Composable
private fun WeekDayHeader(
    data: WeekScheduleData,
    display: ScheduleDisplaySettings,
    metrics: ScheduleGridMetrics,
) {
    val headerColor = remember(display.headerBackgroundHex) {
        parseHexOrNull(display.headerBackgroundHex)
    }
    Row(
        modifier = Modifier
            .width(metrics.totalWidth.dp)
            .height(metrics.headerHeight.dp)
            .background(headerColor ?: Color.Transparent),
    ) {
        Spacer(modifier = Modifier.width(metrics.railWidth.dp))
        (1..metrics.visibleDayCount).forEach { day ->
            val isToday = data.showsToday && data.todayDay == day
            Column(
                modifier = Modifier
                    .width(metrics.dayColumnWidth.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = if (!data.datesKnown || display.headerOrder == com.gfs.iyango.core.model.ScheduleHeaderOrder.WEEKDAY_DATE) DayLabels.shortLabel(day) else data.weekStart.plusDays((day - 1).toLong()).let { "${it.monthValue}/${it.dayOfMonth}" },
                    fontSize = display.headerTextSizeSp.sp,
                    lineHeight = (display.headerTextSizeSp * 1.25f).sp,
                    style = CompactGridTextStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (isToday) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                val date = data.weekStart.plusDays((day - 1).toLong())
                val holiday = if (data.datesKnown) data.holidays[date] else null
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = if (!data.datesKnown) "" else if (display.headerOrder == com.gfs.iyango.core.model.ScheduleHeaderOrder.WEEKDAY_DATE) "${date.monthValue}/${date.dayOfMonth}" else DayLabels.shortLabel(day),
                        fontSize = (display.headerTextSizeSp - 3f).coerceAtLeast(8f).sp,
                        lineHeight = ((display.headerTextSizeSp - 3f).coerceAtLeast(8f) * 1.25f).sp,
                        style = CompactGridTextStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = textTertiaryColor(),
                    )
                    if (holiday != null) {
                        val badgeColor = if (holiday.isOffDay) {
                            Color(0xFF16A34A)
                        } else {
                            Color(0xFFD97706)
                        }
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
}



/**
 * 时间轴（宽 [ScheduleGridMetrics.railWidth]）：逐行打印节号/节名与起止钟点。
 *
 * 南北作息并存的课表在差异节并列「南 / 北」两套开始时间，不伪装成全校统一；
 * 行高不足 40dp 时只保留节号/节名，避免文字被裁切。
 */
@Composable
private fun TimeRailColumn(
    rows: List<TimeRailRow>,
    metrics: ScheduleGridMetrics,
    axis: ScheduleAxisGeometry,
) {
    Box(Modifier.width(metrics.railWidth.dp).fillMaxHeight().clipToBounds()) {
        rows.filter { it.section in 1..axis.sectionCount }.forEach { row ->
            Box(Modifier.offset(y=axis.sectionTop(row.section).dp)) {
                TimeRailCell(row=row,height=axis.sectionBottom(row.section)-axis.sectionTop(row.section))
            }
        }
    }
}

@Composable
private fun TimeRailCell(row: TimeRailRow, height: Float) {
    val roomy = height >= 40f
    val density = LocalDensity.current
    val measurer = LocalScheduleTextMeasurer.current ?: rememberTextMeasurer(cacheSize = 32)
    val layoutCache = LocalScheduleGridLayoutCache.current
    val clocks = remember(roomy, row) {
        if (!roomy) emptyList() else if (row.showsCampusSplit) {
            listOf("南 ${row.southStart}", "北 ${row.northStart}")
        } else listOf(row.start, row.end).filter { it.isNotEmpty() }
    }
    val clockSize = if (row.showsCampusSplit) 8f else 9f
    // CJK fallback metrics can exceed the requested line height. Measure the
    // actual campus labels before fitting their stack to a compact row.
    val fit = remember(row, height, density.density, density.fontScale) {
        val key = TimeRailFitKey(row, height, density.density, density.fontScale)
        layoutCache?.getOrPutTimeRailFit(key) {
            val labelStyle = CompactGridTextStyle.copy(fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium)
            val clockStyle = CompactGridTextStyle.copy(fontSize = clockSize.sp, lineHeight = 10.sp)
            val textWidth = with(density) { (ScheduleGridMath.TIME_RAIL_WIDTH_DP - 2f).dp.roundToPx() }
            fun textHeight(text: String, style: TextStyle) = measurer.measure(text, style,
                maxLines = 1, softWrap = false, constraints = Constraints(maxWidth = textWidth)).size.height
            val naturalHeight = textHeight(row.label, labelStyle) + clocks.sumOf { textHeight(it, clockStyle) }
            val availableHeight = with(density) { height.dp.roundToPx() } - 2
            (availableHeight.toFloat() / naturalHeight.coerceAtLeast(1)).coerceIn(.5f, 1f)
        } ?: run {
            val labelStyle = CompactGridTextStyle.copy(fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium)
            val clockStyle = CompactGridTextStyle.copy(fontSize = clockSize.sp, lineHeight = 10.sp)
            val textWidth = with(density) { (ScheduleGridMath.TIME_RAIL_WIDTH_DP - 2f).dp.roundToPx() }
            fun textHeight(text: String, style: TextStyle) = measurer.measure(text, style,
                maxLines = 1, softWrap = false, constraints = Constraints(maxWidth = textWidth)).size.height
            val naturalHeight = textHeight(row.label, labelStyle) + clocks.sumOf { textHeight(it, clockStyle) }
            val availableHeight = with(density) { height.dp.roundToPx() } - 2
            (availableHeight.toFloat() / naturalHeight.coerceAtLeast(1)).coerceIn(.5f, 1f)
        }
    }
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val clockColor = textTertiaryColor()
    Column(
        modifier = Modifier
            .width(ScheduleGridMath.TIME_RAIL_WIDTH_DP.dp)
            .height(height.dp)
            .padding(horizontal = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = row.label,
            fontSize = (11f * fit).sp,
            lineHeight = (13f * fit).sp,
            style = CompactGridTextStyle,
            fontWeight = FontWeight.Medium,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!roomy) return@Column
        if (row.showsCampusSplit) {
            Text(
                text = "南 ${row.southStart}",
                fontSize = (8f * fit).sp,
                lineHeight = (10f * fit).sp,
                style = CompactGridTextStyle,
                color = clockColor,
                maxLines = 1,
            )
            Text(
                text = "北 ${row.northStart}",
                fontSize = (8f * fit).sp,
                lineHeight = (10f * fit).sp,
                style = CompactGridTextStyle,
                color = clockColor,
                maxLines = 1,
            )
        } else {
            if (row.start.isNotEmpty()) {
                Text(text = row.start, fontSize = (9f * fit).sp, lineHeight = (10f * fit).sp,
                    style = CompactGridTextStyle, color = clockColor, maxLines = 1)
            }
            if (row.end.isNotEmpty()) {
                Text(text = row.end, fontSize = (9f * fit).sp, lineHeight = (10f * fit).sp,
                    style = CompactGridTextStyle, color = clockColor, maxLines = 1)
            }
        }
    }
}

// ---------------- 课程块 ----------------

/**
 * 课程块（ui-baseline §3〔参 CourseBlock〕）：tint 底 + ink 字；冲突组只画第一块 +
 * 数量徽章；运行中块 = 1.5dp accent 描边 + 底部 2dp 进度条；非本周块按配置降权。
 *
 * F09：圆角、不透明度、字号/对齐、教师/教室/时间及其顺序、配色覆盖均由
 * [ScheduleDisplaySettings] 驱动；合并相邻块由 [ScheduleDisplayProjection.RenderCell]
 * 提供跨节高度。
 */
@Composable
private fun CourseBlockView(
    axis: ScheduleAxisGeometry,
    courseWidth: Float,
    cell: ScheduleDisplayProjection.RenderCell,
    /** [自适应高度] 该块下方同一天连续空闲的节数（可向下扩展块高的上限）。 */
    freeRowsBelow: Int = 0,
    data: WeekScheduleData,
    display: ScheduleDisplaySettings,
    metrics: ScheduleGridMetrics,
    onClick: () -> Unit,
    clickable: Boolean,
    dragEnabled: Boolean,
    handlePointer: Boolean,
    onDragStart: () -> Boolean,
    onDragPosition: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val startDrag by rememberUpdatedState(onDragStart)
    val moveDrag by rememberUpdatedState(onDragPosition)
    val endDrag by rememberUpdatedState(onDragEnd)
    val cancelDrag by rememberUpdatedState(onDragCancel)
    val canDrag by rememberUpdatedState(dragEnabled)
    val block = cell.primary
    val course = block.course
    val rowSpan = cell.rowSpan
    val isDark = com.gfs.iyango.feature.main.isDarkTheme()
    val overrideColor = display.courseColorOverrides[
        ScheduleDisplayProjection.courseColorKey(course)
    ]
    val swatch = remember(
        overrideColor, course.color, course.id, isDark,
        display.paletteLight, display.paletteDark,
    ) {
        CourseSwatch.forCourse(
            color = overrideColor ?: course.color,
            courseId = course.id,
            dark = isDark,
            paletteLight = display.paletteLight,
            paletteDark = display.paletteDark,
        )
    }

    // 运行状态：仅展示周 = 本周且当天命中时判定（CourseStatusCalculator 唯一入口）。
    val occurrence = block.occurrence
    val status = if (occurrence != null && data.showsToday) {
        CourseStatusCalculator.resolve(
            occurrence.date, data.now, occurrence.startMinute, occurrence.endMinute,
        )
    } else {
        CourseStatus.NOT_STARTED
    }
    val isRunning = status == CourseStatus.IN_PROGRESS
    val progress = if (isRunning && occurrence != null) {
        CourseStatusCalculator.progress(
            occurrence.date, data.now, occurrence.startMinute, occurrence.endMinute,
        ).toFloat()
    } else {
        0f
    }

    val narrow = ScheduleGridMath.densityFor(metrics.dayColumnWidth / cell.laneCount) == CourseCardDensity.NAME_ONLY
    val preferredNameSize = if (narrow) {
        (display.textSizeSp - 2f).coerceAtLeast(9f)
    } else {
        display.textSizeSp
    }
    val preferredMetaSize = (display.textSizeSp - 3f).coerceAtLeast(8f)
    val textAlign = if (display.align == CellTextAlign.CENTER) TextAlign.Center else TextAlign.Start
    val horizontalAlignment = if (display.align == CellTextAlign.CENTER) {
        Alignment.CenterHorizontally
    } else {
        Alignment.Start
    }
    val weekMark = weekMarkOf(block)
    val cardHeight = ((axis.rowToY(cell.topRow + cell.heightRows, true) - axis.rowToY(cell.topRow)) - ScheduleGridMath.CARD_GAP_DP).coerceAtLeast(1f)
    val showsRoom = display.showClassroom && course.classroom.isNotEmpty() &&
        (narrow || CourseInfoField.CLASSROOM in display.courseInfoOrder)
    val density = LocalDensity.current
    val measurer = LocalScheduleTextMeasurer.current ?: rememberTextMeasurer(cacheSize = 64)
    val layoutCache = LocalScheduleGridLayoutCache.current
    val timeText = remember(occurrence, course, data.timeSlots, data.effectiveDays) {
        timeRangeText(occurrence, course, data)
    }
    val cardLayout = remember(
        course,
        cell.topRow,
        cell.heightRows,
        cell.rowSpan,
        cell.laneCount,
        cell.conflictCount,
        freeRowsBelow,
        courseWidth,
        axis,
        metrics.dayColumnWidth,
        metrics.density,
        display.textSizeSp,
        display.showClassroom,
        display.showAtPrefix,
        display.showTeacher,
        display.showTime,
        display.courseInfoOrder,
        isRunning,
        weekMark,
        timeText,
        density.density,
        density.fontScale,
    ) {
        val cacheKey = CourseCardLayoutKey(
            courseName = course.name,
            classroom = course.classroom,
            teacher = course.teacher,
            topRow = cell.topRow,
            heightRows = cell.heightRows,
            rowSpan = cell.rowSpan,
            laneCount = cell.laneCount,
            conflictCount = cell.conflictCount,
            freeRowsBelow = freeRowsBelow,
            courseWidth = courseWidth,
            axis = axis,
            dayColumnWidth = metrics.dayColumnWidth,
            densityTier = metrics.density,
            textSizeSp = display.textSizeSp,
            showClassroom = display.showClassroom,
            showAtPrefix = display.showAtPrefix,
            showTeacher = display.showTeacher,
            showTime = display.showTime,
            courseInfoOrder = display.courseInfoOrder,
            isRunning = isRunning,
            weekMark = weekMark,
            timeText = timeText,
            density = density.density,
            fontScale = density.fontScale,
        )
        val computeLayout: () -> CourseCardLayoutSpec = {
        val innerHeightPx = with(density) { cardHeight.dp.roundToPx() - 2 * ScheduleGridMath.CARD_PADDING_DP.dp.roundToPx() }
        val innerWidthPx = with(density) { (courseWidth / cell.laneCount - ScheduleGridMath.CARD_GAP_DP).dp.roundToPx() -
            2 * ScheduleGridMath.CARD_PADDING_DP.dp.roundToPx() }.coerceAtLeast(1)
        fun paragraph(text: String, size: Float, lineHeight: Float, lines: Int, width: Int = innerWidthPx, bold: Boolean = false) =
            measurer.measure(text, CompactGridTextStyle.copy(fontSize = size.sp, lineHeight = lineHeight.sp,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
                overflow = TextOverflow.Ellipsis, maxLines = lines, constraints = Constraints(maxWidth = width.coerceAtLeast(1)))
        val roomText = (if (display.showAtPrefix) "@" else "") + course.classroom
        // Measure actual paragraphs, including CJK fallback metrics and system font
        // scale. Compact only if even one title and one classroom line cannot fit.
        var fontFit = 1f
        fun badgeWidth() = if (cell.conflictCount > 1) paragraph(cell.conflictCount.toString(),
            preferredMetaSize * fontFit, preferredMetaSize * fontFit * 1.2f, 1).size.width + with(density) { 12.dp.roundToPx() } else 0
        fun titleHeight(lines: Int) = maxOf(
            paragraph(course.name, preferredNameSize * fontFit, preferredNameSize * fontFit * 1.25f,
                lines, innerWidthPx - badgeWidth(), bold = true).size.height,
            if (cell.conflictCount > 1) paragraph(cell.conflictCount.toString(), preferredMetaSize * fontFit,
                preferredMetaSize * fontFit * 1.2f, 1).size.height else 0)
        fun roomHeight(lines: Int) = if (showsRoom) paragraph(roomText, preferredMetaSize * fontFit,
            preferredMetaSize * fontFit * 1.2f, lines).size.height else 0
        while (showsRoom && fontFit > .3f && titleHeight(1) + roomHeight(1) > innerHeightPx) {
            fontFit *= ((innerHeightPx - 1).toFloat() / (titleHeight(1) + roomHeight(1))).coerceIn(.3f, .95f)
        }
        val nameFontSize = preferredNameSize * fontFit
        val metaFontSize = preferredMetaSize * fontFit
        val nameLineHeight = nameFontSize * 1.25f
        val metaLineHeight = metaFontSize * 1.2f
        val needsWrappedRoom = showsRoom && paragraph(roomText, metaFontSize, metaLineHeight, 6).lineCount > 1
        val reservedRoomLines = if (needsWrappedRoom && titleHeight(1) + roomHeight(2) <= innerHeightPx) 2 else 1
        var nameLines = ScheduleGridMath.nameMaxLines(if (narrow) CourseCardDensity.NAME_ONLY else metrics.density)
        while (nameLines > 1 && titleHeight(nameLines) + roomHeight(reservedRoomLines) > innerHeightPx) nameLines--
        var remainingPx = (innerHeightPx - titleHeight(nameLines) - roomHeight(reservedRoomLines)).coerceAtLeast(0)
        val requestedFields = (if (narrow) listOf(CourseInfoField.CLASSROOM) else display.courseInfoOrder).distinct().filter {
            when (it) {
                CourseInfoField.CLASSROOM -> showsRoom
                CourseInfoField.TEACHER -> display.showTeacher && course.teacher.isNotEmpty()
                CourseInfoField.TIME -> display.showTime && timeText != null
            }
        }
        val wantsStatus = isRunning && ScheduleGridMath.showsStatusWord(rowSpan, metrics.sectionHeight)
        val wantsWeekMark = !narrow && weekMark.isNotEmpty()
        fun fitsOptional(text: String): Boolean {
            val pixels = paragraph(text, metaFontSize, metaLineHeight, 1).size.height
            return (pixels <= remainingPx).also { if (it) remainingPx -= pixels }
        }
        val showsStatus = wantsStatus && fitsOptional("正在上课")
        val optionalFields = requestedFields.filter { field -> when (field) {
            CourseInfoField.CLASSROOM -> false
            CourseInfoField.TEACHER -> fitsOptional(course.teacher)
            CourseInfoField.TIME -> fitsOptional(timeText.orEmpty())
        } }
        val showsWeekMark = wantsWeekMark && fitsOptional(weekMark)
        val roomBudget = roomHeight(reservedRoomLines) + remainingPx
        val roomLines = (reservedRoomLines..6).lastOrNull { roomHeight(it) <= roomBudget } ?: reservedRoomLines

        // [自适应高度] 按教室文字长度自动增减块高：
        // 1) 先向下借用同一天的空白节次（freeRowsBelow）把块加高到能容纳完整教室文字；
        // 2) 下方已被其他课程占用时，对教室字号按 0.92 比例逐次缩小兜底（下限 7sp），
        //    保证教室名始终完整显示、不被省略号截断。
        val densityScale = density.density
        val cardPaddingPx = with(density) { ScheduleGridMath.CARD_PADDING_DP.dp.roundToPx() }
        val rowHeightPx = with(density) { metrics.sectionHeight.dp.toPx() }
        fun roomLineCountAt(sizeSp: Float): Int =
            if (showsRoom) paragraph(roomText, sizeSp, sizeSp * 1.2f, 24).lineCount else 0
        var adaptiveMetaSize = metaFontSize
        var fullRoomLines = roomLineCountAt(adaptiveMetaSize)
        var extraRows = 0
        var finalHeightPx = with(density) { cardHeight.dp.toPx() }
        fun fitsAllRoom(): Boolean {
            if (!showsRoom) return true
            val linePx = with(density) { (adaptiveMetaSize * 1.2f).sp.toPx() }
            val available = finalHeightPx - 2 * cardPaddingPx - titleHeight(nameLines)
            return available >= fullRoomLines * linePx
        }
        while (!fitsAllRoom() && extraRows < freeRowsBelow.coerceAtLeast(0)) {
            extraRows++
            finalHeightPx += rowHeightPx
        }
        while (!fitsAllRoom() && adaptiveMetaSize > 7f) {
            adaptiveMetaSize *= 0.92f
            fullRoomLines = roomLineCountAt(adaptiveMetaSize)
        }
        val roomFullyVisible = fitsAllRoom()
        val finalCardHeight = (finalHeightPx / densityScale).coerceAtLeast(1f)
        val finalRoomLines = if (roomFullyVisible) {
            fullRoomLines.coerceAtLeast(roomLines)
        } else {
            roomLines
        }
        val classroomFontSize = if (roomFullyVisible) adaptiveMetaSize else metaFontSize
        val classroomLineHeight = if (roomFullyVisible) adaptiveMetaSize * 1.2f else metaLineHeight
        CourseCardLayoutSpec(
            nameFontSize = nameFontSize,
            nameLineHeight = nameLineHeight,
            nameLines = nameLines,
            metaFontSize = metaFontSize,
            metaLineHeight = metaLineHeight,
            classroomFontSize = classroomFontSize,
            classroomLineHeight = classroomLineHeight,
            finalRoomLines = finalRoomLines,
            finalCardHeight = finalCardHeight,
            requestedFields = requestedFields,
            optionalFields = optionalFields,
            showsStatus = showsStatus,
            showsWeekMark = showsWeekMark,
        )
        }
        layoutCache?.getOrPutCourseCardLayout(cacheKey, computeLayout) ?: computeLayout()
    }
    val nameFontSize = cardLayout.nameFontSize
    val nameLineHeight = cardLayout.nameLineHeight
    val nameLines = cardLayout.nameLines
    val metaFontSize = cardLayout.metaFontSize
    val metaLineHeight = cardLayout.metaLineHeight
    val classroomFontSize = cardLayout.classroomFontSize
    val classroomLineHeight = cardLayout.classroomLineHeight
    val finalRoomLines = cardLayout.finalRoomLines
    val finalCardHeight = cardLayout.finalCardHeight
    val requestedFields = cardLayout.requestedFields
    val optionalFields = cardLayout.optionalFields
    val showsStatus = cardLayout.showsStatus
    val showsWeekMark = cardLayout.showsWeekMark

    val cardAlpha = display.cellAlpha.coerceIn(0.1f, 1f) *
        if (block.isOffWeek) display.offWeekAlpha.coerceIn(0.1f, 1f) else 1f
    val cardShape = RoundedCornerShape(display.cornerRadiusDp.coerceIn(0, 40).dp)
    val detailSource = rememberCourseDetailSource(
        course, occurrence?.occurrenceId.takeIf { data.datesKnown }, swatch.tint.copy(alpha = cardAlpha), swatch.ink,
        CompactGridTextStyle.copy(fontSize = nameFontSize.sp, lineHeight = nameLineHeight.sp,
            fontWeight = FontWeight.Bold, textAlign = textAlign),
        radius = display.cornerRadiusDp.coerceIn(0, 40).dp, titleLines = nameLines, slotKey = data.weekStart,
    )
    val borderColor = if (display.borderUseCourseColor) {
        swatch.fill
    } else {
        parseHexOrNull(display.borderColorHex) ?: MaterialTheme.colorScheme.outline
    }

    Box(
        modifier = Modifier
            .offset(
                x = (metrics.cardLeft(cell.dayOfWeek) + courseWidth * cell.laneIndex / cell.laneCount).dp,
                y = (axis.rowToY(cell.topRow) + ScheduleGridMath.CARD_GAP_DP / 2f).dp,
            )
            .size(width = (courseWidth / cell.laneCount - ScheduleGridMath.CARD_GAP_DP).dp,
                height = finalCardHeight.dp)
            .testTag("schedule-course-${course.id}")
            .then(detailSource.cardModifier)
            .clip(cardShape)
            .background(swatch.tint.copy(alpha = cardAlpha))
            .then(
                when {
                    // 运行中块始终用 accent 描边，优先于自定义边框。
                    isRunning -> Modifier.border(width = 1.5.dp, color = swatch.fill, shape = cardShape)
                    display.borderWidthDp > 0f -> Modifier.courseBorder(
                        width = display.borderWidthDp,
                        color = borderColor,
                        cornerRadius = display.cornerRadiusDp.toFloat(),
                        dashed = display.borderDashed,
                    )
                    else -> Modifier
                },
            )
            .onGloballyPositioned { coordinates = it }
            .then(if (handlePointer) Modifier.clickable(enabled = clickable) {
                detailSource.select()
                onClick()
            } else Modifier)
            .then(if (handlePointer) Modifier.pointerInput(cell) {
                var accepted = false
                var position = Offset.Zero
                detectDragGesturesAfterLongPress(
                    onDragStart = { local -> position = coordinates?.takeIf { it.isAttached }?.localToRoot(local) ?: Offset.Zero
                        accepted = canDrag && startDrag()
                        if (accepted) moveDrag(position) },
                    onDrag = { change, delta -> if (accepted) {
                        change.consume(); position += delta
                        moveDrag(position)
                    } },
                    onDragEnd = { if (accepted) endDrag(); accepted = false },
                    onDragCancel = { if (accepted) cancelDrag(); accepted = false },
                )
            } else Modifier)
            .padding(ScheduleGridMath.CARD_PADDING_DP.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxHeight(),
            horizontalAlignment = horizontalAlignment,
            verticalArrangement = if (display.textVCenter) Arrangement.Center else Arrangement.Top,
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = course.name,
                    fontSize = nameFontSize.sp,
                    style = CompactGridTextStyle,
                    fontWeight = FontWeight.Bold,
                    color = swatch.ink,
                    lineHeight = nameLineHeight.sp,
                    textAlign = textAlign,
                    maxLines = nameLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).then(detailSource.titleModifier),
                )
                if (cell.conflictCount > 1) {
                    Text(
                        text = cell.conflictCount.toString(),
                        fontSize = metaFontSize.sp,
                        lineHeight = metaLineHeight.sp,
                        style = CompactGridTextStyle,
                        color = Color.White,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(swatch.fill)
                            .padding(horizontal = 4.dp),
                    )
                }
            }
            requestedFields.filter { it == CourseInfoField.CLASSROOM || it in optionalFields }.forEach { field ->
                when (field) {
                    CourseInfoField.CLASSROOM -> if (display.showClassroom && course.classroom.isNotEmpty()) {
                        Text(
                            text = (if (display.showAtPrefix) "@" else "") + course.classroom,
                            fontSize = classroomFontSize.sp,
                            lineHeight = classroomLineHeight.sp,
                            style = CompactGridTextStyle,
                            color = swatch.inkSoft,
                            textAlign = textAlign,
                            maxLines = finalRoomLines,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    CourseInfoField.TEACHER -> if (display.showTeacher && course.teacher.isNotEmpty()) {
                        Text(
                            text = course.teacher,
                            fontSize = metaFontSize.sp,
                            lineHeight = metaLineHeight.sp,
                            style = CompactGridTextStyle,
                            color = swatch.inkSoft,
                            textAlign = textAlign,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    CourseInfoField.TIME -> if (display.showTime) {
                        val time = timeRangeText(occurrence, course, data) ?: return@forEach
                        Text(
                            text = time,
                            fontSize = metaFontSize.sp,
                            lineHeight = metaLineHeight.sp,
                            style = CompactGridTextStyle,
                            color = swatch.inkSoft,
                            textAlign = textAlign,
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            if (showsStatus) {
                Text(
                    text = "正在上课",
                    fontSize = metaFontSize.sp,
                    lineHeight = metaLineHeight.sp,
                    style = CompactGridTextStyle,
                    fontWeight = FontWeight.Medium,
                    color = swatch.fill,
                    textAlign = textAlign,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (showsWeekMark) {
                Text(
                    text = weekMark,
                    fontSize = metaFontSize.sp,
                    lineHeight = metaLineHeight.sp,
                    style = CompactGridTextStyle,
                    color = swatch.inkSoft,
                    textAlign = textAlign,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (isRunning) {
            // 底部 2dp 进度条（读作时间线，不是又一行文字）。
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(swatch.inkSoft),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(2.dp)
                        .background(swatch.ink),
                )
            }
        }
    }
}

/** 课程时间文案："08:00-09:40"；钟点不可用返回 null（不猜默认作息）。 */
internal data class CourseCardLayoutSpec(
    val nameFontSize: Float,
    val nameLineHeight: Float,
    val nameLines: Int,
    val metaFontSize: Float,
    val metaLineHeight: Float,
    val classroomFontSize: Float,
    val classroomLineHeight: Float,
    val finalRoomLines: Int,
    val finalCardHeight: Float,
    val requestedFields: List<CourseInfoField>,
    val optionalFields: List<CourseInfoField>,
    val showsStatus: Boolean,
    val showsWeekMark: Boolean,
)

internal data class CourseCardLayoutKey(
    val courseName: String,
    val classroom: String,
    val teacher: String,
    val topRow: Float,
    val heightRows: Float,
    val rowSpan: Int,
    val laneCount: Int,
    val conflictCount: Int,
    val freeRowsBelow: Int,
    val courseWidth: Float,
    val axis: ScheduleAxisGeometry,
    val dayColumnWidth: Float,
    val densityTier: CourseCardDensity,
    val textSizeSp: Float,
    val showClassroom: Boolean,
    val showAtPrefix: Boolean,
    val showTeacher: Boolean,
    val showTime: Boolean,
    val courseInfoOrder: List<CourseInfoField>,
    val isRunning: Boolean,
    val weekMark: String,
    val timeText: String?,
    val density: Float,
    val fontScale: Float,
)

internal data class TimeRailFitKey(
    val row: TimeRailRow,
    val height: Float,
    val density: Float,
    val fontScale: Float,
)

/**
 * 跨周共享的课表排版测量缓存（P2 优化）：
 * 同一张课表在不同周翻页时，绝大部分课程与时间轴格的文字、尺寸、显示配置完全相同；
 * 在 [ScheduleWeeks] 外层持有该缓存，使相邻周进入组合时直接 O(1) 命中，消除主线程重复 TextMeasurer 循环。
 */
internal class ScheduleGridLayoutCache {
    private val courseLayouts = object : LinkedHashMap<CourseCardLayoutKey, CourseCardLayoutSpec>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CourseCardLayoutKey, CourseCardLayoutSpec>?): Boolean =
            size > 512
    }
    private val railFits = object : LinkedHashMap<TimeRailFitKey, Float>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TimeRailFitKey, Float>?): Boolean =
            size > 128
    }

    fun getOrPutCourseCardLayout(
        key: CourseCardLayoutKey,
        compute: () -> CourseCardLayoutSpec,
    ): CourseCardLayoutSpec = courseLayouts[key] ?: compute().also { courseLayouts[key] = it }

    fun getOrPutTimeRailFit(
        key: TimeRailFitKey,
        compute: () -> Float,
    ): Float = railFits[key] ?: compute().also { railFits[key] = it }
}

internal val LocalScheduleGridLayoutCache =
    androidx.compose.runtime.staticCompositionLocalOf<ScheduleGridLayoutCache?> { null }

internal val LocalScheduleTextMeasurer =
    androidx.compose.runtime.staticCompositionLocalOf<TextMeasurer?> { null }

private fun timeRangeText(
    occurrence: CourseOccurrence?,
    course: Course,
    data: WeekScheduleData,
): String? {
    val start = occurrence?.startMinute
    val end = occurrence?.endMinute
    if (start != null && end != null) {
        return "${CourseRules.clock(start)}-${CourseRules.clock(end)}"
    }
    if (occurrence != null) return "时间待定"
    val range = CourseRules.timeRange(course, data.slotsForDay(course.dayOfWeek))
    val startMinute = CourseRules.minutes(range.startTime)
    val endMinute = CourseRules.minutes(range.endTime)
    return if (startMinute != null && endMinute != null) {
        "${range.startTime}-${range.endTime}"
    } else {
        null
    }
}

/** "#RRGGBB" → Compose Color；空串或非法值返回 null（跟随主题）。 */
private fun parseHexOrNull(hex: String): Color? {
    if (hex.length != 7 || !hex.startsWith("#")) return null
    return runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
}

/** 课程卡边框：支持圆角与虚线（Modifier.border 不支持虚线）。 */
private fun Modifier.courseBorder(
    width: Float,
    color: Color,
    cornerRadius: Float,
    dashed: Boolean,
): Modifier = drawBehind {
    if (width <= 0f) return@drawBehind
    val strokeWidth = width.dp.toPx()
    val radius = cornerRadius.coerceIn(0f, 40f).dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
        size = Size(size.width - strokeWidth, size.height - strokeWidth),
        cornerRadius = CornerRadius(radius, radius),
        style = Stroke(
            width = strokeWidth,
            pathEffect = if (dashed) {
                PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
            } else {
                null
            },
        ),
    )
}

/** 周标记文案：非本周 / 单周 / 双周；每周课为空串。 */
private fun weekMarkOf(block: WeekScheduleData.GridBlock): String = when {
    block.isOffWeek -> "非本周"
    block.course.weekType == com.gfs.iyango.core.model.WeekType.ODD -> "单周"
    block.course.weekType == com.gfs.iyango.core.model.WeekType.EVEN -> "双周"
    else -> ""
}

// ---------------- 当前时间指示器 ----------------

/**
 * 当前时间指示器（ui-baseline §3）：accent 2dp 横线 + 左端 HH:mm 药丸（9sp onBrand），
 * 按分钟线性插值；仅展示周 = 本周且时刻落在某节次内时绘制。
 */
@Composable
private fun NowLineView(
    data: WeekScheduleData,
    metrics: ScheduleGridMetrics,
    brand: BrandColors,
    axis: ScheduleAxisGeometry,
) {
    // 隐藏周末后今日列不在网格内：指示器无处可落，直接不画。
    if (!data.showsToday || data.todayDay !in 1..metrics.visibleDayCount) {
        return
    }
    val nowMinute = data.now.hour * 60 + data.now.minute
    val offsetY = axis.minuteToY(nowMinute, data.slotsForDay(data.todayDay)) ?: return

    Box(
        modifier = Modifier
            .offset(y = (offsetY - ScheduleGridMath.NOW_LINE_HEIGHT_DP / 2f).dp)
            .height(ScheduleGridMath.NOW_LINE_HEIGHT_DP.dp),
    ) {
        // accent 2dp 横线：贯通整个日区（对齐 ui-baseline §3）。
        Box(
            modifier = Modifier
                .offset(x = metrics.railWidth.dp)
                .width(metrics.dayAreaWidth.dp)
                .height(2.dp)
                .background(brand.accent),
            contentAlignment = Alignment.CenterStart,
        ) {
            // 左端 HH:mm 药丸：挂进时间轴，盖住横线起点。
            Text(
                text = CourseRules.clock(nowMinute),
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                color = brand.onBrand,
                modifier = Modifier
                    .offset(x = -ScheduleGridMath.NOW_LINE_LABEL_WIDTH_DP.dp)
                    .clip(RoundedCornerShape(50))
                    .background(brand.accent)
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            )
        }
    }
}

// ---------------- 临时整天课表 ----------------


@Composable
private fun ExamDayDialog(items: List<ScheduleExam>, onDismiss: () -> Unit) {
    com.gfs.iyango.core.designsystem.liquidglass.LiquidAlertDialog(onDismissRequest = onDismiss,
        title = { Text("考试安排", style = MaterialTheme.typography.titleLarge) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            items.forEach { item ->
                val exam = item.exam
                Text("${exam.subject} ${when(exam.status) { com.gfs.iyango.core.model.ExamStatus.SCHEDULED -> "待考"; com.gfs.iyango.core.model.ExamStatus.COMPLETED -> "已结束"; else -> "已取消" }}", fontWeight = FontWeight.Bold)
                Text("原日期：${exam.date}${if(exam.endDate.isNotBlank()) " 至 ${exam.endDate}" else ""}\n原时刻：${exam.startTime.ifBlank { "待定" }}–${exam.endTime.ifBlank { "待定" }}\n时区：${exam.timeZone}")
                if(item.topRow == null) Text("时间未定位到网格，请以原始安排为准", fontSize = 12.sp)
                Text("地点：${exam.location.ifBlank { "未填" }}\n座位：${exam.seat.ifBlank { "未填" }}\n备注：${exam.note.ifBlank { "无" }}")
                Spacer(Modifier.height(12.dp))
            }
        } }, confirmButton = { androidx.compose.material3.TextButton(onClick=onDismiss) { Text("关闭") } })
}
