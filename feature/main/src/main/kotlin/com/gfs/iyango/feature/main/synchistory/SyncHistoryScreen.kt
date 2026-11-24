@file:OptIn(ExperimentalMaterial3Api::class)

package com.gfs.iyango.feature.main.synchistory

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import com.gfs.iyango.core.designsystem.component.GlassTopBarTextButton
import com.gfs.iyango.core.designsystem.component.GlassOverlayHeader
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenuItem
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidAlertDialog
import com.gfs.iyango.core.designsystem.liquidglass.LiquidDropdownMenu
import com.gfs.iyango.core.designsystem.liquidglass.LiquidModalBottomSheet
import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassTopBarIconButton
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gfs.iyango.core.designsystem.component.EmptyState
import com.gfs.iyango.core.designsystem.theme.LocalBrand
import com.gfs.iyango.core.model.SyncBatch
import com.gfs.iyango.core.model.SyncBatchResult
import com.gfs.iyango.core.model.SyncChange
import com.gfs.iyango.core.model.SyncChangeType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 教务同步变更记录页（E02）。
 *
 * 批次按当前账号隔离，点开某次同步查看字段级前后值；打开即标记已读，
 * 支持全部已读与清除记录。清除不影响学校快照基线。
 */
@Composable
fun SyncHistoryScreen(
    onBack: () -> Unit,
    viewModel: SyncHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showClearDialog by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }

    LaunchedEffect(state.error) {
        val message = state.error
        if (message.isNotEmpty()) {
            snackbar.showSnackbar(message)
            viewModel.consumeError()
        }
    }

    GlassScaffold(
        topBar = {
            BoxWithConstraints {
                val showReadAction = maxWidth >= 400.dp && LocalDensity.current.fontScale <= 1.3f
                GlassTopBar(
                    title = "同步记录",
                    navigationIcon = {
                        GlassTopBarIconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        if (state.batches.isNotEmpty()) {
                            if (showReadAction) {
                                GlassTopBarTextButton(
                                    text = "全部已读",
                                    onClick = viewModel::markAllRead,
                                    enabled = state.unreadCount > 0,
                                )
                            }
                            Box {
                                GlassTopBarIconButton(onClick = { showMore = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "更多同步记录操作")
                                }
                                LiquidDropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                                    if (!showReadAction) {
                                        DropdownMenuItem(
                                            text = { Text("全部已读") },
                                            enabled = state.unreadCount > 0,
                                            onClick = {
                                                showMore = false
                                                viewModel.markAllRead()
                                            },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("清除记录") },
                                        onClick = {
                                            showMore = false
                                            showClearDialog = true
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        when {
            !state.hasAccount -> EmptyState(
                icon = Icons.Filled.Refresh,
                title = "还没有绑定教务账号",
                hint = "登录教务账号并完成同步后，这里会记录课程变化。",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            state.batches.isEmpty() -> EmptyState(
                icon = Icons.Filled.Refresh,
                title = "还没有同步记录",
                hint = "完成一次教务同步后，这里会显示新增、移除与字段变化。",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            else -> LazyColumn(
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
                items(state.batches, key = { it.id }) { batch ->
                    BatchCard(
                        batch = batch,
                        timetableName = state.timetableNames[batch.timetableId],
                        onClick = { viewModel.openBatch(batch) },
                    )
                }
                item {
                    Text(
                        "记录按账号保存，最多保留最近 50 次同步；清除记录不会影响课表数据与下次比较基线。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
                    )
                }
            }
        }
    }

    state.selectedBatch?.let { batch ->
        SyncBatchSheet(
            batch = batch,
            timetableName = state.timetableNames[batch.timetableId],
            changes = state.changes,
            onDismiss = viewModel::closeBatch,
        )
    }

    if (showClearDialog) {
        LiquidAlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清除同步记录？", style = MaterialTheme.typography.titleLarge) },
            text = { Text("将删除当前账号的全部同步批次与变化明细，不影响课表数据与学校快照。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearHistory()
                        showClearDialog = false
                    },
                ) { Text("清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun BatchCard(
    batch: SyncBatch,
    timetableName: String?,
    onClick: () -> Unit,
) {
    GlassSurface(
        shape = RoundedCornerShape(16.dp),
        material = GlassMaterial.CONTENT,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UnreadDot(visible = !batch.read)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = batch.semester.ifBlank { "未知学期" },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    ResultBadge(batch.result)
                }
                Text(
                    text = listOfNotNull(
                        timetableName?.takeIf { it.isNotEmpty() } ?: "教务课表",
                        formatTimestamp(batch.finishedAt),
                    ).joinToString(" "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = batch.message.ifBlank {
                        if (batch.firstBaseline) {
                            "首次同步建立基线"
                        } else if (batch.changeCount == 0) {
                            "本次同步没有实质变化"
                        } else {
                            "${batch.changeCount} 项变化"
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = if (batch.firstBaseline) {
                        "第 ${batch.version} 次同步 基线记录"
                    } else {
                        "第 ${batch.version} 次同步 ${batch.changeCount} 项变化"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UnreadDot(visible: Boolean) {
    if (!visible) {
        Spacer(Modifier.size(8.dp))
        return
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(LocalBrand.current.accent),
    )
}

@Composable
private fun ResultBadge(result: SyncBatchResult) {
    val (label, container, content) = when (result) {
        SyncBatchResult.SUCCESS -> Triple(
            "成功",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        SyncBatchResult.PARTIAL -> Triple(
            "部分完成",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        SyncBatchResult.FAILED -> Triple(
            "失败",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
    }
    Surface(shape = RoundedCornerShape(999.dp), color = container) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun SyncBatchSheet(
    batch: SyncBatch,
    timetableName: String?,
    changes: List<SyncChange>,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LiquidModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column {
            GlassOverlayHeader(
                title = "同步详情",
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
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (batch.firstBaseline) {
                    InfoNote("首次同步用于建立学校课表基线，不逐条展示“新增”，避免把整张课表当作变化。")
                } else if (changes.isEmpty()) {
                    InfoNote(
                        if (batch.changeCount == 0) {
                            "本次同步没有字段级变化（仅返回顺序或同步时间变化不计入）。"
                        } else {
                            "本次同步的详细变化记录已超出保留范围，仅保留汇总条数。"
                        },
                    )
                }
                changes.forEach { change ->
                    ChangeRow(change)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "仅比较上一次完整学校快照与本次完整数据；本机手动调课不计入学校变化。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InfoNote(text: String) {
    GlassSurface(
        shape = RoundedCornerShape(12.dp),
        material = GlassMaterial.CONTENT,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun ChangeRow(change: SyncChange) {
    val (label, color) = when (change.changeType) {
        SyncChangeType.ADDED -> "新增" to MaterialTheme.colorScheme.tertiary
        SyncChangeType.REMOVED -> "移除" to MaterialTheme.colorScheme.error
        SyncChangeType.MODIFIED -> "修改" to MaterialTheme.colorScheme.primary
        SyncChangeType.AMBIGUOUS -> "待确认" to MaterialTheme.colorScheme.secondary
    }
    GlassSurface(
        shape = RoundedCornerShape(12.dp),
        material = GlassMaterial.CONTENT,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = change.courseName.ifBlank { "未知课程" },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
            }
            when (change.changeType) {
                SyncChangeType.MODIFIED -> {
                    if (change.field.isNotEmpty()) {
                        Text(
                            "字段：${change.field}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "${valueText(change.beforeValue)} → ${valueText(change.afterValue)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                SyncChangeType.AMBIGUOUS -> Text(
                    "学校返回中存在歧义，已保留本地课程并等待确认。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Unit
            }
        }
    }
}

private fun valueText(value: String): String = if (value.isBlank()) "（空）" else value

private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")

private fun formatTimestamp(millis: Long): String {
    if (millis <= 0L) return "时间未知"
    return Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(TIMESTAMP_FORMAT)
}
