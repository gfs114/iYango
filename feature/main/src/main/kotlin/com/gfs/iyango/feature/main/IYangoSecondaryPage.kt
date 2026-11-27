package com.gfs.iyango.feature.main

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassTopBarIconButton
import com.gfs.iyango.core.designsystem.component.LocalGlassPageBackground
import com.gfs.iyango.core.designsystem.liquidglass.GlassBackdropHost
import com.gfs.iyango.core.designsystem.liquidglass.LocalGlassPalette
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled
import com.gfs.iyango.core.domain.ThemeSettings
import com.gfs.iyango.feature.main.about.AboutScreen
import com.gfs.iyango.feature.main.about.AppUpdateScreen
import com.gfs.iyango.feature.main.about.RuntimeLogScreen
import com.gfs.iyango.feature.main.ai.AiAssistantScreen
import com.gfs.iyango.feature.main.ai.AiConfigScreen
import com.gfs.iyango.feature.main.ai.AiProviderDetailScreen
import com.gfs.iyango.feature.main.classroom.ClassroomQueryScreen
import com.gfs.iyango.feature.main.coursemanage.CourseManageScreen
import com.gfs.iyango.feature.main.creditprogress.CreditProgressScreen
import com.gfs.iyango.feature.main.editor.CourseEditorScreen
import com.gfs.iyango.feature.main.export.ScheduleExportScreen
import com.gfs.iyango.feature.main.importexport.AiImportScreen
import com.gfs.iyango.feature.main.importexport.ImportExportScreen
import com.gfs.iyango.feature.main.mine.AppearanceState
import com.gfs.iyango.feature.main.mine.MineAppearanceScreen
import com.gfs.iyango.feature.main.mine.MineCategory
import com.gfs.iyango.feature.main.mine.MineCategoryScreen
import com.gfs.iyango.feature.main.mine.MineDestination
import com.gfs.iyango.feature.main.schedule.CourseRawScreen
import com.gfs.iyango.feature.main.school.SchoolAccountScreen
import com.gfs.iyango.feature.main.school.SchoolTimetableSyncScreen
import com.gfs.iyango.feature.main.semester.SemesterManageScreen
import com.gfs.iyango.feature.main.settings.DisplaySettingsScreen
import com.gfs.iyango.feature.main.settings.FontAppearanceScreen
import com.gfs.iyango.feature.main.settings.LiquidGlassCategory
import com.gfs.iyango.feature.main.settings.LiquidGlassSettingsScreen
import com.gfs.iyango.feature.main.settings.ReminderCalendarScreen
import com.gfs.iyango.feature.main.settings.SettingsBackupScreen
import com.gfs.iyango.feature.main.settings.WallpaperLayer
import com.gfs.iyango.feature.main.settings.WallpaperScreen
import com.gfs.iyango.feature.main.settings.toGlassPalette
import com.gfs.iyango.feature.main.synchistory.SyncHistoryScreen
import com.gfs.iyango.feature.main.timetable.TimetableCompareScreen
import com.gfs.iyango.feature.main.timetable.TimetableManageScreen
import com.gfs.iyango.feature.main.today.HomeSettingsScreen

/**
 * `SecondaryPageActivity` 的通用二级/三级页渲染入口（多 Activity 架构 P2）。
 *
 * 完整承接原 `IYangoMainApp` 全屏次级页分支渲染与参数绑定：使用与主壳同一套
 * [rememberGlobalWallpaperUi] 壁纸调度（含动态取色、日出日落/轮播切换、Bing 更新）、
 * 相同的根级 `CompositionLocalProvider`（[LocalMotionEnabled]、[LocalContentColor]、
 * [LocalGlassPageBackground]、[LocalGlassPalette]）与 [GlassBackdropHost] 结构。
 *
 * 页面级返回安全拦截（编辑器脏状态确认、批量选择态、SchoolAccount 内部子步骤）
 * 由各 Screen 自身的 [com.gfs.iyango.feature.main.EditorDiscardBackHandler] /
 * `NavigationBackHandler` 负责；clean 状态下不注册拦截，直接交给系统原生
 * 跨 Activity 预测性返回。
 *
 * @param route 已解析的二级页路由（白名单校验在解析层完成）。
 * @param themeSettings 外观设置（IYangoActivityHost 注入，DataStore 持久化）。
 * @param onThemeSettingsChange 外观设置变更回调。
 * @param onBack 返回上一级（宿主 Activity `finish()`）。
 * @param onOpenRoute 打开下一级页面（宿主 `SecondaryPageLauncher.open`）。
 */
@Composable
fun IYangoSecondaryPage(
    route: SecondaryPageRoute,
    themeSettings: ThemeSettings,
    onThemeSettingsChange: (ThemeSettings) -> Unit,
    onBack: () -> Unit,
    onOpenRoute: (String) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val wallpaperUi = rememberGlobalWallpaperUi(themeSettings, onThemeSettingsChange)
    val appPreferences = wallpaperUi.preferences
    val onOpenRouteState = rememberUpdatedState(onOpenRoute)
    fun openRoute(next: String) = onOpenRouteState.value(next)

    val effectiveWallpaperPath = wallpaperUi.activeGlobalWallpaperPath
    val wallpaperPageBackground: (@Composable () -> Unit)? = if (effectiveWallpaperPath != null) {
        {
            WallpaperLayer(
                path = effectiveWallpaperPath,
                scrim = appPreferences.wallpaperScrim,
                blur = appPreferences.wallpaperBlur,
                modifier = Modifier.fillMaxSize(),
            )
        }
    } else {
        null
    }

    val glassPalette = remember(themeSettings.globalGlass) { themeSettings.globalGlass.toGlassPalette() }

    CompositionLocalProvider(
        LocalMotionEnabled provides wallpaperUi.motionEnabled,
        LocalPredictiveBackEnabled provides wallpaperUi.predictiveBackEnabled,
        LocalContentColor provides colorScheme.onSurface,
        LocalGlassPageBackground provides wallpaperPageBackground,
        LocalGlassPalette provides glassPalette,
    ) {
        // 关闭预测性返回时由 Priority=0 BackHandler 消费返回键以屏蔽系统预测缩放预览。
        BackHandler(
            enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !wallpaperUi.predictiveBackEnabled,
        ) {
            onBack()
        }
        GlassBackdropHost(
            modifier = Modifier
                .fillMaxSize()
                .background(colorScheme.background),
            hasWallpaper = effectiveWallpaperPath != null,
            wallpaperPath = effectiveWallpaperPath,
            background = {
                // 与主壳一致：全局壁纸铺在页面之下并注册进 L0，供玻璃表面采样。
                WallpaperLayer(
                    path = effectiveWallpaperPath,
                    scrim = appPreferences.wallpaperScrim,
                    blur = appPreferences.wallpaperBlur,
                    modifier = Modifier.fillMaxSize(),
                )
            },
        ) {
            SecondaryRouteContent(
                route = route,
                onBack = onBack,
                openRoute = ::openRoute,
                wallpaperUi = wallpaperUi,
                themeSettings = themeSettings,
                onThemeSettingsChange = onThemeSettingsChange,
            )
        }
    }
}

@Composable
private fun SecondaryRouteContent(
    route: SecondaryPageRoute,
    onBack: () -> Unit,
    openRoute: (String) -> Unit,
    wallpaperUi: GlobalWallpaperUi,
    themeSettings: ThemeSettings,
    onThemeSettingsChange: (ThemeSettings) -> Unit,
) {
    val appPreferences = wallpaperUi.preferences
    val wallpaperViewModel = wallpaperUi.wallpaperViewModel
    val latestThemeSettings by rememberUpdatedState(themeSettings)
    val latestOnThemeSettingsChange by rememberUpdatedState(onThemeSettingsChange)
    var noticeMessage by remember { mutableStateOf<String?>(null) }
    val onNavigateMine = remember {
        { destination: MineDestination ->
            if (destination == MineDestination.EXAMS) {
                noticeMessage = "敬请期待"
            } else {
                openRoute(destination.route)
            }
        }
    }

    when (route) {
        is SecondaryPageRoute.Editor -> CourseEditorScreen(onBack = onBack)
        is SecondaryPageRoute.CourseRaw -> CourseRawScreen(onBack = onBack)
        is SecondaryPageRoute.Display -> DisplaySettingsScreen(onBack = onBack)
        is SecondaryPageRoute.Classroom -> ClassroomQueryScreen(
            onBack = onBack,
            onOpenSchool = { openRoute("school") },
        )
        is SecondaryPageRoute.AiProviderDetail -> AiProviderDetailScreen(
            profileId = route.profileId,
            onBack = onBack,
        )
        is SecondaryPageRoute.Plain -> {
            val category = MineCategory.entries.firstOrNull { it.route == route.id }
            when {
                category == MineCategory.APPEARANCE -> MineAppearanceScreen(
                    onBack = onBack,
                    onNavigate = onNavigateMine,
                    appearance = AppearanceState(
                        accentHex = latestThemeSettings.accentHex,
                        tintBackground = latestThemeSettings.tintBackground,
                        themeMode = latestThemeSettings.themeMode,
                        dockSurfaceAlphaLight = latestThemeSettings.dockSurfaceAlphaLight,
                        dockSurfaceAlphaDark = latestThemeSettings.dockSurfaceAlphaDark,
                    ),
                    onAppearanceChange = { appearance ->
                        latestOnThemeSettingsChange(
                            latestThemeSettings.copy(
                                accentHex = appearance.accentHex,
                                tintBackground = appearance.tintBackground,
                                themeMode = appearance.themeMode,
                                dockSurfaceAlphaLight = appearance.dockSurfaceAlphaLight,
                                dockSurfaceAlphaDark = appearance.dockSurfaceAlphaDark,
                            ),
                        )
                    },
                    appPreferences = appPreferences,
                    onDefaultStartTabChange = wallpaperViewModel::setDefaultStartTab,
                    onNavigationLayoutChange = wallpaperViewModel::setNavigationLayout,
                    onAnimationsEnabledChange = wallpaperViewModel::setAnimationsEnabled,
                    onPredictiveBackEnabledChange = wallpaperViewModel::setPredictiveBackEnabled,
                    savedAccentPresets = appPreferences.savedAccentPresets,
                    onSaveAccentPreset = wallpaperViewModel::saveAccentPreset,
                    onRemoveAccentPreset = wallpaperViewModel::removeAccentPreset,
                )
                category != null -> MineCategoryScreen(
                    category = category,
                    onBack = onBack,
                    onNavigate = onNavigateMine,
                    notice = noticeMessage,
                    onDismissNotice = { noticeMessage = null },
                )
                route.id == "courses" -> CourseManageScreen(
                    onBack = onBack,
                    onAddCourse = { openRoute("editor?courseId=0") },
                    onEditCourse = { courseId -> openRoute("editor?courseId=$courseId") },
                )
                route.id == "school" -> SchoolAccountScreen(
                    onBack = onBack,
                    onOpenSync = { openRoute("schoolsync") },
                )
                route.id == "schoolsync" -> SchoolTimetableSyncScreen(
                    onBack = onBack,
                    onOpenHistory = { openRoute("synchistory") },
                )
                route.id == "synchistory" -> SyncHistoryScreen(onBack = onBack)
                route.id == "exams" -> ExamComingSoonScreen(onBack = onBack)
                route.id == "timetable" -> TimetableManageScreen(
                    onBack = onBack,
                    onOpenSync = { openRoute("schoolsync") },
                    onOpenCompare = { openRoute("timetablecompare") },
                )
                route.id == "timetablecompare" -> TimetableCompareScreen(
                    onBack = onBack,
                    onOpenImport = { openRoute("importexport") },
                    onOpenClassroom = { week, day, startSec, endSec ->
                        openRoute(
                            SecondaryPageRoute.Classroom(
                                week = week,
                                dayOfWeek = day,
                                startSection = startSec,
                                endSection = endSec,
                            ).route,
                        )
                    },
                )
                route.id == "semester" -> SemesterManageScreen(onBack = onBack)
                route.id == "homesettings" -> HomeSettingsScreen(onBack = onBack)
                route.id == "wallpaper" -> WallpaperScreen(
                    onBack = onBack,
                    onApplyAccentHex = { hex ->
                        latestOnThemeSettingsChange(latestThemeSettings.copy(accentHex = hex))
                    },
                )
                route.id == "font" -> FontAppearanceScreen(onBack = onBack)
                route.id == "liquidglass" -> LiquidGlassSettingsScreen(
                    themeSettings = latestThemeSettings,
                    onThemeSettingsChange = latestOnThemeSettingsChange,
                    wallpaperPath = wallpaperUi.activeGlobalWallpaperPath,
                    wallpaperScrim = appPreferences.wallpaperScrim,
                    wallpaperBlur = appPreferences.wallpaperBlur,
                    onBack = onBack,
                )
                route.id == "dockglass" || route.id == "dockglass/dev" -> LiquidGlassSettingsScreen(
                    themeSettings = latestThemeSettings,
                    onThemeSettingsChange = latestOnThemeSettingsChange,
                    wallpaperPath = wallpaperUi.activeGlobalWallpaperPath,
                    wallpaperScrim = appPreferences.wallpaperScrim,
                    wallpaperBlur = appPreferences.wallpaperBlur,
                    initialCategory = LiquidGlassCategory.DOCK,
                    onBack = onBack,
                )
                route.id == "reminder" -> ReminderCalendarScreen(onBack = onBack)
                route.id == "importexport" -> ImportExportScreen(
                    onBack = onBack,
                    onOpenScheduleExport = { openRoute("scheduleexport") },
                    onOpenAiImport = { openRoute("aiimport") },
                )
                route.id == "aiimport" -> AiImportScreen(
                    onBack = onBack,
                    onOpenAiConfig = { openRoute("aiconfig") },
                )
                route.id == "aiconfig" -> AiConfigScreen(
                    onBack = onBack,
                    onOpenProviderDetail = { profileId ->
                        openRoute(SecondaryPageRoute.AiProviderDetail(profileId).route)
                    },
                )
                route.id == "aiassistant" -> AiAssistantScreen(
                    onBack = onBack,
                    onOpenAiConfig = { openRoute("aiconfig") },
                )
                route.id == "scheduleexport" -> ScheduleExportScreen(onBack = onBack)
                route.id == "semestersummary" -> com.gfs.iyango.feature.main.summary.SemesterSummaryScreen(
                    wallpaperPath = wallpaperUi.activeGlobalWallpaperPath,
                    onBack = onBack,
                )
                route.id == "settingsbackup" -> SettingsBackupScreen(onBack = onBack)
                route.id == "creditprogress" -> CreditProgressScreen(
                    onBack = onBack,
                    onOpenSchool = { openRoute("school") },
                )
                route.id == "about" -> AboutScreen(
                    onBack = onBack,
                    onOpenDockGlassDev = { openRoute("dockglass/dev") },
                    onOpenRuntimeLogs = { openRoute("runtimelogs") },
                    onOpenAppUpdate = { openRoute("appupdate") },
                )
                route.id == "appupdate" -> AppUpdateScreen(onBack = onBack)
                route.id == "runtimelogs" -> RuntimeLogScreen(onBack = onBack)
                // 白名单内未覆盖的兜底（理论上不可达：parse 保证只有已知路由）。
                else -> Box(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun ExamComingSoonScreen(onBack: () -> Unit) {
    GlassScaffold(
        topBar = {
            GlassTopBar(
                title = "考试安排",
                navigationIcon = {
                    GlassTopBarIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "敬请期待",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
