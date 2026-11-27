package com.gfs.iyango.feature.main.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.domain.adjustmentExpected
import com.gfs.iyango.core.domain.AdjustmentReplaceResult
import com.gfs.iyango.core.domain.AdjustmentRepositoryPort
import com.gfs.iyango.core.domain.AppSettingsPort
import com.gfs.iyango.core.domain.ApplyTemporaryDay
import com.gfs.iyango.core.domain.AutoSilentCoordinatorPort
import com.gfs.iyango.core.domain.CancelOccurrence
import com.gfs.iyango.core.domain.CourseRules
import com.gfs.iyango.core.domain.EffectiveSchedulePort
import com.gfs.iyango.core.domain.HolidayMakeupCodec
import com.gfs.iyango.core.domain.HolidayRepositoryPort
import com.gfs.iyango.core.domain.MoveOccurrence
import com.gfs.iyango.core.domain.NoopAutoSilentCoordinator
import com.gfs.iyango.core.domain.NoopHolidayRepository
import com.gfs.iyango.core.domain.RefreshCoordinatorPort
import com.gfs.iyango.core.domain.RefreshOutcome
import com.gfs.iyango.core.domain.RefreshResource
import com.gfs.iyango.core.domain.ScheduleDataPort
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.domain.WallpaperStore
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.DayLabels
import com.gfs.iyango.core.model.OccurrenceAdjustment
import com.gfs.iyango.core.model.ScheduleDisplaySettings
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.TemporaryDay
import com.gfs.iyango.core.model.TimeSlot
import com.gfs.iyango.core.model.Timetable
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 删除范围（ui-baseline §4〔参 confirmRemoveCourse 三选〕）。
 */
enum class CourseDeleteScope {
    /** 仅取消本周这次：删除该周的单次调整记录。 */
    CANCEL_THIS_WEEK,

    /** 删除这条安排的所有周次：删除课程本体及其全部单次调整。 */
    DELETE_ARRANGEMENT,

    /** 删除整门课的所有安排：删除同名同教师的全部课程（含调整记录）。 */
    DELETE_WHOLE_COURSE,
}

/**
 * 课表页 ViewModel（ui-baseline §3 周网格）。
 *
 * 数据来源约束：每周的真实实例一律由 [ResolveScheduleOccurrences]（周一..周日）解析，
 * 本类不得用 [Course.dayOfWeek] 自行推导"某天有哪些课"。非本周幽灵块（被单双周/轮换/
 * 所选周规则跳过、但周区间命中的课程）同样只经 [CourseRules.forWeek] + [CourseRules.occursInWeek]
 * 这两个唯一判定入口产生，用于 38% 透明度降权展示。
 */
@HiltViewModel
class ScheduleViewModel constructor(
    private val timetableRepository: TimetableRepositoryPort,
    private val scheduleData: ScheduleDataPort,
    private val adjustmentRepository: AdjustmentRepositoryPort,
    private val effectiveSchedule: EffectiveSchedulePort,
    private val appSettings: AppSettingsPort,
    private val wallpaperStore: WallpaperStore,
    private val refreshCoordinator: RefreshCoordinatorPort,
    private val clock: Clock,
    private val examRepository: com.gfs.iyango.core.domain.ExamRepositoryPort,
    private val termBrowser: TermBrowseController? = null,
    private val browseDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
    private val holidayRepository: HolidayRepositoryPort = NoopHolidayRepository,
    private val autoSilentCoordinator: AutoSilentCoordinatorPort = NoopAutoSilentCoordinator,
) : ViewModel() {
    @Inject constructor(
        timetableRepository: TimetableRepositoryPort,
        scheduleData: ScheduleDataPort,
        adjustmentRepository: AdjustmentRepositoryPort,
        effectiveSchedule: EffectiveSchedulePort,
        appSettings: AppSettingsPort,
        wallpaperStore: WallpaperStore,
        refreshCoordinator: RefreshCoordinatorPort,
        clock: Clock,
        examRepository: com.gfs.iyango.core.domain.ExamRepositoryPort,
        auth: com.gfs.iyango.core.domain.SchoolAuthPort,
        school: com.gfs.iyango.core.domain.SchoolTimetablePort,
        cache: com.gfs.iyango.core.domain.TermBrowseCachePort,
        holidayRepository: HolidayRepositoryPort,
        autoSilentCoordinator: AutoSilentCoordinatorPort,
    ) : this(
        timetableRepository = timetableRepository,
        scheduleData = scheduleData,
        adjustmentRepository = adjustmentRepository,
        effectiveSchedule = effectiveSchedule,
        appSettings = appSettings,
        wallpaperStore = wallpaperStore,
        refreshCoordinator = refreshCoordinator,
        clock = clock,
        examRepository = examRepository,
        holidayRepository = holidayRepository,
        autoSilentCoordinator = autoSilentCoordinator,
    ) {
        browser = TermBrowseController(school,cache,viewModelScope)
        observeBrowser(auth)
    }
    private var browser: TermBrowseController? = termBrowser
    private val browseState = MutableStateFlow(TermBrowseController.State())
    val termBrowsing: StateFlow<TermBrowseController.State> = browseState
    val autoSilentExemptKeys: StateFlow<Set<String>> = autoSilentCoordinator.status
        .map { it.exemptCourseKeys }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun toggleCourseAutoSilentExempt(course: Course) {
        val key = com.gfs.iyango.core.domain.CourseReminderPlanner.reminderKey(course)
        val currentlyExempt = key in autoSilentExemptKeys.value
        viewModelScope.launch {
            runCatching { autoSilentCoordinator.setCourseExempt(key, !currentlyExempt) }
        }
    }
    init {
        termBrowser?.let { supplied ->
            viewModelScope.launch { supplied.state.collect { browseState.value = it } }
        }
    }
    private fun observeBrowser(auth: com.gfs.iyango.core.domain.SchoolAuthPort) {
        viewModelScope.launch { browser!!.state.collect { browseState.value = it } }
        viewModelScope.launch {
            auth.observeConnections().collect { connections ->
                val active = connections.firstOrNull { it.status == com.gfs.iyango.core.model.SchoolConnectionStatus.ACTIVE }
                val expired = connections.firstOrNull { it.status == com.gfs.iyango.core.model.SchoolConnectionStatus.EXPIRED }
                browser!!.accountChanged(active ?: expired)
            }
        }
    }
    fun loadSchoolTerms() { browser?.loadTerms() }
    fun consumeBrowseNotice() { browser?.consumeRefreshNotice() }
    fun browseTerm(term: String) {
        if (localPull.value != null) cancelPull()
        browser?.select(term)
    }
    fun returnToCurrentTimetable() {
        if (localPull.value != null) cancelPull()
        browser?.returnToCurrent()
    }
    private fun browsing() = browseState.value.selected != null


    private val manualRefreshing = MutableStateFlow(false)

    /** 一次性提示（同步结果 / 调课校验失败）；Screen 以顶部胶囊呈现后消费。 */
    val message = MutableStateFlow<String?>(null)

    /** 一次性会话过期事件：Screen 观察到 true 后跳转教务账号页并复位。 */
    val sessionExpired = MutableStateFlow(false)

    fun consumeMessage() {
        message.value = null
    }

    fun consumeSessionExpired() {
        sessionExpired.value = false
    }

    private val pullSyncRefreshing = MutableStateFlow(false)
    private var pullSyncJob: kotlinx.coroutines.Job? = null
    private var pullFeedbackVersion = 0L
    private var pullFeedbackEnabled = false
    val localPull = MutableStateFlow<SchedulePullRequest?>(null)
    val pullMessage = MutableStateFlow<String?>(null)

    /** User-requested synchronization owns the indicator until its local commit completes. */
    val refreshing: StateFlow<Boolean> = combine(
        manualRefreshing,
        pullSyncRefreshing,
    ) { manual, pull -> manual || pull }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun pullRefresh() {
        if (localPull.value != null || pullSyncJob?.isActive == true || manualRefreshing.value) return
        refreshClock()
        pullFeedbackVersion += 1
        val feedbackVersion = pullFeedbackVersion
        pullFeedbackEnabled = true
        pullMessage.value = null
        message.value = null
        browser?.returnToCurrent()
        selectWeek(null)
        localPull.value = SchedulePullRequest(pullFeedbackVersion, weekSelection.value.version)
        pullSyncRefreshing.value = true
        var job: kotlinx.coroutines.Job? = null
        job = viewModelScope.launch {
            try {
                val result = refreshCoordinator.refresh(RefreshResource.TIMETABLE)
                if (feedbackVersion != pullFeedbackVersion || !pullFeedbackEnabled) return@launch
                pullMessage.value = when {
                    result.sessionExpired && result.message == "请先登录阳光学院教务系统" -> result.message
                    result.sessionExpired -> "教务登录已过期，本地课表已保留"
                    result.outcome == RefreshOutcome.SUCCESS -> SCHOOL_SYNC_SUCCESS
                    else -> "教务同步未完成" +
                        result.message.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty() + "，本地课表已保留"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (feedbackVersion == pullFeedbackVersion && pullFeedbackEnabled) {
                    pullMessage.value = "教务同步未完成，本地课表已保留"
                }
            } finally {
                if (feedbackVersion == pullFeedbackVersion) {
                    pullSyncRefreshing.value = false
                }
                if (pullSyncJob === job) {
                    pullSyncJob = null
                }
            }
        }
        pullSyncJob = job
    }

    /** Grid navigation completion is not a data refresh or a successful database write. */
    fun completePull(id: Long) {
        if (localPull.value?.id != id) return
        localPull.value = null
    }

    fun cancelPull() {
        if (!pullFeedbackEnabled && localPull.value == null && pullSyncJob?.isActive != true) return
        pullFeedbackVersion += 1
        pullFeedbackEnabled = false
        localPull.value = null
        pullMessage.value = null
        pullSyncRefreshing.value = false
        pullSyncJob?.cancel()
        pullSyncJob = null
    }

    fun consumePullMessage() { pullMessage.value = null }

    /**
     * 刷新正在查看的课表（选择器中的刷新/重试保持当前浏览学期）：
     * 成功后数据经 Room 流自动重载；不论成败都提示结果文案；会话过期时转教务账号页；
     * 未绑定账号时不显示成功；异常时保留原课表并提示稍后重试。
     */
    fun refresh() {
        refreshClock()
        if (browsing()) {
            if (state.value is ScheduleUiState.Error) browseProjectionRetry.value += 1
            browser?.refresh()
            return
        }
        cancelPull()
        refreshCurrentTimetable()
    }

    private fun refreshCurrentTimetable() {
        if (manualRefreshing.value) return
        manualRefreshing.value = true
        message.value = null
        val feedbackVersion = pullFeedbackVersion
        viewModelScope.launch {
            try {
                val result = refreshCoordinator.refresh(RefreshResource.TIMETABLE)
                if (feedbackVersion != pullFeedbackVersion) return@launch
                if (result.sessionExpired) {
                    message.value = result.message.ifEmpty { "登录状态已失效，请重新登录" }
                    sessionExpired.value = true
                } else {
                    message.value = if (result.outcome == RefreshOutcome.SUCCESS) SCHOOL_SYNC_SUCCESS
                        else result.message.ifEmpty { "刷新失败，原课表已保留" }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (feedbackVersion == pullFeedbackVersion) message.value = "同步未完成，原课表已保留，请稍后重试"
            } finally {
                manualRefreshing.value = false
            }
        }
    }

    /** One atomic navigation intent; null follows today's week. */
    private data class WeekSelection(val week: Int? = null,
        val direction: WeekFlipDirection = WeekFlipDirection.JUMP, val version: Long = 0)
    private val weekSelection = MutableStateFlow(WeekSelection())
    private fun selectWeek(week: Int?, direction: WeekFlipDirection = WeekFlipDirection.JUMP) {
        weekSelection.value = WeekSelection(week, direction, weekSelection.value.version + 1)
    }

    private val clockTrigger = MutableStateFlow(0L)

    fun refreshClock() {
        clockTrigger.value += 1
    }

    /** 30 秒心跳 + 前台/跨日显式刷新：驱动运行状态徽标与当前时间指示器（ui-baseline §3）。 */
    private val nowTick: StateFlow<LocalDateTime> = combine(
        flow {
            while (true) {
                emit(Unit)
                delay(NOW_TICK_MILLIS)
            }
        },
        clockTrigger,
    ) { _, _ ->
        LocalDateTime.now(clock)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LocalDateTime.now(clock),
    )

    /** 上一次观察到的当前课表 id；用于检测课表切换并复位周覆盖。 */
    private var lastTimetableId: Long? = null
    private var contextVersion = 0L

    /** 重试计数：变化时重启派生链（错误态的"重试"入口）。 */
    private val retryTick = MutableStateFlow(0)

    /** 最近一次观察到的学期快照（调整冲突检测用）。 */
    private var latestSemester: Semester? = null

    /** 全部课表（临时整天来源选择用）。 */
    val timetables: StateFlow<List<Timetable>> = timetableRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前课表的临时整天记录。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val temporaryDays: StateFlow<List<TemporaryDay>> = timetableRepository.observeCurrent()
        .flatMapLatest { table ->
            if (table == null) flowOf(emptyList()) else adjustmentRepository.observeTemporaryDays(table.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前课表的有效显示配置：按课表覆盖优先，否则全局默认（F09）。 */
    val displaySettings: StateFlow<ScheduleDisplaySettings> = combine(
        timetableRepository.observeCurrent(),
        appSettings.displaySettings,
    ) { table, bundle -> table?.let { bundle.forTimetable(it.id) } ?: bundle.global }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ScheduleDisplaySettings(),
        )

    /** 课表有效背景：专属亮暗优先，均不可用时跟随全局；解析后再回退以兼容缺失文件。 */
    val wallpaper: StateFlow<ScheduleWallpaper> = appSettings.appPreferences
        .map { prefs ->
            val light = wallpaperStore.resolve(prefs.timetableWallpaperPath)
            val dark = wallpaperStore.resolve(prefs.timetableWallpaperPathDark)
            val globalLight = wallpaperStore.resolve(prefs.wallpaperPath)
            val globalDark = wallpaperStore.resolve(prefs.wallpaperPathDark)
            ScheduleWallpaper(
                path = light ?: dark ?: globalLight ?: globalDark,
                darkPath = dark ?: light ?: globalDark ?: globalLight,
                scrim = prefs.wallpaperScrim,
                blur = prefs.wallpaperBlur,
                hasDedicatedWallpaper = light != null || dark != null,
            )
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduleWallpaper())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val currentState: StateFlow<ScheduleUiState> = combine(
        // 课表切换（id 变化）时清掉手动周覆盖，避免沿用上一份课表的周次导致错位。
        timetableRepository.observeCurrent().onEach { timetable ->
            val id = timetable?.id
            if (id != lastTimetableId) {
                lastTimetableId = id
                contextVersion += 1
                selectWeek(null)
            }
        },
        retryTick,
    ) { timetable, _ -> timetable }
        .flatMapLatest { timetable -> derive(timetable) }
        .combine(nowTick) { state, now ->
            if (state is ScheduleUiState.Ready && state.data.now != now) {
                state.copy(data = state.data.copy(now = now), weeks = state.weeks.map { it.copy(now = now) })
            } else {
                state
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ScheduleUiState.FirstLoading,
        )

    /** Coherent native projection for local fallback; never a previously browsed error. */
    val currentTimetableState: StateFlow<ScheduleUiState> get() = currentState

    private data class BrowseProjectionInput(
        val account: String,
        val connectionId: String?,
        val term: String?,
        val source: com.gfs.iyango.core.model.SchoolTimetableData?,
        val selectionRevision: Long,
        val retry: Int,
    )

    private data class BrowseProjectionResult(
        val input: BrowseProjectionInput,
        val projection: BrowsedSemesterProjection? = null,
        val error: Boolean = false,
    )

    private val browseProjectionRetry = MutableStateFlow(0)
    private val browseProjectionCache = TermBrowseProjectionCache()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val browsedProjection = combine(browseState, browseProjectionRetry) { browse, retry ->
        BrowseProjectionInput(
            account = browse.account?.let {
                listOf(it.schoolId, it.accountSubject.ifBlank { it.id }).joinToString("\u0000")
            }.orEmpty(),
            connectionId = browse.account?.id,
            term = browse.selected,
            source = browse.data?.copy(fetchedAt = 0, rawPayload = "", warnings = emptyList()),
            selectionRevision = browse.revision,
            retry = retry,
        )
    }.distinctUntilChanged().flowOn(browseDispatcher).flatMapLatest { input ->
        if (input.term == null || input.source == null) {
            flowOf(BrowseProjectionResult(input))
        } else {
            timetableRepository.observeAll().map { tables ->
                tables.firstOrNull {
                    it.connectionId == input.connectionId && it.schoolTerm == input.term
                }?.id
            }.distinctUntilChanged().flatMapLatest { tableId ->
                if (tableId == null || input.source.startDate.isNotBlank()) flowOf(null)
                else scheduleData.observeSemester(tableId).map { it?.startDate }
            }.distinctUntilChanged().map { localStart ->
                val source = if (input.source.startDate.isBlank() && localStart != null) {
                    input.source.copy(startDate = localStart.toString())
                } else input.source
                BrowseProjectionResult(
                    input,
                    browseProjectionCache.get(input.account, source, nowTick.value, scheduleData::timeRailRows),
                )
            }.catch { error ->
                if (error is CancellationException) throw error
                emit(BrowseProjectionResult(input, error = true))
            }.flowOn(browseDispatcher)
        }
    }

    val state: StateFlow<ScheduleUiState> = combine(
        currentState, browseState, browsedProjection, nowTick,
    ) { current, browse, result, now ->
        when {
            browse.selected == null -> current
            browse.data == null -> if (browse.loading) ScheduleUiState.FirstLoading
                else ScheduleUiState.Error(browse.error ?: "该学期课表暂不可用", true)
            result.input.selectionRevision != browse.revision -> ScheduleUiState.FirstLoading
            result.error -> ScheduleUiState.Error("课表读取失败，请重试", true)
            result.projection == null -> ScheduleUiState.FirstLoading
            else -> result.projection.render(browse.week, browse.revision, browse.navigationVersion, now)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduleUiState.FirstLoading)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun derive(
        timetable: Timetable?,
    ) = when (timetable) {
        null -> flowOf<ScheduleUiState>(ScheduleUiState.NoTimetable)
        else -> combine(
            scheduleData.observeSemester(timetable.id),
            scheduleData.observeTimeSlots(timetable.id),
            scheduleData.observeCourses(timetable.id),
            adjustmentRepository.observeAdjustments(timetable.id),
            adjustmentRepository.observeTemporaryDays(timetable.id),
        ) { semester, slots, courses, adjustments, temporaryDays ->
            TimetableSnapshot(semester, slots, courses, adjustments, temporaryDays)
        }
            .flatMapLatest { snapshot ->
                if (latestSemester != snapshot.semester) {
                    contextVersion += 1
                    selectWeek(null)
                }
                latestSemester = snapshot.semester
                val semester = snapshot.semester
                if (semester == null) flowOf<ScheduleUiState>(ScheduleUiState.NoSemester)
                else effectiveSchedule.observeSelectedRange(semester.startDate..
                    semester.startDate.plusDays(semester.totalWeeks.coerceIn(1, 30) * 7L - 1))
                    .combine(examRepository.observeInRange(semester.startDate.minusDays(2),
                        semester.startDate.plusDays(semester.totalWeeks.coerceIn(1,30) * 7L + 1))) { days, exams -> days to exams }
                    .combine(nowTick.map { it.toLocalDate() }.distinctUntilChanged()) { (days, exams), date ->
                        val density = ScheduleEffectiveProjection.weekDayCounts(days, semester.startDate, semester.totalWeeks)
                        val slotMasks = ScheduleEffectiveProjection.weekSlotMasks(days, semester.startDate, semester.totalWeeks)
                        (1..semester.totalWeeks.coerceIn(1, 30)).map { selected ->
                            buildScheduleWeekData(timetable, semester, snapshot.slots, snapshot.courses,
                                scheduleData::timeRailRows, WeekFlipDirection.JUMP, LocalDateTime.of(date, nowTick.value.toLocalTime()), days, selected, density, slotMasks)
                                .let { data -> data.copy(contextVersion = contextVersion,
                                    exams = ExamWeekProjection.project(exams, data.weekStart, clock.zone, data.sectionCount, data::slotsForDay)) }
                        }
                    }
                    .combine(holidayRepository.holidays) { weeks, holidays ->
                        if (holidays.isEmpty()) weeks else weeks.map { it.copy(holidays = holidays) }
                    }
                    .combine(weekSelection) { weeks, selection ->
                        val selected = selection.week ?: weeks.first().currentWeek.takeIf { it > 0 } ?: 1
                        ScheduleUiState.Ready(weeks[(selected - 1).coerceIn(weeks.indices)].copy(
                            flipDirection = selection.direction, navigationVersion = selection.version), weeks)
                    }
            }
            .catch { e ->
                emit(ScheduleUiState.Error(message = e.message ?: "课表读取失败", canRetry = true))
            }
    }

    // ---------------- 周切换 ----------------

    /** 切到上一周；到学期边界停止。 */
    fun previousWeek() = shiftWeek(-1, WeekFlipDirection.PREVIOUS)

    /** 切到下一周；到学期边界停止。 */
    fun nextWeek() = shiftWeek(+1, WeekFlipDirection.NEXT)

    /** 经周选择器跳到 [week]（纯淡入动效）。 */
    fun pickWeek(week: Int) {
        if (localPull.value != null) cancelPull()
        if (browsing()) { browser?.pickWeek(week); return }
        val upper = currentTotalWeeks()
        if (week !in 1..upper) {
            return
        }
        selectWeek(week)
    }

    /** Ignore a settled callback from a discarded root/semester/page session. */
    fun pickPagerWeek(context: ScheduleContext, week: Int,
        publishedNavigationVersion: Long = (state.value as? ScheduleUiState.Ready)?.data?.navigationVersion ?: -1) {
        val data = (state.value as? ScheduleUiState.Ready)?.data ?: return
        if (browsing()) { if (data.context == context && data.navigationVersion == publishedNavigationVersion) pickWeek(week); return }
        if (data.context != context || lastTimetableId != context.timetableId || contextVersion != context.version) return
        val intent = weekSelection.value
        if (intent.version != data.navigationVersion || publishedNavigationVersion != intent.version) return
        if (data.displayedWeek != week) pickWeek(week)
    }

    /** Explicit drag token; deliberately independent of the detail dialog's actionDays. */
    suspend fun confirmDrag(candidate: ScheduleDropCandidate): String? {
        if (browsing()) return "学期浏览为只读"
        val data = (state.value as? ScheduleUiState.Ready)?.data
        if (data?.context != candidate.source.context || lastTimetableId != data.timetableId || contextVersion != data.contextVersion) return "课表已变化，请重新拖动"
        val source = candidate.source.expected
        val slot = candidate.slot
        val replacement = MoveOccurrence.create(source.baseCourse.timetableId, source.baseCourse.id,
            source.occurrence.anchorDate.toString(), candidate.targetDay.date, slot.startSection, slot.endSection)
        return try {
            when (val result = adjustmentRepository.replaceOccurrence(source.copy(targetDay = candidate.targetDay), replacement)) {
                AdjustmentReplaceResult.Saved -> null
                AdjustmentReplaceResult.Stale -> "课程或目标日期已变化，请重新拖动"
                is AdjustmentReplaceResult.Rejected -> result.message
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { "调整失败，原安排已保留，请重试" }
    }

    /** 回到本周（复位周偏移）。 */
    fun backToCurrentWeek() {
        if (localPull.value != null) cancelPull()
        if (browsing()) {
            val currentWeek = (state.value as? ScheduleUiState.Ready)?.data?.currentWeek
            browser?.pickWeek(currentWeek?.takeIf { it > 0 } ?: 1)
            return
        }
        selectWeek(null)
    }

    /** 错误重试：重启派生链（错误态的"重试"入口）。 */
    fun retry() {
        if (browsing()) {
            browseProjectionRetry.value += 1
            browser?.refresh()
            return
        }
        retryTick.value += 1
    }

    /** 周次平移：以上一次展示周为基准，出界自动收敛到 1..总周数。 */
    private fun shiftWeek(delta: Int, direction: WeekFlipDirection) {
        if (localPull.value != null) cancelPull()
        val current = (state.value as? ScheduleUiState.Ready)?.data ?: return
        val upper = current.totalWeeks.takeIf { it > 0 } ?: DEFAULT_TOTAL_WEEKS
        val target = (current.displayedWeek + delta).coerceIn(1, upper)
        if (target == current.displayedWeek) {
            return
        }
        if (browsing()) browser?.pickWeek(target) else selectWeek(target, direction)
    }

    private fun currentTotalWeeks(): Int {
        val semester = latestSemester ?: return 0
        return semester.totalWeeks
    }

    // ---------------- 周网格构建 ----------------

    private var actionDays: List<com.gfs.iyango.core.domain.EffectiveScheduleDay> = emptyList()
    fun captureDetail(data: WeekScheduleData) { actionDays = data.effectiveDays }

    suspend fun targetDay(date: LocalDate) = effectiveSchedule.resolveDay(date)

    fun restoreTemporaryDay(ownerTimetableId: Long, date: LocalDate) {
        if (browsing()) return
        viewModelScope.launch {
            try { adjustmentRepository.deleteTemporaryDay(ownerTimetableId, date.toString()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message.value = "恢复未完成，请重试" }
        }
    }

    private fun temporaryCourse(course: Course, sourceDate: String): Boolean =
        (state.value as? ScheduleUiState.Ready)?.data?.effectiveDays.orEmpty()
            .flatMap { it.occurrences }.any { it.isTemporary && it.course == course && (sourceDate.isBlank() || it.anchorDate.toString() == sourceDate) }

    // ---------------- 删除与单次调整 ----------------

    /**
     * 按范围删除课程（详情弹层三选 → AlertDialog 确认后调用）。
     *
     * @param course 被操作的课程（网格块点击时的那条）。
     * @param scope 删除范围。
     * @param displayedWeek 当前展示周（"仅取消本周这次"依据）。
     * @param sourceDate 该次发生的原始日期（yyyy-MM-dd）；空串时按展示周 + 课程星期推导。
     */
    fun deleteCourse(
        course: Course,
        scope: CourseDeleteScope,
        displayedWeek: Int,
        sourceDate: String = "",
    ) {
        if (browsing()) return
        if (temporaryCourse(course, sourceDate)) { message.value = "请先恢复该日课表后调整"; return }
        viewModelScope.launch {
            when (scope) {
                CourseDeleteScope.CANCEL_THIS_WEEK -> cancelOccurrence(course, displayedWeek, sourceDate)

                CourseDeleteScope.DELETE_ARRANGEMENT -> {
                    deleteWithAdjustments(course.timetableId, course.id)
                }

                CourseDeleteScope.DELETE_WHOLE_COURSE -> {
                    scheduleData.observeCourses(course.timetableId).first()
                        .filter {
                            it.overrideOfId == 0L &&
                                it.name == course.name &&
                                it.teacher == course.teacher
                        }
                        .forEach { deleteWithAdjustments(course.timetableId, it.id) }
                }
            }
        }
    }

    /**
     * "仅取消本周这次"：以原始发生日期为锚点写入 CANCEL（F08）。
     *
     * 同一锚点若已有 MOVE/CANCEL，先整组移除再写入，保证一次发生只有一条有效调整；
     * 取消不删除任何课程行，可通过"恢复本周调整"撤回。
     */
    private suspend fun cancelOccurrence(course: Course, displayedWeek: Int, sourceDate: String) {
        val timetableId = course.timetableId
        val baseId = if (course.overrideOfId > 0) course.overrideOfId else course.id
        if (baseId <= 0) return
        val anchor = resolveAnchor(sourceDate, course, displayedWeek) ?: return
        saveOccurrenceAdjustment(course, anchor,
            CancelOccurrence.create(
                timetableId = timetableId,
                sourceCourseId = baseId,
                sourceDate = anchor.toString(),
            )
        )
    }

    /** 删除课程本体、全部就地调整记录以及指向它的临时调整。 */
    private suspend fun deleteWithAdjustments(timetableId: Long, courseId: Long) {
        scheduleData.observeCourses(timetableId).first().filter { it.overrideOfId == courseId }.forEach {
            scheduleData.deleteCourse(it.id)
        }
        adjustmentRepository.observeAdjustments(timetableId).first()
            .filter { it.sourceCourseId == courseId }
            .map { it.operationGroupId }
            .distinct()
            .forEach { adjustmentRepository.deleteGroup(it) }
        scheduleData.deleteCourse(courseId)
    }

    /**
     * 应用课程调整（F08）。
     *
     * - [temporary] = true（默认）：以原始发生为锚点写入 MOVE，保留原安排可恢复；
     * - [temporary] = false：永久调课，直接改写原课程安排（后续所有适用周生效），
     *   规则与鸿蒙 ScheduleAdjustmentService.move 一致——跨周或单次安排不允许永久调整。
     *
     * @param course 被调整的课程（可以是基础课或某条发生实例）。
     * @param targetWeek 目标周。
     * @param dayOfWeek 目标星期（1=周一 … 7=周日）。
     * @param startSection 目标起始节次。
     * @param endSection 目标结束节次。
     * @param sourceDate 该次发生的原始日期（yyyy-MM-dd）；空串时按展示周 + 课程星期推导。
     * @param temporary 是否仅本周生效（临时调整）。
     */
    fun applyAdjustment(
        course: Course,
        targetWeek: Int,
        dayOfWeek: Int,
        startSection: Int,
        endSection: Int,
        sourceDate: String = "",
        temporary: Boolean = true,
        confirmedTarget: com.gfs.iyango.core.domain.EffectiveScheduleDay? = null,
    ) {
        if (browsing()) return
        if (temporaryCourse(course, sourceDate)) { message.value = "请先恢复该日课表后调整"; return }
        viewModelScope.launch {
            val timetableId = course.timetableId
            val data = (state.value as? ScheduleUiState.Ready)?.data ?: return@launch
            // 调整始终指向基础课；对发生实例再调整时以它的锚点为源。
            val baseId = if (course.overrideOfId > 0) course.overrideOfId else course.id
            if (baseId <= 0) return@launch
            val semester = data.semester ?: return@launch
            val anchor = resolveAnchor(sourceDate, course, data.displayedWeek) ?: return@launch
            val targetDate = confirmedTarget?.date ?: semester.startDate
                .plusDays((targetWeek.coerceAtLeast(1) - 1) * 7L + (dayOfWeek.coerceIn(1, 7) - 1))

            if (!temporary) {
                val warning = try { com.gfs.iyango.core.domain.AdjustmentTargetPolicy.warning(effectiveSchedule.resolveDay(targetDate),course.timetableId) }
                    catch(cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch(failure: Exception) { message.value = "调整失败，请重试"; return@launch }
                if(warning != null) { message.value = warning; return@launch }

                applyPermanentAdjustment(course, data.displayedWeek, targetWeek, dayOfWeek, startSection, endSection)
                return@launch
            }
            saveOccurrenceAdjustment(course, anchor,
                MoveOccurrence.create(
                    timetableId = timetableId,
                    sourceCourseId = baseId,
                    sourceDate = anchor.toString(),
                    targetDate = targetDate,
                    targetStartSection = startSection,
                    targetEndSection = endSection,
                ), confirmedTarget,
            )
        }
    }

    /**
     * 永久调课：直接改写原课程安排，节次时间回退为按作息表计算
     * （与鸿蒙 place() 一致：清空自定义钟点与指定日期）。
     */
    private suspend fun applyPermanentAdjustment(
        course: Course,
        displayedWeek: Int,
        targetWeek: Int,
        dayOfWeek: Int,
        startSection: Int,
        endSection: Int,
    ) {
        val source = scheduleData.observeCourses(course.timetableId).first()
            .firstOrNull { it.id == course.id } ?: run {
            message.value = "课程安排已变化，请重试"
            return
        }
        if (source != course) { message.value = "课程安排已变化，请重新打开详情确认"; return }
        if (!source.enabled || targetWeek < 1 || dayOfWeek !in 1..7 ||
            startSection < 1 || endSection < startSection
        ) {
            message.value = "请选择有效的调整时间"
            return
        }
        if (targetWeek != displayedWeek || source.timeMode == 2 || source.overrideWeek > 0) {
            message.value = "跨周或单次安排请选择仅本周生效"
            return
        }
        val sourceSemester = scheduleData.observeSemester(course.timetableId).first()
        val displayDate = (state.value as? ScheduleUiState.Ready)?.data?.weekStart ?: return
        val sourceWeek = CourseRules.weekOfDate(sourceSemester, displayDate)
        if (source.timeMode != 2 && !CourseRules.occursInWeek(source, sourceWeek)) {
            message.value = "所选周次没有这条课程安排"
            return
        }
        runCatching {
            scheduleData.saveCourse(
                source.copy(
                    dayOfWeek = dayOfWeek.coerceIn(1, 7),
                    startSection = startSection,
                    endSection = endSection,
                    timeMode = 0,
                    startTime = "",
                    endTime = "",
                    date = "",
                ),
            )
        }.onFailure { failure ->
            message.value = failure.message?.takeIf { it.isNotBlank() } ?: "调整课程失败，原安排已保留"
        }
    }

    /** 恢复展示周的全部临时调整与临时整天。 */
    fun restoreDisplayedWeek() {
        if (browsing()) return
        val data = (state.value as? ScheduleUiState.Ready)?.data ?: return
        restoreWeek(data)
    }

    private fun restoreWeek(data: WeekScheduleData) {
        val displayed = data.effectiveDays.filter { it.date in data.weekStart..data.weekEnd }
        viewModelScope.launch {
            try {
                val adjustments = displayed.mapNotNull { it.timetable?.id }.distinct().flatMap {
                    adjustmentRepository.observeAdjustments(it).first()
                }
                adjustmentsToRestore(adjustments, displayed).forEach {
                    adjustmentRepository.deleteAnchor(it.timetableId, it.sourceCourseId, it.sourceDate)
                }
                displayed.forEach { day -> day.timetable?.let {
                    adjustmentRepository.deleteTemporaryDay(it.id, day.date.toString())
                } }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { message.value = "恢复未完成，请重试" }
        }
    }

    /** Resolve the selected date's owner; the root is only a navigation context. */
    fun applyTemporaryDay(date: LocalDate, sourceTimetableId: Long) {
        if (browsing()) return
        val context = (state.value as? ScheduleUiState.Ready)?.data?.context ?: return
        viewModelScope.launch {
            try {
                val day = effectiveSchedule.resolveDay(date)
                val owner = day.timetable ?: return@launch
                if ((state.value as? ScheduleUiState.Ready)?.data?.context != context) return@launch
                if (owner.id == sourceTimetableId) { message.value = "请选择其他来源课表"; return@launch }
                adjustmentRepository.deleteTemporaryDay(owner.id, date.toString())
                adjustmentRepository.insertTemporaryDay(ApplyTemporaryDay.create(owner.id, date, sourceTimetableId))
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { message.value = "临时整天设置失败，请重试" }
        }
    }

    fun clearTemporaryDay(date: LocalDate) {
        if (browsing()) return
        val context = (state.value as? ScheduleUiState.Ready)?.data?.context ?: return
        viewModelScope.launch {
            try {
                val day = effectiveSchedule.resolveDay(date)
                if ((state.value as? ScheduleUiState.Ready)?.data?.context != context) return@launch
                day.timetable?.let { adjustmentRepository.deleteTemporaryDay(it.id, date.toString()) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { message.value = "恢复未完成，请重试" }
        }
    }

    /** 调休按指定星期课程覆盖当天课表。 */
    fun applyMakeupWeekday(date: LocalDate, weekday: Int) {
        if (browsing()) return
        val context = (state.value as? ScheduleUiState.Ready)?.data?.context ?: return
        viewModelScope.launch {
            try {
                val day = effectiveSchedule.resolveDay(date)
                val owner = day.timetable ?: return@launch
                if ((state.value as? ScheduleUiState.Ready)?.data?.context != context) return@launch
                val holiday = holidayRepository.holidays.value[date]
                val label = "${holiday?.name ?: "调休"}补${DayLabels.shortLabel(weekday)}"
                adjustmentRepository.deleteTemporaryDay(owner.id, date.toString())
                adjustmentRepository.insertTemporaryDay(
                    ApplyTemporaryDay.create(
                        timetableId = owner.id,
                        date = date,
                        sourceTimetableId = owner.id,
                        title = HolidayMakeupCodec.encodeTitle(weekday, label),
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message.value = "调休课表设置失败，请重试"
            }
        }
    }

    /** 移除锚定在同一 (课程, 原始日期) 上的全部调整（整组，含目标投放）。 */
    private suspend fun saveOccurrenceAdjustment(course: Course, anchor: LocalDate,
        replacement: OccurrenceAdjustment, confirmedTarget: com.gfs.iyango.core.domain.EffectiveScheduleDay? = null) {
        val expected = (actionDays.ifEmpty { (state.value as? ScheduleUiState.Ready)?.data?.effectiveDays.orEmpty() }).adjustmentExpected(course, anchor.toString())
        if (expected == null) { message.value = "课程安排已变化，请重新打开详情确认"; return }
        try {
            when(val result = adjustmentRepository.replaceOccurrence(expected.copy(targetDay=confirmedTarget),replacement)) {
                AdjustmentReplaceResult.Saved -> Unit
                AdjustmentReplaceResult.Stale -> message.value = "课程安排已变化，请重新打开详情确认"
                is AdjustmentReplaceResult.Rejected -> message.value = result.message
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
        } catch (failure: Exception) { message.value = "调整失败，原安排已保留，请重试" }
    }

    /** 原始发生日期：优先用 UI 传入的锚点，其次按展示周 + 课程星期推导。 */
    private fun resolveAnchor(sourceDate: String, course: Course, displayedWeek: Int): LocalDate? {
        parseDate(sourceDate)?.let { return it }
        val semester = latestSemester ?: return null
        val week = course.overrideWeek.takeIf { it > 0 } ?: displayedWeek.coerceAtLeast(1)
        return semester.startDate.plusDays((week - 1) * 7L + (course.dayOfWeek.coerceIn(1, 7) - 1))
    }

    /** 快捷调整当前生效的课表显示设置（已有单表覆盖则写回单表覆盖，否则更新全局默认）。 */
    fun updateDisplaySettings(transform: (ScheduleDisplaySettings) -> ScheduleDisplaySettings) {
        val tableId = (state.value as? ScheduleUiState.Ready)?.data?.timetableId ?: 0L
        viewModelScope.launch {
            val bundle = appSettings.displaySettings.first()
            val hasOverride = tableId > 0L && bundle.byTimetable.containsKey(tableId)
            val current = if (tableId > 0L) bundle.forTimetable(tableId) else bundle.global
            val next = transform(current).sanitized()
            if (hasOverride) {
                appSettings.updateTimetableDisplay(tableId, next)
            } else {
                appSettings.updateGlobalDisplay(next)
            }
        }
    }

    /** 更新或清除单门课程的配色覆盖（写回当前生效的显示设置并递增调色板版本）。 */
    fun updateCourseColor(courseKey: String, hex: String?) {
        if (courseKey.isBlank()) return
        updateDisplaySettings { current ->
            val updated = current.courseColorOverrides.toMutableMap()
            val cleaned = hex?.trim()?.takeIf { it.isNotEmpty() }
            if (cleaned != null) {
                updated[courseKey] = cleaned
            } else {
                updated.remove(courseKey)
            }
            current.copy(
                courseColorOverrides = updated,
                paletteVersion = current.paletteVersion + 1,
            )
        }
    }

    private fun parseDate(text: String): LocalDate? =
        text.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private data class TimetableSnapshot(
        val semester: Semester?,
        val slots: List<TimeSlot>,
        val courses: List<Course>,
        val adjustments: List<OccurrenceAdjustment>,
        val temporaryDays: List<TemporaryDay>,
    )

    private companion object {
        const val SCHOOL_SYNC_SUCCESS = "教务课表同步成功，已保存到本地"
        /** 无学期信息时的展示周兜底。 */
        const val DEFAULT_DISPLAY_WEEK = 1

        /** 无学期信息时的切周上界兜底。 */
        const val DEFAULT_TOTAL_WEEKS = 20

        /** 当前时间指示器与运行状态的刷新周期（ui-baseline §3：30s）。 */
        const val NOW_TICK_MILLIS = 30_000L
    }
}
