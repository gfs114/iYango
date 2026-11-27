package com.gfs.iyango.feature.main.schedule

import com.gfs.iyango.core.domain.CourseRules
import com.gfs.iyango.core.model.TimeSlot

/** Pure content coordinates, excluding header/rail. At a break an integer row has two edges. */
data class ScheduleAxisGeometry(
    val sectionCount: Int,
    val rowHeight: Float,
    val lunchAfter: Int? = null,
    val lunchHeight: Float = 18f,
    val eveningAfter: Int? = null,
    val eveningHeight: Float = 18f,
) {
    private val boundary = lunchAfter?.takeIf { it in 1 until sectionCount }
    private val eveningBoundary = eveningAfter?.takeIf { it in 1 until sectionCount && it != boundary }

    val totalHeight: Float
        get() = sectionCount * rowHeight +
            (if (boundary != null) lunchHeight else 0f) +
            (if (eveningBoundary != null) eveningHeight else 0f)

    private fun gapBefore(target: Int?, height: Float, row: Float, endEdge: Boolean): Float =
        if (target != null && (row > target || (!endEdge && row == target.toFloat()))) height else 0f

    fun rowToY(row: Float, endEdge: Boolean = false): Float =
        row * rowHeight +
            gapBefore(boundary, lunchHeight, row, endEdge) +
            gapBefore(eveningBoundary, eveningHeight, row, endEdge)
    fun sectionTop(section: Int): Float = rowToY((section - 1).toFloat())
    fun sectionBottom(section: Int): Float = rowToY(section.toFloat(), true)
    val gridLines: List<Float> get() = (1..sectionCount).flatMap { listOf(sectionTop(it),sectionBottom(it)) }.distinct()
    fun sectionAtY(y: Float): Int? {
        if (!y.isFinite() || y < 0 || y >= totalHeight) return null
        return (1..sectionCount).firstOrNull { y >= sectionTop(it) && y < sectionBottom(it) }
    }
    fun minuteToY(minute: Int, slots: List<TimeSlot>, endEdge: Boolean = false): Float? =
        rowAtMinute(minute, slots, endEdge)?.let { rowToY(it, endEdge) }
    companion object {
        /** A broken/duplicated/reversed rail cannot establish a reliable minute mapping. */
        private fun validSlots(slots: List<TimeSlot>): List<TimeSlot>? {
            if (slots.isEmpty() || slots.any { it.section < 1 } || slots.map { it.section }.distinct().size != slots.size) return null
            val sorted = slots.sortedBy { it.section }
            var previousEnd = -1
            val known = mutableListOf<TimeSlot>()
            for (slot in sorted) {
                val start = CourseRules.minutes(slot.startTime) ?: continue
                val end = CourseRules.minutes(slot.endTime) ?: continue
                if (end <= start || start < previousEnd) return null
                previousEnd = end
                known += slot
            }
            return known
        }
        fun rowAtMinute(minute: Int, slots: List<TimeSlot>, endEdge: Boolean = false): Float? {
            val valid = validSlots(slots) ?: return null
            val candidates = valid.filter {
                val start = CourseRules.minutes(it.startTime)!!; val end = CourseRules.minutes(it.endTime)!!
                if (endEdge) minute > start && minute <= end else minute >= start && minute < end
            }
            val slot = candidates.singleOrNull() ?: return null
            val start = CourseRules.minutes(slot.startTime)!!; val end = CourseRules.minutes(slot.endTime)!!
            return slot.section - 1 + (minute - start).toFloat() / (end - start)
        }
        /** No default bells: every selected day must explicitly agree on one noon boundary. */
        fun commonLunchBoundary(daySlots: List<List<TimeSlot>>): Int? {
            if (daySlots.isEmpty()) return null
            val boundaries = daySlots.map { slots ->
                val valid = validSlots(slots) ?: return null
                valid.zipWithNext().filter { (a,b) ->
                    val end = CourseRules.minutes(a.endTime)!!; val start = CourseRules.minutes(b.startTime)!!
                    b.section == a.section + 1 && end in 660..780 && start in 720..900 && start - end >= 40
                }.singleOrNull()?.first?.section ?: return null
            }
            return boundaries.distinct().singleOrNull()
        }

        /** Every selected day must agree on one afternoon-to-evening break boundary. */
        fun commonEveningBoundary(daySlots: List<List<TimeSlot>>): Int? {
            if (daySlots.isEmpty()) return null
            val boundaries = daySlots.map { slots ->
                val valid = validSlots(slots) ?: return null
                valid.zipWithNext().filter { (a, b) ->
                    val end = CourseRules.minutes(a.endTime)!!
                    val start = CourseRules.minutes(b.startTime)!!
                    b.section == a.section + 1 && end in 1020..1140 && start in 1080..1230 && start - end >= 30
                }.singleOrNull()?.first?.section ?: return null
            }
            return boundaries.distinct().singleOrNull()
        }
    }
}

object ScheduleSectionPolicy {
    /** Selected/fixed display days carry actual source metadata; owner identity does not set their bounds. */
    fun forDays(rootSource: String, days: List<com.gfs.iyango.core.domain.EffectiveScheduleDay>,
        maxCourse: Int, fallbackSlots: List<TimeSlot>, limit: Int): Int {
        val displayed = days.filter { it.timetable != null }
        val source = if (displayed.isEmpty()) rootSource else if (displayed.all { it.displaySource == "YGU" }) "YGU" else "LOCAL"
        val slots = if(displayed.isEmpty()) fallbackSlots else displayed.flatMap { it.displayTimeSlots }
        return count(source,maxCourse,slots.maxOfOrNull { it.section } ?: 0,limit)
    }
    fun count(source: String, maxCourse: Int, maxSlot: Int, limit: Int): Int =
        if (source == "YGU") 11 else maxOf(11,maxCourse,maxSlot).coerceAtMost(limit)
}

/** An explicit effective day never borrows the root table's bells, even when its rail is empty. */
fun WeekScheduleData.slotsForDay(day: Int): List<TimeSlot> =
    effectiveDays.firstOrNull { it.date == weekStart.plusDays(day - 1L) }?.displayTimeSlots ?: timeSlots
fun WeekScheduleData.lunchBoundary(): Int? = ScheduleAxisGeometry.commonLunchBoundary(
    (1..7).map { slotsForDay(it) })
fun WeekScheduleData.eveningBoundary(): Int? = ScheduleAxisGeometry.commonEveningBoundary(
    (1..7).map { slotsForDay(it) })

/** One rail cannot claim a shared clock when the effective days disagree. */
fun WeekScheduleData.displayRail(visibleDays: Int): List<com.gfs.iyango.core.domain.TimeRailRow> {
    fun key(slots: List<TimeSlot>) = slots.sortedBy { it.section }.map { Triple(it.section,it.startTime,it.endTime) }
    val rails = (1..visibleDays).map { slotsForDay(it) }
    val same = rails.map(::key).distinct().size == 1
    if (same && key(rails.first()) == key(timeSlots)) return timeRail
    val selected = rails.first().associateBy { it.section }
    return (1..sectionCount).map { section ->
        val slot = selected[section]
        com.gfs.iyango.core.domain.TimeRailRow(section,section.toString(),
            start=if(same) slot?.startTime.orEmpty() else "按日",
            end=if(same) slot?.endTime.orEmpty() else "")
    }
}
