package com.gfs.iyango.feature.main.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.domain.AppSettingsPort
import com.gfs.iyango.core.domain.AutoSilentCoordinatorPort
import com.gfs.iyango.core.domain.AutoSilentStatus
import com.gfs.iyango.core.domain.CalendarClearResult
import com.gfs.iyango.core.domain.CalendarCoordinatorPort
import com.gfs.iyango.core.domain.CalendarSyncStatus
import com.gfs.iyango.core.domain.GradeCheckOutcome
import com.gfs.iyango.core.domain.GradeCheckTrigger
import com.gfs.iyango.core.domain.GradeMonitorCoordinatorPort
import com.gfs.iyango.core.domain.GradeMonitorStatus
import com.gfs.iyango.core.domain.HolidayRepositoryPort
import com.gfs.iyango.core.domain.NoopAutoSilentCoordinator
import com.gfs.iyango.core.domain.NoopGradeMonitorCoordinator
import com.gfs.iyango.core.domain.NoopHolidayRepository
import com.gfs.iyango.core.domain.ReminderCoordinatorPort
import com.gfs.iyango.core.domain.ReminderStatus
import com.gfs.iyango.core.model.AutoSilentMode
import com.gfs.iyango.core.model.ReminderSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 提醒与日历设置页 ViewModel（F12/F13）。
 *
 * 两个通道状态分别来自各自协调器，互不覆盖；所有开关先写偏好再立即重算。
 */
@HiltViewModel
class ReminderCalendarViewModel @Inject constructor(
    private val appSettings: AppSettingsPort,
    private val reminderCoordinator: ReminderCoordinatorPort,
    private val calendarCoordinator: CalendarCoordinatorPort,
    private val gradeMonitorCoordinator: GradeMonitorCoordinatorPort = NoopGradeMonitorCoordinator,
    private val autoSilentCoordinator: AutoSilentCoordinatorPort = NoopAutoSilentCoordinator,
    private val holidayRepository: HolidayRepositoryPort = NoopHolidayRepository,
) : ViewModel() {

    private val reminderSettings = appSettings.reminderSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, ReminderSettings())

    val uiState: StateFlow<ReminderCalendarUiState> = combine(
        reminderCoordinator.status,
        calendarCoordinator.status,
        reminderSettings,
        gradeMonitorCoordinator.status,
        autoSilentCoordinator.status,
    ) { reminder, calendar, reminderPrefs, gradeMonitor, autoSilent ->
        ReminderCalendarUiState(
            reminder = reminder,
            calendar = calendar,
            leadMinutes = reminderPrefs.leadMinutes,
            ongoingProgressEnabled = reminderPrefs.ongoingProgressEnabled,
            skipRemindersOnHolidays = reminderPrefs.skipRemindersOnHolidays,
            gradeMonitor = gradeMonitor,
            autoSilent = autoSilent,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ReminderCalendarUiState())

    fun refresh() {
        viewModelScope.launch {
            runCatching { reminderCoordinator.refresh() }
            runCatching { autoSilentCoordinator.refresh() }
            runCatching { calendarCoordinator.refresh() }
            runCatching { gradeMonitorCoordinator.syncSchedule() }
        }
    }

    fun setReminderEnabled(enabled: Boolean) {
        viewModelScope.launch { reminderCoordinator.setEnabled(enabled) }
    }

    fun setLeadMinutes(minutes: Int) {
        viewModelScope.launch { reminderCoordinator.setLeadMinutes(minutes) }
    }

    fun setOngoingProgressEnabled(enabled: Boolean) {
        viewModelScope.launch { reminderCoordinator.setOngoingProgressEnabled(enabled) }
    }

    suspend fun sendLiveProgressPreview(): Boolean =
        runCatching { reminderCoordinator.sendLiveProgressPreview() }.getOrDefault(false)

    fun setCourseEnabled(key: String, enabled: Boolean) {
        viewModelScope.launch { reminderCoordinator.setCourseEnabled(key, enabled) }
    }

    fun setCalendarEnabled(enabled: Boolean) {
        viewModelScope.launch { calendarCoordinator.setEnabled(enabled) }
    }

    fun selectCalendar(calendarId: Long) {
        viewModelScope.launch { calendarCoordinator.selectCalendar(calendarId) }
    }

    suspend fun clearCalendar(): CalendarClearResult =
        runCatching { calendarCoordinator.clearCalendar() }
            .getOrDefault(CalendarClearResult.Completed(0))

    fun setGradeNotifyEnabled(enabled: Boolean) {
        viewModelScope.launch { gradeMonitorCoordinator.setEnabled(enabled) }
    }

    fun setGradeCheckIntervalHours(hours: Int) {
        viewModelScope.launch { gradeMonitorCoordinator.setIntervalHours(hours) }
    }

    fun setGradeNotifyHideScoreOnLockscreen(hide: Boolean) {
        viewModelScope.launch { gradeMonitorCoordinator.setHideScoreOnLockscreen(hide) }
    }

    fun setGradeSprintEnabled(enabled: Boolean) {
        viewModelScope.launch { gradeMonitorCoordinator.setSprintEnabled(enabled) }
    }

    fun setGradeSprintIntervalMinutes(minutes: Int) {
        viewModelScope.launch { gradeMonitorCoordinator.setSprintIntervalMinutes(minutes) }
    }

    fun setGradeSprintDurationDays(days: Int) {
        viewModelScope.launch { gradeMonitorCoordinator.setSprintDurationDays(days) }
    }

    fun setGradeContextTriggerEnabled(enabled: Boolean) {
        viewModelScope.launch { gradeMonitorCoordinator.setContextTriggerEnabled(enabled) }
    }

    suspend fun checkGradesNow(): GradeCheckOutcome =
        runCatching { gradeMonitorCoordinator.checkOnTrigger(GradeCheckTrigger.MANUAL_CHECK_IN_SETTINGS) }
            .getOrDefault(GradeCheckOutcome.FAILED)

    fun setSkipRemindersOnHolidays(skip: Boolean) {
        viewModelScope.launch { reminderCoordinator.setSkipRemindersOnHolidays(skip) }
    }

    suspend fun refreshHolidays(): Boolean {
        val currentYear = java.time.LocalDate.now().year
        val r1 = runCatching { holidayRepository.refreshYear(currentYear).isSuccess }.getOrDefault(false)
        val r2 = runCatching { holidayRepository.refreshYear(currentYear + 1).isSuccess }.getOrDefault(false)
        runCatching { reminderCoordinator.refresh() }
        runCatching { autoSilentCoordinator.refresh() }
        return r1 || r2
    }

    fun setAutoSilentMode(mode: AutoSilentMode) {
        viewModelScope.launch { autoSilentCoordinator.setMode(mode) }
    }

    fun setAutoSilentSkipOnHolidays(skip: Boolean) {
        viewModelScope.launch { autoSilentCoordinator.setSkipOnHolidays(skip) }
    }

    fun setAutoSilentCourseExempt(courseKey: String, exempt: Boolean) {
        viewModelScope.launch { autoSilentCoordinator.setCourseExempt(courseKey, exempt) }
    }

    fun restoreAutoSilentNow() {
        viewModelScope.launch { autoSilentCoordinator.restoreNow() }
    }
}

/** 提醒与日历页状态。 */
data class ReminderCalendarUiState(
    val reminder: ReminderStatus = ReminderStatus(),
    val calendar: CalendarSyncStatus = CalendarSyncStatus(),
    val leadMinutes: Int = 10,
    val ongoingProgressEnabled: Boolean = true,
    val skipRemindersOnHolidays: Boolean = true,
    val gradeMonitor: GradeMonitorStatus = GradeMonitorStatus(),
    val autoSilent: AutoSilentStatus = AutoSilentStatus(),
)
