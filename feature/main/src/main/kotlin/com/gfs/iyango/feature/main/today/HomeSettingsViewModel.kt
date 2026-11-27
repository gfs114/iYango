package com.gfs.iyango.feature.main.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.domain.AppSettingsPort
import com.gfs.iyango.core.model.HomeModule
import com.gfs.iyango.core.model.HomePreferences
import com.gfs.iyango.core.model.HomeShortcut
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class HomeEditorState(
    val loaded: Boolean = false,
    val draft: HomePreferences = HomePreferences(),
    val baseline: HomePreferences = HomePreferences(),
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
) { val dirty: Boolean get() = draft != baseline }

@HiltViewModel
class HomeSettingsViewModel @Inject constructor(private val settings: AppSettingsPort) : ViewModel() {
    private val mutable = MutableStateFlow(HomeEditorState())
    val state = mutable.asStateFlow()

    init { viewModelScope.launch {
        try {
            val home = settings.appPreferences.first().home.sanitized()
            mutable.value = HomeEditorState(loaded = true, draft = home, baseline = home)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { mutable.value = mutable.value.copy(error = "首页设置读取失败，请返回后重试") }
    } }

    private fun edit(transform: (HomePreferences) -> HomePreferences) {
        val current = mutable.value
        if (current.loaded && !current.saving) mutable.value = current.copy(draft = transform(current.draft).sanitized(), error = null)
    }
    fun restoreDefaults() = edit { HomePreferences() }
    fun toggleModule(id: String) = edit {
        if (HomeModule.entries.none { module -> module.id == id }) it
        else it.copy(hiddenModules = if (id in it.hiddenModules) it.hiddenModules - id else it.hiddenModules + id)
    }
    fun toggleShortcut(id: String) = edit {
        val cleanQuick = it.quickActions.filter { s -> s != HomeShortcut.EXAMS.id }
        when {
            id == HomeShortcut.EXAMS.id -> it.copy(quickActions = cleanQuick)
            id in cleanQuick -> it.copy(quickActions = cleanQuick - id)
            cleanQuick.size < 8 && HomeShortcut.entries.any { shortcut -> shortcut.id == id } ->
                it.copy(quickActions = cleanQuick + id)
            else -> it
        }
    }
    fun moveModule(id: String, direction: Int) = edit {
        val visible = it.moduleOrder.filter { m -> m != HomeModule.EXAMS.id }
        val hidden = it.moduleOrder.filter { m -> m == HomeModule.EXAMS.id }
        it.copy(moduleOrder = moved(visible, id, direction) + hidden)
    }
    fun moveShortcut(id: String, direction: Int) = edit {
        val visible = it.quickActions.filter { s -> s != HomeShortcut.EXAMS.id }
        it.copy(quickActions = moved(visible, id, direction))
    }
    private fun moved(items: List<String>, id: String, direction: Int): List<String> {
        val from = items.indexOf(id)
        if (from < 0) return items
        val to = (from + direction).coerceIn(items.indices)
        if (from == to) return items
        return items.toMutableList().apply { add(to, removeAt(from)) }
    }
    fun save() {
        val current = mutable.value
        if (!current.loaded || current.saving) return
        mutable.value = current.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                settings.transformAppPreferences { it.copy(home = current.draft) }
                mutable.value = mutable.value.copy(baseline = current.draft, saved = true, saving = false)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(saving = false, error = "保存失败，草稿已保留，请重试")
            }
        }
    }
}
