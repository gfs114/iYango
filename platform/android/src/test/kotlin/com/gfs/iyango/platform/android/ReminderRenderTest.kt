package com.gfs.iyango.platform.android

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import com.gfs.iyango.platform.android.ReminderIntentsInternal.ACTION
import com.gfs.iyango.platform.android.ReminderIntentsInternal.EXTRA_OCCURRENCE_ID
import com.gfs.iyango.platform.android.ReminderIntentsInternal.EXTRA_OWNER
import com.gfs.iyango.platform.android.ReminderIntentsInternal.EXTRA_PAYLOAD_HASH
import com.gfs.iyango.platform.android.ReminderIntentsInternal.EXTRA_PHASE
import com.gfs.iyango.platform.android.ReminderIntentsInternal.EXTRA_TRIGGER_AT
import com.gfs.iyango.platform.android.ReminderIntentsInternal.PHASE_START
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * 提醒渲染的 Robolectric 测试：阶段由当前时刻判定、已关闭不展示、旧版快照可读取。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderRenderTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val store = ReminderPlanStore(context)

    private companion object {
        const val OWNER = "local"
        const val MINUTE = 60_000L
    }

    @Before
    fun setUp() {
        Shadows.shadowOf(context.applicationContext as Application)
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Shadows.shadowOf(context.getSystemService(NotificationManager::class.java))
            .setNotificationsEnabled(true)
    }

    private fun entry(
        occurrenceId: String = "occ-course",
        startAt: Long,
        endAt: Long? = null,
        isExam: Boolean = false,
        accentColor: String = "",
        dayIndex: Int = 0,
        dayTotal: Int = 0,
    ): StoredReminder = StoredReminder(
        occurrenceId = occurrenceId,
        triggerAtMillis = startAt - 10 * MINUTE,
        payloadHash = "hash-$occurrenceId",
        planRevision = 1,
        title = if (isExam) "考试提醒 高等数学" else "高等数学",
        body = if (isExam) {
            "高等数学 B202 座位 12 09:00 开始"
        } else {
            "张老师 09:00 上课\nA101"
        },
        courseId = 1,
        date = "2026-10-26",
        startMinute = 540,
        startAtMillis = startAt,
        endAtMillis = endAt,
        courseName = "高等数学",
        teacher = if (isExam) "" else "张老师",
        classroom = if (isExam) "B202" else "A101",
        isExam = isExam,
        accentColor = accentColor,
        dayIndex = dayIndex,
        dayTotal = dayTotal,
    )

    private fun posted(id: Int): Notification? =
        context.getSystemService(NotificationManager::class.java)
            .activeNotifications
            .firstOrNull { it.id == id }
            ?.notification

    private fun notificationId(entry: StoredReminder): Int =
        ReminderNotifications.requestCode(entry.occurrenceId)

    /** 与实现同一时区计算下课时刻的展示文本，避免测试依赖设备时区。 */
    private fun endClock(atMillis: Long): String =
        DateTimeFormatter.ofPattern("HH:mm")
            .format(Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault()))

    @Test
    fun `开始前渲染课前阶段并倒计时到上课时刻`() {
        val now = 1_770_000_000_000L
        val item = entry(startAt = now + 5 * MINUTE, endAt = now + 65 * MINUTE)
        ReminderNotifications.render(context, item, now, OWNER)
        val notification = posted(notificationId(item))
        assertNotNull(notification)
        notification!!
        // 三行化文案：title 只放课程名，阶段词在 subText，text 为「教室 HH:mm 上课」。
        assertEquals("高等数学", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(item.body, notification.extras.getString(Notification.EXTRA_TEXT))
        assertEquals("即将上课", notification.extras.getString(Notification.EXTRA_SUB_TEXT))
        assertEquals(item.startAtMillis, notification.`when`)
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        // 课程使用专属书本图标。
        assertEquals(
            R.drawable.ic_stat_course,
            Shadows.shadowOf(notification.smallIcon).getResId(),
        )
    }

    @Test
    fun `课中渲染上课中阶段并倒计时到下课时刻`() {
        val now = 1_770_000_000_000L
        val item = entry(startAt = now - 5 * MINUTE, endAt = now + 55 * MINUTE)
        ReminderNotifications.render(context, item, now, OWNER)
        val notification = posted(notificationId(item))
        assertNotNull(notification)
        notification!!
        assertEquals("高等数学", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("上课中", notification.extras.getString(Notification.EXTRA_SUB_TEXT))
        val text = notification.extras.getString(Notification.EXTRA_TEXT).orEmpty()
        assertEquals("张老师 ${endClock(item.startAtMillis)} - ${endClock(item.endAtMillis!!)}\nA101", text)
        assertEquals(item.endAtMillis, notification.`when`)
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
    }

    @Test
    fun `无结束时间时已开始保持课前文案且不倒计时`() {
        val now = 1_770_000_000_000L
        // 窗口 3 分钟：开始 2 分钟后仍在窗口内，1 分钟后自动消失。
        val item = entry(occurrenceId = "occ-open", startAt = now - 2 * MINUTE, endAt = null)
        ReminderNotifications.render(context, item, now, OWNER)
        val notification = posted(notificationId(item))
        assertNotNull(notification)
        notification!!
        assertEquals("高等数学", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(item.body, notification.extras.getString(Notification.EXTRA_TEXT))
        assertEquals("已开始上课", notification.extras.getString(Notification.EXTRA_SUB_TEXT))
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        // 自动删除时刻 = 开始后 3 分钟。
        assertEquals(item.startAtMillis + 3 * MINUTE - now, notification.timeoutAfter)
    }

    @Test
    fun `已过下课时刻后不再展示并取消通知`() {
        val now = 1_770_000_000_000L
        val item = entry(occurrenceId = "occ-past", startAt = now - 65 * MINUTE, endAt = now - 5 * MINUTE)
        // 先渲染课中阶段，再推进到下课后渲染：通知应被移除。
        ReminderNotifications.render(context, item, now - 10 * MINUTE, OWNER)
        assertNotNull(posted(notificationId(item)))
        ReminderNotifications.render(context, item, now, OWNER)
        assertNull(posted(notificationId(item)))
    }

    @Test
    fun `已关闭的 id 不再展示`() {
        val now = 1_770_000_000_000L
        val item = entry(occurrenceId = "occ-dismissed", startAt = now + 5 * MINUTE, endAt = now + 65 * MINUTE)
        store.addDismissed(OWNER, item.occurrenceId)
        ReminderNotifications.render(context, item, now, OWNER)
        assertNull(posted(notificationId(item)))
    }

    @Test
    fun `旧版快照 JSON 能正常读取并按旧版静态展示`() {
        val legacyJson =
            """[{"occurrenceId":"occ-legacy","triggerAtMillis":1760000000000,""" +
                """"payloadHash":"legacyhash","planRevision":1,"title":"即将上课 · 旧课",""" +
                """"body":"旧课 · C303 · 08:00 开始","courseId":7,"date":"2026-10-26","startMinute":480}]"""
        context.getSharedPreferences("reminder_plan", Context.MODE_PRIVATE)
            .edit()
            .putString("plan_$OWNER", legacyJson)
            .apply()
        val loaded = store.load(OWNER)
        val legacy = loaded["occ-legacy"]
        assertNotNull(legacy)
        legacy!!
        // 缺失字段使用合理默认值，不崩溃。
        assertEquals(0L, legacy.startAtMillis)
        assertNull(legacy.endAtMillis)
        assertEquals("", legacy.courseName)
        assertEquals("", legacy.classroom)
        assertFalse(legacy.isExam)
        // 缺开始时刻仍静态展示；仅移除旧文案分隔符，不改写保存的快照。
        val now = 1_770_000_000_000L
        ReminderNotifications.render(context, legacy, now, OWNER)
        val notification = posted(notificationId(legacy))
        assertNotNull(notification)
        notification!!
        assertEquals("即将上课 旧课", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("旧课 C303 08:00 开始", notification.extras.getString(Notification.EXTRA_TEXT))
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
    }

    @Test
    fun `知道了广播写入已关闭集合并取消通知`() {
        val now = System.currentTimeMillis()
        val item = entry(occurrenceId = "occ-dismiss-action", startAt = now + 5 * MINUTE, endAt = now + 65 * MINUTE)
        store.save(OWNER, listOf(item))
        val dismiss = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderIntentsInternal.ACTION_DISMISS
            putExtra(EXTRA_OWNER, OWNER)
            putExtra(EXTRA_OCCURRENCE_ID, item.occurrenceId)
        }
        ReminderReceiver().onReceive(context, dismiss)
        assertTrue(store.isDismissed(OWNER, item.occurrenceId))
        // 后续触发到达也不会再展示。
        ReminderNotifications.render(context, item, now, OWNER)
        assertNull(posted(notificationId(item)))
    }

    @Test
    fun `start 触发点复核通过后渲染课中阶段`() {
        val now = System.currentTimeMillis()
        val item = entry(occurrenceId = "occ-start", startAt = now - MINUTE, endAt = now + 55 * MINUTE)
        store.save(OWNER, listOf(item))
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION
            putExtra(EXTRA_OWNER, OWNER)
            putExtra(EXTRA_OCCURRENCE_ID, item.occurrenceId)
            putExtra(EXTRA_TRIGGER_AT, item.startAtMillis)
            putExtra(EXTRA_PAYLOAD_HASH, item.payloadHash)
            putExtra(EXTRA_PHASE, PHASE_START)
        }
        ReminderReceiver().onReceive(context, intent)
        val notification = posted(notificationId(item))
        assertNotNull(notification)
        assertEquals("高等数学", notification!!.extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    fun `触发时刻与快照不一致的旧触发点被丢弃`() {
        val now = System.currentTimeMillis()
        val item = entry(occurrenceId = "occ-stale", startAt = now + 5 * MINUTE, endAt = now + 65 * MINUTE)
        store.save(OWNER, listOf(item))
        val stale = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION
            putExtra(EXTRA_OWNER, OWNER)
            putExtra(EXTRA_OCCURRENCE_ID, item.occurrenceId)
            putExtra(EXTRA_TRIGGER_AT, item.triggerAtMillis + 1)
            putExtra(EXTRA_PAYLOAD_HASH, item.payloadHash)
        }
        ReminderReceiver().onReceive(context, stale)
        assertNull(posted(notificationId(item)))
    }

    @Test
    fun `考试提醒使用考试文案事件类别橙色与专属图标`() {
        val now = 1_770_000_000_000L
        val item = entry(
            occurrenceId = "exam:1",
            startAt = now + 5 * MINUTE,
            endAt = now + 125 * MINUTE,
            isExam = true,
            accentColor = "#FF9500",
        )
        ReminderNotifications.render(context, item, now, OWNER)
        val notification = posted(notificationId(item))
        assertNotNull(notification)
        notification!!
        assertEquals("高等数学", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("即将考试", notification.extras.getString(Notification.EXTRA_SUB_TEXT))
        val text = notification.extras.getString(Notification.EXTRA_TEXT).orEmpty()
        assertEquals("B202 座位 12 09:00 开始", text)
        assertEquals(Notification.CATEGORY_EVENT, notification.category)
        // 考试固定橙色强调色与专属试卷图标。
        assertEquals(0xFFFF9500.toInt(), notification.color)
        assertEquals(R.drawable.ic_stat_exam, Shadows.shadowOf(notification.smallIcon).getResId())
        // 考试不显示下一节预告：bigText 与 text 一致。
        assertEquals(text, notification.extras.getString(Notification.EXTRA_BIG_TEXT))
        val oldFormat = item.copy(body = "高等数学 · B202 · 座位 12 · 09:00 开始")
        ReminderNotifications.render(context, oldFormat, now, OWNER)
        assertEquals(text, posted(notificationId(item))!!.extras.getString(Notification.EXTRA_TEXT))
    }

    // Android 16（SDK 36）沙箱需要 Java 21，本机 Gradle 为 Java 17：
    // API 36+ 的 setOngoing(true) + setRequestPromotedOngoing(true) 分支留待真机验证，
    // 此处以 SDK 35 覆盖低版本分支（setOngoing(false) + setAutoCancel(true)）。
    @Test
    fun `Android 16 以下不常驻且点击后自动消失`() {
        val now = 1_770_000_000_000L
        val item = entry(occurrenceId = "occ-pre16", startAt = now + 5 * MINUTE, endAt = now + 65 * MINUTE)
        ReminderNotifications.render(context, item, now, OWNER)
        val notification = posted(notificationId(item))
        assertNotNull(notification)
        notification!!
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT == 0)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test
    fun `课前大文本不追加下一节预告且副标题带已上与总课程数`() {
        val now = 1_770_000_000_000L
        val first = entry(
            occurrenceId = "occ-1of2",
            startAt = now + 5 * MINUTE,
            endAt = now + 45 * MINUTE,
            dayIndex = 1,
            dayTotal = 2,
        )
        val second = entry(
            occurrenceId = "occ-2of2",
            startAt = now + 65 * MINUTE,
            endAt = now + 125 * MINUTE,
            dayIndex = 2,
            dayTotal = 2,
        ).copy(courseName = "线性代数", classroom = "B203")
        store.save(OWNER, listOf(first, second))

        ReminderNotifications.render(context, first, now, OWNER)
        val firstNotification = posted(notificationId(first))
        assertNotNull(firstNotification)
        firstNotification!!
        assertEquals("即将上课 0/2", firstNotification.extras.getString(Notification.EXTRA_SUB_TEXT))
        val firstBigText = firstNotification.extras.getString(Notification.EXTRA_BIG_TEXT).orEmpty()
        assertFalse(firstBigText.contains("下一节"))
        assertEquals(firstNotification.extras.getString(Notification.EXTRA_TEXT), firstBigText)

        ReminderNotifications.render(context, second, now + 50 * MINUTE, OWNER)
        val secondNotification = posted(notificationId(second))
        assertNotNull(secondNotification)
        assertEquals("即将上课 1/2", secondNotification!!.extras.getString(Notification.EXTRA_SUB_TEXT))
    }

    @Test
    fun `课中阶段副标题带节次且最后一节不追加冗余提示行`() {
        val now = 1_770_000_000_000L
        val item = entry(
            occurrenceId = "occ-2of4",
            startAt = now - 5 * MINUTE,
            endAt = now + 55 * MINUTE,
            dayIndex = 2,
            dayTotal = 4,
        )
        ReminderNotifications.render(context, item, now, OWNER)
        val notification = posted(notificationId(item))
        assertNotNull(notification)
        assertEquals("上课中 2/4", notification!!.extras.getString(Notification.EXTRA_SUB_TEXT))

        // 当天最后一节：无下一节且 index == total，不再追加第三行「今日最后一节」，避免灵动胶囊 2 行截断省略号。
        val last = entry(
            occurrenceId = "occ-last",
            startAt = now - 5 * MINUTE,
            endAt = now + 55 * MINUTE,
            dayIndex = 4,
            dayTotal = 4,
        )
        ReminderNotifications.render(context, last, now, OWNER)
        val lastNotification = posted(notificationId(last))
        assertNotNull(lastNotification)
        val bigText = lastNotification!!.extras.getString(Notification.EXTRA_BIG_TEXT).orEmpty()
        assertFalse(bigText.contains("今日最后一节"))
        assertEquals(lastNotification.extras.getString(Notification.EXTRA_TEXT), bigText)
    }

    @Test
    fun `强调色非法时不崩溃且不设色`() {
        val now = 1_770_000_000_000L
        val item = entry(
            occurrenceId = "occ-badcolor",
            startAt = now + 5 * MINUTE,
            endAt = now + 65 * MINUTE,
            accentColor = "not-a-color",
        )
        ReminderNotifications.render(context, item, now, OWNER)
        val notification = posted(notificationId(item))
        assertNotNull(notification)
        assertEquals(0, notification!!.color)
    }

    @Test
    fun `content 纯函数在已开始无结束时提供短文本`() {
        val now = 1_770_000_000_000L
        val course = entry(occurrenceId = "occ-open-2", startAt = now - MINUTE, endAt = null)
        val courseContent = ReminderNotifications.content(
            course,
            ReminderPhase.STARTED_NO_END,
            index = null,
            total = null,
        )
        assertEquals("已上课", courseContent.shortCritical)
        assertEquals("已开始上课", courseContent.subText)
        val exam = entry(occurrenceId = "exam:2", startAt = now - MINUTE, endAt = null, isExam = true)
        val examContent = ReminderNotifications.content(
            exam,
            ReminderPhase.STARTED_NO_END,
            index = null,
            total = null,
        )
        assertEquals("考试中", examContent.shortCritical)
        assertEquals("已开始考试", examContent.subText)
        // 课前：胶囊短文本展示教室（或阶段词）；课中：shortCritical 为 null 以让位给系统原生倒计时。
        val before = ReminderNotifications.content(
            course,
            ReminderPhase.BEFORE_START,
            index = null,
            total = null,
        )
        assertEquals("A101", before.shortCritical)
        val examBefore = ReminderNotifications.content(
            exam,
            ReminderPhase.BEFORE_START,
            index = null,
            total = null,
        )
        assertEquals("B202", examBefore.shortCritical)
        assertNull(
            ReminderNotifications.content(
                course,
                ReminderPhase.IN_CLASS,
                index = null,
                total = null,
            ).shortCritical,
        )
        assertNull(
            ReminderNotifications.content(
                exam,
                ReminderPhase.IN_CLASS,
                index = null,
                total = null,
            ).shortCritical,
        )
        // 教室未知：课前展示阶段词，课中仍为 null（让位给系统倒计时）。
        val noRoom = entry(
            occurrenceId = "occ-noroom",
            startAt = now + 5 * MINUTE,
            endAt = now + 65 * MINUTE,
        ).copy(teacher = "", classroom = "")
        assertEquals(
            "即将上课",
            ReminderNotifications.content(
                noRoom,
                ReminderPhase.BEFORE_START,
                index = null,
                total = null,
            ).shortCritical,
        )
        assertNull(
            ReminderNotifications.content(
                noRoom,
                ReminderPhase.IN_CLASS,
                index = null,
                total = null,
            ).shortCritical,
        )
    }

    @Test
    fun `dayContext 按快照推导下一节与节次`() {
        val now = 1_770_000_000_000L
        val first = entry(occurrenceId = "occ-d1", startAt = now + 60 * MINUTE, endAt = now + 120 * MINUTE)
        val second = entry(occurrenceId = "occ-d2", startAt = now + 120 * MINUTE, endAt = now + 180 * MINUTE)
            .copy(courseName = "线性代数")
        val ctx = ReminderNotifications.dayContext(listOf(second, first), first)
        assertEquals(second.occurrenceId, ctx.next?.occurrenceId)
        assertEquals(1, ctx.index)
        assertEquals(2, ctx.total)
        assertEquals(2, ctx.today.size)
    }

    @Test
    fun `点击通知跳转课表页签而非课程详情`() {
        val pm = Shadows.shadowOf(context.packageManager)
        val component = android.content.ComponentName(context.packageName, "com.gfs.iyango.MainActivity")
        pm.addOrUpdateActivity(android.content.pm.ActivityInfo().apply {
            packageName = context.packageName
            name = component.className
        })
        pm.addIntentFilterForActivity(
            component,
            android.content.IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            },
        )
        val now = 1_770_000_000_000L
        val item = entry(startAt = now + 5 * MINUTE, endAt = now + 65 * MINUTE)
        ReminderNotifications.render(context, item, now, OWNER)
        val notification = posted(notificationId(item))
        assertNotNull(notification)
        val pending = notification!!.contentIntent
        assertNotNull(pending)
        val savedIntent = Shadows.shadowOf(pending).savedIntent
        assertEquals(1, savedIntent.getIntExtra(LaunchIntents.EXTRA_TARGET_TAB, -1))
        assertFalse(savedIntent.hasExtra(ReminderIntents.EXTRA_COURSE_ID))
    }

    @Test
    fun `开启常驻实时进度后课中显示进度条与简短状态词并下课自动移除`() {
        val now = 1_770_000_000_000L
        val item = entry(
            occurrenceId = "occ-live-progress",
            startAt = now - 30 * MINUTE,
            endAt = now + 30 * MINUTE,
        ).copy(ongoingProgress = true)

        ReminderNotifications.render(context, item, now, OWNER)
        val notification = posted(notificationId(item))
        assertNotNull(notification)
        notification!!
        assertTrue((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        assertNull(notification.getLargeIcon())
        assertEquals(100, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(50, notification.extras.getInt(Notification.EXTRA_PROGRESS))
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertEquals("上课中", notification.extras.getString(Notification.EXTRA_SUB_TEXT))
        val text = notification.extras.getString(Notification.EXTRA_TEXT).orEmpty()
        assertEquals("张老师 ${endClock(item.startAtMillis)} - ${endClock(item.endAtMillis!!)}\nA101", text)
        assertFalse(text.contains(" · "))

        // 课后自动消失
        ReminderNotifications.render(context, item, item.endAtMillis!! + MINUTE, OWNER)
        assertNull(posted(notificationId(item)))
    }

    @Test
    fun `换楼赶课预警在快照往返与课前通知中正确保留并渲染`() {
        val now = 1_770_000_000_000L
        val item = entry(
            occurrenceId = "occ-transfer",
            startAt = now + 5 * MINUTE,
            endAt = now + 65 * MINUTE,
        ).copy(
            body = "张老师 09:00 - 10:00\n信息楼 B202 · 换楼：明理楼 → 信息楼",
            classroom = "信息楼 B202",
            transferWarning = "换楼：明理楼 → 信息楼",
        )
        store.save(OWNER, listOf(item))
        val loaded = store.load(OWNER)[item.occurrenceId]
        assertNotNull(loaded)
        assertEquals("换楼：明理楼 → 信息楼", loaded!!.transferWarning)

        ReminderNotifications.render(context, loaded, now, OWNER)
        val notification = posted(notificationId(loaded))
        assertNotNull(notification)
        val text = notification!!.extras.getString(Notification.EXTRA_TEXT).orEmpty()
        assertTrue(text.contains("换楼：明理楼 → 信息楼"))
    }

    @Test
    fun `实况进度样式仅保留书本或试卷追踪图标且无节点圆点与分段断点`() {
        val now = 1_770_000_000_000L
        val course = entry(
            occurrenceId = "occ-live-style-course",
            startAt = now - 33 * MINUTE,
            endAt = now + 27 * MINUTE,
            isExam = false,
        ).copy(ongoingProgress = true)
        val courseStyle = ReminderNotifications.buildLiveProgressStyle(
            context = context,
            entry = course,
            progress = 55,
            accentColor = 0xFF3B82F6.toInt(),
        )
        assertEquals(55, courseStyle.progress)
        assertTrue(courseStyle.isStyledByProgress)
        assertTrue(courseStyle.progressPoints.isEmpty())
        assertEquals(1, courseStyle.progressSegments.size)
        assertEquals(100, courseStyle.progressSegments.single().length)
        assertEquals(0xFF3B82F6.toInt(), courseStyle.progressSegments.single().color)
        assertEquals(R.drawable.ic_stat_course, courseStyle.progressTrackerIcon?.resId)

        val exam = entry(
            occurrenceId = "exam:live-style",
            startAt = now - 33 * MINUTE,
            endAt = now + 27 * MINUTE,
            isExam = true,
        ).copy(ongoingProgress = true)
        val examStyle = ReminderNotifications.buildLiveProgressStyle(
            context = context,
            entry = exam,
            progress = 55,
            accentColor = 0xFFFF9500.toInt(),
        )
        assertEquals(55, examStyle.progress)
        assertTrue(examStyle.isStyledByProgress)
        assertTrue(examStyle.progressPoints.isEmpty())
        assertEquals(1, examStyle.progressSegments.size)
        assertEquals(100, examStyle.progressSegments.single().length)
        assertEquals(0xFFFF9500.toInt(), examStyle.progressSegments.single().color)
        assertEquals(R.drawable.ic_stat_exam, examStyle.progressTrackerIcon?.resId)
    }

    @Test
    fun `课中正文为教师与时间同行且楼栋教室单独占第二行`() {
        val now = 1_770_000_000_000L
        val startAt = now - 30 * MINUTE
        val endAt = now + 30 * MINUTE
        val timeRange = "${endClock(startAt)} - ${endClock(endAt)}"

        val withTeacher = entry(
            occurrenceId = "occ-line-with-teacher",
            startAt = startAt,
            endAt = endAt,
        ).copy(teacher = "韩兴助教", classroom = "知行楼 B305")
        val withTeacherContent = ReminderNotifications.content(
            withTeacher,
            ReminderPhase.IN_CLASS,
            index = 3,
            total = 5,
        )
        assertEquals("韩兴助教 $timeRange\n知行楼 B305", withTeacherContent.text)
        assertFalse(withTeacherContent.text.contains(" · "))

        val withoutTeacher = withTeacher.copy(occurrenceId = "occ-line-no-teacher", teacher = "")
        val withoutTeacherContent = ReminderNotifications.content(
            withoutTeacher,
            ReminderPhase.IN_CLASS,
            index = 3,
            total = 5,
        )
        assertEquals("$timeRange\n知行楼 B305", withoutTeacherContent.text)
    }

    @Test
    fun `进度条整分对齐刷新与精确闹钟降级正确`() {
        val base = 1_770_000_000_000L
        val endWith13s = base + 24 * MINUTE + 13_000L
        assertEquals(endWith13s - 24 * MINUTE + 250L, ReminderNotifications.nextProgressTickAt(endWith13s, base))
        val endExactMin = base + 24 * MINUTE
        assertEquals(endExactMin - 23 * MINUTE + 250L, ReminderNotifications.nextProgressTickAt(endExactMin, base))
        assertNull(ReminderNotifications.nextProgressTickAt(base + 45_000L, base))

        val item = entry(
            occurrenceId = "occ-countdown-tick",
            startAt = base - 30 * MINUTE,
            endAt = endWith13s,
        ).copy(ongoingProgress = true)
        val alarms = context.getSystemService(AlarmManager::class.java)
        val expectedTriggerAt = ReminderNotifications.nextProgressTickAt(endWith13s, base)!!

        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ReminderNotifications.scheduleProgressTickIfNeeded(context, item, ReminderPhase.IN_CLASS, base, OWNER)
        val exactAlarm = Shadows.shadowOf(alarms).scheduledAlarms.last()
        assertEquals(expectedTriggerAt, exactAlarm.triggerAtTime)
        assertEquals(ShadowAlarmManager.WINDOW_EXACT, exactAlarm.windowLengthMs)

        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        ReminderNotifications.scheduleProgressTickIfNeeded(context, item, ReminderPhase.IN_CLASS, base, OWNER)
        val inexactAlarm = Shadows.shadowOf(alarms).scheduledAlarms.last()
        assertEquals(expectedTriggerAt, inexactAlarm.triggerAtTime)
        assertFalse(inexactAlarm.windowLengthMs == ShadowAlarmManager.WINDOW_EXACT)
    }
}
