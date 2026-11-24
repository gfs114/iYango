@file:OptIn(ExperimentalMaterial3Api::class)

package com.gfs.iyango.feature.main.export

import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassTopBarIconButton
import com.gfs.iyango.core.designsystem.component.GlassTopBarTextButton
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidDropdownMenu
import com.gfs.iyango.core.designsystem.liquidglass.LiquidFilterChip
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gfs.iyango.core.designsystem.component.SectionCard
import com.gfs.iyango.core.designsystem.component.SettingSwitchRow
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 课表图片与 ICS 导出页（E04）。
 *
 * 所有内容来自 ViewModel 冻结的同一周实例集合；图片为离屏绘制，不截屏。文件写入使用
 * SAF（系统文件选择器），写入失败时删除未完成的文档，避免留下损坏的正式文件。
 */
@Composable
fun ScheduleExportScreen(
    onBack: () -> Unit,
    viewModel: ScheduleExportViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pendingImageName by viewModel.pendingImageName.collectAsStateWithLifecycle()
    val pendingIcs by viewModel.pendingIcs.collectAsStateWithLifecycle()

    var imageTarget by remember { mutableStateOf<Uri?>(null) }
    var imageBitmapToSave by remember { mutableStateOf<Bitmap?>(null) }
    var icsTarget by remember { mutableStateOf<Uri?>(null) }

    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri ->
        if (uri == null) {
            imageBitmapToSave = null
            viewModel.consumePendingImage()
        } else {
            imageTarget = uri
        }
    }
    val icsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/calendar"),
    ) { uri ->
        if (uri == null) {
            viewModel.consumePendingIcs()
        } else {
            icsTarget = uri
        }
    }

    LaunchedEffect(pendingImageName) {
        val name = pendingImageName ?: return@LaunchedEffect
        val bitmap = viewModel.previewBitmap()
        if (bitmap == null) {
            viewModel.consumePendingImage()
            snackbar.showSnackbar("图片还未生成完成")
            return@LaunchedEffect
        }
        imageBitmapToSave = bitmap
        imageLauncher.launch(name)
    }
    LaunchedEffect(imageTarget, imageBitmapToSave) {
        val uri = imageTarget ?: return@LaunchedEffect
        val bitmap = imageBitmapToSave ?: return@LaunchedEffect
        val ok = writeBitmap(context, uri, bitmap)
        if (!ok) deleteIncompleteDocument(context, uri)
        imageTarget = null
        imageBitmapToSave = null
        viewModel.consumePendingImage()
        snackbar.showSnackbar(if (ok) "图片已保存" else "图片保存失败，未保留损坏文件")
    }
    LaunchedEffect(pendingIcs) {
        val payload = pendingIcs ?: return@LaunchedEffect
        icsLauncher.launch(payload.fileName)
    }
    LaunchedEffect(icsTarget, pendingIcs) {
        val uri = icsTarget ?: return@LaunchedEffect
        val payload = pendingIcs ?: return@LaunchedEffect
        val ok = writeBytes(context, uri, payload.bytes)
        if (!ok) deleteIncompleteDocument(context, uri)
        icsTarget = null
        viewModel.consumePendingIcs()
        snackbar.showSnackbar(if (ok) "ICS 文件已保存" else "ICS 保存失败，未保留损坏文件")
    }

    GlassScaffold(
        topBar = {
            GlassTopBar(
                title = "导出课表",
                navigationIcon = {
                    GlassTopBarIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    GlassTopBarTextButton(text = "刷新数据", onClick = viewModel::refresh)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            state.error.takeIf { it.isNotBlank() }?.let { message ->
                GlassSurface(
                    shape = RoundedCornerShape(12.dp),
                    material = GlassMaterial.CONTENT,
                    tintColor = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::consumeError) { Text("知道了") }
                    }
                }
            }

            if (!state.hasTimetable) {
                SectionCard(title = "还没有课表") {
                    LiquidOutlinedButton(onClick = viewModel::refresh, modifier = Modifier.fillMaxWidth()) {
                        Text("重新读取")
                    }
                }
            } else {
                ExportRangeCard(
                    state = state,
                    onTimetable = viewModel::selectTimetable,
                    onWeek = viewModel::selectWeek,
                )
                ImageOptionsCard(
                    state = state,
                    onShowTeacher = viewModel::setShowTeacher,
                    onShowClassroom = viewModel::setShowClassroom,
                    onResolution = viewModel::setResolution,
                )
                PreviewCard(state)
                ExportActionCard(
                    state = state,
                    onSaveImage = viewModel::requestSaveImage,
                    onExportIcs = viewModel::requestExportIcs,
                )
            }
        }
    }
}

@Composable
private fun ExportRangeCard(
    state: ScheduleExportUiState,
    onTimetable: (Long) -> Unit,
    onWeek: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard(title = "导出范围") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("课表", style = MaterialTheme.typography.labelMedium)
            Box {
                LiquidOutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        state.selectedTimetableName.ifBlank { "选择课表" },
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "切换课表")
                }
                LiquidDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    state.timetables.forEach { table ->
                        DropdownMenuItem(
                            text = { Text(table.name) },
                            leadingIcon = {
                                if (table.id == state.selectedTimetableId) {
                                    Icon(Icons.Filled.Check, contentDescription = null)
                                }
                            },
                            onClick = {
                                expanded = false
                                onTimetable(table.id)
                            },
                        )
                    }
                }
            }

            if (state.semester == null) {
                Text(
                    "该课表未设置学期，无法确定教学周。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("周次", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Text(
                        weekRangeText(state),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.totalWeeks) { index ->
                        val week = index + 1
                        LiquidFilterChip(
                            selected = week == state.week,
                            onClick = { onWeek(week) },
                            label = { Text("第${week}周") },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageOptionsCard(
    state: ScheduleExportUiState,
    onShowTeacher: (Boolean) -> Unit,
    onShowClassroom: (Boolean) -> Unit,
    onResolution: (ExportResolution) -> Unit,
) {
    SectionCard(title = "图片内容") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingSwitchRow(
                title = "显示教师",
                checked = state.showTeacher,
                onCheckedChange = onShowTeacher,
                enabled = state.semester != null,
            )
            SettingSwitchRow(
                title = "显示教室",
                checked = state.showClassroom,
                onCheckedChange = onShowClassroom,
                enabled = state.semester != null,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text("清晰度", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportResolution.entries.forEach { option ->
                    LiquidFilterChip(
                        selected = state.resolution == option,
                        onClick = { onResolution(option) },
                        label = { Text(option.label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewCard(state: ScheduleExportUiState) {
    SectionCard(title = "预览") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                state.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("正在读取课表…", style = MaterialTheme.typography.bodySmall)
                }
                state.rendering -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("正在生成图片…", style = MaterialTheme.typography.bodySmall)
                }
                state.previewBitmap != null -> {
                    val bitmap = state.previewBitmap
                    GlassSurface(
                        shape = RoundedCornerShape(10.dp),
                        material = GlassMaterial.CONTENT,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "课表图片预览",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(
                                    (bitmap.width.toFloat() / bitmap.height.toFloat())
                                        .takeIf { it > 0f } ?: 0.7f,
                                ),
                        )
                    }
                    Text(
                        "${state.previewWidth} × ${state.previewHeight} px " +
                            "${state.occurrences.size} 个课程安排",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> Text(
                    "暂无预览",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ExportActionCard(
    state: ScheduleExportUiState,
    onSaveImage: () -> Unit,
    onExportIcs: () -> Unit,
) {
    SectionCard(title = "导出") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LiquidButton(
                onClick = onSaveImage,
                enabled = state.canSaveImage,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("保存图片（PNG）") }
            LiquidOutlinedButton(
                onClick = onExportIcs,
                enabled = state.canExportIcs,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                if (state.busyIcs) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.size(8.dp))
                }
                Text(if (state.busyIcs) "正在生成 ICS…" else "导出 ICS 日历文件")
            }
            if (state.occurrences.isEmpty()) {
                Text(
                    "本周没有课程，ICS 导出已停用。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.icsEventCount > 0 || state.icsSkipped.isNotEmpty()) {
                Text(
                    "已生成 ${state.icsEventCount} 个事件",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                )
                state.icsSkipped.take(10).forEach { reason ->
                    Text(
                        "$reason",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.icsSkipped.size > 10) {
                    Text(
                        "…另有 ${state.icsSkipped.size - 10} 条跳过原因",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun weekRangeText(state: ScheduleExportUiState): String {
    val start = state.weekStart ?: return ""
    val end = start.plusDays(6)
    return "${DATE_FORMAT.format(start)} ~ ${DATE_FORMAT.format(end)}"
}

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

private suspend fun writeBytes(context: Context, uri: Uri, bytes: ByteArray): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            val stream = context.contentResolver.openOutputStream(uri, "w") ?: error("无法打开文件")
            stream.use {
                it.write(bytes)
                it.flush()
            }
        }.isSuccess
    }

private suspend fun writeBitmap(context: Context, uri: Uri, bitmap: Bitmap): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            val stream = context.contentResolver.openOutputStream(uri, "w") ?: error("无法打开文件")
            stream.use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "图片编码失败" }
            }
        }.isSuccess
    }

/** 写入失败时删除刚由 CreateDocument 建出的空/半成品文档。 */
private fun deleteIncompleteDocument(context: Context, uri: Uri) {
    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
}
