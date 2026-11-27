package com.gfs.iyango.core.domain

import java.time.LocalDateTime
import kotlin.math.abs

/** 双人对照下某一方当日的实时上课状态。 */
sealed interface PartnerSlotStatus {
    data class InClass(val occurrence: CourseOccurrence, val minutesRemaining: Int) : PartnerSlotStatus
    data class Upcoming(val occurrence: CourseOccurrence, val minutesUntil: Int) : PartnerSlotStatus
    data object FinishedToday : PartnerSlotStatus
    data object FreeToday : PartnerSlotStatus
}

/** 日内分钟区间（0..1440），用于展示共同空闲时段。 */
data class TimeRangeMinutes(
    val startMinute: Int,
    val endMinute: Int,
) {
    fun format(): String = "${PartnerScheduleCalculator.formatMinute(startMinute)}-${PartnerScheduleCalculator.formatMinute(endMinute)}"
}

/** 今日页双人课表实时对照汇总。 */
data class PartnerTodaySummary(
    val partnerTimetableId: Long,
    val partnerName: String,
    val myStatus: PartnerSlotStatus,
    val partnerStatus: PartnerSlotStatus,
    val myRemainingCount: Int,
    val partnerRemainingCount: Int,
    val syncEndMinute: Int?,
    val nextCommonFree: TimeRangeMinutes?,
)

/**
 * 双人课表实时对照纯领域计算器。
 */
object PartnerScheduleCalculator {

    private const val DAY_WINDOW_START_MINUTE = 8 * 60
    private const val DAY_WINDOW_END_MINUTE = 22 * 60
    private const val MIN_COMMON_FREE_MINUTES = 30
    private const val SYNC_FINISH_TOLERANCE_MINUTES = 15

    fun projectToday(
        myOccurrences: List<CourseOccurrence>,
        partnerOccurrences: List<CourseOccurrence>,
        now: LocalDateTime,
        partnerTimetableId: Long,
        partnerName: String,
    ): PartnerTodaySummary {
        val nowMinute = now.hour * 60 + now.minute
        val myTimed = timedSorted(myOccurrences)
        val partnerTimed = timedSorted(partnerOccurrences)

        return PartnerTodaySummary(
            partnerTimetableId = partnerTimetableId,
            partnerName = partnerName.trim().ifEmpty { "TA" },
            myStatus = statusOf(myTimed, nowMinute),
            partnerStatus = statusOf(partnerTimed, nowMinute),
            myRemainingCount = myTimed.count { it.endMinute!! > nowMinute },
            partnerRemainingCount = partnerTimed.count { it.endMinute!! > nowMinute },
            syncEndMinute = findNextSyncFinishMinute(myTimed, partnerTimed, nowMinute),
            nextCommonFree = findNextCommonFree(myTimed, partnerTimed, nowMinute),
        )
    }

    fun formatMinute(minuteOfDay: Int): String {
        val clamped = minuteOfDay.coerceIn(0, 24 * 60)
        val h = clamped / 60
        val m = clamped % 60
        return "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
    }

    private fun timedSorted(occurrences: List<CourseOccurrence>): List<CourseOccurrence> =
        occurrences
            .filter { it.startMinute != null && it.endMinute != null && it.endMinute > it.startMinute }
            .sortedWith(compareBy({ it.startMinute }, { it.endMinute }, { it.course.name }))

    private fun statusOf(timed: List<CourseOccurrence>, nowMinute: Int): PartnerSlotStatus {
        if (timed.isEmpty()) return PartnerSlotStatus.FreeToday
        val active = timed.firstOrNull { nowMinute >= it.startMinute!! && nowMinute < it.endMinute!! }
        if (active != null) {
            return PartnerSlotStatus.InClass(active, (active.endMinute!! - nowMinute).coerceAtLeast(1))
        }
        val upcoming = timed.firstOrNull { it.startMinute!! > nowMinute }
        if (upcoming != null) {
            return PartnerSlotStatus.Upcoming(upcoming, (upcoming.startMinute!! - nowMinute).coerceAtLeast(1))
        }
        return PartnerSlotStatus.FinishedToday
    }

    private fun findNextSyncFinishMinute(
        myTimed: List<CourseOccurrence>,
        partnerTimed: List<CourseOccurrence>,
        nowMinute: Int,
    ): Int? {
        var earliest: Int? = null
        for (mine in myTimed) {
            val myEnd = mine.endMinute!!
            if (myEnd < nowMinute) continue
            for (partner in partnerTimed) {
                val pEnd = partner.endMinute!!
                if (pEnd < nowMinute) continue
                if (abs(myEnd - pEnd) <= SYNC_FINISH_TOLERANCE_MINUTES) {
                    val syncedEnd = maxOf(myEnd, pEnd)
                    if (earliest == null || syncedEnd < earliest) {
                        earliest = syncedEnd
                    }
                }
            }
        }
        return earliest
    }

    private fun findNextCommonFree(
        myTimed: List<CourseOccurrence>,
        partnerTimed: List<CourseOccurrence>,
        nowMinute: Int,
    ): TimeRangeMinutes? {
        val windowStart = maxOf(nowMinute, DAY_WINDOW_START_MINUTE)
        if (windowStart + MIN_COMMON_FREE_MINUTES > DAY_WINDOW_END_MINUTE) return null

        val busy = (myTimed + partnerTimed)
            .mapNotNull { occ ->
                val s = occ.startMinute!!.coerceIn(windowStart, DAY_WINDOW_END_MINUTE)
                val e = occ.endMinute!!.coerceIn(windowStart, DAY_WINDOW_END_MINUTE)
                if (e > s) s to e else null
            }
            .sortedWith(compareBy({ it.first }, { it.second }))

        if (busy.isEmpty()) {
            return TimeRangeMinutes(windowStart, DAY_WINDOW_END_MINUTE)
        }

        val merged = mutableListOf<Pair<Int, Int>>()
        for (interval in busy) {
            val last = merged.lastOrNull()
            if (last == null || interval.first > last.second) {
                merged += interval
            } else {
                merged[merged.lastIndex] = last.first to maxOf(last.second, interval.second)
            }
        }

        var cursor = windowStart
        for ((busyStart, busyEnd) in merged) {
            if (busyStart - cursor >= MIN_COMMON_FREE_MINUTES) {
                return TimeRangeMinutes(cursor, busyStart)
            }
            cursor = maxOf(cursor, busyEnd)
        }
        if (DAY_WINDOW_END_MINUTE - cursor >= MIN_COMMON_FREE_MINUTES) {
            return TimeRangeMinutes(cursor, DAY_WINDOW_END_MINUTE)
        }
        return null
    }
}
