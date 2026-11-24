package com.gfs.iyango.core.domain

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 旧课表网格及兼容调用方的分钟级课程状态与进度入口。
 *
 * 首页秒级倒计时使用 [TodayCourseTiming] 的半开区间；此处保留网格的结束分钟语义。
 * 时间以"当日分钟数"（自本地零点起）比较；分钟数不可用（null）的课程永远不会
 * 被判定为进行中，避免没有作息表的课程谎报状态。
 *
 * 语义约束：
 * - **current <= end 判为 IN_PROGRESS（含结束分钟）**，与参考实现一致，不得改为半开；
 * - 同一天且 `end <= start`（含相等，规格明确要求）或分钟不可用 → NOT_STARTED；
 * - [progress] 结果收敛在 0.0..1.0，不可用时为 0.0。
 */
object CourseStatusCalculator {

    /** 开课前多少分钟算"即将开始"。 */
    const val UPCOMING_WINDOW_MINUTES: Int = 30

    /**
     * 判定 [day] 这天的课程在 [now] 时刻的状态。
     *
     * @param day 课程发生日期。
     * @param now 当前时刻。
     * @param startMinute 开始时间（当日分钟数）；null 表示不可用。
     * @param endMinute 结束时间（当日分钟数）；null 表示不可用。
     */
    fun resolve(day: LocalDate, now: LocalDateTime, startMinute: Int?, endMinute: Int?): CourseStatus {
        if (day != now.toLocalDate()) {
            // 不同日按先后：过去的日期视为已结束，将来的日期视为未开始。
            return if (day.isBefore(now.toLocalDate())) CourseStatus.FINISHED else CourseStatus.NOT_STARTED
        }
        // 结束不晚于开始（含相等）或时间缺失，都是坏数据，永远不算进行中。
        if (startMinute == null || endMinute == null || endMinute <= startMinute) {
            return CourseStatus.NOT_STARTED
        }
        val current = now.hour * 60 + now.minute
        return when {
            current < startMinute - UPCOMING_WINDOW_MINUTES -> CourseStatus.NOT_STARTED
            current < startMinute -> CourseStatus.UPCOMING
            // 含结束分钟：current == end 仍在上课（与参考实现一致）。
            current <= endMinute -> CourseStatus.IN_PROGRESS
            else -> CourseStatus.FINISHED
        }
    }

    /**
     * 课程在 [now] 时刻的进度，结果收敛在 0.0..1.0。
     *
     * 不可用情形（不同日、分钟缺失、`end <= start`）一律返回 0.0；
     * `current <= start` 为 0.0，`current >= end` 为 1.0。
     */
    fun progress(day: LocalDate, now: LocalDateTime, startMinute: Int?, endMinute: Int?): Double {
        if (day != now.toLocalDate()) {
            return 0.0
        }
        if (startMinute == null || endMinute == null || endMinute <= startMinute) {
            return 0.0
        }
        val current = now.hour * 60 + now.minute
        if (current <= startMinute) {
            return 0.0
        }
        if (current >= endMinute) {
            return 1.0
        }
        return (current - startMinute).toDouble() / (endMinute - startMinute)
    }

    /**
     * 该状态是否需要在课程卡上打徽标。
     *
     * FINISHED 与 NOT_STARTED 不打标：给每张卡都加徽标只会制造噪音，
     * 只标记"活着"的状态才能保持概览可读。
     */
    fun isHighlighted(status: CourseStatus): Boolean =
        status == CourseStatus.IN_PROGRESS || status == CourseStatus.UPCOMING

    /** 状态的中文文案。 */
    fun label(status: CourseStatus): String = when (status) {
        CourseStatus.IN_PROGRESS -> "正在上课"
        CourseStatus.UPCOMING -> "即将开始"
        CourseStatus.FINISHED -> "已结束"
        CourseStatus.NOT_STARTED -> "未开始"
    }
}
