package com.gfs.iyango

import android.app.Application
import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.RemoteViews
import android.widget.TextView
import com.gfs.iyango.core.domain.CourseOccurrence
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.Timetable
import com.gfs.iyango.core.model.WidgetConfig
import com.gfs.iyango.core.model.WidgetLayout
import com.gfs.iyango.platform.android.ScheduleAppWidgetService
import com.gfs.iyango.platform.android.ScheduleBitmapFactory
import com.gfs.iyango.platform.android.ScheduleWidgets
import com.gfs.iyango.platform.android.TodayColorfulFactory
import com.gfs.iyango.platform.android.TodayModernFactory
import com.gfs.iyango.platform.android.TodayModernService
import com.gfs.iyango.platform.android.WidgetPresentation
import com.gfs.iyango.platform.android.widgetServiceIntent
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 新外壳可被组件宿主应用；列表绑定、空视图与降级动作按文档 8.2.3 断言。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WidgetRemoteViewsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val today = LocalDate.of(2026, 11, 5)
    private val now = today.atTime(9, 0)
    private val state = WidgetPresentation.SemesterState(0, 5, 20, ended = false)
    private val options get() = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 320)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 320)
    }

    private fun shells() = listOf(
        R.layout.widget_preview,
        R.layout.widget_wakeup_classic_shell,
        R.layout.widget_today_4x4,
        R.layout.widget_ruler_4x4,
    )

    @Test fun shellsExposeRootListAndEmptyView() {
        for (layout in shells()) {
            val root = RemoteViews(context.packageName, layout).apply(context, FrameLayout(context))
            assertNotNull(root.findViewById<View>(R.id.widget_root))
            assertNotNull(root.findViewById<View>(android.R.id.empty))
            assertNotNull(root.findViewById<View>(R.id.tv_empty))
            if (layout == R.layout.widget_today_4x4) {
                assertNotNull(root.findViewById<ListView>(R.id.lv_today))
                assertNotNull(root.findViewById<ListView>(R.id.lv_next_day))
            } else {
                assertNotNull(root.findViewById<ListView>(R.id.lv_schedule))
            }
        }
    }

    @Test @Config(qualifiers = "night")
    fun allShellsCanBeAppliedAtNight() {
        shells().forEach { layout ->
            val root = RemoteViews(context.packageName, layout).apply(context, FrameLayout(context))
            assertNotNull(root.findViewById<View>(R.id.widget_root))
        }
    }

    @Test fun presentBindsAdapterEmptyViewAndItemTemplate() {
        for (layout in listOf(WidgetLayout.PREVIEW_2X2, WidgetLayout.TODAY_2X4, WidgetLayout.TODAY_4X4, WidgetLayout.RULER_4X4, WidgetLayout.WEEK_4X4)) {
            val views = ScheduleWidgets.present(
                context, 91, layout, WidgetConfig(), state, "本地课表", today, now,
                hasCourses = false, emptyMessage = "今天没有课哦", background = null,
            )
            val actions = views.actionClassNames()
            assertTrue("$layout must setRemoteAdapter", "SetRemoteViewsAdapterIntent" in actions)
            assertTrue("$layout must setEmptyView", "SetEmptyView" in actions)
            assertTrue("$layout must set a list PendingIntentTemplate", "SetPendingIntentTemplate" in actions)
        }
    }

    @Test fun hiddenButtonsFallBackToHeaderAndListSettingsEntries() {
        // 只关按钮：整个顶栏降级为设置入口（文档 6.1）。
        val headerFallback = ScheduleWidgets.present(
            context, 91, WidgetLayout.TODAY_2X4, WidgetConfig(showButton = false), state, "本地课表", today, now,
            hasCourses = false, emptyMessage = "今天没有课哦", background = null,
        )
        assertTrue(
            "header fallback click missing; actions=${headerFallback.actionClassNames()}",
            headerFallback.actionClassNames().any { "OnClickResponse" in it },
        )
        // 按钮与顶栏都关：列表模板也指向设置页，且空视图给出进入设置的提示。
        val listFallback = ScheduleWidgets.present(
            context, 91, WidgetLayout.TODAY_2X4, WidgetConfig(showButton = false, showHeaderArea = false), state, "本地课表", today, now,
            hasCourses = false, emptyMessage = "今天没有课哦", background = null,
        )
        assertTrue("SetPendingIntentTemplate" in listFallback.actionClassNames())
        val root = listFallback.apply(context, FrameLayout(context))
        assertEquals("点击卡片进入设置", root.findViewById<TextView>(R.id.tv_empty).text.toString())
    }

    @Test fun weekShellShowsWeekHeaderAndHidesWeekendColumnsByConfig() {
        val views = ScheduleWidgets.present(
            context, 91, WidgetLayout.WEEK_4X4, WidgetConfig(showSat = false, showSun = true), state, "本地课表", today, now,
            hasCourses = false, emptyMessage = "本周没有课哦", background = null,
        )
        val defaultRoot = views.apply(context, FrameLayout(context))
        assertTrue((defaultRoot.findViewById<TextView>(R.id.tv_weekday0)).text.toString().startsWith("周一\n"))
        assertEquals(View.GONE, defaultRoot.findViewById<View>(R.id.tv_weekday5).visibility)
        assertEquals(View.VISIBLE, defaultRoot.findViewById<View>(R.id.tv_weekday6).visibility)
        assertEquals("11月", (defaultRoot.findViewById<View>(R.id.tv_month) as TextView).text.toString())
    }

    @Test fun missingTimetableHidesWeekSubtitleAndRulerUsesStableDateHeader() {
        val noTableViews = ScheduleWidgets.present(
            context, 91, WidgetLayout.PREVIEW_2X2, WidgetConfig(), WidgetPresentation.SemesterState(0, 1, 20, ended = false),
            "请先创建课表", today, now, hasCourses = false, emptyMessage = "请先创建课表", background = null,
        ).apply(context, FrameLayout(context))
        assertEquals(View.GONE, noTableViews.findViewById<View>(R.id.tv_week_day).visibility)

        val rulerViews = ScheduleWidgets.present(
            context, 92, WidgetLayout.RULER_4X4, WidgetConfig(), state,
            "本地课表", today, now, hasCourses = true, emptyMessage = "今天没有课哦", background = null,
        ).apply(context, FrameLayout(context))
        assertEquals("11月5日 周四", rulerViews.findViewById<TextView>(R.id.tv_date).text.toString())
    }

    @Test fun modernFactoryFiltersEndedCoursesOnlyForTodayPreview() {
        // 固定上午 9 点，不让夜间执行改变“已下课/尚未下课”的测试条件。
        val ended = occurrence(1, startMinute = 7 * 60, endMinute = 8 * 60)
        val ongoing = occurrence(2, endMinute = 10 * 60)
        val entry = fakeEntry(WidgetConfig(widgetId = 91, layout = WidgetLayout.PREVIEW_2X2), listOf(ended, ongoing))
        // 今天（列偏移 0）：已下课课程被过滤。
        val todayFactory = TodayModernFactory(context, widgetServiceIntent(context, TodayModernService::class.java, 91, 0), entry) { now }
        todayFactory.onDataSetChanged()
        assertEquals(1, todayFactory.count)
        val root = todayFactory.getViewAt(0).apply(context, FrameLayout(context))
        assertEquals("课程2", root.findViewById<TextView>(R.id.tv_course_name).text.toString())
        // 翻到明天：不过滤，两门课都显示。
        val tomorrowFactory = TodayModernFactory(context, widgetServiceIntent(context, TodayModernService::class.java, 91, 1), entry) { now }
        tomorrowFactory.onDataSetChanged()
        assertEquals(2, tomorrowFactory.count)
    }

    @Test fun modernFactorySortsByStartMinuteAndCarriesFillInIntent() {
        val later = occurrence(1, startMinute = 900, endMinute = 1000)
        val earlier = occurrence(2, startMinute = 480, endMinute = 600)
        val entry = fakeEntry(WidgetConfig(widgetId = 92, layout = WidgetLayout.TODAY_4X4), listOf(later, earlier))
        val factory = TodayModernFactory(context, widgetServiceIntent(context, TodayModernService::class.java, 92, 0), entry)
        factory.onDataSetChanged()
        assertEquals(2, factory.count)
        val root = factory.getViewAt(0).apply(context, FrameLayout(context))
        assertEquals("课程2", root.findViewById<TextView>(R.id.tv_course_name).text.toString())
        assertEquals("08:00 - 10:00", root.findViewById<TextView>(R.id.tv_time_range).text.toString())
        val actions = factory.getViewAt(0).actionClassNames()
        assertTrue("item needs a click fill-in for the template; actions=$actions",
            actions.any { "OnClickResponse" in it })
    }

    @Test fun colorfulFactoryRendersOneBitmapPerCourse() {
        val entry = fakeEntry(WidgetConfig(widgetId = 93, layout = WidgetLayout.TODAY_2X4), listOf(occurrence(1), occurrence(2)))
        val factory = TodayColorfulFactory(context, widgetServiceIntent(context, com.gfs.iyango.platform.android.TodayColorfulService::class.java, 93, 0), entry)
        factory.onDataSetChanged()
        assertEquals(2, factory.count)
        val actions = factory.getViewAt(0).actionClassNames()
        assertTrue("course row must carry a rendered bitmap; actions=$actions",
            actions.any { "ImageViewBitmap" in it || "BitmapReflection" in it })
        assertEquals("加载中…", factory.loadingView!!.apply(context, FrameLayout(context))
            .findViewById<TextView>(com.gfs.iyango.R.id.widget_loading_text).text.toString())
    }

    @Test fun weekFactoryProducesScrollableSlices() {
        val dense = (1..10).map { occurrence(100 + it, startMinute = 480 + it * 30, endMinute = 520 + it * 30, endSection = 3) }
        val entry = fakeEntry(WidgetConfig(widgetId = 94, layout = WidgetLayout.WEEK_4X4, itemHeightDp = 96), dense)
        val factory = ScheduleBitmapFactory(context, widgetServiceIntent(context, ScheduleAppWidgetService::class.java, 94, 0), entry)
        factory.onDataSetChanged()
        assertTrue("week grid must produce at least one slice", factory.count >= 1)
        val actions = factory.getViewAt(0).actionClassNames()
        assertTrue("slice must be attached to iv_schedule; actions=$actions",
            actions.any { "ImageViewBitmap" in it || "BitmapReflection" in it })
    }

    @Test fun glassPillAndExamCountdownWidgetsBindWithoutCrashing() {
        val pillState = WidgetPresentation.NextClassPillState(
            courseName = "高等数学",
            subtitle = "明理楼 A101 · 08:00-09:40",
            detailLine = "第1-2节 · 张老师",
            badgeText = "上课中",
            isInProgress = true,
            accentHex = "#3B82F6",
            hasCourse = true,
        )
        val pillViews = ScheduleWidgets.presentNextPill(
            context = context,
            widgetId = 95,
            config = WidgetConfig(widgetId = 95, layout = WidgetLayout.NEXT_PILL_2X1),
            pillState = pillState,
            wide = true,
        ).apply(context, FrameLayout(context))
        assertEquals("高等数学", pillViews.findViewById<TextView>(R.id.tv_pill_title).text.toString())
        assertEquals("上课中", pillViews.findViewById<TextView>(R.id.tv_pill_badge).text.toString())
        assertEquals(View.VISIBLE, pillViews.findViewById<View>(R.id.tv_pill_detail).visibility)

        val examItems = listOf(
            WidgetPresentation.ExamWidgetItem(
                title = "线性代数",
                countdownChip = "2天",
                dateTimeText = "11/07 09:00-11:00",
                locationText = "B202 · 18号座",
                isUrgent = true,
            ),
            WidgetPresentation.ExamWidgetItem(
                title = "大学物理",
                countdownChip = "9天",
                dateTimeText = "11/14 14:00-16:00",
                locationText = "A101",
                isUrgent = false,
            ),
        )
        val examViews = ScheduleWidgets.presentExamCountdown(
            context = context,
            widgetId = 96,
            config = WidgetConfig(widgetId = 96, layout = WidgetLayout.EXAM_COUNTDOWN_2X2),
            items = examItems,
            totalUpcoming = 2,
        ).apply(context, FrameLayout(context))
        assertEquals("线性代数", examViews.findViewById<TextView>(R.id.tv_exam_hero_title).text.toString())
        assertEquals("2天", examViews.findViewById<TextView>(R.id.tv_exam_hero_badge).text.toString())
        assertEquals("大学物理", examViews.findViewById<TextView>(R.id.tv_exam_row_1_title).text.toString())
        assertEquals(View.GONE, examViews.findViewById<View>(R.id.tv_exam_empty).visibility)
        assertEquals(View.GONE, examViews.findViewById<View>(R.id.ll_exam_row_2).visibility)
    }

    private fun occurrence(
        index: Int,
        startMinute: Int = 480,
        endMinute: Int = 600,
        endSection: Int = 2,
    ) = CourseOccurrence(
        "c$index", Course(id = index.toLong(), name = "课程$index", teacher = "张老师", classroom = "A101", startSection = 1, endSection = endSection),
        LocalDate.now(), startMinute, endMinute, false,
    )

    private fun fakeEntry(config: WidgetConfig, occurrences: List<CourseOccurrence>): FakeEntry {
        val table = Timetable(id = 1, name = "本地课表", isCurrent = true)
        return FakeEntry(
            FakeWidgetConfigs(listOf(config)),
            FakeTimetables(listOf(table), table),
            FakeSchedule(dayOccurrences = { occurrences }, rangeOccurrences = { _, _ -> occurrences }),
        )
    }
}
