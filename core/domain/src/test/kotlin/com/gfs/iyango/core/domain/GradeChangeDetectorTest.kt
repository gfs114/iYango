package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.GradeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GradeChangeDetectorTest {

    private fun rec(
        name: String,
        code: String,
        semester: String,
        score: String,
        credit: Double = 2.0,
        gpa: Double? = null,
        attribute: String = "必修",
        schoolRecordId: String = "",
    ) = GradeRecord(
        accountKey = "acc1",
        semester = semester,
        courseName = name,
        courseCode = code,
        credit = credit,
        scoreText = score,
        gradePoint = gpa,
        attribute = attribute,
        schoolRecordId = schoolRecordId,
    )

    @Test
    fun `returns null when baseline does not exist`() {
        val result = GradeChangeDetector.detect(
            accountKey = "acc1",
            partition = "*",
            hadBaseline = false,
            previousRecords = emptyList(),
            newRecords = listOf(rec("高数", "M1", "2026-2027-1", "90", 4.0, 4.0)),
            alreadyNotifiedKeys = emptySet(),
        )
        assertNull(result)
    }

    @Test
    fun `detects new grades in star partition resolves real semester computes GPA delta and excludes electives`() {
        val prev = listOf(
            rec("线代", "M2", "*", "80", 2.0, 3.0, schoolRecordId = "ygu:2026-2027-1|M2"),
        )
        val next = prev + listOf(
            rec("高数", "M1", "*", "90", 2.0, 4.0, schoolRecordId = "ygu:2026-2027-1|M1"),
            rec("音乐欣赏", "ART1", "*", "95", 1.0, 4.5, attribute = "公共选修", schoolRecordId = "ygu:2026-2027-1|ART1"),
            rec("体育", "PE1", "*", "", 1.0, null, schoolRecordId = "ygu:2026-2027-1|PE1"),
        )

        val payload = GradeChangeDetector.detect(
            accountKey = "acc1",
            partition = "*",
            hadBaseline = true,
            previousRecords = prev,
            newRecords = next,
            alreadyNotifiedKeys = emptySet(),
        )
        assertNotNull(payload)
        payload!!
        assertEquals("新增 2 门成绩", payload.summaryTitle)
        assertEquals("2026-2027-1", payload.gpaSummary?.semester)
        assertEquals(3.0, payload.gpaSummary!!.beforeTermGpa!!, 0.001)
        assertEquals(3.5, payload.gpaSummary.afterTermGpa!!, 0.001)
        assertEquals(0.5, payload.gpaSummary.deltaTermGpa!!, 0.001)
        assertTrue(payload.expandedLines.any { it.contains("3.50 (+0.50)") })
        assertFalse(payload.summaryTitle.contains("·") || payload.summaryTitle.contains("•"))
        payload.expandedLines.forEach { line ->
            assertFalse(line.contains("·"))
            assertFalse(line.contains("•"))
        }

        // Repeating detection with notified dedupeKeys returns null
        val second = GradeChangeDetector.detect(
            accountKey = "acc1",
            partition = "*",
            hadBaseline = true,
            previousRecords = prev,
            newRecords = next,
            alreadyNotifiedKeys = payload.dedupeKeys,
        )
        assertNull(second)
    }

    @Test
    fun `detects modified score cleanly`() {
        val prev = listOf(rec("大学物理", "P1", "2026-2027-1", "80", 3.0, 3.0))
        val next = listOf(rec("大学物理", "P1", "2026-2027-1", "90", 3.0, 4.0))
        val payload = GradeChangeDetector.detect(
            accountKey = "acc1",
            partition = "2026-2027-1",
            hadBaseline = true,
            previousRecords = prev,
            newRecords = next,
            alreadyNotifiedKeys = emptySet(),
        )
        assertNotNull(payload)
        payload!!
        assertEquals("成绩变动通知", payload.summaryTitle)
        assertEquals("大学物理 80 → 90", payload.expandedLines.first())
    }
}
