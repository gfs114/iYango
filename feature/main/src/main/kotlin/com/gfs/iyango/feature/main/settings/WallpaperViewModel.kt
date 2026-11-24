package com.gfs.iyango.feature.main.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.domain.AppSettingsPort
import com.gfs.iyango.core.domain.AppAppearanceSnapshot
import com.gfs.iyango.core.domain.BingWallpaperInfo
import com.gfs.iyango.core.domain.BingWallpaperPort
import com.gfs.iyango.core.domain.CropPixelRegion
import com.gfs.iyango.core.domain.PreparedWallpaper
import com.gfs.iyango.core.domain.WallpaperStore
import com.gfs.iyango.core.domain.WallpaperTone
import com.gfs.iyango.core.model.AppPreferences
import com.gfs.iyango.core.model.BingWallpaperTarget
import com.gfs.iyango.core.model.DayPeriod
import com.gfs.iyango.core.model.NavigationLayout
import com.gfs.iyango.core.model.WallpaperSwitchMode
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 壁纸与背景 ViewModel（F10）。
 *
 * 流程两段式：[prepare] 解码原图进临时文件（保留原始取景），裁剪页在其上调整
 * 选框；[confirmCrop] 按像素窗口裁剪提交。亮色/暗色各一个槽位，暗色未设置时
 * 渲染层回落亮色。提交成功后按壁纸亮度自动给出可读性遮罩初值，用户仍可用滑杆微调。
 *
 * 多 Activity 架构：`stateIn` 的 `initialValue` 取自 [AppAppearanceSnapshot]
 * （进程内最新已知值），发射时回写快照，保证新开 SecondaryPageActivity 首帧
 * 不闪无壁纸背景。
 */
@HiltViewModel
class WallpaperViewModel @Inject constructor(
    private val appSettings: AppSettingsPort,
    private val wallpaperStore: WallpaperStore,
    private val bingWallpaper: BingWallpaperPort,
) : ViewModel() {

    /** 应用级壁纸偏好（根背景与课表页共用）。 */
    val preferences: StateFlow<AppPreferences> = appSettings.appPreferences
        .onEach { AppAppearanceSnapshot.updateAppPreferences(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppAppearanceSnapshot.appPreferences)

    /** 首次读取完成信号：默认启动页必须等它之后再应用，避免用占位默认值覆盖跳转。 */
    val preferencesReady: StateFlow<Boolean> = appSettings.appPreferences
        .map { true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppAppearanceSnapshot.preferencesReady)

    private fun pathFlow(
        initialValue: String? = null,
        map: (AppPreferences) -> String,
    ): StateFlow<String?> =
        appSettings.appPreferences
            .map { wallpaperStore.resolve(map(it)) }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue)

    /** 全局壁纸（亮色）绝对路径；未设置或文件缺失为 null。 */
    val globalWallpaperPath: StateFlow<String?> =
        pathFlow(initialValue = AppAppearanceSnapshot.globalWallpaperPath) { it.wallpaperPath }

    /** 全局壁纸（暗色）绝对路径；null 渲染层回落亮色。 */
    val globalWallpaperPathDark: StateFlow<String?> =
        pathFlow(initialValue = AppAppearanceSnapshot.globalWallpaperPathDark) { it.wallpaperPathDark }

    init {
        // 仅由真实 DataStore 发射异步解析并回写快照，避免冷启动未就绪时的 initialValue 倒灌覆盖。
        viewModelScope.launch {
            appSettings.appPreferences
                .map { prefs ->
                    wallpaperStore.resolve(prefs.wallpaperPath) to wallpaperStore.resolve(prefs.wallpaperPathDark)
                }
                .flowOn(Dispatchers.IO)
                .collect { (light, dark) ->
                    AppAppearanceSnapshot.updateGlobalWallpaperPaths(light, dark)
                }
        }
    }

    /** 课表页独立背景（亮色）绝对路径；未设置时课表页透出全局壁纸。 */
    val timetableWallpaperPath: StateFlow<String?> =
        pathFlow { it.timetableWallpaperPath }

    /** 课表页独立背景（暗色）绝对路径；null 渲染层回落亮色。 */
    val timetableWallpaperPathDark: StateFlow<String?> =
        pathFlow { it.timetableWallpaperPathDark }

    /** 四时段壁纸（早晨 / 午后 / 傍晚 / 深夜）绝对路径。 */
    val periodMorningPath: StateFlow<String?> = pathFlow { it.periodMorningPath }
    val periodAfternoonPath: StateFlow<String?> = pathFlow { it.periodAfternoonPath }
    val periodEveningPath: StateFlow<String?> = pathFlow { it.periodEveningPath }
    val periodNightPath: StateFlow<String?> = pathFlow { it.periodNightPath }

    /** 多图轮播已解析的有效条目（相对路径 to 绝对路径）。 */
    val carouselResolvedPaths: StateFlow<List<Pair<String, String>>> =
        preferences.map { prefs ->
            prefs.carouselPaths.mapNotNull { rel ->
                wallpaperStore.resolve(rel)?.let { abs -> rel to abs }
            }
        }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun resolveAbsolutePath(relativePath: String): String? = wallpaperStore.resolve(relativePath)

    private val autoUpdateGuard = AtomicBoolean(false)

    private val _pending = MutableStateFlow<PendingWallpaper?>(null)

    /** 待裁剪的图片（未裁剪，原图已限界解码）。 */
    val pending: StateFlow<PendingWallpaper?> = _pending.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _bingInfo = MutableStateFlow<BingWallpaperInfo?>(null)
    val bingInfo: StateFlow<BingWallpaperInfo?> = _bingInfo.asStateFlow()

    private val _bingLoading = MutableStateFlow(false)
    val bingLoading: StateFlow<Boolean> = _bingLoading.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    /** 一次性提示（解码失败、保存失败等）。 */
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 拉取微软 Bing 今日一图元数据。 */
    fun fetchBingToday() {
        if (_bingLoading.value) return
        _bingLoading.value = true
        viewModelScope.launch {
            try {
                bingWallpaper.fetchToday()
                    .onSuccess { _bingInfo.value = it }
                    .onFailure { _message.value = "获取必应每日壁纸失败，请检查网络连接" }
            } finally {
                _bingLoading.value = false
            }
        }
    }

    /** 下载必应壁纸并打开裁剪页准备应用到指定槽位。 */
    fun applyBingToSlot(slot: String, dark: Boolean) {
        if (_busy.value || _bingLoading.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val info = _bingInfo.value ?: bingWallpaper.fetchToday().getOrNull()?.also {
                    _bingInfo.value = it
                }
                if (info == null) {
                    _message.value = "获取必应每日壁纸失败，请稍后重试"
                    return@launch
                }
                val bytes = bingWallpaper.downloadImage(info.imageUrl).getOrNull()
                    ?: info.fallbackUrl.takeIf { it.isNotBlank() }?.let { url ->
                        bingWallpaper.downloadImage(url).getOrNull()
                    }
                if (bytes == null) {
                    _message.value = "下载必应壁纸失败，请重试"
                    return@launch
                }
                val prepared = wallpaperStore.prepareFromBytes(bytes)
                if (prepared == null) {
                    _message.value = "壁纸解码失败，请重试"
                    return@launch
                }
                _pending.value?.let { wallpaperStore.discard(it.image.tempPath) }
                _pending.value = PendingWallpaper(slot = slot, dark = dark, image = prepared)
            } finally {
                _busy.value = false
            }
        }
    }

    /** 选择图片后解码/限界写入临时文件；失败提示且不改变已保存壁纸。 */
    fun prepare(sourceUri: String, slot: String, dark: Boolean) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val prepared = wallpaperStore.prepare(sourceUri)
            if (prepared == null) {
                _message.value = "图片读取失败，请重新选择图片，已保留原壁纸"
            } else {
                _pending.value?.let { wallpaperStore.discard(it.image.tempPath) }
                _pending.value = PendingWallpaper(slot = slot, dark = dark, image = prepared)
            }
            _busy.value = false
        }
    }

    /**
     * 确认裁剪并提交：按 [crop] 像素窗口裁剪临时文件写入正式文件（null = 整图采用）。
     *
     * @param darkTheme 当前主题（用于按壁纸亮度推导遮罩初值；遮罩为全局共享，取确认时主题）。
     */
    fun confirmCrop(crop: CropPixelRegion?, darkTheme: Boolean) {
        val pending = _pending.value ?: return
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            if (pending.slot == WallpaperStore.SLOT_CAROUSEL_PREFIX) {
                val uniqueSlot = "${WallpaperStore.SLOT_CAROUSEL_PREFIX}_${System.currentTimeMillis()}"
                val relative = wallpaperStore.commit(pending.image.tempPath, uniqueSlot, crop)
                if (relative == null) {
                    _message.value = "壁纸保存失败，已保留原轮播列表"
                } else {
                    val scrim = WallpaperTone.recommendedScrim(pending.image.averageLuminance, darkTheme)
                    persistPreferences {
                        it.copy(
                            carouselPaths = (it.carouselPaths + relative).distinct().take(12),
                            wallpaperScrim = scrim.coerceIn(0f, 0.8f),
                        ).sanitized()
                    }
                    _pending.value = null
                }
                _busy.value = false
                return@launch
            }

            val oldPath = currentPath(pending.slot, pending.dark)
            val fileSlot = pending.slot + if (pending.dark) "_dark" else ""
            val relative = wallpaperStore.commit(pending.image.tempPath, fileSlot, crop)
            if (relative == null) {
                _message.value = "壁纸保存失败，已保留原壁纸"
            } else {
                val scrim = WallpaperTone.recommendedScrim(pending.image.averageLuminance, darkTheme)
                persistPreferences {
                    withPath(it, pending.slot, pending.dark, relative)
                        .copy(wallpaperScrim = scrim.coerceIn(0f, 0.8f))
                }
                _pending.value = null
                if (oldPath.isNotBlank() && oldPath != relative) {
                    wallpaperStore.delete(oldPath)
                }
            }
            _busy.value = false
        }
    }

    /** 取消裁剪：删除临时文件。 */
    fun cancelPending() {
        val pending = _pending.value ?: return
        _pending.value = null
        viewModelScope.launch { wallpaperStore.discard(pending.image.tempPath) }
    }

    /** 移除某个槽位（亮/暗）的壁纸（删除文件并清空引用）。 */
    fun remove(slot: String, dark: Boolean) {
        viewModelScope.launch {
            val path = currentPath(slot, dark)
            persistPreferences { withPath(it, slot, dark, "") }
            if (path.isNotBlank()) wallpaperStore.delete(path)
        }
    }

    /** 保存可读性遮罩不透明度（0..0.8）。 */
    fun setScrim(value: Float) {
        updatePreferences { it.copy(wallpaperScrim = value.coerceIn(0f, 0.8f)) }
    }

    /** 保存壁纸模糊强度（0..1，渲染层映射半径）。 */
    fun setBlur(value: Float) {
        updatePreferences { it.copy(wallpaperBlur = value.coerceIn(0f, 1f)) }
    }

    /** 默认启动页（0=今日 1=课表 2=成绩 3=我的）。 */
    fun setDefaultStartTab(tab: Int) {
        updatePreferences { it.copy(defaultStartTab = tab.coerceIn(0, 3)) }
    }

    fun setNavigationLayout(layout: NavigationLayout) {
        updatePreferences { it.copy(navigationLayout = layout) }
    }

    fun setAnimationsEnabled(enabled: Boolean) {
        updatePreferences { it.copy(animationsEnabled = enabled) }
    }

    fun setPredictiveBackEnabled(enabled: Boolean) {
        updatePreferences { it.copy(predictiveBackEnabled = enabled) }
    }

    fun setWallpaperSwitchMode(mode: WallpaperSwitchMode) {
        updatePreferences { it.copy(wallpaperSwitchMode = mode) }
    }

    fun setSunriseCity(cityId: String) {
        updatePreferences { it.copy(sunriseCityId = cityId).sanitized() }
    }

    fun setSunriseCustomTimes(sunriseMinutes: Int, sunsetMinutes: Int) {
        updatePreferences {
            it.copy(
                sunriseCustomMinutes = sunriseMinutes.coerceIn(0, 1439),
                sunsetCustomMinutes = sunsetMinutes.coerceIn(0, 1439),
            )
        }
    }

    fun setSunriseSyncTheme(enabled: Boolean) {
        updatePreferences { it.copy(sunriseSyncTheme = enabled) }
    }

    fun removePeriodWallpaper(period: DayPeriod) {
        viewModelScope.launch {
            val path = currentPath(period.slotKey, false)
            persistPreferences { withPath(it, period.slotKey, false, "") }
            if (path.isNotBlank()) wallpaperStore.delete(path)
        }
    }

    fun removeCarouselWallpaper(relativePath: String) {
        viewModelScope.launch {
            persistPreferences { prefs ->
                val remaining = prefs.carouselPaths.filterNot { it == relativePath }
                val nextIndex = if (remaining.isEmpty()) 0 else prefs.carouselIndex % remaining.size
                prefs.copy(carouselPaths = remaining, carouselIndex = nextIndex).sanitized()
            }
            if (relativePath.isNotBlank()) wallpaperStore.delete(relativePath)
        }
    }

    fun advanceCarousel() {
        updatePreferences { prefs ->
            val size = prefs.carouselPaths.size
            if (size <= 1) prefs else prefs.copy(carouselIndex = (prefs.carouselIndex + 1) % size)
        }
    }

    fun setWallpaperDynamicColor(enabled: Boolean) {
        updatePreferences { it.copy(wallpaperDynamicColor = enabled) }
    }

    suspend fun extractAccentFromPath(relativePath: String): String? =
        wallpaperStore.extractAccentHex(relativePath)

    fun setBingAutoUpdate(
        enabled: Boolean,
        isWifiConnected: Boolean = false,
        darkTheme: Boolean = false,
    ) {
        viewModelScope.launch {
            persistPreferences { it.copy(bingAutoUpdateEnabled = enabled) }
            if (enabled) {
                runAutoUpdateBing(
                    isWifiConnected = isWifiConnected,
                    darkTheme = darkTheme,
                    notifyUser = true,
                )
            }
        }
    }

    fun setBingAutoUpdateWifiOnly(
        wifiOnly: Boolean,
        isWifiConnected: Boolean = false,
        darkTheme: Boolean = false,
    ) {
        viewModelScope.launch {
            persistPreferences { it.copy(bingAutoUpdateWifiOnly = wifiOnly) }
            if (!wifiOnly) {
                runAutoUpdateBing(
                    isWifiConnected = isWifiConnected,
                    darkTheme = darkTheme,
                    notifyUser = true,
                )
            }
        }
    }

    fun setBingAutoUpdateTarget(target: BingWallpaperTarget) {
        updatePreferences { it.copy(bingAutoUpdateTarget = target) }
    }

    /** 检查并按日自动拉取 Bing 每日一图写入目标槽位（遵循仅 Wi‑Fi 开关，每日至多成功更新一次）。 */
    fun checkAutoUpdateBing(
        isWifiConnected: Boolean,
        darkTheme: Boolean,
        notifyUser: Boolean = false,
    ) {
        viewModelScope.launch {
            runAutoUpdateBing(
                isWifiConnected = isWifiConnected,
                darkTheme = darkTheme,
                notifyUser = notifyUser,
            )
        }
    }

    private suspend fun runAutoUpdateBing(
        isWifiConnected: Boolean,
        darkTheme: Boolean,
        notifyUser: Boolean,
    ) {
        if (!autoUpdateGuard.compareAndSet(false, true)) return
        try {
            val prefs = appSettings.appPreferences.first()
            if (!prefs.bingAutoUpdateEnabled) return
            val todayStr = LocalDate.now().toString()
            if (prefs.bingLastAutoUpdateDate == todayStr) return
            if (prefs.bingAutoUpdateWifiOnly && !isWifiConnected) {
                if (notifyUser) {
                    _message.value = "已开启每日首次打开自动更新，连接 Wi‑Fi 后将自动更新"
                }
                return
            }

            _bingLoading.value = true
            val info = bingWallpaper.fetchToday().getOrNull() ?: run {
                if (notifyUser) _message.value = "今日 Bing 壁纸自动更新失败，下次打开将自动重试"
                return
            }
            _bingInfo.value = info
            val bytes = bingWallpaper.downloadImage(info.imageUrl).getOrNull()
                ?: info.fallbackUrl.takeIf { it.isNotBlank() }?.let { url ->
                    bingWallpaper.downloadImage(url).getOrNull()
                }
                ?: run {
                    if (notifyUser) _message.value = "今日 Bing 壁纸自动更新失败，下次打开将自动重试"
                    return
                }
            val prepared = wallpaperStore.prepareFromBytes(bytes) ?: run {
                if (notifyUser) _message.value = "今日 Bing 壁纸自动更新失败，下次打开将自动重试"
                return
            }

            val target = prefs.bingAutoUpdateTarget
            val oldPath = when (target) {
                BingWallpaperTarget.GLOBAL_LIGHT -> prefs.wallpaperPath
                BingWallpaperTarget.GLOBAL_DARK -> prefs.wallpaperPathDark
                BingWallpaperTarget.TIMETABLE_LIGHT -> prefs.timetableWallpaperPath
                BingWallpaperTarget.TIMETABLE_DARK -> prefs.timetableWallpaperPathDark
            }
            val fileSlot = target.slot + if (target.dark) "_dark" else ""
            val relative = wallpaperStore.commit(prepared.tempPath, fileSlot, null) ?: run {
                if (notifyUser) _message.value = "今日 Bing 壁纸自动更新失败，下次打开将自动重试"
                return
            }
            val scrim = WallpaperTone.recommendedScrim(prepared.averageLuminance, target.dark)
            persistPreferences { current ->
                val updated = withPath(current, target.slot, target.dark, relative)
                    .copy(bingLastAutoUpdateDate = todayStr)
                if (target.slot == WallpaperStore.SLOT_GLOBAL) {
                    updated.copy(wallpaperScrim = scrim.coerceIn(0f, 0.8f))
                } else {
                    updated
                }
            }
            if (oldPath.isNotBlank() && oldPath != relative) {
                wallpaperStore.delete(oldPath)
            }
            if (notifyUser) {
                _message.value = "已自动更新今日 Bing 壁纸（${target.label}）"
            }
        } finally {
            _bingLoading.value = false
            autoUpdateGuard.set(false)
        }
    }

    /** 收藏当前或指定的 #RRGGBB 主题强调色（去重，最多保留 8 个）。 */
    fun saveAccentPreset(hex: String) {
        val clean = hex.trim().uppercase().let { if (it.startsWith("#")) it else "#$it" }
        if (clean.length != 7 || !clean.drop(1).all { it.isDigit() || it in 'A'..'F' }) return
        updatePreferences { prefs ->
            prefs.copy(savedAccentPresets = (prefs.savedAccentPresets + clean).distinct().take(8)).sanitized()
        }
    }

    /** 移除已收藏的主题强调色预设。 */
    fun removeAccentPreset(hex: String) {
        val target = hex.trim().uppercase()
        updatePreferences { prefs ->
            prefs.copy(savedAccentPresets = prefs.savedAccentPresets.filterNot { it.equals(target, ignoreCase = true) })
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun updatePreferences(transform: (AppPreferences) -> AppPreferences) {
        viewModelScope.launch {
            persistPreferences(transform)
        }
    }

    private suspend fun persistPreferences(transform: (AppPreferences) -> AppPreferences) {
        appSettings.transformAppPreferences(transform)
    }

    private fun currentPath(slot: String, dark: Boolean): String {
        val current = preferences.value
        return when {
            slot == WallpaperStore.SLOT_GLOBAL && dark -> current.wallpaperPathDark
            slot == WallpaperStore.SLOT_GLOBAL -> current.wallpaperPath
            slot == WallpaperStore.SLOT_TIMETABLE && dark -> current.timetableWallpaperPathDark
            slot == WallpaperStore.SLOT_TIMETABLE -> current.timetableWallpaperPath
            slot == WallpaperStore.SLOT_PERIOD_MORNING -> current.periodMorningPath
            slot == WallpaperStore.SLOT_PERIOD_AFTERNOON -> current.periodAfternoonPath
            slot == WallpaperStore.SLOT_PERIOD_EVENING -> current.periodEveningPath
            slot == WallpaperStore.SLOT_PERIOD_NIGHT -> current.periodNightPath
            else -> ""
        }
    }

    private fun withPath(current: AppPreferences, slot: String, dark: Boolean, relativePath: String): AppPreferences =
        when {
            slot == WallpaperStore.SLOT_GLOBAL && dark -> current.copy(wallpaperPathDark = relativePath)
            slot == WallpaperStore.SLOT_GLOBAL -> current.copy(wallpaperPath = relativePath)
            slot == WallpaperStore.SLOT_TIMETABLE && dark -> current.copy(timetableWallpaperPathDark = relativePath)
            slot == WallpaperStore.SLOT_TIMETABLE -> current.copy(timetableWallpaperPath = relativePath)
            slot == WallpaperStore.SLOT_PERIOD_MORNING -> current.copy(periodMorningPath = relativePath)
            slot == WallpaperStore.SLOT_PERIOD_AFTERNOON -> current.copy(periodAfternoonPath = relativePath)
            slot == WallpaperStore.SLOT_PERIOD_EVENING -> current.copy(periodEveningPath = relativePath)
            slot == WallpaperStore.SLOT_PERIOD_NIGHT -> current.copy(periodNightPath = relativePath)
            else -> current
        }

    /** 待裁剪图片：目标槽位/亮暗 + 未裁剪的临时文件元数据。 */
    data class PendingWallpaper(
        val slot: String,
        val dark: Boolean,
        val image: PreparedWallpaper,
    )
}
