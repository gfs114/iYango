package com.gfs.iyango

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.domain.AppSettingsPort
import com.gfs.iyango.core.domain.AppAppearanceSnapshot
import com.gfs.iyango.core.domain.CustomFontStore
import com.gfs.iyango.core.domain.ThemeSettings
import com.gfs.iyango.core.domain.ThemeSettingsPort
import com.gfs.iyango.core.model.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 应用主题 ViewModel：把 DataStore 中的外观与字体/灰字偏好暴露为 [StateFlow]，
 * 供 MainActivity / SecondaryPageActivity 的 [com.gfs.iyango.IYangoActivityHost]
 * 与我的页外观区共享读写。
 *
 * 多 Activity 架构：`stateIn` 的 `initialValue` 取自 [AppAppearanceSnapshot]
 * （进程内最新已知值），发射时回写快照，保证新开 SecondaryPageActivity 首帧
 * 不闪默认主题。
 */
@HiltViewModel
class ThemeViewModel @Inject constructor(
    private val settingsPort: ThemeSettingsPort,
    appSettings: AppSettingsPort,
    private val customFontStore: CustomFontStore,
) : ViewModel() {

    val settings: StateFlow<ThemeSettings> = settingsPort.settings
        .onEach { AppAppearanceSnapshot.updateThemeSettings(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppAppearanceSnapshot.themeSettings)

    val appPreferences: StateFlow<AppPreferences> = appSettings.appPreferences
        .onEach { AppAppearanceSnapshot.updateAppPreferences(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppAppearanceSnapshot.appPreferences)

    val customFontPath: StateFlow<String?> = appPreferences
        .map { prefs -> customFontStore.resolve(prefs.customFontPath) }
        .flowOn(Dispatchers.IO)
        .onEach { AppAppearanceSnapshot.updateCustomFontPath(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppAppearanceSnapshot.customFontPath)

    /** 更新外观设置（我的页回调）。 */
    fun update(settings: ThemeSettings) {
        viewModelScope.launch {
            settingsPort.update(settings)
        }
    }
}
