package com.gfs.iyango.feature.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gfs.iyango.core.domain.ThemeSettings
import com.gfs.iyango.core.domain.WallpaperScheduler
import com.gfs.iyango.core.model.AppPreferences
import com.gfs.iyango.core.model.WallpaperSwitchMode
import com.gfs.iyango.feature.main.settings.WallpaperViewModel
import java.time.ZonedDateTime
import kotlinx.coroutines.delay

/**
 * 全局壁纸运行时状态（多 Activity 架构 P2）。
 *
 * 从原 `IYangoMainApp` 抽取：主壳（四个主 Tab）与 [IYangoSecondaryPage]
 * 共用同一套 WallpaperViewModel 订阅、日出日落/轮播调度、Bing 每日自动更新、
 * 壁纸路径解析与动态取色逻辑，避免两处代码漂移。
 */
internal class GlobalWallpaperUi(
    val wallpaperViewModel: WallpaperViewModel,
    val preferences: AppPreferences,
    val preferencesReady: Boolean,
    val motionEnabled: Boolean,
    /** 应用级与系统级预测性返回是否均已就绪。 */
    val predictiveBackEnabled: Boolean,
    /** 当前主题下生效的全局壁纸绝对路径；null = 无壁纸。 */
    val activeGlobalWallpaperPath: String?,
    /** 实际生效的全局壁纸相对路径（动态取色用）。 */
    val effectiveGlobalRelativePath: String,
)

@Composable
internal fun rememberGlobalWallpaperUi(
    themeSettings: ThemeSettings,
    onThemeSettingsChange: (ThemeSettings) -> Unit,
    isMainShell: Boolean = false,
): GlobalWallpaperUi {
    val wallpaperViewModel: WallpaperViewModel = hiltViewModel()
    val appPreferences by wallpaperViewModel.preferences.collectAsStateWithLifecycle()
    val preferencesReady by wallpaperViewModel.preferencesReady.collectAsStateWithLifecycle()
    val motionEnabled = rememberMotionEnabled(preferencesReady && appPreferences.animationsEnabled)
    val systemPredictiveBackStatus = rememberSystemPredictiveBackStatus()
    val predictiveBackEnabled =
        appPreferences.predictiveBackEnabled && systemPredictiveBackStatus == SystemPredictiveBackStatus.ENABLED
    val globalWallpaperPath by wallpaperViewModel.globalWallpaperPath.collectAsStateWithLifecycle()
    val globalWallpaperPathDark by wallpaperViewModel.globalWallpaperPathDark.collectAsStateWithLifecycle()
    val currentDark = isDarkTheme()
    val context = LocalContext.current

    var scheduleTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(
        preferencesReady,
        appPreferences.wallpaperSwitchMode,
        appPreferences.sunriseCityId,
        appPreferences.sunriseCustomMinutes,
        appPreferences.sunsetCustomMinutes,
        scheduleTick,
    ) {
        if (!preferencesReady) return@LaunchedEffect
        val waitMs = WallpaperScheduler.millisUntilNextTransition(appPreferences, ZonedDateTime.now())
        if (waitMs > 0L) {
            delay(waitMs)
            scheduleTick++
        }
    }

    var launchCarouselAdvanced by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(preferencesReady, isMainShell) {
        if (!preferencesReady || !isMainShell) return@LaunchedEffect
        if (!launchCarouselAdvanced &&
            appPreferences.wallpaperSwitchMode == WallpaperSwitchMode.CAROUSEL_LAUNCH &&
            appPreferences.carouselPaths.size > 1
        ) {
            launchCarouselAdvanced = true
            wallpaperViewModel.advanceCarousel()
        }
        wallpaperViewModel.checkAutoUpdateBing(
            isWifiConnected = isWifiActive(context),
            darkTheme = currentDark,
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val latestDark by rememberUpdatedState(currentDark)
    val latestIsMainShell by rememberUpdatedState(isMainShell)
    DisposableEffect(lifecycleOwner, preferencesReady) {
        if (!preferencesReady) return@DisposableEffect onDispose {}
        var skipInitialStart = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                if (skipInitialStart) {
                    skipInitialStart = false
                    return@LifecycleEventObserver
                }
                scheduleTick++
                if (latestIsMainShell) {
                    wallpaperViewModel.checkAutoUpdateBing(
                        isWifiConnected = isWifiActive(context),
                        darkTheme = latestDark,
                    )
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val activeGlobalRelativePath = remember(appPreferences, currentDark, scheduleTick) {
        WallpaperScheduler.resolveGlobalWallpaperRelativePath(
            prefs = appPreferences,
            isDarkTheme = currentDark,
            now = ZonedDateTime.now(),
        )
    }
    val resolvedScheduledPath = remember(
        activeGlobalRelativePath,
        appPreferences.wallpaperPath,
        appPreferences.wallpaperPathDark,
        globalWallpaperPath,
        globalWallpaperPathDark,
    ) {
        when {
            activeGlobalRelativePath.isBlank() -> null
            activeGlobalRelativePath == appPreferences.wallpaperPath -> globalWallpaperPath
            activeGlobalRelativePath == appPreferences.wallpaperPathDark -> globalWallpaperPathDark
            else -> wallpaperViewModel.resolveAbsolutePath(activeGlobalRelativePath)
        }
    }
    // 兼容按常规亮暗槽回落，保证与老配置 100% 等价。
    val activeGlobalWallpaperPath = resolvedScheduledPath ?: if (currentDark) {
        globalWallpaperPathDark ?: globalWallpaperPath
    } else {
        globalWallpaperPath ?: globalWallpaperPathDark
    }

    val effectiveGlobalRelativePath = remember(activeGlobalRelativePath, appPreferences, currentDark) {
        activeGlobalRelativePath.ifBlank {
            if (currentDark) appPreferences.wallpaperPathDark.ifBlank { appPreferences.wallpaperPath }
            else appPreferences.wallpaperPath.ifBlank { appPreferences.wallpaperPathDark }
        }
    }
    val latestThemeSettings by rememberUpdatedState(themeSettings)
    val latestOnThemeSettingsChange by rememberUpdatedState(onThemeSettingsChange)
    LaunchedEffect(preferencesReady, appPreferences.wallpaperDynamicColor, effectiveGlobalRelativePath) {
        if (!preferencesReady || !appPreferences.wallpaperDynamicColor) return@LaunchedEffect
        if (effectiveGlobalRelativePath.isBlank()) return@LaunchedEffect
        val extracted = wallpaperViewModel.extractAccentFromPath(effectiveGlobalRelativePath)
        if (extracted != null && !extracted.equals(latestThemeSettings.accentHex, ignoreCase = true)) {
            latestOnThemeSettingsChange(latestThemeSettings.copy(accentHex = extracted))
        }
    }

    return GlobalWallpaperUi(
        wallpaperViewModel = wallpaperViewModel,
        preferences = appPreferences,
        preferencesReady = preferencesReady,
        motionEnabled = motionEnabled,
        predictiveBackEnabled = predictiveBackEnabled,
        activeGlobalWallpaperPath = activeGlobalWallpaperPath,
        effectiveGlobalRelativePath = effectiveGlobalRelativePath,
    )
}
