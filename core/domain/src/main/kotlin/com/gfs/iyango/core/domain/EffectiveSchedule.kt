package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.AdjustmentType
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.OccurrenceAdjustment
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.TemporaryDay
import com.gfs.iyango.core.model.Timetable
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/** 某一天的有效课表（含自动选中的课表与是否临时整天）。 */
data class EffectiveScheduleDay(
    val date: LocalDate,
    val timetable: Timetable? = null,
    val semester: Semester? = null,
    val occurrences: List<CourseOccurrence> = emptyList(),
    val isTemporaryDay: Boolean = false,
    val timeSlots: List<com.gfs.iyango.core.model.TimeSlot> = emptyList(),
    val baseCourses: List<Course> = emptyList(),
    val adjustments: List<OccurrenceAdjustment> = emptyList(),
    val displayTimeSlots: List<com.gfs.iyango.core.model.TimeSlot> = timeSlots,
    /** Actual display source; the timetable above remains the owner used for restoration. */
    val displaySource: String = timetable?.source.orEmpty(),
)

/**
 * F07 分层课表选择：按实际日期挑选“同组/自动启用”的课表。
 *
 * 规则（可解释且稳定）：
 * 1. 若当前课表存在且 `autoEnabled=false`，它始终生效，直接选中；
 * 2. 否则先把当前课表（若它也自动启用且当日生效）放在首位，再按 id 升序检查其余
 *    自动启用课表；
 * 3. 候选必须同时满足：`enabledDays` 含当日星期、轮换相位匹配、当日落在该课表
 *    学期教学周内；
 * 4. 无命中时回退到当前课表（由展示层呈现“无安排”），没有当前课表则返回 null。
 *
 * 临时整天覆盖的优先级更高，由调用方在拿到课表后单独应用。
 */
object EffectiveTimetableSelector {

    fun selectForDate(
        date: LocalDate,
        current: Timetable?,
        all: List<Timetable>,
        semesterOf: (Long) -> Semester?,
    ): Timetable? {
        if (current != null && !current.autoEnabled) {
            return current
        }
        // 分组：当前课表有分组名时，只考虑同组课表；当前无分组则兼容旧的“全部自动课表”。
        val group = current?.groupName.orEmpty()
        val ordered = buildList {
            if (current != null) add(current)
            all.filter {
                it.id != current?.id && it.autoEnabled &&
                    (group.isEmpty() || it.groupName == group)
            }
                .sortedBy { it.id }
                .forEach(::add)
        }
        val matched = ordered.firstOrNull { table ->
            val week = CourseRules.weekOfDate(semesterOf(table.id), date)
            week > 0 && CourseRules.tableApplies(table, date.dayOfWeek.value, week)
        }
        return matched ?: current
    }

    /** 说明性文案：为什么这一天选中了某课表。 */
    fun explanation(table: Timetable?, date: LocalDate, semester: Semester?): String {
        if (table == null) return "没有可用课表"
        val week = CourseRules.weekOfDate(semester, date)
        return when {
            !table.autoEnabled -> "使用当前课表（未启用自动切换）"
            week <= 0 -> "使用当前课表（${date} 不在学期内）"
            else -> "按分组规则自动选用「${table.name}」第 ${week} 周"
        }
    }
}

/**
 * 把 F08 临时调整应用到基础实例集合。
 *
 * 输入：
 * - [base]：由 [ResolveScheduleOccurrences] 生成的基础实例；
 * - [courses]：课表全部课程（移动目标需要据此重建课程副本）；
 * - [adjustments]：CANCEL/MOVE 记录；
 * - [temporaryDays]：临时整天记录；
 * - [temporaryDaySources]：日期文本（yyyy-MM-dd）→ 来源课表该日的实例；
 * - [range]：查询窗口。
 *
 * 语义：
 * - MOVE 压制源日期的实例，即使查询窗口只包含目标周也能通过目标日期直接生成目标实例；
 * - 同一操作组跨周移动使用**源日期锚点**计算 occurrenceId，目标实例与源实例同身份；
 * - 临时整天只替换该日期的展示，不动任何课程行。
 */
object ApplyOccurrenceAdjustments {

    fun apply(
        base: List<CourseOccurrence>,
        courses: List<Course>,
        timeSlots: List<com.gfs.iyango.core.model.TimeSlot>,
        adjustments: List<OccurrenceAdjustment>,
        temporaryDays: List<TemporaryDay>,
        temporaryDaySources: Map<String, List<CourseOccurrence>>,
        range: ClosedRange<LocalDate>,
    ): List<CourseOccurrence> {
        if (adjustments.isEmpty() && temporaryDays.isEmpty()) {
            return base
        }
        val tempByDate = temporaryDays.associateBy { it.date }
        val suppressed = adjustments
            .filter { it.type == AdjustmentType.CANCEL || it.type == AdjustmentType.MOVE }
            .map { it.sourceCourseId to it.sourceDate }
            .toSet()

        val result = mutableListOf<CourseOccurrence>()
        base.forEach { occurrence ->
            val baseCourseId = occurrence.course.overrideOfId.takeIf { it > 0 } ?: occurrence.course.id
            val dateKey = occurrence.date.toString()
            if (tempByDate.containsKey(dateKey)) return@forEach
            if ((baseCourseId to dateKey) in suppressed) return@forEach
            result += occurrence
        }

        val courseById = courses.associateBy { it.id }
        adjustments.filter { it.type == AdjustmentType.MOVE }.forEach { move ->
            val target = runCatching { LocalDate.parse(move.targetDate) }.getOrNull() ?: return@forEach
            if (target !in range) return@forEach
            val course = courseById[move.sourceCourseId] ?: return@forEach
            val sourceDate = runCatching { LocalDate.parse(move.sourceDate) }.getOrNull() ?: return@forEach
            val targetCourse = moveTargetCourse(course, move, target)
            val timeRange = CourseRules.timeRange(targetCourse, timeSlots)
            result += CourseOccurrence(
                occurrenceId = CourseOccurrence.stableIdForAnchor(course, sourceDate),
                course = targetCourse,
                date = target,
                startMinute = CourseRules.minutes(timeRange.startTime),
                endMinute = CourseRules.minutes(timeRange.endTime),
                isOverride = false,
                anchorDate = sourceDate,
                isMoved = true,
            )
        }

        temporaryDays.forEach { day ->
            val target = runCatching { LocalDate.parse(day.date) }.getOrNull() ?: return@forEach
            if (target !in range) return@forEach
            temporaryDaySources[day.date].orEmpty().forEach { source ->
                val entry = source.copy(
                    occurrenceId = CourseOccurrence.stableIdForAnchor(source.course, target),
                    date = target,
                    anchorDate = source.date,
                    isTemporary = true,
                )
                result += entry
            }
        }

        return result.distinctBy { it.occurrenceId }
            .sortedWith(
                compareBy(
                    { it.date },
                    { it.startMinute ?: Int.MAX_VALUE },
                    { it.course.startSection },
                ),
            )
    }

    private fun moveTargetCourse(
        course: Course,
        move: OccurrenceAdjustment,
        targetDate: LocalDate,
    ): Course {
        val day = move.targetDayOfWeek.takeIf { it in 1..7 } ?: targetDate.dayOfWeek.value
        val startSection = move.targetStartSection.takeIf { it > 0 } ?: course.startSection
        val endSection = move.targetEndSection.takeIf { it > 0 } ?: course.endSection
        val hasClock = move.targetStartTime.isNotEmpty() && move.targetEndTime.isNotEmpty()
        val hasSections = move.targetStartSection > 0 && move.targetEndSection > 0
        val target = if (!hasClock && hasSections) AdjustmentCourseShape.forSections(course, day, startSection, endSection) else course
        return target.copy(
            overrideOfId = 0,
            overrideWeek = 0,
            enabled = true,
            dayOfWeek = day,
            startSection = startSection,
            endSection = maxOf(startSection, endSection),
            timeMode = if (hasClock) 1 else target.timeMode,
            startTime = if (hasClock) move.targetStartTime else target.startTime,
            endTime = if (hasClock) move.targetEndTime else target.endTime,
            classroom = move.targetClassroom.ifEmpty { course.classroom },
        )
    }
}

/** 有效课表查询端口（实现在 data 层，统一今日/课表/提醒/日历/组件的数据来源）。 */
interface EffectiveSchedulePort {

    /** 按当前分组规则逐日选表；固定课表导出/组件继续使用 resolveRange。 */
    suspend fun resolveSelectedRange(range: ClosedRange<LocalDate>): List<EffectiveScheduleDay> =
        buildList {
            var date = range.start
            while (date <= range.endInclusive) { add(resolveDay(date)); date = date.plusDays(1) }
        }

    fun observeSelectedRange(range: ClosedRange<LocalDate>): Flow<List<EffectiveScheduleDay>>

    /** 观察某一天的有效实例（含自动选表、临时调整）。 */
    fun observeDay(date: LocalDate): Flow<EffectiveScheduleDay>

    /** 观察指定课表一段日期内的有效实例。 */
    fun observeRange(timetableId: Long, range: ClosedRange<LocalDate>): Flow<List<CourseOccurrence>>

    /** 一次性解析指定课表一段日期内的有效实例。 */
    suspend fun resolveRange(timetableId: Long, range: ClosedRange<LocalDate>): List<CourseOccurrence>

    /** 一次性解析某一天（自动选表）。 */
    suspend fun resolveDay(date: LocalDate): EffectiveScheduleDay

    /** 当前日期应使用的课表（F07 自动选择结果）。 */
    suspend fun timetableForDate(date: LocalDate): Timetable?

    /** Fixed owner with effective display metadata, including temporary-day source bells; never auto-selects. */
    suspend fun resolveTimetableDays(timetableId: Long, range: ClosedRange<LocalDate>): List<EffectiveScheduleDay>

    /** 解析任意课表一段日期的基础实例（不做自动选表）。 */
    suspend fun resolveTimetableRange(timetableId: Long, range: ClosedRange<LocalDate>): List<CourseOccurrence>
}
