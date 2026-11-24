package com.gfs.iyango.core.domain

import kotlinx.coroutines.flow.StateFlow

/** 单门课程的提醒开关状态（UI 列表用）。 */
data class ReminderCourseOption(
    val key: String = "",
    val name: String = "",
    val teacher: String = "",
    val enabled: Boolean = true,
)

/**
 * 提醒系统状态（F12）。
 *
 * @property plannedCount 本次规划产生的提醒数。
 * @property scheduledCount 实际写入系统的提醒数。
 * @property failures 排程失败/降级原因（权限、精确闹钟等）。
 * @property skipped 规划阶段跳过的原因（时间待定、课程关闭提醒等）。
 * @property exactAlarmsAllowed 精确闹钟能力；false 时排程会降级为准点性较弱的方式。
 * @property notificationsAllowed 通知权限/通道是否可用。
 * @property liveUpdatesAllowed 实时通知（Android 16 Live Update 状态栏胶囊）是否可用。
 */
data class ReminderStatus(
    val enabled: Boolean = false,
    val leadMinutes: Int = 10,
    val notificationsAllowed: Boolean = false,
    val exactAlarmsAllowed: Boolean = false,
    val liveUpdatesAllowed: Boolean = false,
    val ongoingProgressEnabled: Boolean = true,
    val plannedCount: Int = 0,
    val scheduledCount: Int = 0,
    val skipped: List<String> = emptyList(),
    val failures: List<String> = emptyList(),
    val lastSyncAt: Long = 0,
    val courses: List<ReminderCourseOption> = emptyList(),
)

/** 平台提醒排程结果。 */
data class ReminderScheduleResult(
    val scheduled: Int = 0,
    val failures: List<String> = emptyList(),
)

/**
 * 平台提醒能力端口（Android 实现在 platform:android）。
 *
 * 实现必须使用显式目标 + 稳定身份（occurrenceId）的 PendingIntent；Receiver 触发时
 * 复核当前计划，旧计划不得再发通知。
 */
interface ReminderPlatformPort {

    /** 通知权限与通知通道是否都可用于展示。 */
    fun notificationsAllowed(): Boolean

    /** 是否允许精确闹钟（API 31+ 需用户授权）。 */
    fun exactAlarmsAllowed(): Boolean

    /** 是否允许实时通知（Android 16 Live Update 状态栏胶囊）；低版本系统返回 false。 */
    fun liveUpdatesAllowed(): Boolean

    /**
     * 用 [items] 对齐系统闹钟：取消不在列表中的旧计划，新增/更新变化的计划。
     *
     * @param ownerId 安装实例标记，用于隔离本机计划存储。
     */
    suspend fun schedule(items: List<ReminderPlanItem>, ownerId: String): ReminderScheduleResult

    /** 取消指定实例的提醒。 */
    suspend fun cancel(occurrenceIds: Collection<String>, ownerId: String)

    /** 取消本应用全部提醒。 */
    suspend fun cancelAll(ownerId: String)

    /** 立即发送一条“正在上课实时进度条”演示通知，用于真机验证与预览。 */
    fun showProgressPreview(): Boolean = false
}

/**
 * 提醒协调端口（F12）：规划 → 排程 → 状态。
 *
 * 实现负责把有效课表实例交给 [CourseReminderPlanner]，并在课表/设置变化后重建计划。
 */
interface ReminderCoordinatorPort {

    val status: StateFlow<ReminderStatus>

    /** 重新读取设置并重建计划（权限变化、开机、时间/时区变化后调用）。 */
    suspend fun refresh()

    suspend fun setEnabled(enabled: Boolean)

    suspend fun setLeadMinutes(minutes: Int)

    /** 开关“上课实时进度常驻通知”。 */
    suspend fun setOngoingProgressEnabled(enabled: Boolean) {}

    /** 发送实时进度常驻通知预览。 */
    suspend fun sendLiveProgressPreview(): Boolean = false

    /** 单门课程提醒开关；[key] 为 [CourseReminderPlanner.courseKey]。 */
    suspend fun setCourseEnabled(key: String, enabled: Boolean)
}
