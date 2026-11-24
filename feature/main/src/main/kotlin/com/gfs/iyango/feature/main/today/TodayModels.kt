package com.gfs.iyango.feature.main.today

import com.gfs.iyango.core.domain.BuildingTransferWarning
import com.gfs.iyango.core.domain.CourseOccurrence
import com.gfs.iyango.core.domain.CourseStatus
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * 今日页纯展示模型与派生逻辑（契约 §2），全部可 JVM 单测。
 */

/** 今日课程行（courseRow）展示载荷。 */
data class OccurrenceRow(
    val occurrence: CourseOccurrence,
    val status: CourseStatus,
    val transferWarning: BuildingTransferWarning? = null,
) {
    val startText: String get() = occurrence.startMinute?.toClockText() ?: "--:--"
    val endText: String get() = occurrence.endMinute?.toClockText() ?: "--:--"

    companion object {
        fun Int.toClockText(): String = "%02d:%02d".format(this / 60, this % 60)
    }
}

/**
 * 焦点卡状态标签（§2.3）："正在上课 / 下一门 / 今日最后一门 / 时间待确认"。
 *
 * 规则：
 * - 存在进行中 → "正在上课"；
 * - 无进行中且存在未开始 → "下一门"（若未开始的只剩最后一门且其开始前无其他安排，
 *   标签仍为"下一门"，"今日最后一门"仅在恰好剩最后一门未开始且其后无课时给出）；
 * - 全部结束 → null（调用方展示"今日课程已全部结束"卡）；
 * - 当日存在时间缺失（--:--）课程 → "时间待确认"。
 */
fun focusLabel(rows: List<OccurrenceRow>): String? {
    if (rows.isEmpty()) return null
    if (rows.any { it.occurrence.startMinute == null || it.occurrence.endMinute == null }) {
        return "时间待确认"
    }
    val inProgress = rows.any { it.status == CourseStatus.IN_PROGRESS }
    if (inProgress) return "正在上课"
    val upcoming = rows.filter { it.status == CourseStatus.NOT_STARTED || it.status == CourseStatus.UPCOMING }
    if (upcoming.isEmpty()) return null
    return "下一门"
}

/** 按开始时间排序后的行（时间缺失排最后）。 */
fun sortRows(rows: List<OccurrenceRow>): List<OccurrenceRow> = rows.sortedWith(
    compareBy(
        { it.occurrence.startMinute ?: Int.MAX_VALUE },
        { it.occurrence.course.name },
    ),
)

/** 问候语（§2.1）：按小时给出"早上好/上午好/下午好/晚上好"。 */
fun greetingText(hour: Int): String = when (hour) {
    in 5..8 -> "早上好"
    in 9..11 -> "上午好"
    in 12..17 -> "下午好"
    else -> "晚上好"
}

/**
 * 月历矩阵（§2.6）：指定月份按周分行的日期网格（null = 占位）。
 *
 * @param month 目标月份。
 * @param weekAnchorDay 学期锚点日；用于高亮今天所在教学周（null 时不高亮）。
 */
data class MonthCalendar(
    /** 周分行的日期（null = 该格不属于本月）。每行固定 7 格，列 0 = 周一。 */
    val weeks: List<List<LocalDate?>>,
    val today: LocalDate?,
)

fun monthCalendar(month: YearMonth, today: LocalDate?): MonthCalendar {
    val firstDay = month.atDay(1)
    // 周一=1 … 周日=7；列 0 对应周一。
    val leadingBlanks = firstDay.dayOfWeek.value - 1
    val daysInMonth = month.lengthOfMonth()
    val cells = buildList {
        repeat(leadingBlanks) { add(null) }
        for (day in 1..daysInMonth) {
            add(month.atDay(day))
        }
        while (size % 7 != 0) {
            add(null)
        }
    }
    return MonthCalendar(
        weeks = cells.chunked(7),
        today = today?.takeIf { it.year == month.year && it.month == month.month },
    )
}

/** "X月X日" 文案（明日安排标题用）。 */
fun chineseDateText(date: LocalDate): String =
    "${date.monthValue}月${date.dayOfMonth}日"

/** 月历页头 "2026年10月"。 */
fun monthTitleText(month: YearMonth): String =
    month.format(DateTimeFormatter.ofPattern("yyyy年M月"))

/** Countdown strings are derived from the same projection as the title and progress. */
fun homeTimingTitle(timing: com.gfs.iyango.core.domain.TodayCourseTiming): String = when {
    timing.current != null -> "正在上课"
    timing.next != null -> "下一门课程"
    else -> "未来7天暂无已知课程"
}

fun homeCountdownText(timing: com.gfs.iyango.core.domain.TodayCourseTiming): String {
    val seconds = timing.remainingSeconds ?: return ""
    return when {
        timing.current != null || seconds < 3600 -> String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
        seconds < 86400 -> "${seconds / 3600}小时${seconds % 3600 / 60}分钟"
        else -> "${java.time.temporal.ChronoUnit.DAYS.between(timing.now.toLocalDate(), timing.next?.date)}天后"
    }
}

data class TodayDetailContext(
    val semester: com.gfs.iyango.core.model.Semester?,
    val week: Int,
    val timeSlots: List<com.gfs.iyango.core.model.TimeSlot>,
    val sectionCount: Int,
)

/** Actual-day metadata wins even when that day's semester or bells are intentionally empty. */
fun todayDetailContext(day: com.gfs.iyango.core.domain.EffectiveScheduleDay?, fallback: TodayUiState.Ready,
    course: com.gfs.iyango.core.model.Course): TodayDetailContext {
    val slots = day?.displayTimeSlots ?: fallback.timeSlots
    return TodayDetailContext(
        semester = if (day != null) day.semester else fallback.semester,
        week = if (day != null) com.gfs.iyango.core.domain.CourseRules.weekOfDate(day.semester, day.date) else fallback.detailWeek,
        timeSlots = slots,
        sectionCount = maxOf(slots.maxOfOrNull { it.section } ?: 0, course.endSection).coerceIn(1, 20),
    )
}
