package com.gfs.iyango.data.repository

import com.gfs.iyango.core.domain.AdjustmentRepositoryPort
import com.gfs.iyango.core.domain.AppSettingsPort
import com.gfs.iyango.core.domain.AutoSilentCoordinatorPort
import com.gfs.iyango.core.domain.AutoSilentPlanner
import com.gfs.iyango.core.domain.AutoSilentPlatformPort
import com.gfs.iyango.core.domain.AutoSilentStatus
import com.gfs.iyango.core.domain.CourseReminderPlanner
import com.gfs.iyango.core.domain.EffectiveSchedulePort
import com.gfs.iyango.core.domain.HolidayRepositoryPort
import com.gfs.iyango.core.domain.NoopHolidayRepository
import com.gfs.iyango.core.domain.ScheduleDataPort
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.model.AutoSilentMode
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 上课自动静音/免打扰协调器。
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@Singleton
class DefaultAutoSilentCoordinator @Inject constructor(
    private val appSettings: AppSettingsPort,
    private val effectiveSchedule: EffectiveSchedulePort,
    private val timetableRepository: TimetableRepositoryPort,
    private val scheduleData: ScheduleDataPort,
    private val adjustments: AdjustmentRepositoryPort,
    private val platform: AutoSilentPlatformPort,
    private val clock: Clock,
    private val holidayRepository: HolidayRepositoryPort = NoopHolidayRepository,
) : AutoSilentCoordinatorPort {

    private val mutex = Mutex()
    private val _status = MutableStateFlow(AutoSilentStatus())
    override val status: StateFlow<AutoSilentStatus> = _status.asStateFlow()

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
                            appSettings.reminderSettings.map {
                                Triple(it.autoSilentMode, it.autoSilentExemptCourseKeys, it.autoSilentSkipOnHolidays)
                            },
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
        val settings = appSettings.reminderSettings.first().sanitized()
        val policyGranted = platform.hasPolicyAccess()
        if (settings.autoSilentMode == AutoSilentMode.OFF) {
            platform.cancelAllAndRestore()
            _status.value = AutoSilentStatus(
                mode = AutoSilentMode.OFF,
                policyAccessGranted = policyGranted,
                skipOnHolidays = settings.autoSilentSkipOnHolidays,
                scheduledCount = 0,
                activeCourseName = null,
                exemptCourseKeys = settings.autoSilentExemptCourseKeys,
            )
            return@withLock
        }

        val now = LocalDateTime.now(clock)
        val today = now.toLocalDate()
        val occurrences = (0 until HORIZON_DAYS).flatMap { offset ->
            runCatching { effectiveSchedule.resolveDay(today.plusDays(offset.toLong())).occurrences }
                .getOrDefault(emptyList())
        }
        val windows = AutoSilentPlanner.plan(
            occurrences = occurrences,
            settings = settings,
            now = now,
            zoneId = clock.zone,
            isHolidayOffDay = { holidayRepository.isHolidayOffDay(it) },
        )
        platform.schedule(windows, settings.autoSilentMode)
        val nowMillis = clock.millis()
        val activeName = windows.firstOrNull { nowMillis in it.startAtMillis until it.endAtMillis }?.courseName
        _status.value = AutoSilentStatus(
            mode = settings.autoSilentMode,
            policyAccessGranted = policyGranted,
            skipOnHolidays = settings.autoSilentSkipOnHolidays,
            scheduledCount = windows.size,
            activeCourseName = activeName,
            exemptCourseKeys = settings.autoSilentExemptCourseKeys,
        )
    }

    override suspend fun setMode(mode: AutoSilentMode) {
        val current = appSettings.reminderSettings.first()
        appSettings.updateReminderSettings(current.copy(autoSilentMode = mode).sanitized())
        refresh()
    }

    override suspend fun setSkipOnHolidays(skip: Boolean) {
        val current = appSettings.reminderSettings.first()
        appSettings.updateReminderSettings(current.copy(autoSilentSkipOnHolidays = skip).sanitized())
        refresh()
    }

    override suspend fun setCourseExempt(courseKey: String, exempt: Boolean) {
        if (courseKey.isBlank()) return
        val settings = appSettings.reminderSettings.first()
        val exemptSet = settings.autoSilentExemptCourseKeys.toMutableSet()
        val current = timetableRepository.observeCurrent().first()
        val relatedKeys = if (current != null) {
            scheduleData.observeCourses(current.id).first()
                .filter { CourseReminderPlanner.reminderKey(it) == courseKey || CourseReminderPlanner.courseKey(it) == courseKey }
                .flatMap {
                    listOf(
                        CourseReminderPlanner.reminderKey(it),
                        CourseReminderPlanner.courseKey(it),
                        "${it.name}|${it.teacher}",
                    )
                }
                .toSet() + courseKey
        } else {
            setOf(courseKey)
        }
        if (exempt) exemptSet.add(courseKey) else exemptSet.removeAll(relatedKeys)
        appSettings.updateReminderSettings(settings.copy(autoSilentExemptCourseKeys = exemptSet).sanitized())
        refresh()
    }

    override suspend fun restoreNow() {
        platform.cancelAllAndRestore()
        refresh()
    }

    companion object {
        const val HORIZON_DAYS = 7
    }
}
