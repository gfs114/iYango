package com.gfs.iyango.core.domain

/**
 * 同一天相邻课程的跨教学楼赶课预警。
 */
data class BuildingTransferWarning(
    val fromBuilding: String,
    val toBuilding: String,
    val gapMinutes: Int,
) {
    val label: String get() = "换楼：$fromBuilding → $toBuilding"

    val detailLabel: String
        get() = if (gapMinutes <= 0) "$label（无缝衔接）" else "$label（间隔${gapMinutes}分钟）"
}

/**
 * 跨教学楼赶课预警检测器：复用 [SemesterSummaryCalculator.extractBuilding]，
 * 对同一天课间间隔不超过 [MAX_GAP_MINUTES] 分钟且教学楼不同的相邻课程生成换楼提示。
 */
object BuildingTransferDetector {

    const val MAX_GAP_MINUTES = 20

    private val PURE_NUMERIC_ROOM_REGEX = Regex("^\\d+[A-Za-z]?$")
    private val ZONE_SEPARATED_ROOM_REGEX = Regex("^([A-Za-z]\\d?)[-\\s]+\\d{2,4}(?:\\D.*)?$")
    private val ZONE_COMPACT_ROOM_REGEX = Regex("^([A-Za-z])\\d{2,4}(?:\\D.*)?$")

    fun extractBuildingForTransfer(classroom: String): String {
        val cleaned = classroom.trim().removePrefix("@").trim()
        if (cleaned.isEmpty()) return ""
        if (PURE_NUMERIC_ROOM_REGEX.matches(cleaned)) return ""
        ZONE_SEPARATED_ROOM_REGEX.matchEntire(cleaned)?.let { match ->
            return match.groupValues[1].uppercase()
        }
        ZONE_COMPACT_ROOM_REGEX.matchEntire(cleaned)?.let { match ->
            return match.groupValues[1].uppercase()
        }
        return SemesterSummaryCalculator.extractBuilding(cleaned)
    }

    fun detectBetween(
        previous: CourseOccurrence,
        next: CourseOccurrence,
        maxGapMinutes: Int = MAX_GAP_MINUTES,
    ): BuildingTransferWarning? {
        if (previous.date != next.date) return null
        if (!previous.course.enabled || !next.course.enabled) return null
        val prevEnd = previous.endMinute ?: return null
        val nextStart = next.startMinute ?: return null
        val gap = nextStart - prevEnd
        if (gap !in 0..maxGapMinutes) return null
        val from = extractBuildingForTransfer(previous.course.classroom)
        val to = extractBuildingForTransfer(next.course.classroom)
        if (from.isBlank() || to.isBlank()) return null
        if (from.equals(to, ignoreCase = true)) return null
        return BuildingTransferWarning(
            fromBuilding = from,
            toBuilding = to,
            gapMinutes = gap,
        )
    }

    fun detectForDay(
        occurrences: List<CourseOccurrence>,
        maxGapMinutes: Int = MAX_GAP_MINUTES,
    ): Map<String, BuildingTransferWarning> {
        val valid = occurrences.filter {
            it.course.enabled &&
                it.startMinute != null &&
                it.endMinute != null &&
                it.endMinute > it.startMinute
        }
        if (valid.size < 2) return emptyMap()
        val result = linkedMapOf<String, BuildingTransferWarning>()
        valid.groupBy { it.date }.values.forEach { dayOccurrences ->
            val sorted = dayOccurrences.sortedWith(
                compareBy<CourseOccurrence>(
                    { it.startMinute },
                    { it.endMinute },
                    { it.occurrenceId },
                ),
            )
            for (current in sorted) {
                val currentStart = current.startMinute ?: continue
                val prev = sorted
                    .asSequence()
                    .filter { it !== current && (it.endMinute ?: Int.MAX_VALUE) <= currentStart }
                    .maxWithOrNull(compareBy<CourseOccurrence>({ it.endMinute }, { it.occurrenceId }))
                    ?: continue
                val warning = detectBetween(prev, current, maxGapMinutes) ?: continue
                result[current.occurrenceId] = warning
            }
        }
        return result
    }

    fun detectForTiming(
        dayOccurrences: List<CourseOccurrence>,
        timing: TodayCourseTiming,
        maxGapMinutes: Int = MAX_GAP_MINUTES,
    ): BuildingTransferWarning? {
        val next = timing.next ?: return null
        val current = timing.current
        return if (current != null) {
            detectBetween(current, next, maxGapMinutes)
        } else {
            detectForDay(dayOccurrences, maxGapMinutes)[next.occurrenceId]
        }
    }
}
