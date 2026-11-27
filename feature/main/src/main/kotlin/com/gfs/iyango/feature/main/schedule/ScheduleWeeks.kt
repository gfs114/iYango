package com.gfs.iyango.feature.main.schedule

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.liquidglass.LiquidAlertDialog
import com.gfs.iyango.core.designsystem.liquidglass.LiquidFloatingBar
import com.gfs.iyango.core.domain.AdjustmentTargetPolicy
import com.gfs.iyango.core.domain.CourseReminderPlanner
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.ScheduleDisplaySettings
import com.gfs.iyango.core.model.ScheduleViewMode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** A pager page is a real week projected by the ViewModel from its semester cache. */
@Composable
fun ScheduleWeeks(
    ready: ScheduleUiState.Ready,
    display: ScheduleDisplaySettings,
    hasWallpaper: Boolean,
    bottomInset: Dp,
    active: Boolean,
    onPick: (Int) -> Unit,
    onPagerWeek: (ScheduleContext, Int, Long) -> Unit,
    onGroupClick: (WeekScheduleData, ScheduleDisplayProjection.RenderCell) -> Unit,
    onInteractionLocked: (Boolean) -> Unit,
    onConfirmMove: suspend (ScheduleDropCandidate) -> String?,
    topInset: Dp = 0.dp,
    onPageSettled: (SchedulePagePosition) -> Unit = {},
    autoSilentExemptKeys: Set<String> = emptySet(),
    onQuickCancelOccurrence: (WeekScheduleData, WeekScheduleData.GridBlock) -> Unit = { _, _ -> },
    onQuickToggleAutoSilent: (Course) -> Unit = {},
    onQuickEditCourse: (Course) -> Unit = {},
) {
    key(ready.data.context) {
        ScheduleWeeksSession(
            ready, display, hasWallpaper, bottomInset, active, onPick,
            onPagerWeek, onGroupClick, onInteractionLocked, onConfirmMove, topInset, onPageSettled,
            autoSilentExemptKeys, onQuickCancelOccurrence, onQuickToggleAutoSilent, onQuickEditCourse,
        )
    }
}

@Composable
private fun ScheduleWeeksSession(
    ready: ScheduleUiState.Ready, display: ScheduleDisplaySettings, hasWallpaper: Boolean,
    bottomInset: Dp, active: Boolean, onPick: (Int) -> Unit,
    onPagerWeek: (ScheduleContext, Int, Long) -> Unit,
    onGroupClick: (WeekScheduleData, ScheduleDisplayProjection.RenderCell) -> Unit,
    onInteractionLocked: (Boolean) -> Unit,
    onConfirmMove: suspend (ScheduleDropCandidate) -> String?,
    topInset: Dp,
    onPageSettled: (SchedulePagePosition) -> Unit,
    autoSilentExemptKeys: Set<String>,
    onQuickCancelOccurrence: (WeekScheduleData, WeekScheduleData.GridBlock) -> Unit,
    onQuickToggleAutoSilent: (Course) -> Unit,
    onQuickEditCourse: (Course) -> Unit,
) {
    var source by remember { mutableStateOf<ScheduleDragSource?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var candidate by remember { mutableStateOf<ScheduleDropCandidate?>(null) }
    var chooseBlock by remember { mutableStateOf<Pair<WeekScheduleData, List<WeekScheduleData.GridBlock>>?>(null) }
    var chooseLength by remember { mutableStateOf<Pair<WeekScheduleData, WeekScheduleData.GridBlock>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    if (!active) {
        source = null
        dragging = false
        candidate = null
        chooseBlock = null
        chooseLength = null
        feedback = null
    }
    var floatingHeight by remember { mutableStateOf(0.dp) }
    var floatingBounds by remember { mutableStateOf<Rect?>(null) }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val dockAutoHide = com.gfs.iyango.feature.main.liquidglass.LocalDockAutoHide.current
    val dockSuppressToken = remember { Any() }
    val suppressDock = active && source != null
    DisposableEffect(dockAutoHide, suppressDock) {
        dockAutoHide?.setSuppressed(dockSuppressToken, suppressDock)
        onDispose { dockAutoHide?.setSuppressed(dockSuppressToken, false) }
    }
    SideEffect { onInteractionLocked(dragging || busy) }
    DisposableEffect(Unit) { onDispose { onInteractionLocked(false) } }
    val currentReady by rememberUpdatedState(ready)
    val currentPagerCallback by rememberUpdatedState(onPagerWeek)
    val currentSettledCallback by rememberUpdatedState(onPageSettled)
    val pager = rememberPagerState(initialPage = ready.weeks.indexOfFirst { it.displayedWeek == ready.data.displayedWeek }.coerceAtLeast(0),
        pageCount = { ready.weeks.size })
    // A new external selection is unacknowledged immediately during composition. This
    // closes the interval before its effect runs, when an older fling may settle.
    val request = remember(ready.data.displayedWeek, ready.data.navigationVersion) { PagerSelectionRequest(ready.data.displayedWeek) }
    val currentRequest by rememberUpdatedState(request)
    LaunchedEffect(request) {
        val page = ready.weeks.indexOfFirst { it.displayedWeek == request.week }
        if (page >= 0) {
            if (pager.settledPage != page || pager.isScrollInProgress) pager.scrollToPage(page)
            request.synchronized = true
        }
    }
    LaunchedEffect(pager, active) {
        if (!active) return@LaunchedEffect
        snapshotFlow {
            Triple(pager.settledPage, pager.isScrollInProgress, currentRequest.synchronized) to currentReady.data.navigationVersion
        }.distinctUntilChanged().collect { (position, _) ->
                val (page, scrolling, synchronized) = position
                val data = currentReady
                if (synchronized && !scrolling && page in data.weeks.indices &&
                    currentRequest.week == data.data.displayedWeek && data.weeks[page].displayedWeek == data.data.displayedWeek) {
                    currentSettledCallback(SchedulePagePosition(data.data.context, data.data.displayedWeek, data.data.navigationVersion))
                }
                if (synchronized && !scrolling && page in data.weeks.indices &&
                    currentRequest.week == data.data.displayedWeek && data.weeks[page].displayedWeek != data.data.displayedWeek) {
                    currentPagerCallback(data.data.context, data.weeks[page].displayedWeek, data.data.navigationVersion)
                }
            }
    }
    fun capture(data: WeekScheduleData, block: WeekScheduleData.GridBlock, span: Int): Boolean {
        val expected = captureDragExpected(block, data.effectiveDays) ?: return false
        source = ScheduleDragSource(data.context, expected, span, block = block, displayedWeek = data.displayedWeek)
        candidate = null
        feedback = null
        return true
    }
    fun choose(data: WeekScheduleData, block: WeekScheduleData.GridBlock): Boolean {
        val span = dragSectionSpan(block, data.effectiveDays)
        if (span == null) {
            chooseLength = data to block
            return false
        }
        return capture(data, block, span)
    }
    fun drop(data: WeekScheduleData, slot: ScheduleDropSlot?) {
        dragging = false
        val selected = source ?: return
        if (slot == null || !active || busy) return
        val date = data.weekStart.plusDays(slot.dayOfWeek - 1L)
        val target = data.effectiveDays.firstOrNull { it.date == date }
        if (target == null) { feedback = "目标日期尚未读取，请重试"; return }
        val warning = AdjustmentTargetPolicy.warning(target, selected.expected.baseCourse.timetableId)
        if (warning != null) { feedback = warning; return }
        candidate = ScheduleDropCandidate(selected, slot, target, data.displayedWeek)
    }
    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val visibleDays = if (display.showWeekends) 7 else 5
            val sidebar = showWeekSidebar(maxWidth.value, visibleDays)
            // Dock overlays the full viewport. Its footprint is only a scrolling tail,
            // so the grid remains visible beneath it while the final row can scroll above it.
            Row(Modifier.fillMaxSize()) {
                if (sidebar) Column(Modifier.width(152.dp).fillMaxHeight().padding(start = 16.dp, top = topInset + 8.dp)) {
                    Text("选择周次", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
                    WeekPicker(ready.data, { if (!dragging && !busy) onPick(it) })
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    val pageWidth = maxWidth
                    val pageHeight = maxHeight
                    val overflows = pageWidth.value < ScheduleGridMath.TIME_RAIL_WIDTH_DP + visibleDays * ScheduleGridMath.MIN_DAY_COLUMN_WIDTH_DP
                    val layoutCache = remember(ready.data.timetableId) { ScheduleGridLayoutCache() }
                    val sharedTextMeasurer = rememberTextMeasurer(cacheSize = 128)
                    val weeks = ready.weeks
                    val latestOnGroupClick by rememberUpdatedState(onGroupClick)
                    val onPageDragCancel = remember { { source = null; dragging = false; candidate = null } }
                    CompositionLocalProvider(
                        LocalScheduleGridLayoutCache provides layoutCache,
                        LocalScheduleTextMeasurer provides sharedTextMeasurer,
                    ) {
                        HorizontalPager(state = pager, beyondViewportPageCount = 1,
                            userScrollEnabled = active && !dragging && !busy && !overflows,
                            modifier = Modifier.fillMaxSize().testTag("schedule-week-pager")) { page ->
                            val data = weeks[page]
                            val onPageGroupClick = remember(data) {
                                { cell: ScheduleDisplayProjection.RenderCell ->
                                    if (source == null && !dragging && !busy) latestOnGroupClick(data, cell)
                                }
                            }
                            val onPageDragStart = remember(data, active) {
                                { cell: ScheduleDisplayProjection.RenderCell ->
                                    if (!active || data.readOnly || busy || source != null) false else {
                                        val blocks = cell.blocks.filter { captureDragExpected(it, data.effectiveDays) != null }
                                        if (blocks.size > 1) { chooseBlock = data to blocks; false }
                                        else blocks.singleOrNull()?.let { choose(data, it) }?.also { dragging = it } ?: false
                                    }
                                }
                            }
                            val onPageDrop = remember(data, active) {
                                { slot: ScheduleDropSlot? -> drop(data, slot) }
                            }
                            if (display.viewMode == ScheduleViewMode.TIMELINE) {
                                ScheduleTimelineView(
                                    data = data,
                                    display = display,
                                    hasWallpaper = hasWallpaper,
                                    edgeToEdge = true,
                                    topInset = topInset,
                                    knownWidth = pageWidth,
                                    knownHeight = pageHeight,
                                    bottomInset = bottomInset + if (source != null) floatingHeight else 0.dp,
                                    onGroupClick = onPageGroupClick,
                                )
                            } else {
                                ScheduleWeekGrid(
                                    data, display, hasWallpaper,
                                    edgeToEdge = true,
                                    topInset = topInset,
                                    knownWidth = pageWidth,
                                    knownHeight = pageHeight,
                                    bottomInset = bottomInset + if (source != null) floatingHeight else 0.dp,
                                    blockedRootBounds = floatingBounds.takeIf { source != null },
                                    interactionsEnabled = active && !dragging && !busy && candidate == null,
                                    stagedSpan = source?.span,
                                    onGroupClick = onPageGroupClick,
                                    onDragStart = onPageDragStart,
                                    onDrop = onPageDrop,
                                    onDragCancel = onPageDragCancel,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (source != null) LiquidFloatingBar(
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp).padding(bottom = bottomInset)
                .testTag("schedule-drag-bar")
                .onGloballyPositioned { floatingHeight = with(density) { it.size.height.toDp() }; floatingBounds = it.boundsInRoot() },
        ) {
            val activeSource = source!!
            val courseKey = CourseReminderPlanner.reminderKey(activeSource.course)
            val isExempt = courseKey in autoSilentExemptKeys
            Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                Text(if (dragging) "正在拖动：${activeSource.course.name}" else "已暂存：${activeSource.course.name}（${activeSource.span}节）")
                Text(feedback ?: "可选另一周，再点击目标节次；原安排在确认前保留", style = MaterialTheme.typography.bodySmall)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { dragging = false; candidate = null }, enabled = !busy) { Text("暂存到其他周") }
                    TextButton(onClick = { source = null; dragging = false; candidate = null }, enabled = !busy) { Text("取消调课") }
                    activeSource.block?.let { blk ->
                        TextButton(
                            onClick = {
                                onQuickCancelOccurrence(ready.data.copy(displayedWeek = activeSource.displayedWeek), blk)
                                source = null
                                dragging = false
                                candidate = null
                            },
                            enabled = !busy,
                        ) { Text("本节停课") }
                    }
                    TextButton(
                        onClick = { onQuickToggleAutoSilent(activeSource.course) },
                        enabled = !busy,
                    ) { Text(if (isExempt) "恢复静音" else "免静音") }
                    TextButton(
                        onClick = {
                            val targetCourse = activeSource.course
                            source = null
                            dragging = false
                            candidate = null
                            onQuickEditCourse(targetCourse)
                        },
                        enabled = !busy,
                    ) { Text("编辑") }
                }
            }
        }
    }
    chooseBlock?.let { (data, blocks) ->
        LiquidAlertDialog(onDismissRequest = { chooseBlock = null }, title = { Text("选择要调整的课程实例", style = MaterialTheme.typography.titleLarge) },
            text = { Column { blocks.forEach { block -> TextButton(onClick = {
                chooseBlock = null
                choose(data, block)
            }) { Text("${block.course.name} ${block.occurrence?.date} ${block.course.startSection}–${block.course.endSection}节") } } } },
            confirmButton = {}, dismissButton = { TextButton(onClick = { chooseBlock = null }) { Text("取消") } })
    }
    chooseLength?.let { (data, block) ->
        LiquidAlertDialog(onDismissRequest = { chooseLength = null }, title = { Text("选择目标课程长度", style = MaterialTheme.typography.titleLarge) },
            text = { Column {
                Text("原钟点 ${block.course.startTime}–${block.course.endTime}。拖放将转换为目标日作息节次，请明确选择节数；原安排可恢复。")
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    (1..data.sectionCount).chunked(4).forEach { spans -> Row {
                        spans.forEach { span -> TextButton(onClick = { capture(data, block, span); chooseLength = null }) { Text("${span}节") } }
                    } }
                }
            } }, confirmButton = {}, dismissButton = { TextButton(onClick = { chooseLength = null }) { Text("取消") } })
    }
    candidate?.let { target ->
        val slot = target.slot
        val shape = com.gfs.iyango.core.domain.AdjustmentCourseShape.forSections(target.source.course,
            slot.dayOfWeek, slot.startSection, slot.endSection)
        val conflicts = com.gfs.iyango.core.domain.OccurrenceConflicts.find(shape, target.targetDay.timeSlots,
            target.targetDay.occurrences, target.source.expected.occurrence.occurrenceId)
        LiquidAlertDialog(onDismissRequest = { if (!busy) candidate = null }, title = { Text("确认临时调课", style = MaterialTheme.typography.titleLarge) },
            text = { Text("${target.source.course.name}\n${target.targetDay.date} 第 ${target.displayedWeek} 周 ${slot.startSection}–${slot.endSection}节" +
                if (conflicts.isNotEmpty()) "\n与 ${conflicts.joinToString("、") { it.course.name }} 时间重叠" else "") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                if (!busy) {
                    busy = true
                    scope.launch {
                        try {
                            val error = onConfirmMove(target)
                            candidate = null
                            source = null
                            feedback = null
                            android.widget.Toast.makeText(context, error ?: "已临时调整课程", android.widget.Toast.LENGTH_SHORT).show()
                        } finally { busy = false }
                    }
                }
            }) { Text(if (busy) "保存中" else "确认调课") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { candidate = null }) { Text("返回暂存") } })
    }
}

private class PagerSelectionRequest(val week: Int) {
    var synchronized by mutableStateOf(false)
}
