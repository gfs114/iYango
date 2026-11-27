package com.gfs.iyango.platform.android

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.gfs.iyango.core.domain.CourseOccurrence
import com.gfs.iyango.core.domain.EffectiveSchedulePort
import com.gfs.iyango.core.domain.ExamRepositoryPort
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.domain.WidgetConfigPort
import com.gfs.iyango.core.model.Timetable
import com.gfs.iyango.core.model.WidgetConfig
import com.gfs.iyango.core.model.WidgetLayout
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 组件跳转与快捷入口共用的外部目标参数（MainActivity 统一解析）。 */
object LaunchIntents {
    const val EXTRA_TARGET_TAB = "com.gfs.iyango.extra.TARGET_TAB"
    const val EXTRA_TARGET_ROUTE = "com.gfs.iyango.extra.TARGET_ROUTE"
    const val EXTRA_COURSE_ID = ReminderIntents.EXTRA_COURSE_ID
    const val URI_SCHEME = "iyango"
    const val URI_HOST = "open"

    val TAB_ROUTES = listOf("today", "schedule", "grades", "mine")
    val ROUTES = setOf(
        "today", "schedule", "grades", "mine", "timetable", "timetablecompare", "courses",
        "semester", "semestersummary", "importexport", "display", "wallpaper", "reminder", "exams",
        "classroom", "aiassistant",
    )

    /** 显式目标优先于默认启动页；只接受白名单页签/路由与正数课程 id。 */
    fun launchIntent(
        context: Context,
        tab: Int? = null,
        route: String? = null,
        courseId: Long? = null,
    ): Intent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        tab?.takeIf { it in TAB_ROUTES.indices }?.let { intent.putExtra(EXTRA_TARGET_TAB, it) }
        route?.takeIf { it in ROUTES }?.let { intent.putExtra(EXTRA_TARGET_ROUTE, it) }
        courseId?.takeIf { it > 0 }?.let { intent.putExtra(EXTRA_COURSE_ID, it) }
        return intent
    }
}

/** 组件渲染所需的领域端口（Receiver 无构造函数注入，用入口点取依赖）。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun timetableRepository(): TimetableRepositoryPort
    fun effectiveSchedule(): EffectiveSchedulePort
    fun widgetConfig(): WidgetConfigPort
    fun examRepository(): ExamRepositoryPort
}

/**
 * 桌面卡片 RemoteViews 外壳渲染器（F14，对齐 WakeUp 改造方案）。
 *
 * 外壳只负责双层背景、顶栏、周表头、按钮/降级绑定与 `ListView` 的
 * `setRemoteAdapter`/`setEmptyView`/`setPendingIntentTemplate`；列表行数据由
 * [ScheduleAppWidgetService]/[TodayColorfulService]/[TodayModernService] 的
 * Factory 在 `onDataSetChanged` 中按 [WidgetDayStore] 选定日期重新解析。
 * 每个 widgetId 独立读取配置，绑定不同课表不串数据；绑定课表被删除时回退当前课表。
 */
object ScheduleWidgets {

    private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")
    private val weekdayLabels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    private val updates = WidgetUpdateCoordinator()

    suspend fun update(context: Context, widgetId: Int, layout: WidgetLayout, options: Bundle? = null) {
        try {
            updateOrThrow(context, widgetId, layout, options)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Automatic refresh keeps the last successful views when a query or host fails.
        }
    }

    /** All real publications, including configuration saves, share this process-wide per-id gate. */
    suspend fun updateOrThrow(
        context: Context,
        widgetId: Int,
        layout: WidgetLayout,
        options: Bundle? = null,
        entry: WidgetEntryPoint? = null,
        beforeBuild: suspend () -> Unit = {},
    ): Boolean = updates.publish(widgetId, build = {
        check(WidgetIntents.layout(context, widgetId) == layout) { "Widget is no longer bound" }
        beforeBuild()
        if (entry == null) build(context, widgetId, layout, options) else build(context, widgetId, layout, options, entry)
    }, publish = { views ->
        check(WidgetIntents.layout(context, widgetId) == layout) { "Widget is no longer bound" }
        val manager = AppWidgetManager.getInstance(context)
        // 文档 5.2：顶栏 updateAppWidget 与列表 notifyAppWidgetViewDataChanged
        // 必须在同一 Gate 临界区内按序完成，连续快速翻日时无竞态覆盖。
        manager.updateAppWidget(widgetId, views)
        if (layout != WidgetLayout.NEXT_PILL_2X1 && layout != WidgetLayout.EXAM_COUNTDOWN_2X2) {
            val primaryList = viewId(context, if (layout == WidgetLayout.TODAY_4X4) "lv_today" else "lv_schedule")
            if (primaryList != 0) manager.notifyAppWidgetViewDataChanged(widgetId, primaryList)
            if (layout == WidgetLayout.TODAY_4X4) {
                val nextList = viewId(context, "lv_next_day")
                if (nextList != 0) manager.notifyAppWidgetViewDataChanged(widgetId, nextList)
            }
        }
    })

    fun invalidate(widgetId: Int) = updates.invalidate(widgetId)

    suspend fun remove(widgetId: Int, cleanup: suspend () -> Unit) = updates.remove(widgetId, cleanup)

    suspend fun build(
        context: Context,
        widgetId: Int,
        layout: WidgetLayout,
        options: Bundle? = null,
        entry: WidgetEntryPoint = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java),
    ): RemoteViews {
        AppWidgetManager.getInstance(context).getAppWidgetInfo(widgetId)?.provider?.let { provider ->
            if (WidgetIntents.layout(context, widgetId, provider.className) == layout) WidgetDayStore(context).rememberOwner(widgetId, provider.className)
        }
        val binding = bindingOf(entry, widgetId)
        val config = binding.config
        val effective = entry.effectiveSchedule()
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val store = WidgetDayStore(context)
        val selected = if (layout == WidgetLayout.WEEK_4X4) store.weekDate(widgetId, today) else store.date(widgetId, today)
        val range = if (layout == WidgetLayout.WEEK_4X4) {
            WidgetPresentation.mondayOf(selected)..WidgetPresentation.mondayOf(selected).plusDays(6)
        } else {
            selected..selected
        }
        val occurrences = if (binding.table == null) emptyList() else resolve(effective, binding.table, range, binding.followCurrent)
        // 学期状态：跟随当前课表走逐日解析；固定绑定读该表自己的学期。
        val semester = if (binding.followCurrent) {
            effective.resolveDay(selected).semester
        } else {
            binding.table?.let { effective.resolveTimetableDays(it.id, selected..selected).firstOrNull()?.semester }
        }
        val state = WidgetPresentation.semesterState(semester, selected, today)
        val displayTable = when {
            binding.table == null -> null
            binding.followCurrent -> effective.resolveDay(selected).timetable ?: binding.table
            else -> binding.table
        }
        // 文档 4.2：现代卡片按宽度列数截断课表名（<4 列 6 字，>=4 列 12 字）；经典/周外壳固定 12 字。
        val span = WidgetPresentation.resolveCellSpan(
            options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
                ?: AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
                    .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250),
        )
        if (layout == WidgetLayout.NEXT_PILL_2X1) {
            val todayOcc = if (binding.table == null) emptyList() else resolve(effective, binding.table, today..today, binding.followCurrent)
            val tomorrow = today.plusDays(1)
            val tomorrowOcc = if (binding.table == null) emptyList() else resolve(effective, binding.table, tomorrow..tomorrow, binding.followCurrent)
            val pillState = if (binding.table == null) {
                WidgetPresentation.NextClassPillState(
                    courseName = "请先创建课表",
                    subtitle = "点击打开应用添加课程",
                    detailLine = "",
                    badgeText = "未绑定",
                    isInProgress = false,
                    accentHex = "",
                    hasCourse = false,
                )
            } else {
                WidgetPresentation.resolveNextClassPill(todayOcc, tomorrowOcc, now)
            }
            return presentNextPill(
                context = context,
                widgetId = widgetId,
                config = config,
                pillState = pillState,
                wide = span >= 4,
                background = loadBackground(context, widgetId, config),
            )
        }
        if (layout == WidgetLayout.EXAM_COUNTDOWN_2X2) {
            val exams = runCatching { entry.examRepository().observeAll().first() }.getOrDefault(emptyList())
            val items = WidgetPresentation.resolveUpcomingExams(exams, now, limit = 3)
            return presentExamCountdown(
                context = context,
                widgetId = widgetId,
                config = config,
                items = items,
                totalUpcoming = exams.count { com.gfs.iyango.core.domain.ExamCountdown.isUpcoming(it, now) },
                background = loadBackground(context, widgetId, config),
            )
        }
        val nameLimit = if (layout == WidgetLayout.TODAY_2X4 || layout == WidgetLayout.WEEK_4X4) 12 else if (span >= 4) 12 else 6
        val tableName = displayTable?.name?.take(nameLimit) ?: "请先创建课表"
        // 预览卡片会过滤已下课课程；空视图文案必须与列表真实可见性一致。
        val visibleOccurrences = if (layout == WidgetLayout.PREVIEW_2X2 && selected == today) {
            occurrences.filter { it.endMinute == null || it.endMinute!! > now.hour * 60 + now.minute }
        } else {
            occurrences
        }
        val remainingCount = WidgetPresentation.remaining(occurrences, now)
        val allCoursesEndedToday = selected == today && occurrences.isNotEmpty() && visibleOccurrences.isEmpty()
        val emptyMessage = if (binding.table == null) "请先创建课表" else WidgetPresentation.emptyMessage(
            state, selected, today,
            modern = layout != WidgetLayout.TODAY_2X4 && layout != WidgetLayout.WEEK_4X4,
            weekView = layout == WidgetLayout.WEEK_4X4,
            allCoursesEndedToday = allCoursesEndedToday,
        )
        val subtitleText = if (binding.table == null) "" else WidgetPresentation.headerSubtitle(
            state, selected, today, remainingCount, occurrences.size, layout == WidgetLayout.WEEK_4X4,
        )
        val todayColumnMeta = when {
            binding.table == null -> ""
            selected == today && occurrences.isNotEmpty() && remainingCount <= 0 -> "今日已结课"
            selected == today && remainingCount > 0 -> "剩 ${remainingCount} 节"
            occurrences.isNotEmpty() -> "共 ${occurrences.size} 节"
            else -> "无课"
        }
        return present(
            context, widgetId, layout, config, state, tableName, selected, now,
            visibleOccurrences.isNotEmpty(), emptyMessage, loadBackground(context, widgetId, config),
            subtitleText = subtitleText,
            todayColumnMeta = todayColumnMeta,
        )
    }

    internal data class WidgetTextPalette(
        val primary: Int,
        val secondary: Int,
        val accent: Int,
    )

    internal fun resolveTextPalette(context: Context, config: WidgetConfig): WidgetTextPalette {
        val nightMode = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bgAlpha = Color.alpha(config.bgColor)
        val bgLuma = (Color.red(config.bgColor) * 299 + Color.green(config.bgColor) * 587 + Color.blue(config.bgColor) * 114) / 1000
        val hasVisibleSolidBg = config.showBg && config.backgroundUri.isBlank() && bgAlpha >= 80
        val isLightSurface = if (hasVisibleSolidBg) bgLuma >= 140 else !nightMode
        if (config.textColor != Color.BLACK) {
            val custom = if (config.textColorCompose && nightMode) {
                (config.textColor and 0xFF000000.toInt()) or (config.textColor.inv() and 0x00FFFFFF)
            } else {
                config.textColor
            }
            val secondary = (custom and 0x00FFFFFF) or (0xC8 shl 24)
            val accent = if (isLightSurface) 0xFF174BB5.toInt() else 0xFFA8C8FF.toInt()
            return WidgetTextPalette(primary = custom, secondary = secondary, accent = accent)
        }
        val useDarkText = if (config.textColorCompose && nightMode) false else isLightSurface
        return if (useDarkText) {
            WidgetTextPalette(
                primary = 0xFF1B1B1F.toInt(),
                secondary = 0xFF4D525B.toInt(),
                accent = 0xFF174BB5.toInt(),
            )
        } else {
            WidgetTextPalette(
                primary = 0xFFF2F2F5.toInt(),
                secondary = 0xFFD0D3D9.toInt(),
                accent = 0xFFA8C8FF.toInt(),
            )
        }
    }

    /** Render the shell without accessing schedule storage; also exercised through a RemoteViews host. */
    fun present(
        context: Context,
        widgetId: Int,
        layout: WidgetLayout,
        config: WidgetConfig,
        state: WidgetPresentation.SemesterState,
        tableName: String,
        selectedDate: LocalDate,
        now: LocalDateTime,
        hasCourses: Boolean,
        emptyMessage: String,
        background: Bitmap?,
        subtitleText: String = "",
        todayColumnMeta: String = "",
    ): RemoteViews {
        val display = config.normalized()
        val palette = resolveTextPalette(context, display)
        val views = RemoteViews(context.packageName, layoutRes(context, layout))
        bindBackground(views, context, display, background)
        bindHeader(views, context, display, palette, layout, state, tableName, selectedDate, now, hasCourses, subtitleText)
        bindButtons(views, context, widgetId, display, palette, layout)
        bindWeekHeader(views, context, layout, display, palette, selectedDate, now.toLocalDate())
        bindDualColumns(views, context, layout, display, palette, state, selectedDate, now.toLocalDate(), hasCourses, todayColumnMeta)
        bindList(views, context, widgetId, layout, display)
        val emptyTextId = viewId(context, "tv_empty")
        // 空视图文案与点击降级（文档 6.1/6.2）。
        if (!display.showButton && !display.showHeaderArea) {
            views.setTextViewText(emptyTextId, "点击卡片进入设置")
            val emptyNextTextId = viewId(context, "tv_empty_next_day")
            if (emptyNextTextId != 0) views.setTextViewText(emptyNextTextId, "点击卡片进入设置")
        } else if (!hasCourses) {
            views.setTextViewText(emptyTextId, emptyMessage)
        }
        if (emptyTextId != 0) views.setTextColor(emptyTextId, palette.secondary)
        val emptyNextTextId = viewId(context, "tv_empty_next_day")
        if (emptyNextTextId != 0) views.setTextColor(emptyNextTextId, palette.secondary)
        clickIntent(context, widgetId, 90, tab = 1)?.let { views.setOnClickPendingIntent(viewId(context, "widget_root"), it) }
        return views
    }

    /** 渲染「下一节课小胶囊（2x1 / 4x1）」液态玻璃小组件。 */
    fun presentNextPill(
        context: Context,
        widgetId: Int,
        config: WidgetConfig,
        pillState: WidgetPresentation.NextClassPillState,
        wide: Boolean = true,
        background: Bitmap? = null,
    ): RemoteViews {
        val display = config.normalized()
        val palette = resolveTextPalette(context, display)
        val views = RemoteViews(context.packageName, layoutRes(context, WidgetLayout.NEXT_PILL_2X1))
        bindGlassCardBackground(views, context, display, background)

        val titleId = viewId(context, "tv_pill_title")
        val subId = viewId(context, "tv_pill_subtitle")
        val detailId = viewId(context, "tv_pill_detail")
        val badgeId = viewId(context, "tv_pill_badge")
        val barId = viewId(context, "iv_pill_accent_bar")
        val adjustId = viewId(context, "iv_adjust_style")

        views.setTextViewText(titleId, pillState.courseName)
        views.setTextColor(titleId, palette.primary)
        views.setTextViewText(subId, pillState.subtitle)
        views.setTextColor(subId, palette.secondary)

        val showDetail = wide && pillState.detailLine.isNotBlank() && pillState.hasCourse
        views.setViewVisibility(detailId, if (showDetail) View.VISIBLE else View.GONE)
        if (showDetail) {
            views.setTextViewText(detailId, pillState.detailLine)
            views.setTextColor(detailId, palette.accent)
        }

        views.setTextViewText(badgeId, pillState.badgeText)
        val urgentBg = cachedIdentifier(context, if (pillState.isInProgress) "bg_widget_glass_urgent_chip" else "bg_widget_glass_chip", "drawable")
        if (urgentBg != 0) views.setInt(badgeId, "setBackgroundResource", urgentBg)
        views.setTextColor(badgeId, if (pillState.isInProgress) 0xFFDC2626.toInt() else palette.accent)

        val accentArgb = runCatching {
            if (pillState.accentHex.isNotBlank()) Color.parseColor(pillState.accentHex) else palette.accent
        }.getOrDefault(palette.accent)
        if (display.showColor && pillState.hasCourse) {
            views.setViewVisibility(barId, View.VISIBLE)
            views.setInt(barId, "setColorFilter", accentArgb)
        } else {
            views.setViewVisibility(barId, View.GONE)
        }

        if (display.showButton) {
            views.setViewVisibility(adjustId, View.VISIBLE)
            views.setInt(adjustId, "setColorFilter", palette.primary)
            views.setOnClickPendingIntent(adjustId, WidgetIntents.settings(context, widgetId))
        } else {
            views.setViewVisibility(adjustId, View.GONE)
        }
        clickIntent(context, widgetId, 91, tab = 1)?.let { views.setOnClickPendingIntent(viewId(context, "widget_root"), it) }
        return views
    }

    /** 渲染「考试倒计时（2x2）」液态玻璃小组件。 */
    fun presentExamCountdown(
        context: Context,
        widgetId: Int,
        config: WidgetConfig,
        items: List<WidgetPresentation.ExamWidgetItem>,
        totalUpcoming: Int = items.size,
        background: Bitmap? = null,
    ): RemoteViews {
        val display = config.normalized()
        val palette = resolveTextPalette(context, display)
        val views = RemoteViews(context.packageName, layoutRes(context, WidgetLayout.EXAM_COUNTDOWN_2X2))
        bindGlassCardBackground(views, context, display, background)

        val headerId = viewId(context, "tv_exam_header")
        val countId = viewId(context, "tv_exam_count")
        val emptyId = viewId(context, "tv_exam_empty")
        val heroBoxId = viewId(context, "ll_exam_hero")
        val heroTitleId = viewId(context, "tv_exam_hero_title")
        val heroBadgeId = viewId(context, "tv_exam_hero_badge")
        val heroMetaId = viewId(context, "tv_exam_hero_meta")
        val adjustId = viewId(context, "iv_adjust_style")

        views.setTextColor(headerId, palette.primary)
        if (totalUpcoming > 0) {
            views.setViewVisibility(countId, View.VISIBLE)
            views.setTextViewText(countId, "共 ${totalUpcoming} 场")
            views.setTextColor(countId, palette.secondary)
        } else {
            views.setViewVisibility(countId, View.GONE)
        }

        if (items.isEmpty()) {
            views.setViewVisibility(emptyId, View.VISIBLE)
            views.setTextColor(emptyId, palette.secondary)
            views.setViewVisibility(heroBoxId, View.GONE)
            views.setViewVisibility(viewId(context, "ll_exam_row_1"), View.GONE)
            views.setViewVisibility(viewId(context, "ll_exam_row_2"), View.GONE)
        } else {
            views.setViewVisibility(emptyId, View.GONE)
            views.setViewVisibility(heroBoxId, View.VISIBLE)
            val hero = items.first()
            views.setTextViewText(heroTitleId, hero.title)
            views.setTextColor(heroTitleId, palette.primary)
            views.setTextViewText(heroBadgeId, hero.countdownChip)
            val chipDrawable = cachedIdentifier(context, if (hero.isUrgent) "bg_widget_glass_urgent_chip" else "bg_widget_glass_chip", "drawable")
            if (chipDrawable != 0) views.setInt(heroBadgeId, "setBackgroundResource", chipDrawable)
            views.setTextColor(heroBadgeId, if (hero.isUrgent) 0xFFDC2626.toInt() else palette.accent)
            views.setTextViewText(heroMetaId, "${hero.dateTimeText} · ${hero.locationText}")
            views.setTextColor(heroMetaId, palette.secondary)

            val secondary = listOf(
                Triple("ll_exam_row_1", "tv_exam_row_1_title", "tv_exam_row_1_badge"),
                Triple("ll_exam_row_2", "tv_exam_row_2_title", "tv_exam_row_2_badge"),
            )
            secondary.forEachIndexed { idx, (rowName, rowTitleName, rowBadgeName) ->
                val item = items.getOrNull(idx + 1)
                val rowId = viewId(context, rowName)
                if (item == null) {
                    views.setViewVisibility(rowId, View.GONE)
                } else {
                    views.setViewVisibility(rowId, View.VISIBLE)
                    val rowTitleId = viewId(context, rowTitleName)
                    val rowBadgeId = viewId(context, rowBadgeName)
                    views.setTextViewText(rowTitleId, item.title)
                    views.setTextColor(rowTitleId, palette.primary)
                    views.setTextViewText(rowBadgeId, item.countdownChip)
                    views.setTextColor(rowBadgeId, if (item.isUrgent) 0xFFDC2626.toInt() else palette.secondary)
                }
            }
        }

        if (display.showButton) {
            views.setViewVisibility(adjustId, View.VISIBLE)
            views.setInt(adjustId, "setColorFilter", palette.primary)
            views.setOnClickPendingIntent(adjustId, WidgetIntents.settings(context, widgetId))
        } else {
            views.setViewVisibility(adjustId, View.GONE)
        }
        clickIntent(context, widgetId, 92, route = "exams")?.let { views.setOnClickPendingIntent(viewId(context, "widget_root"), it) }
        return views
    }

    private fun bindGlassCardBackground(views: RemoteViews, context: Context, config: WidgetConfig, background: Bitmap?) {
        val picId = viewId(context, "iv_bg_pic")
        val colorId = viewId(context, "iv_bg_color")
        when {
            !config.showBg -> {
                views.setViewVisibility(picId, View.GONE)
                views.setViewVisibility(colorId, View.GONE)
            }
            background != null -> {
                views.setViewVisibility(picId, View.VISIBLE)
                views.setImageViewBitmap(picId, background)
                views.setBoolean(picId, "setClipToOutline", true)
                views.setViewVisibility(colorId, View.GONE)
            }
            config.bgColor == 0xD8FFFFFF.toInt() -> {
                // 默认配置保留布局自带的日/夜自适应液态玻璃 drawable
                views.setViewVisibility(picId, View.GONE)
                views.setViewVisibility(colorId, View.VISIBLE)
            }
            else -> {
                views.setViewVisibility(picId, View.GONE)
                views.setViewVisibility(colorId, View.VISIBLE)
                views.setInt(colorId, "setColorFilter", config.bgColor or 0xFF000000.toInt())
                views.setInt(colorId, "setImageAlpha", Color.alpha(config.bgColor))
            }
        }
    }

    /** 配置 → 绑定课表：timetableId 命中则固定该表，否则回退当前/第一张（跟随自动选表）。 */
    internal suspend fun bindingOf(entry: WidgetEntryPoint, widgetId: Int): WidgetBinding {
        val config = (entry.widgetConfig().get(widgetId) ?: WidgetConfig(widgetId = widgetId)).normalized()
        val timetables = entry.timetableRepository().observeAll().first()
        val current = entry.timetableRepository().observeCurrent().first()
        val bound = timetables.firstOrNull { it.id == config.timetableId }
        return WidgetBinding(config, bound ?: current ?: timetables.firstOrNull(), bound == null)
    }

    private fun bindBackground(views: RemoteViews, context: Context, config: WidgetConfig, background: Bitmap?) {
        val picId = viewId(context, "iv_bg_pic")
        val colorId = viewId(context, "iv_bg_color")
        when {
            !config.showBg -> {
                views.setViewVisibility(picId, View.GONE)
                views.setViewVisibility(colorId, View.GONE)
            }
            background != null -> {
                views.setViewVisibility(picId, View.VISIBLE)
                views.setImageViewBitmap(picId, background)
                views.setBoolean(picId, "setClipToOutline", true)
                views.setViewVisibility(colorId, View.GONE)
            }
            else -> {
                views.setViewVisibility(picId, View.GONE)
                views.setViewVisibility(colorId, View.VISIBLE)
                views.setInt(colorId, "setColorFilter", config.bgColor or 0xFF000000.toInt())
                views.setInt(colorId, "setImageAlpha", Color.alpha(config.bgColor))
            }
        }
    }

    private fun bindHeader(
        views: RemoteViews,
        context: Context,
        config: WidgetConfig,
        palette: WidgetTextPalette,
        layout: WidgetLayout,
        state: WidgetPresentation.SemesterState,
        tableName: String,
        selectedDate: LocalDate,
        now: LocalDateTime,
        hasCourses: Boolean,
        subtitleText: String,
    ) {
        val headerId = viewId(context, "rl_header")
        views.setViewVisibility(headerId, if (config.showHeaderArea) View.VISIBLE else View.GONE)
        if (!config.showHeaderArea) return
        val dateId = viewId(context, "tv_date")
        if (config.showDate) {
            views.setViewVisibility(dateId, View.VISIBLE)
            val dateText = dateLabel(selectedDate)
            views.setTextViewText(dateId, dateText)
            views.setTextViewTextSize(dateId, TypedValue.COMPLEX_UNIT_SP, config.headerTextSizeSp.toFloat())
            views.setTextColor(dateId, palette.primary)
        } else {
            views.setViewVisibility(dateId, View.GONE)
        }
        val tableNameId = viewId(context, "tv_table_name")
        views.setTextViewText(tableNameId, tableName)
        views.setTextColor(tableNameId, palette.secondary)
        val weekDayId = viewId(context, "tv_week_day")
        val resolvedSubtitle = if (tableName == "请先创建课表") {
            ""
        } else {
            subtitleText.ifBlank {
                WidgetPresentation.headerSubtitle(
                    state,
                    selectedDate,
                    now.toLocalDate(),
                    remainingCount = if (hasCourses) 1 else 0,
                    totalCount = if (hasCourses) 1 else 0,
                    weekView = layout == WidgetLayout.WEEK_4X4,
                )
            }
        }
        if (resolvedSubtitle.isNotBlank()) {
            views.setViewVisibility(weekDayId, View.VISIBLE)
            views.setTextViewText(weekDayId, resolvedSubtitle)
            views.setTextColor(weekDayId, palette.secondary)
        } else {
            views.setViewVisibility(weekDayId, View.GONE)
        }
    }

    private fun bindDualColumns(
        views: RemoteViews,
        context: Context,
        layout: WidgetLayout,
        config: WidgetConfig,
        palette: WidgetTextPalette,
        state: WidgetPresentation.SemesterState,
        selectedDate: LocalDate,
        today: LocalDate,
        hasCourses: Boolean,
        todayColumnMeta: String,
    ) {
        if (layout != WidgetLayout.TODAY_4X4) return
        val todayTitleId = viewId(context, "tv_col_today_title")
        val todayMetaId = viewId(context, "tv_col_today_meta")
        val nextTitleId = viewId(context, "tv_col_next_title")
        val nextMetaId = viewId(context, "tv_col_next_meta")
        val nextEmptyTextId = viewId(context, "tv_empty_next_day")
        if (todayTitleId != 0) {
            views.setTextViewText(todayTitleId, WidgetPresentation.columnHeaderTitle(selectedDate, today))
            views.setTextColor(todayTitleId, palette.primary)
        }
        if (todayMetaId != 0) {
            views.setTextViewText(todayMetaId, todayColumnMeta.ifBlank { if (hasCourses) "今日课程" else "无课" })
            views.setTextColor(todayMetaId, palette.secondary)
        }
        if (nextTitleId != 0) {
            views.setTextViewText(nextTitleId, WidgetPresentation.columnHeaderTitle(selectedDate.plusDays(1), today))
            views.setTextColor(nextTitleId, palette.primary)
        }
        if (nextMetaId != 0) {
            views.setTextColor(nextMetaId, palette.secondary)
        }
        if (nextEmptyTextId != 0) {
            views.setTextViewText(
                nextEmptyTextId,
                WidgetPresentation.emptyMessage(state, selectedDate.plusDays(1), today, modern = true, weekView = false),
            )
            views.setTextColor(nextEmptyTextId, palette.secondary)
        }
    }

    private fun bindButtons(
        views: RemoteViews,
        context: Context,
        widgetId: Int,
        config: WidgetConfig,
        palette: WidgetTextPalette,
        layout: WidgetLayout,
    ) {
        val adjustId = viewId(context, "iv_adjust_style")
        val prevId = viewId(context, "widget_prev_day")
        val todayId = viewId(context, "widget_today")
        val nextId = viewId(context, "widget_next_day")
        if (config.showButton) {
            views.setViewVisibility(adjustId, View.VISIBLE)
            views.setViewVisibility(prevId, View.VISIBLE)
            views.setViewVisibility(todayId, View.VISIBLE)
            views.setViewVisibility(nextId, View.VISIBLE)
            if (adjustId != 0) views.setInt(adjustId, "setColorFilter", palette.primary)
            views.setTextColor(prevId, palette.primary)
            views.setTextColor(todayId, palette.primary)
            views.setTextColor(nextId, palette.primary)
            views.setOnClickPendingIntent(adjustId, WidgetIntents.settings(context, widgetId))
            for ((name, action) in listOf("widget_prev_day" to WidgetIntents.PREVIOUS, "widget_next_day" to WidgetIntents.NEXT, "widget_today" to WidgetIntents.TODAY)) {
                WidgetIntents.navigation(context, widgetId, action)?.let { views.setOnClickPendingIntent(viewId(context, name), it) }
            }
        } else {
            views.setViewVisibility(adjustId, View.GONE)
            views.setViewVisibility(prevId, View.GONE)
            views.setViewVisibility(todayId, View.GONE)
            views.setViewVisibility(nextId, View.GONE)
            // 关闭按钮后的降级绑定（文档 6.1）：顶栏整体打开设置页。
            if (config.showHeaderArea) views.setOnClickPendingIntent(viewId(context, "rl_header"), WidgetIntents.settings(context, widgetId))
        }
    }

    private fun bindWeekHeader(
        views: RemoteViews,
        context: Context,
        layout: WidgetLayout,
        config: WidgetConfig,
        palette: WidgetTextPalette,
        selectedDate: LocalDate,
        today: LocalDate,
    ) {
        val headerId = viewId(context, "ll_week_header")
        if (layout != WidgetLayout.WEEK_4X4) {
            views.setViewVisibility(headerId, View.GONE)
            return
        }
        views.setViewVisibility(headerId, View.VISIBLE)
        val monthId = viewId(context, "tv_month")
        views.setTextViewText(monthId, "${selectedDate.monthValue}月")
        if (monthId != 0) views.setTextColor(monthId, palette.secondary)
        val monday = WidgetPresentation.mondayOf(selectedDate)
        for (index in weekdayLabels.indices) {
            val columnId = viewId(context, "tv_weekday$index")
            val enabled = (index != 5 || config.showSat) && (index != 6 || config.showSun)
            views.setViewVisibility(columnId, if (enabled) View.VISIBLE else View.GONE)
            if (enabled) {
                val dayDate = monday.plusDays(index.toLong())
                views.setTextViewText(columnId, "${weekdayLabels[index]}\n${dayDate.dayOfMonth}")
                val textColor = if (dayDate == today) palette.accent else palette.secondary
                if (columnId != 0) views.setTextColor(columnId, textColor)
            }
        }
    }

    private fun bindList(views: RemoteViews, context: Context, widgetId: Int, layout: WidgetLayout, config: WidgetConfig) {
        if (layout == WidgetLayout.TODAY_4X4) {
            val leftId = viewId(context, "lv_today")
            val rightId = viewId(context, "lv_next_day")
            val rightEmptyId = viewId(context, "empty_next_day")
            views.setRemoteAdapter(leftId, widgetServiceIntent(context, TodayModernService::class.java, widgetId, 0))
            views.setRemoteAdapter(rightId, widgetServiceIntent(context, TodayModernService::class.java, widgetId, 1))
            views.setEmptyView(leftId, android.R.id.empty)
            if (rightEmptyId != 0) views.setEmptyView(rightId, rightEmptyId)
            bindListTemplate(views, context, widgetId, leftId, config)
            bindListTemplate(views, context, widgetId, rightId, config)
            return
        }
        val listId = viewId(context, "lv_schedule")
        val service = when (layout) {
            WidgetLayout.WEEK_4X4 -> ScheduleAppWidgetService::class.java
            WidgetLayout.TODAY_2X4 -> TodayColorfulService::class.java
            else -> TodayModernService::class.java
        }
        views.setRemoteAdapter(listId, widgetServiceIntent(context, service, widgetId, 0))
        views.setEmptyView(listId, android.R.id.empty)
        bindListTemplate(views, context, widgetId, listId, config)
    }

    /** 列表项模板：正常打开 MainActivity；顶栏与按钮都被隐藏时降级为打开设置页（文档 6.1）。 */
    private fun bindListTemplate(views: RemoteViews, context: Context, widgetId: Int, listId: Int, config: WidgetConfig) {
        if (listId == 0) return
        val template = if (!config.showButton && !config.showHeaderArea) {
            WidgetIntents.settings(context, widgetId)
        } else {
            clickIntent(context, widgetId, 89, tab = 1)
        }
        template?.let { views.setPendingIntentTemplate(listId, it) }
    }

    internal suspend fun resolve(
        port: EffectiveSchedulePort,
        table: Timetable,
        range: ClosedRange<LocalDate>,
        followCurrent: Boolean,
    ): List<CourseOccurrence> = runCatching {
        val result = mutableListOf<CourseOccurrence>()
        var date = range.start
        while (date <= range.endInclusive) {
            // 跟随当前课表的组件走逐日自动选表（F07）；显式绑定的组件只读该课表。
            if (followCurrent) {
                result += port.resolveDay(date).occurrences
            } else {
                result += port.resolveRange(table.id, date..date)
            }
            date = date.plusDays(1)
        }
        result
    }.getOrDefault(emptyList())

    /** 课程行时间文本（标尺模式使用全区间连字符）。 */
    fun timeRange(occurrence: CourseOccurrence, separator: String = " - "): String = when {
        occurrence.startMinute == null -> "时间待定"
        occurrence.endMinute == null -> formatMinute(occurrence.startMinute!!)
        else -> "${formatMinute(occurrence.startMinute!!)}$separator${formatMinute(occurrence.endMinute!!)}"
    }

    /** 自定义背景图降采样加载（文档 5.3）；文件缺失或解码失败返回 null。 */
    private fun loadBackground(context: Context, widgetId: Int, config: WidgetConfig): Bitmap? {
        if (!config.showBg || config.backgroundUri.isBlank()) return null
        return runCatching {
            WakeUpCanvasRenderer.loadBackgroundBitmap(File(context.filesDir, "widget_${widgetId}_bg.jpg"))
        }.getOrNull()
    }

    private fun clickIntent(
        context: Context,
        widgetId: Int,
        slot: Int,
        tab: Int? = null,
        courseId: Long? = null,
        route: String? = null,
    ): PendingIntent? {
        val intent = LaunchIntents.launchIntent(context, tab = tab, route = route, courseId = courseId) ?: return null
        intent.data = android.net.Uri.parse("iyango://widget/$widgetId/course/$slot")
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun formatMinute(minute: Int): String =
        LocalTime.of(minute / 60, minute % 60).format(clockFormat)

    private fun dateLabel(date: LocalDate): String =
        "${date.monthValue}月${date.dayOfMonth}日 ${weekdayLabels[date.dayOfWeek.value - 1]}"

    private val resourceIdCache = ConcurrentHashMap<String, Int>()

    internal fun cachedIdentifier(context: Context, name: String, defType: String): Int {
        val pkg = context.packageName
        val key = "$pkg:$defType/$name"
        resourceIdCache[key]?.let { return it }
        val resolved = context.resources.getIdentifier(name, defType, pkg)
        if (resolved != 0) resourceIdCache[key] = resolved
        return resolved
    }

    private fun layoutRes(context: Context, layout: WidgetLayout): Int {
        val name = when (layout) {
            WidgetLayout.PREVIEW_2X2 -> "widget_preview"
            WidgetLayout.TODAY_2X4 -> "widget_wakeup_classic_shell"
            WidgetLayout.TODAY_4X4 -> "widget_today_4x4"
            WidgetLayout.RULER_4X4 -> "widget_ruler_4x4"
            WidgetLayout.WEEK_4X4 -> "widget_wakeup_classic_shell"
            WidgetLayout.NEXT_PILL_2X1 -> "widget_next_pill"
            WidgetLayout.EXAM_COUNTDOWN_2X2 -> "widget_exam_countdown"
        }
        return cachedIdentifier(context, name, "layout")
    }

    internal fun viewId(context: Context, name: String): Int =
        cachedIdentifier(context, name, "id")
}

/** 五种组件的公共生命周期：更新、尺寸变化与删除清理。 */
abstract class BaseScheduleWidgetProvider(
    private val layout: WidgetLayout,
) : android.appwidget.AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in WidgetIntents.navigationActions) {
            val id = WidgetIntents.widgetId(intent)
            if (WidgetIntents.layout(context, id, javaClass.name) != layout) return
            // 周视图卡片按 ±7 天翻周，其余卡片保持任意日期连续翻页。
            if (layout == WidgetLayout.WEEK_4X4) {
                WidgetDayStore(context).navigateWeek(id, intent.action!!)
            } else {
                WidgetDayStore(context).navigate(id, intent.action!!)
            }
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try { ScheduleWidgets.update(context, id, layout) } finally { pending?.finish() }
            }
            return
        }
        val manager = AppWidgetManager.getInstance(context)
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE,
            WidgetIntents.ENABLE_AND_UPDATE -> {
                val ids = runCatching { intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS) }.getOrNull() ?: return
                val owned = WidgetIntents.ownedIds(context, ids, javaClass.name)
                if (owned.isNotEmpty()) {
                    if (intent.action == WidgetIntents.ENABLE_AND_UPDATE) onEnabled(context)
                    onUpdate(context, manager, owned)
                }
            }
            AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED -> {
                val id = WidgetIntents.widgetId(intent)
                if (WidgetIntents.layout(context, id, javaClass.name) != layout) return
                val options = runCatching { intent.getBundleExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS) }.getOrNull() ?: return
                onAppWidgetOptionsChanged(context, manager, id, options)
            }
            AppWidgetManager.ACTION_APPWIDGET_DELETED -> {
                val id = WidgetIntents.widgetId(intent)
                if (id <= 0) return
                // The host may already have unbound this id. The stored layout/owner is checked in onDeleted.
                if (manager.getAppWidgetInfo(id) != null && WidgetIntents.layout(context, id, javaClass.name) != layout) return
                onDeleted(context, intArrayOf(id))
            }
            AppWidgetManager.ACTION_APPWIDGET_RESTORED -> {
                val old = runCatching { intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS) }.getOrNull() ?: return
                val new = runCatching { intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS) }.getOrNull() ?: return
                if (old.size != new.size || old.any { it <= 0 }) return
                val pairs = old.zip(new.toList()).filter { WidgetIntents.layout(context, it.second, javaClass.name) == layout }
                if (pairs.isEmpty()) return
                onRestored(context, pairs.map { it.first }.toIntArray(), pairs.map { it.second }.toIntArray())
                onUpdate(context, manager, pairs.map { it.second }.toIntArray())
            }
            AppWidgetManager.ACTION_APPWIDGET_ENABLED -> onEnabled(context)
            AppWidgetManager.ACTION_APPWIDGET_DISABLED -> onDisabled(context)
            else -> return
        }
    }

    override fun onEnabled(context: Context) { WidgetRefreshAlarm.schedule(context) }
    override fun onDisabled(context: Context) { WidgetRefreshAlarm.schedule(context) }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val ownedIds = WidgetIntents.ownedIds(context, appWidgetIds, javaClass.name)
        if (ownedIds.isEmpty()) return
        ownedIds.forEach { WidgetDayStore(context).rememberOwner(it, javaClass.name) }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ownedIds.forEach { id -> runCatching { ScheduleWidgets.update(context, id, layout) } }
                WidgetRefreshAlarm.schedule(context)
            } finally {
                pending?.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        if (WidgetIntents.layout(context, appWidgetId, javaClass.name) != layout) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runCatching { ScheduleWidgets.update(context, appWidgetId, layout, newOptions) }
            } finally {
                pending?.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val manager = AppWidgetManager.getInstance(context)
        val candidates = appWidgetIds.filter { it > 0 && (manager.getAppWidgetInfo(it) == null || WidgetIntents.layout(context, it, javaClass.name) == layout) }
        candidates.forEach(ScheduleWidgets::invalidate)
        val store = WidgetDayStore(context)
        candidates.filter { store.owner(it) == javaClass.name }.forEach(store::delete)
        WidgetRefreshAlarm.schedule(context, candidates.toSet())
        if (candidates.isEmpty()) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val entry = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
                candidates.forEach { id ->
                    ScheduleWidgets.remove(id) {
                        val config = entry.widgetConfig().get(id)
                        if (config?.layout == layout) entry.widgetConfig().delete(id)
                        if (config == null || config.layout == layout) {
                            store.delete(id)
                            runCatching { File(context.filesDir, "widget_${id}_bg.jpg").delete() }
                        }
                    }
                }
            } finally { pending?.finish() }
        }
    }

}

class PreviewScheduleWidgetProvider : BaseScheduleWidgetProvider(WidgetLayout.PREVIEW_2X2)

class TodaySmallScheduleWidgetProvider : BaseScheduleWidgetProvider(WidgetLayout.TODAY_2X4)

class TodayLargeScheduleWidgetProvider : BaseScheduleWidgetProvider(WidgetLayout.TODAY_4X4)

class RulerScheduleWidgetProvider : BaseScheduleWidgetProvider(WidgetLayout.RULER_4X4)

/** 周视图整表卡片（对齐 WakeUp ScheduleAppWidget）。 */
class WeekScheduleWidgetProvider : BaseScheduleWidgetProvider(WidgetLayout.WEEK_4X4)

/** 下一节课液态玻璃小胶囊（2x1 / 4x1）。 */
class NextClassPillWidgetProvider : BaseScheduleWidgetProvider(WidgetLayout.NEXT_PILL_2X1)

/** 考试倒计时液态玻璃卡片（2x2）。 */
class ExamCountdownWidgetProvider : BaseScheduleWidgetProvider(WidgetLayout.EXAM_COUNTDOWN_2X2)

/** One singleton collector. EffectiveSchedule's selected range invalidates for every source table. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@Singleton
class AndroidWidgetUpdater @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val timetableRepository: TimetableRepositoryPort,
    private val effectiveSchedule: EffectiveSchedulePort,
    private val widgetConfig: WidgetConfigPort,
    private val examRepository: ExamRepositoryPort,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val observedDay = MutableStateFlow(LocalDate.now())
    init {
        scope.launch {
            observedDay.flatMapLatest { today ->
                combine(
                    timetableRepository.observeAll(),
                    widgetConfig.observeAll(),
                    effectiveSchedule.observeSelectedRange(today.minusDays(7)..today.plusDays(7)),
                    examRepository.observeAll(),
                ) { _, _, _, _ -> Unit }
            }.debounce(500).collect { updateAll() }
        }
    }
    suspend fun updateAll() {
        observedDay.value = LocalDate.now()
        WidgetRefreshAlarm.refreshAll(context)
        NextClassTileService.requestListeningState(context)
    }
}
