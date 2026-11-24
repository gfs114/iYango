@file:OptIn(ExperimentalMaterial3Api::class)

package com.gfs.iyango.feature.main.exam

import com.gfs.iyango.core.designsystem.component.GlassOverlayHeader
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidAlertDialog
import com.gfs.iyango.core.designsystem.liquidglass.LiquidButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidFilterChip
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gfs.iyango.core.designsystem.component.EmptyState
import com.gfs.iyango.core.designsystem.component.RefreshNoticeCapsule
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.designsystem.theme.LocalBrand
import com.gfs.iyango.core.domain.ExamCountdown
import com.gfs.iyango.core.domain.ExamPhase
import com.gfs.iyango.core.model.Exam
import com.gfs.iyango.core.model.ExamSource
import com.gfs.iyango.core.model.ExamStatus
import com.gfs.iyango.core.model.ExamValidator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 考试安排页（E01）：近期/全部/已结束三类列表 + 手动新增/编辑/删除。
 *
 * 学校考试导入在数据源核实前不启用；本页只管理本地考试与个人安排。
 */
@Composable
fun ExamListScreen(
    onBack: () -> Unit,
    viewModel: ExamListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val refreshNotice by viewModel.refreshNotice.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<Exam?>(null) }
    val pullState = rememberPullToRefreshState()

    LaunchedEffect(state.message) {
        if (state.message.isNotEmpty()) {
            snackbar.showSnackbar(state.message)
            viewModel.clearMessage()
        }
    }

    GlassScaffold(
        topBar = {
            GlassTopBar(
                title = "考试安排",
                navigationIcon = {
                    GlassTopBarIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    GlassTopBarIconButton(onClick = { editing = newExam(state.now.toLocalDate()) }) {
                        Icon(Icons.Filled.Add, contentDescription = "新增考试")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        overlay = { innerPadding ->
            RefreshNoticeCapsule(
                message = refreshNotice,
                onDismiss = viewModel::consumeRefreshNotice,
                topPadding = innerPadding.calculateTopPadding(),
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = viewModel::refresh,
            state = pullState,
            modifier = Modifier
                .fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = refreshing,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = innerPadding.calculateTopPadding()),
                )
            },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = innerPadding.calculateStartPadding(LocalLayoutDirection.current) + 16.dp,
                    top = innerPadding.calculateTopPadding(),
                    end = innerPadding.calculateEndPadding(LocalLayoutDirection.current) + 16.dp,
                    bottom = innerPadding.calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExamFilter.entries.forEach { filter ->
                            LiquidFilterChip(
                                selected = state.filter == filter,
                                onClick = { viewModel.setFilter(filter) },
                                label = { Text(filter.label) },
                            )
                        }
                    }
                }
                if (state.exams.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Filled.Add,
                            title = if (state.filter == ExamFilter.ENDED) "暂无已结束考试" else "暂无考试安排",
                            hint = "点右上角添加四六级、资格考试等个人安排",
                            modifier = Modifier.fillParentMaxHeight(0.75f),
                        )
                    }
                } else {
                    items(items = state.exams, key = { it.id }) { exam ->
                        ExamCard(
                            exam = exam,
                            countdown = ExamCountdown.label(exam, state.now),
                            phase = ExamCountdown.phase(exam, state.now),
                            onClick = { editing = exam },
                        )
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }

    editing?.let { draft ->
        ExamEditorSheet(
            initial = draft,
            isNew = draft.id == 0L,
            onDismiss = { editing = null },
            onSave = {
                viewModel.save(it)
                editing = null
            },
            onDelete = if (draft.id > 0) {
                {
                    viewModel.delete(draft.id)
                    editing = null
                }
            } else {
                null
            },
        )
    }
}

/** 考试卡。 */
@Composable
private fun ExamCard(
    exam: Exam,
    countdown: String,
    phase: ExamPhase,
    onClick: () -> Unit,
) {
    val brand = LocalBrand.current
    val chipColor = when (phase) {
        ExamPhase.UPCOMING -> brand.accent
        ExamPhase.IN_PROGRESS -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val chipBackground = when (phase) {
        ExamPhase.UPCOMING -> brand.brandSoft
        ExamPhase.IN_PROGRESS -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    GlassSurface(
        material = GlassMaterial.CONTENT,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = exam.subject,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (exam.type.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = exam.type,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = examScheduleText(exam),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = buildString {
                        append("地点：")
                        append(exam.location.ifBlank { "待定" })
                        append(" 座位：")
                        append(exam.seat.ifBlank { "待定" })
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (exam.source == ExamSource.SCHOOL) {
                    Text(
                        text = "学校考试",
                        style = MaterialTheme.typography.labelSmall,
                        color = brand.accent,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Surface(shape = RoundedCornerShape(999.dp), color = chipBackground) {
                Text(
                    text = countdown,
                    style = MaterialTheme.typography.labelMedium,
                    color = chipColor,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** 新建考试默认值：只填今天日期，时刻与地点保持待定。 */
private fun newExam(today: LocalDate) = Exam(
    source = ExamSource.MANUAL,
    date = today.toString(),
    status = ExamStatus.SCHEDULED,
)

private enum class DateTarget { START, END }

private enum class TimeTarget { START, END }

/** 新建/编辑考试底部 Sheet。 */
@Composable
private fun ExamEditorSheet(
    initial: Exam,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (Exam) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var dateTarget by remember { mutableStateOf<DateTarget?>(null) }
    var timeTarget by remember { mutableStateOf<TimeTarget?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LiquidModalBottomSheet(onDismissRequest = onDismiss) {
        Column {
            GlassOverlayHeader(
                title = if (isNew) "新建考试" else "编辑考试",
                actions = {
                    GlassTopBarIconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭")
                    }
                },
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LiquidOutlinedTextField(
                    value = draft.subject,
                    onValueChange = { draft = draft.copy(subject = it.take(40)) },
                    label = { Text("考试科目 *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LiquidOutlinedTextField(
                    value = draft.type,
                    onValueChange = { draft = draft.copy(type = it.take(20)) },
                    label = { Text("类型（期末/四六级/资格证等）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiquidOutlinedButton(
                        onClick = { dateTarget = DateTarget.START },
                        modifier = Modifier.weight(1f),
                    ) { Text("日期：${draft.date.ifBlank { "未设置" }}") }
                    LiquidOutlinedButton(
                        onClick = { dateTarget = DateTarget.END },
                        modifier = Modifier.weight(1f),
                    ) { Text("结束：${draft.endDate.ifBlank { "同日" }}") }
                }
                if (draft.endDate.isNotBlank()) {
                    TextButton(onClick = { draft = draft.copy(endDate = "") }) { Text("清除跨日结束日期") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiquidOutlinedButton(
                        onClick = { timeTarget = TimeTarget.START },
                        modifier = Modifier.weight(1f),
                    ) { Text("开始：${draft.startTime.ifBlank { "待定" }}") }
                    LiquidOutlinedButton(
                        onClick = { timeTarget = TimeTarget.END },
                        modifier = Modifier.weight(1f),
                    ) { Text("结束：${draft.endTime.ifBlank { "待定" }}") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { draft = draft.copy(startTime = "") }) { Text("开始待定") }
                    TextButton(onClick = { draft = draft.copy(endTime = "") }) { Text("结束待定") }
                }
                LiquidOutlinedTextField(
                    value = draft.location,
                    onValueChange = { draft = draft.copy(location = it.take(40)) },
                    label = { Text("地点（未公布留空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LiquidOutlinedTextField(
                    value = draft.seat,
                    onValueChange = { draft = draft.copy(seat = it.take(20)) },
                    label = { Text("座位号（未公布留空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LiquidOutlinedTextField(
                    value = draft.note,
                    onValueChange = { draft = draft.copy(note = it.take(300)) },
                    label = { Text("备注") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                LiquidButton(
                    onClick = {
                        val problem = ExamValidator.validate(draft)
                        if (problem != null) {
                            error = problem
                        } else {
                            onSave(draft.copy(updatedAt = System.currentTimeMillis()))
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) { Text("保存") }
                if (onDelete != null) {
                    TextButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("删除考试", color = MaterialTheme.colorScheme.error)
                    }
                }
                Text(
                    text = "时刻未知时可只填日期；倒计时按自然日计算，不会虚构开考时间。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    dateTarget?.let { target ->
        val current = when (target) {
            DateTarget.START -> draft.date
            DateTarget.END -> draft.endDate
        }
        val state = rememberDatePickerState(
            initialSelectedDateMillis = current.toDateMillisOrNull(),
        )
        DatePickerDialog(
            onDismissRequest = { dateTarget = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val picked = Instant.ofEpochMilli(millis)
                            .atZone(ZoneId.of("UTC")).toLocalDate().toString()
                        draft = when (target) {
                            DateTarget.START -> draft.copy(date = picked)
                            DateTarget.END -> draft.copy(endDate = picked)
                        }
                    }
                    dateTarget = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { dateTarget = null }) { Text("取消") }
            },
        ) { DatePicker(state = state) }
    }

    timeTarget?.let { target ->
        val current = when (target) {
            TimeTarget.START -> draft.startTime
            TimeTarget.END -> draft.endTime
        }
        val parts = current.split(":")
        val state = rememberTimePickerState(
            initialHour = parts.getOrNull(0)?.toIntOrNull() ?: 9,
            initialMinute = parts.getOrNull(1)?.toIntOrNull() ?: 0,
            is24Hour = true,
        )
        LiquidAlertDialog(
            onDismissRequest = { timeTarget = null },
            title = { Text(if (target == TimeTarget.START) "开始时间" else "结束时间", style = MaterialTheme.typography.titleLarge) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    val text = "%02d:%02d".format(state.hour, state.minute)
                    draft = when (target) {
                        TimeTarget.START -> draft.copy(startTime = text)
                        TimeTarget.END -> draft.copy(endTime = text)
                    }
                    timeTarget = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { timeTarget = null }) { Text("取消") }
            },
        )
    }

    if (confirmDelete && onDelete != null) {
        LiquidAlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除「${draft.subject}」？", style = MaterialTheme.typography.titleLarge) },
            text = { Text("删除后不可恢复，学校同步记录不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

/** 卡片上的日期与时刻摘要。 */
private fun examScheduleText(exam: Exam): String {
    val span = if (exam.endDate.isNotBlank() && exam.endDate != exam.date) {
        "${exam.date} ~ ${exam.endDate}"
    } else {
        exam.date.ifBlank { "日期待定" }
    }
    val times = when {
        exam.startTime.isNotBlank() && exam.endTime.isNotBlank() -> "${exam.startTime}-${exam.endTime}"
        exam.startTime.isNotBlank() -> "${exam.startTime} 开始"
        else -> "时间待定"
    }
    return "$span $times"
}

private fun String.toDateMillisOrNull(): Long? = runCatching {
    LocalDate.parse(this).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
}.getOrNull()

/** 亮色预览。 */
@Preview(name = "考试安排 - 亮色", showBackground = true)
@Composable
private fun ExamListLightPreview() {
    IYangoThemePreview(darkTheme = false) {
        Surface {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ExamCard(
                    exam = Exam(
                        id = 1,
                        subject = "大学英语四级",
                        type = "等级考试",
                        date = LocalDate.now().plusDays(12).toString(),
                        startTime = "09:00",
                        endTime = "11:20",
                        location = "教学楼 A301",
                        seat = "A-12",
                    ),
                    countdown = "12 天后",
                    phase = ExamPhase.UPCOMING,
                    onClick = {},
                )
                ExamCard(
                    exam = Exam(
                        id = 2,
                        subject = "高等数学",
                        type = "期末考试",
                        date = LocalDate.now().minusDays(2).toString(),
                    ),
                    countdown = "已结束",
                    phase = ExamPhase.ENDED,
                    onClick = {},
                )
            }
        }
    }
}

/** 暗色预览。 */
@Preview(name = "考试安排 - 暗色", showBackground = true)
@Composable
private fun ExamListDarkPreview() {
    IYangoThemePreview(darkTheme = true) {
        Surface {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ExamCard(
                    exam = Exam(
                        id = 3,
                        subject = "数据结构",
                        type = "期末考试",
                        date = LocalDate.now().toString(),
                        startTime = "14:00",
                        endTime = "16:00",
                        location = "B204",
                    ),
                    countdown = "进行中",
                    phase = ExamPhase.IN_PROGRESS,
                    onClick = {},
                )
            }
        }
    }
}
