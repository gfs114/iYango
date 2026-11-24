package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.Course
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildingTransferDetectorTest {

    private val day = LocalDate.of(2026, 11, 2)

    private fun occ(
        id: Long,
        name: String,
        classroom: String,
        startMinute: Int?,
        endMinute: Int?,
        date: LocalDate = day,
        enabled: Boolean = true,
    ): CourseOccurrence {
        val course = Course(
            id = id,
            timetableId = 1,
            name = name,
            classroom = classroom,
            enabled = enabled,
        )
        return CourseOccurrence(
            occurrenceId = CourseOccurrence.stableId(course, date),
            course = course,
            date = date,
            startMinute = startMinute,
            endMinute = endMinute,
            isOverride = false,
        )
    }

    @Test
    fun `extractBuildingForTransfer handles chinese buildings, letter zones, and pure digits`() {
        assertEquals("明理楼", BuildingTransferDetector.extractBuildingForTransfer("@明理楼 A101"))
        assertEquals("信息楼", BuildingTransferDetector.extractBuildingForTransfer("信息楼B301"))
        assertEquals("A", BuildingTransferDetector.extractBuildingForTransfer("A101"))
        assertEquals("A", BuildingTransferDetector.extractBuildingForTransfer("A202"))
        assertEquals("C3", BuildingTransferDetector.extractBuildingForTransfer("C3-102"))
        assertEquals("", BuildingTransferDetector.extractBuildingForTransfer("301"))
        assertEquals("", BuildingTransferDetector.extractBuildingForTransfer("204A"))
        assertEquals("", BuildingTransferDetector.extractBuildingForTransfer("  "))
    }

    @Test
    fun `detectForDay warns when adjacent classes are in different buildings within 20 minutes`() {
        val first = occ(1, "高等数学", "明理楼 A101", 8 * 60, 9 * 60 + 40)
        val second = occ(2, "大学物理", "信息楼 B301", 10 * 60, 11 * 60 + 40)
        val third = occ(3, "数据结构", "实验楼 202", 14 * 60, 15 * 60 + 40)

        val warnings = BuildingTransferDetector.detectForDay(listOf(third, second, first))
        assertEquals(1, warnings.size)
        val warning = warnings[second.occurrenceId]!!
        assertEquals("明理楼", warning.fromBuilding)
        assertEquals("信息楼", warning.toBuilding)
        assertEquals(20, warning.gapMinutes)
        assertEquals("换楼：明理楼 → 信息楼", warning.label)
        assertEquals("换楼：明理楼 → 信息楼（间隔20分钟）", warning.detailLabel)
        assertNull(warnings[third.occurrenceId])
    }

    @Test
    fun `detectForDay ignores same building, pure numeric rooms, gaps over 20m, and overlaps`() {
        val sameA1 = occ(1, "高数", "A101", 8 * 60, 9 * 60 + 40)
        val sameA2 = occ(2, "线代", "A202", 10 * 60, 11 * 60 + 40)
        val gapOver = occ(3, "英语", "B202", 12 * 60 + 5, 13 * 60 + 40)
        val numeric1 = occ(4, "体育", "301", 14 * 60, 15 * 60 + 40)
        val numeric2 = occ(5, "思政", "信息楼 101", 15 * 60 + 50, 17 * 60)
        val overlap = occ(6, "冲突", "实验楼 501", 16 * 60 + 30, 18 * 60)

        val warnings = BuildingTransferDetector.detectForDay(
            listOf(sameA1, sameA2, gapOver, numeric1, numeric2, overlap),
        )
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `detectForTiming resolves warning both in class and before next class`() {
        val first = occ(1, "高等数学", "明理楼 A101", 8 * 60, 9 * 60 + 40)
        val second = occ(2, "大学物理", "信息楼 B301", 9 * 60 + 40, 11 * 60 + 20)
        val all = listOf(first, second)

        val inFirst = TodayCourseTiming.project(all, day.atTime(9, 0))
        val warningInFirst = BuildingTransferDetector.detectForTiming(all, inFirst)!!
        assertEquals("换楼：明理楼 → 信息楼（无缝衔接）", warningInFirst.detailLabel)

        val beforeFirst = TodayCourseTiming.project(all, day.atTime(7, 30))
        assertNull(BuildingTransferDetector.detectForTiming(all, beforeFirst))

        val between = TodayCourseTiming.project(
            listOf(
                occ(1, "高数", "明理楼 A101", 8 * 60, 9 * 60 + 40),
                occ(2, "物理", "信息楼 B301", 9 * 60 + 55, 11 * 60 + 35),
            ),
            day.atTime(9, 45),
        )
        val warningBetween = BuildingTransferDetector.detectForTiming(
            listOf(
                occ(1, "高数", "明理楼 A101", 8 * 60, 9 * 60 + 40),
                occ(2, "物理", "信息楼 B301", 9 * 60 + 55, 11 * 60 + 35),
            ),
            between,
        )!!
        assertEquals("换楼：明理楼 → 信息楼（间隔15分钟）", warningBetween.detailLabel)
    }
}
