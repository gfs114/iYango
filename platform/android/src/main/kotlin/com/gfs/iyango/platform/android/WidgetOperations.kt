package com.gfs.iyango.platform.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.gfs.iyango.core.model.WidgetLayout
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Explicit, immutable per-widget operations; the provider is never exported. */
object WidgetIntents {
    // Framework combination action is hidden from the public SDK but delivered on first binding/boot.
    const val ENABLE_AND_UPDATE = "android.appwidget.action.APPWIDGET_ENABLE_AND_UPDATE"
    const val PREVIOUS = "com.gfs.iyango.widget.PREVIOUS"
    const val NEXT = "com.gfs.iyango.widget.NEXT"
    const val TODAY = "com.gfs.iyango.widget.TODAY"
    val navigationActions = setOf(PREVIOUS, NEXT, TODAY)
    val providers = mapOf(
        PreviewScheduleWidgetProvider::class.java.name to WidgetLayout.PREVIEW_2X2,
        TodaySmallScheduleWidgetProvider::class.java.name to WidgetLayout.TODAY_2X4,
        TodayLargeScheduleWidgetProvider::class.java.name to WidgetLayout.TODAY_4X4,
        RulerScheduleWidgetProvider::class.java.name to WidgetLayout.RULER_4X4,
        WeekScheduleWidgetProvider::class.java.name to WidgetLayout.WEEK_4X4,
        NextClassPillWidgetProvider::class.java.name to WidgetLayout.NEXT_PILL_2X1,
        ExamCountdownWidgetProvider::class.java.name to WidgetLayout.EXAM_COUNTDOWN_2X2,
    )
    fun layout(context: Context, widgetId: Int, expectedProvider: String? = null): WidgetLayout? {
        if (widgetId <= 0) return null
        val manager = AppWidgetManager.getInstance(context)
        val component = manager.getAppWidgetInfo(widgetId)?.provider ?: return null
        if (component.packageName != context.packageName || expectedProvider != null && component.className != expectedProvider) return null
        if (widgetId !in manager.getAppWidgetIds(component)) return null
        return providers[component.className]
    }
    fun ownedIds(context: Context, ids: IntArray, provider: String): IntArray =
        ids.distinct().filter { layout(context, it, provider) != null }.toIntArray()

    fun widgetId(intent: Intent): Int = runCatching {
        intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
    }.getOrDefault(AppWidgetManager.INVALID_APPWIDGET_ID)

    fun navigation(context: Context, widgetId: Int, action: String): PendingIntent? {
        if (action !in navigationActions || layout(context, widgetId) == null) return null
        val provider = AppWidgetManager.getInstance(context).getAppWidgetInfo(widgetId)?.provider ?: return null
        val intent = Intent(action).setComponent(provider)
            .setData(Uri.parse("iyango://widget/$widgetId/${action.substringAfterLast('.')}"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    fun settings(context: Context, widgetId: Int): PendingIntent {
        // Class stays in com.gfs.iyango even when applicationId has the .debug suffix.
        val intent = Intent().setComponent(ComponentName(context.packageName, "com.gfs.iyango.WidgetConfigActivity"))
            .setData(Uri.parse("iyango://widget/$widgetId/settings"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

class WidgetDayStore(context: Context) {
    private val preferences = context.getSharedPreferences("widget_days", Context.MODE_PRIVATE)
    fun rememberOwner(widgetId: Int, provider: String) { preferences.edit().putString("owner_$widgetId", provider).apply() }
    fun owner(widgetId: Int): String? = preferences.getString("owner_$widgetId", null)
    fun date(widgetId: Int, today: LocalDate = LocalDate.now()): LocalDate = WidgetPresentation.selectedDate(
        today,
        if (preferences.contains("day_$widgetId")) preferences.getLong("day_$widgetId", 0) else null,
        if (preferences.contains("anchor_$widgetId")) preferences.getLong("anchor_$widgetId", 0) else null,
    )
    fun navigate(widgetId: Int, action: String, today: LocalDate = LocalDate.now()) {
        if (action !in WidgetIntents.navigationActions) return
        val selected = if (action == WidgetIntents.TODAY) today else WidgetPresentation.navigate(
            today, date(widgetId, today), if (action == WidgetIntents.NEXT) 1 else -1,
        )
        preferences.edit().putLong("day_$widgetId", selected.toEpochDay())
            .putLong("anchor_$widgetId", today.toEpochDay()).apply()
    }

    /** 周视图卡片选定周（周一锚定），支持整学期翻周、跨午夜自动归位本周。 */
    fun weekDate(widgetId: Int, today: LocalDate = LocalDate.now()): LocalDate {
        if (!preferences.contains("wday_$widgetId") || preferences.getLong("wanchor_$widgetId", 0) != today.toEpochDay()) {
            return WidgetPresentation.mondayOf(today)
        }
        return runCatching { LocalDate.ofEpochDay(preferences.getLong("wday_$widgetId", 0)) }
            .getOrNull() ?: WidgetPresentation.mondayOf(today)
    }

    fun navigateWeek(widgetId: Int, action: String, today: LocalDate = LocalDate.now()) {
        if (action !in WidgetIntents.navigationActions) return
        val current = weekDate(widgetId, today)
        val selected = when (action) {
            WidgetIntents.TODAY -> WidgetPresentation.mondayOf(today)
            WidgetIntents.NEXT -> current.plusDays(7)
            else -> current.minusDays(7)
        }
        preferences.edit().putLong("wday_$widgetId", selected.toEpochDay())
            .putLong("wanchor_$widgetId", today.toEpochDay()).apply()
    }

    fun delete(widgetId: Int) {
        preferences.edit()
            .remove("day_$widgetId").remove("anchor_$widgetId").remove("owner_$widgetId")
            .remove("wday_$widgetId").remove("wanchor_$widgetId")
            .apply()
    }
}

/** Inexact local-midnight wake-up, independent of app process lifetime. No exact alarm permission. */
object WidgetRefreshAlarm {
    const val ACTION = "com.gfs.iyango.widget.MIDNIGHT_REFRESH"
    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, WidgetRefreshReceiver::class.java).setAction(ACTION)
            .setData(Uri.parse("iyango://widget/midnight")),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    fun schedule(context: Context, excludedIds: Set<Int> = emptySet()) {
        val manager = AppWidgetManager.getInstance(context)
        val any = WidgetIntents.providers.keys.any { name -> manager.getAppWidgetIds(ComponentName(context.packageName, name)).any { it !in excludedIds } }
        val alarm = context.getSystemService(AlarmManager::class.java)
        if (!any) { alarm.cancel(pending(context)); return }
        val next = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarm.setWindow(AlarmManager.RTC_WAKEUP, next, 10 * 60 * 1000L, pending(context))
    }
    suspend fun refreshAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        WidgetIntents.providers.forEach { (name, layout) ->
            manager.getAppWidgetIds(ComponentName(context.packageName, name)).forEach { id -> ScheduleWidgets.update(context, id, layout) }
        }
        schedule(context)
    }
}

class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WidgetRefreshAlarm.ACTION) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { WidgetRefreshAlarm.refreshAll(context) } finally { pending.finish() }
        }
    }
}
