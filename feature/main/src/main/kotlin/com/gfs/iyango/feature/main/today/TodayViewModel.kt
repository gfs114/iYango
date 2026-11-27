package com.gfs.iyango.feature.main.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.domain.adjustmentExpected
import com.gfs.iyango.core.domain.AdjustmentReplaceResult
import com.gfs.iyango.core.domain.AdjustmentRepositoryPort
import com.gfs.iyango.core.domain.AppSettingsPort
import com.gfs.iyango.core.domain.BuildingTransferDetector
import com.gfs.iyango.core.domain.CancelOccurrence
import com.gfs.iyango.core.domain.CourseOccurrence
import com.gfs.iyango.core.domain.CourseRules
import com.gfs.iyango.core.domain.TodayCourseTiming
import com.gfs.iyango.core.domain.EffectiveScheduleDay
import com.gfs.iyango.core.domain.EffectiveSchedulePort
import com.gfs.iyango.core.domain.ExamCountdown
import com.gfs.iyango.core.domain.ExamPhase
import com.gfs.iyango.core.domain.ExamRepositoryPort
import com.gfs.iyango.core.domain.ApplyTemporaryDay
import com.gfs.iyango.core.domain.HolidayMakeupCodec
import com.gfs.iyango.core.domain.HolidayRepositoryPort
import com.gfs.iyango.core.domain.MoveOccurrence
import com.gfs.iyango.core.domain.NoopHolidayRepository
import com.gfs.iyango.core.domain.RefreshCoordinatorPort
import com.gfs.iyango.core.domain.RefreshOutcome
import com.gfs.iyango.core.domain.RefreshResource
import com.gfs.iyango.core.domain.ResolveScheduleOccurrences
import com.gfs.iyango.core.domain.ScheduleDataPort
import com.gfs.iyango.core.domain.SchoolAuthPort
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.model.OccurrenceAdjustment
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.Exam
import com.gfs.iyango.core.model.ScheduleDisplaySettings
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.SchoolConnectionStatus
import com.gfs.iyango.core.model.TimeSlot
import com.gfs.iyango.core.model.Timetable
import com.gfs.iyango.core.model.DayLabels
import com.gfs.iyango.feature.main.schedule.CourseDeleteScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 今日页 ViewModel（契约 §2）。
 *
 * 有效实例与月历分开观察；秒时钟只投影缓存实例，不触发数据库或整月解析。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val timetableRepository: TimetableRepositoryPort,
    private val scheduleData: ScheduleDataPort,
    private val examRepository: ExamRepositoryPort,
    private val adjustmentRepository: AdjustmentRepositoryPort,
    private val effectiveSchedule: EffectiveSchedulePort,
    private val refreshCoordinator: RefreshCoordinatorPort,
    private val clock: Clock,
    private val appSettings: AppSettingsPort,
    auth: SchoolAuthPort,
    private val holidayRepository: HolidayRepositoryPort = NoopHolidayRepository,
) : ViewModel() {

    /** 当前课表生效的显示设置（色板等呈现项），供今日页卡片取色。 */
    val displaySettings: StateFlow<ScheduleDisplaySettings> = combine(
        appSettings.displaySettings,
        timetableRepository.observeCurrent(),
    ) { bundle, table ->
        table?.let { bundle.forTimetable(it.id) } ?: bundle.global
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ScheduleDisplaySettings())

    private val manualRefreshing = MutableStateFlow(false)

    /** 一次性提示（下拉刷新结果 / 调课校验失败等）；Screen 以顶部胶囊展示后消费。 */
    val message = MutableStateFlow<String?>(null)

    fun consumeMessage() {
        message.value = null
    }

    /** 圆圈只属于本页手动刷新；后台自动同步不能延长本次手势的加载状态。 */
    val refreshing: StateFlow<Boolean> = manualRefreshing.asStateFlow()

    private val now = MutableStateFlow(LocalDateTime.now(clock))
    private val timingMode = MutableStateFlow(0)
    private var zoneOverride: java.time.ZoneId? = null

    val preferences = appSettings.appPreferences.map { it.home.sanitized() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.gfs.iyango.core.model.HomePreferences())

    /** The screen supplies foreground, tab and visible-time-module eligibility together. */
    fun setTimingActive(active: Boolean, seconds: Boolean = true) { timingMode.value = if (!active) 0 else if (seconds) 2 else 1 }

    fun refreshNow(zone: java.time.ZoneId? = null) {
        if (zone != null) zoneOverride = zone
        now.value = LocalDateTime.ofInstant(clock.instant(), zoneOverride ?: clock.zone)
    }

    init {
        timingMode.flatMapLatest { mode ->
            if (mode == 0) emptyFlow() else flow {
                while (true) {
                    emit(Unit)
                    val instant = now.value
                    delay(if (mode == 2) 1_000L - instant.nano / 1_000_000L
                        else 60_000L - instant.second * 1_000L - instant.nano / 1_000_000L)
                }
            }
        }.onEach { refreshNow() }.launchIn(viewModelScope)
    }

    /** 详情弹层请求；每次保留点击实例自己的有效日期元数据。 */
    val detailRequest = MutableStateFlow<List<CourseOccurrence>?>(null)
    val detailDays = MutableStateFlow<List<EffectiveScheduleDay>>(emptyList())

    private val dates = now.map { it.toLocalDate() }.distinctUntilChanged()
    private val localObservationRevision = MutableStateFlow(0L)

    /** Rebuild local observers only; this never invokes school/network synchronization. */
    fun retryLocalLoad() {
        refreshNow()
        localObservationRevision.value += 1
    }

    fun resumeLocalObservation() {
        refreshNow()
        if (state.value is TodayUiState.Error) retryLocalLoad()
    }

    /** Sharing coroutines must carry read failures as values instead of throwing past collectors. */
    private fun <T> observeResults(observe: () -> Flow<Result<T>>): Flow<Result<T>> = flow {
        emitAll(observe())
    }.catch { failure ->
        if (failure is CancellationException) throw failure
        emit(Result.failure(failure))
    }

    private data class CalendarSnapshot(val month: YearMonth, val busyDates: Set<LocalDate>)
    private val calendar = combine(dates.map { YearMonth.from(it) }.distinctUntilChanged(), localObservationRevision) { month, _ -> month }
        .flatMapLatest { month -> observeResults {
            effectiveSchedule.observeSelectedRange(month.atDay(1)..month.atEndOfMonth()).map { days ->
                Result.success(CalendarSnapshot(month, days.filter { it.occurrences.isNotEmpty() }.map { it.date }.toSet()))
            }
        }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private data class Snapshot(val date: LocalDate, val days: List<EffectiveScheduleDay>,
        val calendar: CalendarSnapshot, val exams: List<Exam>, val current: Timetable?)

    private val snapshots = combine(dates, localObservationRevision) { date, _ -> date }.flatMapLatest { date ->
        observeResults {
            combine(effectiveSchedule.observeSelectedRange(date..date.plusDays(6)), calendar,
                examRepository.observeAll(), timetableRepository.observeCurrent()) { days, month, exams, current ->
                month.map { Snapshot(date, days, it, exams, current) }
            }
        }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /** Seconds are collected only by the countdown card; other content changes at minute boundaries. */
    val timing: StateFlow<TodayCourseTiming> = snapshots.mapNotNull { it.getOrNull() }.combine(now) { source, instant ->
        TodayCourseTiming.project(if (source.date == instant.toLocalDate()) source.days.flatMap { it.occurrences } else emptyList(), instant)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayCourseTiming.project(emptyList(), now.value))

    private val presentationNow = now.distinctUntilChanged { before, after ->
        before.withSecond(0).withNano(0) == after.withSecond(0).withNano(0)
    }
    val state: StateFlow<TodayUiState> = localObservationRevision.flatMapLatest {
        flow {
            emitAll(snapshots.combine(presentationNow) { result, instant ->
                val source = result.getOrNull() ?: return@combine TodayUiState.Error("首页本地数据读取失败，请重试")
                if (source.current == null) TodayUiState.NoTimetable
                else if (source.date != instant.toLocalDate() || source.calendar.month != YearMonth.from(instant)) TodayUiState.FirstLoading
                else derive(source, instant)
            }.combine(auth.observeConnections()) { schedule, connections ->
                val connection = connections.filter { it.status == SchoolConnectionStatus.ACTIVE }.maxByOrNull { it.lastLoginAt }
                    ?: connections.filter { it.status != SchoolConnectionStatus.LOGGED_OUT }.maxByOrNull { it.lastLoginAt }
                if (schedule is TodayUiState.Ready) schedule.copy(connection = connection) else schedule
            }.combine(holidayRepository.holidays) { schedule, holidays ->
                if (schedule is TodayUiState.Ready) {
                    schedule.copy(todayHoliday = holidays[schedule.today])
                } else {
                    schedule
                }
            })
        }.catch { failure ->
            if (failure is CancellationException) throw failure
            emit(TodayUiState.Error("首页加载未完成，请重试"))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState.FirstLoading)

    private fun derive(source: Snapshot, now: LocalDateTime): TodayUiState {
        val today = now.toLocalDate()
        val tomorrow = today.plusDays(1)
        val todaySchedule = source.days.firstOrNull { it.date == today } ?: EffectiveScheduleDay(today)
        val tomorrowSchedule = source.days.firstOrNull { it.date == tomorrow } ?: EffectiveScheduleDay(tomorrow)
        val timetable = todaySchedule.timetable ?: requireNotNull(source.current)
        val semester = todaySchedule.semester
        fun rowsOf(occurrences: List<CourseOccurrence>): List<OccurrenceRow> {
            val transfers = BuildingTransferDetector.detectForDay(occurrences)
            return sortRows(occurrences.map {
                OccurrenceRow(it, TodayCourseTiming.status(it, now), transfers[it.occurrenceId])
            })
        }
        return TodayUiState.Ready(
            timetable = timetable, semester = semester, week = CourseRules.weekOfDate(semester, today),
            today = today, todayRows = rowsOf(todaySchedule.occurrences), tomorrow = tomorrow,
            tomorrowRows = rowsOf(tomorrowSchedule.occurrences), busyDates = source.calendar.busyDates,
            month = source.calendar.month, timeSlots = todaySchedule.timeSlots, detailCourses = emptyList(),
            courses = source.days.flatMap { it.baseCourses }.distinctBy { it.id },
            upcomingExams = upcomingExams(source.exams, today, now), effectiveDays = source.days,
            now = now, timing = TodayCourseTiming.project(source.days.flatMap { it.occurrences }, now),
            isTemporaryDay = todaySchedule.isTemporaryDay,
        )
    }

    /**
     * 近期考试窗口固定为 14 天（明确产品默认值）：进行中的考试始终展示，
     * 待考只取窗口内；按日期/时刻升序最多 3 条。
     */
    private fun upcomingExams(
        exams: List<Exam>,
        today: LocalDate,
        now: LocalDateTime,
    ): List<UpcomingExam> = exams.asSequence()
        .filter { exam ->
            when (ExamCountdown.phase(exam, now)) {
                ExamPhase.IN_PROGRESS -> true
                ExamPhase.UPCOMING -> {
                    val start = exam.startDateOrNull() ?: return@filter false
                    ChronoUnit.DAYS.between(today, start) <= UPCOMING_EXAM_DAYS
                }
                else -> false
            }
        }
        .sortedWith(compareBy({ it.date }, { it.startTime }))
        .take(3)
        .map { UpcomingExam(exam = it, countdown = ExamCountdown.label(it, now)) }
        .toList()

    /** 下拉刷新：课表与个人资料并发刷新一轮，完成后提示刷新结果。 */
    fun refresh() {
        if (manualRefreshing.value) return
        manualRefreshing.value = true
        message.value = null
        refreshNow()
        viewModelScope.launch {
            try {
                val hasAccount = (state.value as? TodayUiState.Ready)?.connection != null
                if (!hasAccount) {
                    message.value = "本地数据已刷新，登录后可同步教务"
                    return@launch
                }
                suspend fun refreshResource(resource: RefreshResource) = try {
                    refreshCoordinator.refresh(resource)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                val (timetableResult, profileResult) = coroutineScope {
                    val timetableJob = async {
                        refreshResource(RefreshResource.TIMETABLE)
                    }
                    val profileJob = async {
                        refreshResource(RefreshResource.PROFILE)
                    }
                    timetableJob.await() to profileJob.await()
                }
                message.value = when {
                    timetableResult?.sessionExpired == true || profileResult?.sessionExpired == true ->
                        "教务登录已过期，本地数据已保留"
                    timetableResult?.outcome != RefreshOutcome.SUCCESS ||
                        profileResult?.outcome != RefreshOutcome.SUCCESS ->
                        listOf("课表" to timetableResult, "个人资料" to profileResult)
                            .filter { (_, result) -> result?.outcome != RefreshOutcome.SUCCESS }
                            .joinToString("；") { (label, result) ->
                                "$label：${result?.message?.takeIf { it.isNotBlank() } ?: "刷新未完成"}"
                            } + "（本地数据已保留）"
                    else -> "刷新成功"
                }
            } finally {
                manualRefreshing.value = false
            }
        }
    }

    suspend fun targetDay(date: LocalDate) = effectiveSchedule.resolveDay(date)

    fun restoreTemporaryDay(ownerTimetableId: Long, date: LocalDate) {
        closeDetail()
        viewModelScope.launch {
            try { adjustmentRepository.deleteTemporaryDay(ownerTimetableId, date.toString()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message.value = "恢复未完成，请重试" }
        }
    }

    /** 调休补班一键按指定星期课程覆盖当天课表。 */
    fun applyMakeupWeekday(date: LocalDate, weekday: Int) {
        val ownerId = (state.value as? TodayUiState.Ready)?.timetable?.id ?: return
        val holiday = holidayRepository.holidays.value[date]
        val label = "${holiday?.name ?: "调休"}补${DayLabels.shortLabel(weekday)}"
        viewModelScope.launch {
            try {
                adjustmentRepository.deleteTemporaryDay(ownerId, date.toString())
                adjustmentRepository.insertTemporaryDay(
                    ApplyTemporaryDay.create(
                        timetableId = ownerId,
                        date = date,
                        sourceTimetableId = ownerId,
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

    private fun temporaryCourse(course: Course, sourceDate: String): Boolean =
        (detailRequest.value.orEmpty() + (state.value as? TodayUiState.Ready)?.effectiveDays.orEmpty().flatMap { it.occurrences })
            .any { it.isTemporary && it.course == course && (sourceDate.isBlank() || it.anchorDate.toString() == sourceDate) }

    /** 打开详情弹层（当前点击行；冲突组扩展由弹层内翻页承担）。 */
    fun openDetail(row: OccurrenceRow) {
        detailDays.value = (state.value as? TodayUiState.Ready)?.effectiveDays.orEmpty()
        detailRequest.value = listOf(row.occurrence)
    }

    fun closeDetail() {
        detailRequest.value = null
    }

    /**
     * 详情弹层删除（三档语义与课表页 [CourseDeleteScope] 一致）：
     * - [CourseDeleteScope.CANCEL_THIS_WEEK]：以原始发生日期为锚点写入 CANCEL；
     * - [CourseDeleteScope.DELETE_ARRANGEMENT]：删除该课程行及其全部调整记录；
     * - [CourseDeleteScope.DELETE_WHOLE_COURSE]：删除同名同教师全部课程（含调整）。
     *
     * @param sourceDate 该次发生的原始日期（yyyy-MM-dd）；空串时按当前周 + 课程星期推导。
     */
    fun deleteCourse(
        course: Course,
        scope: CourseDeleteScope,
        currentWeek: Int,
        sourceDate: String = "",
    ) {
        if (temporaryCourse(course, sourceDate)) { message.value = "请先恢复该日课表后调整"; return }
        closeDetail()
        viewModelScope.launch {
            runCatching {
                when (scope) {
                    CourseDeleteScope.CANCEL_THIS_WEEK -> {
                        val baseId = course.overrideOfId.takeIf { it > 0 } ?: course.id
                        val anchor = resolveAnchor(sourceDate, course, currentWeek) ?: return@runCatching
                        saveOccurrenceAdjustment(course, anchor,
                            CancelOccurrence.create(
                                timetableId = course.timetableId,
                                sourceCourseId = baseId,
                                sourceDate = anchor.toString(),
                            )
                        )
                    }
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
    }

    /**
     * 应用课程调整（F08）。
     *
     * - [temporary] = true（默认）：以原始发生为锚点写入 MOVE，保留原安排可恢复；
     * - [temporary] = false：永久调课，直接改写原课程安排，规则与鸿蒙
     *   ScheduleAdjustmentService.move 一致——跨周或单次安排不允许永久调整。
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
        if (temporaryCourse(course, sourceDate)) { message.value = "请先恢复该日课表后调整"; return }
        val selected = detailRequest.value.orEmpty().firstOrNull {
            it.course == course && (sourceDate.isBlank() || it.anchorDate.toString() == sourceDate)
        }
        closeDetail()
        val ready = state.value as? TodayUiState.Ready ?: return
        val detailDate = selected?.date ?: ready.today
        val semester = detailDays.value.firstOrNull { it.date == detailDate }?.semester ?: ready.semester ?: return
        viewModelScope.launch {
            runCatching {
                val baseId = course.overrideOfId.takeIf { it > 0 } ?: course.id
                val anchor = resolveAnchor(sourceDate, course, ready.week) ?: return@runCatching
                val targetDate = confirmedTarget?.date ?: semester.startDate.plusDays(
                    (targetWeek.coerceAtLeast(1) - 1) * 7L + (dayOfWeek.coerceIn(1, 7) - 1),
                )
                if (!temporary) {
                val warning = try { com.gfs.iyango.core.domain.AdjustmentTargetPolicy.warning(effectiveSchedule.resolveDay(targetDate),course.timetableId) }
                    catch(cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch(failure: Exception) { message.value = "调整失败，请重试"; return@runCatching }
                if(warning != null) { message.value = warning; return@runCatching }

                    applyPermanentAdjustment(course, targetWeek, dayOfWeek, startSection, endSection, detailDate, semester)
                    return@runCatching
                }
                saveOccurrenceAdjustment(course, anchor,
                    MoveOccurrence.create(
                        timetableId = course.timetableId,
                        sourceCourseId = baseId,
                        sourceDate = anchor.toString(),
                        targetDate = targetDate,
                        targetStartSection = startSection,
                        targetEndSection = endSection,
                    ), confirmedTarget,
                )
            }
        }
    }

    /** 永久调课：直接改写原课程安排（时间回退为按作息表计算，同鸿蒙 place()）。 */
    private suspend fun applyPermanentAdjustment(
        course: Course,
        targetWeek: Int,
        dayOfWeek: Int,
        startSection: Int,
        endSection: Int,
        detailDate: LocalDate,
        detailSemester: Semester,
    ) {
        val source = scheduleData.observeCourses(course.timetableId).first()
            .firstOrNull { it.id == course.id } ?: run {
            message.value = "课程安排已变化，请重试"
            return
        }
        val week = CourseRules.weekOfDate(detailSemester, detailDate)
        if (source != course) { message.value = "课程安排已变化，请重新打开详情确认"; return }
        if (!source.enabled || targetWeek < 1 || dayOfWeek !in 1..7 ||
            startSection < 1 || endSection < startSection
        ) {
            message.value = "请选择有效的调整时间"
            return
        }
        if (targetWeek != week || source.timeMode == 2 || source.overrideWeek > 0) {
            message.value = "跨周或单次安排请选择仅本周生效"
            return
        }
        val sourceSemester = scheduleData.observeSemester(course.timetableId).first()
        val sourceWeek = CourseRules.weekOfDate(sourceSemester, detailDate)
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

    private suspend fun deleteWithAdjustments(timetableId: Long, courseId: Long) {
        scheduleData.observeCourses(timetableId).first()
            .filter { it.overrideOfId == courseId }
            .forEach { scheduleData.deleteCourse(it.id) }
        adjustmentRepository.observeAdjustments(timetableId).first()
            .filter { it.sourceCourseId == courseId }
            .map { it.operationGroupId }
            .distinct()
            .forEach { adjustmentRepository.deleteGroup(it) }
        scheduleData.deleteCourse(courseId)
    }

    private suspend fun saveOccurrenceAdjustment(course: Course, anchor: LocalDate,
        replacement: OccurrenceAdjustment, confirmedTarget: com.gfs.iyango.core.domain.EffectiveScheduleDay? = null) {
        val expected = (detailDays.value.ifEmpty { (state.value as? TodayUiState.Ready)?.effectiveDays.orEmpty() }).adjustmentExpected(course, anchor.toString())
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

    private fun resolveAnchor(sourceDate: String, course: Course, week: Int): LocalDate? {
        if (sourceDate.isNotEmpty()) {
            return runCatching { LocalDate.parse(sourceDate) }.getOrNull()
        }
        val semester = (state.value as? TodayUiState.Ready)?.semester ?: return null
        val effectiveWeek = course.overrideWeek.takeIf { it > 0 } ?: week.coerceAtLeast(1)
        return semester.startDate.plusDays(
            (effectiveWeek - 1) * 7L + (course.dayOfWeek.coerceIn(1, 7) - 1),
        )
    }

    private companion object {
        /** 今日页近期考试窗口（天）。 */
        const val UPCOMING_EXAM_DAYS = 14L
    }
}
