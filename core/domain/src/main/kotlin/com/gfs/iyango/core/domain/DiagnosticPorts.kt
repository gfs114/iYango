package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.DiagnosticEvent
import kotlinx.coroutines.flow.Flow

/** 诊断日志占用量（用于展示上限与清理入口）。 */
data class DiagnosticLogSummary(
    val count: Int = 0,
    val bytes: Long = 0,
    val lastEventAt: Long = 0,
)

/** 已脱敏、可直接交给用户保存的诊断导出。 */
data class DiagnosticExport(
    val fileName: String,
    val text: String,
)

/**
 * 结构化诊断日志端口（F18）。
 *
 * 约束：
 * - 只记录操作类别、不可逆脱敏的连接标识、任务代次、耗时与错误类别；
 * - 不记录口令、Cookie、完整成绩、完整个人信息或网页响应；
 * - 本地文件有固定上限并轮替，用户可随时清空；
 * - 导出只生成文本，由用户在系统文件选择器中确认保存，不做任何自动上传。
 */
interface DiagnosticLogPort {

    /** 最近事件（新的在前）；上限由实现方维护。 */
    fun observeRecent(limit: Int = 100): Flow<List<DiagnosticEvent>>

    val summary: Flow<DiagnosticLogSummary>

    suspend fun record(event: DiagnosticEvent)

    /** 生成脱敏文本与建议文件名；不写文件、不上传。 */
    suspend fun export(): DiagnosticExport

    suspend fun clear()
}
