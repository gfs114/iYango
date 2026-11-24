package com.gfs.iyango.feature.main.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.domain.SettingsBackupPort
import com.gfs.iyango.core.domain.SettingsBackupPreview
import com.gfs.iyango.core.domain.SettingsBackupReadResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 设置备份/恢复/重置页 ViewModel（F17）。
 *
 * 文件读写经 SAF 由 Screen 完成；本类只处理字节 → 预览 → 确认应用。
 */
@HiltViewModel
class SettingsBackupViewModel @Inject constructor(
    private val backup: SettingsBackupPort,
) : ViewModel() {

    val pendingExport = MutableStateFlow<PendingExport?>(null)

    val preview = MutableStateFlow<SettingsBackupPreview?>(null)

    /** 导出时是否把当前壁纸图片一并写入备份。 */
    val exportIncludesWallpaper = MutableStateFlow(true)

    /** 恢复时是否用备份中的壁纸覆盖本机壁纸。 */
    val restoreIncludesWallpaper = MutableStateFlow(true)

    /** 本机已存在学分计划冲突时是否用备份覆盖；默认保留本机。 */
    val overwriteCreditPlans = MutableStateFlow(false)

    val confirmReset = MutableStateFlow(false)

    val busy = MutableStateFlow(false)

    val error = MutableStateFlow<String?>(null)

    val doneMessage = MutableStateFlow<String?>(null)

    fun requestExport() {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            runCatching { backup.export(exportIncludesWallpaper.value) }
                .onSuccess { export ->
                    pendingExport.value = PendingExport(export.bytes, export.fileName)
                }
                .onFailure { error.value = it.message ?: "导出失败" }
            busy.value = false
        }
    }

    fun consumePendingExport() {
        pendingExport.value = null
    }

    fun readFile(bytes: ByteArray) {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            when (val result = backup.read(bytes)) {
                is SettingsBackupReadResult.Ready -> {
                    preview.value = result.preview
                    restoreIncludesWallpaper.value = result.preview.includesWallpaper
                    overwriteCreditPlans.value = false
                }
                is SettingsBackupReadResult.Failed -> error.value = result.message
            }
            busy.value = false
        }
    }

    fun dismissPreview() {
        preview.value = null
    }

    fun restore() {
        val current = preview.value ?: return
        viewModelScope.launch {
            busy.value = true
            error.value = null
            backup.restore(
                token = current.token,
                includeWallpaper = restoreIncludesWallpaper.value,
                overwriteCreditPlans = overwriteCreditPlans.value,
            )
                .onSuccess {
                    preview.value = null
                    doneMessage.value = "设置已恢复"
                }
                .onFailure { error.value = it.message ?: "恢复失败" }
            busy.value = false
        }
    }

    fun requestReset() {
        confirmReset.value = true
    }

    fun dismissReset() {
        confirmReset.value = false
    }

    fun resetSettings() {
        viewModelScope.launch {
            confirmReset.value = false
            busy.value = true
            error.value = null
            backup.resetSettings()
                .onSuccess { doneMessage.value = "已重置设置与壁纸" }
                .onFailure { error.value = it.message ?: "重置失败" }
            busy.value = false
        }
    }

    fun consumeDone() {
        doneMessage.value = null
    }

    data class PendingExport(val bytes: ByteArray, val fileName: String) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PendingExport) return false
            return fileName == other.fileName && bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = fileName.hashCode() * 31 + bytes.contentHashCode()
    }
}
