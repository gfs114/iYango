package com.gfs.iyango.data.repository

import com.gfs.iyango.core.domain.AppSettingsPort
import com.gfs.iyango.core.domain.GradeChangeDetector
import com.gfs.iyango.core.domain.GradeCheckOutcome
import com.gfs.iyango.core.domain.GradeCheckSchedulerPort
import com.gfs.iyango.core.domain.GradeCheckTrigger
import com.gfs.iyango.core.domain.GradeCurrentSemesterResolverPort
import com.gfs.iyango.core.domain.GradeMonitorCoordinatorPort
import com.gfs.iyango.core.domain.GradeMonitorSchedulePolicy
import com.gfs.iyango.core.domain.GradeMonitorStatus
import com.gfs.iyango.core.domain.GradeNotificationPort
import com.gfs.iyango.core.domain.GradeRepositoryPort
import com.gfs.iyango.core.domain.NoopGradeCurrentSemesterResolver
import com.gfs.iyango.core.domain.RefreshCoordinatorPort
import com.gfs.iyango.core.domain.RefreshOutcome
import com.gfs.iyango.core.domain.RefreshResource
import com.gfs.iyango.core.domain.SchoolAuthPort
import com.gfs.iyango.core.model.GradeRecord
import com.gfs.iyango.core.model.ReminderSettings
import com.gfs.iyango.core.model.SchoolConnection
import com.gfs.iyango.core.model.SchoolConnectionStatus
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 新出分通知与周期检查协调器。
 *
 * - 统一走 [RefreshCoordinatorPort] 的 `RefreshResource.GRADES`（`*` 分区），与成绩页手动刷新互斥防并发；
 * - 仅当主动由本协调器发起的静默/设置页检查触发通知，绝不在成绩页手动刷新时误弹；
 * - 遇 `sessionExpired` 立即暂停后台周期唤醒防教务风控，待重新登录 `ACTIVE` 后自动恢复。
 */
@Singleton
class DefaultGradeMonitorCoordinator @Inject constructor(
    private val appSettings: AppSettingsPort,
    private val schoolAuth: SchoolAuthPort,
    private val refreshCoordinator: RefreshCoordinatorPort,
    private val notificationPort: GradeNotificationPort,
    private val schedulerPort: GradeCheckSchedulerPort,
    private val notifiedStore: GradeNotifiedStore,
    private val notificationBridge: GradeRefreshNotificationBridge,
    private val clock: Clock,
    private val semesterResolver: GradeCurrentSemesterResolverPort = NoopGradeCurrentSemesterResolver,
) : GradeMonitorCoordinatorPort {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkMutex = Mutex()
    private val _status = MutableStateFlow(GradeMonitorStatus())
    override val status: StateFlow<GradeMonitorStatus> = _status.asStateFlow()

    @Volatile
    private var previousKnownAccounts: Set<String> = emptySet()

    init {
        scope.launch {
            combine(
                appSettings.reminderSettings,
                schoolAuth.observeActiveConnection(),
                schoolAuth.observeConnections(),
                notifiedStore.state,
            ) { reminder, active, all, local ->
                val sanitized = reminder.sanitized()
                val usable = pickUsableConnection(active, all)
                val currentAccounts = all
                    .filter { it.status != SchoolConnectionStatus.LOGGED_OUT }
                    .map { it.accountSubject.ifEmpty { it.id } }
                    .toSet()

                val removedAccounts = previousKnownAccounts - currentAccounts
                previousKnownAccounts = currentAccounts
                removedAccounts.forEach { removed ->
                    notificationPort.cancelForAccount(removed)
                    notifiedStore.clearAccount(removed)
                }

                if (usable?.status == SchoolConnectionStatus.ACTIVE && local.pausedBySessionExpired) {
                    notifiedStore.clearSessionExpiredPause()
                }

                val now = clock.millis()
                val sprintActive = GradeMonitorSchedulePolicy.isSprintActive(sanitized, now)
                val sprintSemester = if (sprintActive && usable != null) {
                    val accountKey = usable.accountSubject.ifEmpty { usable.id }
                    runCatching { semesterResolver.resolveCurrentSemester(usable.id, accountKey) }
                        .getOrNull()
                        .orEmpty()
                } else {
                    ""
                }

                val paused = local.pausedBySessionExpired && usable?.status != SchoolConnectionStatus.ACTIVE
                GradeMonitorStatus(
                    enabled = sanitized.gradeNotifyEnabled,
                    intervalHours = sanitized.gradeCheckIntervalHours,
                    hideScoreOnLockscreen = sanitized.gradeNotifyHideScoreOnLockscreen,
                    notificationsAllowed = notificationPort.notificationsAllowed(),
                    liveUpdatesAllowed = notificationPort.liveUpdatesAllowed(),
                    hasAccount = usable != null,
                    sessionExpiredPaused = paused,
                    lastCheckAt = local.lastCheckAt,
                    lastOutcome = local.outcomeEnum(),
                    lastNotifiedCount = local.lastNotifiedCount,
                    lastError = local.lastError,
                    sprintActive = sprintActive,
                    sprintIntervalMinutes = sanitized.gradeSprintIntervalMinutes,
                    sprintDurationDays = sanitized.gradeSprintDurationDays,
                    sprintUntilMillis = sanitized.gradeSprintUntilMillis,
                    contextTriggerEnabled = sanitized.gradeContextTriggerEnabled,
                    activeSprintSemester = sprintSemester,
                )
            }.collect { next ->
                _status.value = next
            }
        }
    }

    override suspend fun setEnabled(enabled: Boolean) {
        val current = appSettings.reminderSettings.first()
        appSettings.updateReminderSettings(
            if (enabled) {
                current.copy(gradeNotifyEnabled = true).sanitized()
            } else {
                current.copy(
                    gradeNotifyEnabled = false,
                    gradeSprintEnabled = false,
                    gradeSprintUntilMillis = 0L,
                ).sanitized()
            },
        )
        if (!enabled) {
            schedulerPort.cancel()
            schedulerPort.setContextTriggersActive(false)
            notificationPort.cancelAll()
        } else {
            syncSchedule()
        }
    }

    override suspend fun setIntervalHours(hours: Int) {
        val current = appSettings.reminderSettings.first()
        val updated = current.copy(gradeCheckIntervalHours = hours).sanitized()
        appSettings.updateReminderSettings(updated)
        if (updated.gradeNotifyEnabled) {
            syncSchedule()
        }
    }

    override suspend fun setHideScoreOnLockscreen(hide: Boolean) {
        val current = appSettings.reminderSettings.first()
        appSettings.updateReminderSettings(current.copy(gradeNotifyHideScoreOnLockscreen = hide).sanitized())
    }

    override suspend fun setSprintEnabled(enabled: Boolean) {
        val current = appSettings.reminderSettings.first().sanitized()
        val now = clock.millis()
        val updated = if (enabled) {
            current.copy(
                gradeNotifyEnabled = true,
                gradeSprintEnabled = true,
                gradeSprintUntilMillis = now + current.gradeSprintDurationDays * DAY_MILLIS,
            ).sanitized()
        } else {
            current.copy(
                gradeSprintEnabled = false,
                gradeSprintUntilMillis = 0L,
            ).sanitized()
        }
        appSettings.updateReminderSettings(updated)
        syncSchedule()
    }

    override suspend fun setSprintIntervalMinutes(minutes: Int) {
        val current = appSettings.reminderSettings.first().sanitized()
        val updated = current.copy(gradeSprintIntervalMinutes = minutes).sanitized()
        appSettings.updateReminderSettings(updated)
        if (updated.gradeNotifyEnabled) {
            syncSchedule()
        }
    }

    override suspend fun setSprintDurationDays(days: Int) {
        val current = appSettings.reminderSettings.first().sanitized()
        val clampedDays = current.copy(gradeSprintDurationDays = days).sanitized().gradeSprintDurationDays
        val now = clock.millis()
        val updatedUntil = if (GradeMonitorSchedulePolicy.isSprintActive(current, now)) {
            now + clampedDays * DAY_MILLIS
        } else {
            current.gradeSprintUntilMillis
        }
        val updated = current.copy(
            gradeSprintDurationDays = clampedDays,
            gradeSprintUntilMillis = updatedUntil,
        ).sanitized()
        appSettings.updateReminderSettings(updated)
        if (updated.gradeNotifyEnabled) {
            syncSchedule()
        }
    }

    override suspend fun setContextTriggerEnabled(enabled: Boolean) {
        val current = appSettings.reminderSettings.first().sanitized()
        val updated = current.copy(gradeContextTriggerEnabled = enabled).sanitized()
        appSettings.updateReminderSettings(updated)
        if (updated.gradeNotifyEnabled) {
            syncSchedule()
        }
    }

    override suspend fun syncSchedule() {
        val now = clock.millis()
        val settings = sanitizeAndAutoDowngradeSprint(now)
        val usable = currentUsableConnection()
        val local = notifiedStore.current()
        if (!settings.gradeNotifyEnabled || usable == null) {
            schedulerPort.cancel()
            schedulerPort.setContextTriggersActive(false)
            refreshStatusSnapshot(settings, usable, now)
            return
        }
        if (usable.status != SchoolConnectionStatus.ACTIVE && !usable.rememberPassword) {
            schedulerPort.cancel()
            schedulerPort.setContextTriggersActive(false)
            refreshStatusSnapshot(settings, usable, now)
            return
        }
        if (local.pausedBySessionExpired && usable.status != SchoolConnectionStatus.ACTIVE) {
            schedulerPort.cancel()
            schedulerPort.setContextTriggersActive(false)
            refreshStatusSnapshot(settings, usable, now)
            return
        }
        val delayMs = GradeMonitorSchedulePolicy.computeNextDelayMillis(
            settings = settings,
            lastCheckAt = local.lastCheckAt,
            nowMillis = now,
            zone = clock.zone,
        )
        schedulerPort.scheduleNext(delayMs)
        schedulerPort.setContextTriggersActive(settings.gradeContextTriggerEnabled)
        refreshStatusSnapshot(settings, usable, now)
    }

    override suspend fun checkOnTrigger(trigger: GradeCheckTrigger): GradeCheckOutcome =
        checkMutex.withLock {
            if (trigger == GradeCheckTrigger.USER_MANUAL_IN_GRADES_PAGE) {
                return@withLock GradeCheckOutcome.IDLE
            }
            val now = clock.millis()
            val settings = sanitizeAndAutoDowngradeSprint(now)
            val manual = trigger == GradeCheckTrigger.MANUAL_CHECK_IN_SETTINGS
            if (!manual && !settings.gradeNotifyEnabled) {
                return@withLock GradeCheckOutcome.SKIPPED_DISABLED
            }

            val isContextTrigger = trigger == GradeCheckTrigger.WIFI_CONNECTED ||
                trigger == GradeCheckTrigger.POWER_CONNECTED
            if (isContextTrigger && !settings.gradeContextTriggerEnabled) {
                return@withLock GradeCheckOutcome.SKIPPED_DISABLED
            }

            val connection = currentUsableConnection()
            if (connection == null) {
                schedulerPort.cancel()
                schedulerPort.setContextTriggersActive(false)
                notifiedStore.recordCheckResult(
                    checkAt = now,
                    outcome = GradeCheckOutcome.SKIPPED_NO_ACCOUNT,
                    notifiedCount = 0,
                    error = "尚未登录教务账号",
                )
                return@withLock GradeCheckOutcome.SKIPPED_NO_ACCOUNT
            }

            val local = notifiedStore.current()
            if (!manual && local.pausedBySessionExpired && connection.status != SchoolConnectionStatus.ACTIVE) {
                schedulerPort.cancel()
                schedulerPort.setContextTriggersActive(false)
                return@withLock GradeCheckOutcome.PAUSED_SESSION_EXPIRED
            }

            val sprintActive = GradeMonitorSchedulePolicy.isSprintActive(settings, now)
            if (trigger == GradeCheckTrigger.WIFI_CONNECTED && !schedulerPort.isOnWifi()) {
                return@withLock GradeCheckOutcome.SKIPPED_NO_NETWORK
            }
            if ((isContextTrigger || (trigger == GradeCheckTrigger.BACKGROUND_PERIODIC && sprintActive)) &&
                !GradeMonitorSchedulePolicy.isInDaytimeWindow(now, clock.zone)
            ) {
                if (settings.gradeNotifyEnabled && trigger == GradeCheckTrigger.BACKGROUND_PERIODIC) {
                    val delay = GradeMonitorSchedulePolicy.computeNextDelayMillis(
                        settings = settings,
                        lastCheckAt = local.lastCheckAt,
                        nowMillis = now,
                        zone = clock.zone,
                    )
                    schedulerPort.scheduleNext(delay)
                }
                return@withLock GradeCheckOutcome.SKIPPED_QUIET_HOURS
            }

            val cooldownMs = GradeMonitorSchedulePolicy.cooldownMillis(trigger, sprintActive)
            if (cooldownMs > 0L) {
                val sinceLast = now - local.lastCheckAt
                if (local.lastCheckAt > 0L && sinceLast in 0L until cooldownMs) {
                    return@withLock GradeCheckOutcome.SKIPPED_COOLDOWN
                }
            }

            if (!schedulerPort.hasNetwork()) {
                if (settings.gradeNotifyEnabled) {
                    val delay = GradeMonitorSchedulePolicy.computeNextDelayMillis(
                        settings = settings,
                        lastCheckAt = local.lastCheckAt,
                        nowMillis = now,
                        zone = clock.zone,
                    )
                    schedulerPort.scheduleNext(delay)
                }
                notifiedStore.recordCheckResult(
                    checkAt = now,
                    outcome = GradeCheckOutcome.SKIPPED_NO_NETWORK,
                    notifiedCount = 0,
                    error = if (manual) "当前无可用网络连接" else "",
                )
                return@withLock GradeCheckOutcome.SKIPPED_NO_NETWORK
            }

            val accountKey = connection.accountSubject.ifEmpty { connection.id }
            val sprintSemester = if (sprintActive && !manual) {
                runCatching { semesterResolver.resolveCurrentSemester(connection.id, accountKey) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() && it != GradeRepositoryPort.ALL_SEMESTERS }
            } else {
                null
            }
            val queryPartition = sprintSemester ?: GradeRepositoryPort.ALL_SEMESTERS
            val beforeSeq = notificationBridge.currentSeq()
            val task = refreshCoordinator.refresh(
                resource = RefreshResource.GRADES,
                queryFingerprint = queryPartition,
            )
            val afterNow = clock.millis()

            if (task.sessionExpired) {
                schedulerPort.cancel()
                schedulerPort.setContextTriggersActive(false)
                notifiedStore.recordCheckResult(
                    checkAt = afterNow,
                    outcome = GradeCheckOutcome.PAUSED_SESSION_EXPIRED,
                    notifiedCount = 0,
                    error = "教务登录已失效，已暂停后台检查",
                    pausedBySessionExpired = true,
                )
                return@withLock GradeCheckOutcome.PAUSED_SESSION_EXPIRED
            }

            if (task.outcome != RefreshOutcome.SUCCESS) {
                if (settings.gradeNotifyEnabled) {
                    val delay = GradeMonitorSchedulePolicy.computeNextDelayMillis(
                        settings = settings,
                        lastCheckAt = local.lastCheckAt,
                        nowMillis = afterNow,
                        zone = clock.zone,
                    )
                    schedulerPort.scheduleNext(delay)
                }
                notifiedStore.recordCheckResult(
                    checkAt = afterNow,
                    outcome = GradeCheckOutcome.FAILED,
                    notifiedCount = 0,
                    error = task.message.ifBlank { "检查成绩失败" },
                )
                return@withLock GradeCheckOutcome.FAILED
            }

            val delta = notificationBridge.consumeIfNewer(beforeSeq, accountKey)
            val payload = if (delta != null) {
                GradeChangeDetector.detect(
                    accountKey = accountKey,
                    partition = delta.partition,
                    hadBaseline = delta.hadBaseline,
                    previousRecords = delta.previousRecords,
                    newRecords = delta.newRecords,
                    alreadyNotifiedKeys = local.keysForAccount(accountKey),
                )
            } else {
                null
            }

            if (settings.gradeNotifyEnabled) {
                val delay = GradeMonitorSchedulePolicy.computeNextDelayMillis(
                    settings = settings,
                    lastCheckAt = afterNow,
                    nowMillis = afterNow,
                    zone = clock.zone,
                )
                schedulerPort.scheduleNext(delay)
                schedulerPort.setContextTriggersActive(settings.gradeContextTriggerEnabled)
            }

            if (payload != null) {
                val posted = notificationPort.showGradeChange(
                    payload = payload,
                    hideScoreOnLockscreen = settings.gradeNotifyHideScoreOnLockscreen,
                )
                val savedKeys = if (posted) payload.dedupeKeys else emptySet()
                notifiedStore.recordCheckResult(
                    checkAt = afterNow,
                    outcome = GradeCheckOutcome.NOTIFIED,
                    notifiedCount = payload.items.size,
                    pausedBySessionExpired = false,
                    accountKeyForNewKeys = accountKey,
                    newNotifiedKeys = savedKeys,
                )
                if (queryPartition != GradeRepositoryPort.ALL_SEMESTERS) {
                    // 仅查当前学期命中新出分后，后台补全一次全学期缓存，保证成绩总览与学分进度最新。
                    scope.launch {
                        runCatching {
                            refreshCoordinator.refresh(
                                resource = RefreshResource.GRADES,
                                queryFingerprint = GradeRepositoryPort.ALL_SEMESTERS,
                            )
                        }
                    }
                }
                return@withLock GradeCheckOutcome.NOTIFIED
            }

            notifiedStore.recordCheckResult(
                checkAt = afterNow,
                outcome = GradeCheckOutcome.CHECKED_NO_CHANGES,
                notifiedCount = 0,
                pausedBySessionExpired = false,
            )
            GradeCheckOutcome.CHECKED_NO_CHANGES
        }

    private suspend fun sanitizeAndAutoDowngradeSprint(now: Long): ReminderSettings {
        val raw = appSettings.reminderSettings.first()
        val sanitized = raw.sanitized()
        if (GradeMonitorSchedulePolicy.isSprintExpired(sanitized, now)) {
            val downgraded = sanitized.copy(
                gradeSprintEnabled = false,
                gradeSprintUntilMillis = 0L,
            ).sanitized()
            appSettings.updateReminderSettings(downgraded)
            return downgraded
        }
        return sanitized
    }

    override suspend fun clearPostedNotification(accountKey: String) {
        if (accountKey.isBlank()) return
        notificationPort.cancelForAccount(accountKey)
    }

    private suspend fun currentUsableConnection(): SchoolConnection? {
        val active = schoolAuth.observeActiveConnection().first()
        val all = schoolAuth.observeConnections().first()
        return pickUsableConnection(active, all)
    }

    private fun pickUsableConnection(
        active: SchoolConnection?,
        all: List<SchoolConnection>,
    ): SchoolConnection? =
        active?.takeIf { it.status != SchoolConnectionStatus.LOGGED_OUT }
            ?: all.filter { it.status != SchoolConnectionStatus.LOGGED_OUT && (it.status == SchoolConnectionStatus.ACTIVE || it.rememberPassword) }
                .maxByOrNull { it.lastLoginAt }

    private suspend fun refreshStatusSnapshot(
        settings: ReminderSettings,
        usable: SchoolConnection?,
        now: Long,
    ) {
        val sprintActive = GradeMonitorSchedulePolicy.isSprintActive(settings, now)
        val sprintSemester = if (sprintActive && usable != null) {
            val accountKey = usable.accountSubject.ifEmpty { usable.id }
            runCatching { semesterResolver.resolveCurrentSemester(usable.id, accountKey) }
                .getOrNull()
                .orEmpty()
        } else {
            ""
        }
        _status.value = _status.value.copy(
            enabled = settings.gradeNotifyEnabled,
            intervalHours = settings.gradeCheckIntervalHours,
            hideScoreOnLockscreen = settings.gradeNotifyHideScoreOnLockscreen,
            notificationsAllowed = notificationPort.notificationsAllowed(),
            liveUpdatesAllowed = notificationPort.liveUpdatesAllowed(),
            sprintActive = sprintActive,
            sprintIntervalMinutes = settings.gradeSprintIntervalMinutes,
            sprintDurationDays = settings.gradeSprintDurationDays,
            sprintUntilMillis = settings.gradeSprintUntilMillis,
            contextTriggerEnabled = settings.gradeContextTriggerEnabled,
            activeSprintSemester = sprintSemester,
        )
    }

    companion object {
        const val HOUR_MILLIS = GradeMonitorSchedulePolicy.HOUR_MILLIS
        const val DAY_MILLIS = GradeMonitorSchedulePolicy.DAY_MILLIS
        const val FOREGROUND_COOLDOWN_MS = GradeMonitorSchedulePolicy.NORMAL_FOREGROUND_COOLDOWN_MS
        private const val MIN_RESCHEDULE_DELAY_MS = GradeMonitorSchedulePolicy.MIN_RESCHEDULE_DELAY_MS
    }
}
