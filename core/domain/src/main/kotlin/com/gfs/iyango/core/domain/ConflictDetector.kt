package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.Course

/**
 * 一条与候选课程相撞的已有课程及其细节。
 *
 * @property existing 已经存储、与候选课程相撞的课程。
 * @property sharedWeeks 两者共同上课的教学周数量（逐周判定，已计入单双周/轮换/所选周）。
 * @property sameSections 两者是否占据完全相同的节次区间。
 */
data class CourseConflict(
    val existing: Course,
    val sharedWeeks: Int,
    val sameSections: Boolean,
)

/**
 * 课程之间的冲突检测。
 *
 * 两门课"相撞"的条件：同一 [Course.dayOfWeek]（周一=1…周日=7）、节次区间闭重叠、
 * 且至少共享一个教学周（共享周逐周判定而非区间算术，因为单双周/轮换使周集合不连续）。
 *
 * **冲突仅提示不阻断**：调用方决定是否弹窗确认，相撞永远不阻止保存——学生完全可能
 * 需要登记重叠条目（同一时段的实验与讲座、重修课程等）。
 */
object ConflictDetector {

    /**
     * 两门课的节次区间是否共享至少一节。
     *
     * 使用**闭区间**判定：`left.startSection <= right.endSection && right.startSection <= left.endSection`。
     */
    fun sectionsOverlap(left: Course, right: Course): Boolean =
        left.startSection <= right.endSection && right.startSection <= left.endSection

    /**
     * 两者共同上课的教学周数量。
     *
     * 在两者周区间的交集内逐周调用 [CourseRules.occursInWeek] 判定，而不是用区间算术，
     * 因为单双周/轮换规则使周集合不连续。
     */
    fun sharedWeekCount(left: Course, right: Course): Int {
        val from = maxOf(left.startWeek, right.startWeek)
        val to = minOf(left.endWeek, right.endWeek)
        if (from > to) {
            return 0
        }
        var shared = 0
        for (week in from..to) {
            if (CourseRules.occursInWeek(left, week) && CourseRules.occursInWeek(right, week)) {
                shared++
            }
        }
        return shared
    }

    /**
     * 两门课是否相撞，可忽略一个课程 id。
     *
     * @param ignoreId 编辑中的课程 id：传入它可避免课程与自己相撞
     *   （id > 0 才参与比较，未持久化课程 id=0 不受影响）。
     */
    fun conflictsIgnoring(left: Course, right: Course, ignoreId: Long): Boolean {
        if (left.id > 0 && left.id == ignoreId) {
            return false
        }
        if (right.id > 0 && right.id == ignoreId) {
            return false
        }
        if (left.dayOfWeek != right.dayOfWeek) {
            return false
        }
        if (!sectionsOverlap(left, right)) {
            return false
        }
        return sharedWeekCount(left, right) > 0
    }

    /**
     * 找出 [existing] 中所有与 [candidate] 相撞的课程，顺序与 [existing] 一致。
     *
     * @param ignoreId 忽略的课程 id；默认忽略 [Course.id]（候选课程自身的 id），
     *   这样重新保存编辑中的课程不会与它正在替换的行相撞。
     */
    fun findConflicts(
        candidate: Course,
        existing: List<Course>,
        ignoreId: Long = candidate.id,
    ): List<CourseConflict> {
        val found = mutableListOf<CourseConflict>()
        for (course in existing) {
            if (!conflictsIgnoring(candidate, course, ignoreId)) {
                continue
            }
            val sameSections = candidate.startSection == course.startSection &&
                candidate.endSection == course.endSection
            found += CourseConflict(
                existing = course,
                sharedWeeks = sharedWeekCount(candidate, course),
                sameSections = sameSections,
            )
        }
        return found
    }

    /**
     * 冲突确认文案；无冲突返回 null。
     *
     * 文案固定为 `发现课程冲突：A、B，是否继续添加？`（与参考实现一致）；
     * 课程名去首尾空白后为空时显示"未命名课程"。
     */
    fun warningMessage(conflicts: List<CourseConflict>): String? {
        if (conflicts.isEmpty()) {
            return null
        }
        val names = conflicts.map { conflict ->
            val name = conflict.existing.name.trim()
            if (name.isNotEmpty()) name else "未命名课程"
        }
        return "发现课程冲突：" + names.joinToString("、") + "，是否继续添加？"
    }
}
