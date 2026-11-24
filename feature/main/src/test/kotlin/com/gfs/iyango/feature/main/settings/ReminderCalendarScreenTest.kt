package com.gfs.iyango.feature.main.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.domain.AppSettingsPort
import com.gfs.iyango.core.domain.CalendarClearResult
import com.gfs.iyango.core.domain.CalendarCoordinatorPort
import com.gfs.iyango.core.domain.CalendarSyncStatus
import com.gfs.iyango.core.domain.ReminderCoordinatorPort
import com.gfs.iyango.core.domain.ReminderStatus
import com.gfs.iyango.core.model.AppPreferences
import com.gfs.iyango.core.model.CalendarSettings
import com.gfs.iyango.core.model.DisplaySettingsBundle
import com.gfs.iyango.core.model.ReminderSettings
import com.gfs.iyango.core.model.ScheduleDisplaySettings
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderCalendarScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun fakeAppSettings() = object : AppSettingsPort {
        override val displaySettings = MutableStateFlow(DisplaySettingsBundle())
        override val reminderSettings = MutableStateFlow(ReminderSettings())
        override val calendarSettings = MutableStateFlow(CalendarSettings())
        override val appPreferences = MutableStateFlow(AppPreferences())
        override suspend fun updateDisplaySettings(bundle: DisplaySettingsBundle) {}
        override suspend fun updateTimetableDisplay(timetableId: Long, settings: ScheduleDisplaySettings?) {}
        override suspend fun updateReminderSettings(settings: ReminderSettings) {}
        override suspend fun updateCalendarSettings(settings: CalendarSettings) {}
        override suspend fun updateAppPreferences(preferences: AppPreferences) {}
        override suspend fun exportNamespace(): String = ""
        override suspend fun reset() {}
    }

    private fun fakeReminderCoordinator() = object : ReminderCoordinatorPort {
        override val status = MutableStateFlow(ReminderStatus())
        override suspend fun refresh() {}
        override suspend fun setEnabled(enabled: Boolean) {}
        override suspend fun setLeadMinutes(minutes: Int) {}
        override suspend fun setCourseEnabled(key: String, enabled: Boolean) {}
    }

    @Test
    fun `clear calendar button is visible when sync disabled and dismiss cancels without clearing`() {
        var clearCalls = 0
        val calendarCoordinator = object : CalendarCoordinatorPort {
            override val status = MutableStateFlow(
                CalendarSyncStatus(enabled = false, permissionGranted = true),
            )
            override suspend fun refresh() {}
            override suspend fun setEnabled(enabled: Boolean) {}
            override suspend fun selectCalendar(calendarId: Long) {}
            override suspend fun clearCalendar(): CalendarClearResult {
                clearCalls++
                return CalendarClearResult.Completed(0)
            }
        }
        val store = ViewModelStore()
        lateinit var vm: ReminderCalendarViewModel
        compose.runOnIdle {
            vm = ReminderCalendarViewModel(
                appSettings = fakeAppSettings(),
                reminderCoordinator = fakeReminderCoordinator(),
                calendarCoordinator = calendarCoordinator,
            ).also { store.put("rc", it) }
        }
        try {
            compose.setContent {
                IYangoThemePreview {
                    ReminderCalendarScreen(onBack = {}, viewModel = vm)
                }
            }
            compose.onNodeWithText("清除系统日历").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithText("清除系统日历？").assertIsDisplayed()
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithText("清除系统日历？").assertDoesNotExist()
            compose.runOnIdle { assertEquals(0, clearCalls) }
        } finally {
            compose.runOnIdle { store.clear() }
        }
    }

    @Test
    fun `confirming clear calendar invokes coordinator and shows snackbar result`() {
        var clearCalls = 0
        val calendarStatus = MutableStateFlow(
            CalendarSyncStatus(enabled = true, permissionGranted = true, syncedCount = 5),
        )
        val calendarCoordinator = object : CalendarCoordinatorPort {
            override val status = calendarStatus
            override suspend fun refresh() {}
            override suspend fun setEnabled(enabled: Boolean) {}
            override suspend fun selectCalendar(calendarId: Long) {}
            override suspend fun clearCalendar(): CalendarClearResult {
                clearCalls++
                calendarStatus.value = calendarStatus.value.copy(enabled = false, syncedCount = 0)
                return CalendarClearResult.Completed(deletedCount = 5, pendingCount = 0)
            }
        }
        val store = ViewModelStore()
        lateinit var vm: ReminderCalendarViewModel
        compose.runOnIdle {
            vm = ReminderCalendarViewModel(
                appSettings = fakeAppSettings(),
                reminderCoordinator = fakeReminderCoordinator(),
                calendarCoordinator = calendarCoordinator,
            ).also { store.put("rc", it) }
        }
        try {
            compose.setContent {
                IYangoThemePreview {
                    ReminderCalendarScreen(onBack = {}, viewModel = vm)
                }
            }
            compose.onNodeWithText("清除系统日历").performScrollTo().performClick()
            compose.onNodeWithText("清除").performClick()
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(1, clearCalls) }
            compose.onNodeWithText("已清除 5 条课程日程").assertIsDisplayed()
        } finally {
            compose.runOnIdle { store.clear() }
        }
    }
}
