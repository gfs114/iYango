package com.gfs.iyango.platform.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.gfs.iyango.core.domain.GradeChangeKind
import com.gfs.iyango.core.domain.GradeChangeNotificationPayload
import com.gfs.iyango.core.domain.GradeCheckSchedulerPort
import com.gfs.iyango.core.domain.GradeCheckTrigger
import com.gfs.iyango.core.domain.GradeGpaDeltaSummary
import com.gfs.iyango.core.domain.GradeMonitorCoordinatorPort
import com.gfs.iyango.core.domain.GradeNotificationPort
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 新出分灵动胶囊渲染文案（纯函数构造，便于单测验证）。 */
internal data class GradeCapsuleContent(
    val title: String,
    val text: String,
    val bigText: String,
    val subText: String,
    val shortCritical: String,
    val publicTitle: String,
    val publicText: String,
    val publicSubText: String,
    val publicShortCritical: String,
)

internal data class ActiveGradeNotification(
    val payload: GradeChangeNotificationPayload,
    val hideScoreOnLockscreen: Boolean,
)

/**
 * 新出分系统通知与灵动胶囊平台实现（`platform:android`）。
 *
 * - 独立通知渠道 `grade_updates_live`（名称“新出分通知”，`IMPORTANCE_HIGH`），自动清理旧 `grade_updates` 渠道；
 * - Android 16+（API 36）提升为 Live Update / 荣耀灵动胶囊，胶囊短文本展示课程名、展开两行展示明细与均绩；
 * - 支持锁屏状态检测与锁屏/解锁动态刷新：锁屏时仅显示课程名隐藏分数与均绩，解锁离开锁屏后自动静默恢复分数详情；
 * - 点击通知或胶囊直达主界面「成绩」页签（Tab 2）并自动消隐，点「知道了」/划掉同步清理。
 */
@Singleton
class AndroidGradeNotificationPlatform @Inject constructor(
    @ApplicationContext private val context: Context,
) : GradeNotificationPort {

    override fun notificationsAllowed(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    override fun liveUpdatesAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.canPostPromotedNotifications()
    }

    override fun showGradeChange(
        payload: GradeChangeNotificationPayload,
        hideScoreOnLockscreen: Boolean,
    ): Boolean {
        ensureChannel()
        if (!notificationsAllowed()) return false

        val notificationId = notificationIdFor(payload.accountKey)
        val onLockscreen = isOnLockscreen(context)
        val posted = postNotification(
            context = context,
            notificationId = notificationId,
            payload = payload,
            hideScoreOnLockscreen = hideScoreOnLockscreen,
            onLockscreen = onLockscreen,
            silentUpdate = false,
        )
        if (posted) {
            if (hideScoreOnLockscreen) {
                activeNotifications[notificationId] = ActiveGradeNotification(payload, true)
                registerLockscreenReceiverIfNeeded(context.applicationContext ?: context)
            } else {
                activeNotifications.remove(notificationId)
                unregisterLockscreenReceiverIfEmpty()
            }
        }
        return posted
    }

    override fun cancelForAccount(accountKey: String) {
        if (accountKey.isBlank()) return
        dismissNotification(context, notificationIdFor(accountKey))
    }

    override fun cancelAll() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.activeNotifications
            ?.filter { it.id in NOTIFICATION_ID_BASE..(NOTIFICATION_ID_BASE + NOTIFICATION_ID_MASK) }
            ?.forEach { manager.cancel(it.id) }
        activeNotifications.clear()
        unregisterLockscreenReceiverIfEmpty()
    }

    internal fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(LEGACY_CHANNEL_ID) != null) {
            manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        }
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "新出分通知",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "发现新出分时在通知栏与灵动胶囊提醒成绩及绩点变化"
            },
        )
    }

    companion object {
        const val CHANNEL_ID = "grade_updates_live"
        private const val LEGACY_CHANNEL_ID = "grade_updates"
        const val GRADES_TAB_INDEX = 2
        const val ACTION_DISMISS = "com.gfs.iyango.action.DISMISS_GRADE_NOTIFICATION"
        const val EXTRA_NOTIFICATION_ID = "com.gfs.iyango.extra.GRADE_NOTIFICATION_ID"
        private const val NOTIFICATION_ID_BASE = 240_000
        private const val NOTIFICATION_ID_MASK = 0x0FFF
        private const val DISMISS_REQUEST_SALT = 0x5A5A
        private const val ACCENT_COLOR = 0xFF2563EB.toInt()
        private const val LIVE_CAPSULE_TIMEOUT_MILLIS = 2L * 60L * 60L * 1000L
        private const val MAX_COURSE_LINE_ITEMS = 3

        private val activeNotifications = ConcurrentHashMap<Int, ActiveGradeNotification>()
        private val receiverLock = Any()

        @Volatile
        private var lockscreenReceiver: BroadcastReceiver? = null

        @Volatile
        private var receiverAppContext: Context? = null

        fun notificationIdFor(accountKey: String): Int =
            NOTIFICATION_ID_BASE + (accountKey.hashCode() and NOTIFICATION_ID_MASK)

        fun dismissIntent(context: Context, notificationId: Int): Intent =
            Intent(context, GradeNotificationDismissReceiver::class.java).apply {
                action = ACTION_DISMISS
                data = Uri.parse("iyango://grade-notification/dismiss/$notificationId")
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            }

        internal fun isOnLockscreen(context: Context): Boolean {
            val keyguard = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
            val screenOff = context.getSystemService(PowerManager::class.java)?.isInteractive == false
            return keyguard || screenOff
        }

        fun dismissNotification(context: Context, notificationId: Int) {
            activeNotifications.remove(notificationId)
            NotificationManagerCompat.from(context).cancel(notificationId)
            unregisterLockscreenReceiverIfEmpty()
        }

        private fun registerLockscreenReceiverIfNeeded(appContext: Context) {
            synchronized(receiverLock) {
                if (lockscreenReceiver != null) return
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        when (intent.action) {
                            Intent.ACTION_SCREEN_OFF ->
                                refreshActiveForLockState(appContext, onLockscreen = true)
                            Intent.ACTION_SCREEN_ON ->
                                refreshActiveForLockState(appContext, onLockscreen = isOnLockscreen(appContext))
                            Intent.ACTION_USER_PRESENT ->
                                refreshActiveForLockState(appContext, onLockscreen = false)
                        }
                    }
                }
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
                runCatching {
                    ContextCompat.registerReceiver(
                        appContext,
                        receiver,
                        filter,
                        ContextCompat.RECEIVER_NOT_EXPORTED,
                    )
                    lockscreenReceiver = receiver
                    receiverAppContext = appContext
                }
            }
        }

        private fun unregisterLockscreenReceiverIfEmpty() {
            synchronized(receiverLock) {
                if (activeNotifications.isNotEmpty()) return
                val receiver = lockscreenReceiver ?: return
                val appContext = receiverAppContext
                lockscreenReceiver = null
                receiverAppContext = null
                runCatching { appContext?.unregisterReceiver(receiver) }
            }
        }

        internal fun refreshActiveForLockState(context: Context, onLockscreen: Boolean) {
            if (activeNotifications.isEmpty()) {
                unregisterLockscreenReceiverIfEmpty()
                return
            }
            val activeIds = context.getSystemService(NotificationManager::class.java)
                ?.activeNotifications
                ?.map { it.id }
                ?.toSet()
            for ((id, entry) in activeNotifications.entries.toList()) {
                if (activeIds != null && id !in activeIds) {
                    activeNotifications.remove(id)
                    continue
                }
                if (!entry.hideScoreOnLockscreen) continue
                postNotification(
                    context = context,
                    notificationId = id,
                    payload = entry.payload,
                    hideScoreOnLockscreen = true,
                    onLockscreen = onLockscreen,
                    silentUpdate = true,
                )
            }
            unregisterLockscreenReceiverIfEmpty()
        }

        @SuppressLint("MissingPermission")
        private fun postNotification(
            context: Context,
            notificationId: Int,
            payload: GradeChangeNotificationPayload,
            hideScoreOnLockscreen: Boolean,
            onLockscreen: Boolean,
            silentUpdate: Boolean,
        ): Boolean {
            val content = buildCapsuleContent(
                payload = payload,
                redactContent = hideScoreOnLockscreen && onLockscreen,
            )
            val contentIntent = LaunchIntents.launchIntent(context, tab = GRADES_TAB_INDEX)?.let { intent ->
                intent.putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                PendingIntent.getActivity(
                    context,
                    notificationId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
            val dismissPending = PendingIntent.getBroadcast(
                context,
                notificationId xor DISMISS_REQUEST_SALT,
                dismissIntent(context, notificationId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_exam)
                .setContentTitle(content.title)
                .setContentText(content.text)
                .setSubText(content.subText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content.bigText))
                .setColor(ACCENT_COLOR)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setShowWhen(true)
                .setOnlyAlertOnce(true)
                .setSilent(silentUpdate)
                .addAction(0, "知道了", dismissPending)
                .setDeleteIntent(dismissPending)
                .apply { if (contentIntent != null) setContentIntent(contentIntent) }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                builder
                    .setShortCriticalText(content.shortCritical)
                    .setOngoing(true)
                    .setRequestPromotedOngoing(true)
                    .setAutoCancel(false)
                    .setTimeoutAfter(LIVE_CAPSULE_TIMEOUT_MILLIS)
            } else {
                builder
                    .setOngoing(false)
                    .setAutoCancel(true)
            }

            if (hideScoreOnLockscreen) {
                val publicBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_exam)
                    .setContentTitle(content.publicTitle)
                    .setContentText(content.publicText)
                    .setSubText(content.publicSubText)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(content.publicText))
                    .setColor(ACCENT_COLOR)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setSilent(silentUpdate)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                    publicBuilder
                        .setShortCriticalText(content.publicShortCritical)
                        .setOngoing(true)
                        .setRequestPromotedOngoing(true)
                } else {
                    publicBuilder.setAutoCancel(true)
                }
                builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                builder.setPublicVersion(publicBuilder.build())
            } else {
                builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            }

            return runCatching {
                NotificationManagerCompat.from(context)
                    .notify(notificationId, builder.build())
                true
            }.getOrDefault(false)
        }

        internal fun buildCapsuleContent(
            payload: GradeChangeNotificationPayload,
            redactContent: Boolean,
        ): GradeCapsuleContent {
            val courseNamesLine = formatCourseLine(payload, includeScores = false)
            val courseWithScoresLine = formatCourseLine(payload, includeScores = true)
            val gpaLine = formatGpaSummaryLine(payload.gpaSummary)
            val allNew = payload.items.all { it.kind == GradeChangeKind.NEWLY_PUBLISHED }
            val subText = if (allNew) "新出分" else "成绩更新"
            val countBadge = if (allNew) {
                "新增 ${payload.items.size} 门"
            } else {
                "更新 ${payload.items.size} 门"
            }
            val firstName = payload.items.firstOrNull()?.record?.courseName?.trim().orEmpty()
            val shortCritical = when {
                firstName.isEmpty() -> countBadge
                payload.items.size == 1 -> firstName.take(7)
                else -> {
                    val tail = "等${payload.items.size}门"
                    val maxPrefix = (7 - tail.length).coerceAtLeast(1)
                    "${firstName.take(maxPrefix)}$tail"
                }
            }
            val text = if (redactContent) courseNamesLine else courseWithScoresLine
            val bigText = if (redactContent) {
                courseNamesLine
            } else {
                listOfNotNull(courseWithScoresLine, gpaLine).joinToString("\n")
            }
            return GradeCapsuleContent(
                title = payload.summaryTitle,
                text = text,
                bigText = bigText,
                subText = subText,
                shortCritical = shortCritical,
                publicTitle = payload.summaryTitle,
                publicText = courseNamesLine,
                publicSubText = subText,
                publicShortCritical = shortCritical,
            )
        }

        private fun formatCourseLine(
            payload: GradeChangeNotificationPayload,
            includeScores: Boolean,
        ): String {
            if (payload.items.isEmpty()) return payload.summaryTitle
            val joined = payload.items.take(MAX_COURSE_LINE_ITEMS).joinToString("、") { item ->
                val name = item.record.courseName.ifBlank { "未命名课程" }
                if (!includeScores) {
                    name
                } else {
                    val score = item.record.scoreText.trim()
                    if (item.kind == GradeChangeKind.SCORE_UPDATED) {
                        "$name ${item.previousScoreText} → $score"
                    } else {
                        "$name $score"
                    }
                }
            }
            return if (payload.items.size > MAX_COURSE_LINE_ITEMS) {
                "$joined 等 ${payload.items.size} 门"
            } else {
                joined
            }
        }

        private fun formatGpaSummaryLine(gpa: GradeGpaDeltaSummary?): String? {
            if (gpa == null) return null
            val after = gpa.afterTermGpa
            val delta = gpa.deltaTermGpa
            val cumulative = gpa.afterCumulativeGpa
            return when {
                after != null -> buildString {
                    append("学期均绩 ${formatGpa(after)}")
                    if (delta != null) {
                        append(" (${formatDelta(delta)})")
                    }
                    if (cumulative != null && abs(cumulative - after) >= 0.005) {
                        append(" 总 ${formatGpa(cumulative)}")
                    }
                }
                cumulative != null -> "总均绩 ${formatGpa(cumulative)}"
                else -> null
            }
        }

        private fun formatGpa(value: Double): String = String.format(Locale.US, "%.2f", value)

        private fun formatDelta(delta: Double): String =
            if (delta >= 0.0) String.format(Locale.US, "+%.2f", delta)
            else String.format(Locale.US, "%.2f", delta)
    }
}

/** 处理新出分通知或灵动胶囊的“知道了”/划掉取消广播（不依赖 Hilt 注入）。 */
class GradeNotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidGradeNotificationPlatform.ACTION_DISMISS) return
        val id = intent.getIntExtra(AndroidGradeNotificationPlatform.EXTRA_NOTIFICATION_ID, 0)
        if (id != 0) {
            AndroidGradeNotificationPlatform.dismissNotification(context, id)
        }
    }
}

/**
 * 后台低频非精确闹钟与场景触发调度器：使用 `setAndAllowWhileIdle` 对齐系统维护窗口，
 * 并按设置启用 Wi-Fi 连入 / 充电连接顺手检查。
 */
@Singleton
class AndroidGradeCheckScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) : GradeCheckSchedulerPort {

    private val triggerLock = Any()

    @Volatile
    private var contextTriggersRegistered = false

    @Volatile
    private var wifiRegisteredAtMillis = 0L

    @Volatile
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var powerReceiver: BroadcastReceiver? = null

    override fun scheduleNext(delayMillis: Long) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val triggerAt = clock.millis() + delayMillis.coerceAtLeast(MIN_DELAY_MILLIS)
        runCatching {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    override fun cancel() {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            checkIntent(context, GradeCheckTrigger.BACKGROUND_PERIODIC),
            flags,
        )
        if (pending != null) {
            alarm?.cancel(pending)
            pending.cancel()
        }
        setContextTriggersActive(false)
    }

    override fun hasNetwork(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun isOnWifi(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    override fun setContextTriggersActive(enabled: Boolean) {
        val appContext = context.applicationContext ?: context
        synchronized(triggerLock) {
            if (!enabled) {
                if (!contextTriggersRegistered) return
                contextTriggersRegistered = false
                val cm = appContext.getSystemService(ConnectivityManager::class.java)
                wifiCallback?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
                wifiCallback = null
                powerReceiver?.let { receiver -> runCatching { appContext.unregisterReceiver(receiver) } }
                powerReceiver = null
                return
            }
            if (contextTriggersRegistered) return
            contextTriggersRegistered = true
            wifiRegisteredAtMillis = clock.millis()

            val cm = appContext.getSystemService(ConnectivityManager::class.java)
            if (cm != null) {
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (clock.millis() - wifiRegisteredAtMillis < INITIAL_WIFI_STICKY_GRACE_MS) {
                            return
                        }
                        runCatching {
                            appContext.sendBroadcast(checkIntent(appContext, GradeCheckTrigger.WIFI_CONNECTED))
                        }
                    }
                }
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                runCatching {
                    cm.registerNetworkCallback(request, callback)
                    wifiCallback = callback
                }
            }

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != Intent.ACTION_POWER_CONNECTED) return
                    runCatching {
                        appContext.sendBroadcast(checkIntent(appContext, GradeCheckTrigger.POWER_CONNECTED))
                    }
                }
            }
            runCatching {
                ContextCompat.registerReceiver(
                    appContext,
                    receiver,
                    IntentFilter(Intent.ACTION_POWER_CONNECTED),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                powerReceiver = receiver
            }
        }
    }

    private fun pendingIntent(flags: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            checkIntent(context, GradeCheckTrigger.BACKGROUND_PERIODIC),
            flags,
        )

    companion object {
        private const val REQUEST_CODE = 24_901
        private const val MIN_DELAY_MILLIS = 60_000L
        private const val INITIAL_WIFI_STICKY_GRACE_MS = 3_000L
        const val EXTRA_TRIGGER = "com.gfs.iyango.extra.GRADE_CHECK_TRIGGER"

        fun checkIntent(
            context: Context,
            trigger: GradeCheckTrigger = GradeCheckTrigger.BACKGROUND_PERIODIC,
        ): Intent = Intent(context, GradeCheckReceiver::class.java).apply {
            action = "com.gfs.iyango.action.CHECK_GRADES"
            setPackage(context.packageName)
            putExtra(EXTRA_TRIGGER, trigger.name)
        }
    }
}

/** 后台周期与场景顺手检查广播接收器。 */
@AndroidEntryPoint
class GradeCheckReceiver : BroadcastReceiver() {

    @Inject
    lateinit var gradeMonitorCoordinator: GradeMonitorCoordinatorPort

    override fun onReceive(context: Context, intent: Intent) {
        val raw = intent.getStringExtra(AndroidGradeCheckScheduler.EXTRA_TRIGGER)
        val trigger = when (GradeCheckTrigger.entries.firstOrNull { it.name == raw }) {
            GradeCheckTrigger.WIFI_CONNECTED -> GradeCheckTrigger.WIFI_CONNECTED
            GradeCheckTrigger.POWER_CONNECTED -> GradeCheckTrigger.POWER_CONNECTED
            else -> GradeCheckTrigger.BACKGROUND_PERIODIC
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runCatching {
                    gradeMonitorCoordinator.checkOnTrigger(trigger)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
