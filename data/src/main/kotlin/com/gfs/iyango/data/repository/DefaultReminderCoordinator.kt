package com.gfs.iyango.data.repository

import com.gfs.iyango.core.domain.AdjustmentRepositoryPort
import com.gfs.iyango.core.domain.AppSettingsPort
import com.gfs.iyango.core.domain.CourseReminderPlanner
import com.gfs.iyango.core.domain.DiagnosticLogPort
import com.gfs.iyango.core.domain.EffectiveSchedulePort
import com.gfs.iyango.core.domain.HolidayRepositoryPort
import com.gfs.iyango.core.domain.NoopHolidayRepository
import com.gfs.iyango.core.domain.ReminderCoordinatorPort
import com.gfs.iyango.core.domain.ReminderCourseOption
import com.gfs.iyango.core.domain.ReminderPlatformPort
import com.gfs.iyango.core.domain.ReminderStatus
import com.gfs.iyango.core.domain.ScheduleDataPort
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.model.DiagnosticEvent
import java.time.Clock
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 课程提醒协调器（F12）。
 *
 * 数据源与今日/周表一致：逐日调用 [EffectiveSchedulePort.resolveDay]，因此自动选表与
 * 临时调整会直接反映到提醒计划；课程编辑、作息/学期变化、调课和切表都会触发防抖重算。
 *
 * 计划生成在 `core:domain` 的纯规划器中完成，本类只负责取数、调用平台排程并汇总状态。
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@Singleton
class DefaultReminderCoordinator @Inject constructor(
    private val appSettings: AppSettingsPort,
    private val effectiveSchedule: EffectiveSchedulePort,
    private val timetableRepository: TimetableRepositoryPort,
    private val scheduleData: ScheduleDataPort,
    private val adjustments: AdjustmentRepositoryPort,
    private val platform: ReminderPlatformPort,
    private val diagnostics: DiagnosticLogPort,
    private val clock: Clock,
    private val holidayRepository: HolidayRepositoryPort = NoopHolidayRepository,
) : ReminderCoordinatorPort {

    private val mutex = Mutex()
    private val _status = MutableStateFlow(ReminderStatus())
    override val status: StateFlow<ReminderStatus> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            timetableRepository.observeCurrent()
                .flatMapLatest { table ->
                    if (table == null) {
                        flowOf(Unit)
                    } else {
                        val data = combine(
                            scheduleData.observeCourses(table.id),
                            scheduleData.observeTimeSlots(table.id),
                            scheduleData.observeSemester(table.id),
                        ) { _, _, _ -> Unit }
                        val adjustmentsFlow = combine(
                            adjustments.observeAdjustments(table.id),
                            adjustments.observeTemporaryDays(table.id),
                        ) { _, _ -> Unit }
                        combine(
                            data,
                            adjustmentsFlow,
                            appSettings.reminderSettings.map { Unit },
                            holidayRepository.holidays.map { it.size },
                        ) { _, _, _, _ -> Unit }
                    }
                }
                .debounce(400)
                .collect {
                    runCatching { refresh() }
                }
        }
    }

    override suspend fun refresh() = mutex.withLock {
        val settings = appSettings.reminderSettings.first()
        val notificationsAllowed = platform.notificationsAllowed()
        val exactAllowed = platform.exactAlarmsAllowed()
        val liveAllowed = platform.liveUpdatesAllowed()
        val courses = courseOptions(settings.disabledCourseKeys)

        if (!settings.enabled) {
            platform.cancelAll(OWNER_ID)
            _status.value = ReminderStatus(
                enabled = false,
                leadMinutes = settings.leadMinutes,
                notificationsAllowed = notificationsAllowed,
                exactAlarmsAllowed = exactAllowed,
                liveUpdatesAllowed = liveAllowed,
                ongoingProgressEnabled = settings.ongoingProgressEnabled,
                courses = courses,
                skipRemindersOnHolidays = settings.skipRemindersOnHolidays,
            )
            return@withLock
        }

        if (!notificationsAllowed) {
            platform.cancelAll(OWNER_ID)
            log("FAILED", "PermissionDenied", "未授予通知权限，提醒不会显示")
            _status.value = ReminderStatus(
                enabled = true,
                leadMinutes = settings.leadMinutes,
                notificationsAllowed = false,
                exactAlarmsAllowed = exactAllowed,
                liveUpdatesAllowed = liveAllowed,
                ongoingProgressEnabled = settings.ongoingProgressEnabled,
                skipped = listOf("未授予通知权限，提醒不会显示"),
                failures = listOf("未授予通知权限，提醒不会显示"),
                courses = courses,
                skipRemindersOnHolidays = settings.skipRemindersOnHolidays,
            )
            return@withLock
        }

        val now = LocalDateTime.now(clock)
        val today = now.toLocalDate()
        val occurrences = (0 until HORIZON_DAYS).flatMap { offset ->
            runCatching { effectiveSchedule.resolveDay(today.plusDays(offset.toLong())).occurrences }
                .getOrDefault(emptyList())
        }
        // 强调色与周网格一致：当前课表的有效显示设置里取用户配色覆盖，未覆盖时用课程自带颜色。
        val display = appSettings.displaySettings.first()
        val currentTable = timetableRepository.observeCurrent().first()
        val overrides = (currentTable?.let { display.forTimetable(it.id) } ?: display.global)
            .courseColorOverrides
        val plan = CourseReminderPlanner.plan(
            occurrences,
            settings,
            now,
            clock.zone,
            colorOf = { overrides[CourseReminderPlanner.courseKey(it)] ?: it.color },
            isHolidayOffDay = { holidayRepository.isHolidayOffDay(it) },
        )
        val result = platform.schedule(plan.items, OWNER_ID)
        val failures = buildList {
            if (!exactAllowed) add("未授予精确闹钟权限，提醒可能延迟")
            addAll(result.failures)
        }.distinct()

        _status.value = ReminderStatus(
            enabled = true,
            leadMinutes = settings.leadMinutes,
            notificationsAllowed = true,
            exactAlarmsAllowed = exactAllowed,
            liveUpdatesAllowed = liveAllowed,
            ongoingProgressEnabled = settings.ongoingProgressEnabled,
            plannedCount = plan.items.size,
            scheduledCount = result.scheduled,
            skipped = plan.skipped,
            failures = failures,
            lastSyncAt = clock.millis(),
            courses = courses,
            skipRemindersOnHolidays = settings.skipRemindersOnHolidays,
        )
        if (failures.isNotEmpty()) {
            log(
                outcome = if (result.scheduled > 0) "PARTIAL" else "FAILED",
                errorType = if (!exactAllowed) "ExactAlarmDenied" else "ScheduleFailure",
                message = failures.first(),
            )
        }
    }

    /** 记录脱敏后的提醒失败原因；日志失败不得影响排程。 */
    private suspend fun log(outcome: String, errorType: String, message: String) {
        runCatching {
            diagnostics.record(
                DiagnosticEvent(
                    timestamp = clock.millis(),
                    category = "reminder",
                    outcome = outcome,
                    errorType = errorType,
                    message = message,
                ),
            )
        }
    }

    override suspend fun setEnabled(enabled: Boolean) {
        val settings = appSettings.reminderSettings.first()
        appSettings.updateReminderSettings(settings.copy(enabled = enabled))
        refresh()
    }

    override suspend fun setLeadMinutes(minutes: Int) {
        val settings = appSettings.reminderSettings.first()
        appSettings.updateReminderSettings(settings.copy(leadMinutes = minutes).sanitized())
        refresh()
    }

    override suspend fun setOngoingProgressEnabled(enabled: Boolean) {
        val settings = appSettings.reminderSettings.first()
        appSettings.updateReminderSettings(settings.copy(ongoingProgressEnabled = enabled).sanitized())
        refresh()
    }

    override suspend fun sendLiveProgressPreview(): Boolean =
        platform.showProgressPreview()

    override suspend fun setCourseEnabled(key: String, enabled: Boolean) {
        if (key.isBlank()) return
        val settings = appSettings.reminderSettings.first()
        val disabled = settings.disabledCourseKeys.toMutableSet()
        val current = timetableRepository.observeCurrent().first()
        val relatedKeys = if (current != null) {
            scheduleData.observeCourses(current.id).first()
                .filter { CourseReminderPlanner.reminderKey(it) == key || CourseReminderPlanner.courseKey(it) == key }
                .flatMap {
                    listOf(
                        CourseReminderPlanner.reminderKey(it),
                        CourseReminderPlanner.courseKey(it),
                        "${it.name}|${it.teacher}",
                    )
                }
                .toSet() + key
        } else {
            setOf(key)
        }
        if (enabled) disabled.removeAll(relatedKeys) else disabled.add(key)
        appSettings.updateReminderSettings(settings.copy(disabledCourseKeys = disabled))
        refresh()
    }

    override suspend fun setSkipRemindersOnHolidays(skip: Boolean) {
        val settings = appSettings.reminderSettings.first()
        appSettings.updateReminderSettings(settings.copy(skipRemindersOnHolidays = skip).sanitized())
        refresh()
    }

    private suspend fun courseOptions(disabledKeys: Set<String>): List<ReminderCourseOption> {
        val current = timetableRepository.observeCurrent().first() ?: return emptyList()
        return scheduleData.observeCourses(current.id).first()
            .filter { it.overrideOfId == 0L }
            .groupBy { CourseReminderPlanner.reminderKey(it) }
            .map { (key, rows) ->
                val first = rows.first()
                val teachers = rows
                    .flatMap { it.teacher.split(Regex("[、,，;；\\r\\n]+")) }
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString("、")
                ReminderCourseOption(
                    key = key,
                    name = first.name,
                    teacher = teachers,
                    enabled = rows.none { CourseReminderPlanner.isCourseDisabled(it, disabledKeys) },
                )
            }
            .sortedWith(compareBy({ it.name }, { it.teacher }))
    }

    private companion object {
        /** 系统映射的安装实例标记；提醒 ID 仅本机有效。 */
        const val OWNER_ID = "local"

        /** 提醒计划窗口（天）：覆盖未来两周，重算时整体替换。 */
        const val HORIZON_DAYS = 14
    }
}
