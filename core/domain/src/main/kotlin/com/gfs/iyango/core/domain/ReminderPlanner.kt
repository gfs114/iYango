package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.Exam
import com.gfs.iyango.core.model.ReminderSettings
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 单条提醒计划。 */
data class ReminderPlanItem(
    val occurrenceId: String = "",
    val courseId: Long = 0,
    val courseName: String = "",
    val teacher: String = "",
    val classroom: String = "",
    val occurrenceDate: LocalDate = LocalDate.MIN,
    val startMinute: Int? = null,
    val triggerAtMillis: Long = 0,
    val title: String = "",
    val body: String = "",
    val payloadHash: String = "",
    val planRevision: Int = 1,
    /** 开始时刻（epoch 毫秒）；0 表示旧版本计划未写入该字段。 */
    val startAtMillis: Long = 0,
    /**
     * 结束时刻（epoch 毫秒）；null 表示未知。
     * 结束不晚于开始（例如 endMinute <= startMinute）时视为未知。
     */
    val endAtMillis: Long? = null,
    /** 卡片/胶囊强调色（#RRGGBB）；空串表示未知，渲染时不设色。 */
    val accentColor: String = "",
    /** 当天第几节（1 起算，按当天有效节次排序）；0 表示未知。 */
    val dayIndex: Int = 0,
    /** 当天有效节次总数（时间待定/停用不计入）；0 表示未知。 */
    val dayTotal: Int = 0,
    /** 是否启用课前至下课常驻实时进度条与倒计时更新。 */
    val ongoingProgress: Boolean = false,
    /** 跨教学楼赶课预警文案（空表示无预警）。 */
    val transferWarning: String = "",
)

/** 提醒规划结果；[skipped] 记录不排程的可解释原因。 */
data class ReminderPlan(
    val items: List<ReminderPlanItem> = emptyList(),
    val skipped: List<String> = emptyList(),
)

/**
 * 课程提醒纯规划器（F12）。
 *
 * 输入有效实例（已经过 F07/F08 的统一查询），输出确定的有序计划：
 * - 跳过开始时间未知（不猜默认时刻）、已结束、已取消（停用）的实例；
 * - 跳过用户关闭提醒的课程；
 * - 触发时刻 = 开始时刻 - 提前量；课前窗口内仍保留，由平台补发；
 * - 进行中的有效实例继续进入计划；无结束时间时仅保留到开始后 3 分钟；
 * - title 只放课程名；body 为「教室 HH:mm 上课」，临时调整/已调课附注保留在 body；
 * - 写入展示上下文：课程强调色（accentColor）与当天节次（dayIndex/dayTotal，
 *   复用 [TodayCourseStateResolver] 的过滤排序口径）。
 *
 * 不做任何 Android API 调用，便于纯逻辑测试。
 */
object CourseReminderPlanner {

    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** 课程配色/分组稳定键：显式 groupKey 优先，否则“课程名 + 教师”。 */
    fun courseKey(course: Course): String =
        course.groupKey.ifBlank { "${course.name}|${course.teacher}" }

    /** 单课程提醒开关的聚合键：同一门课程（按课程名）共用一个提醒开关。 */
    fun reminderKey(course: Course): String =
        course.name.trim().ifBlank { courseKey(course) }

    /** 判断某门课是否已被单课程开关关闭（兼容按课程名聚合键及旧版 groupKey/课名+教师键）。 */
    fun isCourseDisabled(course: Course, disabledKeys: Set<String>): Boolean =
        reminderKey(course) in disabledKeys ||
            courseKey(course) in disabledKeys ||
            "${course.name}|${course.teacher}" in disabledKeys

    fun plan(
        occurrences: List<CourseOccurrence>,
        settings: ReminderSettings,
        now: LocalDateTime,
        zoneId: ZoneId,
        planRevision: Int = 1,
        /** 课程强调色解析器：默认取课程自带颜色；调用方可注入用户配色覆盖。 */
        colorOf: (Course) -> String = { it.color },
    ): ReminderPlan {
        if (!settings.enabled) {
            return ReminderPlan(skipped = listOf("提醒总开关已关闭"))
        }
        val lead = settings.leadMinutes
        val skipped = mutableListOf<String>()
        val items = mutableListOf<ReminderPlanItem>()
        // 当天节次上下文：与 TodayCourseStateResolver 共用同一过滤排序，保证提醒的
        // 「第 x/y 节」与 App 内展示口径一致；含已过去的节次（索引定位需要完整列表）。
        val timedByDate = occurrences
            .groupBy({ it.date }, { it })
            .mapValues { (_, dayOccurrences) -> TodayCourseStateResolver.timedOccurrences(dayOccurrences) }
        val transferByDate = timedByDate.mapValues { (_, dayList) ->
            BuildingTransferDetector.detectForDay(dayList)
        }
        occurrences.forEach { occurrence ->
            val startMinute = occurrence.startMinute
            if (startMinute == null) {
                skipped += "${occurrence.course.name}：时间待定，不安排提醒"
                return@forEach
            }
            if (!occurrence.course.enabled) {
                return@forEach
            }
            if (isCourseDisabled(occurrence.course, settings.disabledCourseKeys)) {
                skipped += "${occurrence.course.name}：该课程提醒已关闭"
                return@forEach
            }
            val startAt = occurrence.date.atStartOfDay().plusMinutes(startMinute.toLong())
            // 结束时刻只在有效（晚于开始）时写入；用于"上课中"阶段与自动消失。
            val endAt = occurrence.endMinute
                ?.takeIf { it > startMinute }
                ?.let { occurrence.date.atStartOfDay().plusMinutes(it.toLong()) }
            val triggerAt = startAt.minusMinutes(lead.toLong())
            val expiresAt = endAt ?: startAt.plusMinutes(3)
            if (!expiresAt.isAfter(now)) {
                return@forEach
            }
            val startLabel = formatClock(startMinute)
            val endLabel = occurrence.endMinute
                ?.takeIf { it > startMinute }
                ?.let { formatClock(it) }
            val classroom = occurrence.course.classroom
            val teacher = occurrence.course.teacher
            // 两行清晰排版：第一行「老师 HH:mm - HH:mm（附注）」（无老师时仅放时间），第二行单独放教室/楼栋。
            val title = occurrence.course.name
            val timePart = buildString {
                if (endLabel != null) {
                    append(startLabel).append(" - ").append(endLabel)
                } else {
                    append(startLabel).append(" 上课")
                }
                if (occurrence.isTemporary) append("（临时调整）")
                if (occurrence.isMoved) append("（已调课）")
            }
            val baseBody = when {
                teacher.isNotBlank() && classroom.isNotBlank() -> "$teacher $timePart\n$classroom"
                teacher.isNotBlank() -> "$teacher $timePart"
                classroom.isNotBlank() -> "$timePart\n$classroom"
                else -> timePart
            }
            val transferWarning = transferByDate[occurrence.date]
                ?.get(occurrence.occurrenceId)
                ?.label
                .orEmpty()
            val body = if (transferWarning.isNotEmpty()) {
                if ('\n' in baseBody) "$baseBody $transferWarning" else "$baseBody\n$transferWarning"
            } else {
                baseBody
            }
            val accentColor = colorOf(occurrence.course)
            // 当天节次上下文：以实例身份在当天有效列表中定位（0 = 未知）。
            val dayList = timedByDate[occurrence.date].orEmpty()
            val dayIndex = dayList.indexOfFirst { it === occurrence } + 1
            val dayTotal = dayList.size
            val transferHashPart = if (transferWarning.isNotEmpty()) "|$transferWarning" else ""
            items += ReminderPlanItem(
                occurrenceId = occurrence.occurrenceId,
                courseId = occurrence.course.id,
                courseName = occurrence.course.name,
                classroom = classroom,
                occurrenceDate = occurrence.date,
                startMinute = startMinute,
                triggerAtMillis = triggerAt.atZone(zoneId).toInstant().toEpochMilli(),
                title = title,
                body = body,
                payloadHash = hash(
                    "${occurrence.occurrenceId}|$title|$body|${startAt.atZone(zoneId).toInstant()}|" +
                        "${startAt.atZone(zoneId).toInstant().toEpochMilli()}|" +
                        "${endAt?.atZone(zoneId)?.toInstant()?.toEpochMilli()}|$accentColor|" +
                        "$dayIndex/$dayTotal|$teacher|${settings.ongoingProgressEnabled}$transferHashPart",
                ),
                planRevision = planRevision,
                startAtMillis = startAt.atZone(zoneId).toInstant().toEpochMilli(),
                endAtMillis = endAt?.atZone(zoneId)?.toInstant()?.toEpochMilli(),
                accentColor = accentColor,
                dayIndex = dayIndex,
                dayTotal = dayTotal,
                teacher = teacher,
                ongoingProgress = settings.ongoingProgressEnabled,
                transferWarning = transferWarning,
            )
        }
        return ReminderPlan(
            items = items.sortedBy { it.triggerAtMillis },
            skipped = skipped.distinct(),
        )
    }

    private fun formatClock(minuteOfDay: Int): String =
        LocalDateTime.of(2000, 1, 1, 0, 0).plusMinutes(minuteOfDay.toLong()).format(timeFormatter)

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }
            .take(16)
}

/**
 * 考试提醒规划：使用 `exam:<examId>` 独立身份。
 *
 * 没有明确开始时刻的考试不排精确提醒；已取消/已结束的不排。
 */
object ExamReminderPlanner {

    /** 考试提醒的固定强调色（橙色）：与课程色区分，胶囊/卡片渲染时统一使用。 */
    const val EXAM_ACCENT_COLOR = "#FF9500"

    fun plan(
        exams: List<Exam>,
        settings: ReminderSettings,
        now: LocalDateTime,
        zoneId: ZoneId,
        planRevision: Int = 1,
    ): List<ReminderPlanItem> {
        if (!settings.enabled) return emptyList()
        return exams.mapNotNull { exam ->
            val date = exam.startDateOrNull() ?: return@mapNotNull null
            val time = exam.startTimeOrNull() ?: return@mapNotNull null
            if (exam.status == com.gfs.iyango.core.model.ExamStatus.CANCELLED) return@mapNotNull null
            val startAt = LocalDateTime.of(date, time)
            // 结束时刻使用解析后的结束日期（跨日考试显式给出）与结束钟点；不晚于开始时视为未知。
            val endAt = exam.endTimeOrNull()?.let { endTime ->
                val endDate = exam.resolvedEndDateOrNull() ?: return@let null
                LocalDateTime.of(endDate, endTime).takeIf { it.isAfter(startAt) }
            }
            val triggerAt = startAt.minusMinutes(settings.leadMinutes.toLong())
            if (!triggerAt.isAfter(now)) return@mapNotNull null
            val body = buildString {
                append(exam.subject)
                if (exam.location.isNotBlank()) append(" ").append(exam.location)
                if (exam.seat.isNotBlank()) append(" 座位 ").append(exam.seat)
                append(" ").append(time.toString()).append(" 开始")
            }
            val startAtMillis = startAt.atZone(zoneId).toInstant().toEpochMilli()
            val endAtMillis = endAt?.atZone(zoneId)?.toInstant()?.toEpochMilli()
            ReminderPlanItem(
                occurrenceId = "exam:${exam.id}",
                courseId = 0,
                courseName = exam.subject,
                classroom = exam.location,
                occurrenceDate = date,
                startMinute = time.hour * 60 + time.minute,
                triggerAtMillis = triggerAt.atZone(zoneId).toInstant().toEpochMilli(),
                title = "考试提醒 ${exam.subject}",
                body = body,
                payloadHash = MessageDigest.getInstance("SHA-256")
                    .digest(
                        "exam:${exam.id}|$body|$startAtMillis|$endAtMillis|$EXAM_ACCENT_COLOR"
                            .toByteArray(Charsets.UTF_8),
                    )
                    .joinToString(separator = "") { "%02x".format(it) }
                    .take(16),
                planRevision = planRevision,
                startAtMillis = startAtMillis,
                endAtMillis = endAtMillis,
                accentColor = EXAM_ACCENT_COLOR,
            )
        }.sortedBy { it.triggerAtMillis }
    }
}
