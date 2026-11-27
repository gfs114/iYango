@file:OptIn(ExperimentalMaterial3Api::class)

package com.gfs.iyango.feature.main.timetable

import com.gfs.iyango.core.designsystem.component.GlassOverlayHeader
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidAlertDialog
import com.gfs.iyango.core.designsystem.liquidglass.LiquidButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidFilterChip
import com.gfs.iyango.core.designsystem.liquidglass.LiquidModalBottomSheet
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedTextField
import com.gfs.iyango.core.designsystem.liquidglass.LiquidToggle
import com.kyant.shapes.Capsule
import androidx.compose.material.icons.filled.Close
import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassTopBarIconButton
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.designsystem.theme.LocalBrand
import com.gfs.iyango.core.model.DayLabels
import com.gfs.iyango.core.model.Timetable

/**
 * 多课表管理页（契约 §6）：课表卡列表 + 新建；当前卡 accent 描边 + ✓；
 * 操作胶囊：名称与规则 / 复制 / 删除（确认弹窗列影响面）。
 */
@Composable
fun TimetableManageScreen(
    onBack: () -> Unit,
    onOpenSync: () -> Unit = {},
    onOpenCompare: () -> Unit = {},
    viewModel: TimetableManageViewModel = hiltViewModel(),
) {
    val timetables by viewModel.timetables.collectAsStateWithLifecycle()
    val pendingDelete by viewModel.pendingDelete.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var nameDialog by remember { mutableStateOf<NameDialog?>(null) }
    var rulesTarget by remember { mutableStateOf<Timetable?>(null) }

    GlassScaffold(
        topBar = {
            GlassTopBar(
                title = "我的课表",
                actions = {
                    if (timetables.size >= 2) {
                        TextButton(onClick = onOpenCompare) {
                            Text("合表找空")
                        }
                    }
                    GlassTopBarIconButton(onClick = { nameDialog = NameDialog.Create }) {
                        Icon(Icons.Filled.Add, contentDescription = "新建课表")
                    }
                },
                navigationIcon = {
                    GlassTopBarIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = innerPadding.calculateStartPadding(LocalLayoutDirection.current) + 16.dp,
                top = innerPadding.calculateTopPadding() + 16.dp,
                end = innerPadding.calculateEndPadding(LocalLayoutDirection.current) + 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "可创建多套课表并随时切换；切换后首页/课表页即时生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                LiquidButton(
                    onClick = onOpenSync,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("同步教务课表")
                }
            }
            items(timetables.size) { index ->
                val timetable = timetables[index]
                TimetableCard(
                    timetable = timetable,
                    busy = busy,
                    onSetCurrent = { viewModel.setCurrent(timetable.id) },
                    onRename = { nameDialog = NameDialog.Rename(timetable) },
                    onRules = { rulesTarget = timetable },
                    onDuplicate = { viewModel.duplicate(timetable.id) },
                    onDelete = { viewModel.requestDelete(timetable.id) },
                )
            }
        }
    }

    when (val dialog = nameDialog) {
        is NameDialog.Create -> NameInputDialog(
            title = "新建课表",
            initial = "",
            onDismiss = { nameDialog = null },
            onConfirm = { name ->
                viewModel.create(name)
                nameDialog = null
            },
        )
        is NameDialog.Rename -> NameInputDialog(
            title = "重命名课表",
            initial = dialog.timetable.name,
            onDismiss = { nameDialog = null },
            onConfirm = { name ->
                viewModel.rename(dialog.timetable.id, name)
                nameDialog = null
            },
        )
        null -> Unit
    }

    rulesTarget?.let { target ->
        TimetableRulesSheet(
            timetable = target,
            onDismiss = { rulesTarget = null },
            onSave = { updated ->
                viewModel.saveRules(updated)
                rulesTarget = null
            },
        )
    }

    pendingDelete?.let { pending ->
        LiquidAlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text("删除「${pending.timetable.name}」？", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    buildString {
                        append("将一并删除：课程 ${pending.courseCount} 条")
                        pending.semesterName?.let { append("、学期「$it」") }
                        append("、作息时间表。此操作不可恢复。")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDelete) { Text("取消") }
            },
        )
    }
}

/** 名称输入弹窗（新建/重命名共用）。 */
private sealed interface NameDialog {
    data object Create : NameDialog
    data class Rename(val timetable: Timetable) : NameDialog
}

/** 单张课表卡（§6）。 */
@Composable
private fun TimetableCard(
    timetable: Timetable,
    busy: Boolean,
    onSetCurrent: () -> Unit,
    onRename: () -> Unit,
    onRules: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val brand = LocalBrand.current
    val cardShape = RoundedCornerShape(16.dp)
    GlassSurface(
        material = GlassMaterial.CONTENT,
        shape = cardShape,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (timetable.isCurrent) Modifier.border(2.dp, brand.accent, cardShape) else Modifier,
            ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = timetable.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (timetable.isCurrent) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "当前课表",
                        tint = brand.accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Text(
                text = buildString {
                    append(if (timetable.source == "LOCAL") "本地课表" else "教务同步")
                    if (timetable.autoEnabled) append(" 分层自动切换")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!timetable.isCurrent) {
                    ActionPill(text = "设为当前", onClick = onSetCurrent)
                }
                ActionPill(text = "名称", onClick = onRename)
                ActionPill(text = "规则", onClick = onRules)
                ActionPill(text = "复制", onClick = onDuplicate)
                if (timetable.source == "LOCAL") {
                    ActionPill(
                        text = "删除",
                        color = MaterialTheme.colorScheme.error,
                        onClick = onDelete,
                    )
                }
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
    }
}

/** 操作胶囊（高 36，控件液态玻璃底座）。 */
@Composable
private fun ActionPill(
    text: String,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    GlassSurface(
        material = GlassMaterial.CONTROL,
        shape = Capsule(),
        modifier = Modifier
            .height(36.dp)
            .clip(Capsule())
            .clickable(onClick = onClick),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** 名称输入弹窗。 */
@Composable
private fun NameInputDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    LiquidAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            LiquidOutlinedTextField(
                value = value,
                onValueChange = { value = it.take(24) },
                label = { Text("课表名称") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 课表分层规则（F07）：分组名 + 自动切换开关 + 生效星期 + 轮换周期/相位。
 *
 * 规则仅在 [Timetable.autoEnabled] 时参与自动选表；同组课表按 id 顺序尝试，
 * 无命中时回退当前课表（见 `EffectiveTimetableSelector`）。
 */
@Composable
private fun TimetableRulesSheet(
    timetable: Timetable,
    onDismiss: () -> Unit,
    onSave: (Timetable) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var groupName by remember { mutableStateOf(timetable.groupName) }
    var autoEnabled by remember { mutableStateOf(timetable.autoEnabled) }
    var enabledDays by remember { mutableStateOf(timetable.enabledDays.toSet()) }
    var cycleLength by remember { mutableStateOf(timetable.cycleLength.coerceAtLeast(1).toString()) }
    var cycleWeek by remember { mutableStateOf(timetable.cycleWeek.toString()) }

    val lengthValue = cycleLength.toIntOrNull()?.coerceIn(1, 52) ?: 1
    val weekValue = cycleWeek.toIntOrNull()?.coerceIn(0, lengthValue) ?: 0
    val invalidDays = autoEnabled && enabledDays.isEmpty()

    LiquidModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column {
            GlassOverlayHeader(
                title = "分层与轮换规则",
                actions = {
                    GlassTopBarIconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭")
                    }
                },
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                LiquidOutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it.take(16) },
                    label = { Text("分组名（同组课表互斥）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("启用分层自动切换", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "按星期与轮换相位自动选用同组课表",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LiquidToggle(checked = autoEnabled, onCheckedChange = { autoEnabled = it })
                }
                Text(
                    text = "生效星期",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (invalidDays) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    (1..7).forEach { day ->
                        LiquidFilterChip(
                            selected = day in enabledDays,
                            onClick = {
                                enabledDays = if (day in enabledDays) {
                                    enabledDays - day
                                } else {
                                    enabledDays + day
                                }
                            },
                            label = { Text(DayLabels.label(day)) },
                        )
                    }
                }
                if (invalidDays) {
                    Text(
                        text = "至少选择一个生效星期",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiquidOutlinedTextField(
                        value = cycleLength,
                        onValueChange = { cycleLength = it.filter(Char::isDigit).take(2) },
                        label = { Text("轮换周期（周）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    LiquidOutlinedTextField(
                        value = cycleWeek,
                        onValueChange = { cycleWeek = it.filter(Char::isDigit).take(2) },
                        label = { Text("相位（0=不轮换）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = "相位 1 表示每 $lengthValue 周中的第 1 周生效；0 表示每周都生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    LiquidButton(
                        onClick = {
                            onSave(
                                timetable.copy(
                                    groupName = groupName.trim(),
                                    autoEnabled = autoEnabled,
                                    enabledDays = enabledDays.sorted(),
                                    cycleLength = lengthValue,
                                    cycleWeek = weekValue,
                                ),
                            )
                        },
                        enabled = !invalidDays,
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text("保存规则") }
                }
            }
        }
    }
}

/** 亮色预览。 */
@Preview(name = "我的课表 - 亮色", showBackground = true)
@Composable
private fun TimetableManageLightPreview() {
    IYangoThemePreview(darkTheme = false) {
        Surface {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TimetableCard(
                    timetable = Timetable(id = 1, name = "我的课表", isCurrent = true),
                    busy = false,
                    onSetCurrent = {},
                    onRename = {},
                    onRules = {},
                    onDuplicate = {},
                    onDelete = {},
                )
                TimetableCard(
                    timetable = Timetable(id = 2, name = "辅修课表", isCurrent = false),
                    busy = false,
                    onSetCurrent = {},
                    onRename = {},
                    onRules = {},
                    onDuplicate = {},
                    onDelete = {},
                )
            }
        }
    }
}

/** 暗色预览。 */
@Preview(name = "我的课表 - 暗色", showBackground = true)
@Composable
private fun TimetableManageDarkPreview() {
    IYangoThemePreview(darkTheme = true) {
        Surface {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TimetableCard(
                    timetable = Timetable(id = 1, name = "我的课表", isCurrent = true),
                    busy = false,
                    onSetCurrent = {},
                    onRename = {},
                    onRules = {},
                    onDuplicate = {},
                    onDelete = {},
                )
            }
        }
    }
}
