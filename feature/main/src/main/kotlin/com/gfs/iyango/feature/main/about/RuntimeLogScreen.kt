@file:OptIn(ExperimentalLayoutApi::class)

package com.gfs.iyango.feature.main.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidAlertDialog
import com.gfs.iyango.core.designsystem.liquidglass.LiquidFilterChip
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.designsystem.component.DissolveBurst
import com.gfs.iyango.core.designsystem.component.EmptyState
import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassTopBarIconButton
import com.gfs.iyango.core.designsystem.component.GlassTopBarTextButton
import com.gfs.iyango.core.domain.DiagnosticLogPort
import com.gfs.iyango.core.domain.DiagnosticLogSummary
import com.gfs.iyango.core.model.DiagnosticEvent
import com.gfs.iyango.feature.main.successColor
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 按类别与关键字过滤诊断事件（不区分大小写）。 */
fun filterDiagnosticEvents(
    events: List<DiagnosticEvent>,
    category: String?,
    query: String,
): List<DiagnosticEvent> {
    val cleanQuery = query.trim()
    return events.filter { event ->
        val categoryMatches = category.isNullOrBlank() || event.category.equals(category, ignoreCase = true)
        val queryMatches = cleanQuery.isEmpty() ||
            event.category.contains(cleanQuery, ignoreCase = true) ||
            event.outcome.contains(cleanQuery, ignoreCase = true) ||
            event.errorType.contains(cleanQuery, ignoreCase = true) ||
            event.message.contains(cleanQuery, ignoreCase = true)
        categoryMatches && queryMatches
    }
}

private val LOG_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** 将诊断事件列表格式化为纯文本行，供一键复制。 */
fun formatDiagnosticLogLines(events: List<DiagnosticEvent>): String =
    events.joinToString(separator = "\n") { event ->
        val time = runCatching {
            Instant.ofEpochMilli(event.timestamp)
                .atZone(ZoneId.systemDefault())
                .format(LOG_TIMESTAMP_FORMAT)
        }.getOrDefault(event.timestamp.toString())
        buildString {
            append("[$time] ")
            append(event.category.ifBlank { "-" })
            append(" outcome=").append(event.outcome.ifBlank { "-" })
            append(" ").append(event.durationMs).append("ms")
            if (event.errorType.isNotBlank()) append(" error=").append(event.errorType)
            if (event.message.isNotBlank()) append(" | ").append(event.message)
        }
    }

@HiltViewModel
class RuntimeLogViewModel @Inject constructor(
    private val diagnostics: DiagnosticLogPort,
) : ViewModel() {

    val events: StateFlow<List<DiagnosticEvent>> = diagnostics.observeRecent(200)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val summary: StateFlow<DiagnosticLogSummary> = diagnostics.summary
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticLogSummary())

    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun setCategory(category: String?) {
        _selectedCategory.value = category
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun clearLogs() {
        viewModelScope.launch {
            try {
                diagnostics.clear()
                _message.value = "运行日志已清空"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _message.value = "清空日志失败，请重试"
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}

/** 运行日志查看器：支持按类别与关键字筛选、一键复制、粒子消散动效清空日志。 */
@Composable
fun RuntimeLogScreen(
    onBack: () -> Unit,
    viewModel: RuntimeLogViewModel = hiltViewModel(),
) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    var clearBurstTrigger by remember { mutableIntStateOf(0) }

    val categories = remember(events) {
        events.map { it.category.trim() }.filter { it.isNotEmpty() }.distinct()
    }
    val filtered = remember(events, selectedCategory, searchQuery) {
        filterDiagnosticEvents(events, selectedCategory, searchQuery)
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    GlassScaffold(
        topBar = {
            GlassTopBar(
                title = "运行日志",
                subtitle = "共 ${summary.count} 条 · ${summary.bytes / 1024} KB",
                navigationIcon = {
                    GlassTopBarIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    GlassTopBarTextButton(
                        text = "复制",
                        enabled = filtered.isNotEmpty(),
                        onClick = {
                            val text = formatDiagnosticLogLines(filtered)
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("运行日志", text))
                            scope.launch { snackbar.showSnackbar("已复制 ${filtered.size} 条日志") }
                        },
                    )
                    GlassTopBarTextButton(
                        text = "清空",
                        enabled = summary.count > 0 && clearBurstTrigger == 0,
                        onClick = { confirmClear = true },
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LiquidOutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::setSearchQuery,
                singleLine = true,
                label = { Text("筛选类别、状态或关键字") },
                modifier = Modifier.fillMaxWidth(),
            )

            if (categories.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LiquidFilterChip(
                        selected = selectedCategory == null,
                        onClick = { viewModel.setCategory(null) },
                        label = { Text("全部") },
                    )
                    categories.forEach { category ->
                        LiquidFilterChip(
                            selected = selectedCategory == category,
                            onClick = {
                                viewModel.setCategory(if (selectedCategory == category) null else category)
                            },
                            label = { Text(category) },
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (filtered.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.Info,
                        title = if (events.isEmpty()) "暂无运行日志" else "没有匹配的日志",
                        hint = if (events.isEmpty()) {
                            "同步、刷新、提醒或日历异常记录会脱敏保存在本机"
                        } else {
                            "试试清除筛选条件或搜索关键字"
                        },
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("runtime_log_list"),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(filtered) { event ->
                            RuntimeLogCard(event = event)
                        }
                    }
                }

                DissolveBurst(
                    trigger = clearBurstTrigger,
                    color = MaterialTheme.colorScheme.error,
                    particleCount = 28,
                    durationMs = 520,
                    onFinished = {
                        clearBurstTrigger = 0
                        viewModel.clearLogs()
                    },
                )
            }
        }
    }

    if (confirmClear) {
        LiquidAlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空运行日志", style = MaterialTheme.typography.titleLarge) },
            text = { Text("确定要清空本地保存的 ${summary.count} 条脱敏运行日志吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        clearBurstTrigger += 1
                    },
                ) {
                    Text("清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun RuntimeLogCard(event: DiagnosticEvent) {
    val isOk = event.outcome.equals("ok", ignoreCase = true) ||
        event.outcome.equals("success", ignoreCase = true)
    val badgeColor = if (isOk) successColor() else MaterialTheme.colorScheme.error
    val timeText = remember(event.timestamp) {
        runCatching {
            Instant.ofEpochMilli(event.timestamp)
                .atZone(ZoneId.systemDefault())
                .format(LOG_TIMESTAMP_FORMAT)
        }.getOrDefault(event.timestamp.toString())
    }

    GlassSurface(
        shape = RoundedCornerShape(14.dp),
        material = GlassMaterial.CONTENT,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = event.category.ifBlank { "GENERAL" },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (event.outcome.isNotBlank()) {
                        Text(
                            text = event.outcome,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = badgeColor,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(badgeColor.copy(alpha = 0.14f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(
                    text = "${event.durationMs}ms · $timeText",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (event.errorType.isNotBlank()) {
                Text(
                    text = event.errorType,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (event.message.isNotBlank()) {
                Text(
                    text = event.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.height(0.dp))
        }
    }
}
