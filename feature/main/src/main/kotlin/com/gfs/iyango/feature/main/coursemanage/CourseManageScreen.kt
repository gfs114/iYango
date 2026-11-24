@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.gfs.iyango.feature.main.coursemanage

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.compose.NavigationBackHandler
import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassTopBarIconButton
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidAlertDialog
import com.gfs.iyango.core.designsystem.liquidglass.LiquidButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidFilterChip
import com.gfs.iyango.core.designsystem.liquidglass.LiquidModalBottomSheet
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedTextField
import com.gfs.iyango.feature.main.backPreviewLayer
import com.gfs.iyango.feature.main.rememberBackPreviewState

@Composable
fun CourseManageScreen(
    onBack: () -> Unit,
    onAddCourse: () -> Unit = {},
    onEditCourse: (Long) -> Unit = {},
    viewModel: CourseManageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ready = state as? CourseManageUiState.Ready
    val snackbar = remember { SnackbarHostState() }
    var filtersOpen by rememberSaveable(ready?.timetable?.id) { mutableStateOf(false) }
    var openedCourseId by rememberSaveable(ready?.timetable?.id) { mutableStateOf<Long?>(null) }
    val openedCourse = ready?.rows?.find { it.id == openedCourseId }
    val message = ready?.message.orEmpty()
    LaunchedEffect(message) {
        if (message.isNotEmpty()) {
            snackbar.showSnackbar(message)
            viewModel.clearMessage()
        }
    }
    // 选择态返回不导航：手势预览页面回缩，松手还原后再退出自选，取消则仅还原。
    val backPreview = rememberBackPreviewState()
    NavigationBackHandler(
        backPreview.eventState,
        isBackEnabled = ready?.selectionMode == true,
        onBackCompleted = {
            backPreview.complete(restoreBeforeCompletion = true, onCompleted = viewModel::clearSelection)
        },
    )
    GlassScaffold(
        modifier = Modifier.backPreviewLayer(backPreview, cardStyle = true),
        topBar = {
            GlassTopBar(
                title = "课程管理",
                navigationIcon = {
                    GlassTopBarIconButton(onClick = {
                        if (ready?.selectionMode == true) viewModel.clearSelection() else onBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    if (ready != null && ready.totalCount > 0) TextButton(
                        enabled = !ready.busy,
                        onClick = { if (ready.selectionMode) viewModel.clearSelection() else viewModel.startSelection() },
                    ) { Text(if (ready.selectionMode) "完成" else "批量") }
                    GlassTopBarIconButton(onClick = { if (ready?.busy != true) onAddCourse() }) {
                        Icon(Icons.Filled.Add, "新增课程")
                    }
                },
            )
        },
        bottomBar = {
            if (ready?.selectionMode == true) SelectionBar(ready,
                viewModel::selectAllVisible, viewModel::copySelected, viewModel::requestDelete)
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val current = state) {
            CourseManageUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            CourseManageUiState.NoTimetable -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyHint("还没有课表", "先在「我的课表」中创建课表，即可添加课程。")
            }
            is CourseManageUiState.Ready -> CourseGrid(
                current, padding, viewModel::setQuery, { filtersOpen = true }, viewModel::resetFilters,
                onAddCourse, viewModel::selectAllVisible, viewModel::toggleSelected,
                onOpen = { group -> openedCourseId = group.id },
            )
        }
    }
    if (filtersOpen && ready != null) FilterSheet(ready, { filtersOpen = false }, viewModel::setWeek, viewModel::setDay)
    if (openedCourse != null) ArrangementSheet(openedCourse, { openedCourseId = null }) {
        openedCourseId = null
        onEditCourse(it)
    }
    ready?.pendingDeletion?.let { request ->
        LiquidAlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text("删除 ${request.groups.size} 门课程？") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(request.groups.take(3).joinToString("、") { it.name } + if (request.groups.size > 3) " 等" else "",
                    maxLines = 4, overflow = TextOverflow.Ellipsis)
                Text("将删除全部 ${request.ids.size} 个上课时段及其单次调整，无法恢复。")
            } },
            confirmButton = { TextButton(onClick = viewModel::confirmDelete) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = viewModel::dismissDelete) { Text("取消") } },
        )
    }
}

@Composable
private fun CourseGrid(
    state: CourseManageUiState.Ready,
    padding: PaddingValues,
    onQuery: (String) -> Unit,
    onFilters: () -> Unit,
    onReset: () -> Unit,
    onAdd: () -> Unit,
    onSelectAll: () -> Unit,
    onToggle: (Long) -> Unit,
    onOpen: (ManagedCourse) -> Unit,
) {
    val direction = LocalLayoutDirection.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive((164 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp),
            modifier = Modifier.widthIn(max = 1120.dp).fillMaxSize(),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(direction) + 16.dp,
                end = padding.calculateEndPadding(direction) + 16.dp,
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(state.timetable.name, style = MaterialTheme.typography.titleMedium)
                        Text("${state.totalCount} 门课程 ${state.arrangementCount} 个上课时段",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LiquidOutlinedTextField(
                        value = state.query, onValueChange = onQuery, enabled = !state.busy,
                        label = { Text("搜索课程 / 教师 / 教室") }, singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        trailingIcon = { if (state.query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Close, "清空搜索") } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        LiquidOutlinedButton(onClick = onFilters, enabled = !state.busy) {
                            Icon(Icons.Filled.DateRange, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(listOfNotNull(
                                if (state.weekFilter > 0) "第 ${state.weekFilter} 周" else null,
                                COURSE_DAYS.getOrNull(state.dayFilter - 1),
                            ).joinToString(" ").ifEmpty { "周次 / 星期" })
                        }
                        if (state.hasFilters) TextButton(onClick = onReset, enabled = !state.busy) { Text("重置筛选") }
                        if (state.rows.isNotEmpty()) TextButton(onClick = onSelectAll, enabled = !state.busy) { Text("全选当前 ${state.rows.size} 门") }
                    }
                    Text(if (state.hasFilters) "找到 ${state.rows.size} 门课程" else "轻触查看课程详情，长按多选",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.rows.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                EmptyHint(
                    if (state.totalCount == 0) "还没有课程" else "没有匹配的课程",
                    if (state.totalCount == 0) "添加第一门课，安排你的学习时间。" else "试试其他关键词，或清除周次和星期筛选。",
                    action = { LiquidButton(onClick = if (state.totalCount == 0) onAdd else onReset) { Text(if (state.totalCount == 0) "添加课程" else "清除筛选") } },
                )
            }
            items(state.rows, key = { it.id }) { group ->
                CourseCard(group, state.selectionMode, state.selectedIds.containsAll(group.ids), !state.busy,
                    onClick = { if (state.selectionMode) onToggle(group.id) else onOpen(group) },
                    onLongClick = { onToggle(group.id) })
            }
        }
    }
}

@Composable
private fun CourseCard(group: ManagedCourse, selectionMode: Boolean, checked: Boolean, enabled: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit,
) {
    val accent = runCatching { Color(android.graphics.Color.parseColor(group.course.color)) }.getOrDefault(MaterialTheme.colorScheme.primary)
    val shape = RoundedCornerShape(22.dp)
    GlassSurface(
        material = GlassMaterial.CONTENT,
        shape = shape,
        modifier = Modifier.fillMaxWidth()
            .border(if (checked) 2.dp else 1.dp, if (checked) MaterialTheme.colorScheme.primary else accent.copy(alpha = .24f), shape)
            .clip(shape)
            .semantics { if (selectionMode) selected = checked }
            .combinedClickable(enabled = enabled, role = if (selectionMode) Role.Checkbox else Role.Button,
                onClickLabel = if (selectionMode) "选择课程" else "查看课程详情", onLongClickLabel = "选择课程",
                onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(Modifier.fillMaxWidth().background(accent.copy(alpha = if (checked) .16f else .07f))) {
            Column(Modifier.heightIn(min = 132.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(24.dp, 5.dp).background(accent, RoundedCornerShape(4.dp)))
                    Spacer(Modifier.weight(1f))
                    if (selectionMode) {
                        if (checked) Icon(Icons.Filled.CheckCircle, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                        else Box(Modifier.size(22.dp).border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape))
                    }
                }
                Text(group.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(group.teacherSummary(),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SelectionBar(state: CourseManageUiState.Ready, onAll: () -> Unit, onCopy: () -> Unit, onDelete: () -> Unit) {
    val barShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    GlassSurface(
        material = GlassMaterial.OVERLAY,
        shape = barShape,
        allowRefraction = true,
        includeShadow = true,
        modifier = Modifier.fillMaxWidth().pointerInput(Unit) {},
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已选 ${state.selectedCount} 门", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                TextButton(onClick = onAll, enabled = state.canSelectAll && !state.busy) { Text("全选") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LiquidOutlinedButton(onClick = onCopy, enabled = state.selectedCount > 0 && !state.busy, modifier = Modifier.weight(1f)) { Text("复制课程") }
                LiquidButton(onClick = onDelete, enabled = state.selectedCount > 0 && !state.busy, modifier = Modifier.weight(1f),
                    tintColor = MaterialTheme.colorScheme.error) { Text("删除课程") }
            }
        }
    }
}

@Composable
private fun FilterSheet(state: CourseManageUiState.Ready, onClose: () -> Unit, onWeek: (Int) -> Unit, onDay: (Int) -> Unit) {
    LiquidModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("筛选课程", style = MaterialTheme.typography.headlineSmall) }
            item {
                Text("星期", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (0..7).forEach { day -> LiquidFilterChip(selected = state.dayFilter == day,
                        onClick = { onDay(day) }, label = { Text(if (day == 0) "全部" else COURSE_DAYS[day - 1]) }) }
                }
            }
            item {
                Text("教学周", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (0..(state.semester?.totalWeeks ?: 30)).forEach { week -> LiquidFilterChip(selected = state.weekFilter == week,
                        onClick = { onWeek(week) }, label = { Text(if (week == 0) "全部" else "第 $week 周") }) }
                }
            }
            item { LiquidButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("查看 ${state.rows.size} 门课程") } }
        }
    }
}

@Composable
private fun ArrangementSheet(group: ManagedCourse, onClose: () -> Unit, onEdit: (Long) -> Unit) {
    LiquidModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(group.name, style = MaterialTheme.typography.headlineSmall)
            }
            items(group.arrangements, key = { it.id }) { course ->
                val shape = RoundedCornerShape(18.dp)
                GlassSurface(
                    material = GlassMaterial.CONTENT,
                    shape = shape,
                    modifier = Modifier.fillMaxWidth().clip(shape).clickable { onEdit(course.id) },
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(arrangementWeeks(course), style = MaterialTheme.typography.titleSmall)
                        Text(arrangementTime(course), style = MaterialTheme.typography.bodyMedium)
                        Text(arrangementPeople(course), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!course.enabled) Text("已停用", color = MaterialTheme.colorScheme.error)
                        Text("编辑此时段", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(title: String, description: String, action: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Filled.DateRange, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action()
    }
}
