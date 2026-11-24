package com.gfs.iyango.platform.android

import com.gfs.iyango.core.domain.CourseRules
import com.gfs.iyango.core.domain.ExamCountdown
import com.gfs.iyango.core.domain.ExamPhase
import com.gfs.iyango.core.model.Exam
import com.gfs.iyango.core.model.Semester
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Pure sizing and date rules; no schedule occurrence is inferred here. */
object WidgetPresentation {
    fun remaining(occurrences: List<com.gfs.iyango.core.domain.CourseOccurrence>, now: java.time.LocalDateTime): Int =
        occurrences.count { it.date == now.toLocalDate() && (it.endMinute == null || it.endMinute!! > now.hour * 60 + now.minute) }

    fun capacity(availableHeight: Int, rowHeights: List<Int>): Int {
        var remaining = availableHeight.coerceAtLeast(0)
        var count = 0
        for (height in rowHeights.take(12)) {
            if (height > remaining) break
            remaining -= height.coerceAtLeast(1)
            count++
        }
        return count
    }
    fun selectedDate(today: LocalDate, epochDay: Long?, anchorToday: Long?): LocalDate {
        if (anchorToday != today.toEpochDay() || epochDay == null) return today
        return runCatching { LocalDate.ofEpochDay(epochDay) }.getOrNull()
            ?.takeIf { it in today.minusDays(7)..today.plusDays(7) } ?: today
    }
    fun navigate(today: LocalDate, selected: LocalDate, delta: Int): LocalDate =
        selected.plusDays(delta.coerceIn(-1, 1).toLong()).coerceIn(today.minusDays(7), today.plusDays(7))

    /**
     * 宽度格子数换算（复刻 WakeUp `utils.OooO0OO.OooO0oo` 的网格列数公式）。
     *
     * `minWidth <= 110 -> 1`、`<= 180 -> 2`、`<= 250 -> 3`、`> 250 -> 4+`。
     */
    fun resolveCellSpan(minWidthDp: Int): Int {
        var cells = 2
        while (70 * cells - 30 < minWidthDp) {
            cells++
        }
        return cells - 1
    }

    /** 周一锚定（周视图翻周与整周网格共用）。 */
    fun mondayOf(date: LocalDate): LocalDate = date.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /**
     * 学期状态快照（供空视图文案使用）。
     *
     * [daysUntilStart] > 0 表示尚未开学；[ended] 表示今天已越过 [totalWeeks]
     * （[currentWeek] 为 0 可能是"学期外"，单独用 [ended] 区分结束与未开始）。
     * semester 为 null 时按"学期内第 1 周"处理。
     */
    data class SemesterState(
        val daysUntilStart: Int,
        val currentWeek: Int,
        val totalWeeks: Int,
        val ended: Boolean,
    ) {
        val notStarted: Boolean get() = daysUntilStart > 0
    }

    fun semesterState(semester: Semester?, date: LocalDate, today: LocalDate = LocalDate.now()): SemesterState {
        if (semester == null) return SemesterState(0, 1, 20, ended = false)
        val daysUntilStart = ChronoUnit.DAYS.between(today, semester.startDate).toInt()
        // "学期已结束"以今天为锚：已过开学日且今天不在 1..totalWeeks 内。
        val ended = daysUntilStart <= 0 && CourseRules.weekOfDate(semester, today) == 0
        return SemesterState(daysUntilStart, CourseRules.weekOfDate(semester, date), semester.totalWeeks, ended)
    }

    /**
     * 空视图文案（文档 6.2 对照表，并区分"今日原本无课"与"今日课程已全部结束"）。
     *
     * @param modern 现代卡片（带颜文字前缀）或经典卡片。
     * @param weekView 周视图使用 "本周/下周没有课哦"。
     * @param allCoursesEndedToday 今日有课但已全部下课（如 2×2 预览卡片过滤已结束课程后为空）。
     */
    fun emptyMessage(
        state: SemesterState,
        selectedDate: LocalDate,
        today: LocalDate,
        modern: Boolean,
        weekView: Boolean,
        allCoursesEndedToday: Boolean = false,
    ): String = when {
        state.notStarted -> if (modern) "距离开学\n还有 ${state.daysUntilStart} 天" else "距离开学还有 ${state.daysUntilStart} 天"
        state.ended -> if (modern) "(〃'▽'〃)\n学期已经结束啦" else "学期已经结束啦"
        weekView -> if (mondayOf(selectedDate) > mondayOf(today)) "下周没有课哦" else "本周没有课哦"
        selectedDate == today && allCoursesEndedToday -> if (modern) "(〃'▽'〃)\n今日课程已结束" else "今日课程已结束"
        selectedDate > today -> if (modern) "(〃'▽'〃)\n明天没有课啦" else "明天没有课哦"
        selectedDate == today -> if (modern) "(〃'▽'〃)\n今天没有课啦" else "今天没有课哦"
        else -> if (modern) "(〃'▽'〃)\n今天没有课啦" else "今天没有课哦"
    }

    private val shortWeekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /**
     * 顶栏副标题（借鉴拾光课程表 & HiXD：周次 + 剩余/总课数摘要）。
     */
    fun headerSubtitle(
        state: SemesterState,
        selectedDate: LocalDate,
        today: LocalDate,
        remainingCount: Int,
        totalCount: Int,
        weekView: Boolean,
    ): String {
        val parts = buildList {
            when {
                state.notStarted -> add("未开学")
                state.ended -> add("已结课")
                state.currentWeek > 0 -> add("第${state.currentWeek}周")
            }
            if (!weekView && !state.notStarted && !state.ended) {
                when {
                    selectedDate == today && totalCount > 0 && remainingCount <= 0 -> add("今日已结课")
                    selectedDate == today && totalCount > 0 && remainingCount < totalCount -> add("剩${remainingCount}节")
                    selectedDate == today && totalCount > 0 -> add("今日${totalCount}节")
                    selectedDate != today && totalCount > 0 -> add("共${totalCount}节")
                }
            }
        }
        return if (parts.isEmpty()) "" else " · " + parts.joinToString(" · ")
    }

    /**
     * 双列组件分栏标题（借鉴拾光课程表 DoubleDays：今天/明天 + MM.dd + 周X）。
     */
    fun columnHeaderTitle(date: LocalDate, today: LocalDate): String {
        val prefix = when (date) {
            today -> "今天 "
            today.plusDays(1) -> "明天 "
            else -> ""
        }
        val monthDay = "${date.monthValue.toString().padStart(2, '0')}.${date.dayOfMonth.toString().padStart(2, '0')}"
        val weekday = shortWeekdays[(date.dayOfWeek.value - 1).coerceIn(0, 6)]
        return "$prefix$monthDay $weekday"
    }

    /**
     * 现代列表课程状态/节次胶囊文案：桌面端默认展示稳定节次（`1-2节`）避免刷新间隔内过时，
     * 开启 [includeLiveStatus] 时可显示 `上课中` / `即将上课`。
     */
    fun courseStatusBadge(
        occurrence: com.gfs.iyango.core.domain.CourseOccurrence,
        today: LocalDate,
        nowMinute: Int,
        includeLiveStatus: Boolean = false,
    ): String {
        if (includeLiveStatus) {
            val start = occurrence.startMinute
            val end = occurrence.endMinute
            if (occurrence.date == today && start != null && end != null) {
                if (nowMinute in start until end) return "上课中"
                if (start > nowMinute && start - nowMinute <= 30) return "即将上课"
            }
        }
        return "${occurrence.course.startSection}-${occurrence.course.endSection}节"
    }

    /**
     * 多教师姓名折叠（借鉴 HiXD compactTeachers：超过 2 位教师折叠为 "A、B等N人"）。
     */
    fun compactTeachers(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val names = trimmed.split(Regex("[,，、;/\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }
        return when {
            names.size <= 1 -> trimmed
            names.size == 2 -> "${names[0]}、${names[1]}"
            else -> "${names[0]}、${names[1]}等${names.size}人"
        }
    }

    /** 下一节课小胶囊（2x1 / 4x1）展示状态。 */
    data class NextClassPillState(
        val courseName: String,
        val subtitle: String,
        val detailLine: String,
        val badgeText: String,
        val isInProgress: Boolean,
        val accentHex: String,
        val hasCourse: Boolean,
    )

    /** 考试倒计时小组件（2x2）单条展示数据。 */
    data class ExamWidgetItem(
        val title: String,
        val countdownChip: String,
        val dateTimeText: String,
        val locationText: String,
        val isUrgent: Boolean,
    )

    fun resolveNextClassPill(
        todayOccurrences: List<com.gfs.iyango.core.domain.CourseOccurrence>,
        tomorrowOccurrences: List<com.gfs.iyango.core.domain.CourseOccurrence>,
        now: LocalDateTime,
    ): NextClassPillState {
        val nowMinute = now.hour * 60 + now.minute
        val sortedToday = todayOccurrences.sortedWith(
            compareBy<com.gfs.iyango.core.domain.CourseOccurrence> { it.startMinute ?: Int.MAX_VALUE }
                .thenBy { it.course.startSection },
        )
        val inProgress = sortedToday.firstOrNull { occ ->
            val s = occ.startMinute
            val e = occ.endMinute
            s != null && e != null && nowMinute in s until e
        }
        if (inProgress != null) {
            val endMin = inProgress.endMinute!!
            val remain = (endMin - nowMinute).coerceAtLeast(1)
            val room = inProgress.course.classroom.ifBlank { "教室待定" }
            val startStr = fmtMinute(inProgress.startMinute!!)
            val endStr = fmtMinute(endMin)
            val teacher = compactTeachers(inProgress.course.teacher)
            val detail = buildString {
                append(startStr).append("-").append(endStr)
                if (teacher.isNotEmpty()) append(" · ").append(teacher)
            }
            return NextClassPillState(
                courseName = inProgress.course.name,
                subtitle = "$room · 距下课 ${remain} 分钟",
                detailLine = detail,
                badgeText = "上课中",
                isInProgress = true,
                accentHex = inProgress.course.color,
                hasCourse = true,
            )
        }

        val upcomingToday = sortedToday.firstOrNull { occ ->
            occ.startMinute == null || occ.startMinute!! > nowMinute
        }
        if (upcomingToday != null) {
            val startMin = upcomingToday.startMinute
            val endMin = upcomingToday.endMinute
            val badge = if (startMin != null) {
                val diff = startMin - nowMinute
                if (diff <= 60) "${diff.coerceAtLeast(1)}分后" else fmtMinute(startMin)
            } else {
                "第${upcomingToday.course.startSection}节"
            }
            val timeRange = when {
                startMin != null && endMin != null -> "${fmtMinute(startMin)}-${fmtMinute(endMin)}"
                startMin != null -> fmtMinute(startMin)
                else -> "时间待定"
            }
            val room = upcomingToday.course.classroom
            val subtitle = listOfNotNull(
                room.takeIf { it.isNotBlank() },
                timeRange,
            ).joinToString(" · ")
            val teacher = compactTeachers(upcomingToday.course.teacher)
            val detail = listOfNotNull(
                "第${upcomingToday.course.startSection}-${upcomingToday.course.endSection}节",
                teacher.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            return NextClassPillState(
                courseName = upcomingToday.course.name,
                subtitle = subtitle,
                detailLine = detail,
                badgeText = badge,
                isInProgress = false,
                accentHex = upcomingToday.course.color,
                hasCourse = true,
            )
        }

        val tomorrowFirst = tomorrowOccurrences.sortedWith(
            compareBy<com.gfs.iyango.core.domain.CourseOccurrence> { it.startMinute ?: Int.MAX_VALUE }
                .thenBy { it.course.startSection },
        ).firstOrNull()
        if (tomorrowFirst != null) {
            val timeStr = tomorrowFirst.startMinute?.let(::fmtMinute) ?: "第${tomorrowFirst.course.startSection}节"
            val room = tomorrowFirst.course.classroom.ifBlank { "教室待定" }
            val teacher = compactTeachers(tomorrowFirst.course.teacher)
            val detail = listOfNotNull(
                "明天 $timeStr",
                teacher.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            return NextClassPillState(
                courseName = tomorrowFirst.course.name,
                subtitle = "明天 $timeStr · $room",
                detailLine = detail,
                badgeText = "明天",
                isInProgress = false,
                accentHex = tomorrowFirst.course.color,
                hasCourse = true,
            )
        }

        return if (todayOccurrences.isNotEmpty()) {
            NextClassPillState(
                courseName = "今日课程已全部结束",
                subtitle = "明天无课，好好休息一下吧",
                detailLine = "点击查看完整课表安排",
                badgeText = "已结课",
                isInProgress = false,
                accentHex = "",
                hasCourse = false,
            )
        } else {
            NextClassPillState(
                courseName = "今日与明日均无课",
                subtitle = "享受自由支配的校园时光",
                detailLine = "点击打开周课表规划安排",
                badgeText = "无课",
                isInProgress = false,
                accentHex = "",
                hasCourse = false,
            )
        }
    }

    fun resolveUpcomingExams(
        exams: List<Exam>,
        now: LocalDateTime,
        limit: Int = 3,
    ): List<ExamWidgetItem> {
        val today = now.toLocalDate()
        return exams
            .filter { ExamCountdown.isUpcoming(it, now) }
            .sortedWith(
                compareBy<Exam> { it.startDateOrNull() ?: LocalDate.MAX }
                    .thenBy { it.startTimeOrNull() ?: java.time.LocalTime.MAX }
                    .thenBy { it.subject },
            )
            .take(limit)
            .map { exam ->
                val phase = ExamCountdown.phase(exam, now)
                val startDate = exam.startDateOrNull()
                val startTime = exam.startTimeOrNull()
                val days = startDate?.let { ChronoUnit.DAYS.between(today, it) }
                val badge = when (phase) {
                    ExamPhase.IN_PROGRESS -> "进行中"
                    ExamPhase.UPCOMING -> {
                        if (startDate != null && startTime != null) {
                            val minutes = ChronoUnit.MINUTES.between(now, LocalDateTime.of(startDate, startTime)).coerceAtLeast(1)
                            when {
                                minutes < 60 -> "${minutes}分"
                                minutes < 1440 -> "${minutes / 60}时"
                                else -> "${days?.coerceAtLeast(1) ?: 1}天"
                            }
                        } else when {
                            days == null -> "待定"
                            days <= 0L -> "今天"
                            else -> "${days}天"
                        }
                    }
                    else -> ExamCountdown.label(exam, now)
                }
                val isUrgent = phase == ExamPhase.IN_PROGRESS || (days != null && days <= 3L)
                val datePart = startDate?.let { "${it.monthValue}/${it.dayOfMonth}" } ?: exam.date.ifBlank { "日期待定" }
                val timePart = if (exam.startTime.isNotBlank() && exam.endTime.isNotBlank()) {
                    "${exam.startTime}-${exam.endTime}"
                } else if (exam.startTime.isNotBlank()) {
                    exam.startTime
                } else {
                    "时间待定"
                }
                val loc = listOfNotNull(
                    exam.location.takeIf { it.isNotBlank() },
                    exam.seat.takeIf { it.isNotBlank() }?.let { "${it}号座" },
                ).joinToString(" · ")
                ExamWidgetItem(
                    title = exam.subject.ifBlank { "未命名考试" },
                    countdownChip = badge,
                    dateTimeText = "$datePart $timePart",
                    locationText = loc.ifBlank { "考场待定" },
                    isUrgent = isUrgent,
                )
            }
    }

    private fun fmtMinute(minute: Int): String =
        "${(minute / 60).toString().padStart(2, '0')}:${(minute % 60).toString().padStart(2, '0')}"
}
