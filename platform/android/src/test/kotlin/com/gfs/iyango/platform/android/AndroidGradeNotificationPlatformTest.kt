package com.gfs.iyango.platform.android

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.gfs.iyango.core.domain.GradeChangeDetector
import com.gfs.iyango.core.domain.GradeCheckTrigger
import com.gfs.iyango.core.domain.GradeRepositoryPort
import com.gfs.iyango.core.model.GradeRecord
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidGradeNotificationPlatformTest {

    private fun samplePayload() = GradeChangeDetector.detect(
        accountKey = "acc-101",
        partition = "*",
        hadBaseline = true,
        previousRecords = listOf(
            GradeRecord(
                accountKey = "acc-101",
                semester = "*",
                courseName = "线性代数",
                courseCode = "M2",
                credit = 2.0,
                scoreText = "80",
                gradePoint = 3.0,
                attribute = "必修",
                schoolRecordId = "ygu:2026-2027-1|M2",
            ),
        ),
        newRecords = listOf(
            GradeRecord(
                accountKey = "acc-101",
                semester = "*",
                courseName = "线性代数",
                courseCode = "M2",
                credit = 2.0,
                scoreText = "80",
                gradePoint = 3.0,
                attribute = "必修",
                schoolRecordId = "ygu:2026-2027-1|M2",
            ),
            GradeRecord(
                accountKey = "acc-101",
                semester = "*",
                courseName = "高等数学",
                courseCode = "M1",
                credit = 2.0,
                scoreText = "92",
                gradePoint = 4.2,
                attribute = "必修",
                schoolRecordId = "ygu:2026-2027-1|M1",
            ),
        ),
        alreadyNotifiedKeys = emptySet(),
    )!!

    private fun multiCoursePayload() = GradeChangeDetector.detect(
        accountKey = "acc-102",
        partition = GradeRepositoryPort.ALL_SEMESTERS,
        hadBaseline = true,
        previousRecords = listOf(
            GradeRecord(
                accountKey = "acc-102",
                semester = "*",
                courseName = "线性代数",
                courseCode = "MATH102",
                credit = 2.0,
                scoreText = "80",
                gradePoint = 3.0,
                attribute = "必修",
                schoolRecordId = "ygu:2026-2027-1|MATH102",
            ),
            GradeRecord(
                accountKey = "acc-102",
                semester = "*",
                courseName = "大学英语",
                courseCode = "ENG101",
                credit = 2.0,
                scoreText = "84",
                gradePoint = 3.4,
                attribute = "必修",
                schoolRecordId = "ygu:2026-2027-1|ENG101",
            ),
        ),
        newRecords = listOf(
            GradeRecord(
                accountKey = "acc-102",
                semester = "*",
                courseName = "线性代数",
                courseCode = "MATH102",
                credit = 2.0,
                scoreText = "80",
                gradePoint = 3.0,
                attribute = "必修",
                schoolRecordId = "ygu:2026-2027-1|MATH102",
            ),
            GradeRecord(
                accountKey = "acc-102",
                semester = "*",
                courseName = "大学英语",
                courseCode = "ENG101",
                credit = 2.0,
                scoreText = "84",
                gradePoint = 3.4,
                attribute = "必修",
                schoolRecordId = "ygu:2026-2027-1|ENG101",
            ),
            GradeRecord(
                accountKey = "acc-102",
                semester = "*",
                courseName = "高等数学",
                courseCode = "MATH101",
                credit = 2.0,
                scoreText = "94",
                gradePoint = 4.4,
                attribute = "必修",
                schoolRecordId = "ygu:2026-2027-1|MATH101",
            ),
            GradeRecord(
                accountKey = "acc-102",
                semester = "*",
                courseName = "数据结构",
                courseCode = "CS201",
                credit = 2.0,
                scoreText = "88",
                gradePoint = 3.8,
                attribute = "必修",
                schoolRecordId = "ygu:2026-2027-1|CS201",
            ),
            GradeRecord(
                accountKey = "acc-102",
                semester = "*",
                courseName = "音乐鉴赏",
                courseCode = "ART01",
                credit = 1.0,
                scoreText = "96",
                gradePoint = 4.6,
                attribute = "公共选修",
                schoolRecordId = "ygu:2026-2027-1|ART01",
            ),
        ),
        alreadyNotifiedKeys = emptySet(),
    )!!

    @Test
    fun `ensureChannel upgrades legacy grade_updates channel to high importance grade_updates_live`() {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel("grade_updates", "旧新出分通知", NotificationManager.IMPORTANCE_DEFAULT),
        )

        val platform = AndroidGradeNotificationPlatform(app)
        platform.ensureChannel()

        assertNull(manager.getNotificationChannel("grade_updates"))
        val channel = manager.getNotificationChannel(AndroidGradeNotificationPlatform.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals("新出分通知", channel!!.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
    }

    @Test
    fun `buildCapsuleContent shows course names without scores or gpa when redacted and hides gpa when collapsed`() {
        val singlePrivate = AndroidGradeNotificationPlatform.buildCapsuleContent(
            payload = samplePayload(),
            redactContent = true,
        )
        assertEquals("高等数学", singlePrivate.shortCritical)
        assertEquals("高等数学", singlePrivate.publicShortCritical)
        assertEquals("新出分", singlePrivate.subText)
        assertEquals("高等数学", singlePrivate.text)
        assertEquals("高等数学", singlePrivate.bigText)
        assertEquals("新增 1 门成绩", singlePrivate.publicTitle)
        assertEquals("高等数学", singlePrivate.publicText)
        listOf(singlePrivate.shortCritical, singlePrivate.text, singlePrivate.bigText, singlePrivate.publicText).forEach {
            assertFalse(it.contains("92") || it.contains("3.60") || it.contains("均绩") || it.contains("绩点"))
        }

        val singlePublic = AndroidGradeNotificationPlatform.buildCapsuleContent(
            payload = samplePayload(),
            redactContent = false,
        )
        assertEquals("高等数学", singlePublic.shortCritical)
        assertEquals("新出分", singlePublic.subText)
        assertEquals("高等数学 92", singlePublic.text)
        assertEquals("高等数学 92\n学期均绩 3.60 (+0.60)", singlePublic.bigText)
        assertEquals(2, singlePublic.bigText.lines().size)

        val multiPrivate = AndroidGradeNotificationPlatform.buildCapsuleContent(
            payload = multiCoursePayload(),
            redactContent = true,
        )
        assertEquals("高等数学等3门", multiPrivate.shortCritical)
        assertEquals("高等数学等3门", multiPrivate.publicShortCritical)
        assertEquals("高等数学、数据结构、音乐鉴赏", multiPrivate.text)
        assertEquals("高等数学、数据结构、音乐鉴赏", multiPrivate.bigText)
        assertEquals("高等数学、数据结构、音乐鉴赏", multiPrivate.publicText)
        assertEquals("新出分", multiPrivate.subText)
        listOf(multiPrivate.shortCritical, multiPrivate.text, multiPrivate.bigText, multiPrivate.publicText).forEach {
            assertFalse(
                it.contains("94") || it.contains("88") || it.contains("96") ||
                    it.contains("3.65") || it.contains("均绩") || it.contains("绩点"),
            )
        }

        val multiPublic = AndroidGradeNotificationPlatform.buildCapsuleContent(
            payload = multiCoursePayload(),
            redactContent = false,
        )
        assertEquals("高等数学等3门", multiPublic.shortCritical)
        assertEquals("新出分", multiPublic.subText)
        assertEquals("高等数学 94、数据结构 88、音乐鉴赏 96", multiPublic.text)
        assertEquals(
            "高等数学 94、数据结构 88、音乐鉴赏 96\n学期均绩 3.65 (+0.45)",
            multiPublic.bigText,
        )
        assertEquals(2, multiPublic.bigText.lines().size)

        val scoreUpdatedPayload = GradeChangeDetector.detect(
            accountKey = "acc-103",
            partition = "*",
            hadBaseline = true,
            previousRecords = listOf(
                GradeRecord(
                    accountKey = "acc-103",
                    semester = "*",
                    courseName = "线性代数",
                    courseCode = "MATH102",
                    credit = 2.0,
                    scoreText = "88",
                    gradePoint = 3.8,
                    attribute = "必修",
                    schoolRecordId = "ygu:2026-2027-1|MATH102",
                ),
            ),
            newRecords = listOf(
                GradeRecord(
                    accountKey = "acc-103",
                    semester = "*",
                    courseName = "线性代数",
                    courseCode = "MATH102",
                    credit = 2.0,
                    scoreText = "74",
                    gradePoint = 2.4,
                    attribute = "必修",
                    schoolRecordId = "ygu:2026-2027-1|MATH102",
                ),
            ),
            alreadyNotifiedKeys = emptySet(),
        )!!
        val updatedContent = AndroidGradeNotificationPlatform.buildCapsuleContent(
            payload = scoreUpdatedPayload,
            redactContent = false,
        )
        assertEquals("线性代数", updatedContent.shortCritical)
        assertEquals("成绩更新", updatedContent.subText)

        val longCoursePayload = GradeChangeDetector.detect(
            accountKey = "acc-104",
            partition = "*",
            hadBaseline = true,
            previousRecords = emptyList(),
            newRecords = listOf(
                GradeRecord(
                    accountKey = "acc-104",
                    semester = "*",
                    courseName = "计算机操作系统原理",
                    courseCode = "CS301",
                    credit = 3.0,
                    scoreText = "91",
                    gradePoint = 4.1,
                    attribute = "必修",
                    schoolRecordId = "ygu:2026-2027-1|CS301",
                ),
            ),
            alreadyNotifiedKeys = emptySet(),
        )!!
        val longContent = AndroidGradeNotificationPlatform.buildCapsuleContent(
            payload = longCoursePayload,
            redactContent = false,
        )
        assertEquals("计算机操作系统", longContent.shortCritical)

        val fourCoursePayload = GradeChangeDetector.detect(
            accountKey = "acc-105",
            partition = "*",
            hadBaseline = true,
            previousRecords = emptyList(),
            newRecords = listOf(
                GradeRecord(accountKey = "acc-105", semester = "*", courseName = "高等数学", courseCode = "C1", credit = 2.0, scoreText = "94", gradePoint = 4.4, attribute = "必修", schoolRecordId = "ygu:2026-2027-1|C1"),
                GradeRecord(accountKey = "acc-105", semester = "*", courseName = "数据结构", courseCode = "C2", credit = 2.0, scoreText = "88", gradePoint = 3.8, attribute = "必修", schoolRecordId = "ygu:2026-2027-1|C2"),
                GradeRecord(accountKey = "acc-105", semester = "*", courseName = "大学英语", courseCode = "C3", credit = 2.0, scoreText = "86", gradePoint = 3.6, attribute = "必修", schoolRecordId = "ygu:2026-2027-1|C3"),
                GradeRecord(accountKey = "acc-105", semester = "*", courseName = "线性代数", courseCode = "C4", credit = 2.0, scoreText = "90", gradePoint = 4.0, attribute = "必修", schoolRecordId = "ygu:2026-2027-1|C4"),
            ),
            alreadyNotifiedKeys = emptySet(),
        )!!
        val fourCourseContent = AndroidGradeNotificationPlatform.buildCapsuleContent(
            payload = fourCoursePayload,
            redactContent = false,
        )
        assertEquals("高等数学 94、数据结构 88、大学英语 86 等 4 门", fourCourseContent.text)
        assertEquals(2, fourCourseContent.bigText.lines().size)

        listOf(singlePrivate, singlePublic, multiPrivate, multiPublic, updatedContent, longContent, fourCourseContent).forEach { content ->
            listOf(content.shortCritical, content.subText, content.text).forEach { collapsed ->
                assertFalse("Collapsed field leaked GPA: $collapsed", collapsed.contains("均绩") || collapsed.contains("绩点"))
            }
            listOf(
                content.title,
                content.text,
                content.bigText,
                content.subText,
                content.shortCritical,
                content.publicTitle,
                content.publicText,
                content.publicSubText,
                content.publicShortCritical,
            ).forEach { field ->
                assertFalse("Copy contains forbidden dot: $field", field.contains('·') || field.contains('•'))
            }
        }
    }

    @Test
    fun `dynamically redacts scores on lockscreen and restores on unlock without resurrecting dismissed notifications`() {
        val app = RuntimeEnvironment.getApplication()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val keyguard = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val shadowKeyguard = shadowOf(keyguard)
        shadowKeyguard.setKeyguardLocked(false)

        val platform = AndroidGradeNotificationPlatform(app)
        val payload = samplePayload()
        val notificationId = AndroidGradeNotificationPlatform.notificationIdFor("acc-101")
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNm = shadowOf(manager)

        // Unlocked initially -> shows scores & GPA in expanded body, publicVersion remains redacted
        assertTrue(platform.showGradeChange(payload, hideScoreOnLockscreen = true))
        val unlockedNotif = shadowNm.getNotification(notificationId)!!
        assertEquals(Notification.VISIBILITY_PRIVATE, unlockedNotif.visibility)
        assertEquals("新出分", unlockedNotif.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())
        assertEquals("高等数学 92", unlockedNotif.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals("高等数学 92\n学期均绩 3.60 (+0.60)", unlockedNotif.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
        assertEquals("新增 1 门成绩", unlockedNotif.publicVersion.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("高等数学", unlockedNotif.publicVersion.extras.getString(Notification.EXTRA_TEXT))
        assertTrue(unlockedNotif.flags and Notification.FLAG_ONGOING_EVENT == 0)
        assertTrue(unlockedNotif.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertTrue(unlockedNotif.actions?.any { it.title == "知道了" } == true)

        // Screen turns off -> notification updates silently to hide scores
        shadowKeyguard.setKeyguardLocked(true)
        AndroidGradeNotificationPlatform.refreshActiveForLockState(app, onLockscreen = true)
        val lockedNotif = shadowNm.getNotification(notificationId)!!
        assertEquals("高等数学", lockedNotif.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals("高等数学", lockedNotif.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())

        // Screen turns on while still locked -> remains redacted
        AndroidGradeNotificationPlatform.refreshActiveForLockState(
            app,
            onLockscreen = AndroidGradeNotificationPlatform.isOnLockscreen(app),
        )
        val stillLockedNotif = shadowNm.getNotification(notificationId)!!
        assertEquals("高等数学", stillLockedNotif.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())

        // Broadcast unlock -> restores full scores & GPA
        shadowKeyguard.setKeyguardLocked(false)
        app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        shadowOf(app.mainLooper).idle()
        val restoredNotif = shadowNm.getNotification(notificationId)!!
        assertEquals("高等数学 92\n学期均绩 3.60 (+0.60)", restoredNotif.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())

        // Dismiss -> subsequent lockscreen events do not resurrect it
        GradeNotificationDismissReceiver().onReceive(
            app,
            AndroidGradeNotificationPlatform.dismissIntent(app, notificationId),
        )
        assertNull(shadowNm.getNotification(notificationId))
        shadowKeyguard.setKeyguardLocked(true)
        AndroidGradeNotificationPlatform.refreshActiveForLockState(app, onLockscreen = true)
        assertNull(shadowNm.getNotification(notificationId))
    }

    @Test
    fun `posts already redacted when keyguard is locked initially and restores on unlock`() {
        val app = RuntimeEnvironment.getApplication()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val keyguard = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val shadowKeyguard = shadowOf(keyguard)
        shadowKeyguard.setKeyguardLocked(true)

        val platform = AndroidGradeNotificationPlatform(app)
        val notificationId = AndroidGradeNotificationPlatform.notificationIdFor("acc-102")
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNm = shadowOf(manager)

        assertTrue(platform.showGradeChange(multiCoursePayload(), hideScoreOnLockscreen = true))
        val lockedNotif = shadowNm.getNotification(notificationId)!!
        assertEquals("高等数学、数据结构、音乐鉴赏", lockedNotif.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals("高等数学、数据结构、音乐鉴赏", lockedNotif.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())

        shadowKeyguard.setKeyguardLocked(false)
        AndroidGradeNotificationPlatform.refreshActiveForLockState(app, onLockscreen = false)
        val restored = shadowNm.getNotification(notificationId)!!
        assertEquals(
            "高等数学 94、数据结构 88、音乐鉴赏 96\n学期均绩 3.65 (+0.45)",
            restored.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
        )
        platform.cancelAll()
        assertNull(shadowNm.getNotification(notificationId))
    }

    @Test
    fun `posts public notification when hideScoreOnLockscreen is false`() {
        val app = RuntimeEnvironment.getApplication()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val keyguard = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(keyguard).setKeyguardLocked(true)

        val platform = AndroidGradeNotificationPlatform(app)
        assertTrue(platform.showGradeChange(samplePayload(), hideScoreOnLockscreen = false))

        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = shadowOf(manager).getNotification(AndroidGradeNotificationPlatform.notificationIdFor("acc-101"))!!
        assertEquals(Notification.VISIBILITY_PUBLIC, notif.visibility)
        assertNull(notif.publicVersion)
        assertEquals("高等数学 92\n学期均绩 3.60 (+0.60)", notif.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
        platform.cancelAll()
    }

    @Test
    fun `scheduler schedules and cancels inexact rtc wakeup alarm`() {
        val app = RuntimeEnvironment.getApplication()
        val clock = Clock.fixed(Instant.parse("2026-11-24T01:00:00Z"), ZoneOffset.UTC)
        val scheduler = AndroidGradeCheckScheduler(app, clock)
        val alarm = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarm = shadowOf(alarm)

        scheduler.scheduleNext(4 * 3_600_000L)
        val next = shadowAlarm.nextScheduledAlarm
        assertNotNull(next)
        assertEquals(clock.millis() + 4 * 3_600_000L, next!!.triggerAtTime)

        scheduler.cancel()
        assertNull(shadowAlarm.nextScheduledAlarm)
    }

    @Test
    fun `scheduler manages power context receiver wifi detection and trigger extras`() {
        val app = RuntimeEnvironment.getApplication()
        val clock = Clock.fixed(Instant.parse("2026-11-24T10:00:00Z"), ZoneOffset.UTC)
        val scheduler = AndroidGradeCheckScheduler(app, clock)

        val wifiIntent = AndroidGradeCheckScheduler.checkIntent(app, GradeCheckTrigger.WIFI_CONNECTED)
        assertEquals("WIFI_CONNECTED", wifiIntent.getStringExtra(AndroidGradeCheckScheduler.EXTRA_TRIGGER))

        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadowCm = shadowOf(cm)
        val wifiCaps = ShadowNetworkCapabilities.newInstance()
        shadowOf(wifiCaps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(wifiCaps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowCm.setNetworkCapabilities(cm.activeNetwork, wifiCaps)
        assertTrue(scheduler.isOnWifi())

        val cellCaps = ShadowNetworkCapabilities.newInstance()
        shadowOf(cellCaps).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        shadowOf(cellCaps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowCm.setNetworkCapabilities(cm.activeNetwork, cellCaps)
        assertFalse(scheduler.isOnWifi())

        val shadowApp = shadowOf(app)
        val beforePowerReceivers = shadowApp.registeredReceivers.count {
            it.intentFilter.hasAction(Intent.ACTION_POWER_CONNECTED)
        }
        scheduler.setContextTriggersActive(true)
        val duringPowerReceivers = shadowApp.registeredReceivers.count {
            it.intentFilter.hasAction(Intent.ACTION_POWER_CONNECTED)
        }
        assertEquals(beforePowerReceivers + 1, duringPowerReceivers)

        scheduler.setContextTriggersActive(false)
        val afterPowerReceivers = shadowApp.registeredReceivers.count {
            it.intentFilter.hasAction(Intent.ACTION_POWER_CONNECTED)
        }
        assertEquals(beforePowerReceivers, afterPowerReceivers)
    }
}
