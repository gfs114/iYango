@file:OptIn(ExperimentalMaterial3Api::class)

package com.gfs.iyango.feature.main.about

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassTopBarIconButton
import com.gfs.iyango.core.designsystem.component.SectionCard
import com.gfs.iyango.core.designsystem.component.SettingSwitchRow
import com.gfs.iyango.core.designsystem.component.StatusBadge
import com.gfs.iyango.core.designsystem.liquidglass.LiquidButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidFilterChip
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedButton
import com.gfs.iyango.core.domain.AppUpdateCheckState
import com.gfs.iyango.core.domain.AppUpdateDownloadState
import com.gfs.iyango.core.domain.AppUpdateRelease
import com.gfs.iyango.core.domain.UpdateDownloadMirror
import java.util.Locale

/**
 * 独立版本更新页：检测新版、切换镜像下载 APK、安装并查看历史版本更新说明。
 */
@Composable
fun AppUpdateScreen(
    onBack: () -> Unit,
    viewModel: AppUpdateViewModel = hiltViewModel(),
    onInstallApk: ((String) -> InstallApkResult)? = null,
    onOpenExternalUrl: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val checkState by viewModel.checkState.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val downloadMirror by viewModel.downloadMirror.collectAsStateWithLifecycle()
    val autoCheckEnabled by viewModel.autoCheckEnabled.collectAsStateWithLifecycle()
    val releaseHistory by viewModel.releaseHistory.collectAsStateWithLifecycle()
    val historyLoading by viewModel.historyLoading.collectAsStateWithLifecycle()
    val manualUpdateDialogRelease by viewModel.manualUpdateDialogRelease.collectAsStateWithLifecycle()

    var installNotice by remember { mutableStateOf<String?>(null) }

    val openExternal: (String) -> Unit = { url ->
        if (onOpenExternalUrl != null) {
            onOpenExternalUrl(url)
        } else if (url.isNotBlank()) {
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }

    val handleInstall: (String) -> Unit = { apkPath ->
        val result = onInstallApk?.invoke(apkPath) ?: installDownloadedApk(context, apkPath)
        installNotice = when (result) {
            InstallApkResult.Launched -> null
            InstallApkResult.PermissionRequired -> "请在系统设置中允许安装未知来源应用后重试"
            is InstallApkResult.Failed -> result.message
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadInitial()
    }

    GlassScaffold(
        topBar = {
            GlassTopBar(
                title = "版本更新",
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
            val currentNameClean = cleanVersionName(viewModel.currentVersionName)
            val checking = checkState is AppUpdateCheckState.Checking

            SectionCard(
                title = "版本状态",
                action = if (checkState is AppUpdateCheckState.Available) {
                    {
                        TextButton(
                            onClick = viewModel::checkForUpdates,
                            enabled = !checking,
                        ) {
                            Text("刷新")
                        }
                    }
                } else {
                    null
                },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusHeaderRow(
                        checkState = checkState,
                        currentVersionName = currentNameClean,
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "当前 v$currentNameClean (${viewModel.currentVersionCode})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (checkState is AppUpdateCheckState.Available) {
                            val release = (checkState as AppUpdateCheckState.Available).release
                            if (release.publishedAt.isNotBlank()) {
                                Text(
                                    text = "发布日期 ${release.publishedAt.take(10)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (release.apkSizeBytes > 0L) {
                                Text(
                                    text = "安装包大小 ${formatBytes(release.apkSizeBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    when (val state = checkState) {
                        is AppUpdateCheckState.Available -> {
                            AvailableReleaseActions(
                                release = state.release,
                                downloadState = downloadState,
                                installNotice = installNotice,
                                onStartDownload = {
                                    installNotice = null
                                    viewModel.startDownload(state.release)
                                },
                                onCancelDownload = viewModel::cancelDownload,
                                onInstall = handleInstall,
                                onOpenBrowser = openExternal,
                                onIgnore = { viewModel.ignoreVersion(state.release.versionKey) },
                            )
                        }

                        is AppUpdateCheckState.Failed -> {
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            CheckUpdateButton(checking = false, onClick = viewModel::checkForUpdates)
                        }

                        AppUpdateCheckState.NotConfigured -> {
                            Text(
                                text = "暂未配置更新地址",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            CheckUpdateButton(checking = false, onClick = viewModel::checkForUpdates)
                        }

                        AppUpdateCheckState.Checking -> {
                            CheckUpdateButton(checking = true, onClick = {})
                        }

                        AppUpdateCheckState.Idle,
                        is AppUpdateCheckState.UpToDate,
                        -> {
                            CheckUpdateButton(checking = false, onClick = viewModel::checkForUpdates)
                        }
                    }
                }
            }

            val activeNotes = remember(checkState, releaseHistory, currentNameClean) {
                when (val state = checkState) {
                    is AppUpdateCheckState.Available -> state.release.releaseNotes
                    else -> releaseHistory.firstOrNull {
                        cleanVersionName(it.versionName) == currentNameClean
                    }?.releaseNotes
                        ?: releaseHistory.firstOrNull()?.releaseNotes.orEmpty()
                }
            }

            if (activeNotes.isNotBlank()) {
                SectionCard(title = "更新内容") {
                    MarkdownNotesBlock(notes = activeNotes)
                }
            }

            SectionCard(title = "下载设置") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        UpdateDownloadMirror.entries.forEach { mirror ->
                            LiquidFilterChip(
                                selected = mirror == downloadMirror,
                                onClick = { viewModel.setDownloadMirror(mirror) },
                                label = { Text(mirror.label) },
                            )
                        }
                    }
                    SettingSwitchRow(
                        title = "启动时自动检查",
                        checked = autoCheckEnabled,
                        onCheckedChange = viewModel::setAutoCheckEnabled,
                    )
                }
            }

            if (historyLoading || releaseHistory.isNotEmpty()) {
                SectionCard(title = "历史版本") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (historyLoading && releaseHistory.isEmpty()) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        releaseHistory.forEachIndexed { index, item ->
                            if (index > 0) {
                                HorizontalDivider()
                            }
                            ReleaseHistoryRow(
                                release = item,
                                isCurrent = cleanVersionName(item.versionName) == currentNameClean,
                                onOpenPage = openExternal,
                            )
                        }
                    }
                }
            }
        }
    }

    manualUpdateDialogRelease?.let { release ->
        AppUpdateDialog(
            release = release,
            onDismiss = viewModel::dismissManualUpdateDialog,
            onIgnore = viewModel::ignoreVersion,
            onOpenUpdatePage = {
                if (release.forceUpdate) {
                    viewModel.clearManualUpdateDialogOnly()
                } else {
                    viewModel.dismissManualUpdateDialog()
                }
                installNotice = null
                viewModel.startDownload(release)
            },
        )
    }
}

@Composable
private fun StatusHeaderRow(
    checkState: AppUpdateCheckState,
    currentVersionName: String,
) {
    val heading = when (checkState) {
        is AppUpdateCheckState.Available -> "发现新版本"
        is AppUpdateCheckState.UpToDate -> "已是最新版本"
        AppUpdateCheckState.Checking -> "正在检查更新"
        is AppUpdateCheckState.Failed -> "检查失败"
        AppUpdateCheckState.NotConfigured -> "未配置更新源"
        AppUpdateCheckState.Idle -> "版本更新"
    }
    val badgeText = when (checkState) {
        is AppUpdateCheckState.Available -> "v${cleanVersionName(checkState.release.versionName)}"
        else -> "v$currentVersionName"
    }
    val icon = when (checkState) {
        is AppUpdateCheckState.UpToDate -> Icons.Filled.Check
        AppUpdateCheckState.Checking -> Icons.Filled.Refresh
        else -> Icons.Filled.Info
    }
    val iconTint = when (checkState) {
        is AppUpdateCheckState.Available,
        is AppUpdateCheckState.UpToDate,
        -> MaterialTheme.colorScheme.primary

        is AppUpdateCheckState.Failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = heading,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        StatusBadge(text = badgeText)
    }
}

@Composable
private fun AvailableReleaseActions(
    release: AppUpdateRelease,
    downloadState: AppUpdateDownloadState,
    installNotice: String?,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: (String) -> Unit,
    onOpenBrowser: (String) -> Unit,
    onIgnore: () -> Unit,
) {
    val activeDownloading = (downloadState as? AppUpdateDownloadState.Downloading)
        ?.takeIf { it.versionKey == release.versionKey }
    val activeReady = (downloadState as? AppUpdateDownloadState.ReadyToInstall)
        ?.takeIf { it.versionKey == release.versionKey }
    val activeFailed = (downloadState as? AppUpdateDownloadState.Failed)
        ?.takeIf { it.versionKey == release.versionKey }

    when {
        activeDownloading != null -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val progress = activeDownloading.progress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val leftLabel = if (activeDownloading.totalBytes > 0L) {
                        val percent = ((progress ?: 0f) * 100).toInt()
                        "$percent% (${formatBytes(activeDownloading.downloadedBytes)} / ${formatBytes(activeDownloading.totalBytes)})"
                    } else {
                        formatBytes(activeDownloading.downloadedBytes)
                    }
                    Text(
                        text = leftLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (activeDownloading.bytesPerSecond > 0L) {
                        Text(
                            text = "${formatBytes(activeDownloading.bytesPerSecond)}/s",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                LiquidOutlinedButton(
                    onClick = onCancelDownload,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text("取消下载")
                }
            }
        }

        activeReady != null -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "已就绪 (${formatBytes(activeReady.fileSizeBytes)})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (!installNotice.isNullOrBlank()) {
                    Text(
                        text = installNotice,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                LiquidButton(
                    onClick = { onInstall(activeReady.apkFilePath) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text("立即安装")
                }
                LiquidOutlinedButton(
                    onClick = onStartDownload,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text("重新下载")
                }
            }
        }

        else -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (activeFailed != null) {
                    Text(
                        text = activeFailed.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (!installNotice.isNullOrBlank()) {
                    Text(
                        text = installNotice,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (release.downloadUrl.isNotBlank()) {
                    LiquidButton(
                        onClick = onStartDownload,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text("立即下载")
                    }
                } else if (release.pageUrl.isNotBlank()) {
                    LiquidButton(
                        onClick = { onOpenBrowser(release.pageUrl) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text("前往下载页")
                    }
                }
                if (release.actionUrl.isNotBlank() || !release.forceUpdate) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (release.actionUrl.isNotBlank()) {
                            LiquidOutlinedButton(
                                onClick = { onOpenBrowser(release.actionUrl) },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            ) {
                                Text("浏览器打开")
                            }
                        }
                        if (!release.forceUpdate) {
                            LiquidOutlinedButton(
                                onClick = onIgnore,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            ) {
                                Text("忽略此版本")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckUpdateButton(
    checking: Boolean,
    onClick: () -> Unit,
) {
    LiquidButton(
        onClick = onClick,
        enabled = !checking,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        if (checking) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
            Text("正在检查...")
        } else {
            Text("检查更新")
        }
    }
}

@Composable
private fun ReleaseHistoryRow(
    release: AppUpdateRelease,
    isCurrent: Boolean,
    onOpenPage: (String) -> Unit,
) {
    var expanded by remember(release.versionKey) { mutableStateOf(false) }
    val versionLabel = when {
        release.versionName.isNotBlank() -> "v${cleanVersionName(release.versionName)}"
        release.versionCode > 0L -> "Build ${release.versionCode}"
        else -> "历史版本"
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = versionLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    if (isCurrent) {
                        StatusBadge(text = "当前")
                    }
                }
                if (release.publishedAt.isNotBlank()) {
                    Text(
                        text = release.publishedAt.take(10),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MarkdownNotesBlock(
                    notes = release.releaseNotes.ifBlank { "无更新说明" },
                )
                if (release.pageUrl.isNotBlank()) {
                    TextButton(onClick = { onOpenPage(release.pageUrl) }) {
                        Text("打开发布页")
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownNotesBlock(notes: String) {
    val lines = remember(notes) {
        notes.lines().map { it.trimEnd() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return@forEach
            when {
                trimmed.startsWith("### ") || trimmed.startsWith("## ") || trimmed.startsWith("# ") -> {
                    val heading = trimmed.trimStart('#').trim()
                    if (heading.isNotEmpty()) {
                        Text(
                            text = heading,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }

                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    val item = trimmed.drop(2).trim()
                    if (item.isNotEmpty()) {
                        Text(
                            text = "- $item",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }

                else -> {
                    Text(
                        text = trimmed,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

private fun cleanVersionName(raw: String): String =
    raw.trim().removePrefix("v").removePrefix("V")

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "${bytes} B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.1f MB", mb)
}
