package com.gfs.iyango.data.repository

import com.gfs.iyango.core.domain.AdjustmentRepositoryPort
import com.gfs.iyango.core.domain.AppSettingsPort
import com.gfs.iyango.core.domain.CalendarClearResult
import com.gfs.iyango.core.domain.CalendarDeleteSummary
import com.gfs.iyango.core.domain.CalendarEventPlan
import com.gfs.iyango.core.domain.CalendarPlatformPort
import com.gfs.iyango.core.domain.CalendarWriteResult
import com.gfs.iyango.core.domain.DiagnosticLogPort
import com.gfs.iyango.core.domain.DiagnosticLogSummary
import com.gfs.iyango.core.domain.EffectiveSchedulePort
import com.gfs.iyango.core.domain.ScheduleDataPort
import com.gfs.iyango.core.domain.SystemCalendar
import com.gfs.iyango.core.domain.SystemMappingPort
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.model.AppPreferences
import com.gfs.iyango.core.model.CalendarSettings
import com.gfs.iyango.core.model.DiagnosticEvent
import com.gfs.iyango.core.model.DisplaySettingsBundle
import com.gfs.iyango.core.model.ReminderSettings
import com.gfs.iyango.core.model.ScheduleDisplaySettings
import com.gfs.iyango.core.model.SystemMapping
import com.gfs.iyango.core.model.SystemMappingKind
import com.gfs.iyango.core.model.Timetable
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultCalendarCoordinatorTest {

    private val nowMillis = 1_770_000_000_000L
    private val fixedClock = Clock.fixed(Instant.ofEpochMilli(nowMillis), ZoneOffset.UTC)

    private inline fun <reified T> port(crossinline call: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            call(method.name)
        } as T

    private class FakeAppSettings(initialCalendar: CalendarSettings) : AppSettingsPort {
        override val displaySettings = MutableStateFlow(DisplaySettingsBundle())
        override val reminderSettings = MutableStateFlow(ReminderSettings())
        override val calendarSettings = MutableStateFlow(initialCalendar)
        override val appPreferences = MutableStateFlow(AppPreferences())
        override suspend fun updateDisplaySettings(bundle: DisplaySettingsBundle) {}
        override suspend fun updateTimetableDisplay(timetableId: Long, settings: ScheduleDisplaySettings?) {}
        override suspend fun updateReminderSettings(settings: ReminderSettings) {}
        override suspend fun updateCalendarSettings(settings: CalendarSettings) {
            calendarSettings.value = settings
        }
        override suspend fun updateAppPreferences(preferences: AppPreferences) {}
        override suspend fun exportNamespace(): String = ""
        override suspend fun reset() {}
    }

    private class FakeSystemMappings(initial: List<SystemMapping> = emptyList()) : SystemMappingPort {
        private val items = initial.toMutableList()
        override suspend fun all(kind: SystemMappingKind, ownerId: String): List<SystemMapping> =
            items.filter { it.kind == kind && it.ownerId == ownerId }

        override suspend fun upsert(mapping: SystemMapping): Long {
            items.removeAll {
                it.kind == mapping.kind && it.occurrenceId == mapping.occurrenceId && it.ownerId == mapping.ownerId
            }
            items.add(mapping)
            return mapping.id
        }

        override suspend fun delete(kind: SystemMappingKind, occurrenceId: String, ownerId: String) {
            items.removeAll { it.kind == kind && it.occurrenceId == occurrenceId && it.ownerId == ownerId }
        }

        override suspend fun deleteKind(kind: SystemMappingKind, ownerId: String) {
            items.removeAll { it.kind == kind && it.ownerId == ownerId }
        }
    }

    private data class FakeEvent(
        val ownerId: String,
        val startMillis: Long,
        val failDelete: Boolean = false,
    )

    private class FakeCalendarPlatform(
        var permission: Boolean = true,
        val events: MutableMap<Long, FakeEvent> = mutableMapOf(),
        var batchOverride: CalendarDeleteSummary? = null,
    ) : CalendarPlatformPort {
        var deleteAllOwnedCalls = 0
        var deleteOwnedCalls = 0
        private val systemCalendars = listOf(SystemCalendar(id = 7L, name = "本地日历", isPrimary = true))

        override fun hasPermission(): Boolean = permission
        override suspend fun calendars(): List<SystemCalendar> = if (permission) systemCalendars else emptyList()

        override suspend fun upsert(
            plan: CalendarEventPlan,
            existingEventId: Long?,
            calendarId: Long,
            ownerId: String,
        ): CalendarWriteResult = CalendarWriteResult.Failure("unused")

        override suspend fun deleteOwned(eventId: Long, ownerId: String): Boolean {
            deleteOwnedCalls++
            if (!permission) return false
            val event = events[eventId] ?: return false
            if (event.ownerId != ownerId || event.failDelete) return false
            events.remove(eventId)
            return true
        }

        override suspend fun deleteAllOwned(ownerId: String): CalendarDeleteSummary {
            deleteAllOwnedCalls++
            batchOverride?.let { return it }
            if (!permission || ownerId.isBlank()) return CalendarDeleteSummary()
            var deleted = 0
            val failed = mutableSetOf<Long>()
            val matchingIds = events.filterValues { it.ownerId == ownerId }.keys.toList()
            matchingIds.forEach { id ->
                if (events.getValue(id).failDelete) {
                    failed += id
                } else {
                    events.remove(id)
                    deleted++
                }
            }
            return CalendarDeleteSummary(deleted = deleted, failedEventIds = failed)
        }

        override suspend fun eventStartMillis(eventId: Long, ownerId: String): Long? {
            if (!permission) return null
            val event = events[eventId] ?: return null
            return if (event.ownerId == ownerId) event.startMillis else null
        }
    }

    private fun createCoordinator(
        appSettings: FakeAppSettings,
        mappings: FakeSystemMappings,
        platform: FakeCalendarPlatform,
        logs: MutableList<DiagnosticEvent> = mutableListOf(),
    ): DefaultCalendarCoordinator {
        val diagnostics = object : DiagnosticLogPort {
            override fun observeRecent(limit: Int) = flowOf(emptyList<DiagnosticEvent>())
            override val summary = flowOf(DiagnosticLogSummary())
            override suspend fun record(event: DiagnosticEvent) {
                logs += event
            }
            override suspend fun export() = error("unused")
            override suspend fun clear() {}
        }
        return DefaultCalendarCoordinator(
            appSettings = appSettings,
            effectiveSchedule = port<EffectiveSchedulePort> { error(it) },
            timetableRepository = port<TimetableRepositoryPort> { name ->
                if (name == "observeCurrent") flowOf<Timetable?>(null) else error(name)
            },
            scheduleData = port<ScheduleDataPort> { error(it) },
            adjustments = port<AdjustmentRepositoryPort> { error(it) },
            mappings = mappings,
            platform = platform,
            diagnostics = diagnostics,
            clock = fixedClock,
        )
    }

    @Test
    fun `clearCalendar deletes all owned past future and unmapped events clears mappings and disables sync`() =
        runTest {
            val settings = FakeAppSettings(
                CalendarSettings(enabled = true, calendarId = 7L, ownerId = "owner-1", lastSyncAt = 100L),
            )
            val mappings = FakeSystemMappings(
                listOf(
                    SystemMapping(kind = SystemMappingKind.CALENDAR, occurrenceId = "occ-past", externalId = "101", ownerId = "owner-1"),
                    SystemMapping(kind = SystemMappingKind.CALENDAR, occurrenceId = "occ-future", externalId = "102", ownerId = "owner-1"),
                ),
            )
            val platform = FakeCalendarPlatform(
                permission = true,
                events = mutableMapOf(
                    101L to FakeEvent(ownerId = "owner-1", startMillis = nowMillis - 3_600_000L),
                    102L to FakeEvent(ownerId = "owner-1", startMillis = nowMillis + 3_600_000L),
                    103L to FakeEvent(ownerId = "owner-1", startMillis = nowMillis - 86_400_000L),
                    201L to FakeEvent(ownerId = "owner-2", startMillis = nowMillis + 3_600_000L),
                    202L to FakeEvent(ownerId = "", startMillis = nowMillis + 3_600_000L),
                ),
            )
            val coordinator = createCoordinator(settings, mappings, platform)

            val result = coordinator.clearCalendar()

            assertEquals(CalendarClearResult.Completed(deletedCount = 3, pendingCount = 0), result)
            assertEquals(setOf(201L, 202L), platform.events.keys)
            assertTrue(mappings.all(SystemMappingKind.CALENDAR, "owner-1").isEmpty())
            assertFalse(settings.calendarSettings.value.enabled)
            val status = coordinator.status.value
            assertFalse(status.enabled)
            assertTrue(status.permissionGranted)
            assertEquals(0, status.syncedCount)
            assertEquals(0, status.pendingCleanup)
            assertEquals("", status.lastError)
        }

    @Test
    fun `clearCalendar returns PermissionDenied and keeps mappings when permission missing`() = runTest {
        val settings = FakeAppSettings(
            CalendarSettings(enabled = true, calendarId = 7L, ownerId = "owner-1"),
        )
        val mappings = FakeSystemMappings(
            listOf(
                SystemMapping(kind = SystemMappingKind.CALENDAR, occurrenceId = "occ-1", externalId = "101", ownerId = "owner-1"),
            ),
        )
        val platform = FakeCalendarPlatform(
            permission = false,
            events = mutableMapOf(101L to FakeEvent(ownerId = "owner-1", startMillis = nowMillis + 60_000L)),
        )
        val coordinator = createCoordinator(settings, mappings, platform)

        val result = coordinator.clearCalendar()

        assertEquals(CalendarClearResult.PermissionDenied, result)
        assertEquals(0, platform.deleteAllOwnedCalls)
        assertEquals(0, platform.deleteOwnedCalls)
        assertEquals(1, mappings.all(SystemMappingKind.CALENDAR, "owner-1").size)
        assertFalse(coordinator.status.value.permissionGranted)
        assertEquals(1, coordinator.status.value.pendingCleanup)
    }

    @Test
    fun `clearCalendar avoids double counting failed batch ids and retains their mappings`() = runTest {
        val settings = FakeAppSettings(
            CalendarSettings(enabled = true, calendarId = 7L, ownerId = "owner-1"),
        )
        val mappings = FakeSystemMappings(
            listOf(
                SystemMapping(kind = SystemMappingKind.CALENDAR, occurrenceId = "occ-fail", externalId = "101", ownerId = "owner-1"),
                SystemMapping(kind = SystemMappingKind.CALENDAR, occurrenceId = "occ-ok", externalId = "102", ownerId = "owner-1"),
            ),
        )
        val platform = FakeCalendarPlatform(
            permission = true,
            events = mutableMapOf(
                101L to FakeEvent(ownerId = "owner-1", startMillis = nowMillis + 60_000L, failDelete = true),
                102L to FakeEvent(ownerId = "owner-1", startMillis = nowMillis + 120_000L, failDelete = false),
            ),
        )
        val coordinator = createCoordinator(settings, mappings, platform)

        val result = coordinator.clearCalendar()

        assertEquals(CalendarClearResult.Completed(deletedCount = 1, pendingCount = 1), result)
        assertEquals(listOf("occ-fail"), mappings.all(SystemMappingKind.CALENDAR, "owner-1").map { it.occurrenceId })
        assertFalse(coordinator.status.value.enabled)
        assertEquals(1, coordinator.status.value.pendingCleanup)
        assertEquals("有 1 个事件待清理", coordinator.status.value.lastError)
    }

    @Test
    fun `clearCalendar falls back to deleteOwned for mapped events when batch summary is empty`() = runTest {
        val settings = FakeAppSettings(
            CalendarSettings(enabled = true, calendarId = 7L, ownerId = "owner-1"),
        )
        val mappings = FakeSystemMappings(
            listOf(
                SystemMapping(kind = SystemMappingKind.CALENDAR, occurrenceId = "occ-1", externalId = "101", ownerId = "owner-1"),
            ),
        )
        val platform = FakeCalendarPlatform(
            permission = true,
            events = mutableMapOf(
                101L to FakeEvent(ownerId = "owner-1", startMillis = nowMillis - 10_000L),
            ),
            batchOverride = CalendarDeleteSummary(),
        )
        val coordinator = createCoordinator(settings, mappings, platform)

        val result = coordinator.clearCalendar()

        assertEquals(CalendarClearResult.Completed(deletedCount = 1, pendingCount = 0), result)
        assertEquals(1, platform.deleteOwnedCalls)
        assertTrue(platform.events.isEmpty())
        assertTrue(mappings.all(SystemMappingKind.CALENDAR, "owner-1").isEmpty())
    }
}
