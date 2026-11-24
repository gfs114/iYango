@file:OptIn(ExperimentalMaterial3Api::class)

package com.gfs.iyango.feature.main.semester

import com.gfs.iyango.core.designsystem.component.GlassOverlayHeader
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidAlertDialog
import com.gfs.iyango.core.designsystem.liquidglass.LiquidButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidModalBottomSheet
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedTextField
import androidx.compose.material.icons.filled.Close
import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassTopBarIconButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.ui.draw.clip
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
import com.gfs.iyango.core.designsystem.component.SectionHeader
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.designsystem.theme.LocalBrand
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.TimeSlot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 学期与节次管理页（契约 §7）：学期列表卡 + 新建/编辑底部 Sheet +
 * 节次时间表（逐节编辑 + 恢复默认）。
 */
@Composable
fun SemesterManageScreen(
    onBack: () -> Unit,
    viewModel: SemesterManageViewModel = hiltViewModel(),
) {
    val semesters by viewModel.semesters.collectAsStateWithLifecycle()
    val timeSlots by viewModel.timeSlots.collectAsStateWithLifecycle()
    var semesterSheet by remember { mutableStateOf<SemesterManageViewModel.SemesterDraft?>(null) }
    var slotEditor by remember { mutableStateOf<SlotEditorState?>(null) }

    GlassScaffold(
        topBar = {
            GlassTopBar(
                title = "学期管理",
                actions = {
                    GlassTopBarIconButton(onClick = {
                        semesterSheet = SemesterManageViewModel.SemesterDraft()
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "新建学期")
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
                SectionHeader(title = "已有学期 ${semesters.size}")
            }
            items(semesters.size) { index ->
                val semester = semesters[index]
                SemesterRow(
                    semester = semester,
                    onSetCurrent = { viewModel.setCurrent(semester.id) },
                    onEdit = {
                        semesterSheet = SemesterManageViewModel.SemesterDraft(
                            id = semester.id,
                            name = semester.name,
                            startDate = semester.startDate,
                            totalWeeks = semester.totalWeeks,
                            isCurrent = semester.isCurrent,
                        )
                    },
                    onDelete = { viewModel.deleteSemester(semester.id) },
                )
            }
            item {
                SectionHeader(
                    title = "节次时间表 ${timeSlots.size} 节",
                    trailing = {
                        TextButton(onClick = viewModel::resetTimeSlots) { Text("恢复默认") }
                    },
                )
            }
            items(timeSlots.size) { index ->
                val slot = timeSlots[index]
                SlotRow(
                    slot = slot,
                    onClick = { slotEditor = SlotEditorState(index = index, slot = slot) },
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    semesterSheet?.let { draft ->
        SemesterSheet(
            draft = draft,
            onDismiss = { semesterSheet = null },
            onSave = {
                viewModel.saveSemester(it)
                semesterSheet = null
            },
        )
    }

    slotEditor?.let { editor ->
        SlotTimeDialog(
            slot = editor.slot,
            onDismiss = { slotEditor = null },
            onSave = { start, end ->
                val updated = timeSlots.mapIndexed { i, s ->
                    if (i == editor.index) s.copy(startTime = start, endTime = end) else s
                }
                viewModel.saveTimeSlots(updated)
                slotEditor = null
            },
        )
    }
}

/** 节次编辑弹层载荷。 */
private data class SlotEditorState(val index: Int, val slot: TimeSlot)

/** 学期行（§7）。 */
@Composable
private fun SemesterRow(
    semester: Semester,
    onSetCurrent: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val brand = LocalBrand.current
    GlassSurface(material = GlassMaterial.CONTENT, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(semester.name, style = MaterialTheme.typography.titleMedium)
                if (semester.isCurrent) {
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(8.dp), color = brand.brandSoft) {
                        Text(
                            "当前学期",
                            style = MaterialTheme.typography.labelSmall,
                            color = brand.accent,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "编辑", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Text(
                text = "开学 ${semester.startDate} 共 ${semester.totalWeeks} 周",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!semester.isCurrent) {
                TextButton(onClick = onSetCurrent) { Text("设为当前学期") }
            }
        }
    }
}

/** 节次行：点击编辑起止时间。 */
@Composable
private fun SlotRow(slot: TimeSlot, onClick: () -> Unit) {
    GlassSurface(
        material = GlassMaterial.CONTENT,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "第 ${slot.section} 节",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${slot.startTime} - ${slot.endTime}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 学期新建/编辑底部 Sheet（§7：名称/开学日期/总周数）。 */
@Composable
private fun SemesterSheet(
    draft: SemesterManageViewModel.SemesterDraft,
    onDismiss: () -> Unit,
    onSave: (SemesterManageViewModel.SemesterDraft) -> Unit,
) {
    var name by remember { mutableStateOf(draft.name) }
    var startDate by remember { mutableStateOf(draft.startDate) }
    var totalWeeks by remember { mutableStateOf(draft.totalWeeks.toString()) }
    var showDatePicker by remember { mutableStateOf(false) }

    LiquidModalBottomSheet(onDismissRequest = onDismiss) {
        Column {
            GlassOverlayHeader(
                title = if (draft.id > 0) "编辑学期" else "新建学期",
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
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LiquidOutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(24) },
                    label = { Text("学期名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LiquidOutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) { Text("开学日期：$startDate") }
                LiquidOutlinedTextField(
                    value = totalWeeks,
                    onValueChange = { text ->
                        totalWeeks = text.filter { it.isDigit() }.take(2)
                    },
                    label = { Text("总周数（1-30）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LiquidButton(
                    onClick = {
                        onSave(
                            draft.copy(
                                name = name,
                                startDate = startDate,
                                totalWeeks = totalWeeks.toIntOrNull()?.coerceIn(1, 30) ?: 20,
                                isCurrent = true,
                            )
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) { Text("保存") }
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = startDate.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        startDate = Instant.ofEpochMilli(millis)
                            .atZone(ZoneId.of("UTC")).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) { DatePicker(state = pickerState) }
    }
}

/** 节次起止时间编辑弹窗（起/止两个 TimePicker 分步）。 */
@Composable
private fun SlotTimeDialog(
    slot: TimeSlot,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var pickingStart by remember { mutableStateOf(true) }
    var start by remember { mutableStateOf(slot.startTime) }
    val initial = (if (pickingStart) start else slot.endTime).split(":")
    val state = rememberTimePickerState(
        initialHour = initial.getOrNull(0)?.toIntOrNull() ?: 8,
        initialMinute = initial.getOrNull(1)?.toIntOrNull() ?: 0,
        is24Hour = true,
    )
    LiquidAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (pickingStart) "第 ${slot.section} 节 开始时间" else "第 ${slot.section} 节 结束时间", style = MaterialTheme.typography.titleLarge)
        },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = {
                val text = "%02d:%02d".format(state.hour, state.minute)
                if (pickingStart) {
                    start = text
                    pickingStart = false
                } else {
                    onSave(start, text)
                }
            }) { Text(if (pickingStart) "下一步" else "保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 亮色预览。 */
@Preview(name = "学期管理 - 亮色", showBackground = true)
@Composable
private fun SemesterManageLightPreview() {
    IYangoThemePreview(darkTheme = false) {
        Surface {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SemesterRow(
                    semester = Semester(
                        id = 1,
                        name = "2026-2027-1",
                        startDate = LocalDate.of(2026, 9, 7),
                        totalWeeks = 20,
                        isCurrent = true,
                    ),
                    onSetCurrent = {},
                    onEdit = {},
                    onDelete = {},
                )
                SlotRow(
                    slot = TimeSlot(section = 1, startTime = "08:00", endTime = "08:45", name = "第1节"),
                    onClick = {},
                )
            }
        }
    }
}

/** 暗色预览。 */
@Preview(name = "学期管理 - 暗色", showBackground = true)
@Composable
private fun SemesterManageDarkPreview() {
    IYangoThemePreview(darkTheme = true) {
        Surface {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SemesterRow(
                    semester = Semester(
                        id = 1,
                        name = "2026-2027-1",
                        startDate = LocalDate.of(2026, 9, 7),
                        totalWeeks = 20,
                        isCurrent = false,
                    ),
                    onSetCurrent = {},
                    onEdit = {},
                    onDelete = {},
                )
            }
        }
    }
}
