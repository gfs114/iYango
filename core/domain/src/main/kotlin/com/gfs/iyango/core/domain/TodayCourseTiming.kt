package com.gfs.iyango.core.domain

import java.time.Duration
import java.time.LocalDateTime

/** Exact homepage projection. Instances and this one now value are its only inputs. */
data class TodayCourseTiming(
    val now: LocalDateTime,
    val current: CourseOccurrence? = null,
    val next: CourseOccurrence? = null,
    val remainingSeconds: Long? = null,
    val progress: Double = 0.0,
    val unknownTimeCount: Int = 0,
    val overlappingCount: Int = 0,
) {
    companion object {
        fun project(occurrences: List<CourseOccurrence>, now: LocalDateTime): TodayCourseTiming {
            val today = now.toLocalDate()
            val inWindow = occurrences.filter { it.course.enabled && it.date in today..today.plusDays(6) }
            val known = inWindow.filter(::hasValidTime)
            val running = known.filter { now >= startAt(it) && now < endAt(it) }
            val current = running.sortedWith(compareByDescending<CourseOccurrence> { startAt(it) }.thenBy { it.occurrenceId }).firstOrNull()
            val next = known.filter { startAt(it) > now }.minWithOrNull(compareBy({ startAt(it) }, { it.occurrenceId }))
            val target = current?.let(::endAt) ?: next?.let(::startAt)
            val progress = current?.let {
                Duration.between(startAt(it), now).toMillis().toDouble() / Duration.between(startAt(it), endAt(it)).toMillis()
            } ?: 0.0
            return TodayCourseTiming(now, current, next, target?.let { Duration.between(now, it).seconds.coerceAtLeast(0) },
                progress.coerceIn(0.0, 1.0), inWindow.count { !hasValidTime(it) }, running.size)
        }
        fun hasValidTime(occurrence: CourseOccurrence): Boolean = occurrence.startMinute?.let { start ->
            start in 0..1439 && occurrence.endMinute?.let { it in 1..1440 && it > start } == true
        } == true
        fun startAt(occurrence: CourseOccurrence): LocalDateTime = occurrence.date.atStartOfDay().plusMinutes(requireNotNull(occurrence.startMinute).toLong())
        fun endAt(occurrence: CourseOccurrence): LocalDateTime = occurrence.date.atStartOfDay().plusMinutes(requireNotNull(occurrence.endMinute).toLong())
        fun status(occurrence: CourseOccurrence, now: LocalDateTime): CourseStatus {
            if (!hasValidTime(occurrence)) return CourseStatus.NOT_STARTED
            return when {
                now >= endAt(occurrence) -> CourseStatus.FINISHED
                now >= startAt(occurrence) -> CourseStatus.IN_PROGRESS
                Duration.between(now, startAt(occurrence)).seconds <= 1800 -> CourseStatus.UPCOMING
                else -> CourseStatus.NOT_STARTED
            }
        }
    }
}
