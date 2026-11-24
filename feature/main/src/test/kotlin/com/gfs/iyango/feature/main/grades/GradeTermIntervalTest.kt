package com.gfs.iyango.feature.main.grades

import com.gfs.iyango.core.model.GradeTerm
import org.junit.Test
import org.junit.Assert.*

class GradeTermIntervalTest {
    @Test fun `interval includes endpoints and all intervening semesters across years`() {
        val interval = GradeTermInterval.between("2024-2025-1", "2025-2026-1")!!
        assertEquals("range:2024-2025-1..2025-2026-1", interval.value)
        listOf("2024-2025-1", "2024-2025-2", "2025-2026-1").forEach { assertTrue(interval.contains(it)) }
        listOf("2023-2024-2", "2025-2026-2", "", "unknown").forEach { assertFalse(interval.contains(it)) }
        assertEquals(interval.value, GradeTermInterval.parse(interval.value)?.value)
    }
    @Test fun `single endpoint range uses the existing single semester representation`() {
        assertEquals("2024-2025-2", GradeTermInterval.between("2024-2025-2", "2024-2025-2")?.value)
    }
    @Test fun `invalid and reversed intervals are rejected`() {
        assertNull(GradeTermInterval.between("2025-2026-1", "2024-2025-1"))
        listOf("range:", "range:a..b", "range:2024-2026-1..2025-2026-1",
            "range:2024-2025-0..2025-2026-1", "range:2024-2025-1..2025-2026-1..2026-2027-1")
            .forEach { assertNull(it, GradeTermInterval.parse(it)) }
    }
    @Test fun `term ordering is chronological and never fabricates missing school terms`() {
        val ordered = GradeTermInterval.ordered(listOf("2025-2026-1", "2024-2025-10", "2024-2025-2", "unknown", "2024-2025-2")
            .map { GradeTerm(it, it) })
        assertEquals(listOf("2024-2025-2", "2024-2025-10", "2025-2026-1"), ordered.map { it.value })
        assertEquals("2024-2025-2 至 2025-2026-1", termLabel(ordered, "range:2024-2025-2..2025-2026-1"))
    }
}
