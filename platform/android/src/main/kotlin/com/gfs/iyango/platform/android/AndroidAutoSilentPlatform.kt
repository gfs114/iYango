package com.gfs.iyango.platform.android

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import com.gfs.iyango.core.domain.AutoSilentPlatformPort
import com.gfs.iyango.core.domain.AutoSilentWindow
import com.gfs.iyango.core.model.AutoSilentMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 平台内部存储：记录已排程静音窗口与进入静音前的系统铃声/勿扰快照。
 */
internal class AutoSilentPlanStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun savePlan(mode: AutoSilentMode, windows: List<AutoSilentWindow>) {
        val array = JSONArray()
        windows.forEach { w ->
            array.put(
                JSONObject().apply {
                    put("occurrenceId", w.occurrenceId)
                    put("courseKey", w.courseKey)
                    put("courseName", w.courseName)
                    put("startAtMillis", w.startAtMillis)
                    put("endAtMillis", w.endAtMillis)
                },
            )
        }
        prefs.edit()
            .putString(KEY_MODE, mode.name)
            .putString(KEY_WINDOWS, array.toString())
            .apply()
    }

    fun loadMode(): AutoSilentMode {
        val raw = prefs.getString(KEY_MODE, null) ?: return AutoSilentMode.OFF
        return runCatching { AutoSilentMode.valueOf(raw) }.getOrDefault(AutoSilentMode.OFF)
    }

    fun loadWindows(): List<AutoSilentWindow> {
        val raw = prefs.getString(KEY_WINDOWS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        AutoSilentWindow(
                            occurrenceId = obj.getString("occurrenceId"),
                            courseKey = obj.optString("courseKey"),
                            courseName = obj.optString("courseName"),
                            startAtMillis = obj.getLong("startAtMillis"),
                            endAtMillis = obj.getLong("endAtMillis"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun addSkippedOccurrence(occurrenceId: String) {
        val updated = loadSkippedOccurrences().toMutableSet().apply {
            add(occurrenceId)
            // Keep bounded so prefs never grow unbounded across semesters.
            if (size > MAX_SKIPPED) {
                val excess = size - MAX_SKIPPED
                repeat(excess) { firstOrNull()?.let(::remove) }
            }
        }
        prefs.edit().putStringSet(KEY_SKIPPED, updated).apply()
    }

    fun loadSkippedOccurrences(): Set<String> =
        prefs.getStringSet(KEY_SKIPPED, emptySet()).orEmpty().toSet()

    fun pruneSkipped(activeOccurrenceIds: Set<String>) {
        val current = loadSkippedOccurrences()
        val kept = current.filterTo(mutableSetOf()) { it in activeOccurrenceIds }
        if (kept.size != current.size) {
            prefs.edit().putStringSet(KEY_SKIPPED, kept).apply()
        }
    }

    fun clearAllPlan() {
        prefs.edit()
            .putString(KEY_MODE, AutoSilentMode.OFF.name)
            .remove(KEY_WINDOWS)
            .remove(KEY_SKIPPED)
            .apply()
    }

    fun isSessionActive(): Boolean = prefs.getBoolean(KEY_SESSION_ACTIVE, false)

    fun saveSnapshotIfIdle(ringerMode: Int, interruptionFilter: Int) {
        if (isSessionActive()) return
        prefs.edit()
            .putBoolean(KEY_SESSION_ACTIVE, true)
            .putInt(KEY_PREV_RINGER, ringerMode)
            .putInt(KEY_PREV_FILTER, interruptionFilter)
            .apply()
    }

    fun consumeSnapshot(): Pair<Int, Int>? {
        if (!isSessionActive()) return null
        val prevRinger = prefs.getInt(KEY_PREV_RINGER, AudioManager.RINGER_MODE_NORMAL)
        val prevFilter = prefs.getInt(KEY_PREV_FILTER, NotificationManager.INTERRUPTION_FILTER_ALL)
        prefs.edit()
            .putBoolean(KEY_SESSION_ACTIVE, false)
            .remove(KEY_PREV_RINGER)
            .remove(KEY_PREV_FILTER)
            .apply()
        return prevRinger to prevFilter
    }

    companion object {
        const val PREFS_NAME = "auto_silent_plan"
        private const val KEY_MODE = "mode"
        private const val KEY_WINDOWS = "windows"
        private const val KEY_SKIPPED = "skipped"
        private const val KEY_SESSION_ACTIVE = "session_active"
        private const val KEY_PREV_RINGER = "prev_ringer"
        private const val KEY_PREV_FILTER = "prev_filter"
        private const val MAX_SKIPPED = 128
    }
}

internal object AutoSilentIntents {
    const val ACTION_ENTER = "com.gfs.iyango.action.AUTO_SILENT_ENTER"
    const val ACTION_EXIT = "com.gfs.iyango.action.AUTO_SILENT_EXIT"
    const val ACTION_CANCEL_OCCURRENCE = "com.gfs.iyango.action.AUTO_SILENT_CANCEL_OCCURRENCE"

    const val EXTRA_OCCURRENCE_ID = "occurrenceId"
    const val EXTRA_MODE = "mode"

    fun alarmIntent(
        context: Context,
        action: String,
        occurrenceId: String,
        mode: AutoSilentMode,
    ): Intent = Intent(context, AutoSilentReceiver::class.java).apply {
        this.action = action
        data = Uri.parse("iyango://autosilent/$action/${Uri.encode(occurrenceId)}")
        putExtra(EXTRA_OCCURRENCE_ID, occurrenceId)
        putExtra(EXTRA_MODE, mode.name)
    }

    fun cancelOccurrenceIntent(context: Context, occurrenceId: String): Intent =
        Intent(context, AutoSilentReceiver::class.java).apply {
            action = ACTION_CANCEL_OCCURRENCE
            data = Uri.parse("iyango://autosilent/cancel/${Uri.encode(occurrenceId)}")
            putExtra(EXTRA_OCCURRENCE_ID, occurrenceId)
        }

    fun requestCode(occurrenceId: String, suffix: String): Int =
        (occurrenceId + "#autosilent#" + suffix).hashCode()
}

internal object AutoSilentSystemController {

    fun hasPolicyAccess(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        return nm.isNotificationPolicyAccessGranted
    }

    fun enter(context: Context, mode: AutoSilentMode, store: AutoSilentPlanStore) {
        if (mode == AutoSilentMode.OFF) return
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return
        val nm = context.getSystemService(NotificationManager::class.java)
        val granted = nm?.isNotificationPolicyAccessGranted == true
        val prevRinger = runCatching { audioManager.ringerMode }.getOrDefault(AudioManager.RINGER_MODE_NORMAL)
        val prevFilter = runCatching {
            nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
        }.getOrDefault(NotificationManager.INTERRUPTION_FILTER_ALL)
        store.saveSnapshotIfIdle(prevRinger, prevFilter)

        when (mode) {
            AutoSilentMode.OFF -> Unit
            AutoSilentMode.VIBRATE -> {
                runCatching { audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE }
            }
            AutoSilentMode.SILENT -> {
                if (granted) {
                    runCatching { audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT }
                } else {
                    // 无勿扰权限时降级为振动，避免系统抛出 SecurityException。
                    runCatching { audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE }
                }
            }
            AutoSilentMode.DND -> {
                if (granted && nm != null) {
                    runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS) }
                } else {
                    runCatching { audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE }
                }
            }
        }
    }

    fun restore(context: Context, store: AutoSilentPlanStore) {
        val snapshot = store.consumeSnapshot() ?: return
        val (prevRinger, prevFilter) = snapshot
        val audioManager = context.getSystemService(AudioManager::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm != null && nm.isNotificationPolicyAccessGranted) {
            runCatching { nm.setInterruptionFilter(prevFilter) }
        }
        if (audioManager != null) {
            runCatching { audioManager.ringerMode = prevRinger }
        }
    }
}

/**
 * AlarmManager + AudioManager + NotificationManager 上课自动静音/免打扰平台实现。
 */
@Singleton
class AndroidAutoSilentPlatform @Inject constructor(
    @ApplicationContext private val context: Context,
) : AutoSilentPlatformPort {

    private val store = AutoSilentPlanStore(context)
    private val alarmManager: AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    override fun hasPolicyAccess(): Boolean = AutoSilentSystemController.hasPolicyAccess(context)

    override suspend fun schedule(windows: List<AutoSilentWindow>, mode: AutoSilentMode) {
        withContext(Dispatchers.IO) {
            val previous = store.loadWindows()
            previous.forEach { cancelWindowAlarms(it.occurrenceId) }

            if (mode == AutoSilentMode.OFF || windows.isEmpty()) {
                store.clearAllPlan()
                AutoSilentSystemController.restore(context, store)
                return@withContext
            }

            store.pruneSkipped(windows.map { it.occurrenceId }.toSet())
            store.savePlan(mode, windows)
            val skipped = store.loadSkippedOccurrences()
            val now = System.currentTimeMillis()
            val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager?.canScheduleExactAlarms() == true

            val activeWindows = windows.filter { it.occurrenceId !in skipped }
            activeWindows.forEach { window ->
                if (window.startAtMillis > now) {
                    scheduleAlarm(
                        triggerAtMillis = window.startAtMillis,
                        action = AutoSilentIntents.ACTION_ENTER,
                        occurrenceId = window.occurrenceId,
                        mode = mode,
                        exactAllowed = exactAllowed,
                    )
                }
                if (window.endAtMillis > now) {
                    scheduleAlarm(
                        triggerAtMillis = window.endAtMillis,
                        action = AutoSilentIntents.ACTION_EXIT,
                        occurrenceId = window.occurrenceId,
                        mode = mode,
                        exactAllowed = exactAllowed,
                    )
                }
            }

            val currentActive = activeWindows.any { now in it.startAtMillis until it.endAtMillis }
            if (currentActive) {
                AutoSilentSystemController.enter(context, mode, store)
            } else {
                AutoSilentSystemController.restore(context, store)
            }
        }
    }

    override suspend fun cancelAllAndRestore() {
        withContext(Dispatchers.IO) {
            store.loadWindows().forEach { cancelWindowAlarms(it.occurrenceId) }
            store.clearAllPlan()
            AutoSilentSystemController.restore(context, store)
        }
    }

    override suspend fun cancelCurrentOccurrence(occurrenceId: String) {
        withContext(Dispatchers.IO) {
            if (occurrenceId.isBlank()) return@withContext
            store.addSkippedOccurrence(occurrenceId)
            cancelWindowAlarms(occurrenceId)
            val skipped = store.loadSkippedOccurrences()
            val now = System.currentTimeMillis()
            val stillActive = store.loadWindows().any {
                it.occurrenceId != occurrenceId &&
                    it.occurrenceId !in skipped &&
                    now in it.startAtMillis until it.endAtMillis
            }
            if (!stillActive) {
                AutoSilentSystemController.restore(context, store)
            }
        }
    }

    private fun scheduleAlarm(
        triggerAtMillis: Long,
        action: String,
        occurrenceId: String,
        mode: AutoSilentMode,
        exactAllowed: Boolean,
    ) {
        val manager = alarmManager ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            AutoSilentIntents.requestCode(occurrenceId, action),
            AutoSilentIntents.alarmIntent(context, action, occurrenceId, mode),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            if (exactAllowed) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            }
        }
    }

    private fun cancelWindowAlarms(occurrenceId: String) {
        cancelSingleAlarm(occurrenceId, AutoSilentIntents.ACTION_ENTER)
        cancelSingleAlarm(occurrenceId, AutoSilentIntents.ACTION_EXIT)
    }

    private fun cancelSingleAlarm(occurrenceId: String, action: String) {
        val pending = PendingIntent.getBroadcast(
            context,
            AutoSilentIntents.requestCode(occurrenceId, action),
            Intent(context, AutoSilentReceiver::class.java).apply {
                this.action = action
                data = Uri.parse("iyango://autosilent/$action/${Uri.encode(occurrenceId)}")
            },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pending != null) {
            alarmManager?.cancel(pending)
            pending.cancel()
        }
    }

    companion object {
        /** 供通知栏判定该课程实例是否在自动静音管理中（用于挂载「取消本节静音」按钮）。 */
        fun isOccurrenceManaged(context: Context, occurrenceId: String): Boolean {
            if (occurrenceId.isBlank()) return false
            val store = AutoSilentPlanStore(context)
            if (store.loadMode() == AutoSilentMode.OFF) return false
            if (occurrenceId in store.loadSkippedOccurrences()) return false
            return store.loadWindows().any { it.occurrenceId == occurrenceId }
        }

        /** 通知栏「取消本节静音」动作 PendingIntent。 */
        fun cancelOccurrencePendingIntent(context: Context, occurrenceId: String): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                AutoSilentIntents.requestCode(occurrenceId, "cancel_action"),
                AutoSilentIntents.cancelOccurrenceIntent(context, occurrenceId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}

/** 上课静音 / 下课恢复 / 通知取消本节静音广播接收器。 */
class AutoSilentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val store = AutoSilentPlanStore(context)
        val occurrenceId = intent.getStringExtra(AutoSilentIntents.EXTRA_OCCURRENCE_ID).orEmpty()
        when (intent.action) {
            AutoSilentIntents.ACTION_ENTER -> {
                val mode = store.loadMode()
                if (mode == AutoSilentMode.OFF) return
                if (occurrenceId in store.loadSkippedOccurrences()) return
                val valid = store.loadWindows().any { it.occurrenceId == occurrenceId }
                if (!valid) return
                AutoSilentSystemController.enter(context, mode, store)
            }
            AutoSilentIntents.ACTION_EXIT -> {
                val now = System.currentTimeMillis()
                val skipped = store.loadSkippedOccurrences()
                val stillActive = store.loadWindows().any {
                    it.occurrenceId != occurrenceId &&
                        it.occurrenceId !in skipped &&
                        now in it.startAtMillis until it.endAtMillis
                }
                if (!stillActive) {
                    AutoSilentSystemController.restore(context, store)
                }
            }
            AutoSilentIntents.ACTION_CANCEL_OCCURRENCE -> {
                if (occurrenceId.isNotBlank()) {
                    store.addSkippedOccurrence(occurrenceId)
                }
                val now = System.currentTimeMillis()
                val skipped = store.loadSkippedOccurrences()
                val stillActive = store.loadWindows().any {
                    it.occurrenceId != occurrenceId &&
                        it.occurrenceId !in skipped &&
                        now in it.startAtMillis until it.endAtMillis
                }
                if (!stillActive) {
                    AutoSilentSystemController.restore(context, store)
                }
            }
        }
    }
}
