package com.gfs.iyango

import android.graphics.Typeface
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gfs.iyango.core.designsystem.theme.IYangoTheme
import com.gfs.iyango.core.domain.ThemeMode
import com.gfs.iyango.core.domain.ThemeSettings
import com.gfs.iyango.core.domain.WallpaperScheduler
import com.gfs.iyango.core.model.WallpaperSwitchMode
import com.gfs.iyango.feature.main.resolveGrayTextColors
import java.io.File
import java.time.ZonedDateTime
import kotlinx.coroutines.delay

/** 自定义字体 FontFamily 进程级缓存：同一路径只 `Typeface.createFromFile` 一次。 */
private object CustomFontFamilyCache {
    private val cache = HashMap<String, FontFamily?>()

    fun fontFamily(path: String?): FontFamily? {
        if (path == null) return null
        synchronized(cache) {
            if (cache.containsKey(path)) return cache[path]
            val family = runCatching { FontFamily(Typeface.createFromFile(File(path))) }.getOrNull()
            cache[path] = family
            return family
        }
    }
}

/**
 * Activity 通用主题外壳（多 Activity 架构 P0）：MainActivity 与 SecondaryPageActivity
 * 共用，从原 MainActivity 抽取。
 *
 * - [ThemeViewModel] 订阅外观/偏好/字体路径（快照缓存消除新开 Activity 首帧闪烁）；
 * - 日出日落自动切换深浅色（[WallpaperScheduler.isSolarDaytime] + `ON_START` tick 重算）；
 * - 自定义字体加载（进程级按路径缓存，避免每个 Activity 重复 `createFromFile`）；
 * - 灰字增强配色（[resolveGrayTextColors]）；
 * - 状态栏/导航栏图标明暗跟随实际生效主题。
 *
 * @param content 主题就绪后的页面内容，注入当前外观设置与其变更回调。
 */
@Composable
fun IYangoActivityHost(
    content: @Composable (themeSettings: ThemeSettings, onThemeSettingsChange: (ThemeSettings) -> Unit) -> Unit,
) {
    val themeViewModel: ThemeViewModel = hiltViewModel()
    val settings by themeViewModel.settings.collectAsStateWithLifecycle()
    val appPrefs by themeViewModel.appPreferences.collectAsStateWithLifecycle()
    val customFontPath by themeViewModel.customFontPath.collectAsStateWithLifecycle()
    var solarTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var skipInitialStart = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                if (skipInitialStart) {
                    skipInitialStart = false
                    return@LifecycleEventObserver
                }
                solarTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(
        appPrefs.wallpaperSwitchMode,
        appPrefs.sunriseCityId,
        appPrefs.sunriseCustomMinutes,
        appPrefs.sunsetCustomMinutes,
        appPrefs.sunriseSyncTheme,
        solarTick,
    ) {
        if (appPrefs.wallpaperSwitchMode == WallpaperSwitchMode.SUNRISE_SUNSET && appPrefs.sunriseSyncTheme) {
            val waitMs = WallpaperScheduler.millisUntilNextTransition(appPrefs, ZonedDateTime.now())
            if (waitMs > 0L) {
                delay(waitMs)
                solarTick++
            }
        }
    }
    val systemDark = isSystemInDarkTheme()
    val darkTheme = if (
        appPrefs.wallpaperSwitchMode == WallpaperSwitchMode.SUNRISE_SUNSET &&
        appPrefs.sunriseSyncTheme
    ) {
        remember(solarTick, appPrefs.sunriseCityId, appPrefs.sunriseCustomMinutes, appPrefs.sunsetCustomMinutes) {
            !WallpaperScheduler.isSolarDaytime(appPrefs, ZonedDateTime.now())
        }
    } else {
        when (settings.themeMode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> systemDark
        }
    }
    val customFontFamily = remember(customFontPath) { CustomFontFamilyCache.fontFamily(customFontPath) }
    val (secondaryTextOverride, tertiaryTextOverride) = remember(
        appPrefs.grayTextMode,
        appPrefs.grayTextCustomHex,
        darkTheme,
    ) {
        resolveGrayTextColors(appPrefs.grayTextMode, appPrefs.grayTextCustomHex, darkTheme)
    }
    // 状态栏/手势栏图标明暗跟随实际生效的主题（而非系统设置）。
    val activity = LocalActivity.current
    LaunchedEffect(darkTheme, activity) {
        val window = activity?.window ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
    IYangoTheme(
        darkTheme = darkTheme,
        accentHex = settings.accentHex,
        tintBackground = settings.tintBackground,
        fontFamily = customFontFamily,
        fontScaleFactor = appPrefs.fontScaleFactor,
        secondaryTextColorOverride = secondaryTextOverride,
        tertiaryTextColorOverride = tertiaryTextOverride,
    ) {
        content(settings, themeViewModel::update)
    }
}
