package com.gfs.iyango.feature.main.today

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gfs.iyango.core.designsystem.component.EmptyState
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.GlassTopBarIconButton
import com.gfs.iyango.core.designsystem.component.ErrorState
import com.gfs.iyango.core.designsystem.component.LoadingState
import com.gfs.iyango.core.designsystem.component.RefreshNoticeCapsule
import com.gfs.iyango.core.designsystem.component.SectionHeader
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidFilterChip
import com.gfs.iyango.core.designsystem.motion.AppMotion
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled
import com.gfs.iyango.core.designsystem.motion.motionPress
import com.gfs.iyango.core.designsystem.motion.motionReveal
import com.gfs.iyango.core.designsystem.theme.CourseSwatch
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.designsystem.theme.LocalBrand
import com.gfs.iyango.core.model.HomeModule
import com.gfs.iyango.core.model.HomePreferences
import com.gfs.iyango.core.model.HomeShortcut
import com.gfs.iyango.core.domain.BuildingTransferDetector
import com.gfs.iyango.core.domain.BuildingTransferWarning
import com.gfs.iyango.core.domain.CourseOccurrence
import com.gfs.iyango.core.domain.TodayCourseTiming
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.repeatOnLifecycle
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.SchoolConnectionStatus
import com.gfs.iyango.feature.main.ai.AiRobotIcon
import com.gfs.iyango.feature.main.coursedetail.CourseDetailDialog
import com.gfs.iyango.feature.main.coursedetail.CourseDetailSource
import com.gfs.iyango.feature.main.coursedetail.CourseDetailSources
import com.gfs.iyango.feature.main.coursedetail.LocalCourseDetailSources
import com.gfs.iyango.feature.main.coursedetail.courseDetailViewport
import com.gfs.iyango.core.designsystem.liquidglass.glassContentFillColor
import com.gfs.iyango.feature.main.coursedetail.rememberCourseDetailSource
import com.gfs.iyango.feature.main.isDarkTheme
import java.time.LocalDate

/**
 * 今日页（契约 §2）：资料卡 → 今日课程 → 快捷入口 → 月历卡 → 明日安排。
 *
 * @param bottomPadding 液态玻璃 Dock 占位高度：作为列表尾部 contentPadding，
 *   让内容滚动时穿过 Dock 下方被玻璃折射。
 * @param onOpenSchedule 快捷入口外跳课表 Tab。
 * @param onOpenGrades 快捷入口外跳成绩 Tab。
 * @param onOpenImportExport 导入导出入口（W9 导航）。
 * @param onOpenSemesterManage 学期管理入口。
 * @param onOpenSchool 教务账号入口。
 * @param onAddCourse 新建课程入口。
 * @param onEditCourse 课程编辑入口（参数为课程 id）。
 * @param onCopyCourse 课程复制入口（参数为来源课程 id）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    bottomPadding: Dp = 96.dp,
    onOpenSchedule: () -> Unit = {},
    onOpenGrades: () -> Unit = {},
    onOpenImportExport: () -> Unit = {},
    onOpenSemesterManage: () -> Unit = {},
    onOpenCourseManage: () -> Unit = {},
    onOpenExams: () -> Unit = {},
    onOpenSchool: () -> Unit = {},
    onAddCourse: () -> Unit = {},
    onEditCourse: (Long) -> Unit = {},
    onCopyCourse: (Long) -> Unit = {},
    onOpenHomeSettings: () -> Unit = {},
    onOpenClassroom: () -> Unit = {},
    onOpenTimetableManage: () -> Unit = {},
    onOpenSettingsBackup: () -> Unit = {},
    onOpenCreditProgress: () -> Unit = {},
    onOpenAiAssistant: () -> Unit = {},
    viewModel: TodayViewModel = hiltViewModel(),
    homePreferences: HomePreferences? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.STARTED)
    val detailRequest by viewModel.detailRequest.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.STARTED)
    val sources = remember { CourseDetailSources() }
    var detailSource by remember { mutableStateOf<CourseDetailSource?>(null) }
    val detailDays by viewModel.detailDays.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.STARTED)
    val displaySettings by viewModel.displaySettings.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.STARTED)
    val message by viewModel.message.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.STARTED)
    // Disposal must use the visibility captured when this receiver was registered.
    // A rememberUpdatedState delegate would read the next tab's visibility during cleanup.
    val tabActive = com.gfs.iyango.feature.main.LocalMainTabActive.current

    val home = homePreferences ?: viewModel.preferences.collectAsStateWithLifecycle().value
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    val visible = tabActive && lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    val context = LocalContext.current
    val secondsVisible = home.isVisible(HomeModule.TODAY)
    val boundaryVisible = HomeModule.entries.any { it != HomeModule.SHORTCUTS && home.isVisible(it) }
    val readyForTiming = state is TodayUiState.Ready
    LaunchedEffect(lifecycleOwner, tabActive) {
        if (tabActive) lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            viewModel.resumeLocalObservation()
            kotlinx.coroutines.awaitCancellation()
        }
    }
    LaunchedEffect(lifecycleOwner, tabActive, secondsVisible, boundaryVisible, readyForTiming) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            viewModel.refreshNow(java.time.ZoneId.systemDefault())
            viewModel.setTimingActive(tabActive && boundaryVisible && readyForTiming, seconds = secondsVisible)
            try { kotlinx.coroutines.awaitCancellation() } finally { viewModel.setTimingActive(false) }
        }
    }
    DisposableEffect(context, tabActive, viewModel) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                viewModel.refreshNow(java.time.ZoneId.systemDefault())
            }
        }
        if (tabActive) {
            val filter = android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_TIME_CHANGED)
                addAction(android.content.Intent.ACTION_TIMEZONE_CHANGED)
                addAction(android.content.Intent.ACTION_DATE_CHANGED)
            }
            context.registerReceiver(receiver, filter)
        }
        onDispose { if (tabActive) context.unregisterReceiver(receiver) }
    }

    LaunchedEffect(message, tabActive) {
        if (!message.isNullOrEmpty() && !tabActive) {
            viewModel.consumeMessage()
        }
    }

    TodayPageLayout(
        state = state,
        onProfileClick = onOpenSchool,
        onOpenHomeSettings = onOpenHomeSettings,
        onOpenAiAssistant = onOpenAiAssistant,
        overlay = { innerPadding ->
        RefreshNoticeCapsule(
            message = if (tabActive) message else null,
            onDismiss = viewModel::consumeMessage,
            topPadding = innerPadding.calculateTopPadding(),
        )
    }) { innerPadding ->
        Box(Modifier.fillMaxSize().motionReveal(state::class)) {
            when (val current = state) {
                TodayUiState.FirstLoading -> Box(Modifier.fillMaxSize().padding(innerPadding)) {
                    if (visible) LoadingState() else Text("正在加载")
                }
                TodayUiState.NoTimetable -> Column(Modifier.fillMaxSize().padding(innerPadding)) {
                    EmptyState(
                        icon = Icons.Filled.DateRange,
                        title = "还没有课表",
                        hint = "去「我的-学期管理」创建学期并添加课程",
                    )
                    com.gfs.iyango.core.designsystem.liquidglass.LiquidButton(
                        onClick = onOpenSemesterManage,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text("去创建学期")
                    }
                }
                is TodayUiState.Error -> Box(Modifier.fillMaxSize().padding(innerPadding)) {
                    ErrorState(
                        title = "加载失败",
                        detail = current.message,
                        onRetry = viewModel::retryLocalLoad,
                    )
                }
                is TodayUiState.Ready -> {
                    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.STARTED)
                    val pullState = rememberPullToRefreshState()
                    PullToRefreshBox(
                        isRefreshing = refreshing,
                        onRefresh = viewModel::refresh,
                        state = pullState,
                        modifier = Modifier.fillMaxSize(),
                        indicator = {
                            PullToRefreshDefaults.Indicator(
                                state = pullState,
                                isRefreshing = refreshing,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = innerPadding.calculateTopPadding()),
                            )
                        },
                    ) {
                        androidx.compose.runtime.CompositionLocalProvider(LocalCourseDetailSources provides sources) {
                            TodayReadyContent(
                                state = current,
                                bottomPadding = bottomPadding,
                                topPadding = innerPadding.calculateTopPadding(),
                                paletteLight = displaySettings.paletteLight,
                                paletteDark = displaySettings.paletteDark,
                                onCourseClick = { row ->
                                    detailSource = sources.takeClicked()
                                    viewModel.openDetail(row)
                                },
                                onQuickTile = { id ->
                                    when (id) {
                                        HomeShortcut.GRADES.id -> onOpenGrades()
                                        HomeShortcut.IMPORT_EXPORT.id -> onOpenImportExport()
                                        HomeShortcut.COURSES.id -> onOpenCourseManage()
                                        HomeShortcut.SEMESTER.id -> onOpenSemesterManage()
                                        HomeShortcut.SCHEDULE.id -> onOpenSchedule()
                                        HomeShortcut.EXAMS.id -> onOpenExams()
                                        HomeShortcut.CLASSROOM.id -> onOpenClassroom()
                                        HomeShortcut.TIMETABLES.id -> onOpenTimetableManage()
                                        HomeShortcut.SCHOOL.id -> onOpenSchool()
                                        HomeShortcut.SETTINGS_BACKUP.id -> onOpenSettingsBackup()
                                        HomeShortcut.CREDIT_PROGRESS.id -> onOpenCreditProgress()
                                        HomeShortcut.AI_ASSISTANT.id -> onOpenAiAssistant()
                                    }
                                },
                                home = home,
                                timing = viewModel.timing,
                                onOpenSchedule = onOpenSchedule,
                                onOpenExams = onOpenExams,
                                onProfileClick = onOpenSchool,
                                onApplyMakeupWeekday = viewModel::applyMakeupWeekday,
                                onRestoreTemporaryDay = viewModel::restoreTemporaryDay,
                            )
                        }
                    }
                }
            }
        }
    }

    val detailRoot = state as? TodayUiState.Ready
    TodayDetailResetEffect(
        timetableId = detailRoot?.timetable?.id,
        semester = detailRoot?.semester,
        onReset = viewModel::closeDetail,
    )

    detailRequest?.let { occurrences ->
        val ready = state as? TodayUiState.Ready
        if (ready != null) {
            val tabActive = com.gfs.iyango.feature.main.LocalMainTabActive.current
            var adjustTarget by remember(occurrences, detailRoot?.timetable?.id, detailRoot?.semester, tabActive) { mutableStateOf<TodayAdjustTarget?>(null) }
            val entryContext = todayDetailContext(detailDays.firstOrNull { it.date == occurrences.firstOrNull()?.date }, ready, occurrences.first().course)
            if (adjustTarget == null) CourseDetailDialog(
                source = detailSource,
                courses = occurrences.map { it.course },
                timeSlots = entryContext.timeSlots,
                displayedWeek = entryContext.week,
                paletteLight = displaySettings.paletteLight,
                paletteDark = displaySettings.paletteDark,
                occurrenceEntries = occurrences,
                onAdjust = {},
                onDelete = { _, _ -> },
                onRestoreOccurrence = { occurrence ->
                    val day = detailDays.firstOrNull { it.date == occurrence.date }
                    day?.timetable?.let { viewModel.restoreTemporaryDay(it.id, day.date) }
                },
                onEdit = { course -> onEditCourse(course.id) },
                onCopy = { course -> viewModel.closeDetail(); onCopyCourse(course.id) },
                onDeleteEntry = { course, scope, occurrence ->
                    viewModel.deleteCourse(
                        course,
                        scope,
                        occurrence?.let { entry -> detailDays.firstOrNull { it.date == entry.date } }?.let { day -> com.gfs.iyango.core.domain.CourseRules.weekOfDate(day.semester, day.date) } ?: ready.detailWeek,
                        occurrence?.anchorDate?.toString().orEmpty(),
                    )
                },
                onAdjustEntry = { course, occurrence ->
                    detailSource = null
                    adjustTarget = TodayAdjustTarget(
                        course = course,
                        sourceDate = occurrence?.anchorDate?.toString().orEmpty(),
                        occurrenceId = occurrence?.occurrenceId,
                    )
                },
                onDismiss = viewModel::closeDetail,
            )
            adjustTarget?.takeIf { tabActive }?.let { target ->
                val targetDay = detailDays.firstOrNull { day -> day.occurrences.any { it.occurrenceId == target.occurrenceId } }
                val targetContext = todayDetailContext(targetDay, ready, target.course)
                val targetSemester = targetContext.semester
                val targetWeek = targetContext.week
                com.gfs.iyango.feature.main.coursedetail.ScheduleAdjustmentDialog(
                    course = target.course,
                    displayedWeek = targetWeek,
                    semester = targetSemester,
                    totalWeeks = targetSemester?.totalWeeks ?: 20,
                    sectionCount = targetContext.sectionCount,
                    sourceOccurrenceId = target.occurrenceId,
                    queryTargetDay = viewModel::targetDay,
                    timeSlots = targetContext.timeSlots,
                    onApply = { targetWeek, day, startSection, endSection, temporary, confirmedDay ->
                        viewModel.applyAdjustment(
                            target.course,
                            targetWeek,
                            day,
                            startSection,
                            endSection,
                            target.sourceDate,
                            temporary,
                        confirmedDay,
                        )
                        adjustTarget = null
                    },
                    onDismiss = { adjustTarget = null },
                )
            }
        }
    }
}

@Composable
private fun TodayDetailResetEffect(
    timetableId: Long?,
    semester: com.gfs.iyango.core.model.Semester?,
    onReset: () -> Unit,
) {
    val tabActive = com.gfs.iyango.feature.main.LocalMainTabActive.current
    val currentOnReset by androidx.compose.runtime.rememberUpdatedState(onReset)
    LaunchedEffect(tabActive, timetableId, semester) { currentOnReset() }
}

/** Fixed chrome for every state; the initial inset scrolls away with ready content. */
@Composable
internal fun TodayPageLayout(
    state: TodayUiState,
    onProfileClick: () -> Unit,
    onOpenHomeSettings: () -> Unit = {},
    onOpenAiAssistant: () -> Unit = {},
    overlay: @Composable (PaddingValues) -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    GlassScaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        // Root tabs already receive a Dock footprint including navigation-bar insets.
        // GlassTopBar owns the status-bar inset; do not apply either system inset twice.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { GlassTopBar(
            title = "今日",
            actions = {
                GlassTopBarIconButton(onClick = onOpenAiAssistant) {
                    Icon(AiRobotIcon, contentDescription = "AI 助理")
                }
                GlassTopBarIconButton(onClick = onOpenHomeSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "首页设置")
                }
                GlassTopBarIconButton(onClick = onProfileClick) {
                    Icon(Icons.Filled.Person, contentDescription = "教务账号")
                }
            },
        ) },
        overlay = overlay,
        content = content,
    )
}

/** 就绪态内容。 */
@Composable
internal fun TodayReadyContent(
    state: TodayUiState.Ready,
    bottomPadding: Dp,
    topPadding: Dp,
    paletteLight: List<String>,
    paletteDark: List<String>,
    onCourseClick: (OccurrenceRow) -> Unit,
    onQuickTile: (String) -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenExams: () -> Unit,
    onProfileClick: () -> Unit,
    home: HomePreferences = HomePreferences(),
    timing: StateFlow<TodayCourseTiming>? = null,
    animateLayoutChanges: Boolean = false,
    onApplyMakeupWeekday: (LocalDate, Int) -> Unit = { _, _ -> },
    onRestoreTemporaryDay: (Long, LocalDate) -> Unit = { _, _ -> },
) {
    val animateItems = animateLayoutChanges && LocalMotionEnabled.current
    val visibleModules = remember(home) { home.sanitized().moduleOrder.filter { it !in home.hiddenModules } }
    val list = rememberLazyListState()
    val previousOrder = remember { arrayOf(visibleModules) }
    SideEffect {
        if (!animateLayoutChanges && previousOrder[0] != visibleModules) {
            // Key anchoring would keep the old first module on screen after saving,
            // even when it has moved far down the list.
            list.requestScrollToItem(0)
        }
        previousOrder[0] = visibleModules
    }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().courseDetailViewport(topPadding, bottomPadding),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            end = 16.dp,
            // Keep the first card below the header, while allowing it to scroll behind it.
            top = topPadding + 8.dp,
            bottom = bottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        visibleModules.forEach { id ->
            item(key = id) {
                val placement = if (animateItems) Modifier.animateItem(
                    fadeInSpec = tween(AppMotion.Content),
                    placementSpec = tween(AppMotion.Reorder),
                    fadeOutSpec = tween(AppMotion.Content),
                ) else Modifier
                Column(placement, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (id != HomeModule.PROFILE.id) SectionHeader(title = homeModuleTitle(id))
                    when (id) {
                        HomeModule.PROFILE.id -> ProfileCard(state, onProfileClick)
                        HomeModule.TODAY.id -> TodayCoursesSection(
                            state = state,
                            paletteLight = paletteLight,
                            paletteDark = paletteDark,
                            onCourseClick = onCourseClick,
                            onOpenSchedule = onOpenSchedule,
                            timing = timing,
                            onApplyMakeupWeekday = onApplyMakeupWeekday,
                            onRestoreTemporaryDay = onRestoreTemporaryDay,
                        )
                        HomeModule.SHORTCUTS.id -> QuickTiles(home.quickActions, onQuickTile)
                        HomeModule.CALENDAR.id -> MonthCalendarCard(state, paletteLight, paletteDark)
                        HomeModule.TOMORROW.id -> TomorrowSection(state, paletteLight, paletteDark, onCourseClick)
                        HomeModule.EXAMS.id -> UpcomingExamsSection(state.upcomingExams, onOpenExams)
                    }
                }
            }
        }
        if (home.hiddenModules.size == HomeModule.entries.size) item {
            Text("首页模块已全部隐藏，可从右上角首页设置重新开启", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

    }
}

/** 近期考试摘要（E01）：无数据时保留可点击入口，引导进入考试安排页。 */
@Composable
private fun UpcomingExamsSection(exams: List<UpcomingExam>, onClick: () -> Unit) {
    val brand = LocalBrand.current
    if (exams.isEmpty()) {
        GlassSurface(
            material = GlassMaterial.CONTENT,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        ) {
            Text(
                text = "近期没有考试安排 点击添加",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        exams.forEach { item ->
            GlassSurface(
                material = GlassMaterial.CONTENT,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = item.exam.subject,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = buildString {
                                append(item.exam.date)
                                if (item.exam.startTime.isNotBlank()) {
                                    append(" ")
                                    append(item.exam.startTime)
                                } else {
                                    append(" 时间待定")
                                }
                                if (item.exam.location.isNotBlank()) {
                                    append(" ")
                                    append(item.exam.location)
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(999.dp), color = brand.brandSoft) {
                        Text(
                            text = item.countdown,
                            style = MaterialTheme.typography.labelSmall,
                            color = brand.accent,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 资料卡（§2.1）。 */
@Composable
private fun ProfileCard(state: TodayUiState.Ready, onClick: () -> Unit) {
    val connection = state.connection?.takeUnless { it.status == SchoolConnectionStatus.LOGGED_OUT }
    val name = connection?.profile?.displayName?.takeIf { it.isNotBlank() }
        ?: connection?.maskedAccount?.takeIf { it.isNotBlank() } ?: "同学"
    GlassSurface(
        material = GlassMaterial.CONTENT,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "${greetingText(state.now.hour)}，$name",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                val holiday = state.todayHoliday
                if (holiday != null || state.isTemporaryDay) {
                    val badgeColor = when {
                        state.isTemporaryDay -> MaterialTheme.colorScheme.primary
                        holiday?.isOffDay == true -> Color(0xFF16A34A)
                        else -> Color(0xFFD97706)
                    }
                    val badgeLabel = when {
                        state.isTemporaryDay -> "调休补课中"
                        holiday?.isOffDay == true -> "假 ${holiday.name}"
                        holiday != null -> "调 ${holiday.name}"
                        else -> ""
                    }
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = badgeColor.copy(alpha = 0.14f),
                    ) {
                        Text(
                            text = badgeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "教务账号",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 今日课程区（§2.3/§2.4）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodayCoursesSection(
    state: TodayUiState.Ready,
    paletteLight: List<String> = emptyList(),
    paletteDark: List<String> = emptyList(),
    onCourseClick: (OccurrenceRow) -> Unit,
    onOpenSchedule: () -> Unit,
    timing: StateFlow<TodayCourseTiming>? = null,
    onApplyMakeupWeekday: (LocalDate, Int) -> Unit = { _, _ -> },
    onRestoreTemporaryDay: (Long, LocalDate) -> Unit = { _, _ -> },
) {
    val todayOccurrences = remember(state.todayRows) { state.todayRows.map { it.occurrence } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val holiday = state.todayHoliday
        if (holiday != null && !holiday.isOffDay && !state.isTemporaryDay) {
            GlassSurface(
                material = GlassMaterial.CONTENT,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "${holiday.name}调休补课",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFFD97706),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        (1..5).forEach { weekday ->
                            LiquidFilterChip(
                                selected = holiday.makeupWeekday == weekday,
                                onClick = { onApplyMakeupWeekday(state.today, weekday) },
                                label = { Text(com.gfs.iyango.core.model.DayLabels.shortLabel(weekday)) },
                            )
                        }
                    }
                }
            }
        } else if (state.isTemporaryDay) {
            GlassSurface(
                material = GlassMaterial.CONTENT,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "当日已使用临时替换课表",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    androidx.compose.material3.TextButton(
                        onClick = { onRestoreTemporaryDay(state.timetable.id, state.today) },
                    ) {
                        Text("恢复原课表")
                    }
                }
            }
        }
        if (timing == null) HomeTimingCard(state.timing, todayOccurrences) {
            val target = state.timing.current ?: state.timing.next
            if (target != null) onCourseClick(OccurrenceRow(target, TodayCourseTiming.status(target, state.timing.now))) else onOpenSchedule()
        }
        else HomeTimingCollector(timing, todayOccurrences, onCourseClick)
        state.todayRows.forEach { row ->
            CourseRow(row, paletteLight, paletteDark) { onCourseClick(row) }
        }
    }
}

@Composable
private fun HomeTimingCollector(
    timing: StateFlow<TodayCourseTiming>,
    dayOccurrences: List<CourseOccurrence>,
    onCourseClick: (OccurrenceRow) -> Unit,
) {
    val current by timing.collectAsStateWithLifecycle()
    HomeTimingCard(current, dayOccurrences) {
        val target = current.current ?: current.next
        if (target != null) onCourseClick(OccurrenceRow(target, TodayCourseTiming.status(target, current.now)))
    }
}

@Composable
internal fun HomeTimingCard(
    timing: TodayCourseTiming,
    dayOccurrences: List<CourseOccurrence> = emptyList(),
    onClick: () -> Unit = {},
) {
    val occurrence = timing.current ?: timing.next
    val transferWarning = remember(dayOccurrences, timing.current, timing.next) {
        BuildingTransferDetector.detectForTiming(dayOccurrences, timing)
    }
    val timeFormatter = remember { java.time.format.DateTimeFormatter.ofPattern("HH:mm") }
    val source = remember(occurrence?.occurrenceId, occurrence?.date) { MutableInteractionSource() }
    val indication = if (LocalMotionEnabled.current) LocalIndication.current else null
    val titleStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
    val detailSource = rememberCourseDetailSource(occurrence?.course, occurrence?.occurrenceId,
        glassContentFillColor(), MaterialTheme.colorScheme.onSurface, titleStyle, 16.dp)
    val focus = Triple(occurrence?.occurrenceId, occurrence?.date, timing.current != null)
    GlassSurface(material = GlassMaterial.CONTENT, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().then(detailSource.cardModifier)) {
        Column(Modifier.padding(16.dp).motionReveal(focus), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(homeTimingTitle(timing), style = MaterialTheme.typography.labelLarge, color = LocalBrand.current.accent,
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = listOfNotNull(homeTimingTitle(timing), occurrence?.course?.name).joinToString("，")
                })
            Column(Modifier.fillMaxWidth().clickable(
                enabled = occurrence != null, interactionSource = source, indication = indication,
                onClick = { detailSource.select(); onClick() },
            ).motionPress(source, enabled = occurrence != null),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (occurrence != null) {
                Text(occurrence.course.name, modifier = detailSource.titleModifier, style = titleStyle)
                val location = listOf(occurrence.course.classroom, occurrence.course.teacher).filter { it.isNotBlank() }.joinToString("\n")
                if (location.isNotEmpty()) Text(location, style = MaterialTheme.typography.bodyMedium)
                val start = TodayCourseTiming.startAt(occurrence)
                val end = TodayCourseTiming.endAt(occurrence)
                Text("${occurrence.date.monthValue}月${occurrence.date.dayOfMonth}日 ${start.format(timeFormatter)}-${end.format(timeFormatter)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (timing.current != null) "距下课" else "距上课", style = MaterialTheme.typography.bodyMedium)
                    // No live-region semantics: TalkBack reads this value on demand, never every second.
                    Text(homeCountdownText(timing), style = MaterialTheme.typography.headlineSmall.copy(
                        fontFeatureSettings = "tnum"), fontWeight = FontWeight.SemiBold)
                }
                if (timing.current != null) key(occurrence.occurrenceId, occurrence.date, occurrence.startMinute, occurrence.endMinute) {
                    HomeCourseProgress(timing)
                }
            }
            if (timing.overlappingCount > 1) Text("同时有 ${timing.overlappingCount} 门课程进行中", style = MaterialTheme.typography.bodySmall)
            if (timing.unknownTimeCount > 0) Text("另有 ${timing.unknownTimeCount} 门课程时间待确认", style = MaterialTheme.typography.bodySmall)
            if (timing.current != null && timing.next != null) {
                val next = requireNotNull(timing.next)
                Text("下一门：${next.course.name} ${next.date.monthValue}月${next.date.dayOfMonth}日 ${TodayCourseTiming.startAt(next).format(timeFormatter)}-${TodayCourseTiming.endAt(next).format(timeFormatter)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            transferWarning?.let { warning ->
                BuildingTransferPill(warning = warning, showGap = true)
            }
            }
        }
    }
}

@Composable
private fun HomeCourseProgress(timing: TodayCourseTiming) {
    val target = timing.progress.toFloat()
    val progress = if (LocalMotionEnabled.current) {
        val animation = remember { Animatable(target) }
        var previousTick by remember { mutableStateOf(timing.now) }
        val elapsed = java.time.Duration.between(previousTick, timing.now)
        // Only interpolate consecutive second ticks. A clock correction or a resumed
        // projection gets its real value on the very first frame.
        val consecutive = !elapsed.isNegative && elapsed <= java.time.Duration.ofSeconds(2)
        LaunchedEffect(timing.now, target) {
            previousTick = timing.now
            if (!consecutive) animation.snapTo(target)
            else if (animation.value != target) animation.animateTo(target, tween(AppMotion.Content))
        }
        animation.takeIf { consecutive }
    } else null
    androidx.compose.material3.LinearProgressIndicator(
        progress = { progress?.value ?: target }, modifier = Modifier.fillMaxWidth(),
    )
}

/** courseRow（§2.4）：时间列 48 + 圆点 + 名称/教室/教师 + chevron。 */
@Composable
private fun CourseRow(
    row: OccurrenceRow,
    paletteLight: List<String> = emptyList(),
    paletteDark: List<String> = emptyList(),
    onClick: () -> Unit,
) {
    val dark = isDarkTheme()
    val source = rememberCourseDetailSource(
        row.occurrence.course, row.occurrence.occurrenceId, glassContentFillColor(),
        MaterialTheme.colorScheme.onSurface, MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), 12.dp,
    )
    val swatch = CourseSwatch.forCourse(
        row.occurrence.course.color,
        row.occurrence.course.id,
        dark,
        paletteLight,
        paletteDark,
    )
    GlassSurface(
        material = GlassMaterial.CONTENT,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(source.cardModifier)
            .clickable { source.select(); onClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width(48.dp)) {
                Text(row.startText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    row.endText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(swatch.ink),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.occurrence.course.name,
                    modifier = source.titleModifier,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail = listOfNotNull(
                    row.occurrence.course.classroom.takeIf { it.isNotBlank() },
                    row.occurrence.course.teacher.takeIf { it.isNotBlank() },
                ).joinToString(" ")
                if (detail.isNotEmpty()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                row.transferWarning?.let { warning ->
                    Spacer(Modifier.height(4.dp))
                    BuildingTransferPill(warning = warning)
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BuildingTransferPill(
    warning: BuildingTransferWarning,
    showGap: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val dark = isDarkTheme()
    val fill = if (dark) Color(0xFFF59E0B).copy(alpha = 0.20f) else Color(0xFFD97706).copy(alpha = 0.14f)
    val ink = if (dark) Color(0xFFFCD34D) else Color(0xFFB45309)
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = fill,
        modifier = modifier,
    ) {
        Text(
            text = if (showGap) warning.detailLabel else warning.label,
            style = MaterialTheme.typography.labelSmall,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** Adaptive columns retain empty cells so odd final items never stretch. */
@Composable
internal fun QuickTiles(ids: List<String>, onTile: (String) -> Unit) {
    val shortcuts = ids.mapNotNull { id -> HomeShortcut.entries.firstOrNull { it.id == id } }
    val brand = LocalBrand.current
    val indication = if (LocalMotionEnabled.current) LocalIndication.current else null
    GlassSurface(material = GlassMaterial.CONTENT, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(12.dp)) {
            val columns = when { maxWidth >= 840.dp -> 4; maxWidth >= 560.dp -> 3; else -> 2 }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (shortcuts.isEmpty()) Text("未设置快捷入口 可在首页设置中添加", Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                shortcuts.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(columns) { index ->
                            val shortcut = row.getOrNull(index)
                            if (shortcut == null) Spacer(Modifier.weight(1f)) else key(shortcut.id) {
                                val source = remember { MutableInteractionSource() }
                                Row(
                                    Modifier.weight(1f).height(48.dp)
                                        .clickable(interactionSource = source, indication = null) { onTile(shortcut.id) }
                                        .motionPress(source).clip(RoundedCornerShape(12.dp)).background(brand.brandSoft)
                                        .indication(source, indication).padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = if (shortcut == HomeShortcut.AI_ASSISTANT) AiRobotIcon else Icons.Filled.Star,
                                        contentDescription = null,
                                        tint = brand.accent,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(homeShortcutTitle(shortcut.id), style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 月历卡（§2.6）。 */
@Composable
private fun MonthCalendarCard(
    state: TodayUiState.Ready,
    paletteLight: List<String> = emptyList(),
    paletteDark: List<String> = emptyList(),
) {
    val brand = LocalBrand.current
    val calendar = remember(state.month, state.today) { monthCalendar(state.month, state.today) }
    val dark = isDarkTheme()
    GlassSurface(material = GlassMaterial.CONTENT, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    monthTitleText(state.month),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(8.dp))
                Surface(shape = RoundedCornerShape(8.dp), color = brand.brandSoft) {
                    Text(
                        text = if (state.week > 0) "第 ${state.week} 周" else "假期",
                        style = MaterialTheme.typography.labelSmall,
                        color = brand.accent,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach { day ->
                    Text(
                        day,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            calendar.weeks.forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { date ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            if (date != null) {
                                val isToday = date == calendar.today
                                val hasCourse = date in state.busyDates
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isToday) brand.accent else Color.Transparent,
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            date.dayOfMonth.toString(),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (isToday) FontWeight.Bold else null,
                                            color = when {
                                                isToday -> brand.onBrand
                                                else -> MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(3.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (hasCourse) {
                                                    CourseSwatch.forCourse(
                                                        null,
                                                        date.dayOfMonth.toLong(),
                                                        dark,
                                                        paletteLight,
                                                        paletteDark,
                                                    ).ink
                                                } else {
                                                    Color.Transparent
                                                },
                                            ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 明日安排（§2.7）。 */
@Composable
private fun TomorrowSection(
    state: TodayUiState.Ready,
    paletteLight: List<String> = emptyList(),
    paletteDark: List<String> = emptyList(),
    onCourseClick: (OccurrenceRow) -> Unit,
) {
    if (state.tomorrowRows.isEmpty()) {
        GlassSurface(material = GlassMaterial.CONTENT, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "明天没有课，好好休息",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.tomorrowRows.forEach { row ->
                CourseRow(
                    row = row,
                    paletteLight = paletteLight,
                    paletteDark = paletteDark,
                    onClick = { onCourseClick(row) },
                )
            }
        }
    }
}

/** 亮色预览：月历卡 + 课程行静态样张。 */
@Preview(name = "今日 - 亮色", showBackground = true)
@Composable
private fun TodayLightPreview() {
    IYangoThemePreview(darkTheme = false) {
        Surface {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(title = "今日课程")
                CourseRow(
                    row = OccurrenceRow(
                        occurrence = com.gfs.iyango.core.domain.CourseOccurrence(
                            occurrenceId = "preview",
                            course = Course(id = 1, name = "数据结构", classroom = "A303", teacher = "李老师"),
                            date = LocalDate.of(2026, 10, 24),
                            startMinute = 10 * 60,
                            endMinute = 11 * 60 + 40,
                            isOverride = false,
                        ),
                        status = com.gfs.iyango.core.domain.CourseStatus.IN_PROGRESS,
                    ),
                    onClick = {},
                )
                MonthCalendarCard(
                    state = TodayUiState.Ready(
                        timetable = com.gfs.iyango.core.model.Timetable(id = 1, name = "我的课表"),
                        semester = null,
                        week = 7,
                        today = LocalDate.of(2026, 10, 24),
                        todayRows = emptyList(),
                        tomorrow = LocalDate.of(2026, 10, 25),
                        tomorrowRows = emptyList(),
                        busyDates = setOf(LocalDate.of(2026, 10, 26)),
                        month = java.time.YearMonth.of(2026, 10),
                        timeSlots = emptyList(),
                        detailCourses = emptyList(),
                    ),
                )
            }
        }
    }
}

/** 暗色预览。 */
@Preview(name = "今日 - 暗色", showBackground = true)
@Composable
private fun TodayDarkPreview() {
    IYangoThemePreview(darkTheme = true) {
        Surface {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(title = "明日安排")
                CourseRow(
                    row = OccurrenceRow(
                        occurrence = com.gfs.iyango.core.domain.CourseOccurrence(
                            occurrenceId = "preview",
                            course = Course(id = 2, name = "大学英语", classroom = "B201", teacher = "王老师"),
                            date = LocalDate.of(2026, 10, 25),
                            startMinute = 14 * 60,
                            endMinute = 15 * 60 + 40,
                            isOverride = false,
                        ),
                        status = com.gfs.iyango.core.domain.CourseStatus.NOT_STARTED,
                    ),
                    onClick = {},
                )
            }
        }
    }
}

/** 今日页单次调整目标：课程 + 该次发生的原始日期锚点。 */
private data class TodayAdjustTarget(
    val course: Course,
    val sourceDate: String,
    val occurrenceId: String? = null,
)
