package com.gfs.iyango

import android.app.Application
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import com.gfs.iyango.core.domain.*
import com.gfs.iyango.core.model.*
import com.gfs.iyango.platform.android.*
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetResolutionTest {
    private val context = RuntimeEnvironment.getApplication()
    private val today = LocalDate.now()
    private val owner = Timetable(id = 1, name = "有效课表A", isCurrent = true)
    private val source = Timetable(id = 2, name = "临时来源B")
    private fun occurrence(date: LocalDate, table: Long = 2) = CourseOccurrence("c-$date", Course(id = 42, timetableId = table, name = "有效课程"), date, 600, 660, false, isTemporary = true)
    private val options get() = Bundle().apply { putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 320); putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 320) }

    private fun entry(config: WidgetConfig, schedule: FakeSchedule, tables: List<Timetable> = listOf(owner, source)): FakeEntry =
        FakeEntry(FakeWidgetConfigs(listOf(config)), FakeTimetables(tables, tables.first { it.isCurrent }), schedule)

    @Test fun listShellResolvesSelectedDayThroughAutomaticOwner() = runBlocking {
        WidgetDayStore(context).navigate(92, WidgetIntents.NEXT, today)
        val schedule = FakeSchedule(
            dayTable = { if (it == today.plusDays(1)) source else owner },
            dayOccurrences = { date -> listOf(occurrence(date)) },
        )
        val root = ScheduleWidgets.build(context, 92, WidgetLayout.TODAY_4X4, options, entry(WidgetConfig(), schedule))
            .apply(context, FrameLayout(context))
        assertTrue(root.findViewById<TextView>(R.id.tv_table_name).text.toString().startsWith(source.name))
        assertTrue(schedule.selectedDates.contains(today.plusDays(1)))
    }

    @Test fun explicitBindingsStayFixedAndNeverFollowTheDailyOwner() = runBlocking {
        val fixed = FakeSchedule(rangeOccurrences = { id, _ -> listOf(occurrence(today, id)) })
        val root = ScheduleWidgets.build(context, 93, WidgetLayout.TODAY_4X4, options, entry(WidgetConfig(timetableId = 2), fixed))
            .apply(context, FrameLayout(context))
        assertEquals(listOf(2L), fixed.fixedIds)
        assertTrue(fixed.selectedDates.isEmpty())
        assertTrue(root.findViewById<TextView>(R.id.tv_table_name).text.toString().startsWith(source.name))
    }

    @Test fun sharedPublicationPathLeavesTheHostOnTheLatestNavigatedDate() = runBlocking {
        withTimeout(5000) {
            val manager = AppWidgetManager.getInstance(context)
            val host = shadowOf(manager)
            val widgetId = host.createWidget(TodayLargeScheduleWidgetProvider::class.java, R.layout.widget_today_4x4)
            host.addBoundWidget(widgetId, AppWidgetProviderInfo().apply {
                provider = ComponentName(context, TodayLargeScheduleWidgetProvider::class.java)
            })
            val oldRead = CompletableDeferred<Unit>()
            val releaseOld = CompletableDeferred<Unit>()
            val schedule = FakeSchedule(
                dayTable = { owner },
                dayOccurrences = { emptyList<CourseOccurrence>() },
                beforeQuery = { date ->
                    if (date == today.plusDays(1) && !oldRead.isCompleted) {
                        oldRead.complete(Unit)
                        releaseOld.await()
                    }
                },
            )
            val dependencies = entry(WidgetConfig(), schedule)
            val store = WidgetDayStore(context)
            store.navigate(widgetId, WidgetIntents.NEXT, today)
            val first = launch(start = CoroutineStart.UNDISPATCHED) {
                assertTrue(ScheduleWidgets.updateOrThrow(context, widgetId, WidgetLayout.TODAY_4X4, options, dependencies))
            }
            oldRead.await()
            store.navigate(widgetId, WidgetIntents.NEXT, today)
            val second = launch(start = CoroutineStart.UNDISPATCHED) {
                assertTrue(ScheduleWidgets.updateOrThrow(context, widgetId, WidgetLayout.TODAY_4X4, options, dependencies))
            }
            releaseOld.complete(Unit)
            first.join(); second.join()
            val selected = today.plusDays(2)
            assertEquals(selected, store.date(widgetId, today))
            val header = host.getViewFor(widgetId).findViewById<TextView>(R.id.tv_date).text.toString()
            assertTrue("Host must show the latest persisted date: $header", header.startsWith("${selected.monthValue}月${selected.dayOfMonth}日"))
        }
    }

    @Test fun weekProviderNavigatesWholeWeeksAndResetsAcrossMidnight() {
        val monday = WidgetPresentation.mondayOf(today)
        val store = WidgetDayStore(context)
        store.navigateWeek(31, WidgetIntents.NEXT, today)
        assertEquals(monday.plusDays(7), store.weekDate(31, today))
        store.navigateWeek(31, WidgetIntents.NEXT, today)
        assertEquals(monday.plusDays(14), store.weekDate(31, today))
        // 翻周不受 ±7 天翻日钳制。
        store.navigateWeek(31, WidgetIntents.PREVIOUS, today)
        store.navigateWeek(31, WidgetIntents.PREVIOUS, today)
        assertEquals(monday, store.weekDate(31, today))
        store.navigateWeek(31, WidgetIntents.PREVIOUS, today)
        assertEquals(monday.minusDays(7), store.weekDate(31, today))
        // 跨午夜自动归位本周（文档 2.2 保留跨午夜归零机制）。
        assertEquals(WidgetPresentation.mondayOf(today.plusDays(1)), store.weekDate(31, today.plusDays(1)))
        store.navigateWeek(31, WidgetIntents.TODAY, today)
        assertEquals(monday, store.weekDate(31, today))
    }
}
