@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gfs.iyango.feature.main.schedule

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassTopBarIconButton
import com.gfs.iyango.core.designsystem.component.GlassTopBarTextButton
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidFilterChip
import com.gfs.iyango.core.domain.CourseRawHtmlRecord
import com.gfs.iyango.core.domain.CourseRawStorePort
import com.gfs.iyango.core.domain.ScheduleDataPort
import com.gfs.iyango.core.domain.ScheduleTransferPort
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/** 课表原文查看器页签。 */
enum class CourseRawTab(val label: String) {
    JSON("结构化 JSON"),
    HTML("教务原始 HTML"),
}

data class CourseRawUiState(
    val loading: Boolean = true,
    val timetableName: String = "",
    val jsonText: String = "",
    val jsonError: String? = null,
    val htmlRecord: CourseRawHtmlRecord? = null,
)

@HiltViewModel
class CourseRawViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val scheduleData: ScheduleDataPort,
    private val scheduleTransfer: ScheduleTransferPort,
    private val courseRawStore: CourseRawStorePort,
) : ViewModel() {

    private val timetableId: Long = savedStateHandle.get<Long>("timetableId") ?: 0L

    private val _state = MutableStateFlow(CourseRawUiState())
    val state: StateFlow<CourseRawUiState> = _state.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        if (timetableId <= 0L) {
            _state.value = CourseRawUiState(loading = false, jsonError = "当前暂无有效课表")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val name = runCatching {
                scheduleData.observeTimetable(timetableId).firstOrNull()?.name.orEmpty()
            }.getOrDefault("")
            val exported = runCatching { scheduleTransfer.export(timetableId).json }
            val html = runCatching { courseRawStore.loadHtml(timetableId) }.getOrNull()
            _state.value = CourseRawUiState(
                loading = false,
                timetableName = name,
                jsonText = exported.getOrDefault(""),
                jsonError = exported.exceptionOrNull()?.message,
                htmlRecord = html,
            )
        }
    }
}

/** 查看课表结构化 JSON 与最近一次教务同步的原始 HTML。 */
@Composable
fun CourseRawScreen(
    onBack: () -> Unit,
    viewModel: CourseRawViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(CourseRawTab.JSON) }

    val activeText = when (selectedTab) {
        CourseRawTab.JSON -> state.jsonText
        CourseRawTab.HTML -> state.htmlRecord?.html.orEmpty()
    }

    GlassScaffold(
        topBar = {
            GlassTopBar(
                title = state.timetableName.ifBlank { "课表原文" },
                subtitle = if (state.timetableName.isNotBlank()) "课表原文 (HTML / JSON)" else null,
                navigationIcon = {
                    GlassTopBarIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    GlassTopBarTextButton(
                        text = "复制",
                        enabled = activeText.isNotEmpty(),
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("课表原文", activeText))
                            scope.launch { snackbar.showSnackbar("已复制到剪贴板") }
                        },
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CourseRawTab.entries.forEach { tab ->
                    LiquidFilterChip(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        label = { Text(tab.label) },
                    )
                }
            }

            when (selectedTab) {
                CourseRawTab.JSON -> {
                    when {
                        state.jsonError != null -> Text(
                            text = "导出 JSON 失败：${state.jsonError}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        state.jsonText.isEmpty() && !state.loading -> Text(
                            text = "暂无可展示的结构化 JSON 数据",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> RawCodeBox(text = state.jsonText)
                    }
                }

                CourseRawTab.HTML -> {
                    val record = state.htmlRecord
                    if (record == null && !state.loading) {
                        GlassSurface(
                            material = GlassMaterial.CONTENT,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "尚未缓存教务原始 HTML，重新从教务同步一次课表后即可查看",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    } else if (record != null) {
                        val timeText = remember(record.savedAt) {
                            runCatching {
                                Instant.ofEpochMilli(record.savedAt)
                                    .atZone(ZoneId.systemDefault())
                                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                            }.getOrDefault("")
                        }
                        Text(
                            text = buildString {
                                if (record.term.isNotBlank()) append("学期：${record.term}  ")
                                if (timeText.isNotBlank()) append("拉取时间：$timeText")
                            }.ifBlank { "已缓存最近一次教务同步原文" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        RawCodeBox(text = record.html)
                    }
                }
            }
        }
    }
}

@Composable
private fun RawCodeBox(text: String) {
    val preview = remember(text) {
        if (text.length > DISPLAY_PREVIEW_LIMIT) text.take(DISPLAY_PREVIEW_LIMIT) else text
    }
    Column(modifier = Modifier.fillMaxSize()) {
        if (text.length > DISPLAY_PREVIEW_LIMIT) {
            Text(
                text = "原文共 ${text.length} 字符，当前展示前 $DISPLAY_PREVIEW_LIMIT 字符（点右上角「复制」可复制完整内容）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
        GlassSurface(
            material = GlassMaterial.CONTENT,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = preview,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

private const val DISPLAY_PREVIEW_LIMIT = 100_000
