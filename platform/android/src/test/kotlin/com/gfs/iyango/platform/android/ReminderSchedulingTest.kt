package com.gfs.iyango.platform.android

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import com.gfs.iyango.core.domain.ReminderPlanItem
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderSchedulingTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val store = ReminderPlanStore(context)
    private val platform = AndroidReminderPlatform(context)

    @Before
    fun setUp() {
        shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(notifications).setNotificationsEnabled(true)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    private fun item(startInMinutes: Int = 30): ReminderPlanItem {
        val start = System.currentTimeMillis() + startInMinutes * MINUTE
        return ReminderPlanItem(
            occurrenceId = "course:reminder-recovery",
            courseId = 1,
            courseName = "提醒回归课程",
            occurrenceDate = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalDate(),
            triggerAtMillis = start - 10 * MINUTE,
            startAtMillis = start,
            endAtMillis = start + 45 * MINUTE,
            title = "提醒回归课程",
            body = "A101 · 即将上课",
            payloadHash = "stable-payload",
        )
    }

    @Test
    fun unchangedPlanRestoresAlarmsAfterSystemClearsThem() = runBlocking {
        val item = item()
        platform.schedule(listOf(item), OWNER)
        assertEquals(2, shadowOf(alarms).scheduledAlarms.size)
        // Simulate reboot/package replacement: the persisted plan survives, system alarms do not.
        shadowOf(alarms).scheduledAlarms.toList().forEach { alarms.cancel(requireNotNull(it.operation)) }
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())

        AndroidReminderPlatform(context).schedule(listOf(item), OWNER)

        assertEquals(
            listOf(item.triggerAtMillis, item.startAtMillis),
            shadowOf(alarms).scheduledAlarms.map { it.triggerAtTime }.sorted(),
        )
    }

    @Test
    fun refreshingSamePlanReplacesAlarmsWithoutDuplicates() = runBlocking {
        val item = item()
        repeat(3) { platform.schedule(listOf(item), OWNER) }
        assertEquals(2, shadowOf(alarms).scheduledAlarms.size)
        assertEquals(1, store.load(OWNER).size)
        assertTrue(notifications.activeNotifications.isEmpty())
    }

    @Test
    fun grantingExactAlarmPermissionUpgradesUnchangedPlan() = runBlocking {
        val item = item()
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        platform.schedule(listOf(item), OWNER)
        assertTrue(shadowOf(alarms).scheduledAlarms.all { it.windowLengthMs != ShadowAlarmManager.WINDOW_EXACT })

        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        platform.schedule(listOf(item), OWNER)

        assertTrue(shadowOf(alarms).scheduledAlarms.all { it.windowLengthMs == ShadowAlarmManager.WINDOW_EXACT })
    }

    @Test
    fun recoveryDuringLeadWindowShowsReminderAndRestoresStartAlarm() = runBlocking {
        val item = item(startInMinutes = 5)
        store.save(OWNER, listOf(store.toStored(item)))

        platform.schedule(listOf(item), OWNER)

        assertEquals(listOf(item.startAtMillis), shadowOf(alarms).scheduledAlarms.map { it.triggerAtTime })
        assertEquals(ReminderNotifications.requestCode(item.occurrenceId), notifications.activeNotifications.single().id)
    }

    @Test
    fun recoveryDuringClassRestoresOngoingNotificationWithoutPastAlarms() = runBlocking {
        val item = item(startInMinutes = -5)
        store.save(OWNER, listOf(store.toStored(item)))

        platform.schedule(listOf(item), OWNER)

        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
        assertEquals(ReminderNotifications.requestCode(item.occurrenceId), notifications.activeNotifications.single().id)
    }

    @Test
    fun removedFutureCourseDoesNotSurviveAsInFlightReminder() = runBlocking {
        val item = item()
        platform.schedule(listOf(item), OWNER)

        platform.schedule(emptyList(), OWNER)

        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
        assertTrue(store.load(OWNER).isEmpty())
        assertTrue(notifications.activeNotifications.isEmpty())
    }

    @Test
    fun dismissedReminderStaysDismissedDuringRecovery() = runBlocking {
        val item = item(startInMinutes = 5)
        store.save(OWNER, listOf(store.toStored(item)))
        store.addDismissed(OWNER, item.occurrenceId)

        platform.schedule(listOf(item), OWNER)

        assertTrue(store.isDismissed(OWNER, item.occurrenceId))
        assertTrue(notifications.activeNotifications.isEmpty())
    }

    @Test
    fun removedActiveCourseIsCancelledInsteadOfRestoredFromStaleSnapshot() = runBlocking {
        val item = item(startInMinutes = -5)
        store.save(OWNER, listOf(store.toStored(item)))
        ReminderNotifications.render(context, store.toStored(item), System.currentTimeMillis(), OWNER)
        assertEquals(1, notifications.activeNotifications.size)

        platform.schedule(emptyList(), OWNER)

        assertTrue(store.load(OWNER).isEmpty())
        assertTrue(notifications.activeNotifications.isEmpty())
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
    }

    @Test
    fun scheduledPreAlarmPassesSnapshotValidationAndPublishesNotification() = runBlocking {
        val item = item()
        platform.schedule(listOf(item), OWNER)
        val pre = shadowOf(alarms).scheduledAlarms.single { it.triggerAtTime == item.triggerAtMillis }
        val intent = shadowOf(pre.operation).savedIntent
        assertEquals(ReminderIntentsInternal.PHASE_PRE, intent.getStringExtra(ReminderIntentsInternal.EXTRA_PHASE))

        ReminderReceiver().onReceive(context, intent)

        assertNotNull(notifications.activeNotifications.single().notification)
        assertFalse(store.isDismissed(OWNER, item.occurrenceId))
    }

    private companion object {
        const val OWNER = "local"
        const val MINUTE = 60_000L
    }
}
