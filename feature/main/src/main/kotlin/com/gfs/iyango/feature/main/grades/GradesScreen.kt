@file:OptIn(ExperimentalMaterial3Api::class)

package com.gfs.iyango.feature.main.grades

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.compose.NavigationBackHandler
import com.gfs.iyango.core.designsystem.component.EmptyState
import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassTopBarTextButton
import com.gfs.iyango.core.designsystem.component.RefreshNoticeCapsule
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidAlertDialog
import com.gfs.iyango.core.designsystem.liquidglass.LiquidButton
import com.gfs.iyango.core.designsystem.motion.motionReveal
import com.gfs.iyango.core.designsystem.theme.LocalBrand
import com.gfs.iyango.core.domain.GradeSorter
import com.gfs.iyango.core.domain.GradeStatisticsCalculator
import com.gfs.iyango.core.model.GradeRecord
import com.gfs.iyango.core.model.GradeSort
import com.gfs.iyango.core.model.GradeStatistics
import com.gfs.iyango.feature.main.LocalMainTabActive
import com.gfs.iyango.feature.main.backPreviewLayer
import com.gfs.iyango.feature.main.rememberBackPreviewState

/** 成绩页浮层互斥：同一时刻至多一个面板/菜单（方案 §9.1）。 */
internal enum class GradesOverlay { TERM_PICKER, STATISTICS_INFO, SORT_MENU, ATTRIBUTE_PICKER }

/**
 * 成绩页（F03 + E03）。
 *
 * 学期/排序筛选即时更新列表与统计；下拉刷新更新当前范围的教务成绩。
 * 页面先展示本机缓存快照，断网或会话过期仍可查看，并明确标注缓存时间与账号。
 * 自选模式下计算结果固定在底部计算栏（P1），统计说明与学期选择以浮层呈现（P2/P3）。
 */
@Composable
fun GradesScreen(
    bottomPadding: Dp = 96.dp,
    onOpenSchool: () -> Unit = {},
    onOpenCreditProgress: () -> Unit = {},
    viewModel: GradesViewModel = hiltViewModel(),
) {
    val tabActive = LocalMainTabActive.current
    val state by key(tabActive) { viewModel.state.collectAsStateWithLifecycle() }
    var detail by remember(tabActive, state.accountKey, state.selectionRevision) { mutableStateOf<GradeRecord?>(null) }

    GradesPageLayout(
        state = state,
        bottomPadding = bottomPadding,
        onOpenSchool = onOpenSchool,
        onOpenCreditProgress = onOpenCreditProgress,
        onSelectRange = viewModel::selectDraftRange,
        onSelectSort = viewModel::setSort,
        onRefresh = viewModel::refresh,
        onDismissError = viewModel::dismissError,
        onClearMessage = viewModel::clearMessage,
        onMarkRead = viewModel::markRead,
        onIncludeOptionalChange = viewModel::setIncludeOptional,
        onSelect = { detail = it },
        onBeginSelection = viewModel::beginSelection,
        onToggleSelection = viewModel::toggleSelection,
        onSelectAll = viewModel::selectAll,
        onClearSelection = viewModel::clearSelection,
        onExitSelection = viewModel::exitSelection,
        onToggleAttributeSelection = viewModel::toggleAttributeSelection,
    )

    if (tabActive) detail?.let { record ->
        GradeDetailDialog(record = record, onDismiss = { detail = null })
    }
}

/**
 * 页面装配：共享列表状态、公共统计派生值、固定计算栏插槽与浮层互斥。
 * 参数签名保持稳定，供直接驱动的 UI 测试复用。
 */
@Composable
internal fun GradesPageLayout(
    state: GradesUiState,
    bottomPadding: Dp,
    onOpenSchool: () -> Unit = {},
    onOpenCreditProgress: () -> Unit = {},
    onSelectRange: (String) -> Unit = {},
    onSelectSort: (GradeSort) -> Unit = {},
    onRefresh: () -> Unit = {},
    onDismissError: () -> Unit = {},
    onMarkRead: () -> Unit = {},
    onIncludeOptionalChange: (Boolean) -> Unit = {},
    onSelect: (GradeRecord) -> Unit = {},
    onBeginSelection: (GradeSelectionContext) -> Unit = {},
    onToggleSelection: (Long, GradeSelectionContext) -> Unit = { _, _ -> },
    onSelectAll: (GradeSelectionContext) -> Unit = {},
    onClearSelection: (GradeSelectionContext) -> Unit = {},
    onExitSelection: (GradeSelectionContext) -> Unit = {},
    onClearMessage: () -> Unit = {},
    onToggleAttributeSelection: (String, GradeSelectionContext) -> Unit = { _, _ -> },
) {
    val tabActive = LocalMainTabActive.current
    var overlay by remember(tabActive, state.accountKey) { mutableStateOf<GradesOverlay?>(null) }
    var returnToAttributes by remember(tabActive, state.accountKey) { mutableStateOf(false) }
    val dismissTermPicker: () -> Unit = {
        overlay = if (returnToAttributes && state.selectionMode) GradesOverlay.ATTRIBUTE_PICKER else null
        returnToAttributes = false
    }
    val listState = rememberLazyListState()

    LaunchedEffect(tabActive, state.message) {
        if (!tabActive && state.message.isNotEmpty()) onClearMessage()
    }

    LaunchedEffect(state.confirmedRange, state.selectionRevision, state.selectionMode) {
        if (overlay != GradesOverlay.TERM_PICKER &&
            !(overlay == GradesOverlay.ATTRIBUTE_PICKER && state.selectionMode)) overlay = null
    }
    // 返回优先级（方案 §9.2）：先浮层，再退出自选；一次返回不做两件事。
    // 选择态返回不导航：手势预览页面回缩，松手还原后再退出自选，取消则仅还原。
    val backPreview = rememberBackPreviewState()
    NavigationBackHandler(
        backPreview.eventState,
        isBackEnabled = tabActive && overlay == null && state.selectionMode,
        onBackCompleted = {
            backPreview.complete(restoreBeforeCompletion = true) { onExitSelection(state.selectionContext) }
        },
    )

    val sorted = remember(state.records, state.sort) {
        GradeSorter.sort(state.records, state.sort)
    }
    val statistics = remember(state.records, state.includeOptional, state.selectionMode, state.selectedRecordIds) {
        GradeStatisticsCalculator.calculate(state.statisticsRecords, state.includeOptional)
    }
    val selectableRecords = remember(state.records) { state.records.filter { it.id > 0 } }
    val allSelected = selectableRecords.all { it.id in state.selectedRecordIds }
    val selectionContext = state.selectionContext
    val resultPhase = when {
        !state.hasAccount -> GradeResultPhase.NoAccount
        state.records.isNotEmpty() -> GradeResultPhase.Records
        state.querying -> GradeResultPhase.Loading
        state.error.isNotEmpty() || state.needsLogin -> GradeResultPhase.Error
        else -> GradeResultPhase.Empty
    }
    val resultModifier = Modifier.motionReveal(Triple(selectionContext, state.sort, resultPhase))

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 紧凑判定使用页面实际可用宽度与字体缩放（方案 §8.2）。
        val compactBase = (maxWidth - 32.dp) < 360.dp || LocalDensity.current.fontScale >= 1.5f
            GlassScaffold(
                modifier = Modifier.fillMaxSize().backPreviewLayer(backPreview),
            topBar = {
                GradesHeader(
                    state = state,
                    onOpenSchool = onOpenSchool,
                    onOpenCreditProgress = onOpenCreditProgress,
                )
            },
            bottomBar = {
                // 空学期也保留自选操作栏，便于继续切换学期或退出。
                if (tabActive && state.hasAccount && state.selectionMode) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = bottomPadding + 8.dp),
                    ) {
                        GradeSelectionBar(
                            statistics = statistics,
                            selectedCount = state.statisticsRecords.size,
                            allSelected = allSelected,
                            includeOptional = state.includeOptional,
                            onIncludeOptionalChange = onIncludeOptionalChange,
                            onSelectAll = { onSelectAll(selectionContext) },
                            onClearSelection = { onClearSelection(selectionContext) },
                            onExitSelection = { onExitSelection(selectionContext) },
                            onOpenInfo = { overlay = GradesOverlay.STATISTICS_INFO },
                            onOpenAttributes = { overlay = GradesOverlay.ATTRIBUTE_PICKER },
                        )
                    }
                }
            },
            containerColor = Color.Transparent,
            // Root Dock padding includes navigation bars; the header owns the status-bar inset.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            overlay = { innerPadding ->
                RefreshNoticeCapsule(
                    message = if (tabActive) state.message else null,
                    onDismiss = onClearMessage,
                    topPadding = innerPadding.calculateTopPadding(),
                )
            },
        ) { innerPadding ->
            Box(Modifier.fillMaxSize()) {
                if (!state.hasAccount) {
                    Box(Modifier.fillMaxSize().padding(innerPadding).then(resultModifier)) {
                        NoAccountState(bottomPadding = bottomPadding, onOpenSchool = onOpenSchool)
                    }
                } else {
                    GradesList(
                        resultModifier = resultModifier,
                        state = state,
                        listState = listState,
                        compactBase = compactBase,
                        statistics = statistics,
                        sorted = sorted,
                        topPadding = innerPadding.calculateTopPadding(),
                        bottomPadding = bottomPadding,
                        listBottomPadding = if (state.selectionMode) {
                            maxOf(bottomPadding, innerPadding.calculateBottomPadding()) + 16.dp
                        } else {
                            bottomPadding + 16.dp
                        },
                        overlay = overlay,
                        onOverlayChange = { overlay = it; returnToAttributes = false },
                        onOpenSchool = onOpenSchool,
                        onSelectRange = onSelectRange,
                        onSelectSort = onSelectSort,
                        onRefresh = onRefresh,
                        onDismissError = onDismissError,
                        onMarkRead = onMarkRead,
                        onIncludeOptionalChange = onIncludeOptionalChange,
                        onSelect = onSelect,
                        onBeginSelection = onBeginSelection,
                        onToggleSelection = onToggleSelection,
                        onSelectAll = onSelectAll,
                        onClearSelection = onClearSelection,
                        onExitSelection = onExitSelection,
                    )
                }
            }
            when (overlay) {
                GradesOverlay.TERM_PICKER -> TermPickerSheet(
                    state = state,
                    onSelectRange = { value ->
                        onSelectRange(value)
                        dismissTermPicker()
                    },
                    onDismiss = dismissTermPicker,
                    onRangeChange = onSelectRange,
                    onQuery = {
                        onRefresh()
                        overlay = null
                        returnToAttributes = false
                    },
                )
                GradesOverlay.STATISTICS_INFO -> StatisticsInfoSheet(
                    records = state.statisticsRecords,
                    includeOptional = state.includeOptional,
                    onDismiss = { overlay = null },
                )
                GradesOverlay.SORT_MENU -> Unit
                GradesOverlay.ATTRIBUTE_PICKER -> GradeAttributePickerSheet(
                    state = state,
                    onToggleAttribute = { onToggleAttributeSelection(it, selectionContext) },
                    onDismiss = { overlay = null },
                    onSelectRange = onSelectRange,
                    onOpenTermPicker = { returnToAttributes = true; overlay = GradesOverlay.TERM_PICKER },
                )
                null -> Unit
            }
        }
    }
}

@Composable
internal fun GradesHeader(
    state: GradesUiState,
    onOpenSchool: () -> Unit,
    onOpenCreditProgress: () -> Unit = {},
) {
    GlassTopBar(
        title = "成绩",
        actions = {
            GlassTopBarTextButton(text = "学分进度", onClick = onOpenCreditProgress)
            if (state.hasAccount && !state.loggedIn) {
                GlassTopBarTextButton(text = "重新登录", onClick = onOpenSchool)
            }
        },
    )
}

@Composable
private fun NoAccountState(bottomPadding: Dp, onOpenSchool: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = bottomPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyState(
            icon = Icons.Filled.Star,
            title = "尚未登录教务",
            hint = "登录后可查询成绩，并在断网或会话过期时查看本机缓存",
        )
        LiquidButton(onClick = onOpenSchool) {
            Text("去登录")
        }
    }
}

private enum class GradeResultPhase { NoAccount, Loading, Error, Empty, Records }

/** One scrollable body keeps filters, statistics and records behind the fixed header. */
@Composable
private fun GradesList(
    resultModifier: Modifier,
    state: GradesUiState,
    listState: LazyListState,
    compactBase: Boolean,
    statistics: GradeStatistics,
    sorted: List<GradeRecord>,
    topPadding: Dp,
    bottomPadding: Dp,
    listBottomPadding: Dp,
    overlay: GradesOverlay?,
    onOverlayChange: (GradesOverlay?) -> Unit,
    onOpenSchool: () -> Unit,
    onSelectRange: (String) -> Unit,
    onSelectSort: (GradeSort) -> Unit,
    onRefresh: () -> Unit,
    onDismissError: () -> Unit,
    onMarkRead: () -> Unit,
    onIncludeOptionalChange: (Boolean) -> Unit,
    onSelect: (GradeRecord) -> Unit,
    onBeginSelection: (GradeSelectionContext) -> Unit,
    onToggleSelection: (Long, GradeSelectionContext) -> Unit,
    onSelectAll: (GradeSelectionContext) -> Unit,
    onClearSelection: (GradeSelectionContext) -> Unit,
    onExitSelection: (GradeSelectionContext) -> Unit,
) {
    val pullState = rememberPullToRefreshState()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val visible = LocalMainTabActive.current && lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val selectionContext = state.selectionContext
    val rangeLabel = remember(state.terms, state.confirmedRange) {
        termLabel(state.terms, state.confirmedRange)
    }

    PullToRefreshBox(
        isRefreshing = state.querying,
        onRefresh = onRefresh,
        state = pullState,
        modifier = Modifier.fillMaxSize(),
        indicator = {
            if (visible) PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = state.querying,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = topPadding),
            )
        },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .testTag("grades-list"),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = topPadding + 12.dp,
                bottom = listBottomPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "filters") {
                GradesFilterRow(
                    state = state,
                    onOpenTermPicker = { onOverlayChange(GradesOverlay.TERM_PICKER) },
                )
            }
            if (state.needsLogin) {
                item(key = "needs-login") {
                    NoticeBanner(
                        modifier = resultModifier,
                        text = "登录已过期，当前显示 $rangeLabel 的本机缓存",
                        actionLabel = "去登录",
                        onAction = onOpenSchool,
                    )
                }
            }
            if (state.error.isNotEmpty() && !state.needsLogin) {
                item(key = "error") {
                    NoticeBanner(
                        modifier = resultModifier,
                        text = state.error,
                        actionLabel = "知道了",
                        onAction = onDismissError,
                    )
                }
            }
            if (state.unreadCount > 0) {
                item(key = "unread") {
                    UnreadBanner(count = state.unreadCount, onMarkRead = onMarkRead)
                }
            }
            if (state.selectionNotice.isNotEmpty()) {
                item(key = "selection-notice") {
                    Text(state.selectionNotice, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.records.isNotEmpty()) {
                if (!state.selectionMode) {
                    item(key = "statistics") {
                        GradeStatisticsCard(
                            statistics = statistics,
                            includeOptional = state.includeOptional,
                            onIncludeOptionalChange = onIncludeOptionalChange,
                            onBeginSelection = { onBeginSelection(selectionContext) },
                            onOpenInfo = { onOverlayChange(GradesOverlay.STATISTICS_INFO) },
                            beginEnabled = state.records.any { it.id > 0 },
                        )
                    }
                }
                item(key = "records-header") {
                    GradesListHeader(
                        recordCount = state.records.size,
                        sort = state.sort,
                        sortMenuOpen = overlay == GradesOverlay.SORT_MENU,
                        onSortMenuOpenChange = { open ->
                            onOverlayChange(if (open) GradesOverlay.SORT_MENU else null)
                        },
                        onSelectSort = onSelectSort,
                    )
                }
            }
            if (sorted.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillParentMaxHeight(0.55f)
                            .then(resultModifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyState(
                            icon = Icons.Filled.Star,
                            title = when {
                                state.querying -> "正在查询成绩…"
                                state.snapshot == null -> "还没有成绩缓存"
                                else -> "该范围暂无成绩记录"
                            },
                            hint = when {
                                state.querying -> "请稍候，查询完成后会自动更新"
                                state.snapshot == null -> "下拉刷新获取教务成绩"
                                else -> "成功查询后会覆盖本机缓存"
                            },
                        )
                    }
                }
            } else {
                items(items = sorted, key = { it.id }) { record ->
                    GradeRecordCard(
                        modifier = resultModifier,
                        record = record,
                        selectionMode = state.selectionMode,
                        selected = record.id in state.selectedRecordIds,
                        compactBase = compactBase,
                        onClick = {
                            if (state.selectionMode) onToggleSelection(record.id, selectionContext) else onSelect(record)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun NoticeBanner(
    modifier: Modifier,
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    GlassSurface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        material = GlassMaterial.CONTENT,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f))
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAction) {
                Text(actionLabel, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun UnreadBanner(count: Int, onMarkRead: () -> Unit) {
    val brand = LocalBrand.current
    GlassSurface(
        shape = RoundedCornerShape(14.dp),
        material = GlassMaterial.CONTENT,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(brand.brandSoft.copy(alpha = 0.78f))
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$count 条成绩有新增或更新",
                style = MaterialTheme.typography.bodySmall,
                color = brand.accent,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onMarkRead) {
                Text("标为已读", color = brand.accent)
            }
        }
    }
}

@Composable
private fun GradeDetailDialog(record: GradeRecord, onDismiss: () -> Unit) {
    LiquidAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(record.courseName.ifBlank { "成绩详情" }, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (record.isNew || record.isModified) {
                    Text(
                        text = when {
                            record.isNew && record.isModified -> "本次查询新增且有分数变化"
                            record.isNew -> "本次查询新增"
                            else -> "分数已更新"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalBrand.current.accent,
                    )
                }
                DetailRow("学期", record.semester.ifBlank { "未标注" })
                DetailRow("课程号", record.courseCode.ifBlank { "未提供" })
                DetailRow("成绩", record.scoreText.ifBlank { "未提供" })
                if (record.previousScoreText.isNotBlank()) {
                    DetailRow("原成绩", record.previousScoreText)
                }
                DetailRow("学分", formatCredit(record.credit))
                DetailRow(gradePointLabel(record),
                    GradeStatisticsCalculator.resolvedGradePoint(record)?.let { formatNumber(it) } ?: "学校未提供")
                DetailRow("课程属性", gradeAttributeLabel(record.attribute).ifBlank { "未提供" })
                DetailRow("考试性质", record.examType.ifBlank { "未提供" })
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}
