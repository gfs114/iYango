package com.gfs.iyango.feature.main.schedule

import com.gfs.iyango.core.domain.EffectiveScheduleDay
import com.gfs.iyango.core.domain.OccurrenceAdjustmentExpected
import com.gfs.iyango.core.domain.adjustmentExpected
import com.gfs.iyango.core.model.OccurrenceAdjustment
import com.gfs.iyango.core.model.Semester
import java.time.LocalDate

/** Identifies a retained page session; a root switch back also receives a new version. */
data class ScheduleContext(val timetableId: Long, val semester: Semester?, val version: Long)
val WeekScheduleData.context: ScheduleContext get() = ScheduleContext(timetableId, semester, contextVersion)

data class ScheduleDropSlot(val dayOfWeek: Int, val startSection: Int, val endSection: Int)

/** Coordinates are local to the actual scrolling lattice, including its rail but no header. */
fun scheduleDropAt(x: Float, y: Float, railWidth: Float, dayWidth: Float,
    visibleDays: Int, axis: ScheduleAxisGeometry, span: Int): ScheduleDropSlot? {
    if (!x.isFinite() || !y.isFinite() || dayWidth <= 0 || span < 1 ||
        x < railWidth || x >= railWidth + visibleDays * dayWidth) return null
    val start = axis.sectionAtY(y) ?: return null
    val end = start + span - 1
    if (end > axis.sectionCount) return null
    return ScheduleDropSlot(((x - railWidth) / dayWidth).toInt() + 1, start, end)
}

/** Leave at least 80dp per visible day plus the rail in the edge-to-edge grid. */
fun showWeekSidebar(availableWidth: Float, visibleDays: Int): Boolean =
    availableWidth >= 152f + ScheduleGridMath.TIME_RAIL_WIDTH_DP + visibleDays * 80f

fun captureDragExpected(block: WeekScheduleData.GridBlock, days: List<EffectiveScheduleDay>): OccurrenceAdjustmentExpected? {
    val occurrence = block.occurrence ?: return null
    if (block.isOffWeek || occurrence.isTemporary || days.any { it.date == occurrence.date && it.isTemporaryDay }) return null
    return days.adjustmentExpected(block.course, occurrence.anchorDate.toString())
        ?.takeIf { it.occurrence == occurrence }
}

/** Clock-bearing imports are section-draggable only when their actual source bells prove the span. */
fun dragSectionSpan(block: WeekScheduleData.GridBlock, days: List<EffectiveScheduleDay>): Int? {
    val occurrence = block.occurrence ?: return null
    if (captureDragExpected(block, days) == null) return null
    val course = occurrence.course
    val span = course.endSection - course.startSection + 1
    if (course.startSection < 1 || span < 1) return null
    if (course.timeMode == 0 && course.startTime.isBlank() && course.endTime.isBlank()) return span
    val slots = days.firstOrNull { it.date == occurrence.date }?.displayTimeSlots ?: return null
    val start = occurrence.startMinute ?: return null
    val end = occurrence.endMinute ?: return null
    if (ScheduleAxisGeometry.rowAtMinute(start, slots) != course.startSection - 1f ||
        ScheduleAxisGeometry.rowAtMinute(end, slots, true) != course.endSection.toFloat()) return null
    return span
}

data class ScheduleDragSource(val context: ScheduleContext, val expected: OccurrenceAdjustmentExpected,
    val span: Int) {
    val course get() = expected.occurrence.course
}
data class ScheduleDropCandidate(val source: ScheduleDragSource, val slot: ScheduleDropSlot,
    val targetDay: EffectiveScheduleDay, val displayedWeek: Int)

/** Only an anchor with a source or target date actually owned on screen is restored. */
fun adjustmentsToRestore(adjustments: List<OccurrenceAdjustment>, displayedDays: List<EffectiveScheduleDay>): List<OccurrenceAdjustment> {
    val owners = displayedDays.associate { it.date.toString() to it.timetable?.id }
    return adjustments.filter { owners[it.sourceDate] == it.timetableId || owners[it.targetDate] == it.timetableId }
        .distinctBy { Triple(it.timetableId, it.sourceCourseId, it.sourceDate) }
}
