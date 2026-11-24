package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.CourseValidator
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.TimeSlot
import com.gfs.iyango.core.model.Timetable
import com.gfs.iyango.core.model.WeekType
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 课程发生规则（周规则 / 时间解析 / 重叠判定 / 相邻合并）。
 *
 * 全部语义逐条移植自鸿蒙参考实现 `CourseScheduleRules.ets`，是全应用唯一的
 * "某门课在某个教学周是否发生、发生在几点" 的判定来源：
 * - 教学周从 1 开始计数；
 * - [weekOfDate] 必须用 `LocalDate` 日期运算（天数差整除 7），禁止毫秒运算，跨 DST 安全；
 * - [slotsOverlap] 在四个钟点全部有效时采用**半开区间** `[start, end)`，即
 *   `aStart < bEnd && bStart < aEnd`（09:00-10:00 与 10:00-11:00 不冲突）。
 */
object CourseRules {

    /**
     * 课程是否在第 [week] 教学周发生。
     *
     * 判定顺序与参考实现一致：
     * 1. [Course.enabled] 关闭 → 不发生；
     * 2. [week] 不在 `[startWeek, endWeek]` 闭区间内 → 不发生；
     * 3. 单次调整课程（`overrideWeek > 0`）只在 `overrideWeek == week` 那一周发生；
     * 4. [Course.selectedWeeks] 非空时，仅列表内的周发生；
     * 5. 单双周：ODD 只在 `week % 2 == 1`、EVEN 只在 `week % 2 == 0` 的周发生；
     * 6. 轮换：`cycleWeek == 0` 不启用；启用时要求 `(week - 1) % cycleLength + 1 == cycleWeek`。
     */
    fun occursInWeek(course: Course, week: Int): Boolean {
        if (!course.enabled || week < course.startWeek || week > course.endWeek) {
            return false
        }
        if (course.overrideWeek > 0 && course.overrideWeek != week) {
            return false
        }
        if (course.selectedWeeks.isNotEmpty() && !course.selectedWeeks.contains(week)) {
            return false
        }
        if ((course.weekType == WeekType.ODD && week % 2 != 1) ||
            (course.weekType == WeekType.EVEN && week % 2 != 0)
        ) {
            return false
        }
        return course.cycleWeek == 0 || (week - 1) % course.cycleLength + 1 == course.cycleWeek
    }

    /**
     * [date] 落在学期的第几教学周；学期为 null 或落在 `1..totalWeeks` 之外时返回 0。
     *
     * 计算式：`floorDiv(date 与 startDate 的天数差, 7) + 1`（对齐参考实现的
     * `floor((d - start) / 7d) + 1`）。使用向下取整意味着： startDate 之前 1..6 天
     * （天数差为负且绝对值 < 7）得到第 0 周而非第 1 周。
     *
     * 注意必须用 `LocalDate` 天数差而非毫秒差：毫秒方案在跨夏令时时会整体偏移，
     * 且 [Semester.startDate] 本身就是日历日期。
     */
    fun weekOfDate(semester: Semester?, date: LocalDate): Int {
        if (semester == null) {
            return 0
        }
        val days = ChronoUnit.DAYS.between(semester.startDate, date)
        // java.lang.Math.floorDiv 保证向下取整语义（std 的 floorDiv 已在新版本移除）。
        val week = Math.floorDiv(days, 7L) + 1
        return if (week in 1..semester.totalWeeks.toLong()) week.toInt() else 0
    }

    /**
     * 取"第 [week] 教学周"的课程集合（替换语义）。
     *
     * 规则（与参考实现一致）：
     * - 本周有单次调整（`overrideWeek == week` 且 `overrideOfId > 0`）指向某基础课时，
     *   该基础课**不出现**——无论调整课本身是否 [Course.enabled]；
     * - 单次调整课程只在其目标周出现（`overrideWeek != week` 的两边都不出现）；
     * - [Course.timeMode]=2 的课：`weekOfDate(date) == week` 才出现，且以**副本**返回，
     *   副本的 `dayOfWeek` 改为 date 的星期（周一=1…周日=7）、`startWeek`/`endWeek`
     *   收敛为该周、`selectedWeeks` 收敛为 `[week]`；原对象不被修改；
     * - 其余课：`week ∈ [startWeek, endWeek]` 时原样出现（单双周/轮换的收敛留给
     *   [occursInWeek] 复核，见 [ResolveScheduleOccurrences]）。
     */
    fun forWeek(courses: List<Course>, week: Int, semester: Semester?): List<Course> {
        val replaced = courses.asSequence()
            .filter { it.overrideWeek == week && it.overrideOfId > 0 }
            .map { it.overrideOfId }
            .toSet()
        val result = mutableListOf<Course>()
        for (course in courses) {
            if (!course.enabled || course.id in replaced ||
                (course.overrideWeek > 0 && course.overrideWeek != week)
            ) {
                continue
            }
            if (course.timeMode == 2) {
                val date = runCatching { LocalDate.parse(course.date) }.getOrNull()
                if (date == null || weekOfDate(semester, date) != week) {
                    continue
                }
                // LocalDate.dayOfWeek.value 已经是周一=1…周日=7，无需 JS 的 0→7 换算。
                result += course.copy(
                    dayOfWeek = date.dayOfWeek.value,
                    startWeek = week,
                    endWeek = week,
                    selectedWeeks = listOf(week),
                )
            } else if (week >= course.startWeek && week <= course.endWeek) {
                result += course
            }
        }
        return result
    }

    /**
     * 解析课程在某日的展示时间段。
     *
     * 优先级（与参考实现一致）：
     * 1. `timeUnconfirmed && timeMode == 0` → 返回开始/结束均为 "" 的占位（调用方必须呈现
     *    "时间待定"，**禁止**回退到表级时间）；
     * 2. 课程自身 startTime/endTime 均为合法 `HH:mm` → 直接使用（即使 timeMode=0 也优先生效）；
     * 3. 否则回退到节次：取 `section == startSection` 的首节开始时间与
     *    `section == endSection` 的末节结束时间；找不到对应节时相应一侧为 ""。
     *
     * 返回的 [TimeSlot] 的 `section` 置为课程的 [Course.startSection]。
     */
    fun timeRange(course: Course, slots: List<TimeSlot>): TimeSlot {
        if (course.timeUnconfirmed && course.timeMode == 0) {
            return TimeSlot(section = course.startSection, startTime = "", endTime = "")
        }
        if (CourseValidator.isTimeValid(course.startTime) && CourseValidator.isTimeValid(course.endTime)) {
            return TimeSlot(section = course.startSection, startTime = course.startTime, endTime = course.endTime)
        }
        val first = slots.firstOrNull { it.section == course.startSection }
        val last = slots.firstOrNull { it.section == course.endSection }
        return TimeSlot(
            section = course.startSection,
            startTime = first?.startTime ?: "",
            endTime = last?.endTime ?: "",
        )
    }

    /** 起止时间是否都可参与计算（两者均为合法 `HH:mm`）。 */
    fun isTimeUsable(slot: TimeSlot): Boolean =
        CourseValidator.isTimeValid(slot.startTime) && CourseValidator.isTimeValid(slot.endTime)

    /**
     * `HH:mm` 文本转当日分钟数。
     *
     * @return 分钟数（如 "08:00" → 480）；文本非法时返回 null。
     */
    fun minutes(time: String): Int? {
        if (!CourseValidator.isTimeValid(time)) {
            return null
        }
        val parts = time.split(":")
        return parts[0].toInt() * 60 + parts[1].toInt()
    }

    /** 分钟数转 `HH:mm` 文本（两位补零），如 480 → "08:00"。 */
    fun clock(minutes: Int): String =
        (minutes / 60).toString().padStart(2, '0') + ":" + (minutes % 60).toString().padStart(2, '0')

    /**
     * 两门课是否时间重叠。
     *
     * 当两门课解析出的起止四个钟点全部有效时，按**半开区间** `[start, end)` 判定：
     * `aStart < bEnd && bStart < aEnd`（首尾相接不冲突）；
     * 任一钟点缺失/非法时退化为**闭区间**节次重叠：
     * `left.startSection <= right.endSection && right.startSection <= left.endSection`。
     */
    fun slotsOverlap(left: Course, right: Course, slots: List<TimeSlot>): Boolean {
        val a = timeRange(left, slots)
        val b = timeRange(right, slots)
        val aStart = minutes(a.startTime)
        val aEnd = minutes(a.endTime)
        val bStart = minutes(b.startTime)
        val bEnd = minutes(b.endTime)
        if (aStart != null && aEnd != null && bStart != null && bEnd != null) {
            return aStart < bEnd && bStart < aEnd
        }
        return left.startSection <= right.endSection && right.startSection <= left.endSection
    }

    /**
     * 课表在给定星期与教学周是否生效。
     *
     * `autoEnabled == false` 的课表始终生效；否则要求 `weekday ∈ enabledDays`
     * 且（未启用轮换或 `(week - 1) % cycleLength + 1 == cycleWeek`）。
     */
    fun tableApplies(table: Timetable, weekday: Int, week: Int): Boolean =
        !table.autoEnabled ||
            (table.enabledDays.contains(weekday) &&
                (table.cycleWeek == 0 || (week - 1) % table.cycleLength + 1 == table.cycleWeek))

    /**
     * 合并相邻节次且其余字段完全等值的课程（连续大节还原）。
     *
     * 排序（dayOfWeek 升序、startSection 升序，稳定）后，当前课与结果末尾的课满足以下
     * 全部条件才合并：
     * - 两者 [Course.groupKey] **非空且相等**；
     * - timetableId、dayOfWeek 相等，且 `previous.endSection + 1 == course.startSection`（严格相邻）；
     * - name、teacher、classroom、color、remark、startWeek、endWeek、weekType、cycleLength、
     *   cycleWeek、timeMode、date、overrideWeek、selectedWeeks 全部等值。
     *
     * 合并只把 `endSection`/`endTime`/`sourceEndSection` 扩展为后一课的值，其余字段
     * （含前一课的 id、startSection、startTime）保持不变；不修改任何入参对象。
     */
    fun mergeAdjacent(courses: List<Course>): List<Course> {
        val sorted = courses.map { it.copy() }
            .sortedWith(compareBy({ it.dayOfWeek }, { it.startSection }))
        val result = mutableListOf<Course>()
        for (course in sorted) {
            val previous = result.lastOrNull()
            if (previous != null && canMerge(previous, course)) {
                result[result.lastIndex] = previous.copy(
                    endSection = course.endSection,
                    endTime = course.endTime,
                    sourceEndSection = course.sourceEndSection,
                )
            } else {
                result += course
            }
        }
        return result
    }

    /** [mergeAdjacent] 的合并条件；与参考实现字段清单逐项一致。 */
    private fun canMerge(a: Course, b: Course): Boolean =
        a.groupKey.isNotEmpty() && a.groupKey == b.groupKey &&
            a.timetableId == b.timetableId &&
            a.dayOfWeek == b.dayOfWeek &&
            a.endSection + 1 == b.startSection &&
            a.name == b.name &&
            a.teacher == b.teacher &&
            a.classroom == b.classroom &&
            a.color == b.color &&
            a.remark == b.remark &&
            a.startWeek == b.startWeek &&
            a.endWeek == b.endWeek &&
            a.weekType == b.weekType &&
            a.cycleLength == b.cycleLength &&
            a.cycleWeek == b.cycleWeek &&
            a.timeMode == b.timeMode &&
            a.date == b.date &&
            a.overrideWeek == b.overrideWeek &&
            a.selectedWeeks == b.selectedWeeks
}
