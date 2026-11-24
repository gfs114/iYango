package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.TimeSlot

/** Compare effective instances, excluding only the occurrence being replaced, never all weeks of its course. */
object OccurrenceConflicts {
    fun find(candidate: Course, slots: List<TimeSlot>, occurrences: List<CourseOccurrence>,
        sourceOccurrenceId: String?): List<CourseOccurrence> {
        val range = CourseRules.timeRange(candidate, slots)
        val start = CourseRules.minutes(range.startTime)
        val end = CourseRules.minutes(range.endTime)
        return occurrences.filter { other ->
            other.occurrenceId != sourceOccurrenceId &&
                if (start != null && end != null && end > start && other.startMinute != null &&
                    other.endMinute != null && other.endMinute > other.startMinute) {
                    start < other.endMinute && other.startMinute < end
                } else ConflictDetector.sectionsOverlap(candidate, other.course)
        }
    }
}

/** A MOVE belongs to its source table; another selected table cannot display that target. */
object AdjustmentTargetPolicy {
    fun warning(day: EffectiveScheduleDay, sourceTimetableId: Long): String? {
        if (day.isTemporaryDay) return "目标日期使用临时整天课表，请先恢复该日课表"
        val table = day.timetable ?: return "目标日期没有可用课表"
        if (table.id != sourceTimetableId) return "目标日期使用另一课表，请先调整自动选表规则"
        val week = CourseRules.weekOfDate(day.semester, day.date)
        if (week <= 0 || !CourseRules.tableApplies(table, day.date.dayOfWeek.value, week)) {
            return "目标日期未启用此课表，请先调整学期或自动选表规则"
        }
        return null
    }
}

/** Selecting sections replaces any source clock; it must not preserve a school's imported old time. */
object AdjustmentCourseShape {
    fun forSections(course: Course, day: Int, start: Int, end: Int): Course = course.copy(
        dayOfWeek = day, startSection = start, endSection = maxOf(start, end),
        timeMode = 0, startTime = "", endTime = "",
    )
}
