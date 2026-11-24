package com.gfs.iyango.feature.main.schedule

import com.gfs.iyango.core.domain.CourseRules
import com.gfs.iyango.core.domain.EffectiveScheduleDay
import com.gfs.iyango.core.domain.ResolveScheduleOccurrences
import com.gfs.iyango.core.domain.TimeRailRow
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.SchoolTimetableData
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.TimeSlot
import com.gfs.iyango.core.model.Timetable
import java.time.LocalDate
import java.time.LocalDateTime

/** A synthetic anchor is only an arithmetic aid; datesKnown fences every visible date. */
fun projectBrowsedTerm(
    source: SchoolTimetableData,
    selectedWeek: Int,
    now: LocalDateTime,
    revision: Long,
    rails: (Timetable, List<Course>, List<TimeSlot>, Int) -> List<TimeRailRow>,
): ScheduleUiState.Ready {
    val knownDate = runCatching { LocalDate.parse(source.startDate) }.getOrNull()
    val lastCourseWeek = source.courses.maxOfOrNull {
        maxOf(it.endWeek, it.selectedWeeks.maxOrNull() ?: 1)
    } ?: 1
    val total = maxOf(source.totalWeeks, lastCourseWeek).coerceIn(1, 30)
    val table = Timetable(
        id = -1,
        name = source.termLabel.ifBlank { source.termValue },
        source = "YGU",
        schoolTerm = source.termValue,
    )
    val semester = Semester(
        timetableId = table.id,
        name = table.name,
        startDate = knownDate ?: LocalDate.of(2000, 1, 3),
        totalWeeks = total,
    )
    val courses = source.courses.mapIndexed { index, course ->
        Course(
            id = -(index + 1L),
            timetableId = table.id,
            name = course.name,
            teacher = course.teacher,
            classroom = course.classroom,
            remark = course.note,
            dayOfWeek = course.dayOfWeek,
            startSection = course.startSection,
            endSection = course.endSection,
            startWeek = course.startWeek,
            endWeek = course.endWeek,
            weekType = course.weekType,
            selectedWeeks = course.selectedWeeks,
            groupKey = course.groupKey,
            sourceKey = course.sourceKey,
            startTime = course.startTime,
            endTime = course.endTime,
            timeUnconfirmed = course.timeUnconfirmed,
        )
    }
    val range = semester.startDate..semester.startDate.plusDays(total * 7L - 1)
    val occurrences = ResolveScheduleOccurrences.invoke(table, semester, source.timeSlots, courses, range)
    val byDate = occurrences.groupBy { it.date }
    val days = (0 until total * 7).map { offset ->
        val date = semester.startDate.plusDays(offset.toLong())
        EffectiveScheduleDay(
            date = date,
            timetable = table,
            semester = semester,
            occurrences = byDate[date].orEmpty(),
            timeSlots = source.timeSlots,
            baseCourses = courses,
        )
    }
    val density = ScheduleEffectiveProjection.weekDayCounts(days, semester.startDate, total)
    val slotMasks = ScheduleEffectiveProjection.weekSlotMasks(days, semester.startDate, total)
    val weeks = (1..total).map { week ->
        buildScheduleWeekData(
            table, semester, source.timeSlots, courses, rails,
            WeekFlipDirection.JUMP, now, days, week, density, slotMasks,
        ).copy(
            readOnly = true,
            browsedTerm = source.termValue,
            datesKnown = knownDate != null,
            currentWeek = if (knownDate == null) 0 else CourseRules.weekOfDate(semester, now.toLocalDate()),
            contextVersion = revision,
        )
    }
    val selected = selectedWeek.takeIf { it > 0 }
        ?: weeks.first().currentWeek.takeIf { it > 0 }
        ?: 1
    return ScheduleUiState.Ready(weeks[(selected - 1).coerceIn(weeks.indices)], weeks)
}
