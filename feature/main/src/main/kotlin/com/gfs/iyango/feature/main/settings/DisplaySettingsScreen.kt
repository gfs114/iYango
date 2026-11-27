@file:OptIn(ExperimentalMaterial3Api::class)

package com.gfs.iyango.feature.main.settings

import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassTopBarIconButton
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.gfs.iyango.core.designsystem.component.SectionCard
import com.gfs.iyango.core.designsystem.component.SettingSliderRow
import com.gfs.iyango.core.designsystem.component.SettingSwitchRow
import com.gfs.iyango.core.designsystem.liquidglass.LiquidFilterChip
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.text.KeyboardOptions
import com.gfs.iyango.core.designsystem.theme.CourseSwatch
import com.gfs.iyango.feature.main.schedule.*
import com.gfs.iyango.feature.main.isDarkTheme
import com.gfs.iyango.core.model.ScheduleHeaderOrder
import com.gfs.iyango.core.model.CellTextAlign
import com.gfs.iyango.core.model.CourseInfoField
import com.gfs.iyango.core.model.GridLineStyle
import com.gfs.iyango.core.model.ScheduleDisplaySettings
import java.util.Locale

/**
 * 课表显示设置页（F09）：全局默认 / 按课表覆盖，全部选项即时生效并持久化。
 *
 * 只影响呈现，不修改数据库行；隐藏周末与合并相邻都只改变绘制，详情仍保留各安排。
 */
@Composable
fun DisplaySettingsScreen(
    onBack: () -> Unit,
    viewModel: DisplaySettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val wallpaper by viewModel.wallpaper.collectAsStateWithLifecycle()
    var perTimetable by rememberSaveable { mutableStateOf(viewModel.initialPerTimetable) }
    val base = if (perTimetable) state.effective else state.global
    var draft by remember(base, perTimetable) { mutableStateOf(base) }

    fun update(settings: ScheduleDisplaySettings) {
        draft = settings
        viewModel.save(settings, perTimetable)
    }

    GlassScaffold(
        topBar = {
            GlassTopBar(
                title = "课表显示",
                navigationIcon = {
                    GlassTopBarIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        val verticalPadding = PaddingValues(
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding(),
        )
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(
                start = innerPadding.calculateStartPadding(layoutDirection),
                end = innerPadding.calculateEndPadding(layoutDirection),
            ),
        ) {
            val availableHeight = (maxHeight - innerPadding.calculateTopPadding() - innerPadding.calculateBottomPadding()).coerceAtLeast(0.dp)
            val previewHeight = (availableHeight * 0.45f).coerceAtMost(400.dp)
            val settingsContent: @Composable (Modifier) -> Unit = { contentModifier ->
        Column(
            modifier = contentModifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.hasTimetable) {
                ScopeSection(
                    timetableName = state.timetableName,
                    perTimetable = perTimetable,
                    hasOverride = state.hasOverride,
                    onChange = { perTimetable = it },
                )
            }

            SectionCard(title = "布局") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("视图模式", style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            com.gfs.iyango.core.model.ScheduleViewMode.GRID to "网格",
                            com.gfs.iyango.core.model.ScheduleViewMode.TIMELINE to "时间轴",
                        ).forEach { (mode, label) ->
                            LiquidFilterChip(
                                selected = draft.viewMode == mode,
                                onClick = { update(draft.copy(viewMode = mode)) },
                                label = { Text(label) },
                            )
                        }
                    }
                    SettingSwitchRow(title = "显示午休分隔", checked = draft.showLunchBreak,
                        onCheckedChange = { update(draft.copy(showLunchBreak = it)) })
                    SettingSwitchRow(title = "显示晚休分隔", checked = draft.showEveningBreak,
                        onCheckedChange = { update(draft.copy(showEveningBreak = it)) })
                    SettingSwitchRow(title = "显示非本周课程", checked = draft.showOffWeek,
                        onCheckedChange = { update(draft.copy(showOffWeek = it)) })
                    Text("表头顺序", style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScheduleHeaderOrder.entries.forEach { order ->
                            LiquidFilterChip(selected = draft.headerOrder == order,
                                onClick = { update(draft.copy(headerOrder = order)) },
                                label = { Text(if (order == ScheduleHeaderOrder.WEEKDAY_DATE) "星期 / 日期" else "日期 / 星期") })
                        }
                    }
                    SettingSwitchRow(
                        title = "隐藏周末",
                        checked = !draft.showWeekends,
                        onCheckedChange = { update(draft.copy(showWeekends = !it)) },
                    )
                    SettingSwitchRow(
                        title = "合并相邻课程",
                        checked = draft.mergeAdjacent,
                        onCheckedChange = { update(draft.copy(mergeAdjacent = it)) },
                    )
                    Text("行高", style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 56, 64, 72, 77, 80).forEach { value ->
                            LiquidFilterChip(
                                selected = draft.rowHeightDp == value,
                                onClick = { update(draft.copy(rowHeightDp = value)) },
                                label = { Text(if (value == 0) "自适应" else "${value}dp") },
                            )
                        }
                    }
                    var minRowHeightDraft by remember(draft.minRowHeightDp) {
                        mutableFloatStateOf(draft.minRowHeightDp.toFloat())
                    }
                    SettingSliderRow(
                        title = "最小行高",
                        value = minRowHeightDraft,
                        range = 44f..80f,
                        valueLabel = "${minRowHeightDraft.toInt()}dp",
                        onValueChange = { minRowHeightDraft = it },
                        onValueChangeFinished = { update(draft.copy(minRowHeightDp = minRowHeightDraft.toInt())) },
                    )
                    var cornerRadiusDraft by remember(draft.cornerRadiusDp) {
                        mutableFloatStateOf(draft.cornerRadiusDp.toFloat())
                    }
                    SettingSliderRow(
                        title = "课程块圆角",
                        value = cornerRadiusDraft,
                        range = 0f..20f,
                        valueLabel = "${cornerRadiusDraft.toInt()}dp",
                        onValueChange = { cornerRadiusDraft = it },
                        onValueChangeFinished = { update(draft.copy(cornerRadiusDp = cornerRadiusDraft.toInt())) },
                    )
                    SettingSwitchRow(
                        title = "显示网格线",
                        checked = draft.showGridLines,
                        onCheckedChange = { update(draft.copy(showGridLines = it)) },
                    )
                    var gridLineWidthDraft by remember(draft.gridLineWidthDp) {
                        mutableFloatStateOf(draft.gridLineWidthDp)
                    }
                    SettingSliderRow(
                        title = "网格线宽",
                        value = gridLineWidthDraft,
                        range = 0.5f..4f,
                        valueLabel = "${String.format(Locale.US, "%.1f", gridLineWidthDraft)}dp",
                        onValueChange = { gridLineWidthDraft = it },
                        onValueChangeFinished = { update(draft.copy(gridLineWidthDp = gridLineWidthDraft)) },
                    )
                    val currentLineStyle = when {
                        draft.gridLineStyle != GridLineStyle.SOLID -> draft.gridLineStyle
                        draft.gridLineDashed -> GridLineStyle.DASHED
                        else -> GridLineStyle.SOLID
                    }
                    Text("网格样式", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            GridLineStyle.SOLID to "实线",
                            GridLineStyle.DASHED to "虚线",
                            GridLineStyle.DOTTED to "点线",
                        ).forEach { (style, label) ->
                            LiquidFilterChip(
                                selected = currentLineStyle == style,
                                onClick = {
                                    update(
                                        draft.copy(
                                            gridLineStyle = style,
                                            gridLineDashed = style == GridLineStyle.DASHED,
                                        ),
                                    )
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }

            SectionCard(title = "文字与信息") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    var textSizeDraft by remember(draft.textSizeSp) {
                        mutableFloatStateOf(draft.textSizeSp)
                    }
                    SettingSliderRow(
                        title = "课程字号",
                        value = textSizeDraft,
                        range = 9f..22f,
                        valueLabel = "${textSizeDraft.toInt()}sp",
                        onValueChange = { textSizeDraft = it },
                        onValueChangeFinished = { update(draft.copy(textSizeSp = textSizeDraft)) },
                    )
                    var headerTextSizeDraft by remember(draft.headerTextSizeSp) {
                        mutableFloatStateOf(draft.headerTextSizeSp)
                    }
                    SettingSliderRow(
                        title = "表头字号",
                        value = headerTextSizeDraft,
                        range = 9f..20f,
                        valueLabel = "${headerTextSizeDraft.toInt()}sp",
                        onValueChange = { headerTextSizeDraft = it },
                        onValueChangeFinished = { update(draft.copy(headerTextSizeSp = headerTextSizeDraft)) },
                    )
                    Text("文字对齐", style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CellTextAlign.entries.forEach { align ->
                            LiquidFilterChip(
                                selected = draft.align == align,
                                onClick = { update(draft.copy(align = align)) },
                                label = { Text(if (align == CellTextAlign.START) "左对齐" else "居中") },
                            )
                        }
                    }
                    SettingSwitchRow(
                        title = "文字垂直居中",
                        checked = draft.textVCenter,
                        onCheckedChange = { update(draft.copy(textVCenter = it)) },
                    )
                    SettingSwitchRow(
                        title = "显示教室",
                        checked = draft.showClassroom,
                        onCheckedChange = { update(draft.copy(showClassroom = it)) },
                    )
                    SettingSwitchRow(
                        title = "教室前显示 @",
                        checked = draft.showAtPrefix,
                        onCheckedChange = { update(draft.copy(showAtPrefix = it)) },
                    )
                    SettingSwitchRow(
                        title = "显示教师",
                        checked = draft.showTeacher,
                        onCheckedChange = { update(draft.copy(showTeacher = it)) },
                    )
                    SettingSwitchRow(
                        title = "显示上课时间",
                        checked = draft.showTime,
                        onCheckedChange = { update(draft.copy(showTime = it)) },
                    )
                    Text("信息顺序", style = MaterialTheme.typography.bodyLarge)
                    draft.courseInfoOrder.forEachIndexed { index, field ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = field.label(),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { update(draft.copy(courseInfoOrder = draft.courseInfoOrder.swap(index, -1))) },
                                enabled = index > 0,
                            ) {
                                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上移")
                            }
                            IconButton(
                                onClick = { update(draft.copy(courseInfoOrder = draft.courseInfoOrder.swap(index, +1))) },
                                enabled = index < draft.courseInfoOrder.lastIndex,
                            ) {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下移")
                            }
                        }
                    }
                }
            }

            SectionCard(title = "透明度") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    var cellAlphaDraft by remember(draft.cellAlpha) {
                        mutableFloatStateOf(draft.cellAlpha)
                    }
                    SettingSliderRow(
                        title = "课程块不透明度",
                        value = cellAlphaDraft,
                        range = 0.1f..1f,
                        valueLabel = "${(cellAlphaDraft * 100).toInt()}%",
                        onValueChange = { cellAlphaDraft = it },
                        onValueChangeFinished = { update(draft.copy(cellAlpha = cellAlphaDraft)) },
                    )
                    var offWeekAlphaDraft by remember(draft.offWeekAlpha) {
                        mutableFloatStateOf(draft.offWeekAlpha)
                    }
                    SettingSliderRow(
                        title = "非本周块不透明度",
                        value = offWeekAlphaDraft,
                        range = 0.1f..1f,
                        valueLabel = "${(offWeekAlphaDraft * 100).toInt()}%",
                        onValueChange = { offWeekAlphaDraft = it },
                        onValueChangeFinished = { update(draft.copy(offWeekAlpha = offWeekAlphaDraft)) },
                    )
                }
            }

            SectionCard(title = "颜色") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HexColorField(
                        title = "表头底色",
                        value = draft.headerBackgroundHex,
                        onValueChange = { update(draft.copy(headerBackgroundHex = it)) },
                    )
                    HexColorField(
                        title = "网格线颜色",
                        value = draft.gridLineHex,
                        onValueChange = { update(draft.copy(gridLineHex = it)) },
                    )
                    HexColorField(
                        title = "网格背景色",
                        value = draft.backgroundColorHex,
                        onValueChange = { update(draft.copy(backgroundColorHex = it)) },
                    )
                    Text(
                        text = if (draft.courseColorOverrides.isEmpty()) {
                            "课程配色：跟随默认色板"
                        } else {
                            "课程配色：已自定义 ${draft.courseColorOverrides.size} 组"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LiquidOutlinedButton(
                            onClick = { viewModel.reassignColors(draft, perTimetable) },
                            enabled = state.hasTimetable,
                        ) { Text("重新配色") }
                        LiquidOutlinedButton(
                            onClick = { viewModel.clearColors(draft, perTimetable) },
                            enabled = draft.courseColorOverrides.isNotEmpty(),
                        ) { Text("清除配色") }
                    }
                }
            }

            PaletteGroup(draft = draft, onUpdate = ::update)

            SectionCard(title = "边框") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    var borderWidthDraft by remember(draft.borderWidthDp) {
                        mutableFloatStateOf(draft.borderWidthDp)
                    }
                    SettingSliderRow(
                        title = "边框宽度",
                        value = borderWidthDraft,
                        range = 0f..4f,
                        valueLabel = if (borderWidthDraft < 0.05f) "无" else "${String.format(Locale.US, "%.1f", borderWidthDraft)}dp",
                        onValueChange = { borderWidthDraft = it },
                        onValueChangeFinished = { update(draft.copy(borderWidthDp = borderWidthDraft)) },
                    )
                    SettingSwitchRow(
                        title = "边框用课程色",
                        checked = draft.borderUseCourseColor,
                        onCheckedChange = { update(draft.copy(borderUseCourseColor = it)) },
                    )
                    HexColorField(
                        title = "边框颜色",
                        value = draft.borderColorHex,
                        onValueChange = { update(draft.copy(borderColorHex = it)) },
                    )
                    SettingSwitchRow(
                        title = "边框虚线",
                        checked = draft.borderDashed,
                        onCheckedChange = { update(draft.copy(borderDashed = it)) },
                    )
                }
            }

            LiquidOutlinedButton(
                onClick = {
                    if (perTimetable) viewModel.removeOverride() else viewModel.resetGlobal()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = perTimetable || draft != ScheduleDisplaySettings(),
            ) {
                Text(if (perTimetable) "删除本课表覆盖" else "恢复全局默认")
            }
        }
            }
            val previewContent: @Composable () -> Unit = {
                Box(Modifier.fillMaxSize()) {
                    val path = if (isDarkTheme()) wallpaper.darkPath ?: wallpaper.path else wallpaper.path ?: wallpaper.darkPath
                    WallpaperLayer(path, wallpaper.scrim, wallpaper.blur)
                    when (val current = preview) {
                        is ScheduleUiState.Ready -> ScheduleWeekGrid(data = current.data, display = draft,
                            hasWallpaper = path != null, readOnly = true, onGroupClick = {})
                        ScheduleUiState.NoTimetable -> Text("还没有课表", Modifier.align(Alignment.Center))
                        ScheduleUiState.NoSemester -> Text("请先设置学期", Modifier.align(Alignment.Center))
                        is ScheduleUiState.Error -> Text(current.message, Modifier.align(Alignment.Center))
                        ScheduleUiState.FirstLoading -> Text("加载课表…", Modifier.align(Alignment.Center))
                    }
                }
            }
            if (maxWidth >= 840.dp) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).widthIn(min = 360.dp).padding(verticalPadding)) { previewContent() }
                    Box(Modifier.weight(1f)) {
                        settingsContent(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(verticalPadding))
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(verticalPadding)) {
                    Box(Modifier.fillMaxWidth().height(previewHeight)) { previewContent() }
                    settingsContent(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun ScopeSection(
    timetableName: String,
    perTimetable: Boolean,
    hasOverride: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SectionCard(title = "生效范围") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiquidFilterChip(
                selected = !perTimetable,
                onClick = { onChange(false) },
                label = { Text("全部课表") },
            )
            LiquidFilterChip(
                selected = perTimetable,
                onClick = { onChange(true) },
                label = { Text(if (hasOverride) "仅 $timetableName · 已覆盖" else "仅 $timetableName") },
            )
        }
    }
}

@Composable
private fun PaletteGroup(
    draft: ScheduleDisplaySettings,
    onUpdate: (ScheduleDisplaySettings) -> Unit,
) {
    var dark by rememberSaveable { mutableStateOf(false) }
    var slot by rememberSaveable { mutableIntStateOf(0) }
    val stored = if (dark) draft.paletteDark else draft.paletteLight
    val effective = CourseSwatch.effectivePalette(draft.paletteLight, draft.paletteDark, dark)

    fun updateSlot(hex: String) {
        val values = List(CourseSwatch.accents.size) { index -> stored.getOrNull(index).orEmpty() }
            .toMutableList()
        values[slot] = hex
        onUpdate(if (dark) draft.copy(paletteDark = values) else draft.copy(paletteLight = values))
    }

    SectionCard(title = "色板") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiquidFilterChip(
                    selected = !dark,
                    onClick = { dark = false },
                    label = { Text("浅色色板") },
                )
                LiquidFilterChip(
                    selected = dark,
                    onClick = { dark = true },
                    label = { Text("深色色板") },
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                effective.chunked(6).forEachIndexed { rowIndex, rowColors ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        rowColors.forEachIndexed { columnIndex, hex ->
                            val index = rowIndex * 6 + columnIndex
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(paletteColor(hex))
                                    .border(
                                        width = if (slot == index) 2.dp else 0.dp,
                                        color = if (slot == index) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            Color.Transparent
                                        },
                                        shape = CircleShape,
                                    )
                                    .semantics { contentDescription = "第 ${index + 1} 色" }
                                    .clickable { slot = index },
                            )
                        }
                    }
                }
            }
            HexColorField(
                title = "第 ${slot + 1} 色",
                value = stored.getOrNull(slot).orEmpty(),
                onValueChange = ::updateSlot,
            )
            LiquidOutlinedButton(
                onClick = { onUpdate(draft.copy(paletteLight = emptyList(), paletteDark = emptyList())) },
                enabled = draft.paletteLight.isNotEmpty() || draft.paletteDark.isNotEmpty(),
            ) { Text("恢复内置色板") }
        }
    }
}

/** 色板圆点取色；非法值回退灰色，避免损坏配置崩溃。 */
private fun paletteColor(hex: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrDefault(Color.Gray)

@Composable
private fun HexColorField(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.removePrefix("#")) }
    LiquidOutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }.take(6)
            text = filtered
            when {
                filtered.isEmpty() -> onValueChange("")
                filtered.length == 6 -> onValueChange("#$filtered")
            }
        },
        label = { Text(title) },
        placeholder = { Text("RRGGBB") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun CourseInfoField.label(): String = when (this) {
    CourseInfoField.CLASSROOM -> "教室"
    CourseInfoField.TEACHER -> "教师"
    CourseInfoField.TIME -> "时间"
}

/** 交换 [index] 与相邻项；越界时原样返回。 */
private fun List<CourseInfoField>.swap(index: Int, delta: Int): List<CourseInfoField> {
    val target = index + delta
    if (index !in indices || target !in indices) return this
    return toMutableList().also {
        val value = it[index]
        it[index] = it[target]
        it[target] = value
    }
}
