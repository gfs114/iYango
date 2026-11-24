package com.gfs.iyango.core.model

/**
 * 一次导入的历史记录。
 *
 * @property id 持久化主键；0 表示尚未持久化。
 * @property timetableId 导入目标课表 id。
 * @property source 导入来源（如 "YGU"）。
 * @property importTime 导入时间（ISO 文本）。
 * @property semester 导入时携带的学期描述文本。
 */
data class ImportRecord(
    val id: Long = 0,
    val timetableId: Long = 0,
    val source: String = "",
    val importTime: String = "",
    val semester: String = "",
)
