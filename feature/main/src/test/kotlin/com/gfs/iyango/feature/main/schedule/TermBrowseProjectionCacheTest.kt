package com.gfs.iyango.feature.main.schedule

import com.gfs.iyango.core.model.SchoolCourseData
import com.gfs.iyango.core.model.SchoolTimetableData
import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class TermBrowseProjectionCacheTest {
    private val now = LocalDateTime.of(2026, 11, 1, 8, 0)
    private val source = SchoolTimetableData(
        termValue = "term", totalWeeks = 20, startDate = "2026-10-05",
        courses = listOf(SchoolCourseData(name = "Course", endWeek = 20)),
    )

    @Test fun `navigation and transport changes reuse geometry but context and clock stay fresh`() {
        val cache = TermBrowseProjectionCache()
        var rails = 0
        val first = cache.get("account", source, now) { _, _, _, _ -> rails++; emptyList() }
        val ready = first.render(1, 7, 1, now)
        val week = first.render(4, 7, 2, now)
        assertSame(ready.weeks, week.weeks)
        assertEquals(4, week.data.displayedWeek)
        assertEquals(2, week.data.navigationVersion)
        val refreshed = cache.get("account", source.copy(fetchedAt = 100, rawPayload = "new HTML"), now) { _, _, _, _ -> rails++; emptyList() }
        assertSame(first, refreshed)
        assertEquals(20, rails)
        val nextDay = first.render(0, 8, 3, now.plusDays(1))
        assertEquals(now.toLocalDate().plusDays(1), nextDay.data.today)
        assertEquals(5, nextDay.data.currentWeek)
        assertEquals(8, nextDay.data.contextVersion)
        assertSame(ready.weeks.first().cells, nextDay.weeks.first().cells)
        assertNotEquals(ready.data.context, nextDay.data.context)
    }

    @Test fun `account real payload and known date changes each invalidate geometry`() {
        val cache = TermBrowseProjectionCache()
        var rails = 0
        fun get(account: String, data: SchoolTimetableData) =
            cache.get(account, data, now) { _, _, _, _ -> rails++; emptyList() }
        val first = get("one", source)
        assertNotSame(first, get("two", source))
        assertNotSame(first, get("one", source.copy(startDate = "2026-10-12")))
        assertNotSame(first, get("one", source.copy(courses = listOf(SchoolCourseData(name = "Changed", endWeek = 20)))))
        assertEquals(80, rails)
        // Four distinct entries exceed the three-term memory bound.
        assertNotSame(first, get("one", source))
        assertEquals(100, rails)
    }
}
