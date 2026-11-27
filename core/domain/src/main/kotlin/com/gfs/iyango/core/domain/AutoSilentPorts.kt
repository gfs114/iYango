package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.AutoSilentMode
import com.gfs.iyango.core.model.ReminderSettings
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 单节课程的自动静音/免打扰窗口。 */
data class AutoSilentWindow(
    val occurrenceId: String,
    val courseKey: String,
    val courseName: String,
    val startAtMillis: Long,
    val endAtMillis: Long,
)

/** 上课自动静音协调状态。 */
data class AutoSilentStatus(
    val mode: AutoSilentMode = AutoSilentMode.OFF,
    val policyAccessGranted: Boolean = false,
    val skipOnHolidays: Boolean = true,
    val scheduledCount: Int = 0,
    val activeCourseName: String? = null,
    val exemptCourseKeys: Set<String> = emptySet(),
)

/**
 * 纯领域自动静音窗口规划器。
 *
 * 跳过未启用课程、起止时间未知或无效课程、已下课实例、单课程豁免项与（可选的）法定放假日。
 */
object AutoSilentPlanner {

    fun plan(
        occurrences: List<CourseOccurrence>,
        settings: ReminderSettings,
        now: LocalDateTime,
        zoneId: ZoneId,
        isHolidayOffDay: (LocalDate) -> Boolean = { false },
    ): List<AutoSilentWindow> {
        if (settings.autoSilentMode == AutoSilentMode.OFF) return emptyList()
        return occurrences.asSequence()
            .filter { occ ->
                val startMinute = occ.startMinute ?: return@filter false
                val endMinute = occ.endMinute ?: return@filter false
                if (!occ.course.enabled || endMinute <= startMinute) return@filter false
                if (CourseReminderPlanner.isCourseDisabled(occ.course, settings.autoSilentExemptCourseKeys)) {
                    return@filter false
                }
                if (settings.autoSilentSkipOnHolidays && isHolidayOffDay(occ.date)) {
                    return@filter false
                }
                val endAt = occ.date.atStartOfDay().plusMinutes(endMinute.toLong())
                endAt.isAfter(now)
            }
            .map { occ ->
                val startMinute = occ.startMinute!!
                val endMinute = occ.endMinute!!
                val startAt = occ.date.atStartOfDay().plusMinutes(startMinute.toLong())
                val endAt = occ.date.atStartOfDay().plusMinutes(endMinute.toLong())
                AutoSilentWindow(
                    occurrenceId = occ.occurrenceId,
                    courseKey = CourseReminderPlanner.reminderKey(occ.course),
                    courseName = occ.course.name,
                    startAtMillis = startAt.atZone(zoneId).toInstant().toEpochMilli(),
                    endAtMillis = endAt.atZone(zoneId).toInstant().toEpochMilli(),
                )
            }
            .sortedBy { it.startAtMillis }
            .toList()
    }
}

interface AutoSilentPlatformPort {
    fun hasPolicyAccess(): Boolean

    suspend fun schedule(windows: List<AutoSilentWindow>, mode: AutoSilentMode)

    suspend fun cancelAllAndRestore()

    suspend fun cancelCurrentOccurrence(occurrenceId: String)
}

interface AutoSilentCoordinatorPort {
    val status: StateFlow<AutoSilentStatus>

    suspend fun refresh()

    suspend fun setMode(mode: AutoSilentMode)

    suspend fun setSkipOnHolidays(skip: Boolean)

    suspend fun setCourseExempt(courseKey: String, exempt: Boolean)

    suspend fun restoreNow()
}

object NoopAutoSilentCoordinator : AutoSilentCoordinatorPort {
    override val status: StateFlow<AutoSilentStatus> = MutableStateFlow(AutoSilentStatus())

    override suspend fun refresh() {}

    override suspend fun setMode(mode: AutoSilentMode) {}

    override suspend fun setSkipOnHolidays(skip: Boolean) {}

    override suspend fun setCourseExempt(courseKey: String, exempt: Boolean) {}

    override suspend fun restoreNow() {}
}
