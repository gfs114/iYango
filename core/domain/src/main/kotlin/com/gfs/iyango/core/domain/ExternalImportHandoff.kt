package com.gfs.iyango.core.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 外部应用「用 iYango 打开」或分享传入的待导入文件载荷。 */
data class ExternalScheduleImportPayload(
    val fileName: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExternalScheduleImportPayload) return false
        return fileName == other.fileName && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = fileName.hashCode() * 31 + bytes.contentHashCode()
}

/** 外部文件导入内存总线（一次性投递给导入页，不落盘）。 */
object ExternalScheduleImportBus {
    private val _pendingFlow = MutableStateFlow<ExternalScheduleImportPayload?>(null)
    val pendingFlow: StateFlow<ExternalScheduleImportPayload?> = _pendingFlow.asStateFlow()

    fun offer(payload: ExternalScheduleImportPayload) {
        _pendingFlow.value = payload
    }

    fun consume(): ExternalScheduleImportPayload? {
        val current = _pendingFlow.value
        _pendingFlow.value = null
        return current
    }
}
