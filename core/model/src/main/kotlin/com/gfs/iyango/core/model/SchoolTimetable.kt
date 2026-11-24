package com.gfs.iyango.core.model

/** 学校学期选项（保留后端值与展示名）。 */
data class SchoolTermOption(
    val value: String = "",
    val label: String = "",
)

/** 学校提供的校区。 */
data class SchoolCampusOption(
    val code: String = "",
    val name: String = "",
)

/**
 * 从学校页面解析出的一条课程安排。
 *
 * [sourceKey] 是学校稳定身份（课程号 + 教学班 + 安排标识）的原始文本，
 * 用于后续同步差异比较；解析器无法确定时留空并产生提示。
 */
data class SchoolCourseData(
    val sourceKey: String = "",
    val name: String = "",
    val teacher: String = "",
    val classroom: String = "",
    val dayOfWeek: Int = 1,
    val startSection: Int = 1,
    val endSection: Int = 1,
    val startWeek: Int = 1,
    val endWeek: Int = 1,
    val weekType: WeekType = WeekType.EVERY,
    val selectedWeeks: List<Int> = emptyList(),
    val campusCode: String = "",
    val campusName: String = "",
    val rawSections: String = "",
    val rawSectionCode: String = "",
    val note: String = "",
    val groupKey: String = "",
    val teacherConfirmed: Boolean = true,
    val timeUnconfirmed: Boolean = false,
    /** 学校官方作息解析出的上课时间（HH:mm）；未知校区/未知学期时为空。 */
    val startTime: String = "",
    val endTime: String = "",
)

/**
 * 一次学校课表读取结果（尚未落库）。
 *
 * [rawPayload] 是规范化后的完整快照文本，用于 E02 变化比较与排查；
 * [complete] 为 false 时调用方不得发布“课程已移除”。
 */
data class SchoolTimetableData(
    val termValue: String = "",
    val termLabel: String = "",
    val startDate: String = "",
    val totalWeeks: Int = 20,
    val campusCode: String = "",
    val campusName: String = "",
    val courses: List<SchoolCourseData> = emptyList(),
    val warnings: List<String> = emptyList(),
    val complete: Boolean = true,
    val rawPayload: String = "",
    val fetchedAt: Long = 0,
    /** 学校官方作息网格（已知学期 11 节）；未知学期时可能为空。 */
    val timeSlots: List<TimeSlot> = emptyList(),
)

/** 学校课表读取结果。 */
sealed interface SchoolTimetableResult {
    data class Success(val data: SchoolTimetableData) : SchoolTimetableResult
    data class SessionExpired(val message: String) : SchoolTimetableResult
    data class Failed(val message: String, val retryable: Boolean = true) : SchoolTimetableResult
}

/** 学校登录结果。 */
sealed interface SchoolLoginResult {
    data class Success(val connection: SchoolConnection) : SchoolLoginResult

    /** 学校明确拒绝凭据；可提示改用网页登录。 */
    data class Rejected(val message: String) : SchoolLoginResult

    data class Failed(val message: String) : SchoolLoginResult
}
