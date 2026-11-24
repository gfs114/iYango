package com.gfs.iyango.core.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 成绩更新检查触发来源。 */
enum class GradeCheckTrigger {
    USER_MANUAL_IN_GRADES_PAGE,
    FOREGROUND_SILENT,
    BACKGROUND_PERIODIC,
    WIFI_CONNECTED,
    POWER_CONNECTED,
    MANUAL_CHECK_IN_SETTINGS,
}

/** 一次成绩检查的执行结论。 */
enum class GradeCheckOutcome {
    IDLE,
    CHECKED_NO_CHANGES,
    NOTIFIED,
    SKIPPED_DISABLED,
    SKIPPED_NO_ACCOUNT,
    SKIPPED_NO_NETWORK,
    SKIPPED_COOLDOWN,
    SKIPPED_QUIET_HOURS,
    PAUSED_SESSION_EXPIRED,
    FAILED,
}

/** 新出分通知协调器的聚合展示状态。 */
data class GradeMonitorStatus(
    val enabled: Boolean = false,
    val intervalHours: Int = 4,
    val hideScoreOnLockscreen: Boolean = true,
    val notificationsAllowed: Boolean = false,
    val liveUpdatesAllowed: Boolean = false,
    val hasAccount: Boolean = false,
    val sessionExpiredPaused: Boolean = false,
    val lastCheckAt: Long = 0L,
    val lastOutcome: GradeCheckOutcome = GradeCheckOutcome.IDLE,
    val lastNotifiedCount: Int = 0,
    val lastError: String = "",
    val sprintActive: Boolean = false,
    val sprintIntervalMinutes: Int = 30,
    val sprintDurationDays: Int = 7,
    val sprintUntilMillis: Long = 0L,
    val contextTriggerEnabled: Boolean = true,
    val activeSprintSemester: String = "",
)

/** 解析当前正在进行的教务学期，供冲刺模式缩小拉取范围。 */
interface GradeCurrentSemesterResolverPort {
    suspend fun resolveCurrentSemester(connectionId: String, accountKey: String): String? = null
}

object NoopGradeCurrentSemesterResolver : GradeCurrentSemesterResolverPort

/** 成绩通知系统展示端口（实现位于 `platform:android`）。 */
interface GradeNotificationPort {
    fun notificationsAllowed(): Boolean
    fun liveUpdatesAllowed(): Boolean = false
    fun showGradeChange(
        payload: GradeChangeNotificationPayload,
        hideScoreOnLockscreen: Boolean,
    ): Boolean
    fun cancelForAccount(accountKey: String)
    fun cancelAll()
}

/** 后台成绩周期检查系统闹钟调度与网络检查端口（实现位于 `platform:android`）。 */
interface GradeCheckSchedulerPort {
    fun scheduleNext(delayMillis: Long)
    fun cancel()
    fun hasNetwork(): Boolean
    fun isOnWifi(): Boolean = false
    fun setContextTriggersActive(enabled: Boolean) = Unit
}

/** 新出分通知与后台周期协调端口。 */
interface GradeMonitorCoordinatorPort {
    val status: StateFlow<GradeMonitorStatus>
    suspend fun setEnabled(enabled: Boolean)
    suspend fun setIntervalHours(hours: Int)
    suspend fun setHideScoreOnLockscreen(hide: Boolean)
    suspend fun setSprintEnabled(enabled: Boolean) = Unit
    suspend fun setSprintIntervalMinutes(minutes: Int) = Unit
    suspend fun setSprintDurationDays(days: Int) = Unit
    suspend fun setContextTriggerEnabled(enabled: Boolean) = Unit
    suspend fun checkOnTrigger(trigger: GradeCheckTrigger): GradeCheckOutcome
    suspend fun syncSchedule()
    suspend fun clearPostedNotification(accountKey: String)
}

/** 供无通知上下文的现有单测或默认参数兜底使用。 */
object NoopGradeMonitorCoordinator : GradeMonitorCoordinatorPort {
    override val status: StateFlow<GradeMonitorStatus> = MutableStateFlow(GradeMonitorStatus())
    override suspend fun setEnabled(enabled: Boolean) = Unit
    override suspend fun setIntervalHours(hours: Int) = Unit
    override suspend fun setHideScoreOnLockscreen(hide: Boolean) = Unit
    override suspend fun checkOnTrigger(trigger: GradeCheckTrigger): GradeCheckOutcome =
        GradeCheckOutcome.SKIPPED_DISABLED
    override suspend fun syncSchedule() = Unit
    override suspend fun clearPostedNotification(accountKey: String) = Unit
}
