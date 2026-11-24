package com.gfs.iyango.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsNewFieldsSanitizeTest {

    @Test
    fun `pinned classroom buildings are trimmed deduplicated and capped at 8`() {
        val raw = (1..10).map { idx ->
            PinnedClassroomBuilding(
                campusCode = " C1 ",
                campusName = " 本部 ",
                buildingCode = " B$idx ",
                buildingName = " $idx 号楼 ",
            )
        } + listOf(
            PinnedClassroomBuilding(campusCode = "C1", campusName = "本部", buildingCode = "B1", buildingName = "重复"),
            PinnedClassroomBuilding(campusCode = "", campusName = "无", buildingCode = "B99", buildingName = "无效"),
            PinnedClassroomBuilding(campusCode = "C1", campusName = "本部", buildingCode = "", buildingName = "无效"),
        )
        val sanitized = AppPreferences(pinnedClassroomBuildings = raw).sanitized().pinnedClassroomBuildings
        assertEquals(8, sanitized.size)
        assertEquals("C1", sanitized.first().campusCode)
        assertEquals("B1", sanitized.first().buildingCode)
        assertEquals("B8", sanitized.last().buildingCode)
    }

    @Test
    fun `grade check interval accepts supported values and falls back otherwise`() {
        assertEquals(2, ReminderSettings(gradeCheckIntervalHours = 2).sanitized().gradeCheckIntervalHours)
        assertEquals(4, ReminderSettings(gradeCheckIntervalHours = 4).sanitized().gradeCheckIntervalHours)
        assertEquals(6, ReminderSettings(gradeCheckIntervalHours = 6).sanitized().gradeCheckIntervalHours)
        assertEquals(12, ReminderSettings(gradeCheckIntervalHours = 12).sanitized().gradeCheckIntervalHours)
        assertEquals(4, ReminderSettings(gradeCheckIntervalHours = 1).sanitized().gradeCheckIntervalHours)
        assertEquals(4, ReminderSettings(gradeCheckIntervalHours = 99).sanitized().gradeCheckIntervalHours)
    }
}
