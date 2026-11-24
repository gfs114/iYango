package com.gfs.iyango.core.domain

/** 已持久化的教务课表原始 HTML 记录。 */
data class CourseRawHtmlRecord(
    val html: String,
    val term: String,
    val savedAt: Long,
)

/**
 * 教务课表原始 HTML 存储端口（实现在 data 层）。
 *
 * 按课表主键独立存放于私有文件目录，不进入 Room 主库或设置备份，
 * 供「查看课表原文」页面展示教务同期原始页面。
 */
interface CourseRawStorePort {

    /** 保存（或覆盖）指定课表最近一次教务同步的原始 HTML 与元数据。 */
    suspend fun saveHtml(
        timetableId: Long,
        html: String,
        term: String,
        savedAt: Long,
    )

    /** 读取指定课表的原始 HTML 记录；尚未同步过教务或文件缺失返回 null。 */
    suspend fun loadHtml(timetableId: Long): CourseRawHtmlRecord?
}
