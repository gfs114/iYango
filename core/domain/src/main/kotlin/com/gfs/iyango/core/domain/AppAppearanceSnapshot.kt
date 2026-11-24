package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.AppPreferences

/**
 * 进程级外观快照缓存（多 Activity 架构 P0）。
 *
 * 新开 [com.gfs.iyango.SecondaryPageActivity] 时，其 [ThemeViewModel]/[WallpaperViewModel]
 * 的 `stateIn` 首帧还没等到 DataStore 首次发射；用本快照中的最新值作 `initialValue`，
 * 消除新开 Activity 的默认主题/无壁纸首帧闪烁。上游 Flow 每次发射时同步刷新快照，
 * 因此各 Activity 之间共享同一份最新外观。纯领域类型，不含 Android 侧
 * `Typeface`/`FontFamily`（后者缓存在 `:app` 的内存缓存中）。
 */
object AppAppearanceSnapshot {

    @Volatile
    var themeSettings: ThemeSettings = ThemeSettings()
        private set

    @Volatile
    var appPreferences: AppPreferences = AppPreferences()
        private set

    @Volatile
    var customFontPath: String? = null
        private set

    @Volatile
    var globalWallpaperPath: String? = null
        private set

    @Volatile
    var globalWallpaperPathDark: String? = null
        private set

    /** DataStore 首次发射是否已到达（未就绪时各 ViewModel 不得用快照默认值覆盖跳转）。 */
    @Volatile
    var preferencesReady: Boolean = false
        private set

    /** 主题外观设置每次发射同步刷新。 */
    fun updateThemeSettings(value: ThemeSettings) {
        themeSettings = value
    }

    /**
     * 应用偏好每次发射同步刷新；首次发射即代表 DataStore 已就绪。
     * （ThemeViewModel 与 WallpaperViewModel 共用同一条底层 DataStore 流，
     * 谁先发射谁就绪，避免重复标记。）
     */
    fun updateAppPreferences(value: AppPreferences) {
        appPreferences = value
        preferencesReady = true
    }

    /** 自定义字体绝对路径（经 CustomFontStore 解析）同步刷新。 */
    fun updateCustomFontPath(value: String?) {
        customFontPath = value
    }

    /** 全局壁纸亮/暗槽绝对路径同步刷新（成对写入避免半更新窗口）。 */
    fun updateGlobalWallpaperPaths(light: String?, dark: String?) {
        globalWallpaperPath = light
        globalWallpaperPathDark = dark
    }
}
