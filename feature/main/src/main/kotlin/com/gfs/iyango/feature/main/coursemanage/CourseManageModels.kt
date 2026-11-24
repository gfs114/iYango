package com.gfs.iyango.feature.main.coursemanage

import com.gfs.iyango.core.domain.CourseRules
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.WeekType
import java.time.LocalDate

/** Filters find courses; a management action always owns all their base arrangements. */
data class ManagedCourse(val arrangements: List<Course>) {
    val id: Long get() = arrangements.minOf { it.id }
    val course: Course get() = arrangements.first()
    val ids: Set<Long> get() = arrangements.map { it.id }.toSet()
    val name: String get() = course.name.ifBlank { "未命名课程" }
}

private data class CourseIdentity(val table: Long, val name: String)

internal fun managedCourses(courses: List<Course>): List<ManagedCourse> = courses
    .filter { it.overrideOfId == 0L }
    // Management groups names within a table, independent of synchronization arrangement keys.
    // This is only a display projection: each original row keeps its own identity and edit target.
    .groupBy { CourseIdentity(it.timetableId, it.name.trim()) }
    .values.map { rows -> ManagedCourse(rows.sortedWith(compareBy({ courseDay(it) }, { it.startTime }, { it.startSection }, { it.id }))) }
    .sortedWith(compareBy({ it.name }, { it.id }))

internal fun ManagedCourse.matches(query: String, week: Int, day: Int, semester: Semester?): Boolean =
    arrangements.any { course ->
        val keyword = query.trim()
        val date = if (course.timeMode == 2) runCatching { LocalDate.parse(course.date) }.getOrNull() else null
        (keyword.isEmpty() || listOf(course.name, course.abbreviation, course.teacher, course.classroom)
            .any { it.contains(keyword, ignoreCase = true) }) &&
            (day == 0 || courseDay(course) == day) &&
            (week == 0 || if (course.timeMode == 2) date != null && CourseRules.weekOfDate(semester, date) == week
                else CourseRules.occursInWeek(course.copy(enabled = true), week))
    }

internal fun courseDay(course: Course): Int = if (course.timeMode == 2)
    runCatching { LocalDate.parse(course.date).dayOfWeek.value }.getOrDefault(course.dayOfWeek)
else course.dayOfWeek

internal val COURSE_DAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

internal fun arrangementTime(course: Course): String {
    val day = if (course.timeMode == 2) course.date else COURSE_DAYS.getOrElse(course.dayOfWeek - 1) { "待定" }
    val time = if (course.startTime.isNotBlank() && course.endTime.isNotBlank()) "${course.startTime}–${course.endTime}"
        else if (course.timeMode == 1) "时间待定"
        else if (course.startSection == course.endSection) "第 ${course.startSection} 节"
        else "第 ${course.startSection}–${course.endSection} 节"
    return "$day $time"
}

internal fun arrangementWeeks(course: Course): String {
    if (course.timeMode == 2) return "仅此一天"
    return buildList {
        add(if (course.selectedWeeks.isNotEmpty()) "第 ${course.selectedWeeks.distinct().sorted().joinToString("、")} 周"
            else if (course.startWeek == course.endWeek) "第 ${course.startWeek} 周"
            else "第 ${course.startWeek}–${course.endWeek} 周")
        when (course.weekType) {
            WeekType.ODD -> add("单周")
            WeekType.EVEN -> add("双周")
            else -> Unit
        }
        if (course.cycleWeek > 0) add("每 ${course.cycleLength} 周的第 ${course.cycleWeek} 周")
    }.joinToString(" ")
}

internal fun arrangementPeople(course: Course): String = listOf(course.teacher, course.classroom)
    .filter { it.isNotBlank() }.joinToString(" ").ifBlank { "未填写教师/教室" }

internal fun ManagedCourse.teacherSummary(): String = arrangements
    .flatMap { it.teacher.split(Regex("[、,，;；\\r\\n]+")) }
    .map { it.trim() }.filter { it.isNotBlank() }.distinct()
    .joinToString("\n").ifBlank { "未填写教师" }

data class CourseDeleteRequest(val timetableId: Long, val groups: List<ManagedCourse>) {
    val ids: Set<Long> get() = groups.flatMap { it.ids }.toSet()
}
