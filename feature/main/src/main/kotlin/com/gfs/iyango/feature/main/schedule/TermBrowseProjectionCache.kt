package com.gfs.iyango.feature.main.schedule

import com.gfs.iyango.core.domain.CourseRules
import com.gfs.iyango.core.domain.TimeRailRow
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.SchoolTimetableData
import com.gfs.iyango.core.model.TimeSlot
import com.gfs.iyango.core.model.Timetable
import java.time.LocalDateTime

/** Bounded in-memory geometry cache; transport timestamps and raw HTML are not geometry inputs. */
internal class TermBrowseProjectionCache {
    private data class Key(val account: String, val source: SchoolTimetableData)
    private val entries = object : LinkedHashMap<Key, BrowsedSemesterProjection>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, BrowsedSemesterProjection>?) = size > 3
    }

    @Synchronized
    fun get(
        account: String,
        source: SchoolTimetableData,
        now: LocalDateTime,
        rails: (Timetable, List<Course>, List<TimeSlot>, Int) -> List<TimeRailRow>,
    ): BrowsedSemesterProjection {
        val key = Key(account, source.copy(fetchedAt = 0, rawPayload = "", warnings = emptyList()))
        return entries.getOrPut(key) {
            BrowsedSemesterProjection(projectBrowsedTerm(key.source, 1, now, 0, rails).weeks)
        }
    }
}

/** Geometry stays shared across week/status changes; only context and clock metadata are rebound. */
internal class BrowsedSemesterProjection(private val baseWeeks: List<WeekScheduleData>) {
    private var contextVersion = Long.MIN_VALUE
    private var renderedNow: LocalDateTime? = null
    private var renderedWeeks = baseWeeks

    fun render(week: Int, revision: Long, navigationVersion: Long, now: LocalDateTime): ScheduleUiState.Ready {
        if (contextVersion != revision || renderedNow != now) {
            renderedWeeks = baseWeeks.map { data ->
                data.copy(
                    contextVersion = revision,
                    now = now,
                    today = now.toLocalDate(),
                    currentWeek = if (data.datesKnown) CourseRules.weekOfDate(data.semester, now.toLocalDate()) else 0,
                )
            }
            contextVersion = revision
            renderedNow = now
        }
        val selected = week.takeIf { it > 0 }
            ?: renderedWeeks.first().currentWeek.takeIf { it > 0 }
            ?: 1
        val data = renderedWeeks[(selected - 1).coerceIn(renderedWeeks.indices)]
        return ScheduleUiState.Ready(data.copy(navigationVersion = navigationVersion), renderedWeeks)
    }
}
