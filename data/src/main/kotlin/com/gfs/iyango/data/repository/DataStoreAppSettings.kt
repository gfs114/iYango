package com.gfs.iyango.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gfs.iyango.core.domain.AppSettingsPort
import com.gfs.iyango.core.model.AppPreferences
import com.gfs.iyango.core.model.BingWallpaperTarget
import com.gfs.iyango.core.model.CalendarSettings
import com.gfs.iyango.core.model.CellTextAlign
import com.gfs.iyango.core.model.CourseInfoField
import com.gfs.iyango.core.model.DisplaySettingsBundle
import com.gfs.iyango.core.model.GridLineStyle
import com.gfs.iyango.core.model.NavigationLayout
import com.gfs.iyango.core.model.PinnedClassroomBuilding
import com.gfs.iyango.core.model.ReminderSettings
import com.gfs.iyango.core.model.ScheduleHeaderOrder
import com.gfs.iyango.core.model.ScheduleDisplaySettings
import com.gfs.iyango.core.model.WallpaperSwitchMode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

internal val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

/**
 * [AppSettingsPort] 的 DataStore 实现。
 *
 * 所有值以 JSON 文本保存；解析失败/字段缺失时回退到模型默认值，不让单条损坏配置
 * 使整份设置不可用。重置只清除非主题设置。
 */
@Singleton
class DataStoreAppSettings internal constructor(
    private val dataStore: DataStore<Preferences>,
) : AppSettingsPort {
    @Inject constructor(@ApplicationContext context: Context) : this(context.appSettingsDataStore)

    private object Keys {
        val DISPLAY_GLOBAL = stringPreferencesKey("display_global")
        val DISPLAY_BY_TIMETABLE = stringPreferencesKey("display_by_timetable")
        val REMINDER = stringPreferencesKey("reminder")
        val CALENDAR = stringPreferencesKey("calendar")
        val APP_PREFERENCES = stringPreferencesKey("app_preferences")
        val EXPORT_NAMESPACE = stringPreferencesKey("export_namespace")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val displaySettings: Flow<DisplaySettingsBundle> =
        dataStore.data.map { prefs ->
            DisplaySettingsBundle(
                global = decode<DisplaySettingsDto>(prefs[Keys.DISPLAY_GLOBAL])
                    ?.toModel()?.sanitized() ?: ScheduleDisplaySettings(),
                byTimetable = decode<Map<String, DisplaySettingsDto>>(prefs[Keys.DISPLAY_BY_TIMETABLE])
                    .orEmpty()
                    .mapNotNull { (key, value) ->
                        key.toLongOrNull()?.let { it to value.toModel().sanitized() }
                    }
                    .toMap(),
            )
        }

    override suspend fun updateDisplaySettings(bundle: DisplaySettingsBundle) {
        dataStore.edit { prefs ->
            prefs[Keys.DISPLAY_GLOBAL] = json.encodeToString(
                DisplaySettingsDto.serializer(),
                bundle.global.sanitized().toDto(),
            )
            prefs[Keys.DISPLAY_BY_TIMETABLE] = json.encodeToString(
                kotlinx.serialization.builtins.MapSerializer(
                    String.serializer(),
                    DisplaySettingsDto.serializer(),
                ),
                bundle.byTimetable.entries.associate { (id, value) ->
                    id.toString() to value.sanitized().toDto()
                },
            )
        }
    }

    override suspend fun updateGlobalDisplay(settings: ScheduleDisplaySettings) {
        dataStore.edit { prefs ->
            prefs[Keys.DISPLAY_GLOBAL] = json.encodeToString(DisplaySettingsDto.serializer(), settings.sanitized().toDto())
        }
    }

    override suspend fun updateTimetableDisplay(
        timetableId: Long,
        settings: ScheduleDisplaySettings?,
    ) {
        dataStore.edit { prefs ->
            val current = decode<Map<String, DisplaySettingsDto>>(prefs[Keys.DISPLAY_BY_TIMETABLE])
                .orEmpty()
                .toMutableMap()
            val key = timetableId.toString()
            if (settings == null) {
                current.remove(key)
            } else {
                current[key] = settings.sanitized().toDto()
            }
            prefs[Keys.DISPLAY_BY_TIMETABLE] = json.encodeToString(
                kotlinx.serialization.builtins.MapSerializer(
                    String.serializer(),
                    DisplaySettingsDto.serializer(),
                ),
                current,
            )
        }
    }

    override val reminderSettings: Flow<ReminderSettings> =
        dataStore.data.map { prefs ->
            decode<ReminderDto>(prefs[Keys.REMINDER])?.toModel()?.sanitized() ?: ReminderSettings()
        }

    override suspend fun updateReminderSettings(settings: ReminderSettings) {
        dataStore.edit { prefs ->
            prefs[Keys.REMINDER] = json.encodeToString(ReminderDto.serializer(), settings.sanitized().toDto())
        }
    }

    override val calendarSettings: Flow<CalendarSettings> =
        dataStore.data.map { prefs ->
            decode<CalendarDto>(prefs[Keys.CALENDAR])?.toModel() ?: CalendarSettings()
        }

    override suspend fun updateCalendarSettings(settings: CalendarSettings) {
        dataStore.edit { prefs ->
            prefs[Keys.CALENDAR] = json.encodeToString(CalendarDto.serializer(), settings.toDto())
        }
    }

    override val appPreferences: Flow<AppPreferences> =
        dataStore.data.map { prefs ->
            decode<AppPreferencesDto>(prefs[Keys.APP_PREFERENCES])?.toModel()?.sanitized()
                ?: AppPreferences()
        }

    override suspend fun updateAppPreferences(preferences: AppPreferences) {
        dataStore.edit { prefs ->
            prefs[Keys.APP_PREFERENCES] = json.encodeToString(
                AppPreferencesDto.serializer(),
                preferences.sanitized().toDto(),
            )
        }
    }

    override suspend fun transformAppPreferences(transform: (AppPreferences) -> AppPreferences) {
        dataStore.edit { prefs ->
            val current = decode<AppPreferencesDto>(prefs[Keys.APP_PREFERENCES])?.toModel()?.sanitized() ?: AppPreferences()
            prefs[Keys.APP_PREFERENCES] = json.encodeToString(AppPreferencesDto.serializer(), transform(current).sanitized().toDto())
        }
    }

    override suspend fun reset() {
        dataStore.edit { prefs ->
            // 导出命名空间标识"本机生成过的 ICS 数据集"，重置个性化设置不能让它变。
            val namespace = prefs[Keys.EXPORT_NAMESPACE]
            prefs.clear()
            if (!namespace.isNullOrBlank()) {
                prefs[Keys.EXPORT_NAMESPACE] = namespace
            }
        }
    }

    override suspend fun exportNamespace(): String {
        var namespace = ""
        dataStore.edit { prefs ->
            namespace = prefs[Keys.EXPORT_NAMESPACE].orEmpty()
            if (namespace.isBlank()) {
                namespace = UUID.randomUUID().toString()
                prefs[Keys.EXPORT_NAMESPACE] = namespace
            }
        }
        return namespace
    }

    private inline fun <reified T> decode(text: String?): T? {
        if (text.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<T>(text) }.getOrNull()
    }

    @Serializable
    internal data class DisplaySettingsDto(
        val version: Int = 1,
        val showWeekends: Boolean = true,
        val showOffWeek: Boolean = true,
        val headerOrder: String = "WEEKDAY_DATE",
        val showExams: Boolean = false,
        val showLunchBreak: Boolean = false,
        val mergeAdjacent: Boolean = false,
        val showTeacher: Boolean = true,
        val showClassroom: Boolean = true,
        val showTime: Boolean = false,
        val courseInfoOrder: List<String> = listOf("CLASSROOM", "TEACHER", "TIME"),
        val rowHeightDp: Int = 0,
        val minRowHeightDp: Int = 56,
        val cornerRadiusDp: Int = 10,
        val cellAlpha: Float = 1f,
        val offWeekAlpha: Float = 0.38f,
        val textSizeSp: Float = 12f,
        val align: String = "CENTER",
        val showGridLines: Boolean = true,
        val headerBackgroundHex: String = "",
        val gridLineHex: String = "",
        val courseColorOverrides: Map<String, String> = emptyMap(),
        val paletteVersion: Int = 1,
        val showAtPrefix: Boolean = true,
        val headerTextSizeSp: Float = 12f,
        val gridLineWidthDp: Float = 1f,
        val gridLineDashed: Boolean = false,
        val gridLineStyle: String = "",
        val textVCenter: Boolean = false,
        val borderWidthDp: Float = 0f,
        val borderColorHex: String = "",
        val borderUseCourseColor: Boolean = true,
        val borderDashed: Boolean = false,
        val backgroundColorHex: String = "",
        val paletteLight: List<String> = emptyList(),
        val paletteDark: List<String> = emptyList(),
        val viewMode: String = "GRID",
        val showEveningBreak: Boolean = false,
    ) {
        fun toModel(): ScheduleDisplaySettings {
            val resolvedStyle = gridLineStyle.trim().takeIf { it.isNotEmpty() }
                ?.let { runCatching { GridLineStyle.valueOf(it) }.getOrNull() }
                ?: if (gridLineDashed) GridLineStyle.DASHED else GridLineStyle.SOLID
            return ScheduleDisplaySettings(
                version = version,
                showWeekends = showWeekends,
                showOffWeek = showOffWeek,
                headerOrder = runCatching { ScheduleHeaderOrder.valueOf(headerOrder) }.getOrDefault(ScheduleHeaderOrder.WEEKDAY_DATE),
                showExams = showExams,
                showLunchBreak = showLunchBreak,
                mergeAdjacent = mergeAdjacent,
                showTeacher = showTeacher,
                showClassroom = showClassroom,
                showTime = showTime,
                courseInfoOrder = courseInfoOrder.mapNotNull { name ->
                    runCatching { CourseInfoField.valueOf(name) }.getOrNull()
                },
                rowHeightDp = rowHeightDp,
                minRowHeightDp = minRowHeightDp,
                cornerRadiusDp = cornerRadiusDp,
                cellAlpha = cellAlpha,
                offWeekAlpha = offWeekAlpha,
                textSizeSp = textSizeSp,
                align = runCatching { CellTextAlign.valueOf(align) }.getOrDefault(CellTextAlign.CENTER),
                showGridLines = showGridLines,
                headerBackgroundHex = headerBackgroundHex,
                gridLineHex = gridLineHex,
                courseColorOverrides = courseColorOverrides,
                paletteVersion = paletteVersion,
                showAtPrefix = showAtPrefix,
                headerTextSizeSp = headerTextSizeSp,
                gridLineWidthDp = gridLineWidthDp,
                gridLineDashed = resolvedStyle == GridLineStyle.DASHED,
                gridLineStyle = resolvedStyle,
                textVCenter = textVCenter,
            borderWidthDp = borderWidthDp,
            borderColorHex = borderColorHex,
            borderUseCourseColor = borderUseCourseColor,
            borderDashed = borderDashed,
            backgroundColorHex = backgroundColorHex,
            paletteLight = paletteLight,
            paletteDark = paletteDark,
            viewMode = runCatching { com.gfs.iyango.core.model.ScheduleViewMode.valueOf(viewMode) }
                .getOrDefault(com.gfs.iyango.core.model.ScheduleViewMode.GRID),
            showEveningBreak = showEveningBreak,
        ).sanitized()
        }
    }

    @Serializable
    internal data class PinnedBuildingDto(
        val campusCode: String = "",
        val campusName: String = "",
        val buildingCode: String = "",
        val buildingName: String = "",
    ) {
        fun toModel(): PinnedClassroomBuilding = PinnedClassroomBuilding(
            campusCode = campusCode,
            campusName = campusName,
            buildingCode = buildingCode,
            buildingName = buildingName,
        )

        companion object {
            fun fromModel(model: PinnedClassroomBuilding): PinnedBuildingDto = PinnedBuildingDto(
                campusCode = model.campusCode,
                campusName = model.campusName,
                buildingCode = model.buildingCode,
                buildingName = model.buildingName,
            )
        }
    }

    @Serializable
    internal data class ReminderDto(
        val enabled: Boolean = false,
        val leadMinutes: Int = 10,
        val disabledCourseKeys: List<String> = emptyList(),
        val exactAlarmPrompted: Boolean = false,
        val ongoingProgressEnabled: Boolean = true,
        val gradeNotifyEnabled: Boolean = false,
        val gradeCheckIntervalHours: Int = 4,
        val gradeNotifyHideScoreOnLockscreen: Boolean = true,
        val gradeSprintEnabled: Boolean = false,
        val gradeSprintIntervalMinutes: Int = 30,
        val gradeSprintUntilMillis: Long = 0L,
        val gradeSprintDurationDays: Int = 7,
        val gradeContextTriggerEnabled: Boolean = true,
        val skipRemindersOnHolidays: Boolean = true,
        val autoSilentMode: String = com.gfs.iyango.core.model.AutoSilentMode.OFF.name,
        val autoSilentExemptCourseKeys: List<String> = emptyList(),
        val autoSilentSkipOnHolidays: Boolean = true,
    ) {
        fun toModel(): ReminderSettings = ReminderSettings(
            enabled = enabled,
            leadMinutes = leadMinutes,
            disabledCourseKeys = disabledCourseKeys.toSet(),
            exactAlarmPrompted = exactAlarmPrompted,
            ongoingProgressEnabled = ongoingProgressEnabled,
            gradeNotifyEnabled = gradeNotifyEnabled,
            gradeCheckIntervalHours = gradeCheckIntervalHours,
            gradeNotifyHideScoreOnLockscreen = gradeNotifyHideScoreOnLockscreen,
            gradeSprintEnabled = gradeSprintEnabled,
            gradeSprintIntervalMinutes = gradeSprintIntervalMinutes,
            gradeSprintUntilMillis = gradeSprintUntilMillis,
            gradeSprintDurationDays = gradeSprintDurationDays,
            gradeContextTriggerEnabled = gradeContextTriggerEnabled,
            skipRemindersOnHolidays = skipRemindersOnHolidays,
            autoSilentMode = runCatching {
                com.gfs.iyango.core.model.AutoSilentMode.valueOf(autoSilentMode)
            }.getOrDefault(com.gfs.iyango.core.model.AutoSilentMode.OFF),
            autoSilentExemptCourseKeys = autoSilentExemptCourseKeys.toSet(),
            autoSilentSkipOnHolidays = autoSilentSkipOnHolidays,
        )
    }

    @Serializable
    internal data class CalendarDto(
        val enabled: Boolean = false,
        val calendarId: Long = -1,
        val ownerId: String = "",
        val lastSyncAt: Long = 0,
        val lastError: String = "",
    ) {
        fun toModel(): CalendarSettings = CalendarSettings(
            enabled = enabled,
            calendarId = calendarId,
            ownerId = ownerId,
            lastSyncAt = lastSyncAt,
            lastError = lastError,
        )
    }

    @Serializable
    internal data class AppPreferencesDto(
        val version: Int = 1,
        val defaultStartTab: Int = 0,
        val navigationLayout: String = "AUTO",
        val animationsEnabled: Boolean = true,
        val predictiveBackEnabled: Boolean = true,
        val wallpaperPath: String = "",
        val wallpaperPathDark: String = "",
        val wallpaperScrim: Float = 0.18f,
        val wallpaperBlur: Float = 0f,
        val timetableWallpaperPath: String = "",
        val timetableWallpaperPathDark: String = "",
        @Serializable(with = SafeHomePreferencesSerializer::class)
        val home: HomePreferencesDto = HomePreferencesDto(),
        val developerModeEnabled: Boolean = false,
        val customFontPath: String = "",
        val customFontName: String = "",
        val fontScaleFactor: Float = 1.0f,
        val grayTextMode: String = "DEFAULT",
        val grayTextCustomHex: String = "",
        val savedAccentPresets: List<String> = emptyList(),
        val wallpaperSwitchMode: String = WallpaperSwitchMode.FOLLOW_THEME.name,
        val sunriseCityId: String = "beijing",
        val sunriseCustomMinutes: Int = 6 * 60 + 30,
        val sunsetCustomMinutes: Int = 18 * 60 + 30,
        val sunriseSyncTheme: Boolean = false,
        val periodMorningPath: String = "",
        val periodAfternoonPath: String = "",
        val periodEveningPath: String = "",
        val periodNightPath: String = "",
        val carouselPaths: List<String> = emptyList(),
        val carouselIndex: Int = 0,
        val wallpaperDynamicColor: Boolean = false,
        val bingAutoUpdateEnabled: Boolean = false,
        val bingAutoUpdateWifiOnly: Boolean = true,
        val bingAutoUpdateTarget: String = BingWallpaperTarget.GLOBAL_LIGHT.name,
        val bingLastAutoUpdateDate: String = "",
        val pinnedClassroomBuildings: List<PinnedBuildingDto> = emptyList(),
        val partnerTimetableId: Long = 0L,
        val partnerLabel: String = "TA",
    ) {
        fun toModel(): AppPreferences = AppPreferences(
            version = version,
            defaultStartTab = defaultStartTab,
            navigationLayout = runCatching { NavigationLayout.valueOf(navigationLayout) }
                .getOrDefault(NavigationLayout.AUTO),
            animationsEnabled = animationsEnabled,
            predictiveBackEnabled = predictiveBackEnabled,
            wallpaperPath = wallpaperPath,
            wallpaperPathDark = wallpaperPathDark,
            wallpaperScrim = wallpaperScrim,
            wallpaperBlur = wallpaperBlur,
            timetableWallpaperPath = timetableWallpaperPath,
            timetableWallpaperPathDark = timetableWallpaperPathDark,
            home = home.toModel(),
            developerModeEnabled = developerModeEnabled,
            customFontPath = customFontPath,
            customFontName = customFontName,
            fontScaleFactor = fontScaleFactor,
            grayTextMode = runCatching { com.gfs.iyango.core.model.GrayTextMode.valueOf(grayTextMode) }
                .getOrDefault(com.gfs.iyango.core.model.GrayTextMode.DEFAULT),
            grayTextCustomHex = grayTextCustomHex,
            savedAccentPresets = savedAccentPresets,
            wallpaperSwitchMode = runCatching { WallpaperSwitchMode.valueOf(wallpaperSwitchMode) }
                .getOrDefault(WallpaperSwitchMode.FOLLOW_THEME),
            sunriseCityId = sunriseCityId,
            sunriseCustomMinutes = sunriseCustomMinutes,
            sunsetCustomMinutes = sunsetCustomMinutes,
            sunriseSyncTheme = sunriseSyncTheme,
            periodMorningPath = periodMorningPath,
            periodAfternoonPath = periodAfternoonPath,
            periodEveningPath = periodEveningPath,
            periodNightPath = periodNightPath,
            carouselPaths = carouselPaths,
            carouselIndex = carouselIndex,
            wallpaperDynamicColor = wallpaperDynamicColor,
            bingAutoUpdateEnabled = bingAutoUpdateEnabled,
            bingAutoUpdateWifiOnly = bingAutoUpdateWifiOnly,
            bingAutoUpdateTarget = runCatching { BingWallpaperTarget.valueOf(bingAutoUpdateTarget) }
                .getOrDefault(BingWallpaperTarget.GLOBAL_LIGHT),
            bingLastAutoUpdateDate = bingLastAutoUpdateDate,
            pinnedClassroomBuildings = pinnedClassroomBuildings.map { it.toModel() },
            partnerTimetableId = partnerTimetableId,
            partnerLabel = partnerLabel,
        ).sanitized()
    }
}

private fun ScheduleDisplaySettings.toDto(): DataStoreAppSettings.DisplaySettingsDto =
    DataStoreAppSettings.DisplaySettingsDto(
        version = version,
        showWeekends = showWeekends,
        showOffWeek = showOffWeek,
        headerOrder = headerOrder.name,
        showExams = showExams,
        showLunchBreak = showLunchBreak,
        mergeAdjacent = mergeAdjacent,
        showTeacher = showTeacher,
        showClassroom = showClassroom,
        showTime = showTime,
        courseInfoOrder = courseInfoOrder.map { it.name },
        rowHeightDp = rowHeightDp,
        minRowHeightDp = minRowHeightDp,
        cornerRadiusDp = cornerRadiusDp,
        cellAlpha = cellAlpha,
        offWeekAlpha = offWeekAlpha,
        textSizeSp = textSizeSp,
        align = align.name,
        showGridLines = showGridLines,
        headerBackgroundHex = headerBackgroundHex,
        gridLineHex = gridLineHex,
        courseColorOverrides = courseColorOverrides,
        paletteVersion = paletteVersion,
        showAtPrefix = showAtPrefix,
        headerTextSizeSp = headerTextSizeSp,
        gridLineWidthDp = gridLineWidthDp,
        gridLineDashed = gridLineStyle == GridLineStyle.DASHED,
        gridLineStyle = gridLineStyle.name,
        textVCenter = textVCenter,
        borderWidthDp = borderWidthDp,
        borderColorHex = borderColorHex,
        borderUseCourseColor = borderUseCourseColor,
        borderDashed = borderDashed,
        backgroundColorHex = backgroundColorHex,
        paletteLight = paletteLight,
        paletteDark = paletteDark,
        viewMode = viewMode.name,
        showEveningBreak = showEveningBreak,
    )

private fun ReminderSettings.toDto(): DataStoreAppSettings.ReminderDto =
    DataStoreAppSettings.ReminderDto(
        enabled = enabled,
        leadMinutes = leadMinutes,
        disabledCourseKeys = disabledCourseKeys.sorted(),
        exactAlarmPrompted = exactAlarmPrompted,
        ongoingProgressEnabled = ongoingProgressEnabled,
        gradeNotifyEnabled = gradeNotifyEnabled,
        gradeCheckIntervalHours = gradeCheckIntervalHours,
        gradeNotifyHideScoreOnLockscreen = gradeNotifyHideScoreOnLockscreen,
        gradeSprintEnabled = gradeSprintEnabled,
        gradeSprintIntervalMinutes = gradeSprintIntervalMinutes,
        gradeSprintUntilMillis = gradeSprintUntilMillis,
        gradeSprintDurationDays = gradeSprintDurationDays,
        gradeContextTriggerEnabled = gradeContextTriggerEnabled,
        skipRemindersOnHolidays = skipRemindersOnHolidays,
        autoSilentMode = autoSilentMode.name,
        autoSilentExemptCourseKeys = autoSilentExemptCourseKeys.sorted(),
        autoSilentSkipOnHolidays = autoSilentSkipOnHolidays,
    )

private fun CalendarSettings.toDto(): DataStoreAppSettings.CalendarDto =
    DataStoreAppSettings.CalendarDto(
        enabled = enabled,
        calendarId = calendarId,
        ownerId = ownerId,
        lastSyncAt = lastSyncAt,
        lastError = lastError,
    )

private fun AppPreferences.toDto(): DataStoreAppSettings.AppPreferencesDto =
    DataStoreAppSettings.AppPreferencesDto(
        version = version,
        defaultStartTab = defaultStartTab,
        navigationLayout = navigationLayout.name,
        animationsEnabled = animationsEnabled,
        predictiveBackEnabled = predictiveBackEnabled,
        wallpaperPath = wallpaperPath,
        wallpaperPathDark = wallpaperPathDark,
        wallpaperScrim = wallpaperScrim,
        wallpaperBlur = wallpaperBlur,
        timetableWallpaperPath = timetableWallpaperPath,
        timetableWallpaperPathDark = timetableWallpaperPathDark,
        home = HomePreferencesDto.fromModel(home),
        developerModeEnabled = developerModeEnabled,
        customFontPath = customFontPath,
        customFontName = customFontName,
        fontScaleFactor = fontScaleFactor,
        grayTextMode = grayTextMode.name,
        grayTextCustomHex = grayTextCustomHex,
        savedAccentPresets = savedAccentPresets,
        wallpaperSwitchMode = wallpaperSwitchMode.name,
        sunriseCityId = sunriseCityId,
        sunriseCustomMinutes = sunriseCustomMinutes,
        sunsetCustomMinutes = sunsetCustomMinutes,
        sunriseSyncTheme = sunriseSyncTheme,
        periodMorningPath = periodMorningPath,
        periodAfternoonPath = periodAfternoonPath,
        periodEveningPath = periodEveningPath,
        periodNightPath = periodNightPath,
        carouselPaths = carouselPaths,
        carouselIndex = carouselIndex,
        wallpaperDynamicColor = wallpaperDynamicColor,
        bingAutoUpdateEnabled = bingAutoUpdateEnabled,
        bingAutoUpdateWifiOnly = bingAutoUpdateWifiOnly,
        bingAutoUpdateTarget = bingAutoUpdateTarget.name,
        bingLastAutoUpdateDate = bingLastAutoUpdateDate,
        pinnedClassroomBuildings = pinnedClassroomBuildings.map {
            DataStoreAppSettings.PinnedBuildingDto.fromModel(it)
        },
        partnerTimetableId = partnerTimetableId,
        partnerLabel = partnerLabel,
    )
