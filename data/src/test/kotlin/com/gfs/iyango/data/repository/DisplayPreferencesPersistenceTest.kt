package com.gfs.iyango.data.repository

import com.gfs.iyango.core.model.*
import com.gfs.iyango.data.json.DisplayConfigJson
import com.gfs.iyango.data.json.encodeDisplayConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DisplayPreferencesPersistenceTest {
    private val configured = ScheduleDisplaySettings(showOffWeek = false,
        headerOrder = ScheduleHeaderOrder.DATE_WEEKDAY, showExams = true, showLunchBreak = true)
    @Test fun `datastore model dto round trip and scoped writes stay isolated`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val settings = DataStoreAppSettings(context)
        settings.updateDisplaySettings(DisplaySettingsBundle(global = configured))
        settings.updateTimetableDisplay(81, configured.copy(showOffWeek = true))
        val reloaded = DataStoreAppSettings(context).displaySettings.first()
        assertEquals(configured, reloaded.global)
        assertEquals(configured.copy(showOffWeek = true), reloaded.forTimetable(81))
        assertEquals(configured, reloaded.forTimetable(82))
        settings.updateTimetableDisplay(81, null)
        assertEquals(configured, settings.displaySettings.first().forTimetable(81))
    }
    @Test fun `concurrent global and single table writes never replace the other scope`() = runBlocking {
        val settings = DataStoreAppSettings(RuntimeEnvironment.getApplication())
        settings.updateDisplaySettings(DisplaySettingsBundle())
        coroutineScope {
            launch { repeat(20) { settings.updateGlobalDisplay(configured) } }
            launch { repeat(20) { settings.updateTimetableDisplay(91, configured.copy(showOffWeek = true)) } }
        }
        val reloaded = settings.displaySettings.first()
        assertEquals(configured, reloaded.global)
        assertEquals(configured.copy(showOffWeek = true), reloaded.byTimetable[91])
    }

    @Test fun `settings backup document retains display fields at both scopes`() {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val dto = json.decodeFromString(DisplayConfigJson.serializer(), encodeDisplayConfig(configured))
        val document = SettingsBackupDocument(display = DisplayDto(global = dto,
            byTimetable = mapOf("81" to dto.copy(showOffWeek = true))))
        val reloaded = json.decodeFromString(SettingsBackupDocument.serializer(),
            json.encodeToString(SettingsBackupDocument.serializer(), document))
        assertEquals(configured, reloaded.display.global.toModel())
        assertEquals(configured.copy(showOffWeek = true), reloaded.display.byTimetable.getValue("81").toModel())
    }
}
