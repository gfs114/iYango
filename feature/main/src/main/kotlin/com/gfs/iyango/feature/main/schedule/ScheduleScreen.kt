@file:OptIn(ExperimentalMaterial3Api::class)

package com.gfs.iyango.feature.main.schedule

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.gfs.iyango.core.designsystem.component.EmptyState
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.GlassTopBarIconButton
import com.gfs.iyango.core.designsystem.component.GlassOverlayHeader
import com.gfs.iyango.core.designsystem.component.RefreshNoticeCapsule
import com.gfs.iyango.core.designsystem.liquidglass.GlassBackdropHost
import com.gfs.iyango.core.designsystem.liquidglass.LiquidButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidDropdownMenu
import com.gfs.iyango.core.designsystem.liquidglass.LiquidFilterChip
import com.gfs.iyango.core.designsystem.liquidglass.LiquidModalBottomSheet
import com.gfs.iyango.core.designsystem.liquidglass.LocalGlassBackdrop
import com.gfs.iyango.core.designsystem.liquidglass.LocalGlassWallpaperPath
import com.gfs.iyango.core.designsystem.component.ErrorState
import com.gfs.iyango.core.designsystem.component.LoadingState
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.domain.CourseOccurrence
import com.gfs.iyango.core.domain.TimeRailRow
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.DayLabels
import com.gfs.iyango.core.model.ScheduleDisplaySettings
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.TemporaryDay
import com.gfs.iyango.core.model.TimeSlot
import com.gfs.iyango.core.model.Timetable
import com.gfs.iyango.feature.main.coursedetail.CourseDetailDialog
import com.gfs.iyango.feature.main.coursedetail.CourseDetailSource
import com.gfs.iyango.feature.main.coursedetail.CourseDetailSources
import com.gfs.iyango.feature.main.coursedetail.LocalCourseDetailSources
import com.gfs.iyango.feature.main.coursedetail.courseDetailViewport
import com.gfs.iyango.feature.main.coursedetail.ScheduleAdjustmentDialog
import com.gfs.iyango.feature.main.isDarkTheme
import com.gfs.iyango.feature.main.settings.WallpaperLayer
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 课表页（ui-baseline §3）：标题槽 60dp + 周网格面板 + 周选择半模态 +
 * 课程详情弹层与单次调整对话框。
 */
@Composable
fun ScheduleScreen(
    bottomPadding: Dp = 96.dp,
    onAddCourse: () -> Unit = {},
    onEditCourse: (Long) -> Unit = {},
    onCopyCourse: (Long) -> Unit = {},
    onOpenDisplaySettings: () -> Unit = {},
    onOpenSchool: () -> Unit = {},
    onOpenSync: () -> Unit = {},
    onOpenCourseRaw: (Long) -> Unit = {},
    onOpenAiImport: () -> Unit = {},
    viewModel: ScheduleViewModel = hiltViewModel(),
) {
    val nativeState by viewModel.currentTimetableState.collectAsStateWithLifecycle()
    val localPull by viewModel.localPull.collectAsStateWithLifecycle()
    val pullActive = com.gfs.iyango.feature.main.LocalMainTabActive.current
    val termBrowsing by viewModel.termBrowsing.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.CREATED)
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.CREATED)
    val timetables by viewModel.timetables.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.CREATED)
    val temporaryDays by viewModel.temporaryDays.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.CREATED)
    val displaySettings by viewModel.displaySettings.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.CREATED)
    val wallpaper by viewModel.wallpaper.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.CREATED)
    val message by viewModel.message.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.CREATED)
    val pullMessage by viewModel.pullMessage.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.CREATED)

    // 刷新结果与操作提示以顶部胶囊展示；非活动标签页的下拉提示直接消费；会话过期转教务账号页。
    LaunchedEffect(pullMessage, pullActive) {
        if (!pullMessage.isNullOrEmpty() && !pullActive) {
            viewModel.consumePullMessage()
        }
    }
    LaunchedEffect(termBrowsing.refreshNotice, pullActive) {
        if (!pullActive) viewModel.consumeBrowseNotice()
    }
    LaunchedEffect(Unit) {
        viewModel.sessionExpired.collect { expired ->
            if (expired) {
                viewModel.consumeSessionExpired()
                onOpenSchool()
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    LaunchedEffect(lifecycleOwner, pullActive) {
        if (pullActive) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.refreshClock()
                kotlinx.coroutines.awaitCancellation()
            }
        }
    }
    DisposableEffect(context, pullActive, viewModel) {
        if (!pullActive) return@DisposableEffect onDispose {}
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                viewModel.refreshClock()
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_TIME_CHANGED)
            addAction(android.content.Intent.ACTION_TIMEZONE_CHANGED)
            addAction(android.content.Intent.ACTION_DATE_CHANGED)
        }
        context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }

    ScheduleContent(
        state = state,
        localPull = localPull,
        localTimetableState = nativeState,
        onCompletePull = { id, _ -> viewModel.completePull(id) },
        onCancelPull = viewModel::cancelPull,
        termBrowsing = termBrowsing,
        onLoadTerms = viewModel::loadSchoolTerms,
        onBrowseTerm = viewModel::browseTerm,
        onReturnToCurrent = viewModel::returnToCurrentTimetable,
        onOpenSchool = onOpenSchool,
        bottomPadding = bottomPadding,
        isRefreshing = if (termBrowsing.selected != null) termBrowsing.loading else refreshing,
        onRefresh = viewModel::refresh,
        onPullRefresh = viewModel::pullRefresh,
        refreshNotice = if (pullActive) termBrowsing.refreshNotice ?: pullMessage ?: message else null,
        onConsumeRefreshNotice = {
            viewModel.consumeBrowseNotice()
            viewModel.consumePullMessage()
            viewModel.consumeMessage()
        },
        onPreviousWeek = viewModel::previousWeek,
        onNextWeek = viewModel::nextWeek,
        onPickWeek = viewModel::pickWeek,
        onBackToCurrentWeek = viewModel::backToCurrentWeek,
        onRestoreWeek = viewModel::restoreDisplayedWeek,
        onRetry = viewModel::retry,
        onDeleteCourse = viewModel::deleteCourse,
        onApplyAdjustment = viewModel::applyAdjustment,
        onAddCourse = onAddCourse,
        onEditCourse = { course -> onEditCourse(course.id) },
        onCopyCourse = { course -> onCopyCourse(course.id) },
        onOpenDisplaySettings = onOpenDisplaySettings,
        onOpenSync = onOpenSync,
        onOpenCourseRaw = onOpenCourseRaw,
        onOpenAiImport = onOpenAiImport,
        timetables = timetables,
        temporaryDays = temporaryDays,
        onApplyTemporaryDay = viewModel::applyTemporaryDay,
        onClearTemporaryDay = viewModel::clearTemporaryDay,
        display = displaySettings,
        onUpdateDisplay = viewModel::updateDisplaySettings,
        onUpdateCourseColor = viewModel::updateCourseColor,
        wallpaper = wallpaper,
        queryTargetDay = viewModel::targetDay,
        onRestoreTemporaryDay = viewModel::restoreTemporaryDay,
        onDetailSnapshot = viewModel::captureDetail,
        onPagerWeek = viewModel::pickPagerWeek,
        onConfirmMove = viewModel::confirmDrag,
    )
}

/**
 * 课表页无状态内容（预览与页面共用）。
 */
@Composable
fun ScheduleContent(
    state: ScheduleUiState,
    localPull: SchedulePullRequest? = null,
    localTimetableState: ScheduleUiState = state,
    onCompletePull: (Long, Boolean) -> Unit = { _, _ -> },
    onCancelPull: () -> Unit = {},
    termBrowsing: TermBrowseController.State = TermBrowseController.State(),
    onLoadTerms: () -> Unit = {},
    onBrowseTerm: (String) -> Unit = {},
    onReturnToCurrent: () -> Unit = {},
    onOpenSchool: () -> Unit = {},
    bottomPadding: Dp = 96.dp,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onPullRefresh: () -> Unit = onRefresh,
    refreshNotice: String? = null,
    onConsumeRefreshNotice: () -> Unit = {},
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onPickWeek: (Int) -> Unit,
    onBackToCurrentWeek: () -> Unit,
    onRestoreWeek: () -> Unit = {},
    onRetry: () -> Unit,
    onDeleteCourse: (Course, CourseDeleteScope, Int, String) -> Unit,
    onApplyAdjustment: (Course, Int, Int, Int, Int, String, Boolean, com.gfs.iyango.core.domain.EffectiveScheduleDay?) -> Unit,
    onAddCourse: () -> Unit = {},
    onEditCourse: (Course) -> Unit = {},
    onCopyCourse: (Course) -> Unit = {},
    timetables: List<Timetable> = emptyList(),
    temporaryDays: List<TemporaryDay> = emptyList(),
    onApplyTemporaryDay: (LocalDate, Long) -> Unit = { _, _ -> },
    onClearTemporaryDay: (LocalDate) -> Unit = {},
    onOpenDisplaySettings: () -> Unit = {},
    onOpenSync: () -> Unit = {},
    onOpenCourseRaw: (Long) -> Unit = {},
    onOpenAiImport: () -> Unit = {},
    display: ScheduleDisplaySettings = ScheduleDisplaySettings(),
    onUpdateDisplay: ((ScheduleDisplaySettings) -> ScheduleDisplaySettings) -> Unit = {},
    onUpdateCourseColor: (String, String?) -> Unit = { _, _ -> },
    wallpaper: ScheduleWallpaper = ScheduleWallpaper(),
    queryTargetDay: suspend (LocalDate) -> com.gfs.iyango.core.domain.EffectiveScheduleDay = { com.gfs.iyango.core.domain.EffectiveScheduleDay(it) },
    onRestoreTemporaryDay: (Long, LocalDate) -> Unit = { _, _ -> },
    onDetailSnapshot: (WeekScheduleData) -> Unit = {},
    onPagerWeek: (ScheduleContext, Int, Long) -> Unit = { _, week, _ -> onPickWeek(week) },
    onConfirmMove: suspend (ScheduleDropCandidate) -> String? = { "调整不可用" },
) {
    val overlayState = remember { ScheduleOverlayState() }
    val ready = state as? ScheduleUiState.Ready
    val active = com.gfs.iyango.feature.main.LocalMainTabActive.current
    LaunchedEffect(active, ready?.data?.timetableId, ready?.data?.semester) {
        overlayState.resetContext()
    }
    LaunchedEffect(active) {
        if (!active) {
            overlayState.showWeekPicker = false
            overlayState.showQuickSettings = false
        }
    }

    var settledPage by remember { mutableStateOf<SchedulePagePosition?>(null) }
    val indicatorRefreshing = localPull != null || isRefreshing
    var interactionLocked by remember { mutableStateOf(false) }
    val blockRefresh by rememberUpdatedState(interactionLocked || !active || indicatorRefreshing)
    val latestOnRefresh by rememberUpdatedState(onPullRefresh)
    val latestOnDetailSnapshot by rememberUpdatedState(onDetailSnapshot)
    val onOpenWeekPickerCallback = remember(overlayState) { { overlayState.showWeekPicker = true } }
    val onOpenTemporaryDayCallback = remember(overlayState) { { overlayState.showTemporaryDay = true } }
    val onPullRefreshCallback = remember { { if (!blockRefresh) latestOnRefresh() } }
    val onGroupClickCallback = remember(overlayState) {
        { data: WeekScheduleData, cell: ScheduleDisplayProjection.RenderCell ->
            latestOnDetailSnapshot(data)
            overlayState.detailSource = overlayState.sources.takeClicked()
            overlayState.detailGroup = cell
            overlayState.detailData = data
        }
    }
    val onInteractionLockedCallback = remember { { locked: Boolean -> interactionLocked = locked } }
    val refreshBlocker = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (blockRefresh) Offset(0f, available.y) else Offset.Zero
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (blockRefresh) Offset(0f, available.y) else Offset.Zero
            override suspend fun onPreFling(available: Velocity): Velocity =
                if (blockRefresh) Velocity(0f, available.y) else Velocity.Zero
        }
    }
    val pullState = rememberPullToRefreshState()
    val parentBackdrop = LocalGlassBackdrop.current
    val parentWallpaperPath = LocalGlassWallpaperPath.current
    // 若未设置课表专属壁纸，则直接跟随根级调度的全局壁纸（日出日落 / 四时段 / 轮播）。
    val activeWallpaperPath = if (!wallpaper.hasDedicatedWallpaper && parentBackdrop != null) {
        parentWallpaperPath
    } else if (isDarkTheme()) {
        wallpaper.darkPath ?: wallpaper.path
    } else {
        wallpaper.path ?: wallpaper.darkPath
    }
    // P3 优化：当存在父级 GlassBackdropHost 且课表有效壁纸与全局壁纸一致（含均无壁纸）时，
    // 直接复用根级 L0，避免重复创建全屏 layerBackdrop 及重复绘制/模糊同一张壁纸。
    val needsDedicatedBackdropHost = parentBackdrop == null || activeWallpaperPath != parentWallpaperPath
    val scaffoldContent: @Composable () -> Unit = {
        GlassScaffold(
            modifier = Modifier.fillMaxSize().nestedScroll(refreshBlocker),
            containerColor = Color.Transparent,
            // The root Dock already accounts for navigation bars; GlassTopBar owns top.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = { ScheduleHeader(
                state = state,
                browsing = termBrowsing.selected != null,
                navigationEnabled = active && !interactionLocked,
                onPreviousWeek = onPreviousWeek,
                onNextWeek = onNextWeek,
                onOpenWeekPicker = onOpenWeekPickerCallback,
                onAddCourse = onAddCourse,
                onDisplaySettings = { overlayState.showQuickSettings = true },
                onOpenSync = onOpenSync,
                canOpenRaw = (ready?.data?.timetableId ?: 0L) > 0L,
                onOpenCourseRaw = { ready?.data?.timetableId?.takeIf { it > 0L }?.let(onOpenCourseRaw) },
                onBackToCurrentWeek = onBackToCurrentWeek,
                onRestoreWeek = onRestoreWeek,
                onOpenTemporaryDay = onOpenTemporaryDayCallback,
                onOpenAiImport = onOpenAiImport,
            ) },
            overlay = { innerPadding ->
                RefreshNoticeCapsule(
                    message = refreshNotice,
                    onDismiss = onConsumeRefreshNotice,
                    topPadding = innerPadding.calculateTopPadding(),
                )
            },
        ) { innerPadding ->
            PullToRefreshBox(
                isRefreshing = indicatorRefreshing,
                onRefresh = onPullRefreshCallback,
                state = pullState,
                modifier = Modifier.fillMaxSize().courseDetailViewport(innerPadding.calculateTopPadding(), bottomPadding),
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pullState,
                        isRefreshing = indicatorRefreshing,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = innerPadding.calculateTopPadding()),
                    )
                },
            ) {
                SchedulePullTransition(localPull, state, settledPage, active, onCompletePull, onCancelPull, localTimetableState) { transitionModifier ->
                    Box(modifier = transitionModifier) {
                        when (state) {
                            ScheduleUiState.FirstLoading,
                            ScheduleUiState.NoTimetable,
                            ScheduleUiState.NoSemester,
                            is ScheduleUiState.Error -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                                item {
                                    Box(modifier = Modifier.fillParentMaxSize().padding(innerPadding)) {
                                        when (state) {
                                            ScheduleUiState.FirstLoading -> LoadingState(modifier = Modifier.fillMaxSize())
                                            ScheduleUiState.NoTimetable -> EmptyState(
                                                icon = Icons.Filled.DateRange,
                                                title = "还没有课表",
                                                hint = "先在「我的」中创建或导入课表",
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                            ScheduleUiState.NoSemester -> EmptyState(
                                                icon = Icons.Filled.DateRange,
                                                title = "未设置学期",
                                                hint = "下拉同步教务课表，或在「我的」中创建学期",
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                            is ScheduleUiState.Error -> ErrorState(
                                                title = "加载失败",
                                                detail = state.message,
                                                onRetry = if (state.canRetry) onRetry else null,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                            is ScheduleUiState.Ready -> Unit
                                        }
                                    }
                                }
                            }

                            is ScheduleUiState.Ready -> ScheduleWeeks(
                                ready = state, display = display,
                                // [无底部空行] 不再给 Dock 预留滚动尾部留白：网格内容铺到屏幕
                                // 底部、从玻璃 Dock 下穿过（与今日页一致的穿透效果）。
                                hasWallpaper = activeWallpaperPath != null, bottomInset = 0.dp,
                                topInset = innerPadding.calculateTopPadding(),
                                active = active, onPick = onPickWeek, onPagerWeek = onPagerWeek,
                                onGroupClick = onGroupClickCallback,
                                onInteractionLocked = onInteractionLockedCallback, onConfirmMove = onConfirmMove,
                                onPageSettled = { settledPage = it },
                            )
                        }
                    }
                }
            }
        }
    }
    if (needsDedicatedBackdropHost) {
        GlassBackdropHost(
            modifier = Modifier.fillMaxSize(),
            hasWallpaper = activeWallpaperPath != null,
            wallpaperPath = activeWallpaperPath,
            background = {
                WallpaperLayer(path = activeWallpaperPath, scrim = wallpaper.scrim, blur = wallpaper.blur)
            },
        ) {
            CompositionLocalProvider(LocalCourseDetailSources provides overlayState.sources) { scaffoldContent() }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalCourseDetailSources provides overlayState.sources) { scaffoldContent() }
        }
    }

    ScheduleOverlays(
        overlayState = overlayState,
        ready = ready,
        scheduleState = state,
        onRetryChooser = if (termBrowsing.error != null) onRefresh else onRetry,
        termBrowsing = termBrowsing,
        onLoadTerms = onLoadTerms,
        onBrowseTerm = onBrowseTerm,
        onReturnToCurrent = onReturnToCurrent,
        onOpenSchool = onOpenSchool,
        display = display,
        onUpdateDisplay = onUpdateDisplay,
        onUpdateCourseColor = onUpdateCourseColor,
        onOpenFullDisplaySettings = onOpenDisplaySettings,
        onOpenCourseRaw = { ready?.data?.timetableId?.takeIf { it > 0L }?.let(onOpenCourseRaw) },
        timetables = timetables,
        temporaryDays = temporaryDays,
        queryTargetDay = queryTargetDay,
        onPickWeek = onPickWeek,
        onBackToCurrentWeek = onBackToCurrentWeek,
        onRestoreTemporaryDay = onRestoreTemporaryDay,
        onEditCourse = onEditCourse,
        onCopyCourse = onCopyCourse,
        onDeleteCourse = onDeleteCourse,
        onApplyAdjustment = onApplyAdjustment,
        onApplyTemporaryDay = onApplyTemporaryDay,
        onClearTemporaryDay = onClearTemporaryDay,
    )
}

@Stable
private class ScheduleOverlayState {
    val sources = CourseDetailSources()
    var detailSource by mutableStateOf<CourseDetailSource?>(null)
    var showWeekPicker by mutableStateOf(false)
    var showQuickSettings by mutableStateOf(false)
    var detailData by mutableStateOf<WeekScheduleData?>(null)
    var detailGroup by mutableStateOf<ScheduleDisplayProjection.RenderCell?>(null)
    var adjustTarget by mutableStateOf<AdjustmentTarget?>(null)
    var showTemporaryDay by mutableStateOf(false)

    fun resetContext() {
        detailSource = null
        showQuickSettings = false
        detailData = null
        detailGroup = null
        adjustTarget = null
        showTemporaryDay = false
    }
}

@Composable
private fun ScheduleOverlays(
    overlayState: ScheduleOverlayState,
    ready: ScheduleUiState.Ready?,
    scheduleState: ScheduleUiState,
    onRetryChooser: () -> Unit,
    termBrowsing: TermBrowseController.State,
    onLoadTerms: () -> Unit,
    onBrowseTerm: (String) -> Unit,
    onReturnToCurrent: () -> Unit,
    onOpenSchool: () -> Unit,
    display: ScheduleDisplaySettings,
    onUpdateDisplay: ((ScheduleDisplaySettings) -> ScheduleDisplaySettings) -> Unit,
    onUpdateCourseColor: (String, String?) -> Unit,
    onOpenFullDisplaySettings: () -> Unit,
    onOpenCourseRaw: () -> Unit,
    timetables: List<Timetable>,
    temporaryDays: List<TemporaryDay>,
    queryTargetDay: suspend (LocalDate) -> com.gfs.iyango.core.domain.EffectiveScheduleDay,
    onPickWeek: (Int) -> Unit,
    onBackToCurrentWeek: () -> Unit,
    onRestoreTemporaryDay: (Long, LocalDate) -> Unit,
    onEditCourse: (Course) -> Unit,
    onCopyCourse: (Course) -> Unit,
    onDeleteCourse: (Course, CourseDeleteScope, Int, String) -> Unit,
    onApplyAdjustment: (Course, Int, Int, Int, Int, String, Boolean, com.gfs.iyango.core.domain.EffectiveScheduleDay?) -> Unit,
    onApplyTemporaryDay: (LocalDate, Long) -> Unit,
    onClearTemporaryDay: (LocalDate) -> Unit,
) {
    if (overlayState.showWeekPicker) {
        val chooserData = ready?.data?.takeIf {
            if (termBrowsing.selected == null) !it.readOnly else it.browsedTerm == termBrowsing.selected
        }
        WeekPickerSheet(
            data = chooserData,
            scheduleState = scheduleState,
            onRetry = onRetryChooser,
            termBrowsing = termBrowsing,
            onLoadTerms = onLoadTerms,
            onBrowseTerm = onBrowseTerm,
            onReturnToCurrent = onReturnToCurrent,
            onOpenSchool = onOpenSchool,
            onPick = { week ->
                onPickWeek(week)
                overlayState.showWeekPicker = false
            },
            onDismiss = { overlayState.showWeekPicker = false },
            onBackToCurrentWeek = {
                onBackToCurrentWeek()
                overlayState.showWeekPicker = false
            },
        )
    }

    if (overlayState.showQuickSettings) {
        ScheduleQuickSettingsSheet(
            display = display,
            onUpdateDisplay = onUpdateDisplay,
            onOpenFullDisplaySettings = onOpenFullDisplaySettings,
            onOpenCourseRaw = if ((ready?.data?.timetableId ?: 0L) > 0L) onOpenCourseRaw else null,
            onDismiss = { overlayState.showQuickSettings = false },
        )
    }

    overlayState.detailGroup?.let { group ->
        val data = overlayState.detailData
        CourseDetailDialog(
            source = overlayState.detailSource,
            courses = group.blocks.map { it.course },
            occurrenceEntries = if (data?.datesKnown != false) group.blocks.map { it.occurrence } else emptyList(),
            readOnly = data?.readOnly == true,
            onAdjust = {},
            onDelete = { _, _ -> },
            timeSlots = data?.slotsForDay(group.dayOfWeek).orEmpty(),
            displayedWeek = data?.displayedWeek ?: 1,
            paletteLight = display.paletteLight,
            paletteDark = display.paletteDark,
            allCourses = data?.courses.orEmpty(),
            courseColorOverrides = display.courseColorOverrides,
            onRestoreOccurrence = { occurrence ->
                val day = data?.effectiveDays?.firstOrNull { it.date == occurrence.date }
                day?.timetable?.let { onRestoreTemporaryDay(it.id, day.date) }
            },
            onEdit = onEditCourse,
            onCopy = { course ->
                overlayState.detailGroup = null
                onCopyCourse(course)
            },
            onAdjustEntry = { course, occurrence ->
                overlayState.detailGroup = null
                overlayState.adjustTarget = AdjustmentTarget(
                    course = course,
                    sourceDate = occurrence?.anchorDate?.toString().orEmpty(),
                    occurrenceId = occurrence?.occurrenceId,
                    context = data,
                )
            },
            onDeleteEntry = { course, scope, occurrence ->
                overlayState.detailGroup = null
                data?.let {
                    onDeleteCourse(
                        course,
                        scope,
                        it.displayedWeek,
                        occurrence?.anchorDate?.toString().orEmpty(),
                    )
                }
            },
            onDismiss = { overlayState.detailGroup = null },
        )
    }

    overlayState.adjustTarget?.let { target ->
        val data = target.context
        if (data != null) {
            ScheduleAdjustmentDialog(
                course = target.course,
                displayedWeek = data.displayedWeek,
                semester = data.semester,
                totalWeeks = data.totalWeeks,
                sectionCount = data.sectionCount,
                sourceOccurrenceId = target.occurrenceId,
                queryTargetDay = queryTargetDay,
                timeSlots = data.timeSlots,
                onApply = { targetWeek, day, startSection, endSection, temporary, confirmedDay ->
                    onApplyAdjustment(
                        target.course,
                        targetWeek,
                        day,
                        startSection,
                        endSection,
                        target.sourceDate,
                        temporary,
                        confirmedDay,
                    )
                    overlayState.adjustTarget = null
                },
                onDismiss = { overlayState.adjustTarget = null },
            )
        }
    }

    if (overlayState.showTemporaryDay && ready != null) {
        TemporaryDaySheet(
            data = ready.data,
            timetables = timetables,
            temporaryDays = temporaryDays,
            onApply = { date, sourceId ->
                onApplyTemporaryDay(date, sourceId)
                overlayState.showTemporaryDay = false
            },
            onClear = { date ->
                onClearTemporaryDay(date)
                overlayState.showTemporaryDay = false
            },
            onDismiss = { overlayState.showTemporaryDay = false },
        )
    }
}

/** 单次调整目标：课程 + 该次发生的原始日期锚点。 */
private data class AdjustmentTarget(
    val course: Course,
    val sourceDate: String,
    val occurrenceId: String? = null,
    val context: WeekScheduleData? = null,
)

// ---------------- 标题区 ----------------

/**
 * 固定顶栏：周标题直接打开选择器；添加和完整溢出菜单保留原有业务入口。
 */
@Composable
private fun ScheduleHeader(
    state: ScheduleUiState,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onOpenWeekPicker: () -> Unit,
    onAddCourse: () -> Unit,
    onDisplaySettings: () -> Unit,
    onOpenSync: () -> Unit = {},
    canOpenRaw: Boolean = false,
    onOpenCourseRaw: () -> Unit = {},
    onBackToCurrentWeek: () -> Unit,
    onRestoreWeek: () -> Unit,
    onOpenTemporaryDay: () -> Unit,
    onOpenAiImport: () -> Unit = {},
    navigationEnabled: Boolean = true,
    browsing: Boolean = false,
) {
    val ready = state as? ScheduleUiState.Ready
    val data = ready?.data
    val canNavigate = navigationEnabled && data != null && data.totalWeeks > 0
    val canBackToCurrent = navigationEnabled && data != null &&
        data.currentWeek > 0 &&
        data.displayedWeek != data.currentWeek

    GlassTopBar(
        title = data?.let { "第${it.displayedWeek}周 周${DayLabels.shortLabel(it.todayDay)}" } ?: "课表",
        subtitle = data?.let { "${it.today.year}/${it.today.monthValue}/${it.today.dayOfMonth}" },
        onTitleClick = if (navigationEnabled) onOpenWeekPicker else null,
        actions = {
            HeaderIconButton(
                icon = Icons.Filled.Add,
                contentDescription = "添加课程",
                enabled = !browsing && data?.readOnly != true,
                onClick = onAddCourse,
            )
            HeaderOverflowMenu(
                onDisplaySettings = onDisplaySettings,
                onOpenSync = onOpenSync,
                onOpenAiImport = onOpenAiImport,
                canOpenRaw = canOpenRaw,
                onOpenCourseRaw = onOpenCourseRaw,
                onPreviousWeek = onPreviousWeek,
                onNextWeek = onNextWeek,
                onBackToCurrentWeek = onBackToCurrentWeek,
                onRestoreWeek = onRestoreWeek,
                onOpenTemporaryDay = onOpenTemporaryDayCallback@{ onOpenTemporaryDay() },
                canNavigate = canNavigate,
                canBackToCurrent = canBackToCurrent,
                hasData = navigationEnabled && !browsing && data != null && !data.readOnly,
            )
        },
    )
}

@Composable
private fun HeaderIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    GlassTopBarIconButton(onClick = onClick, enabled = enabled) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}

/** 溢出菜单：显示设置 / 上一周 / 下一周 / 回到本周。 */
@Composable
private fun HeaderOverflowMenu(
    onDisplaySettings: () -> Unit,
    onOpenSync: () -> Unit = {},
    onOpenAiImport: () -> Unit = {},
    canOpenRaw: Boolean = false,
    onOpenCourseRaw: () -> Unit = {},
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onBackToCurrentWeek: () -> Unit,
    onRestoreWeek: () -> Unit,
    onOpenTemporaryDay: () -> Unit,
    canNavigate: Boolean,
    canBackToCurrent: Boolean,
    hasData: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val onOpenMenu = remember { { expanded = true } }
    val onDismissMenu = remember { { expanded = false } }
    Box {
        HeaderIconButton(
            icon = Icons.Filled.MoreVert,
            contentDescription = "更多操作",
            enabled = true,
            onClick = onOpenMenu,
        )
        LiquidDropdownMenu(
            expanded = expanded && com.gfs.iyango.feature.main.LocalMainTabActive.current,
            onDismissRequest = onDismissMenu,
        ) {
            DropdownMenuItem(
                text = { Text("Ai导入") },
                onClick = {
                    expanded = false
                    onOpenAiImport()
                },
            )
            DropdownMenuItem(
                text = { Text("同步教务课表") },
                onClick = {
                    expanded = false
                    onOpenSync()
                },
            )
            DropdownMenuItem(
                text = { Text("显示设置") },
                onClick = {
                    expanded = false
                    onDisplaySettings()
                },
            )
            DropdownMenuItem(
                text = { Text("课表原文 (HTML/JSON)") },
                enabled = canOpenRaw,
                onClick = {
                    expanded = false
                    onOpenCourseRaw()
                },
            )
            DropdownMenuItem(
                text = { Text("上一周") },
                enabled = canNavigate,
                onClick = {
                    expanded = false
                    onPreviousWeek()
                },
            )
            DropdownMenuItem(
                text = { Text("下一周") },
                enabled = canNavigate,
                onClick = {
                    expanded = false
                    onNextWeek()
                },
            )
            DropdownMenuItem(
                text = { Text("回到本周") },
                enabled = canBackToCurrent,
                onClick = {
                    expanded = false
                    onBackToCurrentWeek()
                },
            )
            DropdownMenuItem(
                text = { Text("恢复本周调整") },
                enabled = hasData,
                onClick = {
                    expanded = false
                    onRestoreWeek()
                },
            )
            DropdownMenuItem(
                text = { Text("临时整天课表") },
                enabled = hasData,
                onClick = {
                    expanded = false
                    onOpenTemporaryDay()
                },
            )
        }
    }
}

// ---------------- 网格面板 ----------------

/**
 * F08 临时整天课表：选择展示周内的一天 + 来源课表，用来源课表该日安排替换当天。
 * 连续多日可多次应用；「恢复该日原课表」只删除该日的临时整天记录。
 */
@Composable
private fun TemporaryDaySheet(
    data: WeekScheduleData,
    timetables: List<Timetable>,
    temporaryDays: List<TemporaryDay>,
    onApply: (LocalDate, Long) -> Unit,
    onClear: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedDate by remember { mutableStateOf(data.weekStart) }
    var sourceId by remember { mutableStateOf(timetables.firstOrNull()?.id ?: 0L) }
    val selectedDay = data.effectiveDays.firstOrNull { it.date == selectedDate }
    val owner = selectedDay?.timetable
    val availableSources = timetables.filter { it.id != owner?.id }
    LaunchedEffect(selectedDate, owner?.id, availableSources) {
        if (availableSources.none { it.id == sourceId }) sourceId = availableSources.firstOrNull()?.id ?: 0
    }
    val existing = selectedDay?.isTemporaryDay == true

    LiquidModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassOverlayHeader(title = "临时整天课表", contentPadding = PaddingValues(0.dp))
            Text(
                text = "用另一张课表当天的安排替换所选日期，不修改任何课程。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("选择日期", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (0..6).forEach { offset ->
                    val date = data.weekStart.plusDays(offset.toLong())
                    LiquidFilterChip(
                        selected = date == selectedDate,
                        onClick = { selectedDate = date },
                        label = {
                            Text(
                                "${DayLabels.shortLabel(date.dayOfWeek.value)} " +
                                    "${date.monthValue}/${date.dayOfMonth}",
                            )
                        },
                    )
                }
            }
            Text("来源课表", style = MaterialTheme.typography.labelLarge)
            Text("所选日期课表：${owner?.name ?: "暂无课表"}", style = MaterialTheme.typography.bodySmall)
            if (availableSources.isEmpty()) {
                Text(
                    text = "暂无其他课表；先在「我的课表」中新建或复制一份。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    availableSources.forEach { table ->
                        LiquidFilterChip(
                            selected = table.id == sourceId,
                            onClick = { sourceId = table.id },
                            label = { Text(table.name) },
                        )
                    }
                }
            }
            if (existing) {
                Text(
                    text = "该日已使用临时整天课表。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (existing) {
                    TextButton(onClick = { onClear(selectedDate) }) { Text("恢复该日原课表") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
                LiquidButton(
                    onClick = { onApply(selectedDate, sourceId) },
                    enabled = owner != null && sourceId > 0 && availableSources.any { it.id == sourceId },
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("应用") }
            }
        }
    }
}

// ---------------- 预览 ----------------

/** 预览用示例数据：2026-09-21 开学的学期 + 三门课（其中周三两门冲突）。 */
private fun previewScheduleData(dark: Boolean): WeekScheduleData {
    val semester = Semester(
        id = 1,
        timetableId = 1,
        name = "2026 秋季学期",
        startDate = LocalDate.of(2026, 9, 21),
        totalWeeks = 20,
        isCurrent = true,
    )
    val slots = listOf(
        TimeSlot(section = 1, startTime = "08:00", endTime = "08:45"),
        TimeSlot(section = 2, startTime = "08:55", endTime = "09:40"),
        TimeSlot(section = 3, startTime = "10:00", endTime = "10:45"),
        TimeSlot(section = 4, startTime = "10:55", endTime = "11:40"),
        TimeSlot(section = 5, startTime = "14:00", endTime = "14:45"),
        TimeSlot(section = 6, startTime = "14:55", endTime = "15:40"),
    )
    val today = LocalDate.of(2026, 9, 23)
    val math = Course(
        id = 1, timetableId = 1, name = "高等数学", teacher = "张老师",
        classroom = "教 1-201", dayOfWeek = 1, startSection = 1, endSection = 2,
        startWeek = 1, endWeek = 16, color = "#3E7BFA",
    )
    val english = Course(
        id = 2, timetableId = 1, name = "大学英语", teacher = "李老师",
        classroom = "外语楼 305", dayOfWeek = 2, startSection = 3, endSection = 4,
        startWeek = 1, endWeek = 16, weekType = com.gfs.iyango.core.model.WeekType.ODD,
        color = "#2BB673",
    )
    val physics = Course(
        id = 3, timetableId = 1, name = "大学物理", teacher = "王老师",
        classroom = "实验楼 B102", dayOfWeek = 3, startSection = 5, endSection = 5,
        startWeek = 1, endWeek = 16, color = "#F2A03D",
    )
    val chemistry = Course(
        id = 4, timetableId = 1, name = "普通化学实验", teacher = "赵老师",
        classroom = "化学楼 401", dayOfWeek = 3, startSection = 5, endSection = 6,
        startWeek = 1, endWeek = 16, color = "#8B6BF0",
    )
    fun occurrence(course: Course, dayOffset: Long) = CourseOccurrence(
        occurrenceId = "preview-${course.id}",
        course = course,
        date = LocalDate.of(2026, 9, 21).plusDays(dayOffset),
        startMinute = 8 * 60,
        endMinute = 9 * 60 + 40,
        isOverride = false,
    )
    val cells = listOf(
        WeekScheduleData.GridCellGroup(
            dayOfWeek = 1,
            startSection = 1,
            blocks = listOf(
                WeekScheduleData.GridBlock(
                    course = math,
                    occurrence = occurrence(math, 0),
                    rowSpan = 2,
                    isOffWeek = false,
                ),
            ),
        ),
        WeekScheduleData.GridCellGroup(
            dayOfWeek = 2,
            startSection = 3,
            blocks = listOf(
                WeekScheduleData.GridBlock(
                    course = english,
                    occurrence = occurrence(english, 1),
                    rowSpan = 2,
                    isOffWeek = false,
                ),
            ),
        ),
        WeekScheduleData.GridCellGroup(
            dayOfWeek = 3,
            startSection = 5,
            blocks = listOf(
                WeekScheduleData.GridBlock(
                    course = physics,
                    occurrence = occurrence(physics, 2),
                    rowSpan = 1,
                    isOffWeek = false,
                ),
                WeekScheduleData.GridBlock(
                    course = chemistry,
                    occurrence = occurrence(chemistry, 2),
                    rowSpan = 2,
                    isOffWeek = false,
                ),
            ),
        ),
    )
    val weekCounts = List(20) { week ->
        List(7) { day ->
            if (week == 2 && day == 0) 1 else if (week == 2 && day == 1) 1 else if (week == 2 && day == 2) 2 else 0
        }
    }
    return WeekScheduleData(
        timetableName = "我的课表",
        semester = semester,
        totalWeeks = 20,
        currentWeek = 1,
        displayedWeek = 1,
        flipDirection = WeekFlipDirection.JUMP,
        weekStart = LocalDate.of(2026, 9, 21),
        today = today,
        now = LocalDateTime.of(2026, 9, 23, 14, 20),
        sectionCount = 12,
        cells = cells,
        timeSlots = slots,
        courses = listOf(math, english, physics, chemistry),
        weekDayCounts = weekCounts,
        timeRail = (1..12).map { section ->
            val slot = slots.firstOrNull { it.section == section }
            TimeRailRow(
                section = section,
                label = section.toString(),
                start = slot?.startTime.orEmpty(),
                end = slot?.endTime.orEmpty(),
            )
        },
    )
}

/** 亮色预览：完整课表页（明）。 */
@Preview(name = "课表 - 亮色", showBackground = true, widthDp = 400, heightDp = 820)
@Composable
private fun ScheduleScreenLightPreview() {
    IYangoThemePreview(darkTheme = false) {
        ScheduleContent(
            state = ScheduleUiState.Ready(previewScheduleData(dark = false)),
            onPreviousWeek = {},
            onNextWeek = {},
            onPickWeek = {},
            onBackToCurrentWeek = {},
            onRetry = {},
            onDeleteCourse = { _, _, _, _ -> },
            onApplyAdjustment = { _, _, _, _, _, _, _, _ -> },
        )
    }
}

/** 暗色预览：完整课表页（暗）。 */
@Preview(name = "课表 - 暗色", showBackground = true, widthDp = 400, heightDp = 820)
@Composable
private fun ScheduleScreenDarkPreview() {
    IYangoThemePreview(darkTheme = true) {
        ScheduleContent(
            state = ScheduleUiState.Ready(previewScheduleData(dark = true)),
            onPreviousWeek = {},
            onNextWeek = {},
            onPickWeek = {},
            onBackToCurrentWeek = {},
            onRetry = {},
            onDeleteCourse = { _, _, _, _ -> },
            onApplyAdjustment = { _, _, _, _, _, _, _, _ -> },
        )
    }
}
