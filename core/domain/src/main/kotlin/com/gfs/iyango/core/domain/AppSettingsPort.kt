package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.AppPreferences
import com.gfs.iyango.core.model.CalendarSettings
import com.gfs.iyango.core.model.DisplaySettingsBundle
import com.gfs.iyango.core.model.ReminderSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow

/**
 * 个性化设置端口（除主题外）：显示配置、提醒、日历与应用偏好。
 *
 * 实现方使用 DataStore；所有读取都必须给出**默认值**，损坏字段回退到默认而不是
 * 让整份设置不可用。
 */
interface AppSettingsPort {

    val displaySettings: Flow<DisplaySettingsBundle>

    suspend fun updateDisplaySettings(bundle: DisplaySettingsBundle)

    /** 只保存全局显示默认，持久化实现应只编辑全局键，保留并发的课表覆盖。 */
    suspend fun updateGlobalDisplay(settings: com.gfs.iyango.core.model.ScheduleDisplaySettings) {
        updateDisplaySettings(displaySettings.first().copy(global = settings))
    }

    /** 保存单课表的显示覆盖；传 null 删除覆盖。 */
    suspend fun updateTimetableDisplay(timetableId: Long, settings: com.gfs.iyango.core.model.ScheduleDisplaySettings?)

    val reminderSettings: Flow<ReminderSettings>

    suspend fun updateReminderSettings(settings: ReminderSettings)

    val calendarSettings: Flow<CalendarSettings>

    suspend fun updateCalendarSettings(settings: CalendarSettings)

    val appPreferences: Flow<AppPreferences>

    suspend fun updateAppPreferences(preferences: AppPreferences)

    /** Atomic read/modify/write in persistent repositories; transforms must not suspend. */
    suspend fun transformAppPreferences(transform: (AppPreferences) -> AppPreferences) {
        updateAppPreferences(transform(appPreferences.first()))
    }

    /**
     * ICS 导出使用的稳定随机命名空间。
     *
     * 首次调用生成并持久化，之后保持不变；重置设置也不会改写它，否则同一事件在
     * 多次导出之间的 UID 会漂移。仅本机使用，不写入设置备份。
     */
    suspend fun exportNamespace(): String

    /** 重置全部非主题设置（保留课程/学期/登录与导出命名空间）。 */
    suspend fun reset()
}
