package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.TimeSlot
import java.time.LocalTime

/**
 * 课表展示投影（P2-02）。
 *
 * 全部语义逐条移植自鸿蒙参考实现 `CourseScheduleRules.ets` 下半部的
 * `displaySlots` / `project` / `sourceSection`：
 * - [displaySlots] 把"基础作息 + 特殊课程（隐藏节次/休息占位/自定义钟点/单次日期）"
 *   合成为一组**连续展示行**（重新编号的 `section`），供周网格按行布局；
 * - [project] 把课程节次映射到展示行：`startSection/endSection` 变为展示行号，
 *   原始节次保存在 `sourceStartSection/sourceEndSection`（编辑回退用）；
 * - [sourceSection] 把展示行号反查回基础节次。
 *
 * 时间解析统一复用 [CourseRules.minutes]/[clock]/[timeRange]：非法 `HH:mm` 一律按
 * "时间不可用"处理（参考实现返回 NaN，Kotlin 以 null 表达同一语义，比较结果一致为
 * false），绝不抛异常。
 */
object ScheduleDisplay {

    /** 自定义钟点行（不属于任何基础节次）的占位名称。 */
    private const val CUSTOM_SLOT_NAME = "自定义"

    /**
     * 计算给定时刻的展示作息行。
     *
     * 快路径：当全部节次都"普通"（无 hidden、kind=0）、全部课程都按节次上课
     * （timeMode=0）且节次号连续（section == 序号，sortOrder 为 0 或等于序号）时，
     * 原样返回输入（不复制、不重排）。
     *
     * 慢路径（参考算法逐行对齐）：
     * 1. **可见性**：hidden 行仅在"当前时刻落在其钟点区间内"（`start <= now < end`，
     *    半开区间）时可见；钟点不可用的 hidden 行不可见。
     * 2. **边界集合**：收集全部可见行与自定义钟点课（timeMode≠0 且启用）的起止分钟，
     *    去重升序；相邻边界两两组成候选展示区间。
     * 3. **区间保留**：候选区间必须被某个可见行完整覆盖（`slotStart <= s && slotEnd >= e`）
     *    或被某个自定义钟点区间完整覆盖，否则丢弃（这样间隙行自然消失）。
     * 4. **行属性**：被基础行覆盖的区间继承该行的 id/timetableId/kind/sortOrder/name
     *    （无名时回退"第 X 节"）与 defaultCourseName；纯自定义区间命名
     *    [CUSTOM_SLOT_NAME]，sortOrder 取其后继行的权重减 0.5（无后继时取最大权重加
     *    0.5），保证插入到正确的位置。
     * 5. **空占位行**：kind=2 且无钟点的休息行（如午休占位）原样追加（仅把 sortOrder
     *    回退到 section），随排序落在对应位置。
     * 6. **排序与重编号**：按 `sortOrder`（相同则按开始分钟）排序后把 `section`
     *    重新编为 1..n。
     *
     * @param slots 基础作息行（未投影）。
     * @param courses 该课表全部课程（含停用与调整记录；仅 timeMode≠0 的启用课程参与合成）。
     * @param now 判定 hidden 行可见性的当前时刻（参考实现用 Date，Kotlin 用
     *   [LocalTime] 的"时:分"分钟数等价实现）。
     */
    fun displaySlots(slots: List<TimeSlot>, courses: List<Course>, now: LocalTime): List<TimeSlot> {
        val trivial = slots.none { it.hidden || it.kind != 0 } &&
            courses.none { it.timeMode != 0 } &&
            slots.withIndex().all { (index, slot) ->
                slot.section == index + 1 &&
                    (slot.sortOrder == 0.0 || slot.sortOrder == (index + 1).toDouble())
            }
        if (trivial) {
            return slots
        }

        val minute = now.hour * 60 + now.minute
        val visible = slots.filter { slot -> isVisible(slot, minute) }

        val boundaries = mutableListOf<Int>()
        visible.forEach { slot ->
            CourseRules.minutes(slot.startTime)?.let(boundaries::add)
            CourseRules.minutes(slot.endTime)?.let(boundaries::add)
        }
        val ranges = courses
            .filter { it.timeMode != 0 && it.enabled }
            .map { CourseRules.timeRange(it, slots) }
        ranges.forEach { range ->
            CourseRules.minutes(range.startTime)?.let(boundaries::add)
            CourseRules.minutes(range.endTime)?.let(boundaries::add)
        }
        val unique = boundaries.distinct().sorted()

        val result = mutableListOf<TimeSlot>()
        for (index in 0 until (unique.size - 1)) {
            val start = unique[index]
            val end = unique[index + 1]
            val covering = visible.firstOrNull { covers(it, start, end) }
            val custom = ranges.any { covers(it, start, end) }
            if (covering == null && !custom) {
                continue
            }
            val slot = TimeSlot(
                section = result.size + 1,
                startTime = CourseRules.clock(start),
                endTime = CourseRules.clock(end),
            )
            result += if (covering != null) {
                slot.copy(
                    id = covering.id,
                    timetableId = covering.timetableId,
                    kind = covering.kind,
                    sortOrder = weightOf(covering),
                    name = covering.name.ifEmpty { "第 ${covering.section} 节" },
                    defaultCourseName = covering.defaultCourseName,
                )
            } else {
                // 自定义钟点行：插到时间上位于其后继行之前（权重 - 0.5）；
                // 没有后继行时排在全部行之后（最大权重 + 0.5）。
                val next = visible.firstOrNull { item ->
                    CourseRules.minutes(item.startTime)?.let { it >= start } == true
                }
                val fallback = (visible.maxOfOrNull { weightOf(it) } ?: 0.0).coerceAtLeast(0.0)
                slot.copy(
                    name = CUSTOM_SLOT_NAME,
                    sortOrder = next?.let { weightOf(it) - 0.5 } ?: (fallback + 0.5),
                )
            }
        }

        // kind=2 且无钟点的空占位行（午休等）：不参与区间合成，原样追加。
        visible.filter { it.kind == 2 && it.startTime.isEmpty() }.forEach { slot ->
            result += slot.copy(sortOrder = weightOf(slot))
        }

        result.sortWith(
            compareBy(
                { it.sortOrder },
                { CourseRules.minutes(it.startTime) ?: Int.MAX_VALUE },
            ),
        )
        return result.mapIndexed { index, slot -> slot.copy(section = index + 1) }
    }

    /**
     * 把课程节次映射到展示行（写入 `sourceStartSection/sourceEndSection`）。
     *
     * 规则（与参考实现一致）：
     * - 用 [CourseRules.timeRange] 以**基础作息**解析课程的展示钟点；钟点不可用
     *   （时间待定/缺失）时原样返回课程克隆，不做任何映射；
     * - 展示行与课程钟点按**开区间**重叠（`rowStart < rangeEnd && rowEnd > rangeStart`）
     *   匹配；命中时 `startSection/endSection` 改为**首/末命中展示行的编号**，
     *   原始节次写入 `sourceStartSection/sourceEndSection`，`startTime/endTime`
     *   写入解析钟点；
     * - 无命中且课程按节次上课（timeMode=0）、其节次区间完全落在基础隐藏行内时，
     *   `endSection = 0`（"整段被隐藏"的语义标记，展示层跳过该课）。
     *
     * 不修改入参对象。
     */
    fun project(course: Course, base: List<TimeSlot>, display: List<TimeSlot>): Course {
        val range = CourseRules.timeRange(course, base)
        val rangeStart = CourseRules.minutes(range.startTime)
        val rangeEnd = CourseRules.minutes(range.endTime)
        if (rangeStart == null || rangeEnd == null) {
            return course.copy()
        }
        val matching = display.filter { slot ->
            val slotStart = CourseRules.minutes(slot.startTime)
            val slotEnd = CourseRules.minutes(slot.endTime)
            slotStart != null && slotEnd != null &&
                slotStart < rangeEnd && slotEnd > rangeStart
        }
        if (matching.isNotEmpty()) {
            return course.copy(
                sourceStartSection = course.startSection,
                sourceEndSection = course.endSection,
                startSection = matching.first().section,
                endSection = matching.last().section,
                startTime = range.startTime,
                endTime = range.endTime,
            )
        }
        val fullyHidden = course.timeMode == 0 && base.any { slot ->
            slot.hidden && slot.section >= course.startSection && slot.section <= course.endSection
        }
        return if (fullyHidden) course.copy(endSection = 0) else course
    }

    /**
     * 展示行对应的基础节次号（反投影）。
     *
     * 在基础作息中查找 kind=0 且钟点区间**覆盖展示行开始分钟**（`start <= s < end`，
     * 半开区间）的行；找不到（自定义钟点行/输入不完整）返回 0。
     */
    fun sourceSection(slot: TimeSlot, base: List<TimeSlot>): Int {
        val target = CourseRules.minutes(slot.startTime) ?: return 0
        val source = base.firstOrNull { item ->
            item.kind == 0 &&
                CourseRules.minutes(item.startTime)?.let { it <= target } == true &&
                CourseRules.minutes(item.endTime)?.let { it > target } == true
        }
        return source?.section ?: 0
    }

    /** hidden 行仅在 `start <= nowMinute < end`（钟点可用）时可见。 */
    private fun isVisible(slot: TimeSlot, minute: Int): Boolean {
        if (!slot.hidden) {
            return true
        }
        val start = CourseRules.minutes(slot.startTime) ?: return false
        val end = CourseRules.minutes(slot.endTime) ?: return false
        return minute >= start && minute < end
    }

    /** 区间 `[start, end)`（闭开端）是否被行的钟点区间完整覆盖；钟点不可用时视为不覆盖。 */
    private fun covers(slot: TimeSlot, start: Int, end: Int): Boolean {
        val slotStart = CourseRules.minutes(slot.startTime) ?: return false
        val slotEnd = CourseRules.minutes(slot.endTime) ?: return false
        return slotStart <= start && slotEnd >= end
    }

    /** 展示排序权重：sortOrder 为 0（未设置）时回退到 section。 */
    private fun weightOf(slot: TimeSlot): Double =
        if (slot.sortOrder != 0.0) slot.sortOrder else slot.section.toDouble()
}
