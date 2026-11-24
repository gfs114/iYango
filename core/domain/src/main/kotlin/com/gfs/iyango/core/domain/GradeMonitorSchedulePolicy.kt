package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.ReminderSettings
import java.time.Instant
import java.time.ZoneId

/**
 * 新出分检查调度与冷却策略（纯 JVM）。
 *
 * - 期末冲刺激活时仅在 08:00 至 22:00 白天窗口高频检查（15 / 30 分钟），夜间自动休眠至次日 08:00；
 * - 超过 `gradeSprintUntilMillis` 自动退回常规小时周期；
 * - 前台、Wi-Fi 与充电场景顺手触发在冲刺期间冷却缩短至 10 分钟，常规期保持 30 分钟。
 */
object GradeMonitorSchedulePolicy {

    const val DAYTIME_START_HOUR = 8
    const val DAYTIME_END_HOUR = 22
    const val MINUTE_MILLIS = 60_000L
    const val HOUR_MILLIS = 60L * MINUTE_MILLIS
    const val DAY_MILLIS = 24L * HOUR_MILLIS
    const val MIN_RESCHEDULE_DELAY_MS = 60_000L
    const val NORMAL_FOREGROUND_COOLDOWN_MS = 30L * MINUTE_MILLIS
    const val SPRINT_FOREGROUND_COOLDOWN_MS = 10L * MINUTE_MILLIS
    const val NORMAL_CONTEXT_COOLDOWN_MS = 30L * MINUTE_MILLIS
    const val SPRINT_CONTEXT_COOLDOWN_MS = 10L * MINUTE_MILLIS

    fun isSprintActive(settings: ReminderSettings, nowMillis: Long): Boolean =
        settings.gradeSprintEnabled && settings.gradeSprintUntilMillis > nowMillis

    fun isSprintExpired(settings: ReminderSettings, nowMillis: Long): Boolean =
        settings.gradeSprintEnabled && settings.gradeSprintUntilMillis in 1..nowMillis

    fun isInDaytimeWindow(nowMillis: Long, zone: ZoneId): Boolean {
        val hour = Instant.ofEpochMilli(nowMillis).atZone(zone).hour
        return hour in DAYTIME_START_HOUR until DAYTIME_END_HOUR
    }

    fun nextDaytimeStartMillis(fromMillis: Long, zone: ZoneId): Long {
        val zoned = Instant.ofEpochMilli(fromMillis).atZone(zone)
        return when {
            zoned.hour < DAYTIME_START_HOUR ->
                zoned.toLocalDate().atTime(DAYTIME_START_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
            zoned.hour >= DAYTIME_END_HOUR ->
                zoned.toLocalDate().plusDays(1).atTime(DAYTIME_START_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
            else -> fromMillis
        }
    }

    fun cooldownMillis(trigger: GradeCheckTrigger, sprintActive: Boolean): Long = when (trigger) {
        GradeCheckTrigger.FOREGROUND_SILENT ->
            if (sprintActive) SPRINT_FOREGROUND_COOLDOWN_MS else NORMAL_FOREGROUND_COOLDOWN_MS
        GradeCheckTrigger.WIFI_CONNECTED, GradeCheckTrigger.POWER_CONNECTED ->
            if (sprintActive) SPRINT_CONTEXT_COOLDOWN_MS else NORMAL_CONTEXT_COOLDOWN_MS
        GradeCheckTrigger.USER_MANUAL_IN_GRADES_PAGE,
        GradeCheckTrigger.BACKGROUND_PERIODIC,
        GradeCheckTrigger.MANUAL_CHECK_IN_SETTINGS -> 0L
    }

    fun computeNextDelayMillis(
        settings: ReminderSettings,
        lastCheckAt: Long,
        nowMillis: Long,
        zone: ZoneId,
    ): Long {
        val sanitized = settings.sanitized()
        if (isSprintActive(sanitized, nowMillis)) {
            val sprintIntervalMs = sanitized.gradeSprintIntervalMinutes * MINUTE_MILLIS
            val baseTarget = if (lastCheckAt > 0L && (nowMillis - lastCheckAt) in 0L until sprintIntervalMs) {
                lastCheckAt + sprintIntervalMs
            } else {
                nowMillis + sprintIntervalMs
            }
            val daytimeTarget = nextDaytimeStartMillis(maxOf(baseTarget, nowMillis + MIN_RESCHEDULE_DELAY_MS), zone)
            if (daytimeTarget < sanitized.gradeSprintUntilMillis) {
                return (daytimeTarget - nowMillis).coerceAtLeast(MIN_RESCHEDULE_DELAY_MS)
            }
        }
        val normalIntervalMs = sanitized.gradeCheckIntervalHours * HOUR_MILLIS
        val elapsed = (nowMillis - lastCheckAt).coerceAtLeast(0L)
        return if (lastCheckAt > 0L && elapsed < normalIntervalMs) {
            (normalIntervalMs - elapsed).coerceAtLeast(MIN_RESCHEDULE_DELAY_MS)
        } else {
            normalIntervalMs
        }
    }
}
