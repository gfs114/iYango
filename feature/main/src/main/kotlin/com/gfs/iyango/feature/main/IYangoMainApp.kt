package com.gfs.iyango.feature.main

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gfs.iyango.core.designsystem.component.LocalGlassPageBackground
import com.gfs.iyango.core.designsystem.liquidglass.GlassBackdropHost
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LocalGlassBackdrop
import com.gfs.iyango.core.designsystem.liquidglass.LocalGlassPalette
import com.gfs.iyango.core.designsystem.motion.AppMotion
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled
import com.gfs.iyango.core.designsystem.theme.LocalBrand
import com.gfs.iyango.core.domain.ThemeSettings
import com.gfs.iyango.core.model.NavigationLayout
import com.gfs.iyango.feature.main.about.AppUpdateDialog
import com.gfs.iyango.feature.main.about.AppUpdateViewModel
import com.gfs.iyango.feature.main.grades.GradesScreen
import com.gfs.iyango.feature.main.liquidglass.LiquidBottomTab
import com.gfs.iyango.feature.main.liquidglass.LiquidBottomTabs
import com.gfs.iyango.feature.main.mine.MineDestination
import com.gfs.iyango.feature.main.mine.MineScreen
import com.gfs.iyango.feature.main.school.SchoolAccountViewModel
import com.gfs.iyango.feature.main.schedule.ScheduleScreen
import com.gfs.iyango.feature.main.settings.WallpaperLayer
import com.gfs.iyango.feature.main.settings.toGlassPalette
import com.gfs.iyango.feature.main.today.TodayScreen
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.RoundedRectangle
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity

/** 底部导航项。 */
internal data class MainDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

/**
 * 液态玻璃 Dock 几何：条高 56dp / 内容行 48dp（酷安 16.6.5 `liquid_bottom_navigation`
 * 实测值：容器 182px@3.25 = 56dp、内容行 156px = 48dp、content padding 4dp）。
 */
private val DockHeight = 56.dp

/** 页面内容与 Dock 之间的呼吸间距。 */
private val DockBreathing = 8.dp

/** Dock 距屏幕左右边距（酷安生产页实测 65px@3.25 = 20dp）。 */
private val DockHorizontalMargin = 20.dp

/** 自动布局切换为侧边导航的窗口宽度阈值（按可用宽度，不按设备型号）。 */
private val SideNavigationWidthThreshold = 600.dp

/** 侧边导航栏宽度。 */
private val SideNavigationWidth = 88.dp

/** 外部入口可打开的次级页白名单（与 platform 层 LaunchIntents.ROUTES 非 Tab 部分保持一致）。 */
private val ExternalRoutes = setOf(
    "timetable", "timetablecompare", "courses", "semester", "semestersummary", "importexport",
    "display", "wallpaper", "reminder", "exams", "classroom", "aiassistant",
)

/**
 * 应用主骨架（多 Activity 架构 P2）：仅承载今日/课表/成绩/我的四个主 Tab
 * + 液态玻璃 Dock 底部导航。二级/三级页改由 `SecondaryPageActivity` 承载，
 * 通过 [onOpenRoute] 拉起；主壳不再注册页面级返回拦截——四个主 Tab 常态
 * 直接放行给系统，触发原生 `TYPE_RETURN_TO_HOME` 返回桌面动画。
 *
 * Dock 移植自 Kyant0/AndroidLiquidGlass 的 LiquidBottomTabs（Apache-2.0）：
 * - 根 Box 绘制主题背景色；主 Tab 容器通过 [Modifier.layerBackdrop] 注册进共享
 *   [rememberLayerBackdrop]，保持全屏绘制（内容滑到 Dock 之下才会被折射）；
 * - [LiquidBottomTabs] 三层结构：基础玻璃条、隐藏强调色内容层、可拖拽玻璃指示器。
 *
 * @param themeSettings 外观设置（IYangoActivityHost 注入，DataStore 持久化）。
 * @param onThemeSettingsChange 外观设置变更（我的页外观区回调）。
 * @param onOpenRoute 打开二级页（宿主接 [com.gfs.iyango.SecondaryPageLauncher.open]）。
 * @param pendingCourseId 通知点击待跳转的课程 id（冷启动经 savedInstanceState 保留）。
 * @param onPendingCourseConsumed 跳转已执行，调用方清除待处理目标。
 * @param pendingTab 组件/快捷入口显式页签；优先于默认启动页。
 * @param onPendingTabConsumed 页签已切换，调用方清除待处理目标。
 * @param pendingRoute 组件/快捷入口显式次级页路由。
 * @param onPendingRouteConsumed 次级页已打开，调用方清除待处理目标。
 */
@Composable
fun IYangoMainApp(
    themeSettings: ThemeSettings = ThemeSettings(),
    onThemeSettingsChange: (ThemeSettings) -> Unit = {},
    onOpenRoute: (String) -> Unit = {},
    pendingCourseId: Long? = null,
    onPendingCourseConsumed: () -> Unit = {},
    pendingTab: Int? = null,
    onPendingTabConsumed: () -> Unit = {},
    pendingRoute: String? = null,
    onPendingRouteConsumed: () -> Unit = {},
) {
    val destinations = remember { listOf(
        MainDestination(route = "today", label = "今日", icon = Icons.Filled.Home),
        MainDestination(route = "schedule", label = "课表", icon = Icons.Filled.DateRange),
        MainDestination(route = "grades", label = "成绩", icon = Icons.Filled.Star),
        MainDestination(route = "mine", label = "我的", icon = Icons.Filled.Person),
    ) }
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val wallpaperUi = rememberGlobalWallpaperUi(themeSettings, onThemeSettingsChange, isMainShell = true)
    val appPreferences = wallpaperUi.preferences
    val preferencesReady = wallpaperUi.preferencesReady
    val motionEnabled = wallpaperUi.motionEnabled
    val schoolViewModel: SchoolAccountViewModel = hiltViewModel()
    val activeConnection by schoolViewModel.activeConnection.collectAsStateWithLifecycle()
    val appUpdateViewModel: AppUpdateViewModel = hiltViewModel()
    val updatePromptRelease by appUpdateViewModel.promptRelease.collectAsStateWithLifecycle()

    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val dockFootprint = DockHeight + navBarBottom + DockBreathing

    // 侧边导航按窗口可用宽度判断（不按设备型号）；显式设置优先于自动判定。
    val windowWidth = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width.toDp()
    }
    val sideNavigation = when (appPreferences.navigationLayout) {
        NavigationLayout.SIDE -> true
        NavigationLayout.BOTTOM -> false
        NavigationLayout.AUTO -> windowWidth >= SideNavigationWidthThreshold
    }
    val showDock = !sideNavigation
    val contentBottomPadding = if (sideNavigation) navBarBottom + DockBreathing else dockFootprint

    // Selection is a user event, not a round trip through route effects. Save it with
    // the Activity so secondary-page return and process restoration keep the same tab.
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val selectedTabIndexGetter = remember { { selectedTab } }
    fun selectTab(index: Int) {
        if (index in destinations.indices) selectedTab = index
    }
    // 默认启动页只在首次就绪时应用一次；恢复状态与显式目标都不能被它覆盖。
    var initialStartApplied by rememberSaveable { mutableStateOf(false) }
    var explicitTargetApplied by remember { mutableStateOf(false) }

    val onOpenRouteState = rememberUpdatedState(onOpenRoute)
    fun openRoute(route: String) = onOpenRouteState.value(route)

    // 提醒通知目标统一在此消费：只接受课程 id，跳转后清除，避免重组重复打开。
    LaunchedEffect(pendingCourseId) {
        val courseId = pendingCourseId ?: return@LaunchedEffect
        if (courseId > 0) {
            explicitTargetApplied = true
            openRoute("editor?courseId=$courseId")
        }
        onPendingCourseConsumed()
    }

    // 快捷入口/组件显式路由：白名单外的目标直接丢弃。
    LaunchedEffect(pendingRoute) {
        val route = pendingRoute ?: return@LaunchedEffect
        if (route == "exams") {
            android.widget.Toast.makeText(context, "敬请期待", android.widget.Toast.LENGTH_SHORT).show()
            onPendingRouteConsumed()
            return@LaunchedEffect
        }
        if (route !in ExternalRoutes || SecondaryPageRoute.parse(route) == null) {
            onPendingRouteConsumed()
            return@LaunchedEffect
        }
        explicitTargetApplied = true
        openRoute(route)
        onPendingRouteConsumed()
    }

    LaunchedEffect(pendingTab) {
        val tab = pendingTab ?: return@LaunchedEffect
        explicitTargetApplied = true
        selectTab(tab)
        onPendingTabConsumed()
    }

    var prewarmVisitedMask by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(preferencesReady) {
        if (!preferencesReady) return@LaunchedEffect
        if (!initialStartApplied) {
            initialStartApplied = true
            if (!explicitTargetApplied) {
                selectTab(appPreferences.defaultStartTab.coerceIn(0, destinations.lastIndex))
            }
        }
        // 首屏稳定后按帧依次预热其余主 Tab，消除首次点击底栏时的冷启动构建与重组卡顿。
        for (tabIndex in destinations.indices) {
            if (prewarmVisitedMask and (1 shl tabIndex) == 0) {
                kotlinx.coroutines.delay(90)
                prewarmVisitedMask = prewarmVisitedMask or (1 shl tabIndex)
            }
        }
    }

    // [Dock 滚动自动收起] 仅课表页接入（阈值触发滑出/滑入）；切 Tab 时复位展开。
    val dockAutoHide = remember { com.gfs.iyango.feature.main.liquidglass.DockAutoHideState() }
    val dockHideDensity = LocalDensity.current
    LaunchedEffect(windowWidth) {
        with(dockHideDensity) { dockAutoHide.setThresholdPx(16.dp.toPx()) }
    }
    LaunchedEffect(selectedTab) {
        dockAutoHide.expand()
    }

    val latestBottomPadding by rememberUpdatedState(contentBottomPadding)
    val latestConnection by rememberUpdatedState(activeConnection)
    val latestAppPreferences by rememberUpdatedState(appPreferences)
    val onOpenScheduleTab = remember { { selectTab(1) } }
    val onOpenGradesTab = remember { { selectTab(2) } }

    val tabContent: @Composable (Int) -> Unit = remember {
        { index ->
            when (index) {
                0 -> {
                    TodayScreen(
                        homePreferences = latestAppPreferences.home,
                        bottomPadding = latestBottomPadding,
                        onOpenSchedule = onOpenScheduleTab,
                        onOpenGrades = onOpenGradesTab,
                        onOpenImportExport = { openRoute("importexport") },
                        onOpenSemesterManage = { openRoute("semester") },
                        onOpenCourseManage = { openRoute("courses") },
                        onOpenExams = { android.widget.Toast.makeText(context, "敬请期待", android.widget.Toast.LENGTH_SHORT).show() },
                        onOpenSchool = { openRoute("school") },
                        onOpenHomeSettings = { openRoute("homesettings") },
                        onOpenClassroom = { openRoute("classroom") },
                        onOpenTimetableManage = { openRoute("timetable") },
                        onOpenSettingsBackup = { openRoute("settingsbackup") },
                        onOpenCreditProgress = { openRoute("creditprogress") },
                        onOpenAiAssistant = { openRoute("aiassistant") },
                        onAddCourse = { openRoute("editor?courseId=0") },
                        onOpenCompare = { openRoute("timetablecompare") },
                        onEditCourse = { courseId -> openRoute("editor?courseId=$courseId") },
                        onCopyCourse = { courseId -> openRoute("editor?courseId=0&copyFromId=$courseId") },
                    )
                }
                1 -> {
                    ScheduleScreen(
                        bottomPadding = latestBottomPadding,
                        onAddCourse = { openRoute("editor?courseId=0") },
                        onEditCourse = { courseId -> openRoute("editor?courseId=$courseId") },
                        onCopyCourse = { courseId -> openRoute("editor?courseId=0&copyFromId=$courseId") },
                        onOpenDisplaySettings = { openRoute("display?perTimetable=true") },
                        onOpenSchool = { openRoute("school") },
                        onOpenSync = { openRoute("schoolsync") },
                        onOpenCourseRaw = { timetableId -> openRoute("courseraw/$timetableId") },
                        onOpenAiImport = { openRoute("aiimport") },
                        onOpenCompare = { openRoute("timetablecompare") },
                        onOpenTimetableManage = { openRoute("timetable") },
                    )
                }
                2 -> {
                    GradesScreen(
                        bottomPadding = latestBottomPadding,
                        onOpenSchool = { openRoute("school") },
                        onOpenCreditProgress = { openRoute("creditprogress") },
                    )
                }
                3 -> {
                    MineScreen(
                        bottomPadding = latestBottomPadding,
                        connection = latestConnection,
                        onNavigate = { destination: MineDestination -> openRoute(destination.route) },
                        onOpenCategory = { category -> openRoute(category.route) },
                    )
                }
            }
        }
    }

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

    // L0 背景基底 + L1 页面内容层（统一规范 §4.1）：玻璃表面（卡片/侧栏）只采样 L0，
    // 导航（Dock）采样 L0+L1；页面内容不录入 L0，杜绝自采样闭环。
    // L0 由 GlassBackdropHost 创建并经 LocalGlassBackdrop 下发。
    val contentBackdrop = rememberLayerBackdrop()

    // 根容器手动提供 contentColor（等价 Surface 的默认行为）：四个 Tab 页没有各自的
    // Scaffold/Surface 包裹，裸 Text 依赖 LocalContentColor，而其默认值是黑色——
    // 暗色主题下会黑字黑底（如外观区的分组标题）。在此单点修复，覆盖全部页面。
    CompositionLocalProvider(
        LocalMotionEnabled provides motionEnabled,
        LocalPredictiveBackEnabled provides wallpaperUi.predictiveBackEnabled,
        LocalContentColor provides colorScheme.onSurface,
        LocalGlassPageBackground provides wallpaperPageBackground,
        LocalGlassPalette provides glassPalette,
        com.gfs.iyango.feature.main.liquidglass.LocalDockAutoHide provides dockAutoHide,
    ) {
        val activity = LocalActivity.current
        // 关闭预测性返回时由 Priority=0 BackHandler 消费返回键以屏蔽系统预测缩放预览。
        BackHandler(
            enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !wallpaperUi.predictiveBackEnabled,
        ) {
            activity?.moveTaskToBack(false)
        }
        GlassBackdropHost(
            modifier = Modifier
                .fillMaxSize()
                .background(colorScheme.background),
            hasWallpaper = effectiveWallpaperPath != null,
            wallpaperPath = effectiveWallpaperPath,
            background = {
                // F10：全局壁纸铺在所有主页面之下（今日/成绩/我的均透明根），
                // 课表页可用独立背景覆盖。壁纸层注册进 L0，供玻璃表面采样。
                WallpaperLayer(
                    path = effectiveWallpaperPath,
                    scrim = appPreferences.wallpaperScrim,
                    blur = appPreferences.wallpaperBlur,
                    modifier = Modifier.fillMaxSize(),
                )
            },
        ) {
            // Dock 同时采样 L0（背景基底）与 L1（页面内容）；L0 缺失时退化为只采样内容层。
            val glassL0 = LocalGlassBackdrop.current
            val dockBackdrop = if (glassL0 != null) {
                rememberCombinedBackdrop(glassL0, contentBackdrop)
            } else {
                contentBackdrop
            }
            if (sideNavigation) {
                Row(Modifier.fillMaxSize()) {
                    SideNavigation(
                        destinations = destinations,
                        selectedIndex = selectedTab,
                        onSelect = ::selectTab,
                        interactive = true,
                        animationsEnabled = motionEnabled,
                        modifier = Modifier.fillMaxHeight(),
                    )
                    RetainedMainTabs(
                        selectedIndex = selectedTab,
                        prewarmVisitedMask = prewarmVisitedMask,
                        animateSwitch = motionEnabled,
                        content = tabContent,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .layerBackdrop(contentBackdrop),
                    )
                }
            } else {
                RetainedMainTabs(
                    selectedIndex = selectedTab,
                    prewarmVisitedMask = prewarmVisitedMask,
                    animateSwitch = motionEnabled,
                    content = tabContent,
                    modifier = Modifier.layerBackdrop(contentBackdrop),
                )
            }
            if (showDock) {
                val dockVisible = dockAutoHide.visible
                val target = if (dockVisible) 1f else 0f
                val dockFraction = if (motionEnabled) animateFloatAsState(
                    target, tween(AppMotion.Expand), label = "dockVisibility",
                ) else rememberUpdatedState(target)
                val dockPlaced by remember(dockFraction) { derivedStateOf { dockFraction.value > 0f } }
                if (dockVisible || dockPlaced) Box(
                    Modifier.align(Alignment.BottomCenter).graphicsLayer {
                        alpha = dockFraction.value
                        translationY = size.height * (1f - alpha)
                    }.then(if (dockVisible) Modifier else Modifier.clearAndSetSemantics {})
                        .blockInputWhenInactive(dockVisible),
                ) {
                    LiquidBottomTabs(
                        selectedTabIndex = selectedTabIndexGetter,
                        interactive = dockVisible,
                        onTabSelected = ::selectTab,
                        backdrop = dockBackdrop,
                        tabsCount = destinations.size,
                        surfaceAlphaLight = themeSettings.dockSurfaceAlphaLight,
                        surfaceAlphaDark = themeSettings.dockSurfaceAlphaDark,
                        glassSettings = themeSettings.dockGlass,
                        modifier = Modifier
                            .padding(horizontal = DockHorizontalMargin)
                            // [酷安实测对齐] 只避让底部系统内边距，左右保持 20dp（酷安生产页 65px@3.25），
                            // 不再叠加 side safe inset，否则实际边距会变成 35dp、整条比酷安窄 30dp。
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
                    ) {
                        // [酷安对齐] 选中项图标/文字用主题强调色（酷安生产页实测：选中 tab 为 accent）。
                        destinations.forEachIndexed { index, destination ->
                            LiquidBottomTab(
                                index = index,
                                selected = index == selectedTab,
                            ) {
                                DockTabContent(
                                    destination = destination,
                                    selected = index == selectedTab,
                                )
                            }
                        }
                    }
                }
            }
            updatePromptRelease?.let { release ->
                AppUpdateDialog(
                    release = release,
                    onDismiss = appUpdateViewModel::dismissPrompt,
                    onIgnore = appUpdateViewModel::ignoreVersion,
                    onOpenUpdatePage = {
                        if (!release.forceUpdate) {
                            appUpdateViewModel.dismissPrompt()
                        }
                        openRoute("appupdate")
                    },
                )
            }
        }
    }
}

/**
 * tab 内容（图标 + 文案）。基础玻璃条与隐藏强调色层各渲染一份相同内容，
 * 隐藏层经 ColorFilter.tint(accent) 后被可拖拽指示器取色。
 *
 * [酷安对齐] 选中项（以 selectedTab 为准，松手才变）在可见层直接用品牌强调色点亮，
 * 未选中保持中性色 onSurfaceVariant；选中文字加 Medium 字重。
 * [图标明暗自适应] 图标颜色跟随主题背景：浅色主题纯黑、深色主题纯白（ThemeMode 跟随系统时
 * 即随系统深浅色切换）；选中差异只由文字颜色 + 透镜透出的强调色层体现。
 */
@Composable
private fun ColumnScope.DockTabContent(destination: MainDestination, selected: Boolean) {
    DockTabContent(label = destination.label, icon = destination.icon, selected = selected)
}

@Composable
internal fun ColumnScope.DockTabContent(label: String, icon: ImageVector, selected: Boolean) {
    val contentColor = if (selected) {
        LocalBrand.current.accent
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    // 与 LiquidBottomTabs 的明暗判定保持一致：选中项直接使用主题强调色，未选中按背景明暗适配。
    val isLightTheme = MaterialTheme.colorScheme.background.luminance() >= 0.5f
    val iconTint = if (selected) {
        LocalBrand.current.accent
    } else if (isLightTheme) {
        Color.Black.copy(alpha = 0.78f)
    } else {
        Color.White.copy(alpha = 0.80f)
    }
    Icon(
        imageVector = icon,
        contentDescription = label,
        tint = iconTint,
        // [酷安实测对齐] 图标 24dp、文案 12sp（labelMedium），内容行 48dp 内居中。
        modifier = Modifier.size(24.dp),
    )
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = contentColor,
        fontWeight = if (selected) FontWeight.SemiBold else null,
    )
}

/**
 * 宽窗口侧边导航（F15）：与底部 Dock 同一套目标，只改变摆放位置。
 * 使用导航玻璃档统一材质（GlassTokens.Navigation），采样根级 L0 背景基底；
 * 无宿主或旧系统按 GlassSurface 回退主题表面。显隐动画跟随“界面动画”设置。
 */
@Composable
private fun SideNavigation(
    destinations: List<MainDestination>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    interactive: Boolean,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val brand = LocalBrand.current
    val hostBackdrop = LocalGlassBackdrop.current
    val exportedNavBackdrop = rememberLayerBackdrop()
    val itemBackdrop = if (hostBackdrop != null) exportedNavBackdrop else null
    GlassSurface(
        material = GlassMaterial.NAVIGATION,
        shape = RectangleShape,
        exportedBackdrop = exportedNavBackdrop,
        modifier = modifier.width(SideNavigationWidth)
            .then(if (interactive) Modifier else Modifier.clearAndSetSemantics {})
            .blockInputWhenInactive(interactive),
    ) {
        CompositionLocalProvider(LocalGlassBackdrop provides itemBackdrop) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(brand.brandSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Y",
                        style = MaterialTheme.typography.labelLarge,
                        color = brand.accent,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(10.dp))
                destinations.forEachIndexed { index, destination ->
                    val isSelected = index == selectedIndex
                    val itemModifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                    val itemContent: @Composable () -> Unit = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedRectangle(16.dp))
                                .clickable(enabled = interactive) { onSelect(index) }
                                .padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = destination.label,
                                tint = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = destination.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (isSelected) FontWeight.Medium else null,
                            )
                        }
                    }
                    if (isSelected) {
                        GlassSurface(
                            modifier = itemModifier,
                            shape = RoundedRectangle(16.dp),
                            material = GlassMaterial.CONTROL,
                            tintColor = brand.accent,
                        ) {
                            itemContent()
                        }
                    } else {
                        Box(modifier = itemModifier) {
                            itemContent()
                        }
                    }
                }
            }
        }
    }
}

internal fun isWifiActive(context: Context): Boolean = runCatching {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
}.getOrDefault(false)
