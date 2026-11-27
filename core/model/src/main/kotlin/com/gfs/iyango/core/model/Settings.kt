package com.gfs.iyango.core.model

/** 课程卡上可显示的信息字段。 */
enum class CourseInfoField {
    TEACHER,
    CLASSROOM,
    TIME,
}

/** 课程卡文字对齐。 */
enum class CellTextAlign {
    START,
    CENTER,
}

enum class ScheduleHeaderOrder { WEEKDAY_DATE, DATE_WEEKDAY }

/** 课表网格辅助线线型。 */
enum class GridLineStyle { SOLID, DASHED, DOTTED }

/** 周课表展示视图模式。 */
enum class ScheduleViewMode { GRID, TIMELINE }

/**
 * 课表显示设置（有版本，可随设置备份迁移）。
 *
 * 全局默认与“按课表覆盖”共用本类型；合并规则见 [DisplaySettingsBundle]。
 * 所有选项只影响呈现，不修改任何课程/作息数据行。
 */
data class ScheduleDisplaySettings(
    val version: Int = 1,
    val showWeekends: Boolean = true,
    val showOffWeek: Boolean = true,
    val headerOrder: ScheduleHeaderOrder = ScheduleHeaderOrder.WEEKDAY_DATE,
    val showExams: Boolean = false,
    val showLunchBreak: Boolean = false,
    val mergeAdjacent: Boolean = false,
    val showTeacher: Boolean = true,
    val showClassroom: Boolean = true,
    val showTime: Boolean = false,
    val courseInfoOrder: List<CourseInfoField> =
        listOf(CourseInfoField.CLASSROOM, CourseInfoField.TEACHER, CourseInfoField.TIME),
    /** 固定行高（dp）；0 表示按可用窗口自适应并遵守 [minRowHeightDp]。 */
    val rowHeightDp: Int = 0,
    val minRowHeightDp: Int = 56,
    val cornerRadiusDp: Int = 10,
    /** 单元格底色不透明度 0..1。 */
    val cellAlpha: Float = 1.0f,
    /** 非本周（单双周/轮换跳过）块的不透明度 0..1。 */
    val offWeekAlpha: Float = 0.38f,
    val textSizeSp: Float = 12.0f,
    val align: CellTextAlign = CellTextAlign.CENTER,
    val showGridLines: Boolean = true,
    /** 表头底色（#RRGGBB）；空使用主题色。 */
    val headerBackgroundHex: String = "",
    /** 网格线颜色（#RRGGBB）；空使用主题色。 */
    val gridLineHex: String = "",
    /** 稳定课程组键 → 颜色覆盖（由用户“重新配色”或手动选择产生）。 */
    val courseColorOverrides: Map<String, String> = emptyMap(),
    val paletteVersion: Int = 1,
    /** 教室前是否打印 “@” 前缀（关闭后只显示地点文本）。 */
    val showAtPrefix: Boolean = true,
    /** 表头星期/日期字号（sp）。 */
    val headerTextSizeSp: Float = 12f,
    /** 网格线宽（dp）。 */
    val gridLineWidthDp: Float = 1f,
    /** 网格线是否画成虚线。 */
    val gridLineDashed: Boolean = false,
    /** 网格线样式（实线 / 虚线 / 点线）；与 [gridLineDashed] 保持双向同步。 */
    val gridLineStyle: GridLineStyle = if (gridLineDashed) GridLineStyle.DASHED else GridLineStyle.SOLID,
    /** 课程卡文字是否垂直居中（false = 顶部对齐）。 */
    val textVCenter: Boolean = false,
    /** 课程卡边框宽度（dp）；0 = 不画边框。 */
    val borderWidthDp: Float = 0f,
    /** 课程卡边框颜色（#RRGGBB）；仅 [borderUseCourseColor] = false 时生效，空跟随主题。 */
    val borderColorHex: String = "",
    /** 课程卡边框是否使用课程自身颜色。 */
    val borderUseCourseColor: Boolean = true,
    /** 课程卡边框是否画成虚线。 */
    val borderDashed: Boolean = false,
    /** 网格区纯色背景（#RRGGBB）；空 = 跟随主题。 */
    val backgroundColorHex: String = "",
    /** 浅色模式的 12 色板（#RRGGBB）；不足 12 项或某槽为空时回退内置预设。 */
    val paletteLight: List<String> = emptyList(),
    /** 深色模式的 12 色板（#RRGGBB）；规则同 [paletteLight]。 */
    val paletteDark: List<String> = emptyList(),
    /** 周课表视图模式（标准网格 / 按分钟比例时间轴）。 */
    val viewMode: ScheduleViewMode = ScheduleViewMode.GRID,
    /** 是否显示晚休分隔行。 */
    val showEveningBreak: Boolean = false,
) {
    /** 规范化范围，避免损坏配置把界面撑爆。 */
    fun sanitized(): ScheduleDisplaySettings {
        val resolvedStyle = if (gridLineStyle == GridLineStyle.SOLID && gridLineDashed) {
            GridLineStyle.DASHED
        } else {
            gridLineStyle
        }
        return copy(
            rowHeightDp = rowHeightDp.coerceIn(0, 240),
            minRowHeightDp = minRowHeightDp.coerceIn(40, 160),
            cornerRadiusDp = cornerRadiusDp.coerceIn(0, 40),
            cellAlpha = cellAlpha.coerceIn(0.1f, 1f),
            offWeekAlpha = offWeekAlpha.coerceIn(0.1f, 1f),
            textSizeSp = textSizeSp.coerceIn(9f, 22f),
            headerTextSizeSp = headerTextSizeSp.coerceIn(9f, 20f),
            gridLineWidthDp = gridLineWidthDp.coerceIn(0.5f, 4f),
            gridLineStyle = resolvedStyle,
            gridLineDashed = resolvedStyle == GridLineStyle.DASHED,
            borderWidthDp = borderWidthDp.coerceIn(0f, 4f),
            courseInfoOrder = courseInfoOrder.ifEmpty {
                listOf(CourseInfoField.CLASSROOM, CourseInfoField.TEACHER, CourseInfoField.TIME)
            },
            courseColorOverrides = courseColorOverrides
                .filterKeys { it.isNotBlank() }
                .filterValues { isHexColor(it) },
            paletteLight = sanitizePalette(paletteLight),
            paletteDark = sanitizePalette(paletteDark),
        )
    }

    /** 色板每槽必须是 #RRGGBB；非法槽置空，尾部空槽裁掉，最长 12 项。 */
    private fun sanitizePalette(palette: List<String>): List<String> =
        palette.take(12)
            .map { it.trim().uppercase().takeIf { value -> isHexColor(value) }.orEmpty() }
            .dropLastWhile { it.isEmpty() }

    private fun isHexColor(value: String): Boolean =
        value.length == 7 && value.startsWith("#") &&
            value.drop(1).all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
}

/** 全局显示设置 + 按课表覆盖。 */
data class DisplaySettingsBundle(
    val global: ScheduleDisplaySettings = ScheduleDisplaySettings(),
    val byTimetable: Map<Long, ScheduleDisplaySettings> = emptyMap(),
) {
    /** 取某课表的有效配置：按课表覆盖优先，否则全局。 */
    fun forTimetable(timetableId: Long): ScheduleDisplaySettings =
        (byTimetable[timetableId] ?: global).sanitized()
}

/** 已置顶的常用教学楼项。 */
data class PinnedClassroomBuilding(
    val campusCode: String = "",
    val campusName: String = "",
    val buildingCode: String = "",
    val buildingName: String = "",
) {
    /** 跨校区唯一复合键。 */
    val key: String get() = "$campusCode|$buildingCode"
}

/** 上课自动静音/免打扰模式。 */
enum class AutoSilentMode {
    OFF,
    VIBRATE,
    SILENT,
    DND,
}

/** 提醒偏好。 */
data class ReminderSettings(
    val enabled: Boolean = false,
    /** 提前量：5/10/15 分钟。 */
    val leadMinutes: Int = 10,
    /** 被用户关闭提醒的课程组键集合。 */
    val disabledCourseKeys: Set<String> = emptySet(),
    /** 是否已向用户解释过精确闹钟能力。 */
    val exactAlarmPrompted: Boolean = false,
    /** 是否在课前至下课期间显示常驻实时进度条与倒计时。 */
    val ongoingProgressEnabled: Boolean = true,
    /** 是否开启新出分变动通知。 */
    val gradeNotifyEnabled: Boolean = false,
    /** 后台自动检查周期（小时）：支持 2 / 4 / 6 / 12。 */
    val gradeCheckIntervalHours: Int = 4,
    /** 锁屏界面隐藏课程名与具体分数。 */
    val gradeNotifyHideScoreOnLockscreen: Boolean = true,
    /** 期末出分冲刺开关（仅在 08:00-22:00 高频拉取当前学期，到期自动降回常规周期）。 */
    val gradeSprintEnabled: Boolean = false,
    /** 期末出分冲刺频率（分钟）：支持 15 / 30。 */
    val gradeSprintIntervalMinutes: Int = 30,
    /** 期末出分冲刺截止时间戳（毫秒）；0 表示未激活截止。 */
    val gradeSprintUntilMillis: Long = 0L,
    /** 期末出分冲刺持续天数：支持 3 / 5 / 7。 */
    val gradeSprintDurationDays: Int = 7,
    /** Wi-Fi 连接、充电或切回前台时顺手触发成绩检查。 */
    val gradeContextTriggerEnabled: Boolean = true,
    /** 法定节假日自动跳过课程提醒。 */
    val skipRemindersOnHolidays: Boolean = true,
    /** 上课自动静音/免打扰模式。 */
    val autoSilentMode: AutoSilentMode = AutoSilentMode.OFF,
    /** 豁免上课自动静音的课程键集合。 */
    val autoSilentExemptCourseKeys: Set<String> = emptySet(),
    /** 法定节假日跳过自动静音。 */
    val autoSilentSkipOnHolidays: Boolean = true,
) {
    fun sanitized(): ReminderSettings = copy(
        leadMinutes = when (leadMinutes) {
            5, 10, 15 -> leadMinutes
            else -> 10
        },
        disabledCourseKeys = disabledCourseKeys.filter { it.isNotBlank() }.toSet(),
        autoSilentExemptCourseKeys = autoSilentExemptCourseKeys.filter { it.isNotBlank() }.toSet(),
        gradeCheckIntervalHours = when (gradeCheckIntervalHours) {
            2, 4, 6, 12 -> gradeCheckIntervalHours
            else -> 4
        },
        gradeSprintIntervalMinutes = when (gradeSprintIntervalMinutes) {
            15, 30 -> gradeSprintIntervalMinutes
            else -> 30
        },
        gradeSprintUntilMillis = gradeSprintUntilMillis.coerceAtLeast(0L),
        gradeSprintDurationDays = when (gradeSprintDurationDays) {
            3, 5, 7 -> gradeSprintDurationDays
            else -> 7
        },
    )
}

/** 系统日历同步偏好。 */
data class CalendarSettings(
    val enabled: Boolean = false,
    /** 用户选择的可写日历 id；-1 表示尚未选择。 */
    val calendarId: Long = -1,
    /** 安装实例的日历所有权标记。 */
    val ownerId: String = "",
    val lastSyncAt: Long = 0,
    val lastError: String = "",
)

/** 导航布局。 */
enum class NavigationLayout {
    AUTO,
    BOTTOM,
    SIDE,
}

/** 次级/三级灰色文字可读性增强模式。 */
enum class GrayTextMode {
    DEFAULT,
    AUTO_ENHANCED,
    HIGH_CONTRAST_DARK,
    HIGH_CONTRAST_LIGHT,
    CUSTOM,
}

/** 壁纸切换策略。 */
enum class WallpaperSwitchMode {
    /** 跟随浅色/深色主题自动切换亮/暗壁纸。 */
    FOLLOW_THEME,
    /** 根据日出日落时间自动切换亮/暗壁纸。 */
    SUNRISE_SUNSET,
    /** 按早晨、午后、傍晚、深夜四时段切换壁纸。 */
    FOUR_PERIODS,
    /** 每天自动轮播下一张壁纸。 */
    CAROUSEL_DAILY,
    /** 每次进入应用自动轮播下一张壁纸。 */
    CAROUSEL_LAUNCH,
}

/** Bing 每日一图自动应用目标槽位。 */
enum class BingWallpaperTarget(val slot: String, val dark: Boolean, val label: String) {
    GLOBAL_LIGHT("global", false, "全局亮色"),
    GLOBAL_DARK("global", true, "全局暗色"),
    TIMETABLE_LIGHT("timetable", false, "课表亮色"),
    TIMETABLE_DARK("timetable", true, "课表暗色"),
}

/** 四时段壁纸对应时间段。 */
enum class DayPeriod(val slotKey: String, val label: String, val hoursLabel: String) {
    MORNING("period_morning", "早晨", "06:00–11:59"),
    AFTERNOON("period_afternoon", "午后", "12:00–16:59"),
    EVENING("period_evening", "傍晚", "17:00–20:59"),
    NIGHT("period_night", "深夜", "21:00–05:59"),
}

/** 应用级个性化偏好（不含主题，主题见 ThemeSettings）。 */
data class AppPreferences(
    val version: Int = 1,
    /** 默认启动页：0=今日 1=课表 2=成绩 3=我的。 */
    val defaultStartTab: Int = 0,
    val navigationLayout: NavigationLayout = NavigationLayout.AUTO,
    val animationsEnabled: Boolean = true,
    /** 应用级预测性返回动画开关（仅 Android 13+ 显示；需同时满足系统级预测性返回已开启）。 */
    val predictiveBackEnabled: Boolean = true,
    /** 全局壁纸（浅色）在私有目录中的相对路径；空=无壁纸。 */
    val wallpaperPath: String = "",
    /** 全局壁纸（深色）相对路径；空=跟随浅色路径。 */
    val wallpaperPathDark: String = "",
    /** 壁纸遮罩不透明度 0..1。 */
    val wallpaperScrim: Float = 0.18f,
    /** 壁纸模糊强度 0..1（渲染层映射半径；深于平台的版本自动忽略）。 */
    val wallpaperBlur: Float = 0f,
    /** 独立课表背景（浅色）相对路径；空=跟随全局。 */
    val timetableWallpaperPath: String = "",
    /** 独立课表背景（深色）相对路径；空=跟随浅色路径。 */
    val timetableWallpaperPathDark: String = "",
    val home: HomePreferences = HomePreferences(),
    /** 本机开发者入口解锁状态；不随设置备份导出。 */
    val developerModeEnabled: Boolean = false,
    /** 自定义字体文件相对路径（应用私有目录）；空=使用系统默认字体。 */
    val customFontPath: String = "",
    /** 自定义字体展示名。 */
    val customFontName: String = "",
    /** 全局字体缩放倍率 0.85..1.30。 */
    val fontScaleFactor: Float = 1.0f,
    /** 灰字可读性增强模式。 */
    val grayTextMode: GrayTextMode = GrayTextMode.DEFAULT,
    /** [GrayTextMode.CUSTOM] 下使用的 #RRGGBB 字色。 */
    val grayTextCustomHex: String = "",
    /** 用户收藏的自定义主题强调色预设（最多 8 个 #RRGGBB）。 */
    val savedAccentPresets: List<String> = emptyList(),
    /** 全局壁纸切换策略。 */
    val wallpaperSwitchMode: WallpaperSwitchMode = WallpaperSwitchMode.FOLLOW_THEME,
    /** 日出日落城市 ID 或 "custom"。 */
    val sunriseCityId: String = "beijing",
    /** 手动日出时刻（距当日 00:00 的分钟数 0..1439）。 */
    val sunriseCustomMinutes: Int = 6 * 60 + 30,
    /** 手动日落时刻（距当日 00:00 的分钟数 0..1439）。 */
    val sunsetCustomMinutes: Int = 18 * 60 + 30,
    /** 日出日落切换壁纸时是否同时联动应用深浅色主题。 */
    val sunriseSyncTheme: Boolean = false,
    /** 四时段壁纸相对路径。 */
    val periodMorningPath: String = "",
    val periodAfternoonPath: String = "",
    val periodEveningPath: String = "",
    val periodNightPath: String = "",
    /** 多图轮播相对路径列表（最多 12 张）。 */
    val carouselPaths: List<String> = emptyList(),
    /** 当前启动轮播索引。 */
    val carouselIndex: Int = 0,
    /** 壁纸智能取色开关（切换壁纸后自动更新主题强调色）。 */
    val wallpaperDynamicColor: Boolean = false,
    /** Bing 每日一图每日自动更新开关。 */
    val bingAutoUpdateEnabled: Boolean = false,
    /** Bing 自动更新仅限 Wi‑Fi。 */
    val bingAutoUpdateWifiOnly: Boolean = true,
    /** Bing 自动更新应用目标槽位。 */
    val bingAutoUpdateTarget: BingWallpaperTarget = BingWallpaperTarget.GLOBAL_LIGHT,
    /** 上次自动更新 Bing 壁纸的日期（yyyy-MM-dd）。 */
    val bingLastAutoUpdateDate: String = "",
    /** 常用置顶教学楼列表（最多 8 项）。 */
    val pinnedClassroomBuildings: List<PinnedClassroomBuilding> = emptyList(),
    /** 双人课表对照目标课表 id（0 = 未开启）。 */
    val partnerTimetableId: Long = 0L,
    /** 双人课表对照称呼（默认 "TA"）。 */
    val partnerLabel: String = "TA",
) {
    fun sanitized(): AppPreferences = copy(
        defaultStartTab = defaultStartTab.coerceIn(0, 3),
        wallpaperScrim = wallpaperScrim.coerceIn(0f, 0.8f),
        wallpaperBlur = wallpaperBlur.coerceIn(0f, 1f),
        home = home.sanitized(),
        fontScaleFactor = fontScaleFactor.coerceIn(0.85f, 1.30f),
        grayTextCustomHex = grayTextCustomHex.trim().uppercase()
            .takeIf { it.length == 7 && it.startsWith("#") && it.drop(1).all { c -> c.isDigit() || c in 'A'..'F' } }
            .orEmpty(),
        savedAccentPresets = savedAccentPresets
            .map { it.trim().uppercase() }
            .filter { it.length == 7 && it.startsWith("#") && it.drop(1).all { c -> c.isDigit() || c in 'A'..'F' } }
            .distinct()
            .take(8),
        sunriseCityId = sunriseCityId.trim().ifBlank { "beijing" },
        sunriseCustomMinutes = sunriseCustomMinutes.coerceIn(0, 1439),
        sunsetCustomMinutes = sunsetCustomMinutes.coerceIn(0, 1439),
        carouselPaths = carouselPaths.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(12),
        carouselIndex = carouselIndex.coerceAtLeast(0),
        pinnedClassroomBuildings = pinnedClassroomBuildings
            .map {
                it.copy(
                    campusCode = it.campusCode.trim(),
                    campusName = it.campusName.trim(),
                    buildingCode = it.buildingCode.trim(),
                    buildingName = it.buildingName.trim(),
                )
            }
            .filter { it.campusCode.isNotEmpty() && it.buildingCode.isNotEmpty() }
            .distinctBy { it.key }
            .take(MAX_PINNED_BUILDINGS),
        partnerTimetableId = partnerTimetableId.coerceAtLeast(0L),
        partnerLabel = partnerLabel.trim().take(12).ifEmpty { "TA" },
    )

    companion object {
        const val MAX_PINNED_BUILDINGS = 8
    }
}

/** 桌面组件布局。 */
enum class WidgetLayout {
    PREVIEW_2X2,
    TODAY_2X4,
    TODAY_4X4,
    RULER_4X4,
    WEEK_4X4,
    NEXT_PILL_2X1,
    EXAM_COUNTDOWN_2X2,
}

/** 桌面组件外观。 */
enum class WidgetAppearance {
    SOLID,
    TRANSPARENT,
}

/** 单个组件实例配置（仅本机有效）。 */
data class WidgetConfig(
    // === v5 既有字段（保持向后兼容） ===
    val widgetId: Int = 0,
    val timetableId: Long = 0,
    val layout: WidgetLayout = WidgetLayout.TODAY_2X4,
    val appearance: WidgetAppearance = WidgetAppearance.TRANSPARENT,
    val updatedAt: Long = 0,
    val glassOpacityPercent: Int = 25,
    val textSizeSp: Int = 13,
    val showTeacher: Boolean = false,
    val showLocation: Boolean = true,

    // === v6 WakeUp 样式对齐字段（默认值取自 WakeUp 6.0.22 字节码实测值） ===
    val showBg: Boolean = true,
    val bgColor: Int = 0xD8FFFFFF.toInt(),
    val backgroundUri: String = "",
    val showHeaderArea: Boolean = true,
    val showDate: Boolean = true,
    val showButton: Boolean = true,
    val showColor: Boolean = true,
    val textColor: Int = 0xFF000000.toInt(),
    val courseTextColor: Int = 0xFFFFFFFF.toInt(),
    val strokeColor: Int = 0x80FFFFFF.toInt(),
    val textColorCompose: Boolean = false,
    val strokeColorCompose: Boolean = false,
    val headerTextSizeSp: Int = 12,
    val itemHeightDp: Int = 64,
    val radiusDp: Int = 4,
    val itemAlpha: Int = 50,
    val otherWeekCourseAlpha: Int = 50,
    val itemCenterHorizontal: Boolean = false,
    val itemCenterVertical: Boolean = false,
    val showTime: Boolean = false,
    val showRoomPrefix: Boolean = true,
    val showTimeBar: Boolean = true,
    val showSat: Boolean = true,
    val showSun: Boolean = true,
    val showOtherWeekCourse: Boolean = true,
    val showGrid: Boolean = false,
    val useDottedLine: Boolean = false,
) {
    fun normalized(): WidgetConfig = copy(
        glassOpacityPercent = listOf(0, 25, 50).minBy { kotlin.math.abs(it.toLong() - glassOpacityPercent.toLong()) },
        textSizeSp = listOf(12, 13, 15, 17).minBy { kotlin.math.abs(it.toLong() - textSizeSp.toLong()) },
        headerTextSizeSp = headerTextSizeSp.coerceIn(10, 20),
        itemHeightDp = itemHeightDp.coerceIn(36, 96),
        radiusDp = radiusDp.coerceIn(0, 24),
        itemAlpha = itemAlpha.coerceIn(0, 100),
        otherWeekCourseAlpha = otherWeekCourseAlpha.coerceIn(0, 100),
    )
}

/** 系统记录类型。 */
enum class SystemMappingKind {
    REMINDER,
    CALENDAR,
    WIDGET,
}

/**
 * 应用拥有的系统记录映射。
 *
 * [externalId] 是本机 ID，不能跨设备恢复；[payloadHash] 与 [planRevision] 用于
 * 判断内容是否变化以及丢弃旧计划。
 */
data class SystemMapping(
    val id: Long = 0,
    val kind: SystemMappingKind = SystemMappingKind.REMINDER,
    val occurrenceId: String = "",
    val externalId: String = "",
    val payloadHash: String = "",
    val planRevision: Int = 0,
    val ownerId: String = "",
    val updatedAt: Long = 0,
)

/** 诊断日志事件（不含密码、Cookie、完整成绩/个人信息）。 */
data class DiagnosticEvent(
    val timestamp: Long = 0,
    val category: String = "",
    val connectionHash: String = "",
    val taskRevision: Int = 0,
    val durationMs: Long = 0,
    val outcome: String = "",
    val errorType: String = "",
    val message: String = "",
)
