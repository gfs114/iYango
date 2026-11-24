package com.gfs.iyango.platform.android

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.gfs.iyango.core.domain.CourseOccurrence
import com.gfs.iyango.core.model.Timetable
import com.gfs.iyango.core.model.WidgetConfig
import com.gfs.iyango.core.model.WidgetLayout
import dagger.hilt.android.EntryPointAccessors
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 桌面卡片列表数据源（文档 5.1）：
 * - [ScheduleAppWidgetService]：周视图整表（单张离屏网格 Bitmap，纵向切片）；
 * - [TodayColorfulService]：经典日视图（每节课程离屏绘制圆角彩色块）；
 * - [TodayModernService]：现代紧凑/双列/标尺列表（4dp 竖条 + 文本）。
 *
 * 三个服务均在 AndroidManifest 中声明 `BIND_REMOTEVIEWS` 权限且不导出。
 * 列偏移（0 = 选定日，1 = 次日）经 `Intent.data` 的 schemeSpecificPart 传入，
 * 格式与文档 5.2 一致：`"content", "$widgetId,$columnOffset"`。
 */
fun widgetServiceIntent(context: Context, service: Class<*>, widgetId: Int, columnOffset: Int): Intent =
    Intent(context, service).apply {
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        data = Uri.fromParts("content", "$widgetId,$columnOffset", null)
    }

class ScheduleAppWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        ScheduleBitmapFactory(applicationContext, intent)
}

class TodayColorfulService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        TodayColorfulFactory(applicationContext, intent)
}

class TodayModernService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        TodayModernFactory(applicationContext, intent)
}

/** 服务与 [ScheduleWidgets] 共用的绑定解析结果。 */
data class WidgetBinding(
    val config: WidgetConfig,
    val table: Timetable?,
    val followCurrent: Boolean,
)

/** 三个 Factory 的公共骨架；[injectedEntry] 供测试注入假端口。 */
abstract class WidgetListFactory(
    protected val context: Context,
    protected val intent: Intent,
    private val injectedEntry: WidgetEntryPoint? = null,
) : RemoteViewsService.RemoteViewsFactory {

    protected val widgetId: Int = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)

    protected fun entry(): WidgetEntryPoint =
        injectedEntry ?: EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)

    /** 配置 → 绑定课表：timetableId 命中则固定该表，否则回退当前/第一张（跟随自动选表）。 */
    protected fun resolveBinding(): WidgetBinding = runBlocking {
        val entry = entry()
        val config = (entry.widgetConfig().get(widgetId) ?: WidgetConfig(widgetId = widgetId)).normalized()
        val timetables = entry.timetableRepository().observeAll().first()
        val current = entry.timetableRepository().observeCurrent().first()
        val bound = timetables.firstOrNull { it.id == config.timetableId }
        WidgetBinding(config, bound ?: current ?: timetables.firstOrNull(), bound == null)
    }

    protected fun resolveOccurrences(binding: WidgetBinding, range: ClosedRange<LocalDate>): List<CourseOccurrence> {
        val table = binding.table ?: return emptyList()
        return runBlocking {
            ScheduleWidgets.resolve(entry().effectiveSchedule(), table, range, binding.followCurrent)
        }
    }

    protected fun widgetWidthDp(): Int = runCatching {
        AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
            .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
    }.getOrDefault(250).coerceAtLeast(1)

    protected fun itemLayout(name: String): Int =
        ScheduleWidgets.cachedIdentifier(context, name, "layout")

    protected fun itemId(name: String): Int =
        ScheduleWidgets.cachedIdentifier(context, name, "id")

    override fun onCreate() {}
    override fun onDestroy() {}
    override fun hasStableIds(): Boolean = false
    override fun getItemId(position: Int): Long = position.toLong()
    override fun getViewTypeCount(): Int = 1

    /** 避免系统默认生硬的"正在加载..."（文档 4.1）。 */
    override fun getLoadingView(): RemoteViews? {
        val layout = itemLayout("widget_wakeup_loading_view")
        if (layout == 0) return null
        return RemoteViews(context.packageName, layout)
    }
}

/** 周视图整表 Factory：整周网格 Bitmap → 纵向切片逐条下发（文档 5.1/5.3）。 */
class ScheduleBitmapFactory(
    context: Context,
    intent: Intent,
    injectedEntry: WidgetEntryPoint? = null,
) : WidgetListFactory(context, intent, injectedEntry) {

    private var slices: List<Bitmap> = emptyList()

    override fun onDataSetChanged() {
        val binding = resolveBinding()
        if (binding.table == null) {
            slices = emptyList()
            return
        }
        val today = LocalDate.now()
        val monday = WidgetPresentation.mondayOf(WidgetDayStore(context).weekDate(widgetId, today))
        val occurrences = resolveOccurrences(binding, monday..monday.plusDays(6))
        val config = binding.config
        val density = context.resources.displayMetrics.density
        val grid = WakeUpCanvasRenderer.renderWeekGridBitmap(
            widthPx = (widgetWidthDp() * density).toInt(),
            occurrences = occurrences,
            density = density,
            itemHeightDp = config.itemHeightDp,
            radiusDp = config.radiusDp,
            itemAlpha = config.itemAlpha,
            otherWeekCourseAlpha = config.otherWeekCourseAlpha,
            textSizeSp = config.textSizeSp,
            courseTextColor = config.courseTextColor,
            strokeColor = config.strokeColor,
            showSat = config.showSat,
            showSun = config.showSun,
            showLocation = config.showLocation,
            showRoomPrefix = config.showRoomPrefix,
            showTeacher = config.showTeacher,
            showTime = config.showTime,
            showGrid = config.showGrid,
            useDottedLine = config.useDottedLine,
            showOtherWeekCourse = config.showOtherWeekCourse,
        )
        slices = WakeUpCanvasRenderer.sliceVertical(grid)
    }

    override fun getCount(): Int = slices.size

    override fun getViewAt(position: Int): RemoteViews {
        val layout = itemLayout("item_widget_schedule_bitmap")
        val views = RemoteViews(context.packageName, if (layout == 0) android.R.layout.simple_list_item_1 else layout)
        slices.getOrNull(position)?.let { views.setImageViewBitmap(itemId("iv_schedule"), it) }
        return views
    }
}

/** 列表项模板点击的补齐 Intent（携带课程 id 打开 MainActivity，文档 6.1）。 */
internal fun courseFillInIntent(courseId: Long?): Intent =
    Intent().apply { courseId?.takeIf { it > 0 }?.let { putExtra(LaunchIntents.EXTRA_COURSE_ID, it) } }

/** 经典日视图 Factory：每节课离屏绘制圆角彩色块（文档 5.1）。 */
class TodayColorfulFactory(
    context: Context,
    intent: Intent,
    injectedEntry: WidgetEntryPoint? = null,
) : WidgetListFactory(context, intent, injectedEntry) {

    private var config: WidgetConfig = WidgetConfig()
    private var occurrences: List<CourseOccurrence> = emptyList()

    override fun onDataSetChanged() {
        val binding = resolveBinding()
        config = binding.config
        if (binding.table == null) {
            occurrences = emptyList()
            return
        }
        val selected = WidgetDayStore(context).date(widgetId, LocalDate.now())
        occurrences = resolveOccurrences(binding, selected..selected)
            .sortedWith(compareBy({ it.startMinute ?: Int.MAX_VALUE }, { it.course.id }))
    }

    override fun getCount(): Int = occurrences.size

    override fun getViewAt(position: Int): RemoteViews {
        val layout = itemLayout("item_widget_schedule_bitmap")
        val views = RemoteViews(context.packageName, if (layout == 0) android.R.layout.simple_list_item_1 else layout)
        val occurrence = occurrences.getOrNull(position) ?: return views
        val density = context.resources.displayMetrics.density
        val width = (widgetWidthDp() * density).toInt()
        val height = (config.itemHeightDp * density).toInt()
        val bitmap = WakeUpCanvasRenderer.renderClassicCourseBitmap(
            widthPx = width,
            heightPx = height,
            density = density,
            courseHex = occurrence.course.color,
            name = occurrence.course.name,
            teacher = occurrence.course.teacher,
            classroom = occurrence.course.classroom,
            sectionLabel = "第${occurrence.course.startSection}-${occurrence.course.endSection}节",
            timeText = ScheduleWidgets.timeRange(occurrence),
            radiusDp = config.radiusDp,
            itemAlpha = config.itemAlpha,
            strokeColor = config.strokeColor,
            showColor = config.showColor,
            courseTextColor = config.courseTextColor,
            showTime = config.showTime,
            showLocation = config.showLocation,
            showRoomPrefix = config.showRoomPrefix,
            showTeacher = config.showTeacher,
            itemCenterHorizontal = config.itemCenterHorizontal,
            itemCenterVertical = config.itemCenterVertical,
            textSizeSp = config.textSizeSp,
        )
        views.setImageViewBitmap(itemId("iv_schedule"), bitmap)
        views.setOnClickFillInIntent(itemId("iv_schedule"), courseFillInIntent(occurrence.course.id))
        return views
    }
}

/** 现代紧凑/双列/标尺 Factory：Intent URI 的列偏移区分选定日与次日（文档 5.1/5.2）。 */
class TodayModernFactory(
    context: Context,
    intent: Intent,
    injectedEntry: WidgetEntryPoint? = null,
    private val currentTime: () -> LocalDateTime = LocalDateTime::now,
) : WidgetListFactory(context, intent, injectedEntry) {

    private val columnOffset: Int = intent.data?.schemeSpecificPart
        ?.substringAfter(',', "0")?.trim()?.toIntOrNull()?.coerceIn(0, 1) ?: 0

    private var config: WidgetConfig = WidgetConfig()
    private var ruler: Boolean = false
    private var today: LocalDate = LocalDate.now()
    private var nowMinute: Int = 0
    private var occurrences: List<CourseOccurrence> = emptyList()

    override fun onDataSetChanged() {
        val binding = resolveBinding()
        config = binding.config
        ruler = binding.config.layout == WidgetLayout.RULER_4X4
        val now = currentTime()
        today = now.toLocalDate()
        nowMinute = now.hour * 60 + now.minute
        if (binding.table == null) {
            occurrences = emptyList()
            return
        }
        val date = WidgetDayStore(context).date(widgetId, today).plusDays(columnOffset.toLong())
        var list = resolveOccurrences(binding, date..date)
            .sortedWith(compareBy({ it.startMinute ?: Int.MAX_VALUE }, { it.course.id }))
        // 今日已下课自动过滤（文档 5.1：selectedDate == 今天且为紧凑模式时只留未结束课程）。
        if (date == today && binding.config.layout == WidgetLayout.PREVIEW_2X2 && columnOffset == 0) {
            list = list.filter { it.endMinute == null || it.endMinute!! > nowMinute }
        }
        occurrences = list
    }

    override fun getCount(): Int = occurrences.size

    override fun getViewAt(position: Int): RemoteViews {
        val layout = itemLayout("item_widget_today_modern")
        val views = RemoteViews(context.packageName, if (layout == 0) android.R.layout.simple_list_item_1 else layout)
        val occurrence = occurrences.getOrNull(position) ?: return views
        val palette = ScheduleWidgets.resolveTextPalette(context, config)
        val barId = itemId("iv_color_bar")
        views.setViewVisibility(barId, if (config.showColor) View.VISIBLE else View.GONE)
        if (config.showColor) {
            views.setInt(barId, "setColorFilter", WakeUpCanvasRenderer.courseColor(occurrence.course.color))
        }
        val nameId = itemId("tv_course_name")
        views.setTextViewText(nameId, occurrence.course.name)
        views.setTextViewTextSize(nameId, TypedValue.COMPLEX_UNIT_SP, config.textSizeSp.toFloat())
        views.setTextColor(nameId, palette.primary)
        val badgeId = itemId("tv_section_badge")
        if (badgeId != 0) {
            views.setTextViewText(badgeId, WidgetPresentation.courseStatusBadge(occurrence, today, nowMinute))
            views.setTextColor(badgeId, palette.secondary)
        }
        val detailSizeSp = (config.textSizeSp - 2).coerceAtLeast(9).toFloat()
        val timeId = itemId("tv_time_range")
        views.setTextViewText(timeId, ScheduleWidgets.timeRange(occurrence, separator = if (ruler) "–" else " - "))
        views.setTextViewTextSize(timeId, TypedValue.COMPLEX_UNIT_SP, detailSizeSp)
        views.setTextColor(timeId, palette.accent)
        val classroom = occurrence.course.classroom
        val hasRoom = config.showLocation && classroom.isNotBlank()
        val roomId = itemId("tv_room")
        views.setViewVisibility(roomId, if (hasRoom) View.VISIBLE else View.GONE)
        if (hasRoom) {
            views.setTextViewText(roomId, if (config.showRoomPrefix) "@$classroom" else classroom)
            views.setTextViewTextSize(roomId, TypedValue.COMPLEX_UNIT_SP, detailSizeSp)
            views.setTextColor(roomId, palette.secondary)
        }
        val teacher = WidgetPresentation.compactTeachers(occurrence.course.teacher)
        val hasTeacher = config.showTeacher && teacher.isNotBlank()
        val teacherId = itemId("tv_teacher")
        views.setViewVisibility(teacherId, if (hasTeacher) View.VISIBLE else View.GONE)
        if (hasTeacher) {
            views.setTextViewText(teacherId, teacher)
            views.setTextViewTextSize(teacherId, TypedValue.COMPLEX_UNIT_SP, (config.textSizeSp - 3).coerceAtLeast(8).toFloat())
            views.setTextColor(teacherId, palette.secondary)
        }
        views.setOnClickFillInIntent(itemId("item_root"), courseFillInIntent(occurrence.course.id))
        return views
    }
}
