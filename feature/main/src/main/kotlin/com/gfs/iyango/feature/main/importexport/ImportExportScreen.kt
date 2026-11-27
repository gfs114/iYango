@file:OptIn(ExperimentalMaterial3Api::class)

package com.gfs.iyango.feature.main.importexport

import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassTopBarIconButton
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidAlertDialog
import com.gfs.iyango.core.designsystem.liquidglass.LiquidButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidCheckbox
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedTextField
import com.gfs.iyango.core.designsystem.liquidglass.LiquidRadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gfs.iyango.core.designsystem.component.SectionCard
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.domain.ExternalScheduleImportBus
import com.gfs.iyango.core.domain.ImportWriteMode
import com.gfs.iyango.core.domain.QrEncodeResult
import com.gfs.iyango.core.domain.ScheduleImportItem
import com.gfs.iyango.core.domain.ScheduleImportPreview
import com.gfs.iyango.core.domain.ScheduleImportTargetInfo
import com.gfs.iyango.core.model.Timetable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * JSON 导入导出页（契约 §8）：备份当前课表 + 从文件导入（预览/模式/确认）。
 *
 * 文件读写全部经 SAF（系统文件选择器）在本机完成。
 */
@Composable
fun ImportExportScreen(
    onBack: () -> Unit,
    onOpenScheduleExport: () -> Unit = {},
    onOpenAiImport: () -> Unit = {},
    viewModel: ImportExportViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val pendingExport by viewModel.pendingExport.collectAsStateWithLifecycle()
    val shareState by viewModel.shareState.collectAsStateWithLifecycle()
    val pendingQrSaveName by viewModel.pendingQrSaveName.collectAsStateWithLifecycle()
    var showImportCodeDialog by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var importCodeInput by remember { mutableStateOf("") }
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val readError by viewModel.readError.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val targetId by viewModel.targetId.collectAsStateWithLifecycle()
    val targetInfo by viewModel.targetInfo.collectAsStateWithLifecycle()
    val selectedKeys by viewModel.selectedKeys.collectAsStateWithLifecycle()
    val applySemester by viewModel.applySemester.collectAsStateWithLifecycle()
    val applyTimeSlots by viewModel.applyTimeSlots.collectAsStateWithLifecycle()
    val applyDisplayConfig by viewModel.applyDisplayConfig.collectAsStateWithLifecycle()
    val localTimetables by viewModel.localTimetables.collectAsStateWithLifecycle()
    val confirmDuplicate by viewModel.confirmDuplicate.collectAsStateWithLifecycle()
    val doneMessage by viewModel.doneMessage.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    // SAF：导出创建文件。回调只登记目标 Uri，实际写入由下方 LaunchedEffect 在 IO 线程执行。
    var exportTargetUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) {
            viewModel.consumePendingExport()
        } else {
            exportTargetUri = uri
        }
    }
    LaunchedEffect(exportTargetUri, pendingExport) {
        val uri = exportTargetUri ?: return@LaunchedEffect
        val payload = pendingExport ?: return@LaunchedEffect
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(payload.json.toByteArray(Charsets.UTF_8))
                }
            }
        }
        exportTargetUri = null
        viewModel.consumePendingExport()
    }
    // SAF：导入选择文件。
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) viewModel.readFile(bytes)
            }
        }
    }

    val qrGalleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val decoded = withContext(Dispatchers.IO) { decodeQrFromUri(context, uri) }
                if (decoded == null) {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar("未在图片中识别到有效二维码")
                } else {
                    viewModel.importFromShareCode(decoded)
                }
            }
        }
    }

    val externalPayload by ExternalScheduleImportBus.pendingFlow.collectAsStateWithLifecycle()
    LaunchedEffect(externalPayload) {
        if (externalPayload != null) {
            val consumed = ExternalScheduleImportBus.consume()
            if (consumed != null) {
                viewModel.readFile(consumed.bytes)
            }
        }
    }
    LaunchedEffect(pendingExport) {
        pendingExport?.let { exportLauncher.launch(it.fileName) }
    }

    var qrSaveUri by remember { mutableStateOf<Uri?>(null) }
    val qrSaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri ->
        if (uri == null) {
            viewModel.consumePendingQrSave()
        } else {
            qrSaveUri = uri
        }
    }
    LaunchedEffect(pendingQrSaveName) {
        pendingQrSaveName?.let { qrSaveLauncher.launch(it) }
    }
    LaunchedEffect(qrSaveUri, shareState) {
        val uri = qrSaveUri ?: return@LaunchedEffect
        val ready = (shareState?.qrResult as? QrEncodeResult.Ready) ?: return@LaunchedEffect
        val saved = withContext(Dispatchers.IO) {
            runCatching {
                val bitmap = renderQrBitmap(ready.matrix)
                try {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    } ?: false
                } finally {
                    bitmap.recycle()
                }
            }.getOrDefault(false)
        }
        if (!saved) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
        }
        qrSaveUri = null
        viewModel.consumePendingQrSave()
        snackbar.showSnackbar(if (saved) "二维码图片已保存" else "二维码图片保存失败")
    }

    GlassScaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            GlassTopBar(
                title = "导入与导出",
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

            SectionCard(title = "导入") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiquidButton(
                        onClick = onOpenAiImport,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text("AI 拍图 / 文本导入") }
                    LiquidOutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text("从文件导入（JSON / ICS / WakeUp / CSV）") }
                    LiquidOutlinedButton(
                        onClick = {
                            importCodeInput = ""
                            showImportCodeDialog = true
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text("从分享口令导入") }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        LiquidOutlinedButton(
                            onClick = { showQrScanner = true },
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) { Text("扫码导入") }
                        LiquidOutlinedButton(
                            onClick = { qrGalleryLauncher.launch("image/*") },
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) { Text("相册识码") }
                    }
                    readError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    preview?.let { current ->
                        ImportPreviewArea(
                            preview = current,
                            mode = mode,
                            targetId = targetId,
                            targetInfo = targetInfo,
                            selectedKeys = selectedKeys,
                            applySemester = applySemester,
                            applyTimeSlots = applyTimeSlots,
                            applyDisplayConfig = applyDisplayConfig,
                            localTimetables = localTimetables,
                            onMode = viewModel::setMode,
                            onTarget = viewModel::setTarget,
                            onToggleRow = viewModel::toggleRow,
                            onSelectVisible = viewModel::selectVisible,
                            onClearSelection = viewModel::clearSelection,
                            onApplySemester = { viewModel.applySemester.value = it },
                            onApplyTimeSlots = { viewModel.applyTimeSlots.value = it },
                            onApplyDisplayConfig = { viewModel.applyDisplayConfig.value = it },
                            onConfirm = { viewModel.commit() },
                            onCancel = viewModel::dismissPreview,
                        )
                    }
                }
            }

            SectionCard(title = "导出") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiquidButton(
                        onClick = onOpenScheduleExport,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text("导出课表图片 / ICS") }
                    LiquidOutlinedButton(
                        onClick = viewModel::requestShareCode,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text("分享口令 / 二维码") }
                    LiquidOutlinedButton(
                        onClick = viewModel::requestExport,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text("导出 JSON 备份") }
                }
            }
        }
    }

    if (confirmDuplicate) {
        LiquidAlertDialog(
            onDismissRequest = viewModel::dismissDuplicate,
            title = { Text("重复导入", style = MaterialTheme.typography.titleLarge) },
            text = { Text("该文件此前已导入过。确定要再次导入吗？") },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDuplicate) { Text("仍要导入") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDuplicate) { Text("取消") }
            },
        )
    }

    shareState?.let { share ->
        LiquidAlertDialog(
            onDismissRequest = viewModel::dismissShareCode,
            title = { Text("分享课表", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(share.timetableName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "课程 ${share.courseCount} 条 作息 ${share.timeSlotCount} 节",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    when (val qr = share.qrResult) {
                        is QrEncodeResult.Ready -> {
                            QrCodeCanvas(
                                matrix = qr.matrix,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = 240.dp),
                            )
                        }
                        is QrEncodeResult.TooLarge -> {
                            Text(
                                "课程数量较多，请复制口令分享",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (share.qrResult is QrEncodeResult.Ready) {
                        TextButton(onClick = viewModel::requestSaveQrImage) { Text("保存图片") }
                    }
                    TextButton(
                        onClick = {
                            val copied = copyShareText(context, share.shareCode)
                            scope.launch {
                                snackbar.currentSnackbarData?.dismiss()
                                snackbar.showSnackbar(if (copied) "已复制课表口令" else "复制口令失败")
                            }
                        },
                    ) { Text("复制口令") }
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissShareCode) { Text("关闭") }
            },
        )
    }

    if (showImportCodeDialog) {
        LiquidAlertDialog(
            onDismissRequest = { showImportCodeDialog = false },
            title = { Text("从分享口令导入", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LiquidOutlinedTextField(
                        value = importCodeInput,
                        onValueChange = { importCodeInput = it },
                        minLines = 3,
                        maxLines = 6,
                        placeholder = { Text("IYANGO-TT1:...") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LiquidOutlinedButton(
                        onClick = {
                            val text = readClipboardText(context)
                            if (text != null) importCodeInput = text
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("粘贴剪贴板") }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.importFromShareCode(importCodeInput)
                        showImportCodeDialog = false
                    },
                ) { Text("解析预览") }
            },
            dismissButton = {
                TextButton(onClick = { showImportCodeDialog = false }) { Text("取消") }
            },
        )
    }

    if (showQrScanner) {
        QrScannerDialog(
            onScanned = { decoded ->
                showQrScanner = false
                viewModel.importFromShareCode(decoded)
            },
            onPickFromGallery = {
                qrGalleryLauncher.launch("image/*")
            },
            onDismiss = { showQrScanner = false },
        )
    }
}

private fun copyShareText(context: Context, text: String): Boolean =
    runCatching {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("iYango 课表口令", text))
        true
    }.getOrDefault(false)

private fun readClipboardText(context: Context): String? =
    runCatching {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        clip.getItemAt(0).coerceToText(context)?.toString()
    }.getOrNull()

/**
 * 导入预览区（§8 + F11）：数量/解析问题/逐条选择/写入模式/配置采纳/确认。
 *
 * 选择用预览行键（[ScheduleImportItem.rowKey]），搜索只影响可见行；"全选可见"不动
 * 被搜索隐藏的已选行，"全不选"清空全部。
 */
@Composable
internal fun ImportPreviewArea(
    preview: ScheduleImportPreview,
    mode: ImportWriteMode,
    targetId: Long?,
    targetInfo: ScheduleImportTargetInfo?,
    selectedKeys: Set<String>,
    applySemester: Boolean,
    applyTimeSlots: Boolean,
    applyDisplayConfig: Boolean,
    localTimetables: List<Timetable>,
    onMode: (ImportWriteMode) -> Unit,
    onTarget: (Long?) -> Unit,
    onToggleRow: (String) -> Unit,
    onSelectVisible: (Collection<String>) -> Unit,
    onClearSelection: () -> Unit,
    onApplySemester: (Boolean) -> Unit,
    onApplyTimeSlots: (Boolean) -> Unit,
    onApplyDisplayConfig: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    var showIssues by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val filtered = preview.items.filter { it.matches(query) }
    val selectedCount = preview.items.count { it.rowKey in selectedKeys }
    val hiddenSelected = preview.items.count { it.rowKey in selectedKeys && it !in filtered }
    val localTables = localTimetables.filter { it.source == "LOCAL" }
    val partialReplace = mode == ImportWriteMode.REPLACE && selectedCount != preview.items.size
    val targetUsable = targetInfo?.isLocal == true

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "课程 ${preview.courseCount} 条 作息 ${preview.timeSlotCount} 节" +
                (preview.semesterName?.let { " 学期「$it」" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        if (preview.issueCount > 0) {
            Text(
                text = if (showIssues) "收起解析提示（${preview.issueCount}）" else "解析提示（${preview.issueCount}）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { showIssues = !showIssues },
            )
            if (showIssues) {
                preview.issues.take(10).forEach {
                    Text(
                        "$it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (preview.duplicateExisting) {
            Text(
                "该文件此前已导入过",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        LiquidOutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("按名称/教师/教室搜索") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = { onSelectVisible(filtered.map { it.rowKey }) }) {
                Text("全选可见（${filtered.size}）")
            }
            TextButton(onClick = onClearSelection) { Text("全不选") }
            Text(
                "已选 $selectedCount/${preview.items.size}" +
                    if (hiddenSelected > 0) " 隐藏 ${hiddenSelected}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (filtered.isEmpty()) {
            Text(
                "没有匹配的课程",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                items(filtered, key = { it.rowKey }) { item ->
                    ImportRowItem(
                        item = item,
                        checked = item.rowKey in selectedKeys,
                        onToggle = { onToggleRow(item.rowKey) },
                    )
                }
            }
        }

        Text("写入模式", style = MaterialTheme.typography.labelMedium)
        ModeRow(
            selected = mode == ImportWriteMode.CREATE,
            title = "新建课表导入",
            onClick = { onMode(ImportWriteMode.CREATE) },
        )
        localTables.forEach { timetable ->
            ModeRow(
                selected = mode == ImportWriteMode.APPEND && targetId == timetable.id,
                title = "追加到「${timetable.name}」",
                onClick = {
                    onMode(ImportWriteMode.APPEND)
                    onTarget(timetable.id)
                },
            )
            ModeRow(
                selected = mode == ImportWriteMode.REPLACE && targetId == timetable.id,
                title = "替换「${timetable.name}」",
                onClick = {
                    onMode(ImportWriteMode.REPLACE)
                    onTarget(timetable.id)
                },
            )
        }
        if (mode != ImportWriteMode.CREATE) {
            if (targetInfo == null) {
                Text(
                    "请选择目标课表",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    "目标：${targetInfo.courseCount} 条课程 ${targetInfo.timeSlotCount} 节作息 学期" +
                        (targetInfo.semesterName?.let { "「$it」" } ?: "未设置"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!targetUsable) {
                    Text(
                        "学校绑定课表不支持追加/替换",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        if (mode == ImportWriteMode.APPEND && targetInfo?.semesterName != null && preview.semesterName != null) {
            Text(
                "追加不会覆盖目标已有学期（将保留「${targetInfo.semesterName}」）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (mode == ImportWriteMode.APPEND && applyTimeSlots &&
            (targetInfo?.timeSlotCount ?: 0) > 0 && preview.timeSlotCount > 0
        ) {
            Text(
                "采用文件作息会替换目标作息，已有课程的上下课时间可能变化",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (partialReplace) {
            Text(
                "替换导入需要选择全部课程",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        val semesterForcedByAppend =
            mode == ImportWriteMode.APPEND && targetInfo?.semesterName != null
        if (preview.semesterName != null) {
            CheckRow(
                checked = applySemester && !semesterForcedByAppend,
                enabled = !semesterForcedByAppend,
                title = "采用文件学期「${preview.semesterName}」",
                onCheckedChange = onApplySemester,
            )
        }
        if (preview.timeSlotCount > 0) {
            CheckRow(
                checked = applyTimeSlots,
                title = "采用文件作息（${preview.timeSlotCount} 节）",
                onCheckedChange = onApplyTimeSlots,
            )
        }
        if (preview.hasDisplayConfig) {
            CheckRow(
                checked = applyDisplayConfig,
                title = "采用文件课表显示配置",
                onCheckedChange = onApplyDisplayConfig,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LiquidOutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
            LiquidButton(
                onClick = onConfirm,
                enabled = selectedCount > 0 && !partialReplace &&
                    (mode == ImportWriteMode.CREATE || targetUsable),
                modifier = Modifier.weight(1f),
            ) { Text("确认导入") }
        }
    }
}

/** 搜索匹配：名称/教师/教室/星期/节次。 */
private fun ScheduleImportItem.matches(query: String): Boolean {
    val text = query.trim()
    if (text.isEmpty()) return true
    return name.contains(text, ignoreCase = true) ||
        teacher.contains(text, ignoreCase = true) ||
        classroom.contains(text, ignoreCase = true) ||
        dayLabel(dayOfWeek).contains(text) ||
        "$startSection-$endSection".contains(text)
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

@Composable
private fun ImportRowItem(
    item: ScheduleImportItem,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiquidCheckbox(checked = checked, onCheckedChange = null)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.name.ifBlank { "未命名课程" },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                buildString {
                    append(dayLabel(item.dayOfWeek))
                    append(" 第${item.startSection}-${item.endSection}节")
                    append(" 第${item.startWeek}-${item.endWeek}周")
                    when (item.weekType) {
                        1 -> append(" 单周")
                        2 -> append(" 双周")
                    }
                    if (item.timeMode == 2 && item.date.isNotBlank()) append(" ${item.date}")
                    if (item.isAdjustment) append(" 调整")
                    if (item.teacher.isNotBlank()) append(" ${item.teacher}")
                    if (item.classroom.isNotBlank()) append(" ${item.classroom}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModeRow(selected: Boolean, title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiquidRadioButton(selected = selected, onClick = null)
        Text(title, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CheckRow(
    checked: Boolean,
    title: String,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiquidCheckbox(
            checked = checked,
            enabled = enabled,
            onCheckedChange = null,
        )
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 亮色预览。 */
@Preview(name = "导入导出 - 亮色", showBackground = true)
@Composable
private fun ImportExportLightPreview() {
    IYangoThemePreview(darkTheme = false) {
        Surface {
            SectionCard(title = "从文件导入") {
                ImportPreviewArea(
                    preview = ScheduleImportPreview(
                        courseCount = 24,
                        issueCount = 2,
                        issues = listOf("未知字段 xxx 已忽略", "课程「X」节次越界已收敛"),
                        semesterName = "2026-2027-1",
                        timeSlotCount = 11,
                        fileDigest = "preview",
                        duplicateExisting = false,
                        token = "preview",
                        items = listOf(
                            ScheduleImportItem(
                                rowKey = "r0000-aaaa",
                                fileId = 1,
                                name = "高等数学",
                                teacher = "陈老师",
                                classroom = "A301",
                                dayOfWeek = 1,
                                startSection = 1,
                                endSection = 2,
                                startWeek = 1,
                                endWeek = 16,
                                weekType = 0,
                                isAdjustment = false,
                            ),
                        ),
                        hasDisplayConfig = true,
                    ),
                    mode = ImportWriteMode.CREATE,
                    targetId = null,
                    targetInfo = null,
                    selectedKeys = setOf("r0000-aaaa"),
                    applySemester = true,
                    applyTimeSlots = true,
                    applyDisplayConfig = true,
                    localTimetables = listOf(Timetable(id = 1, name = "我的课表")),
                    onMode = {},
                    onTarget = {},
                    onToggleRow = {},
                    onSelectVisible = {},
                    onClearSelection = {},
                    onApplySemester = {},
                    onApplyTimeSlots = {},
                    onApplyDisplayConfig = {},
                    onConfirm = {},
                    onCancel = {},
                )
            }
        }
    }
}

/** 暗色预览。 */
@Preview(name = "导入导出 - 暗色", showBackground = true)
@Composable
private fun ImportExportDarkPreview() {
    IYangoThemePreview(darkTheme = true) {
        Surface {
            SectionCard(title = "导出") {
                LiquidOutlinedButton(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text("导出 JSON 备份") }
            }
        }
    }
}
