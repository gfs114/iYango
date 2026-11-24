@file:OptIn(ExperimentalMaterial3Api::class)

package com.gfs.iyango.feature.main.editor

import com.gfs.iyango.core.designsystem.component.GlassTopBarTextButton
import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassTopBarIconButton
import com.gfs.iyango.feature.main.EditorDiscardBackHandler
import com.gfs.iyango.feature.main.rememberBackPreviewState
import com.gfs.iyango.feature.main.backPreviewLayer
import com.gfs.iyango.feature.main.isDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidAlertDialog
import com.gfs.iyango.core.designsystem.liquidglass.LiquidFilterChip
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedTextField
import com.gfs.iyango.core.designsystem.liquidglass.LiquidToggle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gfs.iyango.core.designsystem.component.SectionCard
import com.gfs.iyango.core.designsystem.theme.CourseSwatch
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.designsystem.theme.LocalBrand
import com.gfs.iyango.core.model.WeekType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 编辑页局部选择弹层状态。 */
private sealed interface EditorSheet {
    data object None : EditorSheet
    data class WeekPicker(val index: Int) : EditorSheet
    data class TimePicker(val index: Int, val isStart: Boolean) : EditorSheet
    data class DatePicker(val index: Int) : EditorSheet
    data class DayPicker(val index: Int) : EditorSheet
    data class SectionPicker(val index: Int, val isStart: Boolean) : EditorSheet
}

/** 星期标签（周一…周日）。 */
private val DayLabels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/**
 * 课程编辑页（契约 §5）：基本信息卡 + 上课安排卡×N + 顶栏保存 + 底部撤销/重做操作条。
 *
 * @param onBack 返回上一页（导航由 W9 接线）。
 */
@Composable
fun CourseEditorScreen(
    onBack: () -> Unit,
    viewModel: CourseEditorViewModel = hiltViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val conflictMessage by viewModel.conflictMessage.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val canUndo by viewModel.canUndo.collectAsStateWithLifecycle()
    val canRedo by viewModel.canRedo.collectAsStateWithLifecycle()
    val displaySettings by viewModel.displaySettings.collectAsStateWithLifecycle()
    val palette = CourseSwatch.effectivePalette(
        displaySettings.paletteLight,
        displaySettings.paletteDark,
        isDarkTheme(),
    )
    var sheet by remember { mutableStateOf<EditorSheet>(EditorSheet.None) }
    var discardDialog by remember { mutableStateOf(false) }

    LaunchedEffect(saved) {
        if (saved) onBack()
    }
    val backPreview = rememberBackPreviewState()
    EditorDiscardBackHandler(backPreview, enabled = viewModel.isDirty()) {
        discardDialog = true
    }

    /** 对第 [index] 条安排应用编辑（先记撤销快照）。 */
    fun editArrangement(index: Int, tag: String, transform: (ArrangementDraft) -> ArrangementDraft) {
        viewModel.update("a$index.$tag") { current ->
            current.copy(
                arrangements = current.arrangements.mapIndexed { i, a ->
                    if (i == index) transform(a) else a
                }
            )
        }
    }

    GlassScaffold(
        modifier = Modifier.backPreviewLayer(backPreview, cardStyle = true),
        topBar = {
            GlassTopBar(
                title = if (viewModel.courseId > 0) "编辑课程" else "新建课程",
                actions = {
                    GlassTopBarTextButton(text = "保存", onClick = { viewModel.save() })
                },
                navigationIcon = {
                    GlassTopBarIconButton(onClick = {
                        if (viewModel.isDirty()) discardDialog = true else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        bottomBar = {
            GlassSurface(
                material = GlassMaterial.OVERLAY,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                allowRefraction = true,
                includeShadow = true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarSafePadding(),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        if (error != null) {
                            Text(
                                text = error ?: "",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LiquidOutlinedButton(
                                onClick = viewModel::undo,
                                enabled = canUndo,
                                modifier = Modifier.width(76.dp),
                            ) { Text("撤销") }
                            LiquidOutlinedButton(
                                onClick = viewModel::redo,
                                enabled = canRedo,
                                modifier = Modifier.width(76.dp),
                            ) { Text("重做") }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "共 ${form.arrangements.size} 条上课安排",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BasicInfoCard(
                form = form,
                onEdit = viewModel::update,
                palette = palette,
            )
            form.arrangements.forEachIndexed { index, arrangement ->
                ArrangementCard(
                    index = index,
                    arrangement = arrangement,
                    removable = form.arrangements.size > 1,
                    onRemove = {
                        viewModel.update("remove") {
                            it.copy(arrangements = it.arrangements.filterIndexed { i, _ -> i != index })
                        }
                    },
                    onEdit = { tag, transform -> editArrangement(index, tag, transform) },
                    onOpenSheet = { sheetFactory -> sheet = sheetFactory(index) },
                )
            }
            LiquidOutlinedButton(
                onClick = {
                    viewModel.update("add") { it.copy(arrangements = it.arrangements + ArrangementDraft()) }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("添加上课安排")
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    EditorSheetHost(
        sheet = sheet,
        form = form,
        onDismiss = { sheet = EditorSheet.None },
        onEditArrangement = ::editArrangement,
    )

    if (conflictMessage != null) {
        LiquidAlertDialog(
            onDismissRequest = viewModel::dismissConflict,
            title = { Text("冲突提示", style = MaterialTheme.typography.titleLarge) },
            text = { Text(conflictMessage ?: "") },
            confirmButton = {
                TextButton(onClick = viewModel::confirmConflict) { Text("继续添加") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissConflict) { Text("取消") }
            },
        )
    }

    if (discardDialog) {
        LiquidAlertDialog(
            onDismissRequest = { discardDialog = false },
            title = { Text("放弃修改？", style = MaterialTheme.typography.titleLarge) },
            text = { Text("表单尚未保存，离开将丢失已填写的内容。") },
            confirmButton = {
                TextButton(onClick = {
                    discardDialog = false
                    onBack()
                }) { Text("放弃并离开") }
            },
            dismissButton = {
                TextButton(onClick = { discardDialog = false }) { Text("继续编辑") }
            },
        )
    }
}

/** 底部操作条的导航栏避让。 */
private fun Modifier.statusBarSafePadding(): Modifier = this.navigationBarsPadding()

/** 基本信息卡（§5.1）。 */
@Composable
private fun BasicInfoCard(
    form: CourseEditorForm,
    onEdit: (String, (CourseEditorForm) -> CourseEditorForm) -> Unit,
    palette: List<String>,
) {
    SectionCard(title = "基本信息") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LabeledField(
                label = "课程名称（必填）",
                value = form.name,
                onValueChange = { text -> onEdit("name") { it.copy(name = text.take(64)) } },
            )
            LabeledField(
                label = "简称",
                value = form.abbreviation,
                onValueChange = { text -> onEdit("abbr") { it.copy(abbreviation = text.take(32)) } },
            )
            LabeledField(
                label = "学分",
                value = form.credit,
                onValueChange = { text ->
                    onEdit("credit") {
                        it.copy(credit = text.filter { c -> c.isDigit() || c == '.' }.take(5))
                    }
                },
            )
            SwitchRow(
                label = "户外课程",
                checked = form.isOutdoor,
                onToggle = { value -> onEdit("outdoor") { it.copy(isOutdoor = value) } },
            )
            SwitchRow(
                label = "启用课程",
                checked = form.enabled,
                onToggle = { value -> onEdit("enabled") { it.copy(enabled = value) } },
            )
            LiquidOutlinedTextField(
                value = form.remark,
                onValueChange = { text -> onEdit("remark") { it.copy(remark = text.take(300)) } },
                label = { Text("备注") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "${form.remark.length}/300",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End),
            )
            ColorDots(
                palette = palette,
                selected = form.color,
                onSelect = { hex -> onEdit("color") { it.copy(color = hex) } },
            )
        }
    }
}

/** 上课安排卡（§5.2）。[onOpenSheet] 收到"按 index 构造弹层"的工厂。 */
@Composable
private fun ArrangementCard(
    index: Int,
    arrangement: ArrangementDraft,
    removable: Boolean,
    onRemove: () -> Unit,
    onEdit: (String, (ArrangementDraft) -> ArrangementDraft) -> Unit,
    onOpenSheet: ((Int) -> EditorSheet) -> Unit,
) {
    SectionCard(
        title = "上课安排 ${index + 1}",
        action = {
            if (removable) {
                TextButton(onClick = onRemove) {
                    Text("移除", color = MaterialTheme.colorScheme.error)
                }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LabeledField(
                label = "教师",
                value = arrangement.teacher,
                onValueChange = { text -> onEdit("teacher") { it.copy(teacher = text) } },
            )
            LabeledField(
                label = "地点",
                value = arrangement.classroom,
                onValueChange = { text -> onEdit("room") { it.copy(classroom = text) } },
            )
            TimeModeRow(arrangement, onEdit)
            when (arrangement.timeMode) {
                0 -> {
                    PickerRow(
                        label = "星期",
                        value = DayLabels[arrangement.dayOfWeek - 1],
                        onClick = { onOpenSheet { EditorSheet.DayPicker(it) } },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PickerRow(
                            label = "起始节",
                            value = "第 ${arrangement.startSection} 节",
                            onClick = { onOpenSheet { EditorSheet.SectionPicker(it, true) } },
                            modifier = Modifier.weight(1f),
                        )
                        PickerRow(
                            label = "结束节",
                            value = "第 ${arrangement.endSection} 节",
                            onClick = { onOpenSheet { EditorSheet.SectionPicker(it, false) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                1 -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PickerRow(
                            label = "开始",
                            value = arrangement.startTime,
                            onClick = { onOpenSheet { EditorSheet.TimePicker(it, true) } },
                            modifier = Modifier.weight(1f),
                        )
                        PickerRow(
                            label = "结束",
                            value = arrangement.endTime,
                            onClick = { onOpenSheet { EditorSheet.TimePicker(it, false) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                2 -> {
                    PickerRow(
                        label = "日期",
                        value = arrangement.date.ifEmpty { "点击选择" },
                        onClick = { onOpenSheet { EditorSheet.DatePicker(it) } },
                    )
                }
            }
            WeekTypeRow(arrangement, onEdit)
            PickerRow(
                label = "周次",
                value = weekSummary(arrangement),
                onClick = { onOpenSheet { EditorSheet.WeekPicker(it) } },
            )
            if (arrangement.selectedWeeks.isEmpty()) {
                CycleRow(arrangement, onEdit)
            }
        }
    }
}

/** 全部弹层集中渲染（index 已绑定，杜绝越界）。 */
@Composable
private fun EditorSheetHost(
    sheet: EditorSheet,
    form: CourseEditorForm,
    onDismiss: () -> Unit,
    onEditArrangement: (Int, String, (ArrangementDraft) -> ArrangementDraft) -> Unit,
) {
    when (val current = sheet) {
        EditorSheet.None -> Unit
        is EditorSheet.WeekPicker -> WeekPickerDialog(
            arrangement = form.arrangements[current.index],
            onDismiss = onDismiss,
            onConfirm = { weeks ->
                onEditArrangement(current.index, "weeks") { it.copy(selectedWeeks = weeks) }
                onDismiss()
            },
        )
        is EditorSheet.TimePicker -> {
            val arrangement = form.arrangements[current.index]
            val parts = (if (current.isStart) arrangement.startTime else arrangement.endTime)
                .split(":")
            val state = rememberTimePickerState(
                initialHour = parts.getOrNull(0)?.toIntOrNull() ?: 8,
                initialMinute = parts.getOrNull(1)?.toIntOrNull() ?: 0,
                is24Hour = true,
            )
            LiquidAlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(if (current.isStart) "开始时间" else "结束时间", style = MaterialTheme.typography.titleLarge) },
                text = { TimePicker(state = state) },
                confirmButton = {
                    TextButton(onClick = {
                        val text = "%02d:%02d".format(state.hour, state.minute)
                        onEditArrangement(current.index, "time") { a ->
                            if (current.isStart) a.copy(startTime = text) else a.copy(endTime = text)
                        }
                        onDismiss()
                    }) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("取消") }
                },
            )
        }
        is EditorSheet.DatePicker -> {
            val pickerState = rememberDatePickerState(
                initialSelectedDateMillis = form.arrangements[current.index].date
                    .takeIf { it.isNotBlank() }
                    ?.let { LocalDate.parse(it).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli() },
            )
            DatePickerDialog(
                onDismissRequest = onDismiss,
                confirmButton = {
                    TextButton(onClick = {
                        val text = pickerState.selectedDateMillis?.let { millis ->
                            Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate().toString()
                        } ?: ""
                        onEditArrangement(current.index, "date") { it.copy(date = text) }
                        onDismiss()
                    }) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("取消") }
                },
            ) { DatePicker(state = pickerState) }
        }
        is EditorSheet.DayPicker -> OptionPickerDialog(
            title = "星期",
            options = DayLabels,
            selectedIndex = form.arrangements[current.index].dayOfWeek - 1,
            onDismiss = onDismiss,
            onSelect = { dayIndex ->
                onEditArrangement(current.index, "day") { it.copy(dayOfWeek = dayIndex + 1) }
                onDismiss()
            },
        )
        is EditorSheet.SectionPicker -> OptionPickerDialog(
            title = if (current.isStart) "起始节次" else "结束节次",
            options = (1..20).map { "第 $it 节" },
            selectedIndex = (if (current.isStart) {
                form.arrangements[current.index].startSection
            } else {
                form.arrangements[current.index].endSection
            }) - 1,
            onDismiss = onDismiss,
            onSelect = { sectionIndex ->
                onEditArrangement(current.index, "section") { a ->
                    val section = sectionIndex + 1
                    if (current.isStart) {
                        a.copy(startSection = section, endSection = maxOf(a.endSection, section))
                    } else {
                        a.copy(endSection = section, startSection = minOf(a.startSection, section))
                    }
                }
                onDismiss()
            },
        )
    }
}

/** 通用标签输入行。 */
@Composable
private fun LabeledField(label: String, value: String, onValueChange: (String) -> Unit) {
    LiquidOutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 开关行。 */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        LiquidToggle(checked = checked, onCheckedChange = onToggle)
    }
}

/** 12 色圆点选择区（§5.2），两行 6 个。 */
@Composable
private fun ColorDots(palette: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column {
        Text("课程颜色", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        palette.chunked(6).forEach { rowColors ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                rowColors.forEach { hex ->
                    ColorDot(
                        hex = hex,
                        selected = selected.equals(hex, ignoreCase = true),
                        onClick = { onSelect(hex) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorDot(hex: String, selected: Boolean, onClick: () -> Unit) {
    val brand = LocalBrand.current
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(parseHexColor(hex))
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "已选择",
                tint = brand.onBrand,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun parseHexColor(hex: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrDefault(Color.Gray)

/** 选择行（surfaceAlt 底 R12，高 48）。 */
@Composable
private fun PickerRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 时间模式三选（§5.2）。 */
@Composable
private fun TimeModeRow(
    arrangement: ArrangementDraft,
    onEdit: (String, (ArrangementDraft) -> ArrangementDraft) -> Unit,
) {
    val brand = LocalBrand.current
    val modes = listOf(0 to "按节次", 1 to "自定义时间", 2 to "指定日期")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        modes.forEach { (mode, label) ->
            val chosen = arrangement.timeMode == mode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (chosen) brand.accent else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable { onEdit("mode") { it.copy(timeMode = mode) } },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (chosen) brand.onBrand else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 每周/单周/双周三选。 */
@Composable
private fun WeekTypeRow(
    arrangement: ArrangementDraft,
    onEdit: (String, (ArrangementDraft) -> ArrangementDraft) -> Unit,
) {
    val types = listOf(WeekType.EVERY to "每周", WeekType.ODD to "单周", WeekType.EVEN to "双周")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        types.forEach { (type, label) ->
            val chosen = arrangement.weekType == type
            LiquidFilterChip(
                selected = chosen,
                onClick = { onEdit("weekType") { it.copy(weekType = type) } },
                label = { Text(label) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 轮换两个数字输入（§5.2）。 */
@Composable
private fun CycleRow(
    arrangement: ArrangementDraft,
    onEdit: (String, (ArrangementDraft) -> ArrangementDraft) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NumberField(
            label = "每几周",
            value = arrangement.cycleLength,
            modifier = Modifier.weight(1f),
        ) { number -> onEdit("cycleLen") { it.copy(cycleLength = number.coerceIn(1, 10)) } }
        NumberField(
            label = "轮换第几周",
            value = arrangement.cycleWeek,
            modifier = Modifier.weight(1f),
        ) { number -> onEdit("cycleWeek") { it.copy(cycleWeek = number.coerceIn(0, 10)) } }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit,
) {
    LiquidOutlinedTextField(
        value = if (value == 0) "" else value.toString(),
        onValueChange = { text -> text.toIntOrNull()?.let(onChange) },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
    )
}

/** 周次选择弹窗：1..MAX_WEEK 芯片多选 + 全选/清空/单周/双周快捷。 */
@Composable
private fun WeekPickerDialog(
    arrangement: ArrangementDraft,
    onDismiss: () -> Unit,
    onConfirm: (List<Int>) -> Unit,
) {
    var selected by remember(arrangement) { mutableStateOf(arrangement.selectedWeeks.toSet()) }
    LiquidAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择周次", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { selected = (1..CourseEditorDefaults.MAX_WEEK).toSet() }) { Text("全选") }
                    TextButton(onClick = { selected = emptySet() }) { Text("清空") }
                    TextButton(onClick = { selected = (1..CourseEditorDefaults.MAX_WEEK).filter { it % 2 == 1 }.toSet() }) { Text("单周") }
                    TextButton(onClick = { selected = (1..CourseEditorDefaults.MAX_WEEK).filter { it % 2 == 0 }.toSet() }) { Text("双周") }
                }
                WeekChipGrid(selected = selected) { week ->
                    selected = if (week in selected) selected - week else selected + week
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected.sorted()) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun WeekChipGrid(selected: Set<Int>, onToggle: (Int) -> Unit) {
    val brand = LocalBrand.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (1..CourseEditorDefaults.MAX_WEEK).chunked(6).forEach { weekRow ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                weekRow.forEach { week ->
                    val chosen = week in selected
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (chosen) brand.accent else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onToggle(week) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            week.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (chosen) brand.onBrand else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 通用单选弹窗。 */
@Composable
private fun OptionPickerDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    LiquidAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                options.forEachIndexed { index, option ->
                    Text(
                        text = option,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (index == selectedIndex) {
                            LocalBrand.current.accent
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 亮色预览（静态表单卡片，不经 VM/导航）。 */
@Preview(name = "课程编辑 - 亮色", showBackground = true)
@Composable
private fun CourseEditorLightPreview() {
    IYangoThemePreview(darkTheme = false) {
        Surface {
            EditorCardsPreviewBody()
        }
    }
}

/** 暗色预览。 */
@Preview(name = "课程编辑 - 暗色", showBackground = true)
@Composable
private fun CourseEditorDarkPreview() {
    IYangoThemePreview(darkTheme = true) {
        Surface {
            EditorCardsPreviewBody()
        }
    }
}

@Composable
private fun EditorCardsPreviewBody() {
    val previewForm = CourseEditorForm(
        name = "高等数学",
        arrangements = listOf(
            ArrangementDraft(teacher = "张老师", classroom = "教1-301", startWeek = 1, endWeek = 16),
        ),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BasicInfoCard(previewForm, onEdit = { _, _ -> }, palette = CourseSwatch.accents)
        ArrangementCard(
            index = 0,
            arrangement = previewForm.arrangements.first(),
            removable = false,
            onRemove = {},
            onEdit = { _, _ -> },
            onOpenSheet = {},
        )
    }
}
