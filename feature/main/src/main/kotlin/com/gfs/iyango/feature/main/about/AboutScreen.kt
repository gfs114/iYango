@file:OptIn(ExperimentalMaterial3Api::class)

package com.gfs.iyango.feature.main.about

import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassTopBarIconButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidAlertDialog
import com.gfs.iyango.core.designsystem.liquidglass.LiquidButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedTextField
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import com.gfs.iyango.core.designsystem.motion.AppMotion
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gfs.iyango.core.designsystem.component.SectionCard
import com.gfs.iyango.core.domain.AppUpdateCheckState
import com.gfs.iyango.core.domain.CalendarSyncStatus
import com.gfs.iyango.core.domain.DiagnosticExport
import com.gfs.iyango.core.domain.RefreshOutcome
import com.gfs.iyango.core.domain.RefreshResource
import com.gfs.iyango.core.domain.RefreshTaskState
import com.gfs.iyango.core.domain.ReminderStatus
import com.gfs.iyango.core.model.DiagnosticEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 关于与诊断页（F18）。
 *
 * 展示版本、刷新区/提醒/日历近期状态与本地脱敏日志；导出前先预览文本，再由用户在
 * 系统文件选择器确认保存，不进行任何自动上传。
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenDockGlassDev: () -> Unit = {},
    onOpenRuntimeLogs: () -> Unit = {},
    viewModel: AboutViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val developerModeEnabled by viewModel.developerModeEnabled.collectAsStateWithLifecycle()
    var tappedVersion by remember { mutableStateOf(false) }
    val exportPreview by viewModel.exportPreview.collectAsStateWithLifecycle()
    val confirmClear by viewModel.confirmClear.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val doneMessage by viewModel.doneMessage.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val customUpdateUrl by viewModel.customUpdateUrl.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.resetVersionClicks()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.resetVersionClicks()
        }
    }

    var pendingExport by remember { mutableStateOf<DiagnosticExport?>(null) }
    var exportTargetUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) pendingExport = null else exportTargetUri = uri
    }
    LaunchedEffect(pendingExport) {
        pendingExport?.let { saveLauncher.launch(it.fileName) }
    }
    LaunchedEffect(exportTargetUri, pendingExport) {
        val uri = exportTargetUri ?: return@LaunchedEffect
        val payload = pendingExport ?: return@LaunchedEffect
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(payload.text.toByteArray(Charsets.UTF_8))
                }
            }
        }
        exportTargetUri = null
        pendingExport = null
    }

    GlassScaffold(
        topBar = {
            GlassTopBar(
                title = "关于与反馈",
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
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            doneMessage?.let {
                GlassSurface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    material = GlassMaterial.CONTENT,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = viewModel::consumeDone) { Text("知道了") }
                    }
                }
            }

            SectionCard(title = "应用信息") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoRow("版本", "${viewModel.appVersion} (${viewModel.versionCode})",
                        modifier = Modifier.heightIn(min = 48.dp).clickable(onClickLabel = "版本号") {
                            tappedVersion = true
                            viewModel.onVersionClick()
                        })
                    DeveloperReveal(developerModeEnabled, tappedVersion, expand = false) {
                        InfoRow("手机型号", viewModel.deviceModel)
                    }
                    InfoRow("系统", viewModel.systemVersion)
                    LiquidOutlinedButton(
                        onClick = onOpenRuntimeLogs,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text("查看运行日志")
                    }
                    val checkingUpdate = updateState is AppUpdateCheckState.Checking
                    LiquidOutlinedButton(
                        onClick = viewModel::checkForUpdates,
                        enabled = !checkingUpdate,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        if (checkingUpdate) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("正在检查更新...")
                        } else {
                            val label = when (val s = updateState) {
                                is AppUpdateCheckState.Available -> "发现新版本 v${s.release.versionName}"
                                else -> "检查更新"
                            }
                            Text(label)
                        }
                    }
                    val updateStatusHint = when (val s = updateState) {
                        AppUpdateCheckState.NotConfigured -> "暂未配置更新地址，待后续提供"
                        is AppUpdateCheckState.UpToDate -> "当前已是最新版本 (${s.currentVersionName})"
                        is AppUpdateCheckState.Available -> "发现新版本 v${s.release.versionName}"
                        is AppUpdateCheckState.Failed -> s.message
                        else -> null
                    }
                    if (updateStatusHint != null) {
                        Text(
                            text = updateStatusHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (updateState is AppUpdateCheckState.Failed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            DeveloperReveal(developerModeEnabled, tappedVersion, expand = true) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val refreshState by viewModel.refreshState.collectAsStateWithLifecycle()
                    val reminderStatus by viewModel.reminderStatus.collectAsStateWithLifecycle()
                    val calendarStatus by viewModel.calendarStatus.collectAsStateWithLifecycle()
                    val recent by viewModel.recent.collectAsStateWithLifecycle()
                    val summary by viewModel.summary.collectAsStateWithLifecycle()

                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("开发者模式已开启", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = viewModel::disableDeveloperMode, enabled = !busy) { Text("关闭开发者模式") }
                    }

                    SectionCard(title = "界面调试") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            LiquidOutlinedButton(
                                onClick = onOpenDockGlassDev,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) {
                                Text("底栏液态玻璃完整调参")
                            }
                        }
                    }

                    SectionCard(title = "更新源调试") {
                        var draftUrl by remember { mutableStateOf(customUpdateUrl) }
                        LaunchedEffect(customUpdateUrl) {
                            draftUrl = customUpdateUrl
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            LiquidOutlinedTextField(
                                value = draftUrl,
                                onValueChange = { draftUrl = it },
                                label = { Text("自定义更新清单 URL") },
                                placeholder = { Text("https://.../releases/latest") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                LiquidButton(
                                    onClick = { viewModel.saveCustomUpdateUrl(draftUrl) },
                                    modifier = Modifier.weight(1f),
                                ) { Text("保存地址") }
                                LiquidOutlinedButton(
                                    onClick = viewModel::checkForUpdates,
                                    modifier = Modifier.weight(1f),
                                ) { Text("立即检测") }
                            }
                        }
                    }

                    SectionCard(title = "近期状态") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            RefreshResource.entries.forEach { resource ->
                                RefreshStatusRow(resource, refreshState.task(resource))
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            ReminderStatusBlock(reminderStatus)
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            CalendarStatusBlock(calendarStatus)
                        }
                    }

                    SectionCard(title = "诊断日志") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "共 ${summary.count} 条 ${summary.bytes / 1024} KB（上限约 192 KB，自动轮替）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (recent.isEmpty()) {
                                Text(
                                    "暂无日志",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                recent.take(20).forEach { event -> DiagnosticRow(event) }
                                if (recent.size > 20) {
                                    Text(
                                        "仅显示最近 20 条，导出文件包含全部 ${summary.count} 条",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                LiquidButton(
                                    onClick = viewModel::prepareExport,
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                ) { Text("导出诊断") }
                                LiquidOutlinedButton(
                                    onClick = viewModel::requestClear,
                                    enabled = !busy && summary.count > 0,
                                    modifier = Modifier.weight(1f),
                                ) { Text("清空日志") }
                            }
                        }
                    }
                }
            }
        }
    }

    exportPreview?.takeIf { developerModeEnabled }?.let { export ->
        LiquidAlertDialog(
            onDismissRequest = viewModel::consumeExportPreview,
            title = { Text("导出内容预览", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        export.text,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingExport = export
                        viewModel.consumeExportPreview()
                    },
                ) { Text("保存到文件") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::consumeExportPreview) { Text("取消") }
            },
        )
    }

    if (developerModeEnabled && confirmClear) {
        LiquidAlertDialog(
            onDismissRequest = viewModel::dismissClear,
            title = { Text("清空诊断日志？", style = MaterialTheme.typography.titleLarge) },
            text = { Text("只会删除本机的诊断记录，不影响课程、设置与登录。") },
            confirmButton = {
                TextButton(onClick = viewModel::clearLogs) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissClear) { Text("取消") }
            },
        )
    }
}

/** The permission gate owns the composition, so locking also removes its semantics and flows immediately. */
@Composable
private fun DeveloperReveal(enabled: Boolean, tappedVersion: Boolean, expand: Boolean, content: @Composable () -> Unit) {
    if (!enabled) return
    val animate = LocalMotionEnabled.current
    val opened = remember { booleanArrayOf(false) }
    val state = remember(animate) {
        MutableTransitionState(!animate || !tappedVersion || opened[0]).apply { targetState = true }
    }
    androidx.compose.runtime.SideEffect { opened[0] = true }
    AnimatedVisibility(visibleState = state,
        enter = if (expand) expandVertically(tween(AppMotion.Expand), expandFrom = Alignment.Top) + fadeIn(tween(AppMotion.Content))
            else fadeIn(tween(AppMotion.Content)),
        exit = ExitTransition.None,
    ) { content() }
}

@Composable
private fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RefreshStatusRow(resource: RefreshResource, task: RefreshTaskState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(resourceLabel(resource), style = MaterialTheme.typography.bodyMedium)
        Column(horizontalAlignment = Alignment.End) {
            Text(
                outcomeLabel(task.outcome),
                style = MaterialTheme.typography.labelMedium,
                color = if (task.outcome == RefreshOutcome.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (task.outcome == RefreshOutcome.FAILED && task.message.isNotBlank()) {
                Text(
                    task.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReminderStatusBlock(status: ReminderStatus) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("提醒", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(
            "开关${if (status.enabled) "开" else "关"} 通知权限" +
                "${if (status.notificationsAllowed) "已授予" else "未授予"} 精确闹钟" +
                "${if (status.exactAlarmsAllowed) "可用" else "不可用"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val reasons = (status.failures + status.skipped).distinct().take(3)
        reasons.forEach {
            Text(
                "$it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun CalendarStatusBlock(status: CalendarSyncStatus) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("系统日历", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(
            "开关${if (status.enabled) "开" else "关"} 权限" +
                "${if (status.permissionGranted) "已授予" else "未授予"} 已同步 ${status.syncedCount} 条" +
                if (status.pendingCleanup > 0) " 待清理 ${status.pendingCleanup} 条" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (status.lastError.isNotBlank()) {
            Text(
                "${status.lastError}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun DiagnosticRow(event: DiagnosticEvent) {
    val time = remember(event.timestamp) {
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(event.timestamp))
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            buildString {
                append(time).append(" ").append(event.category.ifBlank { "事件" })
                if (event.outcome.isNotBlank()) append(" ").append(event.outcome)
                if (event.durationMs > 0) append(" ").append(event.durationMs).append("ms")
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (event.outcome == RefreshOutcome.FAILED.name || event.outcome == "PARTIAL") {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        val detail = listOfNotNull(
            event.errorType.takeIf { it.isNotBlank() },
            event.message.takeIf { it.isNotBlank() },
        ).joinToString(" ")
        if (detail.isNotBlank()) {
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun resourceLabel(resource: RefreshResource): String = when (resource) {
    RefreshResource.TIMETABLE -> "课表"
    RefreshResource.PROFILE -> "个人资料"
    RefreshResource.GRADES -> "成绩"
    RefreshResource.CLASSROOM -> "教室"
    RefreshResource.EXAMS -> "考试"
}

private fun outcomeLabel(outcome: RefreshOutcome): String = when (outcome) {
    RefreshOutcome.IDLE -> "未刷新"
    RefreshOutcome.RUNNING -> "刷新中"
    RefreshOutcome.SUCCESS -> "成功"
    RefreshOutcome.FAILED -> "失败"
    RefreshOutcome.CANCELLED -> "已取消"
}
