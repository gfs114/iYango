@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.gfs.iyango.feature.main.settings

import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassTopBarIconButton
import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.gfs.iyango.core.designsystem.component.SectionCard
import com.gfs.iyango.core.designsystem.component.SettingSwitchRow
import com.gfs.iyango.core.designsystem.liquidglass.LiquidAlertDialog
import com.gfs.iyango.core.designsystem.liquidglass.LiquidFilterChip
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidRadioButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidToggle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gfs.iyango.core.domain.CalendarClearResult
import com.gfs.iyango.core.domain.GradeCheckOutcome
import com.gfs.iyango.core.domain.ReminderCourseOption
import com.gfs.iyango.core.model.AutoSilentMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 提醒与日历设置（F12/F13）。
 *
 * 两个通道的开关、授权状态与失败原因分别展示；提醒使用应用通知，日历写入系统
 * CalendarProvider，二者可独立开启，同时开启会出现两套提醒，页面明确说明。
 */
@Composable
fun ReminderCalendarScreen(
    onBack: () -> Unit,
    viewModel: ReminderCalendarViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmClearCalendar by remember { mutableStateOf(false) }
    var clearingCalendar by remember { mutableStateOf(false) }
    var checkingGrades by remember { mutableStateOf(false) }
    var refreshingHolidays by remember { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refresh() }
    val calendarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refresh() }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun requestCalendarPermission() {
        calendarLauncher.launch(
            arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
        )
    }

    LaunchedEffect(Unit) { viewModel.refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    GlassScaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            GlassTopBar(
                title = "提醒与日历",
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = "课程通知") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingSwitchRow(
                        title = "启用课程提醒",
                        checked = state.reminder.enabled,
                        onCheckedChange = { enabled ->
                            viewModel.setReminderEnabled(enabled)
                            if (enabled && !state.reminder.notificationsAllowed) {
                                requestNotificationPermission()
                            }
                        },
                    )
                    if (state.reminder.enabled) {
                        Text("提前时间", style = MaterialTheme.typography.bodyLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(5, 10, 15).forEach { minutes ->
                                LiquidFilterChip(
                                    selected = state.leadMinutes == minutes,
                                    onClick = { viewModel.setLeadMinutes(minutes) },
                                    label = { Text("$minutes 分钟") },
                                )
                            }
                        }
                        StatusRow(
                            title = "通知权限",
                            value = if (state.reminder.notificationsAllowed) "已授权" else "未授权",
                            action = if (state.reminder.notificationsAllowed) {
                                null
                            } else {
                                "去授权" to { requestNotificationPermission() }
                            },
                        )
                        StatusRow(
                            title = "精确提醒",
                            value = if (state.reminder.exactAlarmsAllowed) "可用" else "受限，提醒可能延迟",
                            action = if (state.reminder.exactAlarmsAllowed) {
                                null
                            } else {
                                "去设置" to { openExactAlarmSettings(context) }
                            },
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
                            !state.reminder.liveUpdatesAllowed
                        ) {
                            StatusRow(
                                title = "实时通知",
                                value = "实时通知已关闭，状态栏不会显示上课倒计时",
                                action = "去设置" to { openLiveUpdateSettings(context) },
                            )
                        }
                        Text(
                            "已排程 ${state.reminder.scheduledCount} / 计划 ${state.reminder.plannedCount}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        SettingSwitchRow(
                            title = "上课实时进度常驻通知",
                            checked = state.ongoingProgressEnabled,
                            onCheckedChange = viewModel::setOngoingProgressEnabled,
                        )
                        SettingSwitchRow(
                            title = "法定节假日跳过提醒",
                            checked = state.skipRemindersOnHolidays,
                            onCheckedChange = viewModel::setSkipRemindersOnHolidays,
                        )
                        LiquidOutlinedButton(
                            onClick = {
                                if (!state.reminder.notificationsAllowed) {
                                    requestNotificationPermission()
                                } else {
                                    scope.launch {
                                        val sent = viewModel.sendLiveProgressPreview()
                                        snackbarHostState.currentSnackbarData?.dismiss()
                                        snackbarHostState.showSnackbar(
                                            if (sent) "已发送上课实时进度常驻预览到通知栏" else "通知权限未就绪，请先授权通知",
                                        )
                                    }
                                }
                            },
                        ) {
                            Text("发送进度通知预览")
                        }
                        IssueList(
                            title = "未排程原因",
                            items = state.reminder.skipped,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IssueList(
                            title = "排程失败",
                            items = state.reminder.failures,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (state.reminder.courses.isNotEmpty()) {
                            HorizontalDivider()
                            Text("单课程开关", style = MaterialTheme.typography.bodyLarge)
                            state.reminder.courses.forEach { course ->
                                CourseSwitchRow(
                                    course = course,
                                    onCheckedChange = { enabled ->
                                        viewModel.setCourseEnabled(course.key, enabled)
                                    },
                                )
                            }
                        }
                    }
                }
            }

            SectionCard(title = "系统日历") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingSwitchRow(
                        title = "同步到系统日历",
                        checked = state.calendar.enabled,
                        onCheckedChange = { enabled ->
                            viewModel.setCalendarEnabled(enabled)
                            if (enabled && !state.calendar.permissionGranted) {
                                requestCalendarPermission()
                            }
                        },
                    )
                    if (state.calendar.enabled) {
                        StatusRow(
                            title = "日历权限",
                            value = if (state.calendar.permissionGranted) "已授权" else "未授权或已撤销",
                            action = if (state.calendar.permissionGranted) {
                                null
                            } else {
                                "去授权" to { requestCalendarPermission() }
                            },
                        )
                        if (state.calendar.permissionGranted) {
                            Text("写入日历", style = MaterialTheme.typography.bodyLarge)
                            if (state.calendar.calendars.isEmpty()) {
                                Text(
                                    "没有可写日历，请先在系统日历中添加账号",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            state.calendar.calendars.forEach { calendar ->
                                val selected = state.calendar.selectedCalendarId == calendar.id
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .selectable(selected = selected) {
                                            viewModel.selectCalendar(calendar.id)
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    LiquidRadioButton(
                                        selected = selected,
                                        onClick = null,
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(calendar.name, style = MaterialTheme.typography.bodyMedium)
                                        if (calendar.accountName.isNotBlank()) {
                                            Text(
                                                calendar.accountName,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    if (calendar.isPrimary) {
                                        Text(
                                            "默认",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            if (state.calendar.selectedCalendarId <= 0) {
                                Text(
                                    "请选择目标日历",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                "已同步 ${state.calendar.syncedCount} 条" +
                                    if (state.calendar.pendingCleanup > 0) {
                                        " 待清理 ${state.calendar.pendingCleanup} 条"
                                    } else {
                                        ""
                                    },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (state.calendar.lastError.isNotBlank()) {
                                Text(
                                    state.calendar.lastError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            IssueList(
                                title = "未写入原因",
                                items = state.calendar.skipped,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    LiquidOutlinedButton(
                        onClick = {
                            if (!state.calendar.permissionGranted) {
                                requestCalendarPermission()
                            } else {
                                confirmClearCalendar = true
                            }
                        },
                        enabled = !clearingCalendar,
                    ) {
                        Text(
                            if (clearingCalendar) "清除中…" else "清除系统日历",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            SectionCard(title = "新出分通知") {
                val grade = state.gradeMonitor
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingSwitchRow(
                        title = "新出分变动通知",
                        checked = grade.enabled,
                        onCheckedChange = { enabled ->
                            viewModel.setGradeNotifyEnabled(enabled)
                            if (enabled && !grade.notificationsAllowed) {
                                requestNotificationPermission()
                            }
                        },
                    )
                    if (grade.enabled) {
                        Text("检查频率", style = MaterialTheme.typography.bodyLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(2, 4, 6, 12).forEach { hours ->
                                LiquidFilterChip(
                                    selected = grade.intervalHours == hours,
                                    onClick = { viewModel.setGradeCheckIntervalHours(hours) },
                                    label = { Text("$hours 小时") },
                                )
                            }
                        }
                        SettingSwitchRow(
                            title = "期末出分冲刺",
                            checked = grade.sprintActive,
                            onCheckedChange = viewModel::setGradeSprintEnabled,
                        )
                        if (grade.sprintActive) {
                            Text("冲刺频率", style = MaterialTheme.typography.bodyLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(15, 30).forEach { minutes ->
                                    LiquidFilterChip(
                                        selected = grade.sprintIntervalMinutes == minutes,
                                        onClick = { viewModel.setGradeSprintIntervalMinutes(minutes) },
                                        label = { Text("$minutes 分钟") },
                                    )
                                }
                            }
                            Text("自动恢复周期", style = MaterialTheme.typography.bodyLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(3, 5, 7).forEach { days ->
                                    LiquidFilterChip(
                                        selected = grade.sprintDurationDays == days,
                                        onClick = { viewModel.setGradeSprintDurationDays(days) },
                                        label = { Text("$days 天") },
                                    )
                                }
                            }
                            if (grade.sprintUntilMillis > 0L) {
                                val untilText = Instant.ofEpochMilli(grade.sprintUntilMillis)
                                    .atZone(ZoneId.systemDefault())
                                    .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                                Text(
                                    "冲刺至 $untilText",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (grade.activeSprintSemester.isNotBlank()) {
                                Text(
                                    "仅查 ${grade.activeSprintSemester}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        SettingSwitchRow(
                            title = "Wi-Fi 与充电顺手检查",
                            checked = grade.contextTriggerEnabled,
                            onCheckedChange = viewModel::setGradeContextTriggerEnabled,
                        )
                        SettingSwitchRow(
                            title = "锁屏隐藏具体分数",
                            checked = grade.hideScoreOnLockscreen,
                            onCheckedChange = viewModel::setGradeNotifyHideScoreOnLockscreen,
                        )
                        StatusRow(
                            title = "通知权限",
                            value = if (grade.notificationsAllowed) "已授权" else "未授权",
                            action = if (grade.notificationsAllowed) {
                                null
                            } else {
                                "去授权" to { requestNotificationPermission() }
                            },
                        )
                        if (grade.sessionExpiredPaused) {
                            Text(
                                "教务登录已失效，已暂停后台检查",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else if (!grade.hasAccount) {
                            Text(
                                "尚未绑定教务账号",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (grade.lastCheckAt > 0L) {
                            val checkTime = Instant.ofEpochMilli(grade.lastCheckAt)
                                .atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                            Text(
                                "最近检查 $checkTime",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (grade.lastError.isNotBlank() && !grade.sessionExpiredPaused) {
                            Text(
                                grade.lastError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        LiquidOutlinedButton(
                            enabled = !checkingGrades,
                            onClick = {
                                checkingGrades = true
                                scope.launch {
                                    try {
                                        val msg = when (viewModel.checkGradesNow()) {
                                            GradeCheckOutcome.NOTIFIED -> "已发现并推送新成绩"
                                            GradeCheckOutcome.CHECKED_NO_CHANGES -> "检查完成，暂无新成绩"
                                            GradeCheckOutcome.SKIPPED_NO_ACCOUNT -> "请先绑定教务账号"
                                            GradeCheckOutcome.SKIPPED_NO_NETWORK -> "当前无可用网络连接"
                                            GradeCheckOutcome.PAUSED_SESSION_EXPIRED -> "教务登录已失效，请重新登录"
                                            else -> "检查失败，请稍后重试"
                                        }
                                        snackbarHostState.currentSnackbarData?.dismiss()
                                        snackbarHostState.showSnackbar(msg)
                                    } finally {
                                        checkingGrades = false
                                    }
                                }
                            },
                        ) {
                            Text(if (checkingGrades) "检查中…" else "立即检查")
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
                        !grade.liveUpdatesAllowed &&
                        grade.enabled
                    ) {
                        StatusRow(
                            title = "实时通知",
                            value = "未开启，不会显示在灵动胶囊",
                            action = "去设置" to { openLiveUpdateSettings(context) },
                        )
                    }
                }
            }

            SectionCard(title = "上课自动静音") {
                val silent = state.autoSilent
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            AutoSilentMode.OFF to "关闭",
                            AutoSilentMode.VIBRATE to "振动",
                            AutoSilentMode.SILENT to "静音",
                            AutoSilentMode.DND to "勿扰",
                        ).forEach { (mode, label) ->
                            LiquidFilterChip(
                                selected = silent.mode == mode,
                                onClick = { viewModel.setAutoSilentMode(mode) },
                                label = { Text(label) },
                            )
                        }
                    }
                    if (silent.mode != AutoSilentMode.OFF) {
                        if (silent.mode == AutoSilentMode.SILENT || silent.mode == AutoSilentMode.DND) {
                            StatusRow(
                                title = "勿扰权限",
                                value = if (silent.policyAccessGranted) "已授权" else "未授权（将降级为振动）",
                                action = if (silent.policyAccessGranted) {
                                    null
                                } else {
                                    "去授权" to { openPolicyAccessSettings(context) }
                                },
                            )
                        }
                        if (!state.reminder.exactAlarmsAllowed) {
                            StatusRow(
                                title = "精确闹钟",
                                value = "受限，切换时间可能延迟",
                                action = "去设置" to { openExactAlarmSettings(context) },
                            )
                        }
                        SettingSwitchRow(
                            title = "法定节假日跳过静音",
                            checked = silent.skipOnHolidays,
                            onCheckedChange = viewModel::setAutoSilentSkipOnHolidays,
                        )
                        Text(
                            text = if (silent.activeCourseName != null) {
                                "当前生效 ${silent.activeCourseName}"
                            } else {
                                "已排程 ${silent.scheduledCount} 节"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        LiquidOutlinedButton(
                            onClick = {
                                viewModel.restoreAutoSilentNow()
                                scope.launch {
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    snackbarHostState.showSnackbar("已恢复铃声状态")
                                }
                            },
                        ) {
                            Text("立即恢复铃声")
                        }
                        if (state.reminder.courses.isNotEmpty()) {
                            HorizontalDivider()
                            Text("免静音课程", style = MaterialTheme.typography.titleSmall)
                            state.reminder.courses.forEach { course ->
                                val exempt = course.key in silent.exemptCourseKeys
                                CourseSwitchRow(
                                    course = course.copy(enabled = exempt),
                                    onCheckedChange = { checked ->
                                        viewModel.setAutoSilentCourseExempt(course.key, checked)
                                    },
                                )
                            }
                        }
                    }
                }
            }

            SectionCard(title = "节假日与调休") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LiquidOutlinedButton(
                        enabled = !refreshingHolidays,
                        onClick = {
                            refreshingHolidays = true
                            scope.launch {
                                try {
                                    val ok = viewModel.refreshHolidays()
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    snackbarHostState.showSnackbar(
                                        if (ok) "节假日数据已更新" else "离线内置数据已就绪，在线更新失败",
                                    )
                                } finally {
                                    refreshingHolidays = false
                                }
                            }
                        },
                    ) {
                        Text(if (refreshingHolidays) "更新中…" else "更新节假日数据")
                    }
                }
            }
        }
    }

    if (confirmClearCalendar) {
        LiquidAlertDialog(
            onDismissRequest = { if (!clearingCalendar) confirmClearCalendar = false },
            title = { Text("清除系统日历？", style = MaterialTheme.typography.titleLarge) },
            text = { Text("将删除本应用写入的全部课程日程并关闭同步，不影响其他日历事件。") },
            confirmButton = {
                TextButton(
                    enabled = !clearingCalendar,
                    onClick = {
                        confirmClearCalendar = false
                        clearingCalendar = true
                        scope.launch {
                            try {
                                val message = when (val result = viewModel.clearCalendar()) {
                                    CalendarClearResult.PermissionDenied -> "缺少日历读写权限，请先授权"
                                    is CalendarClearResult.Completed -> when {
                                        result.pendingCount > 0 ->
                                            "已清除 ${result.deletedCount} 条，另有 ${result.pendingCount} 条待清理"
                                        result.deletedCount > 0 ->
                                            "已清除 ${result.deletedCount} 条课程日程"
                                        else ->
                                            "系统日历中没有本应用写入的日程"
                                    }
                                }
                                snackbarHostState.currentSnackbarData?.dismiss()
                                snackbarHostState.showSnackbar(message)
                            } finally {
                                clearingCalendar = false
                            }
                        }
                    },
                ) {
                    Text("清除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearCalendar = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun StatusRow(
    title: String,
    value: String,
    action: Pair<String, () -> Unit>?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                value,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (action != null) {
            TextButton(onClick = action.second) { Text(action.first) }
        }
    }
}

@Composable
private fun IssueList(title: String, items: List<String>, color: androidx.compose.ui.graphics.Color) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = color)
        items.take(6).forEach { item ->
            Text("$item", style = MaterialTheme.typography.labelSmall, color = color)
        }
        if (items.size > 6) {
            Text(
                "…另有 ${items.size - 6} 条",
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}

@Composable
private fun CourseSwitchRow(course: ReminderCourseOption, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { onCheckedChange(!course.enabled) },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(course.name, style = MaterialTheme.typography.bodyMedium)
            if (course.teacher.isNotBlank()) {
                Text(
                    course.teacher,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LiquidToggle(
            checked = course.enabled,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = course.name },
        )
    }
}

private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
            },
        )
    }
}

private fun openPolicyAccessSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }
}

/** 跳转实时通知（Live Update）授权设置；目标页不存在时回退到应用通知设置。 */
private fun openLiveUpdateSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return
    val opened = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            },
        )
    }.isSuccess
    if (!opened) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                },
            )
        }
    }
}
