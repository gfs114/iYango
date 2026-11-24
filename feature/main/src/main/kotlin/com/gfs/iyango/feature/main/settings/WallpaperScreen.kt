@file:OptIn(ExperimentalMaterial3Api::class)

package com.gfs.iyango.feature.main.settings

import com.gfs.iyango.core.designsystem.component.GlassTopBarTextButton
import com.gfs.iyango.core.designsystem.liquidglass.GlassBackdropHost
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassTopBarIconButton
import com.gfs.iyango.core.designsystem.component.SectionCard
import com.gfs.iyango.core.designsystem.component.SettingSliderRow
import com.gfs.iyango.core.designsystem.component.SettingSwitchRow
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidFilterChip
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedTextField
import com.gfs.iyango.core.designsystem.liquidglass.LiquidSlider
import com.gfs.iyango.core.designsystem.liquidglass.LiquidToggle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gfs.iyango.core.domain.BingWallpaperInfo
import com.gfs.iyango.core.domain.CropGeometry
import com.gfs.iyango.core.domain.CropPixelRegion
import com.gfs.iyango.core.domain.CropRect
import com.gfs.iyango.core.domain.WallpaperScheduler
import com.gfs.iyango.core.domain.WallpaperStore
import com.gfs.iyango.core.model.AppPreferences
import com.gfs.iyango.core.model.BingWallpaperTarget
import com.gfs.iyango.core.model.DayPeriod
import com.gfs.iyango.core.model.WallpaperSwitchMode
import com.gfs.iyango.feature.main.isDarkTheme
import com.gfs.iyango.feature.main.isWifiActive
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 壁纸与背景页（F10）：系统图片选择 → 裁剪页（比例锁定为屏幕、拖动/把手缩放）→
 * 保存/取消。全局壁纸与课表页独立背景各分亮色/暗色两个槽位，另有可读性遮罩与
 * 背景模糊滑杆。
 */
@Composable
fun WallpaperScreen(
    onBack: () -> Unit,
    onApplyAccentHex: (String) -> Unit = {},
    viewModel: WallpaperViewModel = hiltViewModel(),
) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val preferencesReady by viewModel.preferencesReady.collectAsStateWithLifecycle()
    val globalPath by viewModel.globalWallpaperPath.collectAsStateWithLifecycle()
    val globalPathDark by viewModel.globalWallpaperPathDark.collectAsStateWithLifecycle()
    val timetablePath by viewModel.timetableWallpaperPath.collectAsStateWithLifecycle()
    val timetablePathDark by viewModel.timetableWallpaperPathDark.collectAsStateWithLifecycle()
    val periodMorningPath by viewModel.periodMorningPath.collectAsStateWithLifecycle()
    val periodAfternoonPath by viewModel.periodAfternoonPath.collectAsStateWithLifecycle()
    val periodEveningPath by viewModel.periodEveningPath.collectAsStateWithLifecycle()
    val periodNightPath by viewModel.periodNightPath.collectAsStateWithLifecycle()
    val carouselItems by viewModel.carouselResolvedPaths.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val bingInfo by viewModel.bingInfo.collectAsStateWithLifecycle()
    val bingLoading by viewModel.bingLoading.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val isDarkNow = isDarkTheme()

    LaunchedEffect(preferencesReady) {
        if (preferencesReady &&
            preferences.bingAutoUpdateEnabled &&
            preferences.bingLastAutoUpdateDate != LocalDate.now().toString()
        ) {
            viewModel.checkAutoUpdateBing(
                isWifiConnected = isWifiActive(context),
                darkTheme = isDarkNow,
                notifyUser = false,
            )
        }
    }

    var pickTarget by remember { mutableStateOf(PickTarget(WallpaperStore.SLOT_GLOBAL, dark = false)) }
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) viewModel.prepare(uri.toString(), pickTarget.slot, pickTarget.dark)
    }
    val launchPicker: (String, Boolean) -> Unit = { slot, dark ->
        pickTarget = PickTarget(slot, dark)
        picker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    GlassScaffold(
        topBar = {
            GlassTopBar(
                title = "壁纸与背景",
                navigationIcon = {
                    GlassTopBarIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "slot_global") {
                WallpaperSlotCard(
                    title = "全局壁纸",
                    slot = WallpaperStore.SLOT_GLOBAL,
                    lightPath = globalPath,
                    darkPath = globalPathDark,
                    busy = busy,
                    onPick = launchPicker,
                    onRemove = viewModel::remove,
                )
            }
            item(key = "slot_timetable") {
                WallpaperSlotCard(
                    title = "课表页背景",
                    slot = WallpaperStore.SLOT_TIMETABLE,
                    lightPath = timetablePath,
                    darkPath = timetablePathDark,
                    busy = busy,
                    onPick = launchPicker,
                    onRemove = viewModel::remove,
                )
            }
            item(key = "auto_switch") {
                WallpaperAutoSwitchCard(
                    preferences = preferences,
                    periodPaths = mapOf(
                        DayPeriod.MORNING to periodMorningPath,
                        DayPeriod.AFTERNOON to periodAfternoonPath,
                        DayPeriod.EVENING to periodEveningPath,
                        DayPeriod.NIGHT to periodNightPath,
                    ),
                    carouselItems = carouselItems,
                    busy = busy,
                    onModeChange = viewModel::setWallpaperSwitchMode,
                    onCitySelect = viewModel::setSunriseCity,
                    onCustomTimesChange = viewModel::setSunriseCustomTimes,
                    onSyncThemeChange = viewModel::setSunriseSyncTheme,
                    onPickPeriod = { period -> launchPicker(period.slotKey, false) },
                    onRemovePeriod = viewModel::removePeriodWallpaper,
                    onAddCarousel = { launchPicker(WallpaperStore.SLOT_CAROUSEL_PREFIX, false) },
                    onRemoveCarousel = viewModel::removeCarouselWallpaper,
                    onNextCarousel = viewModel::advanceCarousel,
                )
            }
            item(key = "dynamic_color") {
                WallpaperDynamicColorCard(
                    enabled = preferences.wallpaperDynamicColor,
                    onToggle = viewModel::setWallpaperDynamicColor,
                    onExtractNow = {
                        scope.launch {
                            val rel = WallpaperScheduler.resolveGlobalWallpaperRelativePath(
                                prefs = preferences,
                                isDarkTheme = isDarkNow,
                            )
                            val hex = if (rel.isBlank()) null else viewModel.extractAccentFromPath(rel)
                            if (hex != null) {
                                onApplyAccentHex(hex)
                                snackbarHostState.showSnackbar("已从当前壁纸提取并应用主题色 $hex")
                            } else {
                                snackbarHostState.showSnackbar("当前全局壁纸未设置或未检测到足够色彩")
                            }
                        }
                    },
                )
            }
            item(key = "bing_daily") {
                BingDailyWallpaperCard(
                    info = bingInfo,
                    loading = bingLoading,
                    busy = busy,
                    autoEnabled = preferences.bingAutoUpdateEnabled,
                    wifiOnly = preferences.bingAutoUpdateWifiOnly,
                    target = preferences.bingAutoUpdateTarget,
                    lastAutoUpdateDate = preferences.bingLastAutoUpdateDate,
                    onFetch = viewModel::fetchBingToday,
                    onApply = viewModel::applyBingToSlot,
                    onAutoToggle = { enabled ->
                        viewModel.setBingAutoUpdate(enabled, isWifiActive(context), isDarkNow)
                    },
                    onWifiOnlyToggle = { wifiOnly ->
                        viewModel.setBingAutoUpdateWifiOnly(wifiOnly, isWifiActive(context), isDarkNow)
                    },
                    onTargetSelect = viewModel::setBingAutoUpdateTarget,
                )
            }
            item(key = "scrim") {
                ScrimSection(
                    value = preferences.wallpaperScrim,
                    onChangeFinished = viewModel::setScrim,
                )
            }
            item(key = "blur") {
                BlurSection(
                    value = preferences.wallpaperBlur,
                    onChangeFinished = viewModel::setBlur,
                )
            }
        }
    }

    pending?.let { target ->
        val darkTheme = isDarkTheme()
        WallpaperCropDialog(
            pending = target,
            busy = busy,
            onConfirm = { region -> viewModel.confirmCrop(region, darkTheme) },
            onDismiss = viewModel::cancelPending,
        )
    }
}

/** 一次选图的目标：壁纸槽位 + 亮/暗模式。 */
private data class PickTarget(val slot: String, val dark: Boolean)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WallpaperAutoSwitchCard(
    preferences: AppPreferences,
    periodPaths: Map<DayPeriod, String?>,
    carouselItems: List<Pair<String, String>>,
    busy: Boolean,
    onModeChange: (WallpaperSwitchMode) -> Unit,
    onCitySelect: (String) -> Unit,
    onCustomTimesChange: (Int, Int) -> Unit,
    onSyncThemeChange: (Boolean) -> Unit,
    onPickPeriod: (DayPeriod) -> Unit,
    onRemovePeriod: (DayPeriod) -> Unit,
    onAddCarousel: () -> Unit,
    onRemoveCarousel: (String) -> Unit,
    onNextCarousel: () -> Unit,
) {
    val modeLabels = listOf(
        WallpaperSwitchMode.FOLLOW_THEME to "跟随主题",
        WallpaperSwitchMode.SUNRISE_SUNSET to "日出日落",
        WallpaperSwitchMode.FOUR_PERIODS to "四时段",
        WallpaperSwitchMode.CAROUSEL_DAILY to "每日轮播",
        WallpaperSwitchMode.CAROUSEL_LAUNCH to "启动轮播",
    )
    SectionCard(title = "自动切换与轮播") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                modeLabels.forEach { (mode, label) ->
                    LiquidFilterChip(
                        selected = preferences.wallpaperSwitchMode == mode,
                        onClick = { onModeChange(mode) },
                        label = { Text(label) },
                    )
                }
            }

            when (preferences.wallpaperSwitchMode) {
                WallpaperSwitchMode.FOLLOW_THEME -> Unit
                WallpaperSwitchMode.SUNRISE_SUNSET -> {
                    val (sr, ss) = remember(
                        preferences.sunriseCityId,
                        preferences.sunriseCustomMinutes,
                        preferences.sunsetCustomMinutes,
                    ) {
                        WallpaperScheduler.sunTimesFor(preferences, LocalDate.now())
                    }
                    val fmt = remember { DateTimeFormatter.ofPattern("HH:mm") }
                    Text(
                        text = "选择城市（离线推算今日日出 ${sr.format(fmt)} · 日落 ${ss.format(fmt)}）",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        WallpaperScheduler.SolarCities.forEach { city ->
                            LiquidFilterChip(
                                selected = preferences.sunriseCityId == city.id,
                                onClick = { onCitySelect(city.id) },
                                label = { Text(city.name) },
                            )
                        }
                        LiquidFilterChip(
                            selected = preferences.sunriseCityId == WallpaperScheduler.CUSTOM_CITY_ID,
                            onClick = { onCitySelect(WallpaperScheduler.CUSTOM_CITY_ID) },
                            label = { Text("自定义时间") },
                        )
                    }
                    if (preferences.sunriseCityId == WallpaperScheduler.CUSTOM_CITY_ID) {
                        var srText by remember(preferences.sunriseCustomMinutes) {
                            mutableStateOf(formatMinutesHm(preferences.sunriseCustomMinutes))
                        }
                        var ssText by remember(preferences.sunsetCustomMinutes) {
                            mutableStateOf(formatMinutesHm(preferences.sunsetCustomMinutes))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            LiquidOutlinedTextField(
                                value = srText,
                                onValueChange = { raw ->
                                    srText = raw
                                    val parsedSr = parseMinutesHm(raw)
                                    val parsedSs = parseMinutesHm(ssText) ?: preferences.sunsetCustomMinutes
                                    if (parsedSr != null) onCustomTimesChange(parsedSr, parsedSs)
                                },
                                label = { Text("日出 (HH:mm)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            LiquidOutlinedTextField(
                                value = ssText,
                                onValueChange = { raw ->
                                    ssText = raw
                                    val parsedSr = parseMinutesHm(srText) ?: preferences.sunriseCustomMinutes
                                    val parsedSs = parseMinutesHm(raw)
                                    if (parsedSs != null) onCustomTimesChange(parsedSr, parsedSs)
                                },
                                label = { Text("日落 (HH:mm)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    SettingSwitchRow(
                        title = "联动切换浅色/深色主题",
                        checked = preferences.sunriseSyncTheme,
                        onCheckedChange = onSyncThemeChange,
                    )
                }
                WallpaperSwitchMode.FOUR_PERIODS -> {
                    DayPeriod.entries.forEach { period ->
                        val path = periodPaths[period]
                        val thumb by rememberWallpaperBitmap(path, maxDimension = 300)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 52.dp, height = 70.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (thumb != null) {
                                    Image(
                                        bitmap = thumb!!,
                                        contentDescription = period.label,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    Text(
                                        text = "未设",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(period.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(
                                    text = "${period.hoursLabel} · ${if (path != null) "已设置" else "跟随默认"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            LiquidOutlinedButton(enabled = !busy, onClick = { onPickPeriod(period) }) {
                                Text(if (path != null) "更换" else "选择")
                            }
                            if (path != null) {
                                TextButton(enabled = !busy, onClick = { onRemovePeriod(period) }) {
                                    Text("移除")
                                }
                            }
                        }
                    }
                }
                WallpaperSwitchMode.CAROUSEL_DAILY,
                WallpaperSwitchMode.CAROUSEL_LAUNCH,
                -> {
                    Text(
                        text = "已添加 ${carouselItems.size} / 12 张轮播壁纸",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (carouselItems.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(carouselItems, key = { it.first }) { (rel, abs) ->
                                val thumb by rememberWallpaperBitmap(abs, maxDimension = 360)
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(width = 76.dp, height = 108.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (thumb != null) {
                                            Image(
                                                bitmap = thumb!!,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(4.dp)
                                                .size(22.dp)
                                                .clip(CircleShape)
                                                .background(Color.Black.copy(alpha = 0.55f)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            TextButton(
                                                onClick = { onRemoveCarousel(rel) },
                                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                            ) {
                                                Icon(
                                                    Icons.Filled.Close,
                                                    contentDescription = "删除轮播图",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(14.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LiquidOutlinedButton(
                            enabled = !busy && carouselItems.size < 12,
                            onClick = onAddCarousel,
                        ) { Text("添加轮播图") }
                        if (preferences.wallpaperSwitchMode == WallpaperSwitchMode.CAROUSEL_LAUNCH && carouselItems.size > 1) {
                            LiquidOutlinedButton(onClick = onNextCarousel) {
                                Text("切换下一张")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WallpaperDynamicColorCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onExtractNow: () -> Unit,
) {
    SectionCard(title = "壁纸智能取色") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingSwitchRow(
                title = "跟随壁纸自动更新主题色",
                checked = enabled,
                onCheckedChange = onToggle,
            )
            LiquidOutlinedButton(
                onClick = onExtractNow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("立即从当前壁纸取色")
            }
        }
    }
}

private fun formatMinutesHm(minutes: Int): String {
    val clamped = minutes.coerceIn(0, 1439)
    return "%02d:%02d".format(clamped / 60, clamped % 60)
}

private fun parseMinutesHm(text: String): Int? {
    val parts = text.trim().split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BingDailyWallpaperCard(
    info: BingWallpaperInfo?,
    loading: Boolean,
    busy: Boolean,
    autoEnabled: Boolean,
    wifiOnly: Boolean,
    target: BingWallpaperTarget,
    lastAutoUpdateDate: String,
    onFetch: () -> Unit,
    onApply: (String, Boolean) -> Unit,
    onAutoToggle: (Boolean) -> Unit,
    onWifiOnlyToggle: (Boolean) -> Unit,
    onTargetSelect: (BingWallpaperTarget) -> Unit,
) {
    GlassSurface(
        shape = RoundedCornerShape(16.dp),
        material = GlassMaterial.CONTENT,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Bing 每日一图", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(enabled = !loading && !busy, onClick = onFetch) {
                    Text(
                        when {
                            loading -> "获取中..."
                            info != null -> "刷新今日壁纸"
                            else -> "获取今日壁纸"
                        },
                    )
                }
            }
            if (info != null) {
                if (info.title.isNotBlank()) {
                    Text(info.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
                Text(
                    text = buildString {
                        if (info.date.isNotBlank()) append("${info.date} · ")
                        append(info.copyright)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LiquidOutlinedButton(
                    enabled = !loading && !busy,
                    onClick = { onApply(WallpaperStore.SLOT_GLOBAL, false) },
                ) { Text("设为全局亮色") }
                LiquidOutlinedButton(
                    enabled = !loading && !busy,
                    onClick = { onApply(WallpaperStore.SLOT_GLOBAL, true) },
                ) { Text("设为全局暗色") }
                LiquidOutlinedButton(
                    enabled = !loading && !busy,
                    onClick = { onApply(WallpaperStore.SLOT_TIMETABLE, false) },
                ) { Text("设为课表亮色") }
                LiquidOutlinedButton(
                    enabled = !loading && !busy,
                    onClick = { onApply(WallpaperStore.SLOT_TIMETABLE, true) },
                ) { Text("设为课表暗色") }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SettingSwitchRow(
                title = "每日首次打开自动更新",
                checked = autoEnabled,
                onCheckedChange = onAutoToggle,
            )
            if (autoEnabled) {
                SettingSwitchRow(
                    title = "仅 Wi‑Fi 自动更新",
                    checked = wifiOnly,
                    onCheckedChange = onWifiOnlyToggle,
                )
                Text("自动更新目标槽位", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BingWallpaperTarget.entries.forEach { slot ->
                        LiquidFilterChip(
                            selected = target == slot,
                            onClick = { onTargetSelect(slot) },
                            label = { Text(slot.label) },
                        )
                    }
                }
                Text(
                    text = "上次自动更新：${lastAutoUpdateDate.ifBlank { "暂无记录" }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WallpaperSlotCard(
    title: String,
    slot: String,
    lightPath: String?,
    darkPath: String?,
    busy: Boolean,
    onPick: (String, Boolean) -> Unit,
    onRemove: (String, Boolean) -> Unit,
) {
    SectionCard(title = title) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            WallpaperRow(
                label = "亮色",
                path = lightPath,
                busy = busy,
                onPick = { onPick(slot, false) },
                onRemove = { onRemove(slot, false) },
            )
            WallpaperRow(
                label = "暗色",
                path = darkPath,
                busy = busy,
                onPick = { onPick(slot, true) },
                onRemove = { onRemove(slot, true) },
            )
        }
    }
}

@Composable
private fun WallpaperRow(
    label: String,
    path: String?,
    busy: Boolean,
    onPick: () -> Unit,
    onRemove: () -> Unit,
) {
    val bitmap by rememberWallpaperBitmap(path, maxDimension = 900)
    val hasImage = path != null
    val decodeFailed = hasImage && bitmap == null

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            when {
                decodeFailed -> Text(
                    text = "图片无法读取，请重新选择",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                bitmap != null -> Image(
                    bitmap = bitmap!!,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )

                else -> Text(
                    text = "未设置",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiquidOutlinedButton(onClick = onPick, enabled = !busy) {
                Text(if (hasImage) "更换图片" else "选择图片")
            }
            if (hasImage) {
                TextButton(onClick = onRemove, enabled = !busy) { Text("移除") }
            }
            Spacer(Modifier.weight(1f))
            if (busy) {
                Text(
                    text = "处理中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ScrimSection(
    value: Float,
    onChangeFinished: (Float) -> Unit,
) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    SectionCard(title = "可读性遮罩") {
        SettingSliderRow(
            title = "遮罩强度",
            value = draft,
            range = 0f..0.8f,
            valueLabel = "${(draft * 100).toInt()}%",
            onValueChange = { draft = it },
            onValueChangeFinished = { onChangeFinished(draft) },
        )
    }
}

@Composable
private fun BlurSection(
    value: Float,
    onChangeFinished: (Float) -> Unit,
) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    SectionCard(title = "背景模糊") {
        SettingSliderRow(
            title = "模糊强度",
            value = draft,
            range = 0f..1f,
            valueLabel = if (draft <= 0f) "关闭" else "${(draft * 100).toInt()}%",
            onValueChange = { draft = it },
            onValueChangeFinished = { onChangeFinished(draft) },
        )
    }
}

/**
 * 全屏裁剪页：图片 Contain 展示，裁剪框比例锁定为屏幕（壁纸实际铺满的面），
 * 框体拖动 + 右下角把手缩放；所有边界约束经 [CropGeometry]（与鸿蒙参考实现同构）。
 */
@Composable
private fun WallpaperCropDialog(
    pending: WallpaperViewModel.PendingWallpaper,
    busy: Boolean,
    onConfirm: (CropPixelRegion?) -> Unit,
    onDismiss: () -> Unit,
) {
    val image by rememberWallpaperBitmap(pending.image.tempPath, maxDimension = 1600)
    // 裁剪框比例跟随壁纸实际覆盖的表面（应用窗口），而不是本页剩余区域。
    val windowSize = LocalWindowInfo.current.containerSize
    val configuration = LocalConfiguration.current
    val aspect = when {
        windowSize.width > 0 && windowSize.height > 0 ->
            windowSize.width.toFloat() / windowSize.height.toFloat()
        configuration.screenHeightDp > 0 ->
            configuration.screenWidthDp.toFloat() / configuration.screenHeightDp.toFloat()
        else -> 9f / 16f
    }

    var displayRect by remember { mutableStateOf<CropRect?>(null) }
    var cropRect by remember { mutableStateOf<CropRect?>(null) }
    var gestureStart by remember { mutableStateOf<CropRect?>(null) }

    fun relayout(size: IntSize) {
        val display = CropGeometry.fitDisplay(
            size.width.toFloat(), size.height.toFloat(),
            pending.image.width, pending.image.height,
        )
        val previous = displayRect
        displayRect = display
        cropRect = when {
            display == null -> null
            previous == null -> CropGeometry.initialCrop(display, aspect)
            else -> CropGeometry.recenterCrop(cropRect, previous, aspect, display, aspect)
        } ?: display?.let { CropGeometry.initialCrop(it, aspect) }
    }

    fun canConfirm(): Boolean {
        val display = displayRect ?: return false
        val crop = cropRect ?: return false
        return CropGeometry.pixelRegion(crop, display, pending.image.width, pending.image.height) != null
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        GlassBackdropHost(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    GlassTopBar(
                        title = "调整取景",
                        navigationIcon = {
                            GlassTopBarTextButton(text = "取消", onClick = onDismiss, enabled = !busy)
                        },
                        actions = {
                            GlassTopBarTextButton(
                                text = "使用",
                                onClick = {
                                    val display = displayRect
                                    val crop = cropRect
                                    if (display != null && crop != null) {
                                        onConfirm(
                                            CropGeometry.pixelRegion(
                                                crop, display, pending.image.width, pending.image.height,
                                            ),
                                        )
                                    }
                                },
                                enabled = !busy && canConfirm(),
                                loading = busy,
                                modifier = Modifier.semantics { contentDescription = "使用裁剪结果" },
                            )
                        },
                    )
                },
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF101014))
                            .onSizeChanged { relayout(it) }
                            .pointerInput(displayRect, aspect) {
                                var total = Offset.Zero
                                detectDragGestures(
                                    onDragStart = {
                                        gestureStart = cropRect
                                        total = Offset.Zero
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        total += dragAmount
                                        val start = gestureStart ?: return@detectDragGestures
                                        val display = displayRect ?: return@detectDragGestures
                                        cropRect = CropGeometry.clampCrop(
                                            start.copy(x = start.x + total.x, y = start.y + total.y),
                                            display,
                                            aspect,
                                        )
                                    },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        image?.let { bitmap ->
                            Image(
                                bitmap = bitmap,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        val crop = cropRect
                        val display = displayRect
                        if (crop != null && display != null) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val dim = Color.Black.copy(alpha = 0.45f)
                                // 四块遮罩盖住选框外区域。
                                drawRect(
                                    dim,
                                    topLeft = Offset(0f, 0f),
                                    size = Size(size.width, crop.y.coerceAtLeast(0f)),
                                )
                                drawRect(
                                    dim,
                                    topLeft = Offset(0f, crop.y + crop.height),
                                    size = Size(size.width, (size.height - crop.y - crop.height).coerceAtLeast(0f)),
                                )
                                drawRect(
                                    dim,
                                    topLeft = Offset(0f, crop.y),
                                    size = Size(crop.x.coerceAtLeast(0f), crop.height),
                                )
                                drawRect(
                                    dim,
                                    topLeft = Offset(crop.x + crop.width, crop.y),
                                    size = Size((size.width - crop.x - crop.width).coerceAtLeast(0f), crop.height),
                                )
                                drawRect(
                                    color = Color.White,
                                    topLeft = Offset(crop.x, crop.y),
                                    size = Size(crop.width, crop.height),
                                    style = Stroke(width = 1.5f),
                                )
                            }
                            // 右下角缩放把手：拖动只改宽度，高度按锁定比例推导。
                            val handleSize = 28.dp
                            Box(
                                modifier = Modifier
                                    .offset {
                                        IntOffset(
                                            (crop.x + crop.width - handleSize.toPx() / 2f).roundToInt(),
                                            (crop.y + crop.height - handleSize.toPx() / 2f).roundToInt(),
                                        )
                                    }
                                    .size(handleSize)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.9f))
                                    .pointerInput(displayRect, aspect) {
                                        var totalX = 0f
                                        detectDragGestures(
                                            onDragStart = {
                                                gestureStart = cropRect
                                                totalX = 0f
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                totalX += dragAmount.x
                                                val start = gestureStart ?: return@detectDragGestures
                                                val currentDisplay = displayRect ?: return@detectDragGestures
                                                cropRect = CropGeometry.clampCrop(
                                                    start.copy(width = start.width + totalX),
                                                    currentDisplay,
                                                    aspect,
                                                )
                                            },
                                        )
                                    },
                            )
                        } else {
                            Text(
                                text = "正在读取图片…",
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Text(
                        text = "拖动选框调整位置，拖右下角圆点缩放；选框比例与屏幕一致。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
