package com.gfs.iyango.feature.main.schedule

import com.gfs.iyango.core.domain.CourseRules
import com.gfs.iyango.core.domain.EffectiveScheduleDay
import com.gfs.iyango.core.model.Course
import java.time.LocalDate

/** Display-only summaries share the selected, adjusted day instances. */
object ScheduleEffectiveProjection {
    fun offWeekCourses(days: List<EffectiveScheduleDay>): List<Course> = days.filter { !it.isTemporaryDay }.flatMap { day ->
        val table = day.timetable ?: return@flatMap emptyList()
        val week = CourseRules.weekOfDate(day.semester, day.date)
        if (week <= 0 || !CourseRules.tableApplies(table, day.date.dayOfWeek.value, week)) emptyList()
        else CourseRules.forWeek(day.baseCourses, week, day.semester)
            .filter { it.dayOfWeek == day.date.dayOfWeek.value && !CourseRules.occursInWeek(it, week) }
    }

    fun weekDayCounts(days: List<EffectiveScheduleDay>, start: LocalDate, totalWeeks: Int): List<List<Int>> {
        val counts = days.associate { it.date to it.occurrences.size }
        return (0 until totalWeeks.coerceIn(0, 30)).map { week ->
            (0..6).map { day -> counts[start.plusDays(week * 7L + day)] ?: 0 }
        }
    }

    /** 将小节号映射到 5 大节时段行（0:1-2节, 1:3-4节, 2:5-6节, 3:7-8节, 4:9+节）。 */
    fun sectionToPeriodRow(section: Int): Int =
        ((section.coerceAtLeast(1) - 1) / 2).coerceAtMost(4)

    /** 计算每周每天的 5 位大节时段占用掩码 `[weekIndex][dayIndex 0..6]`，bit 0..4 对应第 1..5 大节。 */
    fun weekSlotMasks(days: List<EffectiveScheduleDay>, start: LocalDate, totalWeeks: Int): List<List<Int>> {
        val byDate = days.associateBy { it.date }
        return (0 until totalWeeks.coerceIn(0, 30)).map { week ->
            (0..6).map { day ->
                val eff = byDate[start.plusDays(week * 7L + day)]
                var mask = 0
                eff?.occurrences.orEmpty().forEach { occ ->
                    val startRow = sectionToPeriodRow(occ.course.startSection)
                    val endRow = sectionToPeriodRow(occ.course.endSection.coerceAtLeast(occ.course.startSection))
                    for (row in startRow..endRow) {
                        mask = mask or (1 shl row)
                    }
                }
                mask
            }
        }
    }
}
