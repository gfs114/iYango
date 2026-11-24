package com.gfs.iyango.feature.main.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.domain.AppUpdatePort
import com.gfs.iyango.core.domain.AppUpdateRelease
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 根级版本更新弹窗协调 ViewModel。
 */
@HiltViewModel
class AppUpdateViewModel @Inject constructor(
    private val appUpdate: AppUpdatePort,
) : ViewModel() {

    val promptRelease: StateFlow<AppUpdateRelease?> = appUpdate.promptRelease

    fun dismissPrompt() {
        appUpdate.dismissPrompt()
    }

    fun ignoreVersion(versionKey: String) {
        viewModelScope.launch {
            appUpdate.ignoreVersion(versionKey)
        }
    }
}
