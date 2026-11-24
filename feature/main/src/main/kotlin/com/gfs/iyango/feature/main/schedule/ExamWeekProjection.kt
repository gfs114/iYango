package com.gfs.iyango.feature.main.schedule

import com.gfs.iyango.core.model.*
import java.time.*

data class ScheduleExam(val exam: Exam, val date: LocalDate, val topRow: Float? = null, val bottomRow: Float? = null)

/** Presentation only. Dates without a complete, unambiguous instant retain their literal date. */
object ExamWeekProjection {
    fun project(exams: List<Exam>, weekStart: LocalDate, zone: ZoneId, maxSections: Int = 20,
        slots: (Int) -> List<TimeSlot>): List<ScheduleExam> = exams.filter { it.status != ExamStatus.CANCELLED }.flatMap { exam ->
        val rawStart = exam.startDateOrNull() ?: return@flatMap emptyList()
        val rawEnd = exam.resolvedEndDateOrNull() ?: rawStart
        val startTime = exam.startTimeOrNull(); val endTime = exam.endTimeOrNull()
        val examZone = runCatching { ZoneId.of(exam.timeZone) }.getOrNull()
        fun exact(date: LocalDate, time: LocalTime?): ZonedDateTime? {
            if (time == null || examZone == null) return null
            val local = date.atTime(time)
            val offsets = examZone.rules.getValidOffsets(local)
            return offsets.singleOrNull()?.let { ZonedDateTime.ofStrict(local,it,examZone).withZoneSameInstant(zone) }
        }
        val start = exact(rawStart,startTime); val end = exact(rawEnd,endTime)
        val complete = start != null && end != null && end.isAfter(start)
        val first = if (complete) start!!.toLocalDate() else rawStart
        val last = if (complete) end!!.toLocalDate() else maxOf(rawStart,rawEnd)
        val from = maxOf(first,weekStart); val to = minOf(last,weekStart.plusDays(6))
        if (from > to) return@flatMap emptyList()
        generateSequence(from){it.plusDays(1)}.takeWhile { it <= to }.map { date ->
            val canDraw = complete && first == last && rawStart == rawEnd
            val rail = slots(date.dayOfWeek.value)
            val top = if (canDraw) ScheduleAxisGeometry.rowAtMinute(start!!.hour * 60 + start.minute,rail) else null
            val bottom = if (canDraw) ScheduleAxisGeometry.rowAtMinute(end!!.hour * 60 + end.minute,rail,true) else null
            if (top != null && bottom != null && bottom > top && top >= 0f && bottom <= maxSections) ScheduleExam(exam,date,top,bottom) else ScheduleExam(exam,date)
        }.toList()
    }
}
