package com.gfs.iyango.core.model

import java.time.LocalDate

/**
 * 课程与节次的共享校验器（表单层与仓储层共用）。
 *
 * 消息文案与鸿蒙参考实现 CourseModels.ets 逐条对齐；[validate] 返回**首个**发现的问题，
 * null 表示通过。Kotlin 枚举使 `weekType` 取值由类型系统保证合法（对应参考实现中
 * 不可达的"周次类型无效"分支），故此处不再有对应检查项。
 */
object CourseValidator {

    private val TIME_REGEX = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    private val DATE_REGEX = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    /** 名称去掉首尾空白后是否为空。 */
    fun isEmptyName(name: String): Boolean = name.trim().isEmpty()

    /** 节次区间是否合法：start ≥ 1 且 start ≤ end（闭区间）。 */
    fun isSectionRangeValid(startSection: Int, endSection: Int): Boolean =
        startSection >= 1 && endSection >= startSection

    /** 周次区间是否合法：start ≥ 1 且 start ≤ end（闭区间，教学周从 1 开始）。 */
    fun isWeekRangeValid(startWeek: Int, endWeek: Int): Boolean =
        startWeek >= 1 && endWeek >= startWeek

    /**
     * 是否为合法 `HH:mm` 钟点文本。
     *
     * 严格匹配 `^([01]\d|2[0-3]):[0-5]\d$`：小时必须两位、00–23，分钟两位、00–59；
     * "7:30"、"24:00" 等一律非法。
     */
    fun isTimeValid(time: String): Boolean = TIME_REGEX.matches(time)

    /**
     * 是否为严格合法的 `yyyy-MM-dd` 日期文本。
     *
     * 除格式（四位年、两位月/日）外还校验日历合法性："2026-02-30"、"2026-13-01"、
     * 未补零的 "2026-2-3" 均非法；"2024-02-29"（闰年）合法。
     */
    fun isDateValid(text: String): Boolean {
        if (!DATE_REGEX.matches(text)) {
            return false
        }
        return try {
            val date = LocalDate.parse(text)
            // 参考实现要求 year > 0；java.time 允许 0 年，这里显式排除。
            date.year > 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 轮换规则是否合法：`1 ≤ length` 且 `0 ≤ week ≤ length`。
     *
     * week=0 表示"不启用轮换"，因此允许取 0。
     */
    fun isCycleValid(length: Int, week: Int): Boolean =
        length >= 1 && week >= 0 && week <= length

    /**
     * 校验一个作息时间表行。
     *
     * @return 首个问题的中文消息；null 表示通过。
     */
    fun validateSlot(slot: TimeSlot): String? {
        if (slot.section < 1) {
            return "节次必须为正整数"
        }
        if (slot.kind < 0 || slot.kind > 3) {
            return "时间节点类型无效"
        }
        // 休息/占位节点允许没有钟点。
        if (slot.kind == 2 && slot.startTime.isEmpty() && slot.endTime.isEmpty()) {
            return null
        }
        if (!isTimeValid(slot.startTime) || !isTimeValid(slot.endTime) || slot.startTime >= slot.endTime) {
            return "开始时间必须早于结束时间"
        }
        return null
    }

    /**
     * 校验一条课程定义，返回首个问题；null 表示通过。
     *
     * 校验顺序与参考实现一致：名称 → 星期 → 节次区间 → 周次区间 → 学分 → 备注 →
     * 所选周 → 轮换 → 时间模式 → 自定义时间 → 指定日期 → 单次调整来源。
     */
    fun validate(course: Course): String? {
        if (isEmptyName(course.name)) {
            return "课程名称不能为空"
        }
        if (course.dayOfWeek < 1 || course.dayOfWeek > 7) {
            return "星期取值需在 1-7 之间"
        }
        if (!isSectionRangeValid(course.startSection, course.endSection)) {
            return "开始节次不能大于结束节次"
        }
        if (!isWeekRangeValid(course.startWeek, course.endWeek)) {
            return "开始周不能大于结束周"
        }
        if (!course.credit.isFinite() || course.credit < 0) {
            return "学分必须为非负数"
        }
        if (course.remark.length > 300) {
            return "备注最多 300 字"
        }
        if (course.selectedWeeks.any { it < course.startWeek || it > course.endWeek }) {
            return "所选周次需在上课周次范围内"
        }
        if (!isCycleValid(course.cycleLength, course.cycleWeek)) {
            return "轮换周次规则无效"
        }
        if (course.timeMode < 0 || course.timeMode > 2) {
            return "上课时间模式无效"
        }
        if (course.timeMode > 0 &&
            (!isTimeValid(course.startTime) || !isTimeValid(course.endTime) || course.startTime >= course.endTime)
        ) {
            return "开始时间必须早于结束时间"
        }
        if (course.timeMode == 2 && !isDateValid(course.date)) {
            return "上课日期无效"
        }
        if (course.overrideOfId < 0 || course.overrideWeek < 0 ||
            (course.overrideOfId > 0 && course.overrideWeek < 1)
        ) {
            return "临时换课来源或周次无效"
        }
        return null
    }

    /**
     * 跨记录校验：单次调整记录的 (overrideOfId, overrideWeek) 在同课表内必须唯一。
     *
     * 规则（P2-01 契约）：[candidate.overrideOfId] > 0 时，[existing] 中不得存在
     * **另一条**（id 不同，允许"保存即自身"的更新场景）满足
     * `timetableId`、`overrideOfId`、`overrideWeek` 全部等值的课程，否则返回
     * `"该周已有针对此课程的调整"`。
     *
     * 这是数据库唯一索引（data 层 `index_course_override`，对可空列建索引、非调整行以
     * NULL 落库而不参与唯一性）之外的应用层双保险：导入批量校验与保存路径都先过这里，
     * 避免 SQLiteConstraintException 以裸异常形式冒泡。
     *
     * @param candidate 待保存的课程（id=0 表示新建）。
     * @param existing 同课表下的现有课程集合（含候选自身，若为更新场景）。
     * @return 首个冲突的中文消息；null 表示通过。
     */
    fun validateOverrideUniqueness(candidate: Course, existing: List<Course>): String? {
        if (candidate.overrideOfId <= 0) {
            return null
        }
        val conflict = existing.any { other ->
            other.id != candidate.id &&
                other.timetableId == candidate.timetableId &&
                other.overrideOfId == candidate.overrideOfId &&
                other.overrideWeek == candidate.overrideWeek
        }
        return if (conflict) "该周已有针对此课程的调整" else null
    }
}
