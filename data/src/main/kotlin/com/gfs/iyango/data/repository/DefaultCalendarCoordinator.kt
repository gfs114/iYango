package com.gfs.iyango.data.repository

import com.gfs.iyango.core.domain.AdjustmentRepositoryPort
import com.gfs.iyango.core.domain.AppSettingsPort
import com.gfs.iyango.core.domain.CalendarClearResult
import com.gfs.iyango.core.domain.CalendarCoordinatorPort
import com.gfs.iyango.core.domain.CalendarEventPlanner
import com.gfs.iyango.core.domain.CalendarPlatformPort
import com.gfs.iyango.core.domain.CalendarSyncStatus
import com.gfs.iyango.core.domain.CalendarWriteResult
import com.gfs.iyango.core.domain.DiagnosticLogPort
import com.gfs.iyango.core.domain.EffectiveSchedulePort
import com.gfs.iyango.core.domain.ScheduleDataPort
import com.gfs.iyango.core.domain.SystemMappingPort
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.model.DiagnosticEvent
import com.gfs.iyango.core.model.SystemMapping
import com.gfs.iyango.core.model.SystemMappingKind
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 系统日历差异同步（F13）。
 *
 * - 与提醒共用“有效实例”数据源，但写入通道、开关与状态完全独立；
 * - 每个实例最多一条映射；内容未变时不重复写入，调课/改时间/改教室就地更新；
 * - 删除只针对正文带本安装实例所有权标记的事件；用户自建事件保留；
 * - 已结束的历史事件保留在日历中，只解除本应用映射；未来且已不在计划中的事件才删除。
 * - 权限撤销时保留映射与待清理计数，重新授权后重试，不先丢映射再谎称删除。
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@Singleton
class DefaultCalendarCoordinator @Inject constructor(
    private val appSettings: AppSettingsPort,
    private val effectiveSchedule: EffectiveSchedulePort,
    private val timetableRepository: TimetableRepositoryPort,
    private val scheduleData: ScheduleDataPort,
    private val adjustments: AdjustmentRepositoryPort,
    private val mappings: SystemMappingPort,
    private val platform: CalendarPlatformPort,
    private val diagnostics: DiagnosticLogPort,
    private val clock: Clock,
) : CalendarCoordinatorPort {

    private val mutex = Mutex()
    private val _status = MutableStateFlow(CalendarSyncStatus())
    override val status: StateFlow<CalendarSyncStatus> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            timetableRepository.observeCurrent()
                .flatMapLatest { table ->
                    val scheduleChanges = if (table == null) {
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
                        combine(data, adjustmentsFlow) { _, _ -> Unit }
                    }
                    combine(
                        scheduleChanges,
                        appSettings.calendarSettings
                            .map { it.enabled to it.calendarId }
                            .distinctUntilChanged(),
                    ) { _, _ -> Unit }
                }
                .debounce(500)
                .collect {
                    runCatching { refresh() }
                }
        }
    }

    override suspend fun refresh() = mutex.withLock { refreshLocked() }

    override suspend fun setEnabled(enabled: Boolean) = mutex.withLock {
        val settings = appSettings.calendarSettings.first()
        appSettings.updateCalendarSettings(settings.copy(enabled = enabled))
        refreshLocked()
    }

    override suspend fun selectCalendar(calendarId: Long) = mutex.withLock {
        val settings = appSettings.calendarSettings.first()
        if (calendarId != settings.calendarId && settings.ownerId.isNotBlank() && platform.hasPermission()) {
            removeFutureOwnedEvents(settings.ownerId)
        }
        appSettings.updateCalendarSettings(settings.copy(calendarId = calendarId))
        refreshLocked()
    }

    override suspend fun clearCalendar(): CalendarClearResult = mutex.withLock {
        var settings = appSettings.calendarSettings.first()
        val ownerId = ensureOwner(settings.ownerId)
        if (ownerId != settings.ownerId) {
            settings = settings.copy(ownerId = ownerId)
            appSettings.updateCalendarSettings(settings)
        }
        if (!platform.hasPermission()) {
            log("FAILED", "PermissionDenied", "缺少日历读写权限，授权后重试")
            _status.value = _status.value.copy(
                permissionGranted = false,
                pendingCleanup = mappings.all(SystemMappingKind.CALENDAR, ownerId).size,
                lastError = "缺少日历读写权限，授权后重试",
            )
            return@withLock CalendarClearResult.PermissionDenied
        }

        val summary = platform.deleteAllOwned(ownerId)
        var deleted = summary.deleted
        val failedIds = summary.failedEventIds.toMutableSet()

        // 核对全部本地映射：已被批量删除或不存在的移除映射；未尝试过的仍存活事件兜底逐条删除；批量已失败的保留映射且不重复计数。
        mappings.all(SystemMappingKind.CALENDAR, ownerId).forEach { mapping ->
            val eventId = mapping.externalId.toLongOrNull()
            if (eventId != null && eventId in failedIds) return@forEach
            val startAt = eventId?.let { platform.eventStartMillis(it, ownerId) }
            when {
                eventId == null || startAt == null ->
                    mappings.delete(SystemMappingKind.CALENDAR, mapping.occurrenceId, ownerId)
                platform.deleteOwned(eventId, ownerId) -> {
                    deleted++
                    mappings.delete(SystemMappingKind.CALENDAR, mapping.occurrenceId, ownerId)
                }
                else -> failedIds += eventId
            }
        }

        val pending = failedIds.size
        val nowMillis = clock.millis()
        val error = if (pending > 0) "有 $pending 个事件待清理" else ""
        val calendars = platform.calendars()
        appSettings.updateCalendarSettings(
            settings.copy(
                enabled = false,
                lastSyncAt = nowMillis,
                lastError = error,
            ),
        )
        if (pending > 0) {
            log("PARTIAL", "CleanupPending", error)
        } else {
            log("SUCCESS", "CalendarCleared", "已清除 $deleted 条系统日历日程")
        }
        _status.value = CalendarSyncStatus(
            enabled = false,
            permissionGranted = true,
            calendars = calendars,
            selectedCalendarId = settings.calendarId,
            syncedCount = 0,
            pendingCleanup = pending,
            lastSyncAt = nowMillis,
            lastError = error,
        )
        CalendarClearResult.Completed(deletedCount = deleted, pendingCount = pending)
    }

    private suspend fun refreshLocked() {
        var settings = appSettings.calendarSettings.first()
        val ownerId = ensureOwner(settings.ownerId)
        if (ownerId != settings.ownerId) {
            settings = settings.copy(ownerId = ownerId)
            appSettings.updateCalendarSettings(settings)
        }
        val permission = platform.hasPermission()
        val calendars = if (permission) platform.calendars() else emptyList()

        if (!permission) {
            if (settings.enabled) log("FAILED", "PermissionDenied", "缺少日历读写权限，授权后重试")
            _status.value = CalendarSyncStatus(
                enabled = settings.enabled,
                permissionGranted = false,
                selectedCalendarId = settings.calendarId,
                pendingCleanup = mappings.all(SystemMappingKind.CALENDAR, ownerId).size,
                lastSyncAt = settings.lastSyncAt,
                lastError = if (settings.enabled) "缺少日历读写权限，授权后重试" else settings.lastError,
            )
            return
        }

        if (!settings.enabled) {
            val pending = removeFutureOwnedEvents(ownerId)
            val nowMillis = clock.millis()
            val error = if (pending > 0) "有 $pending 个事件待清理" else ""
            appSettings.updateCalendarSettings(settings.copy(lastSyncAt = nowMillis, lastError = error))
            if (pending > 0) log("PARTIAL", "CleanupPending", error)
            _status.value = CalendarSyncStatus(
                enabled = false,
                permissionGranted = true,
                calendars = calendars,
                selectedCalendarId = settings.calendarId,
                pendingCleanup = pending,
                lastSyncAt = nowMillis,
                lastError = error,
            )
            return
        }

        if (settings.calendarId <= 0) {
            log("FAILED", "NoCalendar", "请选择要写入的系统日历")
            _status.value = CalendarSyncStatus(
                enabled = true,
                permissionGranted = true,
                calendars = calendars,
                selectedCalendarId = settings.calendarId,
                lastSyncAt = settings.lastSyncAt,
                lastError = "请选择要写入的系统日历",
            )
            return
        }
        if (calendars.none { it.id == settings.calendarId }) {
            log("FAILED", "CalendarUnavailable", "所选日历不可写，请重新选择")
            _status.value = CalendarSyncStatus(
                enabled = true,
                permissionGranted = true,
                calendars = calendars,
                selectedCalendarId = settings.calendarId,
                lastSyncAt = settings.lastSyncAt,
                lastError = "所选日历不可写，请重新选择",
            )
            return
        }

        val now = LocalDateTime.now(clock)
        val today = now.toLocalDate()
        val occurrences = (0 until HORIZON_DAYS).flatMap { offset ->
            runCatching { effectiveSchedule.resolveDay(today.plusDays(offset.toLong())).occurrences }
                .getOrDefault(emptyList())
        }
        val (plans, skipped) = CalendarEventPlanner.plan(occurrences, clock.zone)
        val existing = mappings.all(SystemMappingKind.CALENDAR, ownerId).associateBy { it.occurrenceId }
        val failures = mutableListOf<String>()
        val nowMillis = clock.millis()
        var synced = 0

        plans.forEach { plan ->
            val mapping = existing[plan.occurrenceId]
            val eventId = mapping?.externalId?.toLongOrNull()
            val unchanged = mapping != null && eventId != null &&
                mapping.payloadHash == plan.payloadHash &&
                mapping.planRevision == plan.planRevision &&
                platform.eventStartMillis(eventId, ownerId) != null
            if (unchanged) {
                synced++
                return@forEach
            }
            var result = platform.upsert(plan, eventId, settings.calendarId, ownerId)
            if (result is CalendarWriteResult.NotOwned) {
                mappings.delete(SystemMappingKind.CALENDAR, plan.occurrenceId, ownerId)
                result = platform.upsert(plan, null, settings.calendarId, ownerId)
            }
            when (result) {
                is CalendarWriteResult.Success -> {
                    synced++
                    mappings.upsert(
                        SystemMapping(
                            kind = SystemMappingKind.CALENDAR,
                            occurrenceId = plan.occurrenceId,
                            externalId = result.eventId.toString(),
                            payloadHash = plan.payloadHash,
                            planRevision = plan.planRevision,
                            ownerId = ownerId,
                            updatedAt = nowMillis,
                        ),
                    )
                }
                is CalendarWriteResult.Failure ->
                    failures += "${plan.title}：${result.message}"
                CalendarWriteResult.NotOwned ->
                    failures += "${plan.title}：事件不属于本应用，未覆盖"
            }
        }

        var pending = 0
        val plannedIds = plans.map { it.occurrenceId }.toSet()
        existing.values.filter { it.occurrenceId !in plannedIds }.forEach { mapping ->
            val eventId = mapping.externalId.toLongOrNull()
            val startAt = eventId?.let { platform.eventStartMillis(it, ownerId) }
            when {
                eventId == null || startAt == null ->
                    mappings.delete(SystemMappingKind.CALENDAR, mapping.occurrenceId, ownerId)
                startAt < nowMillis ->
                    mappings.delete(SystemMappingKind.CALENDAR, mapping.occurrenceId, ownerId)
                platform.deleteOwned(eventId, ownerId) ->
                    mappings.delete(SystemMappingKind.CALENDAR, mapping.occurrenceId, ownerId)
                else -> pending++
            }
        }

        val error = when {
            failures.isNotEmpty() -> failures.first()
            pending > 0 -> "有 $pending 个旧事件待清理"
            else -> ""
        }
        if (error.isNotBlank()) {
            log(
                outcome = if (failures.isEmpty()) "PARTIAL" else "FAILED",
                errorType = if (failures.isEmpty()) "CleanupPending" else "WriteFailure",
                message = error,
            )
        }
        appSettings.updateCalendarSettings(settings.copy(lastSyncAt = nowMillis, lastError = error))
        _status.value = CalendarSyncStatus(
            enabled = true,
            permissionGranted = true,
            calendars = calendars,
            selectedCalendarId = settings.calendarId,
            syncedCount = synced,
            pendingCleanup = pending,
            skipped = skipped,
            lastSyncAt = nowMillis,
            lastError = error,
        )
    }

    /** 删除本实例拥有的未来事件（过去事件作为历史保留），返回无法删除的待清理数。 */
    private suspend fun removeFutureOwnedEvents(ownerId: String): Int {
        val nowMillis = clock.millis()
        var pending = 0
        mappings.all(SystemMappingKind.CALENDAR, ownerId).forEach { mapping ->
            val eventId = mapping.externalId.toLongOrNull()
            val startAt = eventId?.let { platform.eventStartMillis(it, ownerId) }
            when {
                eventId == null || startAt == null ->
                    mappings.delete(SystemMappingKind.CALENDAR, mapping.occurrenceId, ownerId)
                startAt < nowMillis ->
                    mappings.delete(SystemMappingKind.CALENDAR, mapping.occurrenceId, ownerId)
                platform.deleteOwned(eventId, ownerId) ->
                    mappings.delete(SystemMappingKind.CALENDAR, mapping.occurrenceId, ownerId)
                else -> pending++
            }
        }
        return pending
    }

    /** 记录脱敏后的日历同步失败原因；日志失败不得影响同步。 */
    private suspend fun log(outcome: String, errorType: String, message: String) {
        runCatching {
            diagnostics.record(
                DiagnosticEvent(
                    timestamp = clock.millis(),
                    category = "calendar",
                    outcome = outcome,
                    errorType = errorType,
                    message = message,
                ),
            )
        }
    }

    private suspend fun ensureOwner(current: String): String {
        if (current.isNotBlank()) return current
        return UUID.randomUUID().toString().replace("-", "").take(16)
    }

    private companion object {
        /** 日历同步窗口（天）：覆盖未来两周。 */
        const val HORIZON_DAYS = 14
    }
}
