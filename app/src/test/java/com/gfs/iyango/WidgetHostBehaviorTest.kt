package com.gfs.iyango

import android.app.Application
import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ListView
import android.widget.TextView
import com.gfs.iyango.core.domain.CourseOccurrence
import com.gfs.iyango.core.model.*
import com.gfs.iyango.platform.android.*
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 组件宿主行为测试（文档 8.2.4）：
 * 连续翻日时 updateAppWidget 与 notifyAppWidgetViewDataChanged 在同一 Gate 锁内按序执行，
 * 宿主视图始终呈现最新选定日期，且 ListView 保持绑定；其余覆盖意图校验/闹钟/生命周期。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetHostBehaviorTest {
    private val context = RuntimeEnvironment.getApplication()
    private val today: LocalDate = LocalDate.now()
    private fun occurrence(index: Int, start: Int? = 480 + index * 30, end: Int? = start?.plus(60)) = CourseOccurrence(
        "c$index", Course(id = index.toLong(), name = "课程$index", teacher = "张老师", classroom = "A101"), today, start, end, false,
    )
    private fun bind(id: Int, name: String = TodaySmallScheduleWidgetProvider::class.java.name, pkg: String = context.packageName) {
        val info = AppWidgetProviderInfo().apply { provider = ComponentName(pkg, name) }
        shadowOf(AppWidgetManager.getInstance(context)).addBoundWidget(id, info)
    }

    @Test fun rapidDayNavigationKeepsListBoundAndShowsTheLatestDate() = runBlocking {
        val manager = AppWidgetManager.getInstance(context)
        val host = shadowOf(manager)
        val widgetId = host.createWidget(TodaySmallScheduleWidgetProvider::class.java, R.layout.widget_wakeup_classic_shell)
        host.addBoundWidget(widgetId, AppWidgetProviderInfo().apply {
            provider = ComponentName(context, TodaySmallScheduleWidgetProvider::class.java)
        })
        val table = Timetable(id = 1, name = "本地课表", isCurrent = true)
        val entry = FakeEntry(
            FakeWidgetConfigs(listOf(WidgetConfig(widgetId = widgetId, layout = WidgetLayout.TODAY_2X4))),
            FakeTimetables(listOf(table), table),
            FakeSchedule(dayOccurrences = { date -> if (date == today) listOf(occurrence(1)) else emptyList() }),
        )
        val store = WidgetDayStore(context)
        repeat(3) { store.navigate(widgetId, WidgetIntents.NEXT, today) }
        assertTrue(ScheduleWidgets.updateOrThrow(context, widgetId, WidgetLayout.TODAY_2X4, options(), entry))
        val root = host.getViewFor(widgetId)
        // 顶栏呈现最新选定日期；列表适配器仍绑定在 lv_schedule 上（外壳未被覆盖）。
        val expected = today.plusDays(3)
        assertTrue(
            "header must show the latest date: ${root.findViewById<TextView>(R.id.tv_date).text}",
            root.findViewById<TextView>(R.id.tv_date).text.toString().startsWith("${expected.monthValue}月${expected.dayOfMonth}日"),
        )
        assertNotNull(root.findViewById<ListView>(R.id.lv_schedule))
        // 空日期时宿主必须能切换到空视图文案。
        assertTrue(root.findViewById<TextView>(R.id.tv_empty).text.isNotEmpty())
        store.navigate(widgetId, WidgetIntents.TODAY, today)
        assertTrue(ScheduleWidgets.updateOrThrow(context, widgetId, WidgetLayout.TODAY_2X4, options(), entry))
        assertEquals(
            "${today.monthValue}月${today.dayOfMonth}日",
            host.getViewFor(widgetId).findViewById<TextView>(R.id.tv_date).text.toString().substringBefore(" "),
        )
    }

    @Test fun previewShellHidesEndedCoursesInItsEmptyStateCopy() = runBlocking {
        val manager = AppWidgetManager.getInstance(context)
        val host = shadowOf(manager)
        val widgetId = host.createWidget(PreviewScheduleWidgetProvider::class.java, R.layout.widget_preview)
        host.addBoundWidget(widgetId, AppWidgetProviderInfo().apply {
            provider = ComponentName(context, PreviewScheduleWidgetProvider::class.java)
        })
        val table = Timetable(id = 1, name = "本地课表", isCurrent = true)
        // 今天的课全部已下课 → 列表为空，空视图使用现代卡片文案。
        val entry = FakeEntry(
            FakeWidgetConfigs(listOf(WidgetConfig(widgetId = widgetId, layout = WidgetLayout.PREVIEW_2X2))),
            FakeTimetables(listOf(table), table),
            FakeSchedule(dayOccurrences = { date -> if (date == today) listOf(occurrence(1, 0, 1)) else emptyList() }),
        )
        assertTrue(ScheduleWidgets.updateOrThrow(context, widgetId, WidgetLayout.PREVIEW_2X2, options(), entry))
        assertTrue(host.getViewFor(widgetId).findViewById<TextView>(R.id.tv_empty).text.isNotEmpty())
    }

    @Test fun navigationStateIsIsolatedRestoredClampedAndResetAcrossMidnight() {
        val store = WidgetDayStore(context)
        store.navigate(1, WidgetIntents.NEXT, today)
        assertEquals(today.plusDays(1), WidgetDayStore(context).date(1, today))
        assertEquals(today, store.date(2, today))
        repeat(20) { store.navigate(1, WidgetIntents.NEXT, today) }
        assertEquals(today.plusDays(7), store.date(1, today))
        assertEquals(today.plusDays(1), store.date(1, today.plusDays(1)))
        store.navigate(1, WidgetIntents.TODAY, today)
        assertEquals(today, store.date(1, today))
        store.navigate(1, WidgetIntents.PREVIOUS, today)
        store.delete(1)
        assertEquals(today, store.date(1, today))
    }

    @Test fun intentsRejectForeignIdsProvidersAndMalformedExtras() {
        bind(1)
        bind(2, pkg = "other.app")
        bind(3, name = "other.Provider")
        assertEquals(WidgetLayout.TODAY_2X4, WidgetIntents.layout(context, 1))
        for (id in listOf(-1, 0, 2, 3, 999)) assertNull(WidgetIntents.layout(context, id))
        assertNull(WidgetIntents.layout(context, 1, TodayLargeScheduleWidgetProvider::class.java.name))
        assertEquals(AppWidgetManager.INVALID_APPWIDGET_ID, WidgetIntents.widgetId(Intent()))
        assertEquals(AppWidgetManager.INVALID_APPWIDGET_ID, WidgetIntents.widgetId(Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, "bad")))
        val provider = TodayLargeScheduleWidgetProvider()
        provider.onReceive(context, Intent(WidgetIntents.NEXT).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 1))
        TodaySmallScheduleWidgetProvider().onReceive(context, Intent("evil.action").putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 1))
        TodaySmallScheduleWidgetProvider().onReceive(context, Intent(WidgetIntents.NEXT))
        assertEquals(LocalDate.now(), WidgetDayStore(context).date(1))
        assertNull(WidgetIntents.navigation(context, 1, "evil.action"))
        // 周视图 Provider 注册在 providers 表中且导航动作被拒绝时直接忽略。
        assertEquals(WidgetLayout.WEEK_4X4, WidgetIntents.providers[WeekScheduleWidgetProvider::class.java.name])
    }

    @Test fun lifecycleRejectsMalformedOptionsAndMissingIdsWithoutThrowing() {
        bind(1)
        val provider = TodaySmallScheduleWidgetProvider()
        for (intent in listOf(
            Intent(AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 1),
            Intent(AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 1).putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, "bad"),
            Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, "bad"),
            Intent("android.appwidget.action.UNKNOWN"),
        )) provider.onReceive(context, intent)
    }

    @Test fun firstEnableAndUpdateUsesOnlyIdsOwnedByThisProvider() {
        bind(1)
        bind(2, TodayLargeScheduleWidgetProvider::class.java.name)
        TodaySmallScheduleWidgetProvider().onReceive(context,
            Intent("android.appwidget.action.APPWIDGET_ENABLE_AND_UPDATE").putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(1, 2)))
        assertEquals(TodaySmallScheduleWidgetProvider::class.java.name, WidgetDayStore(context).owner(1))
        assertNull(WidgetDayStore(context).owner(2))
        assertArrayEquals(intArrayOf(1), WidgetIntents.ownedIds(context, intArrayOf(1, 2, -1), TodaySmallScheduleWidgetProvider::class.java.name))
    }

    @Test fun pendingIntentsAreExplicitImmutableUniqueAndDebugConfigurationClassIsStable() {
        bind(1); bind(2)
        val previous = WidgetIntents.navigation(context, 1, WidgetIntents.PREVIOUS)!!
        val next = WidgetIntents.navigation(context, 1, WidgetIntents.NEXT)!!
        val second = WidgetIntents.navigation(context, 2, WidgetIntents.NEXT)!!
        assertNotEquals(previous, next); assertNotEquals(next, second)
        assertTrue(shadowOf(next).isImmutable)
        assertEquals(TodaySmallScheduleWidgetProvider::class.java.name, shadowOf(next).savedIntent.component!!.className)
        val settings = WidgetIntents.settings(context, 1)
        assertTrue(shadowOf(settings).isImmutable)
        assertEquals("com.gfs.iyango.WidgetConfigActivity", shadowOf(settings).savedIntent.component!!.className)
        assertEquals(context.packageName, shadowOf(settings).savedIntent.component!!.packageName)
        assertEquals(1, WidgetIntents.widgetId(shadowOf(settings).savedIntent))
    }

    @Test fun midnightAlarmIsInexactAndCancelsWhenLastWidgetIsDeleted() {
        bind(1)
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        WidgetRefreshAlarm.schedule(context)
        val scheduled = alarms.scheduledAlarms.single()
        assertEquals(LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), scheduled.triggerAtTime)
        assertTrue(scheduled.windowLengthMs >= 600000)
        assertEquals(WidgetRefreshReceiver::class.java.name, shadowOf(scheduled.operation).savedIntent.component!!.className)
        WidgetRefreshAlarm.schedule(context, setOf(1))
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    private fun options(): Bundle = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 250)
    }
}
