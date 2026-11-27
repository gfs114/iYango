@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.gfs.iyango.feature.main.settings

import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassTopBarIconButton
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gfs.iyango.core.designsystem.component.SectionCard
import com.gfs.iyango.core.designsystem.component.SettingSwitchRow
import com.gfs.iyango.core.domain.SettingsBackupPreview
import com.gfs.iyango.core.domain.ThemeMode
import com.gfs.iyango.core.domain.WebDavConfig
import com.gfs.iyango.core.domain.WebDavPreset
import com.gfs.iyango.core.model.NavigationLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置备份页（F17）：导出版本化设置、文件预览后恢复、重置个性化设置。
 *
 * 备份不含登录凭据、Cookie、系统日历/闹钟 ID 与组件宿主 ID；壁纸是否携带由用户选择。
 * 重置只影响设置与壁纸，不触碰课程、学期、考试与登录。
 */
@Composable
fun SettingsBackupScreen(
    onBack: () -> Unit,
    viewModel: SettingsBackupViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val pendingExport by viewModel.pendingExport.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val exportWallpaper by viewModel.exportIncludesWallpaper.collectAsStateWithLifecycle()
    val restoreWallpaper by viewModel.restoreIncludesWallpaper.collectAsStateWithLifecycle()
    val overwriteCreditPlans by viewModel.overwriteCreditPlans.collectAsStateWithLifecycle()
    val confirmReset by viewModel.confirmReset.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val doneMessage by viewModel.doneMessage.collectAsStateWithLifecycle()
    val storedWebDav by viewModel.webDavConfig.collectAsStateWithLifecycle()
    val webDavBackups by viewModel.webDavBackups.collectAsStateWithLifecycle()
    val webDavPreview by viewModel.webDavPreview.collectAsStateWithLifecycle()
    val webDavBusy by viewModel.webDavBusy.collectAsStateWithLifecycle()

    var davPreset by remember(storedWebDav.preset) { mutableStateOf(storedWebDav.preset) }
    var davUrl by remember(storedWebDav.serverUrl) { mutableStateOf(storedWebDav.serverUrl) }
    var davUser by remember(storedWebDav.username) { mutableStateOf(storedWebDav.username) }
    var davPassword by remember { mutableStateOf("") }
    var davDirectory by remember(storedWebDav.directory) { mutableStateOf(storedWebDav.directory) }
    var davAutoSync by remember(storedWebDav.autoSyncEnabled) { mutableStateOf(storedWebDav.autoSyncEnabled) }

    fun currentWebDavConfig(): WebDavConfig = storedWebDav.copy(
        preset = davPreset,
        serverUrl = davUrl,
        username = davUser,
        directory = davDirectory,
        autoSyncEnabled = davAutoSync,
    )

    var exportTargetUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) viewModel.consumePendingExport() else exportTargetUri = uri
    }
    LaunchedEffect(exportTargetUri, pendingExport) {
        val uri = exportTargetUri ?: return@LaunchedEffect
        val payload = pendingExport ?: return@LaunchedEffect
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(payload.bytes) }
            }
        }
        exportTargetUri = null
        viewModel.consumePendingExport()
    }
    LaunchedEffect(pendingExport) {
        pendingExport?.let { exportLauncher.launch(it.fileName) }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()?.let(viewModel::readFile)
        }
    }

    GlassScaffold(
        topBar = {
            GlassTopBar(
                title = "设置备份与重置",
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
                    shape = RoundedCornerShape(12.dp),
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

            SectionCard(title = "导出设置") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingSwitchRow(
                        title = "一并导出壁纸图片",
                        checked = exportWallpaper,
                        onCheckedChange = { viewModel.exportIncludesWallpaper.value = it },
                    )
                    LiquidButton(
                        onClick = viewModel::requestExport,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text("导出到文件") }
                }
            }

            SectionCard(title = "从文件恢复") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiquidOutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/json")) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text("选择备份并预览") }
                    preview?.let { current ->
                        RestorePreview(
                            preview = current,
                            restoreWallpaper = restoreWallpaper,
                            onRestoreWallpaperChange = { viewModel.restoreIncludesWallpaper.value = it },
                            overwriteCreditPlans = overwriteCreditPlans,
                            onOverwriteCreditPlansChange = { viewModel.overwriteCreditPlans.value = it },
                            onConfirm = viewModel::restore,
                            onCancel = viewModel::dismissPreview,
                            busy = busy,
                        )
                    }
                }
            }

            SectionCard(title = "WebDAV 云同步") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        WebDavPreset.entries.forEach { preset ->
                            LiquidFilterChip(
                                selected = davPreset == preset,
                                onClick = {
                                    davPreset = preset
                                    if (preset.defaultUrl.isNotEmpty()) davUrl = preset.defaultUrl
                                    davDirectory = preset.defaultDirectory
                                },
                                label = { Text(preset.label) },
                            )
                        }
                    }
                    LiquidOutlinedTextField(
                        value = davUrl,
                        onValueChange = { davUrl = it },
                        label = { Text("服务器地址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LiquidOutlinedTextField(
                        value = davUser,
                        onValueChange = { davUser = it },
                        label = { Text("账号") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LiquidOutlinedTextField(
                        value = davPassword,
                        onValueChange = { davPassword = it },
                        label = { Text("应用密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LiquidOutlinedTextField(
                        value = davDirectory,
                        onValueChange = { davDirectory = it },
                        label = { Text("远端目录") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SettingSwitchRow(
                        title = "进入前台自动备份（6 小时）",
                        checked = davAutoSync,
                        onCheckedChange = {
                            davAutoSync = it
                            viewModel.saveWebDavConfig(currentWebDavConfig().copy(autoSyncEnabled = it), davPassword.takeIf { p -> p.isNotEmpty() })
                        },
                    )
                    if (storedWebDav.lastSyncAtMillis > 0L) {
                        val lastText = remember(storedWebDav.lastSyncAtMillis) {
                            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                .format(Date(storedWebDav.lastSyncAtMillis))
                        }
                        Text(
                            text = "最近同步 $lastText",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LiquidOutlinedButton(
                            onClick = {
                                viewModel.testWebDav(currentWebDavConfig(), davPassword.takeIf { it.isNotEmpty() })
                            },
                            enabled = !webDavBusy && davUrl.isNotBlank() && davUser.isNotBlank(),
                            modifier = Modifier.weight(1f).height(44.dp),
                        ) { Text("测试连接") }
                        LiquidButton(
                            onClick = {
                                viewModel.backupToWebDav(currentWebDavConfig(), davPassword.takeIf { it.isNotEmpty() })
                            },
                            enabled = !webDavBusy && davUrl.isNotBlank() && davUser.isNotBlank(),
                            modifier = Modifier.weight(1f).height(44.dp),
                        ) { Text(if (webDavBusy) "处理中…" else "立即备份") }
                    }
                    LiquidOutlinedButton(
                        onClick = {
                            viewModel.loadWebDavBackups(currentWebDavConfig(), davPassword.takeIf { it.isNotEmpty() })
                        },
                        enabled = !webDavBusy && davUrl.isNotBlank() && davUser.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                    ) { Text("从云端拉取备份") }
                    if (webDavBackups.isNotEmpty()) {
                        HorizontalDivider()
                        webDavBackups.take(8).forEach { remote ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !webDavBusy) {
                                        viewModel.previewWebDavBackup(remote)
                                    }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(remote.fileName, style = MaterialTheme.typography.bodyMedium)
                                    if (remote.lastModified.isNotBlank()) {
                                        Text(
                                            remote.lastModified,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Text(
                                    "恢复",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }

            SectionCard(title = "重置设置") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiquidOutlinedButton(
                        onClick = viewModel::requestReset,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text("重置个性化设置", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }

    if (confirmReset) {
        LiquidAlertDialog(
            onDismissRequest = viewModel::dismissReset,
            title = { Text("重置个性化设置？", style = MaterialTheme.typography.titleLarge) },
            text = { Text("主题、布局、显示、提醒与壁纸将恢复默认；课程与登录数据不受影响。") },
            confirmButton = {
                TextButton(onClick = viewModel::resetSettings) { Text("重置") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissReset) { Text("取消") }
            },
        )
    }

    webDavPreview?.let { (cloudPreview, _) ->
        val timeText = remember(cloudPreview.exportedAt) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(Date(cloudPreview.exportedAt))
        }
        LiquidAlertDialog(
            onDismissRequest = viewModel::dismissWebDavPreview,
            title = { Text("确认从云端恢复？", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    "文件：${cloudPreview.fileName}\n" +
                        "备份时间：$timeText\n" +
                        "包含 ${cloudPreview.timetableCount} 张课表、${cloudPreview.courseCount} 条课程、${cloudPreview.examCount} 场考试\n" +
                        "将覆盖本地课表与个性化设置",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRestoreWebDav, enabled = !webDavBusy) {
                    Text("确认恢复")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissWebDavPreview) { Text("取消") }
            },
        )
    }
}

@Composable
internal fun RestorePreview(
    preview: SettingsBackupPreview,
    restoreWallpaper: Boolean,
    onRestoreWallpaperChange: (Boolean) -> Unit,
    overwriteCreditPlans: Boolean,
    onOverwriteCreditPlansChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    busy: Boolean,
) {
    val time = remember(preview.exportedAt) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(preview.exportedAt))
    }
    val themeLabel = runCatching { ThemeMode.valueOf(preview.themeMode) }
        .getOrDefault(ThemeMode.SYSTEM)
        .label
    val layoutLabel = when (runCatching { NavigationLayout.valueOf(preview.navigationLayout) }
        .getOrDefault(NavigationLayout.AUTO)) {
        NavigationLayout.AUTO -> "自动"
        NavigationLayout.BOTTOM -> "底部"
        NavigationLayout.SIDE -> "侧边"
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("备份预览", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(
            "导出于 $time 格式 v${preview.formatVersion}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "主题：$themeLabel 强调色 ${preview.accentHex ?: "默认"}",
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            "默认页 ${startTabLabel(preview.defaultStartTab)} 导航 $layoutLabel " +
                "界面动画${if (preview.animationsEnabled) "开" else "关"}",
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            "按课表显示覆盖 ${preview.displayOverrideCount} 份 " +
                "提醒${if (preview.reminderEnabled) "开（提前 ${preview.reminderLeadMinutes} 分钟）" else "关"}",
            style = MaterialTheme.typography.labelMedium,
        )
        if (preview.issues.isNotEmpty()) {
            preview.issues.forEach {
                Text(
                    "$it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (preview.includesWallpaper) {
            SettingSwitchRow(
                title = "恢复备份中的壁纸",
                checked = restoreWallpaper,
                onCheckedChange = onRestoreWallpaperChange,
            )
        } else {
            Text(
                "未包含壁纸",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (preview.creditBackupPreview.conflicts.isNotEmpty()) {
            SettingSwitchRow(
                title = "覆盖本机学分计划（${preview.creditBackupPreview.conflicts.size} 项冲突）",
                checked = overwriteCreditPlans,
                onCheckedChange = onOverwriteCreditPlansChange,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LiquidOutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
            LiquidButton(onClick = onConfirm, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text("确认恢复")
            }
        }
    }
}

private fun startTabLabel(tab: Int): String = when (tab) {
    0 -> "今日"
    1 -> "课表"
    2 -> "成绩"
    3 -> "我的"
    else -> "今日"
}
