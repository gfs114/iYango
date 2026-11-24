package com.gfs.iyango.data.json

import com.gfs.iyango.core.model.*
import com.gfs.iyango.data.repository.DataStoreAppSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DisplayPreferencesTest {
    @Test fun `legacy and unknown enum retain defaults`() {
        val old = decodeDisplayConfig("{}")!!
        assertTrue(old.showOffWeek)
        assertFalse(old.showExams)
        assertFalse(old.showLunchBreak)
        assertEquals(ScheduleHeaderOrder.WEEKDAY_DATE, old.headerOrder)
        assertEquals(old, decodeDisplayConfig("""{"headerOrder":"FUTURE"}"""))
        val dto = Json.decodeFromString(DataStoreAppSettings.DisplaySettingsDto.serializer(), """{"headerOrder":"FUTURE"}""")
        assertEquals(old, dto.toModel())
    }
    @Test fun `exchange and datastore preserve new preferences independently`() {
        val configured = ScheduleDisplaySettings(showOffWeek = false,
            headerOrder = ScheduleHeaderOrder.DATE_WEEKDAY, showExams = true, showLunchBreak = true)
        assertEquals(configured, decodeDisplayConfig(encodeDisplayConfig(configured)))
        val dto = DataStoreAppSettings.DisplaySettingsDto(showOffWeek = false,
            headerOrder = "DATE_WEEKDAY", showExams = true, showLunchBreak = true)
        val restored = Json.decodeFromString(DataStoreAppSettings.DisplaySettingsDto.serializer(),
            Json.encodeToString(DataStoreAppSettings.DisplaySettingsDto.serializer(), dto)).toModel()
        assertEquals(configured, restored)
        val bundle = DisplaySettingsBundle(byTimetable = mapOf(1L to configured))
        assertEquals(configured, bundle.forTimetable(1))
        assertEquals(ScheduleDisplaySettings(), bundle.forTimetable(2))
        assertEquals(configured, bundle.copy(global = configured.copy(showOffWeek = true)).forTimetable(1))
    }
}
