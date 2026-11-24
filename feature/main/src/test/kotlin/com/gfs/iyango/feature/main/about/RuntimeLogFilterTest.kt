package com.gfs.iyango.feature.main.about

import com.gfs.iyango.core.model.DiagnosticEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeLogFilterTest {

    private val sampleEvents = listOf(
        DiagnosticEvent(timestamp = 1_700_000_000_000L, category = "TIMETABLE", durationMs = 240, outcome = "ok", message = "synced 12 courses"),
        DiagnosticEvent(timestamp = 1_700_000_010_000L, category = "GRADE", durationMs = 180, outcome = "error", errorType = "SessionExpired", message = "cookie expired"),
        DiagnosticEvent(timestamp = 1_700_000_020_000L, category = "CALENDAR", durationMs = 95, outcome = "ok", message = "reconciled events"),
    )

    @Test fun `filterDiagnosticEvents filters by category and keyword case-insensitively`() {
        assertEquals(3, filterDiagnosticEvents(sampleEvents, null, "").size)
        assertEquals(listOf(sampleEvents[1]), filterDiagnosticEvents(sampleEvents, "GRADE", ""))
        assertEquals(listOf(sampleEvents[1]), filterDiagnosticEvents(sampleEvents, "grade", "EXPIRED"))
        assertEquals(listOf(sampleEvents[0]), filterDiagnosticEvents(sampleEvents, null, "12 courses"))
        assertTrue(filterDiagnosticEvents(sampleEvents, "CALENDAR", "expired").isEmpty())
    }

    @Test fun `formatDiagnosticLogLines emits readable single-line summaries`() {
        val text = formatDiagnosticLogLines(sampleEvents.take(2))
        val lines = text.lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("TIMETABLE outcome=ok 240ms | synced 12 courses"))
        assertTrue(lines[1].contains("GRADE outcome=error 180ms error=SessionExpired | cookie expired"))
    }
}
