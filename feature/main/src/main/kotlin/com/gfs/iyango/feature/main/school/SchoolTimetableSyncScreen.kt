@file:OptIn(ExperimentalMaterial3Api::class)

package com.gfs.iyango.feature.main.school

import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassTopBarIconButton
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import com.gfs.iyango.core.designsystem.liquidglass.LiquidButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidCheckbox
import com.gfs.iyango.core.designsystem.liquidglass.LiquidFilterChip
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gfs.iyango.core.designsystem.component.SectionCard
import com.gfs.iyango.core.domain.SchoolSyncArrangement
import com.gfs.iyango.core.domain.SchoolSyncGroup
import com.gfs.iyango.core.domain.SchoolSyncPreview
import com.gfs.iyango.core.model.SchoolCourseData
import com.gfs.iyango.core.model.WeekType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 教务课表同步页（F02 / E02）。
 *
 * 手动流程：选择学期 → 获取并预览课程组/具体安排 → 校对开学日期与总周数 →
 * 勾选要写入的安排 → 确认写入。提交遇到预览后本地变化时不做覆盖，要求重新获取。
 */
@Composable
fun SchoolTimetableSyncScreen(
    onBack: () -> Unit,
    onOpenHistory: () -> Unit = {},
    viewModel: SchoolTimetableSyncViewModel = hiltViewModel(),
) {
    val connection by viewModel.activeConnection.collectAsStateWithLifecycle()
    val terms by viewModel.terms.collectAsStateWithLifecycle()
    val selectedTerm by viewModel.selectedTerm.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val selectedKeys by viewModel.selectedRowKeys.collectAsStateWithLifecycle()
    val startDate by viewModel.startDate.collectAsStateWithLifecycle()
    val totalWeeks by viewModel.totalWeeks.collectAsStateWithLifecycle()
    val loadingTerms by viewModel.loadingTerms.collectAsStateWithLifecycle()
    val loadingPreview by viewModel.loadingPreview.collectAsStateWithLifecycle()
    val committing by viewModel.committing.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()

    var showDatePicker by remember { mutableStateOf(false) }

    LaunchedEffect(connection?.id) {
        viewModel.onConnectionAvailable(connection?.id)
    }

    GlassScaffold(
        topBar = {
            GlassTopBar(
                title = "同步教务课表",
                navigationIcon = {
                    GlassTopBarIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
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
            if (connection == null) {
                SectionCard(title = "需要教务账号") {
                    Text(
                        "请先在教务账号页登录，再同步课表。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                SectionCard(
                    title = "选择学期",
                    action = {
                        if (!loadingTerms && !loadingPreview && !committing) {
                            TextButton(onClick = viewModel::refreshTerms) { Text("刷新学期") }
                        }
                    },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (loadingTerms) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(10.dp))
                                Text("正在读取学校学期…", style = MaterialTheme.typography.bodyMedium)
                            }
                        } else if (terms.isEmpty()) {
                            Text(
                                "暂无可用学期，请刷新学期列表或重新登录。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                terms.forEach { term ->
                                    LiquidFilterChip(
                                        selected = term.value == selectedTerm,
                                        onClick = { viewModel.selectTerm(term.value) },
                                        enabled = !loadingPreview && !committing,
                                        label = { Text(term.label.ifBlank { term.value }) },
                                    )
                                }
                            }
                        }
                        LiquidOutlinedButton(
                            onClick = viewModel::fetchPreview,
                            enabled = selectedTerm.isNotEmpty() && !loadingPreview && !committing,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (loadingPreview) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.size(8.dp))
                                Text("正在读取完整课表…")
                            } else {
                                Text("获取课表并预览")
                            }
                        }
                    }
                }

                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                preview?.let { ready ->
                    PreviewSummaryCard(ready)
                    SemesterDraftCard(
                        startDate = startDate,
                        totalWeeks = totalWeeks,
                        onStartDateChange = viewModel::setStartDate,
                        onTotalWeeksChange = viewModel::setTotalWeeks,
                        onPickDate = { showDatePicker = true },
                        enabled = !committing,
                    )
                    ArrangementSelectionCard(
                        preview = ready,
                        selectedKeys = selectedKeys,
                        enabled = !committing,
                        onToggleGroup = viewModel::toggleGroup,
                        onToggleArrangement = viewModel::toggleArrangement,
                    )
                    LiquidButton(
                        onClick = viewModel::commit,
                        enabled = selectedKeys.isNotEmpty() && !committing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (committing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.size(8.dp))
                            Text("正在写入…")
                        } else {
                            Text("确认写入课表（已选 ${selectedKeys.size}/${ready.courses.size}）")
                        }
                    }
                    Text(
                        "写入后本学期课表将成为当前课表；本机新增课程与单次调整不会被覆盖，" +
                            "无法匹配但被调整引用的学校安排会保留并停用。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                summary?.let { done ->
                    SectionCard(
                        title = "同步完成",
                        action = {
                            TextButton(onClick = onOpenHistory) { Text("查看记录") }
                        },
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "目标课表：${done.timetableName}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                if (done.firstBaseline) {
                                    "首次同步已建立学校课表基线。"
                                } else {
                                    "更新 ${done.modified} 条，新增 ${done.added} 条，移除 ${done.removed} 条。"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (done.disabled > 0 || done.localKept > 0) {
                                Text(
                                    "停用 ${done.disabled} 条，保留本机课程 ${done.localKept} 条。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (done.warnings.isNotEmpty()) {
                                done.warnings.forEach { warning ->
                                    Text(
                                        "$warning",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = runCatching {
                LocalDate.parse(startDate).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
            }.getOrNull(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            viewModel.setStartDate(
                                Instant.ofEpochMilli(millis)
                                    .atZone(ZoneId.of("UTC")).toLocalDate().toString(),
                            )
                        }
                        showDatePicker = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun PreviewSummaryCard(preview: SchoolSyncPreview) {
    SectionCard(title = "同步预览") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = when {
                    preview.target.adopted -> "将接管同账号课表「${preview.target.name}」"
                    preview.target.timetableId == 0L -> "将新建课表「${preview.target.name}」"
                    else -> "将更新课表「${preview.target.name}」"
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "${preview.termLabel} ${preview.courses.size} 条安排 ${preview.groups.size} 门课程",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (preview.officialTimes) {
                    "已匹配学校官方作息，课程开始/结束时间按校区写入。"
                } else {
                    "未识别该学期官方作息，课程时间以节次为准。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            preview.warnings.forEach { warning ->
                Text(
                    "$warning",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SemesterDraftCard(
    startDate: String,
    totalWeeks: String,
    onStartDateChange: (String) -> Unit,
    onTotalWeeksChange: (String) -> Unit,
    onPickDate: () -> Unit,
    enabled: Boolean,
) {
    SectionCard(title = "开学日期与总周数") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LiquidOutlinedTextField(
                value = startDate,
                onValueChange = onStartDateChange,
                label = { Text("第 1 周周一（yyyy-MM-dd）") },
                singleLine = true,
                enabled = enabled,
                trailingIcon = {
                    TextButton(onClick = onPickDate, enabled = enabled) { Text("选择") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            LiquidOutlinedTextField(
                value = totalWeeks,
                onValueChange = onTotalWeeksChange,
                label = { Text("总周数（1–30）") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ArrangementSelectionCard(
    preview: SchoolSyncPreview,
    selectedKeys: Set<String>,
    enabled: Boolean,
    onToggleGroup: (SchoolSyncGroup) -> Unit,
    onToggleArrangement: (String) -> Unit,
) {
    SectionCard(
        title = "课程安排",
        action = {
            Text(
                "已选 ${selectedKeys.size}/${preview.courses.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            preview.groups.forEachIndexed { index, group ->
                if (index > 0) HorizontalDivider()
                val groupSelected = group.arrangements.all { it.rowKey in selectedKeys }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LiquidCheckbox(
                        checked = groupSelected,
                        onCheckedChange = { onToggleGroup(group) },
                        enabled = enabled,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(group.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            listOf(group.teacher, group.classroom)
                                .filter { it.isNotEmpty() }
                                .joinToString(" "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                group.arrangements.forEach { arrangement ->
                    ArrangementRow(
                        arrangement = arrangement,
                        checked = arrangement.rowKey in selectedKeys,
                        enabled = enabled,
                        onToggle = { onToggleArrangement(arrangement.rowKey) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ArrangementRow(
    arrangement: SchoolSyncArrangement,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val course = arrangement.course
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiquidCheckbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(
                "${dayLabel(course.dayOfWeek)} 第 ${course.startSection}-${course.endSection} 节",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                listOfNotNull(
                    weekLabel(course),
                    course.classroom.takeIf { it.isNotEmpty() },
                    timeLabel(course),
                ).joinToString(" "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun dayLabel(day: Int): String = when (day) {
    1 -> "周一"
    2 -> "周二"
    3 -> "周三"
    4 -> "周四"
    5 -> "周五"
    6 -> "周六"
    7 -> "周日"
    else -> "周$day"
}

private fun weekLabel(course: SchoolCourseData): String {
    val suffix = when (course.weekType) {
        WeekType.ODD -> "单周"
        WeekType.EVEN -> "双周"
        WeekType.EVERY -> "周"
    }
    return "${course.startWeek}-${course.endWeek}$suffix"
}

private fun timeLabel(course: SchoolCourseData): String = when {
    course.startTime.isNotEmpty() && course.endTime.isNotEmpty() ->
        "${course.startTime}-${course.endTime}"
    course.timeUnconfirmed -> "时间待确认"
    else -> ""
}
