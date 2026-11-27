package com.gfs.iyango.feature.main.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.domain.AppUpdateCheckState
import com.gfs.iyango.core.domain.AppUpdateDownloadState
import com.gfs.iyango.core.domain.AppUpdatePort
import com.gfs.iyango.core.domain.AppUpdateRelease
import com.gfs.iyango.core.domain.UpdateDownloadMirror
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 根级更新弹窗与版本更新页 ViewModel。
 */
@HiltViewModel
class AppUpdateViewModel @Inject constructor(
    private val appUpdate: AppUpdatePort,
) : ViewModel() {

    val promptRelease: StateFlow<AppUpdateRelease?> = appUpdate.promptRelease
    val checkState: StateFlow<AppUpdateCheckState> = appUpdate.state
    val downloadState: StateFlow<AppUpdateDownloadState> = appUpdate.downloadState
    val downloadMirror: StateFlow<UpdateDownloadMirror> = appUpdate.downloadMirror
    val autoCheckEnabled: StateFlow<Boolean> = appUpdate.autoCheckEnabled
    val releaseHistory: StateFlow<List<AppUpdateRelease>> = appUpdate.releaseHistory
    val historyLoading: StateFlow<Boolean> = appUpdate.historyLoading

    private val _manualUpdateDialogRelease = MutableStateFlow<AppUpdateRelease?>(null)
    val manualUpdateDialogRelease: StateFlow<AppUpdateRelease?> = _manualUpdateDialogRelease.asStateFlow()

    val currentVersionName: String get() = appUpdate.currentVersionName
    val currentVersionCode: Long get() = appUpdate.currentVersionCode

    fun loadInitial() {
        viewModelScope.launch {
            if (checkState.value is AppUpdateCheckState.Idle) {
                appUpdate.checkManually()
            }
            appUpdate.loadReleaseHistory(force = false)
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            val result = appUpdate.checkManually()
            if (result is AppUpdateCheckState.Available) {
                _manualUpdateDialogRelease.value = result.release
            }
            appUpdate.loadReleaseHistory(force = true)
        }
    }

    fun dismissManualUpdateDialog() {
        _manualUpdateDialogRelease.value = null
        appUpdate.dismissPrompt()
    }

    fun clearManualUpdateDialogOnly() {
        _manualUpdateDialogRelease.value = null
    }

    fun startDownload(release: AppUpdateRelease) {
        appUpdate.startDownload(release)
    }

    fun cancelDownload() {
        appUpdate.cancelDownload()
    }

    fun resetDownloadState() {
        appUpdate.resetDownloadState()
    }

    fun setDownloadMirror(mirror: UpdateDownloadMirror) {
        viewModelScope.launch {
            appUpdate.setDownloadMirror(mirror)
        }
    }

    fun setAutoCheckEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appUpdate.setAutoCheckEnabled(enabled)
        }
    }

    fun dismissPrompt() {
        appUpdate.dismissPrompt()
    }

    fun ignoreVersion(versionKey: String) {
        _manualUpdateDialogRelease.value = null
        viewModelScope.launch {
            appUpdate.ignoreVersion(versionKey)
        }
    }
}
