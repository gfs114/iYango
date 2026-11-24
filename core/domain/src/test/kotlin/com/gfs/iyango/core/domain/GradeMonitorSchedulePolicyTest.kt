package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.ReminderSettings
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GradeMonitorSchedulePolicyTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun epoch(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `daytime window covers 08_00 inclusive to 22_00 exclusive`() {
        assertFalse(GradeMonitorSchedulePolicy.isInDaytimeWindow(epoch(2026, 11, 24, 7, 59), zone))
        assertTrue(GradeMonitorSchedulePolicy.isInDaytimeWindow(epoch(2026, 11, 24, 8, 0), zone))
        assertTrue(GradeMonitorSchedulePolicy.isInDaytimeWindow(epoch(2026, 11, 24, 21, 59), zone))
        assertFalse(GradeMonitorSchedulePolicy.isInDaytimeWindow(epoch(2026, 11, 24, 22, 0), zone))

        assertEquals(
            epoch(2026, 11, 24, 8, 0),
            GradeMonitorSchedulePolicy.nextDaytimeStartMillis(epoch(2026, 11, 24, 6, 30), zone),
        )
        assertEquals(
            epoch(2026, 11, 25, 8, 0),
            GradeMonitorSchedulePolicy.nextDaytimeStartMillis(epoch(2026, 11, 24, 22, 10), zone),
        )
        assertEquals(
            epoch(2026, 11, 24, 14, 15),
            GradeMonitorSchedulePolicy.nextDaytimeStartMillis(epoch(2026, 11, 24, 14, 15), zone),
        )
    }

    @Test
    fun `sprint scheduling uses 15m or 30m in daytime and sleeps across 22_00 to next 08_00`() {
        val nowDay = epoch(2026, 11, 24, 14, 0)
        val sprintSettings = ReminderSettings(
            gradeNotifyEnabled = true,
            gradeCheckIntervalHours = 4,
            gradeSprintEnabled = true,
            gradeSprintIntervalMinutes = 15,
            gradeSprintDurationDays = 7,
            gradeSprintUntilMillis = nowDay + 7 * GradeMonitorSchedulePolicy.DAY_MILLIS,
        )

        assertEquals(
            15 * GradeMonitorSchedulePolicy.MINUTE_MILLIS,
            GradeMonitorSchedulePolicy.computeNextDelayMillis(sprintSettings, nowDay, nowDay, zone),
        )

        // At 21:50 with 15m interval -> next candidate is 22:05 (night) -> jumps to 2026-11-25 08:00
        val lateNight = epoch(2026, 11, 24, 21, 50)
        val expectedMorning = epoch(2026, 11, 25, 8, 0)
        assertEquals(
            expectedMorning - lateNight,
            GradeMonitorSchedulePolicy.computeNextDelayMillis(sprintSettings, lateNight, lateNight, zone),
        )
    }

    @Test
    fun `expired sprint automatically reverts delay calculation to normal interval hours`() {
        val now = epoch(2026, 11, 24, 10, 0)
        val expiredSprint = ReminderSettings(
            gradeNotifyEnabled = true,
            gradeCheckIntervalHours = 6,
            gradeSprintEnabled = true,
            gradeSprintIntervalMinutes = 15,
            gradeSprintUntilMillis = now - 1_000L,
        )

        assertFalse(GradeMonitorSchedulePolicy.isSprintActive(expiredSprint, now))
        assertTrue(GradeMonitorSchedulePolicy.isSprintExpired(expiredSprint, now))
        assertEquals(
            6 * GradeMonitorSchedulePolicy.HOUR_MILLIS,
            GradeMonitorSchedulePolicy.computeNextDelayMillis(expiredSprint, 0L, now, zone),
        )
    }

    @Test
    fun `cooldown drops from 30m to 10m during sprint for foreground wifi and power triggers`() {
        listOf(
            GradeCheckTrigger.FOREGROUND_SILENT,
            GradeCheckTrigger.WIFI_CONNECTED,
            GradeCheckTrigger.POWER_CONNECTED,
        ).forEach { trigger ->
            assertEquals(
                30 * GradeMonitorSchedulePolicy.MINUTE_MILLIS,
                GradeMonitorSchedulePolicy.cooldownMillis(trigger, sprintActive = false),
            )
            assertEquals(
                10 * GradeMonitorSchedulePolicy.MINUTE_MILLIS,
                GradeMonitorSchedulePolicy.cooldownMillis(trigger, sprintActive = true),
            )
        }
        assertEquals(0L, GradeMonitorSchedulePolicy.cooldownMillis(GradeCheckTrigger.BACKGROUND_PERIODIC, sprintActive = true))
        assertEquals(0L, GradeMonitorSchedulePolicy.cooldownMillis(GradeCheckTrigger.MANUAL_CHECK_IN_SETTINGS, sprintActive = true))
    }
}
