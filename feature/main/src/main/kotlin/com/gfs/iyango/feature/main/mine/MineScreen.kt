package com.gfs.iyango.feature.main.mine

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.component.DissolveBurst
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.SectionHeader
import com.gfs.iyango.core.designsystem.component.SettingSwitchRow
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidAlertDialog
import com.gfs.iyango.core.designsystem.liquidglass.LiquidFilterChip
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedTextField
import com.gfs.iyango.core.designsystem.liquidglass.LiquidToggle
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled
import com.gfs.iyango.core.designsystem.theme.AccentPreset
import com.gfs.iyango.feature.main.PredictiveBackSettingsShortcut
import com.gfs.iyango.feature.main.SystemPredictiveBackStatus
import com.gfs.iyango.feature.main.openPredictiveBackSettingsShortcut
import com.gfs.iyango.feature.main.openSystemPredictiveBackSettings
import com.gfs.iyango.feature.main.querySystemPredictiveBackStatus
import com.gfs.iyango.feature.main.rememberSystemPredictiveBackStatus
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.designsystem.theme.LocalBrand
import com.gfs.iyango.core.domain.ThemeMode
import com.gfs.iyango.core.model.AppPreferences
import com.gfs.iyango.core.model.NavigationLayout
import com.gfs.iyango.core.model.SchoolConnection
import com.gfs.iyango.core.model.SchoolConnectionStatus

/** 外观偏好（经 MainActivity 的 ThemeViewModel 与 DataStore 持久化互通）。 */
data class AppearanceState(
    val accentHex: String? = null,
    val tintBackground: Boolean = false,
    /** 深色模式策略（跟随系统/浅色/深色）。 */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 液态玻璃底栏蒙层不透明度（亮色模式），0..0.8。默认 0 = 无蒙层。 */
    val dockSurfaceAlphaLight: Float = 0f,
    /** 液态玻璃底栏蒙层不透明度（暗色模式），0..0.8。默认 0 = 无蒙层。 */
    val dockSurfaceAlphaDark: Float = 0f,
)

/** 账号、两个常用入口和功能分类；具体设置在二级页面操作。 */
@Composable
fun MineScreen(
    bottomPadding: Dp = 96.dp,
    connection: SchoolConnection? = null,
    onNavigate: (MineDestination) -> Unit = {},
    onOpenCategory: (MineCategory) -> Unit = {},
) {
    GlassScaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { GlassTopBar(title = "我的") },
        containerColor = Color.Transparent,
        // Root Dock padding already includes the bottom system inset; the header owns top.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            HeaderCard(connection = connection, onClick = { onNavigate(MineDestination.SCHOOL) })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MineShortcut(MineDestination.COURSES, Modifier.weight(1f)) { onNavigate(MineDestination.COURSES) }
                MineShortcut(MineDestination.CLASSROOM, Modifier.weight(1f)) { onNavigate(MineDestination.CLASSROOM) }
            }
            SectionHeader(title = "功能与设置")
            val menuRows = buildList {
                MineCategory.entries.forEach { category ->
                    add(Triple(category.icon, category.title) { onOpenCategory(category) })
                }
                listOf(
                    MineDestination.AI_ASSISTANT,
                    MineDestination.REMINDER,
                    MineDestination.ABOUT,
                ).forEach { destination ->
                    add(Triple(destination.icon, destination.title) { onNavigate(destination) })
                }
            }
            MineGroupedCard {
                menuRows.forEachIndexed { index, (icon, title, action) ->
                    MineGroupedRow(
                        icon = icon,
                        title = title,
                        showDivider = index < menuRows.lastIndex,
                        onClick = action,
                    )
                }
            }
            // 尾部留出玻璃 Dock 高度：滚动时内容可穿过 Dock 下方被折射。
            Spacer(Modifier.height(bottomPadding))
        }
    }
}

/** 原有主题、启动和布局设置的二级页面；状态继续由既有持久层提供。 */
@Composable
fun MineAppearanceScreen(
    onBack: () -> Unit,
    onNavigate: (MineDestination) -> Unit = {},
    appearance: AppearanceState = AppearanceState(),
    onAppearanceChange: (AppearanceState) -> Unit = {},
    appPreferences: AppPreferences = AppPreferences(),
    onDefaultStartTabChange: (Int) -> Unit = {},
    onNavigationLayoutChange: (NavigationLayout) -> Unit = {},
    onAnimationsEnabledChange: (Boolean) -> Unit = {},
    onPredictiveBackEnabledChange: (Boolean) -> Unit = {},
    savedAccentPresets: List<String> = appPreferences.savedAccentPresets,
    onSaveAccentPreset: (String) -> Unit = {},
    onRemoveAccentPreset: (String) -> Unit = {},
    systemPredictiveBackChecker: (() -> SystemPredictiveBackStatus)? = null,
) {
    MineSubpage(title = MineCategory.APPEARANCE.title, onBack = onBack) {
        SectionHeader(title = "显示与背景")
        MineGroupedCard {
            MineCategory.APPEARANCE.destinations.forEachIndexed { index, destination ->
                MineGroupedRow(
                    icon = destination.icon,
                    title = destination.title,
                    showDivider = index < MineCategory.APPEARANCE.destinations.lastIndex,
                    onClick = { onNavigate(destination) },
                )
            }
        }
        SectionHeader(title = "主题与颜色")
        GlassSurface(
            material = GlassMaterial.CONTENT,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(Modifier.padding(16.dp)) {
                AppearanceSection(
                    state = appearance,
                    onChange = onAppearanceChange,
                    savedAccentPresets = savedAccentPresets,
                    onSaveAccentPreset = onSaveAccentPreset,
                    onRemoveAccentPreset = onRemoveAccentPreset,
                )
            }
        }
        SectionHeader(title = "布局与启动")
        GlassSurface(
            material = GlassMaterial.CONTENT,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(Modifier.padding(16.dp)) {
                LayoutSection(
                    preferences = appPreferences,
                    onDefaultStartTabChange = onDefaultStartTabChange,
                    onNavigationLayoutChange = onNavigationLayoutChange,
                    onAnimationsEnabledChange = onAnimationsEnabledChange,
                    onPredictiveBackEnabledChange = onPredictiveBackEnabledChange,
                    systemPredictiveBackChecker = systemPredictiveBackChecker,
                )
            }
        }
    }
}

/** 账号入口：未登录显示登录操作，已绑定显示真实资料，不使用占位姓名或头像。 */
@Composable
private fun HeaderCard(connection: SchoolConnection?, onClick: () -> Unit) {
    val status = connection?.status ?: SchoolConnectionStatus.LOGGED_OUT
    val studentNo = connection?.profile?.studentNo?.trim()?.takeIf { it.isNotEmpty() }
        ?: connection?.accountSubject?.takeIf { it.isNotBlank() }
    val displayName = if (status == SchoolConnectionStatus.LOGGED_OUT) {
        "登录教务账号"
    } else {
        connection?.profile?.displayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: studentNo
            ?: "教务账号"
    }
    val detailText = when (status) {
        SchoolConnectionStatus.LOGGED_OUT -> "登录后查看课表与成绩"
        SchoolConnectionStatus.ACTIVE ->
            listOfNotNull(
                connection?.profile?.className?.takeIf { it.isNotBlank() },
                studentNo,
            ).joinToString("\n").ifEmpty { "已连接教务账号" }
        SchoolConnectionStatus.EXPIRED -> "会话已失效 点击重新登录"
        SchoolConnectionStatus.FAILED -> "连接暂不可用 点击查看"
        SchoolConnectionStatus.VERIFYING -> "正在验证登录状态"
    }
    GlassSurface(
        material = GlassMaterial.CONTENT,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    detailText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 菜单行。 */
@Composable
internal fun MineMenuRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    GlassSurface(
        material = GlassMaterial.CONTENT,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = LocalBrand.current.accent,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 外观区（§9）：6 预设横排 + 自定义 hex + 染背景开关。 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AppearanceSection(
    state: AppearanceState,
    onChange: (AppearanceState) -> Unit,
    savedAccentPresets: List<String> = emptyList(),
    onSaveAccentPreset: (String) -> Unit = {},
    onRemoveAccentPreset: (String) -> Unit = {},
) {
    val brand = LocalBrand.current
    var customHex by rememberSaveable(state.accentHex) {
        mutableStateOf(state.accentHex?.removePrefix("#") ?: "")
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AccentPreset.entries.forEach { preset ->
                val selected = state.accentHex == preset.light || state.accentHex == preset.dark ||
                    (state.accentHex == null && preset == AccentPreset.CLASSIC)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(parse(preset.light))
                            .border(
                                width = if (selected) 2.dp else 0.dp,
                                color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                shape = CircleShape,
                            )
                            .selectable(selected = selected, role = Role.RadioButton) { onChange(state.copy(accentHex = preset.light)) }
                            .semantics { contentDescription = "${preset.label}主题色" },
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        preset.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) brand.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // 深色模式三选：跟随系统 / 浅色 / 深色。
        Text("深色模式", style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                OptionPill(mode.label, state.themeMode == mode) { onChange(state.copy(themeMode = mode)) }
            }
        }
        LiquidOutlinedTextField(
            value = customHex,
            onValueChange = { text ->
                customHex = text.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }.take(6)
                onChange(
                    state.copy(accentHex = customHex.takeIf { it.length == 6 }?.let { "#$it" }),
                )
            },
            label = { Text("自定义 RRGGBB") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val currentSaveHex = (state.accentHex ?: AccentPreset.CLASSIC.light).trim().uppercase()
        val canSavePreset = currentSaveHex.length == 7 &&
            savedAccentPresets.none { it.equals(currentSaveHex, ignoreCase = true) } &&
            savedAccentPresets.size < 8
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("收藏预设配色", style = MaterialTheme.typography.bodyMedium)
            TextButton(
                enabled = canSavePreset,
                onClick = { onSaveAccentPreset(currentSaveHex) },
            ) {
                Text(if (savedAccentPresets.any { it.equals(currentSaveHex, ignoreCase = true) }) "已收藏" else "收藏当前")
            }
        }
        if (savedAccentPresets.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                savedAccentPresets.forEach { presetHex ->
                    val presetColor = parse(presetHex)
                    var burstTrigger by remember(presetHex) { mutableIntStateOf(0) }
                    val isSelected = state.accentHex?.equals(presetHex, ignoreCase = true) == true
                    Box(contentAlignment = Alignment.Center) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = presetColor.copy(alpha = if (isSelected) 0.22f else 0.12f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .border(
                                    width = if (isSelected) 1.5.dp else 0.8.dp,
                                    color = if (isSelected) presetColor else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(999.dp),
                                )
                                .clickable { onChange(state.copy(accentHex = presetHex)) },
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(presetColor),
                                )
                                Text(
                                    text = presetHex,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (presetColor.luminance() > 0.55f) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "删除配色 $presetHex",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .clickable(enabled = burstTrigger == 0) {
                                            burstTrigger += 1
                                        },
                                )
                            }
                        }
                        DissolveBurst(
                            trigger = burstTrigger,
                            color = presetColor,
                            particleCount = 16,
                            durationMs = 460,
                            modifier = Modifier.matchParentSize(),
                            onFinished = {
                                burstTrigger = 0
                                onRemoveAccentPreset(presetHex)
                            },
                        )
                    }
                }
            }
        }
        SettingSwitchRow(
            title = "染背景",
            checked = state.tintBackground,
            onCheckedChange = { checked -> onChange(state.copy(tintBackground = checked)) },
        )
        // 玻璃底栏遮罩开关：开 = 亮 0.06 / 暗 0.20 轻蒙层（默认，与 ThemeSettings 同步）；关 = 纯折射玻璃。
        SettingSwitchRow(
            title = "玻璃底栏遮罩",
            checked = state.dockSurfaceAlphaLight > 0f || state.dockSurfaceAlphaDark > 0f,
            onCheckedChange = { enable ->
                onChange(
                    if (enable) {
                        state.copy(dockSurfaceAlphaLight = 0.06f, dockSurfaceAlphaDark = 0.20f)
                    } else {
                        state.copy(dockSurfaceAlphaLight = 0f, dockSurfaceAlphaDark = 0f)
                    },
                )
            },
        )
    }
}

private fun parse(hex: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrDefault(Color.Gray)

/** 平板判定阈值：最小宽度 ≥ 600dp 视为平板。 */
private const val TabletSmallestWidthDp = 600

/** 布局与启动（F15）：默认页、导航布局与界面动画。 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun LayoutSection(
    preferences: AppPreferences,
    onDefaultStartTabChange: (Int) -> Unit,
    onNavigationLayoutChange: (NavigationLayout) -> Unit,
    onAnimationsEnabledChange: (Boolean) -> Unit,
    onPredictiveBackEnabledChange: (Boolean) -> Unit,
    systemPredictiveBackChecker: (() -> SystemPredictiveBackStatus)? = null,
) {
    val startLabels = listOf("今日", "课表", "成绩", "我的")
    val layoutLabels = mapOf(
        NavigationLayout.AUTO to "自动",
        NavigationLayout.BOTTOM to "底部",
        NavigationLayout.SIDE to "侧边",
    )
    // 手机固定底部导航，导航布局只在平板上提供。
    val showNavigationLayout = LocalConfiguration.current.smallestScreenWidthDp >= TabletSmallestWidthDp
    val context = LocalContext.current
    val systemPredictiveBackStatus = rememberSystemPredictiveBackStatus(systemPredictiveBackChecker)
    var blockedPredictiveStatus by remember { mutableStateOf<SystemPredictiveBackStatus?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("默认打开页面", style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            startLabels.forEachIndexed { index, label ->
                OptionPill(
                    label = label,
                    selected = preferences.defaultStartTab == index,
                    onClick = { onDefaultStartTabChange(index) },
                )
            }
        }
        if (showNavigationLayout) {
            Text("导航布局", style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                layoutLabels.forEach { (layout, label) ->
                    OptionPill(
                        label = label,
                        selected = preferences.navigationLayout == layout,
                        onClick = { onNavigationLayoutChange(layout) },
                    )
                }
            }
            Text(
                "自动：宽窗口使用侧边导航，窄窗口保持底部导航",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingSwitchRow(
            title = "界面动画",
            checked = preferences.animationsEnabled,
            onCheckedChange = onAnimationsEnabledChange,
        )
        // 仅 Android 13+（API 33+）显示；Android 12 及以下不渲染此功能。
        if (systemPredictiveBackStatus != SystemPredictiveBackStatus.UNSUPPORTED_ANDROID_VERSION) {
            val effectiveChecked =
                preferences.predictiveBackEnabled && systemPredictiveBackStatus == SystemPredictiveBackStatus.ENABLED
            var guideExpanded by remember { mutableStateOf(false) }
            val motionEnabled = LocalMotionEnabled.current

            SettingSwitchRow(
                title = "预测性返回动画",
                checked = effectiveChecked,
                onCheckedChange = { target ->
                    if (!target) {
                        onPredictiveBackEnabledChange(false)
                    } else {
                        val latest = systemPredictiveBackChecker?.invoke()
                            ?: querySystemPredictiveBackStatus(context)
                        if (latest == SystemPredictiveBackStatus.ENABLED) {
                            onPredictiveBackEnabledChange(true)
                        } else {
                            if (preferences.predictiveBackEnabled) {
                                onPredictiveBackEnabledChange(false)
                            }
                            blockedPredictiveStatus = latest
                            guideExpanded = true
                        }
                    }
                },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
                    .clickable { guideExpanded = !guideExpanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "找不到系统开关？快捷跳转",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Icon(
                    imageVector = if (guideExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (guideExpanded) "收起快捷跳转" else "展开快捷跳转",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(
                visible = guideExpanded,
                enter = if (motionEnabled) fadeIn() + expandVertically() else EnterTransition.None,
                exit = if (motionEnabled) fadeOut() + shrinkVertically() else ExitTransition.None,
            ) {
                val shortcuts = listOf(
                    "开启「开发者选项」" to PredictiveBackSettingsShortcut.ABOUT_PHONE_UNLOCK_DEV,
                    "直达「预测性返回动画」" to PredictiveBackSettingsShortcut.DEVELOPER_PREDICTIVE_BACK,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                ) {
                    shortcuts.forEachIndexed { index, (title, target) ->
                        PredictiveBackShortcutRow(
                            title = title,
                            onClick = { openPredictiveBackSettingsShortcut(context, target) },
                        )
                        if (index < shortcuts.lastIndex) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
        }
    }

    blockedPredictiveStatus?.let { blocked ->
        val isAnimationDisabled = blocked == SystemPredictiveBackStatus.SYSTEM_ANIMATION_DISABLED
        LiquidAlertDialog(
            onDismissRequest = { blockedPredictiveStatus = null },
            title = {
                Text(if (isAnimationDisabled) "系统动画已关闭" else "需先开启系统级预测性返回")
            },
            text = {
                Text(
                    if (isAnimationDisabled) {
                        "检测到系统已关闭全局动画效果，预测性返回动画无法生效。请先在系统设置中恢复动画后再打开此开关。"
                    } else {
                        "检测到系统尚未开启「预测性返回动画」。请先在系统「设置 → 开发者选项」中开启「预测性返回动画」，返回后再打开此开关。"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        blockedPredictiveStatus = null
                        openSystemPredictiveBackSettings(context, blocked)
                    },
                ) { Text("去系统设置") }
            },
            dismissButton = {
                TextButton(onClick = { blockedPredictiveStatus = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun PredictiveBackShortcutRow(
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OptionPill(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    LiquidFilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        modifier = modifier.heightIn(min = 48.dp),
    )
}

/** 亮色预览。 */
@Preview(name = "我的 - 亮色", showBackground = true)
@Composable
private fun MineLightPreview() {
    IYangoThemePreview(darkTheme = false) {
        Surface {
            MineScreen()
        }
    }
}

/** 暗色预览。 */
@Preview(name = "我的 - 暗色", showBackground = true)
@Composable
private fun MineDarkPreview() {
    IYangoThemePreview(darkTheme = true) {
        Surface {
            MineScreen()
        }
    }
}
