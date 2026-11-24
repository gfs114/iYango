package com.gfs.iyango.feature.main.coursedetail

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import com.gfs.iyango.core.designsystem.component.GlassOverlayHeader
import com.gfs.iyango.core.designsystem.liquidglass.LiquidAlertDialog
import com.gfs.iyango.core.designsystem.liquidglass.LiquidModalBottomSheet
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled
import com.gfs.iyango.core.designsystem.component.StatusBadge
import com.gfs.iyango.core.designsystem.theme.CourseSwatch
import com.gfs.iyango.feature.main.schedule.ScheduleDisplayProjection
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import com.gfs.iyango.core.domain.CourseOccurrence
import com.gfs.iyango.core.domain.CourseRules
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.DayLabels
import com.gfs.iyango.core.model.TimeSlot
import com.gfs.iyango.core.model.WeekType
import com.gfs.iyango.feature.main.isDarkTheme
import com.gfs.iyango.feature.main.LocalMainTabActive
import com.gfs.iyango.feature.main.schedule.CourseDeleteScope

/**
 * 课程详情弹层（ui-baseline §4〔参 CourseDetailDialog〕）。
 *
 * 居中 Dialog：标题 = 课名、副标题 = 教师；内容滚动（maxHeight 320）：
 * 信息行×N
 * （18sp 图标 + 12sp Secondary 标签 + 14sp 值）。override 实例显示"单次调整"标记。
 * 按钮行：调整课程 / 编辑 / 复制 / 删除（error 字）；点击弹窗外或系统返回关闭。
 * 删除走 ActionSheet 三选 + AlertDialog 确认。
 *
 * 冲突翻页：同一格子的冲突组以 [courses] 传入（网格只画第一门），组内 >1 时顶部出现
 * 翻页行，可在多门课之间切换详情。
 *
 * @param courses 冲突组课程列表（至少一条）。
 * @param timeSlots 作息时间表（节次 → 钟点）。
 * @param displayedWeek 当前展示周（"仅取消本周这次"确认文案使用）。
 * @param onEdit 编辑回调（P2 编辑页未就绪时由调用方提示建设中）。
 * @param onCopy 复制回调（同上）。
 * @param onDelete 删除回调；范围由弹层内 ActionSheet 收集。
 * @param onAdjust 单次调整回调（进入 ScheduleAdjustmentDialog）。
 * @param onDismiss 关闭回调。
 * @param occurrenceEntries 与courses逐项对应；动作直接传回所选实例，不能靠Course相等反查锚点。
 * @param onRestoreOccurrence 临时整天只读实例的恢复入口，调用方持有打开详情时的owner/date。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailDialog(
    courses: List<Course>,
    timeSlots: List<TimeSlot>,
    displayedWeek: Int,
    paletteLight: List<String> = emptyList(),
    paletteDark: List<String> = emptyList(),
    onEdit: (Course) -> Unit,
    onCopy: (Course) -> Unit,
    onDelete: (Course, CourseDeleteScope) -> Unit,
    onAdjust: (Course) -> Unit,
    onDismiss: () -> Unit,
    readOnly: Boolean = false,
    occurrenceEntries: List<com.gfs.iyango.core.domain.CourseOccurrence?> = emptyList(),
    onAdjustEntry: ((Course, com.gfs.iyango.core.domain.CourseOccurrence?) -> Unit)? = null,
    onDeleteEntry: ((Course, CourseDeleteScope, com.gfs.iyango.core.domain.CourseOccurrence?) -> Unit)? = null,
    onRestoreOccurrence: ((com.gfs.iyango.core.domain.CourseOccurrence) -> Unit)? = null,
    source: CourseDetailSource? = null,
    allCourses: List<Course> = emptyList(),
    courseColorOverrides: Map<String, String> = emptyMap(),
    onSelectCourseColor: ((courseKey: String, hex: String?) -> Unit)? = null,
    useBottomSheet: Boolean = false,
) {
    val active = LocalMainTabActive.current
    val enabled = LocalMotionEnabled.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var released by remember { mutableStateOf(false) }
    var exitSnapshot by remember { mutableStateOf<Pair<List<Course>, List<CourseOccurrence?>>?>(null) }
    val closing = exitSnapshot != null
    val visibleCourses = exitSnapshot?.first ?: courses
    val visibleEntries = exitSnapshot?.second ?: occurrenceEntries
    val latestDismiss by rememberUpdatedState(onDismiss)
    val release = remember { {
        if (!released) {
            released = true
            latestDismiss()
        }
    } }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) release() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (!active) {
        LaunchedEffect(Unit) { release() }
        return
    }
    if (released || visibleCourses.isEmpty()) return
    fun handoff(action: () -> Unit) {
        if (exitSnapshot != null || released) return
        released = true
        action()
    }
    val pager = rememberPagerState(pageCount = { visibleCourses.size })
    val coroutineScope = rememberCoroutineScope()
    var buttonPage by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(enabled) {
        if (!enabled) buttonPage?.let { pager.scrollToPage(it.coerceIn(visibleCourses.indices)) }
    }
    var deleteSelection by remember(courses, occurrenceEntries) { mutableStateOf<DetailDeleteSelection?>(null) }
    var confirmSelection by remember(courses, occurrenceEntries) { mutableStateOf<DetailDeleteSelection?>(null) }

    val pageSource = source?.takeIf {
        pager.currentPage == 0 && it.course == visibleCourses.first() &&
            it.occurrenceId == visibleEntries.firstOrNull()?.occurrenceId
    }
    @Composable
    fun DetailContent(titleModifier: Modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 冲突翻页行（组内 >1 时出现）。
            if (visibleCourses.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    fun goToPage(page: Int) {
                        if (exitSnapshot != null || released) return
                        buttonPage = page
                        coroutineScope.launch {
                            try {
                                if (enabled) pager.animateScrollToPage(page) else pager.scrollToPage(page)
                            } finally {
                                if (buttonPage == page) buttonPage = null
                            }
                        }
                    }
                    IconButton(onClick = { goToPage((pager.currentPage - 1).mod(visibleCourses.size)) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "上一门",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "第 ${pager.currentPage + 1} / ${visibleCourses.size} 门",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    IconButton(onClick = { goToPage((pager.currentPage + 1) % visibleCourses.size) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "下一门",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (visibleCourses.size > 1) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    repeat(visibleCourses.size) { page ->
                        Text(if (page == pager.currentPage) "●" else "○", Modifier.padding(horizontal = 3.dp),
                            color = MaterialTheme.colorScheme.primary, fontSize = 10.sp)
                    }
                }
            }
            val pageKeys = remember(visibleCourses, visibleEntries) {
                val duplicates = mutableMapOf<String, Int>()
                visibleCourses.mapIndexed { index, course ->
                    val occurrence = visibleEntries.getOrNull(index)
                    val identity = if (occurrence != null) {
                        "occurrence:${occurrence.occurrenceId}:${occurrence.anchorDate}"
                    } else {
                        val kind = if (visibleEntries.isNotEmpty()) "ghost" else "definition"
                        "$kind:${course.timetableId}:${course.id}:${course.dayOfWeek}:${course.startSection}:${course.endSection}:${course.date}"
                    }
                    val duplicateIndex = duplicates.getOrDefault(identity, 0)
                    duplicates[identity] = duplicateIndex + 1
                    "$identity:$duplicateIndex"
                }
            }
            HorizontalPager(
                state = pager,
                userScrollEnabled = !closing,
                key = { pageKeys[it] },
                modifier = Modifier.fillMaxWidth().testTag("courseDetailPager"),
                verticalAlignment = Alignment.Top,
            ) { page ->
                val course = visibleCourses[page]
                val occurrence = visibleEntries.getOrNull(page)
                val ghost = visibleEntries.isNotEmpty() && occurrence == null
                CourseDetailPage(
                    course = course,
                    browseReadOnly = readOnly,
                    occurrence = occurrence,
                    ghost = ghost,
                    timeSlots = timeSlots,
                    paletteLight = paletteLight,
                    paletteDark = paletteDark,
                    titleModifier = if (page == pager.currentPage) titleModifier else Modifier,
                    onEdit = { if (exitSnapshot == null && !released) { release(); onEdit(it) } },
                    onCopy = { selected -> handoff { onCopy(selected) } },
                    onDelete = { if (exitSnapshot == null && !released) deleteSelection = DetailDeleteSelection(course, occurrence, ghost) },
                    onAdjust = { handoff { onAdjustEntry?.invoke(course, occurrence) ?: onAdjust(course) } },
                    onRestoreOccurrence = onRestoreOccurrence?.let { restore ->
                        { entry -> if (exitSnapshot == null && !released) { restore(entry); release() } }
                    },
                    allCourses = allCourses,
                    courseColorOverrides = courseColorOverrides,
                    onSelectCourseColor = onSelectCourseColor,
                    useBottomSheet = useBottomSheet,
                )
            }
        }
    }

    if (useBottomSheet) {
        LiquidModalBottomSheet(
            onDismissRequest = release,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        ) {
            DetailContent(titleModifier = Modifier)
        }
    } else {
        CourseDetailMotionWindow(
            source = pageSource,
            closing = closing,
            onRequestClose = {
                if (exitSnapshot == null && !released) {
                    exitSnapshot = visibleCourses.toList() to visibleEntries.toList()
                    if (!enabled) release()
                }
            },
            onReleased = release,
        ) { titleModifier ->
            DetailContent(titleModifier = titleModifier)
        }
    }

    // 删除范围 ActionSheet（ui-baseline §10.3：范围选择 = 底部半模态）。
    deleteSelection?.let { selection ->
        LiquidModalBottomSheet(
            onDismissRequest = { deleteSelection = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            DeleteScopeSheet(
                courseName = selection.course.name,
                allowOccurrenceCancellation = !selection.ghost,
                onSelect = { scope ->
                    deleteSelection = null
                    confirmSelection = selection.copy(scope = scope)
                },
                onDismiss = { deleteSelection = null },
            )
        }
    }
    confirmSelection?.let { selection ->
        val scope = selection.scope ?: return@let
        LiquidAlertDialog(
            onDismissRequest = { confirmSelection = null },
            title = { Text("删除课程", style = MaterialTheme.typography.titleLarge) },
            text = { Text(deleteConfirmMessage(selection.course, scope, displayedWeek)) },
            confirmButton = {
                TextButton(onClick = {
                    if (confirmSelection != selection) return@TextButton
                    confirmSelection = null
                    handoff {
                        onDeleteEntry?.invoke(selection.course, scope, selection.occurrence)
                            ?: onDelete(selection.course, scope)
                    }
                }, colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error)) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmSelection = null }) { Text("取消") } },
        )
    }
}

/** One page owns its concrete definition and occurrence; callbacks never resolve by course equality. */
@Composable
private fun CourseDetailPage(
    titleModifier: Modifier,
    browseReadOnly: Boolean,
    course: Course,
    occurrence: CourseOccurrence?,
    ghost: Boolean,
    timeSlots: List<TimeSlot>,
    paletteLight: List<String>,
    paletteDark: List<String>,
    onEdit: (Course) -> Unit,
    onCopy: (Course) -> Unit,
    onDelete: () -> Unit,
    onAdjust: () -> Unit,
    onRestoreOccurrence: ((CourseOccurrence) -> Unit)?,
    allCourses: List<Course> = emptyList(),
    courseColorOverrides: Map<String, String> = emptyMap(),
    onSelectCourseColor: ((courseKey: String, hex: String?) -> Unit)? = null,
    useBottomSheet: Boolean = false,
) {
    val readOnly = browseReadOnly || occurrence?.isTemporary == true
    val canAdjust = !readOnly && !ghost && course.id > 0 && course.overrideOfId == 0L
    val courseKey = ScheduleDisplayProjection.courseColorKey(course)
    val effectiveHex = courseColorOverrides[courseKey]?.takeIf { it.isNotBlank() } ?: course.color
    val courseAccent = CourseSwatch.forCourse(effectiveHex, course.id, isDarkTheme(), paletteLight, paletteDark).fill
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = course.name,
            modifier = Modifier.fillMaxWidth().then(titleModifier).semantics { heading() },
            style = detailTitleStyle(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (course.overrideOfId > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            StatusBadge(text = "单次调整")
        }

        Spacer(modifier = Modifier.height(4.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = if (useBottomSheet) 520.dp else 320.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (browseReadOnly) Text("学期浏览 只读", fontSize = 12.sp)
            if (readOnly && !browseReadOnly) {
                Text("临时整天课表中的课程，请恢复该日后调整", fontSize = 12.sp)
                TextButton(enabled = onRestoreOccurrence != null, onClick = {
                    if (occurrence != null) onRestoreOccurrence?.invoke(occurrence)
                }) { Text("恢复该日课表") }
            }
            InfoRow(
                icon = Icons.Filled.DateRange,
                accent = courseAccent,
                label = "周次",
                value = weekLabel(course, occurrence),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            InfoRow(
                icon = Icons.Filled.PlayArrow,
                accent = courseAccent,
                label = "节次与时间",
                value = sectionTimeLabel(course, timeSlots, occurrence),
            )
            if (course.teacher.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                InfoRow(
                    icon = Icons.Filled.Person,
                    accent = courseAccent,
                    label = "教师",
                    value = course.teacher,
                )
            }
            if (course.classroom.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                InfoRow(
                    icon = Icons.Filled.Place,
                    accent = courseAccent,
                    label = "教室",
                    value = course.classroom,
                )
            }
            if (course.remark.isNotBlank()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                InfoRow(
                    icon = Icons.Filled.Info,
                    accent = courseAccent,
                    label = "备注",
                    value = course.remark,
                )
            }
            if (course.abbreviation.isNotEmpty() || course.credit > 0.0 || course.isOutdoor) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                InfoRow(
                    icon = Icons.Filled.Star,
                    accent = courseAccent,
                    label = "课程资料",
                    value = courseMaterialLabel(course),
                )
            }
            if (onSelectCourseColor != null && !browseReadOnly) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(courseAccent)
                                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        )
                        Text(
                            text = "课程颜色",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    CourseColorPalettePicker(
                        course = course,
                        currentHex = effectiveHex,
                        allCourses = allCourses,
                        courseColorOverrides = courseColorOverrides,
                        onSelectHex = { hex -> onSelectCourseColor(courseKey, hex) },
                    )
                }
            }
        }

        if (!browseReadOnly) Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            val actionModifier = if (canAdjust) Modifier.weight(1f) else Modifier
            val actionPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
            if (canAdjust) {
                TextButton(
                    onClick = onAdjust,
                    modifier = Modifier.weight(1.5f),
                    contentPadding = actionPadding,
                ) { Text("调整课程", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            TextButton(enabled = !readOnly, modifier = actionModifier, contentPadding = actionPadding, onClick = {
                onEdit(course)
            }) { Text("编辑", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            TextButton(onClick = { onCopy(course) }, modifier = actionModifier, contentPadding = actionPadding) {
                Text("复制", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(
                onClick = onDelete,
                enabled = !readOnly,
                modifier = actionModifier,
                contentPadding = actionPadding,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("删除", maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

private data class DetailDeleteSelection(
    val course: Course,
    val occurrence: com.gfs.iyango.core.domain.CourseOccurrence?,
    val ghost: Boolean,
    val scope: CourseDeleteScope? = null,
)

/** 删除范围三选（ui-baseline §4：仅取消本周这次 / 删除这条安排 / 删除整门课）。 */
@Composable
private fun DeleteScopeSheet(
    courseName: String,
    allowOccurrenceCancellation: Boolean,
    onSelect: (CourseDeleteScope) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        GlassOverlayHeader(title = courseName, contentPadding = PaddingValues(0.dp))
        Spacer(modifier = Modifier.height(4.dp))
        listOf(
            CourseDeleteScope.CANCEL_THIS_WEEK to "仅取消本周这次安排",
            CourseDeleteScope.DELETE_ARRANGEMENT to "删除这条安排的所有周次",
            CourseDeleteScope.DELETE_WHOLE_COURSE to "删除整门课的所有安排",
        ).filter { (scope, _) -> allowOccurrenceCancellation || scope != CourseDeleteScope.CANCEL_THIS_WEEK }
            .forEach { (scope, label) ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onSelect(scope) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text("取消")
        }
    }
}

/** 删除确认文案（对齐参考 confirmDeleteKind）。 */
private fun deleteConfirmMessage(
    course: Course,
    scope: CourseDeleteScope,
    displayedWeek: Int,
): String = when (scope) {
    CourseDeleteScope.CANCEL_THIS_WEEK ->
        "仅取消第 $displayedWeek 周的这次安排，其他周保留。"
    CourseDeleteScope.DELETE_ARRANGEMENT ->
        "确定删除「${course.name}」的这条安排吗？"
    CourseDeleteScope.DELETE_WHOLE_COURSE ->
        "确定删除「${course.name}」的所有安排吗？"
}

// ---------------- 信息行与文案 ----------------

/** 信息行：18dp 图标（课程 accent 色）+ 12sp Secondary 标签 + 14sp 值。 */
@Composable
private fun InfoRow(
    icon: ImageVector,
    accent: Color,
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * 周次文案："1-16 周 单周 每 3 周第 1 周"；指定周如实列出；单次日期课显示日期。
 */
private fun weekLabel(course: Course, occurrence: CourseOccurrence?): String {
    if (course.timeMode == 2) {
        return occurrence?.date?.toString() ?: course.date
    }
    if (course.selectedWeeks.isNotEmpty()) {
        return "第 " + course.selectedWeeks.joinToString("、") + " 周"
    }
    val base = if (course.startWeek == course.endWeek) {
        "第 ${course.startWeek} 周"
    } else {
        "第 ${course.startWeek} - ${course.endWeek} 周"
    }
    val builder = StringBuilder(base)
    when (course.weekType) {
        WeekType.ODD -> builder.append(" 单周")
        WeekType.EVEN -> builder.append(" 双周")
        WeekType.EVERY -> Unit
    }
    if (course.cycleWeek > 0) {
        builder
            .append(" 每 ")
            .append(course.cycleLength)
            .append(" 周第 ")
            .append(course.cycleWeek)
            .append(" 周")
    }
    return builder.toString()
}

/**
 * 节次与时间文案：节次区间 + 星期前缀 + 起止钟点；
 * 自定义钟点 / 指定日期 / 时间待定如实展示，不静默套默认作息。
 */
private fun sectionTimeLabel(course: Course, timeSlots: List<TimeSlot>, occurrence: com.gfs.iyango.core.domain.CourseOccurrence?): String {
    val sectionPart = when {
        course.timeMode == 2 -> occurrence?.date?.toString() ?: course.date
        course.timeMode == 1 -> "自定义时间"
        occurrence?.isMoved != true && course.sourceStartSection > 0 ->
            "第 ${course.sourceStartSection} - ${course.sourceEndSection} 节"
        course.startSection == course.endSection -> "第 ${course.startSection} 节"
        else -> "第 ${course.startSection} - ${course.endSection} 节"
    }
    val occurrenceStart = occurrence?.startMinute
    val occurrenceEnd = occurrence?.endMinute
    val timePart = when {
        occurrenceStart != null && occurrenceEnd != null -> {
            fun clock(minute: Int) = "%02d:%02d".format(java.util.Locale.ROOT, minute / 60, minute % 60)
            DayLabels.label(occurrence!!.date.dayOfWeek.value) + " " + clock(occurrenceStart) + " - " + clock(occurrenceEnd)
        }
        occurrence != null -> "时间待定"
        else -> {
            val range = CourseRules.timeRange(course, timeSlots)
            if (range.startTime.isEmpty() || range.endTime.isEmpty()) "时间待定" else {
                val dayPrefix = if (course.timeMode == 2) "" else DayLabels.label(course.dayOfWeek) + " "
                dayPrefix + range.startTime + " - " + range.endTime
            }
        }
    }
    return "$sectionPart　$timePart"
}

/** 课程资料文案：简称 / 学分 / 户外标记。 */
private fun courseMaterialLabel(course: Course): String {
    val parts = mutableListOf<String>()
    if (course.abbreviation.isNotEmpty()) {
        parts += "简称：${course.abbreviation}"
    }
    parts += "学分：${formatCredit(course.credit)}"
    if (course.isOutdoor) {
        parts += "户外课程"
    }
    return parts.joinToString("　")
}

/** 学分展示：整数不带小数点，其余保留一位以内。 */
private fun formatCredit(credit: Double): String =
    if (credit == credit.toLong().toDouble()) {
        credit.toLong().toString()
    } else {
        String.format(java.util.Locale.CHINA, "%.1f", credit)
    }
