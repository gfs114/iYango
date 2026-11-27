package com.gfs.iyango.feature.main.about

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.domain.CalendarCoordinatorPort
import com.gfs.iyango.core.domain.AppSettingsPort
import com.gfs.iyango.core.domain.AppUpdateCheckState
import com.gfs.iyango.core.domain.AppUpdatePort
import com.gfs.iyango.core.domain.AppUpdateRelease
import com.gfs.iyango.core.domain.CalendarSyncStatus
import com.gfs.iyango.core.domain.DiagnosticExport
import com.gfs.iyango.core.domain.DiagnosticLogPort
import com.gfs.iyango.core.domain.DiagnosticLogSummary
import com.gfs.iyango.core.domain.RefreshCoordinatorPort
import com.gfs.iyango.core.domain.RefreshState
import com.gfs.iyango.core.domain.ReminderCoordinatorPort
import com.gfs.iyango.core.domain.ReminderStatus
import com.gfs.iyango.core.model.DiagnosticEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 关于与诊断页 ViewModel（F18）。
 *
 * 组合刷新/提醒/日历的近期状态与本地脱敏日志；导出只生成文本，保存动作由用户在
 * 系统文件选择器中确认。
 */
@HiltViewModel
class AboutViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diagnostics: DiagnosticLogPort,
    refreshCoordinator: RefreshCoordinatorPort,
    reminderCoordinator: ReminderCoordinatorPort,
    calendarCoordinator: CalendarCoordinatorPort,
    private val appSettings: AppSettingsPort,
    private val appUpdate: AppUpdatePort,
) : ViewModel() {

    val appVersion: String
    val versionCode: Long
    val deviceModel: String
    val systemVersion: String

    val developerModeEnabled: StateFlow<Boolean> = appSettings.appPreferences
        .map { it.developerModeEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var versionTapCount = 0
    private var lastVersionTapAt: Long? = null

    val refreshState: StateFlow<RefreshState> = refreshCoordinator.state
    val reminderStatus: StateFlow<ReminderStatus> = reminderCoordinator.status
    val calendarStatus: StateFlow<CalendarSyncStatus> = calendarCoordinator.status

    val recent: StateFlow<List<DiagnosticEvent>> = diagnostics.observeRecent(60)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    val summary: StateFlow<DiagnosticLogSummary> = diagnostics.summary
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), DiagnosticLogSummary())

    /** 生成后等待用户确认保存的导出内容。 */
    val exportPreview = MutableStateFlow<DiagnosticExport?>(null)

    val confirmClear = MutableStateFlow(false)

    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val doneMessage = MutableStateFlow<String?>(null)

    val updateState: StateFlow<AppUpdateCheckState> = appUpdate.state
    val updateDialogRelease = MutableStateFlow<AppUpdateRelease?>(null)
    val customUpdateUrl = MutableStateFlow("")

    init {
        viewModelScope.launch {
            runCatching { appUpdate.configuredUrl() }
                .onSuccess { customUpdateUrl.value = it }
        }
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        appVersion = info?.versionName ?: "未知"
        versionCode = when {
            info == null -> 0
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> info.longVersionCode
            else -> {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        }
        val manufacturer = Build.MANUFACTURER.orEmpty().trim().takeUnless { it.isBlank() || it.equals("unknown", ignoreCase = true) }
        val model = Build.MODEL.orEmpty().trim().takeUnless { it.isBlank() || it.equals("unknown", ignoreCase = true) }
        deviceModel = when {
            model == null -> manufacturer ?: "未知型号"
            manufacturer == null || model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }
        systemVersion = "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）"
    }

    fun onVersionClick() {
        if (developerModeEnabled.value || busy.value) return
        val now = SystemClock.elapsedRealtime()
        if (lastVersionTapAt?.let { now - it in 0L..2_000L } != true) versionTapCount = 0
        lastVersionTapAt = now
        versionTapCount += 1
        if (versionTapCount == 7) {
            resetVersionClicks()
            saveDeveloperMode(true)
        }
    }

    fun resetVersionClicks() {
        versionTapCount = 0
        lastVersionTapAt = null
    }

    fun disableDeveloperMode() {
        if (!developerModeEnabled.value || busy.value) return
        resetVersionClicks()
        saveDeveloperMode(false)
    }

    private fun saveDeveloperMode(enabled: Boolean) {
        busy.value = true
        error.value = null
        viewModelScope.launch {
            try {
                appSettings.transformAppPreferences { it.copy(developerModeEnabled = enabled) }
                exportPreview.value = null
                confirmClear.value = false
                doneMessage.value = if (enabled) "已开启开发者模式" else "已关闭开发者模式"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error.value = "开发者模式设置未保存，请重试"
            } finally {
                busy.value = false
            }
        }
    }

    fun prepareExport() {
        if (!developerModeEnabled.value || busy.value) return
        viewModelScope.launch {
            busy.value = true
            error.value = null
            runCatching { diagnostics.export() }
                .onSuccess { exportPreview.value = it }
                .onFailure { error.value = it.message ?: "生成诊断文件失败" }
            busy.value = false
        }
    }

    fun consumeExportPreview() {
        exportPreview.value = null
    }

    fun requestClear() {
        if (!developerModeEnabled.value || busy.value) return
        confirmClear.value = true
    }

    fun dismissClear() {
        confirmClear.value = false
    }

    fun clearLogs() {
        if (!developerModeEnabled.value || busy.value) return
        viewModelScope.launch {
            confirmClear.value = false
            busy.value = true
            diagnostics.clear()
            busy.value = false
            doneMessage.value = "诊断日志已清空"
        }
    }

    fun consumeDone() {
        doneMessage.value = null
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            error.value = null
            val result = appUpdate.checkManually()
            if (result is AppUpdateCheckState.Available) {
                updateDialogRelease.value = result.release
            }
        }
    }

    fun showUpdateDialog(release: AppUpdateRelease) {
        updateDialogRelease.value = release
    }

    fun dismissUpdateDialog() {
        updateDialogRelease.value = null
        appUpdate.dismissPrompt()
    }

    fun clearUpdateDialogOnly() {
        updateDialogRelease.value = null
    }

    fun ignoreUpdateVersion(versionKey: String) {
        updateDialogRelease.value = null
        viewModelScope.launch {
            appUpdate.ignoreVersion(versionKey)
        }
    }

    fun saveCustomUpdateUrl(url: String) {
        if (!developerModeEnabled.value) return
        viewModelScope.launch {
            appUpdate.setCustomEndpointOverride(url)
            customUpdateUrl.value = appUpdate.configuredUrl()
            doneMessage.value = if (url.isBlank()) "已恢复默认更新地址" else "已保存调试更新地址"
        }
    }
}
