package com.gfs.iyango.core.domain

import kotlinx.coroutines.flow.StateFlow

/** 系统可写日历（UI 让用户显式选择，避免静默写入）。 */
data class SystemCalendar(
    val id: Long = 0,
    val name: String = "",
    val accountName: String = "",
    val isPrimary: Boolean = false,
)

/**
 * 日历同步状态（F13）。
 *
 * @property pendingCleanup 权限撤销/所有权校验失败导致暂时无法删除的事件数。
 */
data class CalendarSyncStatus(
    val enabled: Boolean = false,
    val permissionGranted: Boolean = false,
    val calendars: List<SystemCalendar> = emptyList(),
    val selectedCalendarId: Long = -1,
    val syncedCount: Int = 0,
    val pendingCleanup: Int = 0,
    val skipped: List<String> = emptyList(),
    val lastSyncAt: Long = 0,
    val lastError: String = "",
)

/** 平台日历写入结果。 */
sealed interface CalendarWriteResult {
    data class Success(val eventId: Long) : CalendarWriteResult

    /** 事件存在但所有权标记不匹配（用户自建或已换签名），不得删除/覆盖。 */
    data object NotOwned : CalendarWriteResult

    data class Failure(val message: String) : CalendarWriteResult
}

/** 平台批量删除本实例日历事件的结果。 */
data class CalendarDeleteSummary(
    val deleted: Int = 0,
    val failedEventIds: Set<Long> = emptySet(),
)

/** 清除系统日历操作结果。 */
sealed interface CalendarClearResult {
    data object PermissionDenied : CalendarClearResult

    data class Completed(
        val deletedCount: Int,
        val pendingCount: Int = 0,
    ) : CalendarClearResult
}

/**
 * 平台日历端口（Android CalendarProvider 实现）。
 *
 * 只操作通过 [CalendarWriteResult.NotOwned] 校验的应用自有事件；事件正文带
 * ownerId 标记，禁止按课程名/时间模糊删除。
 */
interface CalendarPlatformPort {

    fun hasPermission(): Boolean

    suspend fun calendars(): List<SystemCalendar>

    /**
     * 插入或更新一条应用自有事件。
     *
     * @param existingEventId 已有映射的事件 id；null 表示新增。
     * @param ownerId 安装实例标记，写入正文作为所有权凭证。
     */
    suspend fun upsert(
        plan: CalendarEventPlan,
        existingEventId: Long?,
        calendarId: Long,
        ownerId: String,
    ): CalendarWriteResult

    /** 删除前校验所有权；返回是否确实删除。 */
    suspend fun deleteOwned(eventId: Long, ownerId: String): Boolean

    /** 删除系统日历中所有带本实例 ownerId 标记的事件（含历史与未来），不影响用户自建事件。 */
    suspend fun deleteAllOwned(ownerId: String): CalendarDeleteSummary = CalendarDeleteSummary()

    /** 事件开始时刻；事件不存在、无权限或不属于本实例时返回 null。 */
    suspend fun eventStartMillis(eventId: Long, ownerId: String): Long?
}

/** 日历同步协调端口（F13）。 */
interface CalendarCoordinatorPort {

    val status: StateFlow<CalendarSyncStatus>

    /** 重新读取设置/权限并做差异同步。 */
    suspend fun refresh()

    suspend fun setEnabled(enabled: Boolean)

    /** 用户选定目标日历。 */
    suspend fun selectCalendar(calendarId: Long)

    /** 清除本应用写入系统日历的全部事件（含历史与未来），并关闭自动同步。 */
    suspend fun clearCalendar(): CalendarClearResult = CalendarClearResult.Completed(0)
}
