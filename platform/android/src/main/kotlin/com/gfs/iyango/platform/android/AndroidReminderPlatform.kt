package com.gfs.iyango.platform.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.gfs.iyango.core.domain.ReminderPlanItem
import com.gfs.iyango.core.domain.ReminderPlatformPort
import com.gfs.iyango.core.domain.ReminderScheduleResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 通知跳转携带的附加字段（MainActivity 读取）。 */
object ReminderIntents {
    const val EXTRA_COURSE_ID = "com.gfs.iyango.extra.COURSE_ID"
    const val EXTRA_OCCURRENCE_ID = "com.gfs.iyango.extra.OCCURRENCE_ID"
    const val EXTRA_DATE = "com.gfs.iyango.extra.DATE"
}

/** 单条已排程提醒的持久快照（Receiver 复核用）。 */
internal data class StoredReminder(
    val occurrenceId: String,
    val triggerAtMillis: Long,
    val payloadHash: String,
    val planRevision: Int,
    val title: String,
    val body: String,
    val courseId: Long,
    val date: String,
    val startMinute: Int?,
    /** 开始时刻（epoch 毫秒）；旧版本快照缺失时为 0，渲染时退化为旧版静态展示。 */
    val startAtMillis: Long = 0,
    /** 结束时刻（epoch 毫秒）；null 表示未知（无下课/结束时间）。 */
    val endAtMillis: Long? = null,
    /** 课程/考试名称，用于分阶段生成标题。 */
    val courseName: String = "",
    /** 任课老师（考试为空），用于三行文案正文。 */
    val teacher: String = "",
    /** 教室/考场，用于"上课中"正文。 */
    val classroom: String = "",
    /** 是否考试提醒（occurrenceId 以 exam: 开头）。 */
    val isExam: Boolean = false,
    /** 卡片/胶囊强调色（#RRGGBB）；空串表示未知，渲染时不设色。旧快照缺失时为空。 */
    val accentColor: String = "",
    /** 当天第几节（1 起算，由 planner 按当天有效节次计算）；0 表示未知。 */
    val dayIndex: Int = 0,
    /** 当天有效节次总数（时间待定/停用不计入）；0 表示未知。 */
    val dayTotal: Int = 0,
    /** 是否开启常驻实时进度条与定时刷新。 */
    val ongoingProgress: Boolean = false,
    /** 跨教学楼赶课预警文案（空表示无预警）。 */
    val transferWarning: String = "",
)

/** 提醒计划的平台私有存储（SharedPreferences；不进入设置备份）。 */
internal class ReminderPlanStore(context: Context) {

    private val prefs = context.getSharedPreferences("reminder_plan", Context.MODE_PRIVATE)

    fun load(ownerId: String): Map<String, StoredReminder> {
        val text = prefs.getString(key(ownerId), null) ?: return emptyMap()
        return runCatching {
            val array = JSONArray(text)
            buildMap {
                for (index in 0 until array.length()) {
                    val obj = array.getJSONObject(index)
                    val item = StoredReminder(
                        occurrenceId = obj.getString("occurrenceId"),
                        triggerAtMillis = obj.getLong("triggerAtMillis"),
                        payloadHash = obj.optString("payloadHash"),
                        planRevision = obj.optInt("planRevision"),
                        title = obj.optString("title"),
                        body = obj.optString("body"),
                        courseId = obj.optLong("courseId"),
                        date = obj.optString("date"),
                        startMinute = if (obj.has("startMinute")) obj.getInt("startMinute") else null,
                        // 旧版本快照没有这些字段：给默认值，缺开始时刻时按旧版静态展示渲染。
                        startAtMillis = obj.optLong("startAtMillis"),
                        endAtMillis =
                            if (obj.has("endAtMillis") && !obj.isNull("endAtMillis")) {
                                obj.getLong("endAtMillis")
                            } else {
                                null
                            },
                        courseName = obj.optString("courseName"),
                        teacher = obj.optString("teacher"),
                        classroom = obj.optString("classroom"),
                        isExam = obj.optBoolean("isExam"),
                        accentColor = obj.optString("accentColor"),
                        dayIndex = obj.optInt("dayIndex"),
                        dayTotal = obj.optInt("dayTotal"),
                        ongoingProgress = obj.optBoolean("ongoingProgress", false),
                        transferWarning = obj.optString("transferWarning"),
                    )
                    put(item.occurrenceId, item)
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun save(ownerId: String, items: List<StoredReminder>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("occurrenceId", item.occurrenceId)
                    put("triggerAtMillis", item.triggerAtMillis)
                    put("payloadHash", item.payloadHash)
                    put("planRevision", item.planRevision)
                    put("title", item.title)
                    put("body", item.body)
                    put("courseId", item.courseId)
                    put("date", item.date)
                    item.startMinute?.let { put("startMinute", it) }
                    put("startAtMillis", item.startAtMillis)
                    item.endAtMillis?.let { put("endAtMillis", it) }
                    put("courseName", item.courseName)
                    put("teacher", item.teacher)
                    put("classroom", item.classroom)
                    put("isExam", item.isExam)
                    put("accentColor", item.accentColor)
                    put("dayIndex", item.dayIndex)
                    put("dayTotal", item.dayTotal)
                    put("ongoingProgress", item.ongoingProgress)
                    if (item.transferWarning.isNotEmpty()) put("transferWarning", item.transferWarning)
                },
            )
        }
        prefs.edit().putString(key(ownerId), array.toString()).apply()
    }

    fun toStored(item: ReminderPlanItem): StoredReminder = StoredReminder(
        occurrenceId = item.occurrenceId,
        triggerAtMillis = item.triggerAtMillis,
        payloadHash = item.payloadHash,
        planRevision = item.planRevision,
        title = item.title,
        body = item.body,
        courseId = item.courseId,
        date = item.occurrenceDate.toString(),
        startMinute = item.startMinute,
        startAtMillis = item.startAtMillis,
        endAtMillis = item.endAtMillis,
        courseName = item.courseName,
        teacher = item.teacher,
        classroom = item.classroom,
        isExam = item.occurrenceId.startsWith(EXAM_ID_PREFIX),
        accentColor = item.accentColor,
        dayIndex = item.dayIndex,
        dayTotal = item.dayTotal,
        ongoingProgress = item.ongoingProgress,
        transferWarning = item.transferWarning,
    )

    /** 记录"知道了"：该实例的后续触发不再展示。 */
    fun addDismissed(ownerId: String, occurrenceId: String) {
        val current = dismissed(ownerId).toMutableSet()
        if (current.add(occurrenceId)) {
            prefs.edit().putStringSet(dismissedKey(ownerId), current).apply()
        }
    }

    fun isDismissed(ownerId: String, occurrenceId: String): Boolean =
        occurrenceId in dismissed(ownerId)

    /** 只保留仍在当前计划中的已关闭项，避免集合无限增长。 */
    fun retainDismissed(ownerId: String, keep: Set<String>) {
        val current = dismissed(ownerId)
        val retained = current.filterTo(mutableSetOf()) { it in keep }
        if (retained.size != current.size) {
            prefs.edit().putStringSet(dismissedKey(ownerId), retained).apply()
        }
    }

    /** 清空已关闭集合（提醒全部取消时调用）。 */
    fun clearDismissed(ownerId: String) {
        prefs.edit().remove(dismissedKey(ownerId)).apply()
    }

    private fun dismissed(ownerId: String): Set<String> =
        prefs.getStringSet(dismissedKey(ownerId), emptySet()) ?: emptySet()

    private fun dismissedKey(ownerId: String) = "dismissed_$ownerId"

    private fun key(ownerId: String) = "plan_$ownerId"

    companion object {
        /** 考试实例的 occurrenceId 前缀（与 ExamReminderPlanner 保持一致）。 */
        const val EXAM_ID_PREFIX = "exam:"
    }
}

/** 提醒展示阶段；由当前时刻判定，与触发点先后无关（迟到/乱序触发也能渲染出正确阶段）。 */
internal enum class ReminderPhase {
    /** 课前：倒计时到开始时刻。 */
    BEFORE_START,

    /** 课中：倒计时到结束时刻。 */
    IN_CLASS,

    /** 已开始但无结束时间：保持课前文案并标注已开始，不倒计时。 */
    STARTED_NO_END,

    /** 已结束：不再展示。 */
    ENDED,

    /** 旧版本快照（缺开始时刻）：保持旧版静态展示。 */
    LEGACY,
}

/** 通知渠道与展示（Receiver 与排程器共用，渠道创建幂等）。 */
internal object ReminderNotifications {

    const val CHANNEL_ID = "course_reminders"

    /** 无结束时间时，从开始时刻起保留通知的窗口。 */
    private const val STARTED_WINDOW_MILLIS = 3 * 60 * 1000L

    /** 常驻进度与倒计时圆环的刷新步长（1 分钟）。 */
    private const val PROGRESS_TICK_MILLIS = 60 * 1000L

    /** 对齐整分后额外留出的触发余量（避免闹钟提前几十毫秒唤醒导致仍算上一分钟）。 */
    private const val PROGRESS_TICK_MARGIN_MILLIS = 250L

    internal const val PREVIEW_ID_PREFIX = "preview:"

    private val endClockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun allowed(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "课程提醒",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "上课前提醒课程、教室与开始时间"
            },
        )
    }

    /** 依据当前时刻解析展示阶段；旧版快照（无开始时刻）退化为静态展示。 */
    fun resolvePhase(entry: StoredReminder, now: Long): ReminderPhase {
        if (entry.startAtMillis <= 0L) return ReminderPhase.LEGACY
        if (now < entry.startAtMillis) return ReminderPhase.BEFORE_START
        val endAt = entry.endAtMillis
        if (endAt != null) {
            return if (now < endAt) ReminderPhase.IN_CLASS else ReminderPhase.ENDED
        }
        // 无结束时间：开始后保留 3 分钟，保持课前文案。
        return if (now < entry.startAtMillis + STARTED_WINDOW_MILLIS) {
            ReminderPhase.STARTED_NO_END
        } else {
            ReminderPhase.ENDED
        }
    }

    /** 已进入提醒窗口、尚未结束的有效实例需要立即展示或恢复通知。 */
    fun inFlight(entry: StoredReminder, now: Long): Boolean =
        entry.startAtMillis > 0L && now >= entry.triggerAtMillis && now < deleteAtOf(entry)

    /** 通知的自动删除时刻：有结束时间用结束时刻，否则为开始后 3 分钟。 */
    private fun deleteAtOf(entry: StoredReminder): Long =
        entry.endAtMillis ?: (entry.startAtMillis + STARTED_WINDOW_MILLIS)

    // 权限由 allowed() 在调用前检查，且通知权限可能在检查后被撤销。
    @SuppressLint("MissingPermission")
    fun render(context: Context, entry: StoredReminder, now: Long, ownerId: String) {
        val isPreview = entry.occurrenceId.startsWith(PREVIEW_ID_PREFIX)
        // 已关闭（点过"知道了"/划掉）的实例不再展示；ownerId 参与已关闭集合的隔离。
        if (!isPreview && ReminderPlanStore(context).isDismissed(ownerId, entry.occurrenceId)) return
        ensureChannel(context)
        if (!allowed(context)) return
        val manager = NotificationManagerCompat.from(context)
        val notificationId = requestCode(entry.occurrenceId)
        val phase = resolvePhase(entry, now)
        if (phase == ReminderPhase.ENDED) {
            // 已下课/已结束：通知自动消失，并清理进度刷新闹钟。
            cancelProgressTick(context, entry.occurrenceId)
            manager.cancel(notificationId)
            return
        }
        if (phase == ReminderPhase.LEGACY) {
            runCatching { manager.notify(notificationId, legacyNotification(context, entry)) }
            return
        }
        // 课中但缺结束时刻：无法构造课中文案与倒计时，放弃本次渲染（后续触发再算）。
        if (phase == ReminderPhase.IN_CLASS && entry.endAtMillis == null) return
        // 当天上下文：预告行与「第 x/y 节」。节次以 planner 写入快照的 dayIndex/dayTotal
        // 为准（快照推导值受「已过期实例被清出计划」影响），旧快照缺失时退回快照推导。
        val planStore = ReminderPlanStore(context)
        val ctx = dayContext(planStore.load(ownerId).values, entry)
        val index = entry.dayIndex.takeIf { it > 0 } ?: ctx.index
        val total = entry.dayTotal.takeIf { it > 0 } ?: ctx.total
        val progress = if (entry.ongoingProgress) computeProgress(entry, phase, now) else null
        val c = content(entry, phase, index, total)
        // "知道了"按钮与划掉通知共用同一 PendingIntent：两者都写入已关闭集合并取消通知。
        val dismissPending = PendingIntent.getBroadcast(
            context,
            (entry.occurrenceId + "#dismiss").hashCode(),
            ReminderIntentsInternal.dismissIntent(context, ownerId, entry.occurrenceId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            // 专属状态栏图标（单色矢量）：课程用书本、考试用试卷。
            .setSmallIcon(if (entry.isExam) R.drawable.ic_stat_exam else R.drawable.ic_stat_course)
            .setContentTitle(c.title)
            .setContentText(c.text)
            .setSubText(c.subText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(c.bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // 课程用 REMINDER、考试用 EVENT。
            .setCategory(
                if (entry.isExam) NotificationCompat.CATEGORY_EVENT else NotificationCompat.CATEGORY_REMINDER,
            )
            // 同一实例原地更新，不重复响铃。
            .setOnlyAlertOnce(true)
            .addAction(0, "知道了", dismissPending)
            .setDeleteIntent(dismissPending)
            .apply { contentIntentOf(context, entry)?.let { setContentIntent(it) } }
        // 课程强调色（荣耀胶囊规范：高亮色对比度 >= 4.5:1）；解析失败不设色，保持系统默认。
        parseColorOrNull(entry.accentColor)?.let { builder.setColor(it) }
        if (c.shortCritical != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            // 课前胶囊短文本展示教室（或阶段词）；课中为 null，让位给系统原生实时下课倒计时。
            builder.setShortCriticalText(c.shortCritical)
        }
        val countdownTarget = when (phase) {
            ReminderPhase.IN_CLASS -> entry.endAtMillis ?: 0L
            ReminderPhase.BEFORE_START -> entry.startAtMillis
            else -> 0L
        }
        if (countdownTarget > 0L) {
            // 系统倒计时到目标时刻；计秒滚动由系统驱动（荣耀规范：计秒更新不视为节点刷新）。
            builder.setWhen(countdownTarget)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
        }
        val useOngoingProgress = entry.ongoingProgress &&
            (phase == ReminderPhase.BEFORE_START || phase == ReminderPhase.IN_CLASS)
        if (useOngoingProgress && progress != null) {
            val resolvedAccent = parseColorOrNull(entry.accentColor) ?: 0xFF3B82F6.toInt()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                builder.setStyle(buildLiveProgressStyle(context, entry, progress, resolvedAccent))
            } else {
                builder.setProgress(100, progress, false)
            }
        }
        // 到删除时刻（下课，或无结束时间时开始后 3 分钟）通知自动消失。
        val remain = deleteAtOf(entry) - now
        if (remain > 0L) builder.setTimeoutAfter(remain)
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA -> {
                // Android 16+：升级为 Live Update，展示在状态栏胶囊、锁屏与通知栏顶部。
                builder.setOngoing(true).setRequestPromotedOngoing(true)
            }
            useOngoingProgress -> {
                builder.setOngoing(true).setAutoCancel(false)
            }
            else -> {
                builder.setOngoing(false).setAutoCancel(true)
            }
        }
        runCatching { manager.notify(notificationId, builder.build()) }
        if (useOngoingProgress && !isPreview) {
            scheduleProgressTickIfNeeded(context, entry, phase, now, ownerId)
        } else {
            cancelProgressTick(context, entry.occurrenceId)
        }
    }

    internal fun computeProgress(entry: StoredReminder, phase: ReminderPhase, now: Long): Int? = when (phase) {
        ReminderPhase.BEFORE_START -> {
            val total = entry.startAtMillis - entry.triggerAtMillis
            if (total > 0L) (((now - entry.triggerAtMillis) * 100L) / total).toInt().coerceIn(0, 100) else null
        }
        ReminderPhase.IN_CLASS -> {
            val endAt = entry.endAtMillis
            if (endAt != null && endAt > entry.startAtMillis) {
                (((now - entry.startAtMillis) * 100L) / (endAt - entry.startAtMillis)).toInt().coerceIn(0, 100)
            } else {
                null
            }
        }
        else -> null
    }

    internal fun buildLiveProgressStyle(
        context: Context,
        entry: StoredReminder,
        progress: Int,
        accentColor: Int,
    ): NotificationCompat.ProgressStyle {
        val opaqueAccent = accentColor or 0xFF000000.toInt()
        val trackerIcon = IconCompat.createWithResource(
            context,
            if (entry.isExam) R.drawable.ic_stat_exam else R.drawable.ic_stat_course,
        )
        return NotificationCompat.ProgressStyle()
            .setProgress(progress.coerceIn(0, 100))
            .setStyledByProgress(true)
            .setProgressTrackerIcon(trackerIcon)
            .setProgressSegments(
                listOf(
                    NotificationCompat.ProgressStyle.Segment(100).setColor(opaqueAccent),
                ),
            )
    }

    internal fun nextProgressTickAt(phaseEnd: Long, now: Long): Long? {
        val remain = phaseEnd - now
        if (remain <= PROGRESS_TICK_MILLIS) return null
        val nextFullMinutes = (remain - 1L) / PROGRESS_TICK_MILLIS
        val aligned = phaseEnd - nextFullMinutes * PROGRESS_TICK_MILLIS + PROGRESS_TICK_MARGIN_MILLIS
        val nextTick = maxOf(aligned, now + 1_000L)
        return nextTick.takeIf { it < phaseEnd }
    }

    internal fun scheduleProgressTickIfNeeded(
        context: Context,
        entry: StoredReminder,
        phase: ReminderPhase,
        now: Long,
        ownerId: String,
    ) {
        val phaseEnd = when (phase) {
            ReminderPhase.BEFORE_START -> entry.startAtMillis
            ReminderPhase.IN_CLASS -> entry.endAtMillis ?: return
            else -> return
        }
        val nextTick = nextProgressTickAt(phaseEnd, now) ?: return
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            ReminderIntentsInternal.requestCode(entry.occurrenceId, ReminderIntentsInternal.PHASE_PROGRESS),
            ReminderIntentsInternal.intent(context, entry, ownerId, ReminderIntentsInternal.PHASE_PROGRESS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        runCatching {
            if (exactAllowed) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTick, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTick, pending)
            }
        }
    }

    internal fun cancelProgressTick(context: Context, occurrenceId: String) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            ReminderIntentsInternal.requestCode(occurrenceId, ReminderIntentsInternal.PHASE_PROGRESS),
            Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderIntentsInternal.ACTION
                data = Uri.parse("iyango://reminder/${Uri.encode(occurrenceId)}/${ReminderIntentsInternal.PHASE_PROGRESS}")
            },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pending != null) {
            manager?.cancel(pending)
            pending.cancel()
        }
    }

    /** 三行文案产物：title/text/bigText/subText 与可选的胶囊短文本，渲染与测试共用。 */
    internal data class ReminderContent(
        val title: String,
        val text: String,
        val bigText: String,
        val subText: String,
        /** 课前展示教室（缺省展示阶段词）；课中为 null，让位给系统原生实时下课倒计时。 */
        val shortCritical: String?,
    )

    /** 当天上下文：预告行用的「下一节」与（快照推导的）节次信息。 */
    internal data class DayContext(
        val next: StoredReminder?,
        val index: Int?,
        val total: Int?,
        /** 同一天、非考试、开始时刻已知的快照条目（按开始时刻排序）。 */
        val today: List<StoredReminder>,
    )

    /**
     * 按阶段生成文案（兼容荣耀灵动胶囊 2 行正文限制）：
     * title 只放课程名/科目；阶段词（及课程进度）放 subText（与系统倒计时同行头部）；
     * text 与 bigText 均只放地点与时刻（最多两行，不追加下一节预告行以避免截断省略号）。
     *
     * @param index 当天第几节（1 起算，来自 planner 写入快照的 dayIndex，0 表示未知传 null）。
     * @param total 当天有效课程总数（来自 planner 写入快照的 dayTotal，0 表示未知传 null）。
     */
    internal fun content(
        entry: StoredReminder,
        phase: ReminderPhase,
        index: Int?,
        total: Int?,
    ): ReminderContent {
        val name = entry.courseName.ifBlank { entry.title }
        val text = when (phase) {
            ReminderPhase.IN_CLASS -> {
                val startClock = clockText(entry.startAtMillis)
                val endClock = clockText(entry.endAtMillis ?: 0L)
                val timePart = if (entry.startAtMillis > 0L) {
                    "$startClock - $endClock"
                } else {
                    "$endClock${if (entry.isExam) " 结束" else " 下课"}"
                }
                when {
                    entry.teacher.isNotBlank() && entry.classroom.isNotBlank() ->
                        "${entry.teacher} $timePart\n${entry.classroom}"
                    entry.teacher.isNotBlank() -> "${entry.teacher} $timePart"
                    entry.classroom.isNotBlank() -> "$timePart\n${entry.classroom}"
                    else -> timePart
                }
            }

            else -> if (entry.isExam) {
                // 兼容旧快照的分隔符；正文只展示考场、座位和时刻。
                val prefix = if (entry.body.startsWith("$name · ")) "$name · " else "$name "
                entry.body.removePrefix(prefix).ifBlank { entry.body }
            } else {
                // planner 的 body 即「老师 HH:mm - HH:mm\n教室」。
                entry.body
            }
        }
        val displayText = text.replace(" · ", " ")
        val hasProgress = !entry.isExam && total != null && total > 0 && index != null && index > 0
        val subText = when (phase) {
            ReminderPhase.IN_CLASS -> when {
                entry.isExam -> "考试中"
                hasProgress -> "上课中 $index/$total"
                else -> "上课中"
            }

            ReminderPhase.STARTED_NO_END -> if (entry.isExam) "已开始考试" else "已开始上课"
            else -> when {
                entry.isExam -> "即将考试"
                hasProgress -> "即将上课 ${(index - 1).coerceAtLeast(0)}/$total"
                else -> "即将上课"
            }
        }
        return ReminderContent(
            title = name,
            text = displayText,
            bigText = displayText,
            subText = subText,
            // 课前展示教室（缺省展示阶段词）；课中返回 null，让位给系统原生秒级下课倒计时。
            shortCritical = when (phase) {
                ReminderPhase.IN_CLASS -> null
                ReminderPhase.STARTED_NO_END -> if (entry.isExam) "考试中" else "已上课"
                else ->
                    entry.classroom.ifBlank {
                        if (entry.isExam) "即将考试" else "即将上课"
                    }
            },
        )
    }

    /**
     * 从计划快照推导当天上下文：next 取开始时刻不早于本条结束时刻的第一条；
     * index/total 为快照口径（planner 写入的 dayIndex/dayTotal 优先于该推导值）。
     */
    internal fun dayContext(all: Collection<StoredReminder>, entry: StoredReminder): DayContext {
        val today = all
            .filter { it.date == entry.date && !it.isExam && it.startAtMillis > 0L }
            .sortedBy { it.startAtMillis }
        val position = today.indexOfFirst { it.occurrenceId == entry.occurrenceId }
        val next = today
            .asSequence()
            .filter { it.occurrenceId != entry.occurrenceId }
            .firstOrNull { it.startAtMillis >= (entry.endAtMillis ?: entry.startAtMillis) }
        return DayContext(
            next = next,
            index = (position + 1).takeIf { position >= 0 },
            total = today.size.takeIf { position >= 0 },
            today = today,
        )
    }

    /** 毫秒时刻的 HH:mm 展示（系统时区）。 */
    private fun clockText(millis: Long): String =
        endClockFormatter.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    /** 旧版本快照：没有开始/结束时刻，维持原有静态展示行为。 */
    private fun legacyNotification(context: Context, entry: StoredReminder): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            // 旧版快照统一使用课程图标（身份信息已缺失，不再区分考试）。
            .setSmallIcon(R.drawable.ic_stat_course)
            .setContentTitle(entry.title.replace(" · ", " "))
            .setContentText(entry.body.replace(" · ", " "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(entry.body.replace(" · ", " ")))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .apply { contentIntentOf(context, entry)?.let { setContentIntent(it) } }
            .build()

    /** 通知点击跳转：直接打开课程表页签（MainActivity 解析 EXTRA_TARGET_TAB）。 */
    private fun contentIntentOf(context: Context, entry: StoredReminder): PendingIntent? {
        val launch = LaunchIntents.launchIntent(context, tab = 1)
            ?.apply {
                putExtra(ReminderIntents.EXTRA_OCCURRENCE_ID, entry.occurrenceId)
                putExtra(ReminderIntents.EXTRA_DATE, entry.date)
            }
            ?: return null
        return PendingIntent.getActivity(
            context,
            requestCode(entry.occurrenceId),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun requestCode(occurrenceId: String): Int = occurrenceId.hashCode()

    /**
     * 解析 #RRGGBB 强调色；非法值返回 null（渲染时不设色，保持系统默认）。
     * 只接受 "#" 前缀的十六进制形式，避免把任意命名颜色当作合法输入。
     */
    internal fun parseColorOrNull(hex: String): Int? {
        if (!hex.startsWith("#")) return null
        return runCatching { Color.parseColor(hex) }.getOrNull()
    }
}

/** 提醒广播的稳定身份（action/data/requestCode 共同定位同一条 PendingIntent）。 */
internal object ReminderIntentsInternal {
    const val ACTION = "com.gfs.iyango.action.COURSE_REMINDER"

    /** 用户点"知道了"/划掉通知。 */
    const val ACTION_DISMISS = "com.gfs.iyango.action.DISMISS_REMINDER"
    const val EXTRA_OWNER = "ownerId"
    const val EXTRA_OCCURRENCE_ID = "occurrenceId"
    const val EXTRA_TRIGGER_AT = "triggerAt"
    const val EXTRA_PAYLOAD_HASH = "payloadHash"

    /** 触发阶段：pre=课前横幅，start=上课时刻切换为课中，progress=课前/课中进度条定时刷新。 */
    const val EXTRA_PHASE = "phase"
    const val PHASE_PRE = "pre"
    const val PHASE_START = "start"
    const val PHASE_PROGRESS = "progress"

    fun intent(context: Context, item: StoredReminder, ownerId: String, phase: String): Intent =
        Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION
            data = Uri.parse("iyango://reminder/${Uri.encode(item.occurrenceId)}/$phase")
            putExtra(EXTRA_OWNER, ownerId)
            putExtra(EXTRA_OCCURRENCE_ID, item.occurrenceId)
            // start 触发点用开始时刻复核，pre 触发点用现有触发时刻复核。
            putExtra(
                EXTRA_TRIGGER_AT,
                if (phase == PHASE_START) item.startAtMillis else item.triggerAtMillis,
            )
            putExtra(EXTRA_PAYLOAD_HASH, item.payloadHash)
            putExtra(EXTRA_PHASE, phase)
        }

    fun dismissIntent(context: Context, ownerId: String, occurrenceId: String): Intent =
        Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_DISMISS
            data = Uri.parse("iyango://reminder/${Uri.encode(occurrenceId)}/dismiss")
            putExtra(EXTRA_OWNER, ownerId)
            putExtra(EXTRA_OCCURRENCE_ID, occurrenceId)
        }

    /** 触发点的 requestCode：phase 参与区分，避免同实例两个触发点互相覆盖。 */
    fun requestCode(occurrenceId: String, phase: String): Int =
        (occurrenceId + "#" + phase).hashCode()
}

/**
 * AlamManager + BroadcastReceiver 提醒排程（F12）。
 *
 * - 有精确闹钟权限时用 `setExactAndAllowWhileIdle`，否则降级为
 *   `setAndAllowWhileIdle` 并在结果中如实报告；
 * - 计划快照写入平台私有 SharedPreferences，Receiver 触发时复核触发时刻与内容摘要，
 *   旧计划即使已唤醒也不再通知；
 * - 每个实例排两个触发点：pre（课前横幅）与 start（上课时刻，仅在未来时才排）；
 *   通知阶段由 render 依据当前时刻判定，两个触发点最终都渲染出正确阶段；
 * - 取消/重建只针对本应用记录的 occurrenceId。
 */
@Singleton
class AndroidReminderPlatform @Inject constructor(
    @ApplicationContext private val context: Context,
) : ReminderPlatformPort {

    private val store = ReminderPlanStore(context)
    private val alarmManager: AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    init {
        ReminderNotifications.ensureChannel(context)
    }

    override fun notificationsAllowed(): Boolean = ReminderNotifications.allowed(context)

    override fun exactAlarmsAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager?.canScheduleExactAlarms() == true

    override fun liveUpdatesAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.canPostPromotedNotifications()
    }

    override suspend fun schedule(
        items: List<ReminderPlanItem>,
        ownerId: String,
    ): ReminderScheduleResult = withContext(Dispatchers.IO) {
        val manager = alarmManager
            ?: return@withContext ReminderScheduleResult(failures = listOf("系统闹钟服务不可用"))
        val now = System.currentTimeMillis()
        val stored = store.load(ownerId)
        val incoming = items.associateBy { it.occurrenceId }

        val failures = mutableListOf<String>()
        val exactAllowed = exactAlarmsAllowed()
        if (!exactAllowed) {
            failures += "未授予精确闹钟权限，提醒可能延迟"
        }

        // 规划器包含仍在进行中的有效实例；不再从旧快照猜测课程是否仍有效。
        // 删除、停用、切表或关闭单课程提醒后，旧闹钟与通知都必须及时移除。
        (stored.keys - incoming.keys).forEach { id ->
            cancelInternal(id)
        }

        val scheduled = mutableListOf<StoredReminder>()
        items.map(store::toStored).forEach { item ->
            if (!notificationsAllowed()) {
                failures += "未授予通知权限，提醒不会显示"
                return@forEach
            }
            // 持久快照不代表系统闹钟仍存在：重启、覆盖安装或撤销权限都可能清除闹钟。
            // 每次刷新用稳定 PendingIntent 重新注册未来触发点，同身份注册会替换而不重复。
            val preError = if (item.triggerAtMillis > now) {
                scheduleTrigger(
                    manager,
                    item,
                    ownerId,
                    ReminderIntentsInternal.PHASE_PRE,
                    item.triggerAtMillis,
                    exactAllowed,
                )
            } else {
                null
            }
            // start 触发点仅当开始时刻在未来时才排，避免已过去的时刻立即唤醒。
            val startError = if (item.startAtMillis > now) {
                scheduleTrigger(
                    manager,
                    item,
                    ownerId,
                    ReminderIntentsInternal.PHASE_START,
                    item.startAtMillis,
                    exactAllowed,
                )
            } else {
                null
            }
            if (preError == null && startError == null) {
                scheduled += item
            } else {
                failures += "${item.courseName}：${preError ?: startError ?: "排程失败"}"
            }
        }
        val finalItems = scheduled
        store.save(ownerId, finalItems)
        // 清理不在当前计划中的已关闭项；进行中的实例保留，防止"知道了"后课中重新弹出。
        store.retainDismissed(ownerId, incoming.keys + finalItems.map { it.occurrenceId }.toSet())
        // 已进入课前窗口或正在上课：恢复被系统清掉的通知，不注册过去时刻的闹钟。
        // render 会尊重“知道了”，并以同一通知 ID 原地更新，避免重复响铃。
        val renderAt = System.currentTimeMillis()
        finalItems.filter { ReminderNotifications.inFlight(it, renderAt) }.forEach {
            ReminderNotifications.render(context, it, renderAt, ownerId)
        }
        ReminderScheduleResult(
            scheduled = finalItems.size,
            failures = failures.distinct(),
        )
    }

    override suspend fun cancel(occurrenceIds: Collection<String>, ownerId: String) =
        withContext(Dispatchers.IO) {
            val stored = store.load(ownerId).toMutableMap()
            occurrenceIds.forEach { id ->
                cancelInternal(id)
                stored.remove(id)
            }
            store.save(ownerId, stored.values.toList())
        }

    override suspend fun cancelAll(ownerId: String) = withContext(Dispatchers.IO) {
        store.load(ownerId).keys.forEach { cancelInternal(it) }
        store.save(ownerId, emptyList())
        // 没有计划即没有可能展示的提醒，已关闭集合一并清空。
        store.clearDismissed(ownerId)
    }

    /** 排单个触发点；成功返回 null，失败返回原因。 */
    private fun scheduleTrigger(
        manager: AlarmManager,
        item: StoredReminder,
        ownerId: String,
        phase: String,
        atMillis: Long,
        exactAllowed: Boolean,
    ): String? {
        val pending = PendingIntent.getBroadcast(
            context,
            ReminderIntentsInternal.requestCode(item.occurrenceId, phase),
            ReminderIntentsInternal.intent(context, item, ownerId, phase),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val result = runCatching {
            if (exactAllowed) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            }
        }
        return result.exceptionOrNull()?.message
    }

    private fun cancelInternal(occurrenceId: String) {
        // 新版双触发点逐一取消；同时兼容取消旧版本的单触发点 PendingIntent。
        val phases = listOf(ReminderIntentsInternal.PHASE_PRE, ReminderIntentsInternal.PHASE_START)
        phases.forEach { phase ->
            val pending = PendingIntent.getBroadcast(
                context,
                ReminderIntentsInternal.requestCode(occurrenceId, phase),
                Intent(context, ReminderReceiver::class.java).apply {
                    action = ReminderIntentsInternal.ACTION
                    data = Uri.parse("iyango://reminder/${Uri.encode(occurrenceId)}/$phase")
                },
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            if (pending != null) {
                alarmManager?.cancel(pending)
                pending.cancel()
            }
        }
        val legacyPending = PendingIntent.getBroadcast(
            context,
            ReminderNotifications.requestCode(occurrenceId),
            Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderIntentsInternal.ACTION
                data = Uri.parse("iyango://reminder/${Uri.encode(occurrenceId)}")
            },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (legacyPending != null) {
            alarmManager?.cancel(legacyPending)
            legacyPending.cancel()
        }
        // 计划已不包含该实例，正在展示的 Live Update 与进度闹钟一并移除。
        ReminderNotifications.cancelProgressTick(context, occurrenceId)
        NotificationManagerCompat.from(context)
            .cancel(ReminderNotifications.requestCode(occurrenceId))
    }

    override fun showProgressPreview(): Boolean {
        if (!notificationsAllowed()) return false
        val now = System.currentTimeMillis()
        val minute = 60_000L
        val preview = StoredReminder(
            occurrenceId = "${ReminderNotifications.PREVIEW_ID_PREFIX}live_progress",
            triggerAtMillis = now - 30 * minute,
            payloadHash = "preview",
            planRevision = 1,
            title = "高等数学（进度预览）",
            body = "李明 08:00 - 09:40\n明理楼 A302",
            courseId = 0L,
            date = java.time.Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString(),
            startMinute = null,
            startAtMillis = now - 20 * minute,
            endAtMillis = now + 25 * minute,
            courseName = "高等数学（进度预览）",
            teacher = "李明",
            classroom = "明理楼 A302",
            isExam = false,
            accentColor = "#3B82F6",
            dayIndex = 2,
            dayTotal = 4,
            ongoingProgress = true,
        )
        ReminderNotifications.render(context, preview, now, "local")
        return true
    }
}

/**
 * 到点广播：复核平台内最新的计划快照后才展示通知。
 *
 * 计划快照中不存在该 occurrenceId、触发时刻或内容摘要不一致时直接丢弃；
 * 用户关闭提醒后计划被清空，已唤醒的旧闹钟同样不会展示。
 *
 * pre 与 start 两个触发点都会走到 [ReminderNotifications.render]，由当前时刻
 * 判定阶段；用户点"知道了"或划掉通知会写入已关闭集合，后续触发不再展示。
 */
class ReminderReceiver : android.content.BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ReminderIntentsInternal.ACTION -> showReminder(context, intent)
            ReminderIntentsInternal.ACTION_DISMISS -> dismissReminder(context, intent)
            else -> return
        }
    }

    private fun showReminder(context: Context, intent: Intent) {
        val ownerId = intent.getStringExtra(ReminderIntentsInternal.EXTRA_OWNER) ?: return
        val occurrenceId = intent.getStringExtra(ReminderIntentsInternal.EXTRA_OCCURRENCE_ID) ?: return
        val triggerAt = intent.getLongExtra(ReminderIntentsInternal.EXTRA_TRIGGER_AT, 0L)
        val payloadHash = intent.getStringExtra(ReminderIntentsInternal.EXTRA_PAYLOAD_HASH).orEmpty()
        val entry = ReminderPlanStore(context).load(ownerId)[occurrenceId] ?: return
        // 旧版本 Intent 没有 phase，按课前触发处理；start 触发点用开始时刻复核；progress 刷新跳过触发时刻比对并校对进行窗口。
        val phase = intent.getStringExtra(ReminderIntentsInternal.EXTRA_PHASE)
            ?: ReminderIntentsInternal.PHASE_PRE
        if (entry.payloadHash != payloadHash) return
        val now = System.currentTimeMillis()
        if (phase == ReminderIntentsInternal.PHASE_PROGRESS) {
            if (!ReminderNotifications.inFlight(entry, now)) {
                ReminderNotifications.render(context, entry, now, ownerId)
                return
            }
            ReminderNotifications.render(context, entry, now, ownerId)
            return
        }
        val expectedAt = when (phase) {
            ReminderIntentsInternal.PHASE_START -> entry.startAtMillis
            else -> entry.triggerAtMillis
        }
        if (triggerAt != expectedAt) return
        ReminderNotifications.render(context, entry, now, ownerId)
    }

    private fun dismissReminder(context: Context, intent: Intent) {
        val ownerId = intent.getStringExtra(ReminderIntentsInternal.EXTRA_OWNER) ?: return
        val occurrenceId = intent.getStringExtra(ReminderIntentsInternal.EXTRA_OCCURRENCE_ID) ?: return
        val store = ReminderPlanStore(context)
        if (!occurrenceId.startsWith(ReminderNotifications.PREVIEW_ID_PREFIX)) {
            store.addDismissed(ownerId, occurrenceId)
        }
        ReminderNotifications.cancelProgressTick(context, occurrenceId)
        NotificationManagerCompat.from(context).cancel(ReminderNotifications.requestCode(occurrenceId))
    }
}

/** 开机/时间/时区变化后重建提醒与日历计划。 */
@dagger.hilt.android.AndroidEntryPoint
class SystemEventReceiver : android.content.BroadcastReceiver() {

    @javax.inject.Inject
    lateinit var reminderCoordinator: com.gfs.iyango.core.domain.ReminderCoordinatorPort

    @javax.inject.Inject
    lateinit var calendarCoordinator: com.gfs.iyango.core.domain.CalendarCoordinatorPort

    @javax.inject.Inject
    lateinit var gradeMonitorCoordinator: com.gfs.iyango.core.domain.GradeMonitorCoordinatorPort

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> Unit
            else -> return
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runCatching { reminderCoordinator.refresh() }
                runCatching { calendarCoordinator.refresh() }
                runCatching { gradeMonitorCoordinator.syncSchedule() }
                runCatching { WidgetRefreshAlarm.refreshAll(context) }
            } finally {
                pending.finish()
            }
        }
    }
}
