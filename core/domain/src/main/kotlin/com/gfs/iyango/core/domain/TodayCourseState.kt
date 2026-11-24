package com.gfs.iyango.core.domain

import java.time.LocalDateTime

/**
 * 今日课程状态（提醒渲染、App 内胶囊与小组件共用的纯函数模型）。
 *
 * 由 [TodayCourseStateResolver] 从当天有效实例解析。口径约定：
 * - 只有「开始钟点已知且课程启用」的实例参与计算（时间待定/停用的课被忽略）；
 * - 课中判定要求结束时刻已知且有效（end > start）；「已开始但无结束时刻」的课
 *   不参与课中匹配、也不阻塞「已结束」判定——与提醒渲染对该类课的退场策略一致。
 */
sealed interface TodayCourseState {

    /** 课中：[current] 正在进行，[next] 为排序列表中的下一节（可能为 null）。 */
    data class InClass(
        val current: CourseOccurrence,
        val next: CourseOccurrence?,
        /** 当天第几节（1 起算，与 [TodayCourseStateResolver.timedOccurrences] 排序一致）。 */
        val index: Int,
        /** 当天有效节次总数。 */
        val total: Int,
        /** 结束时刻。 */
        val endAt: LocalDateTime,
    ) : TodayCourseState

    /** 课前/课间：[next] 为即将开始的下一节。 */
    data class Upcoming(
        val next: CourseOccurrence,
        val index: Int,
        val total: Int,
        val startAt: LocalDateTime,
    ) : TodayCourseState

    /** 今天全部课程已结束；[total] 为当天有效节次总数。 */
    data class Finished(val total: Int) : TodayCourseState

    /** 当天没有时间确定的课程。 */
    data object NoClass : TodayCourseState
}

/** 今日课程状态解析（纯函数，不依赖平台）。 */
object TodayCourseStateResolver {

    /**
     * 参与当天状态计算的实例：开始钟点已知且课程启用，按开始钟点排序。
     * 提醒的「第 x/y 节」与 App 内展示共用同一口径，避免两处展示不一致。
     */
    fun timedOccurrences(occurrences: List<CourseOccurrence>): List<CourseOccurrence> =
        occurrences
            .filter { it.startMinute != null && it.course.enabled }
            .sortedWith(compareBy({ it.startMinute }, { it.endMinute ?: Int.MAX_VALUE }))

    /** 解析当前时刻的今日课程状态。 */
    fun resolve(occurrences: List<CourseOccurrence>, now: LocalDateTime): TodayCourseState {
        val timed = timedOccurrences(occurrences)
        if (timed.isEmpty()) return TodayCourseState.NoClass
        val total = timed.size
        val nowMinute = now.hour * 60 + now.minute

        // 课中：开始 <= 现在 < 结束；结束未知/无效（end <= start）的课不参与匹配。
        val currentIndex = timed.indexOfFirst { occ ->
            val start = occ.startMinute ?: return@indexOfFirst false
            val end = occ.endMinute?.takeIf { it > start }
            end != null && start <= nowMinute && nowMinute < end
        }
        if (currentIndex >= 0) {
            val current = timed[currentIndex]
            return TodayCourseState.InClass(
                current = current,
                // 下一节取排序列表中的后继；重叠课时仍给出后继，便于预告。
                next = timed.getOrNull(currentIndex + 1),
                index = currentIndex + 1,
                total = total,
                endAt = now.toLocalDate().atStartOfDay().plusMinutes(current.endMinute!!.toLong()),
            )
        }

        // 课前/课间：找下一节还未开始的课。
        val upcomingIndex = timed.indexOfFirst { it.startMinute!! > nowMinute }
        if (upcomingIndex >= 0) {
            val next = timed[upcomingIndex]
            return TodayCourseState.Upcoming(
                next = next,
                index = upcomingIndex + 1,
                total = total,
                startAt = now.toLocalDate().atStartOfDay().plusMinutes(next.startMinute!!.toLong()),
            )
        }

        // 无课中、无未开始：当天课程已全部结束（已开始但无结束时刻的课不阻塞该判定）。
        return TodayCourseState.Finished(total)
    }
}
