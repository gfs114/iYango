package com.gfs.iyango.core.model

import java.time.LocalDate
import java.time.LocalTime

/** 考试来源。 */
enum class ExamSource {
    /** 用户手动录入（四六级、资格考试等）。 */
    MANUAL,

    /** 学校同步（数据源核实前不启用）。 */
    SCHOOL,
}

/** 考试状态。 */
enum class ExamStatus {
    /** 待考。 */
    SCHEDULED,

    /** 已结束（按结束时间/日期派生，也可由用户标注）。 */
    COMPLETED,

    /** 已取消：不进入倒计时。 */
    CANCELLED,
}

/**
 * 一场考试或考试类个人安排。
 *
 * 时间语义：
 * - [date] 为必填的开始日期；
 * - [startTime]/[endTime] 为空表示“待定”，不得回退到任何默认时刻；
 * - 跨日考试必须显式给出 [endDate]；
 * - 只有日期时倒计时按日计算，不生成虚构的开考时刻。
 *
 * [source] 为 [ExamSource.SCHOOL] 时 [connectionId] 必须非空；手动安排允许为空。
 */
data class Exam(
    val id: Long = 0,
    val source: ExamSource = ExamSource.MANUAL,
    val connectionId: String = "",
    val semester: String = "",
    val subject: String = "",
    val type: String = "",
    val date: String = "",
    val endDate: String = "",
    val startTime: String = "",
    val endTime: String = "",
    val timeZone: String = "Asia/Shanghai",
    val location: String = "",
    val seat: String = "",
    val note: String = "",
    val status: ExamStatus = ExamStatus.SCHEDULED,
    val sourceExamId: String = "",
    val updatedAt: Long = 0,
) {
    /** 解析开始日期；非法/缺失返回 null。 */
    fun startDateOrNull(): LocalDate? = parseDate(date)

    /** 解析结束日期；未给出时回退到开始日期（同日考试）。 */
    fun resolvedEndDateOrNull(): LocalDate? = parseDate(endDate) ?: startDateOrNull()

    /** 开始钟点；待定返回 null。 */
    fun startTimeOrNull(): LocalTime? = parseTime(startTime)

    /** 结束钟点；待定返回 null。 */
    fun endTimeOrNull(): LocalTime? = parseTime(endTime)

    /** 是否只确定了日期（没有开始时刻）。 */
    fun hasOnlyDate(): Boolean = startTimeOrNull() == null

    /** 是否跨日。 */
    fun isMultiDay(): Boolean {
        val start = startDateOrNull() ?: return false
        return resolvedEndDateOrNull()?.isAfter(start) == true
    }

    private fun parseDate(text: String): LocalDate? =
        runCatching { LocalDate.parse(text) }.getOrNull()

    private fun parseTime(text: String): LocalTime? {
        if (!CourseValidator.isTimeValid(text)) return null
        return runCatching { LocalTime.parse(text) }.getOrNull()
    }
}

/** 考试保存前的校验（返回首个问题，null 表示通过）。 */
object ExamValidator {

    fun validate(exam: Exam): String? {
        if (exam.subject.trim().isEmpty()) return "考试科目不能为空"
        val startDate = exam.startDateOrNull() ?: return "考试日期无效"
        if (exam.endDate.isNotEmpty()) {
            val endDate = exam.resolvedEndDateOrNull() ?: return "结束日期无效"
            if (endDate.isBefore(startDate)) return "结束日期不能早于开始日期"
        }
        if (exam.startTime.isNotEmpty() && !CourseValidator.isTimeValid(exam.startTime)) {
            return "开始时间格式应为 HH:mm"
        }
        if (exam.endTime.isNotEmpty() && !CourseValidator.isTimeValid(exam.endTime)) {
            return "结束时间格式应为 HH:mm"
        }
        if (exam.startTime.isNotEmpty() && exam.endTime.isNotEmpty() && exam.startTime >= exam.endTime) {
            return "开始时间必须早于结束时间"
        }
        if (exam.source == ExamSource.SCHOOL && exam.connectionId.isEmpty()) {
            return "学校考试缺少连接标识"
        }
        if (exam.note.length > 300) return "备注最多 300 字"
        return null
    }
}
