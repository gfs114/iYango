package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.Exam
import com.gfs.iyango.core.model.ExamStatus
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** 考试相对当前时刻的阶段。 */
enum class ExamPhase {
    UPCOMING,
    IN_PROGRESS,
    ENDED,
    CANCELLED,
}

/**
 * 考试倒计时（E01）。
 *
 * 语义：
 * - 已取消不进入倒计时；
 * - 只有日期、没有开始时刻时按自然日计算，不虚构开考时刻；
 * - 跨日考试以显式结束日期（及结束时刻）判定结束；
 * - 缺少结束时刻时，结束日当天 23:59 视为结束。
 */
object ExamCountdown {

    fun phase(exam: Exam, now: LocalDateTime): ExamPhase {
        if (exam.status == ExamStatus.CANCELLED) return ExamPhase.CANCELLED
        if (exam.status == ExamStatus.COMPLETED) return ExamPhase.ENDED
        val startDate = exam.startDateOrNull() ?: return ExamPhase.ENDED
        val endDate = exam.resolvedEndDateOrNull() ?: startDate
        val end = exam.endTimeOrNull()?.let { LocalDateTime.of(endDate, it) }
            ?: endDate.atTime(23, 59, 59)
        if (now.isAfter(end)) return ExamPhase.ENDED
        val start = exam.startTimeOrNull()?.let { LocalDateTime.of(startDate, it) }
        if (start != null && !now.isBefore(start)) return ExamPhase.IN_PROGRESS
        if (start == null) {
            val today = now.toLocalDate()
            if (!today.isBefore(startDate) && !today.isAfter(endDate)) return ExamPhase.IN_PROGRESS
        }
        return ExamPhase.UPCOMING
    }

    /** 倒计时展示文案（列表、详情与今日摘要共用）。 */
    fun label(exam: Exam, now: LocalDateTime): String = when (phase(exam, now)) {
        ExamPhase.CANCELLED -> "已取消"
        ExamPhase.ENDED -> "已结束"
        ExamPhase.IN_PROGRESS -> "进行中"
        ExamPhase.UPCOMING -> upcomingLabel(exam, now)
    }

    /** 是否应出现在待考视图（未结束且未取消）。 */
    fun isUpcoming(exam: Exam, now: LocalDateTime): Boolean =
        phase(exam, now) == ExamPhase.UPCOMING || phase(exam, now) == ExamPhase.IN_PROGRESS

    private fun upcomingLabel(exam: Exam, now: LocalDateTime): String {
        val startDate = exam.startDateOrNull() ?: return "日期待定"
        val start = exam.startTimeOrNull()?.let { LocalDateTime.of(startDate, it) }
        if (start == null) {
            val days = ChronoUnit.DAYS.between(now.toLocalDate(), startDate)
            return if (days > 0) "还有 $days 天" else if (days == 0L) "今天" else "即将开始"
        }
        val minutes = ChronoUnit.MINUTES.between(now, start)
        return when {
            minutes <= 0 -> "即将开始"
            minutes >= 1440 -> {
                val days = minutes / 1440
                val hours = (minutes % 1440) / 60
                if (hours > 0) "$days 天 $hours 小时后" else "$days 天后"
            }
            minutes >= 60 -> {
                val hours = minutes / 60
                val rest = minutes % 60
                if (rest > 0) "$hours 小时 $rest 分后" else "$hours 小时后"
            }
            else -> "$minutes 分钟后"
        }
    }
}
