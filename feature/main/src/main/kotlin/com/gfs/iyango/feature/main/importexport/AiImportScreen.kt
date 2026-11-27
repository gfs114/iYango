@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.gfs.iyango.feature.main.importexport

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassTopBarIconButton
import com.gfs.iyango.core.designsystem.component.SectionCard
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidAlertDialog
import com.gfs.iyango.core.designsystem.liquidglass.LiquidButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidFilterChip
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedTextField
import com.gfs.iyango.core.domain.AiApiProtocol
import com.gfs.iyango.core.domain.AiImagePayload
import com.gfs.iyango.core.domain.AiModelInfo
import com.gfs.iyango.core.domain.AiProviderPreset
import com.gfs.iyango.core.domain.AiProviderProfile
import com.gfs.iyango.core.domain.ImportWriteMode
import com.gfs.iyango.core.domain.ScheduleImportPreview
import com.gfs.iyango.core.domain.ScheduleImportTargetInfo
import com.gfs.iyango.core.model.Timetable
import com.gfs.iyango.feature.main.ai.AiProviderAvatar
import com.gfs.iyango.feature.main.ai.compressAiImageUri
import com.gfs.iyango.feature.main.ai.decodeSelectedAiImages
import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object AiImportTags {
    const val CONNECTION_STRIP = "ai_import_connection_strip"
    const val CONFIG_SECTION = "ai_import_config_section"
    const val INPUT_SECTION = "ai_import_input_section"
    const val CAPTURE_BUTTON = "ai_import_capture_button"
    const val GALLERY_BUTTON = "ai_import_gallery_button"
    const val PARSE_BUTTON = "ai_import_parse_button"
    const val FETCH_MODELS_BUTTON = "ai_import_fetch_models_button"
    const val MODEL_PICKER_BUTTON = "ai_import_model_picker_button"
    const val PROVIDER_PICKER_BUTTON = "ai_import_provider_picker_button"
    const val API_KEY_INPUT = "ai_import_api_key_input"
    const val MODEL_INPUT = "ai_import_model_input"
}

/**
 * 拍图 / 文本智能识别导入页。
 */
@Composable
fun AiImportScreen(
    onBack: () -> Unit,
    onOpenAiConfig: (() -> Unit)? = null,
    viewModel: AiImportViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.reloadConfig()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()

    val protocol by viewModel.protocol.collectAsStateWithLifecycle()
    val baseUrl by viewModel.baseUrl.collectAsStateWithLifecycle()
    val model by viewModel.model.collectAsStateWithLifecycle()
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val configExpanded by viewModel.configExpanded.collectAsStateWithLifecycle()
    val configConfigured by viewModel.configConfigured.collectAsStateWithLifecycle()
    val testingConnection by viewModel.testingConnection.collectAsStateWithLifecycle()
    val connectionMessage by viewModel.connectionMessage.collectAsStateWithLifecycle()
    val fetchingModels by viewModel.fetchingModels.collectAsStateWithLifecycle()
    val availableModels by viewModel.availableModels.collectAsStateWithLifecycle()
    val modelsFetched by viewModel.modelsFetched.collectAsStateWithLifecycle()
    val showModelPicker by viewModel.showModelPicker.collectAsStateWithLifecycle()
    val showProviderPicker by viewModel.showProviderPicker.collectAsStateWithLifecycle()

    val textInput by viewModel.textInput.collectAsStateWithLifecycle()
    val attachedImages by viewModel.attachedImages.collectAsStateWithLifecycle()
    val aiWarnings by viewModel.aiWarnings.collectAsStateWithLifecycle()
    val parsing by viewModel.parsing.collectAsStateWithLifecycle()

    val preview by viewModel.preview.collectAsStateWithLifecycle()
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

    var pendingCaptureFile by remember { mutableStateOf<File?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val (decoded, skipped) = withContext(Dispatchers.IO) {
                decodeSelectedAiImages(context, uris, AiImportViewModel.MAX_IMAGES)
            }
            viewModel.addImages(decoded, skipped)
        }
    }

    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved ->
        val file = pendingCaptureFile
        pendingCaptureFile = null
        if (file == null) return@rememberLauncherForActivityResult
        scope.launch {
            val (decoded, skipped) = withContext(Dispatchers.IO) {
                try {
                    if (!saved) {
                        emptyList<AiImagePayload>() to 0
                    } else {
                        val payload = compressAiImageUri(context, Uri.fromFile(file), "拍照_${System.currentTimeMillis() % 10000}.jpg")
                        if (payload != null) listOf(payload) to 0 else emptyList<AiImagePayload>() to 1
                    }
                } finally {
                    file.delete()
                }
            }
            if (decoded.isNotEmpty() || skipped > 0) {
                viewModel.addImages(decoded, skipped)
            }
        }
    }

    AiImportContent(
        baseUrl = baseUrl,
        model = model,
        apiKey = apiKey,
        configExpanded = configExpanded,
        configConfigured = configConfigured,
        testingConnection = testingConnection,
        connectionMessage = connectionMessage,
        fetchingModels = fetchingModels,
        availableModels = availableModels,
        modelsFetched = modelsFetched,
        showModelPicker = showModelPicker,
        showProviderPicker = showProviderPicker,
        textInput = textInput,
        attachedImages = attachedImages,
        aiWarnings = aiWarnings,
        parsing = parsing,
        preview = preview,
        mode = mode,
        targetId = targetId,
        targetInfo = targetInfo,
        selectedKeys = selectedKeys,
        applySemester = applySemester,
        applyTimeSlots = applyTimeSlots,
        applyDisplayConfig = applyDisplayConfig,
        localTimetables = localTimetables,
        confirmDuplicate = confirmDuplicate,
        doneMessage = doneMessage,
        error = error,
        busy = busy,
        onBack = onBack,
        onToggleConfigExpanded = viewModel::toggleConfigExpanded,
        onBaseUrlChange = { viewModel.baseUrl.value = it },
        onModelChange = { viewModel.model.value = it },
        onApiKeyChange = { viewModel.apiKey.value = it },
        onApplyPreset = viewModel::applyPreset,
        onOpenProviderPicker = viewModel::openProviderPicker,
        onDismissProviderPicker = viewModel::dismissProviderPicker,
        onFetchModels = viewModel::fetchAvailableModels,
        onOpenModelPicker = viewModel::openModelPicker,
        onDismissModelPicker = viewModel::dismissModelPicker,
        onSelectModel = viewModel::selectModel,
        onSaveConfig = viewModel::saveConfig,
        onTestConnection = viewModel::testConnection,
        onClearApiKey = viewModel::clearApiKey,
        onCaptureClick = {
            runCatching {
                val dir = File(context.cacheDir, "ai_capture").apply { mkdirs() }
                val target = File(dir, "capture_${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    target,
                )
                pendingCaptureFile = target
                captureLauncher.launch(uri)
            }.onFailure {
                pendingCaptureFile?.delete()
                pendingCaptureFile = null
                viewModel.error.value = "无法启动相机：${it.message ?: "未知错误"}"
            }
        },
        onGalleryClick = { imagePickerLauncher.launch(arrayOf("image/*")) },
        onRemoveImage = viewModel::removeImage,
        onTextInputChange = { viewModel.textInput.value = it },
        onStartParse = viewModel::startParse,
        onCancelParse = viewModel::cancelParse,
        onMode = viewModel::setMode,
        onTarget = viewModel::setTarget,
        onToggleRow = viewModel::toggleRow,
        onSelectVisible = viewModel::selectVisible,
        onClearSelection = viewModel::clearSelection,
        onApplySemester = { viewModel.applySemester.value = it },
        onApplyTimeSlots = { viewModel.applyTimeSlots.value = it },
        onApplyDisplayConfig = { viewModel.applyDisplayConfig.value = it },
        onConfirmImport = { viewModel.commit() },
        onCancelPreview = viewModel::dismissPreview,
        onConfirmDuplicate = viewModel::confirmDuplicate,
        onDismissDuplicate = viewModel::dismissDuplicate,
        onConsumeError = viewModel::consumeError,
        onConsumeDone = viewModel::consumeDone,
        protocol = protocol,
        onProtocolChange = viewModel::selectProtocol,
        onOpenAiConfig = onOpenAiConfig,
        profiles = profiles,
        activeProfileId = activeProfileId,
        onSelectProfile = viewModel::selectProfile,
    )
}

@Composable
internal fun AiImportContent(
    baseUrl: String,
    model: String,
    apiKey: String,
    configExpanded: Boolean,
    configConfigured: Boolean,
    testingConnection: Boolean,
    connectionMessage: String?,
    fetchingModels: Boolean,
    availableModels: List<AiModelInfo>,
    modelsFetched: Boolean,
    showModelPicker: Boolean,
    showProviderPicker: Boolean,
    textInput: String,
    attachedImages: List<AiImagePayload>,
    aiWarnings: List<String>,
    parsing: Boolean,
    preview: ScheduleImportPreview?,
    mode: ImportWriteMode,
    targetId: Long?,
    targetInfo: ScheduleImportTargetInfo?,
    selectedKeys: Set<String>,
    applySemester: Boolean,
    applyTimeSlots: Boolean,
    applyDisplayConfig: Boolean,
    localTimetables: List<Timetable>,
    confirmDuplicate: Boolean,
    doneMessage: String?,
    error: String?,
    busy: Boolean,
    onBack: () -> Unit,
    onToggleConfigExpanded: () -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onApplyPreset: (AiProviderPreset) -> Unit,
    onOpenProviderPicker: () -> Unit,
    onDismissProviderPicker: () -> Unit,
    onFetchModels: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onDismissModelPicker: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSaveConfig: () -> Unit,
    onTestConnection: () -> Unit,
    onClearApiKey: () -> Unit,
    onCaptureClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onRemoveImage: (Int) -> Unit,
    onTextInputChange: (String) -> Unit,
    onStartParse: () -> Unit,
    onCancelParse: () -> Unit,
    onMode: (ImportWriteMode) -> Unit,
    onTarget: (Long?) -> Unit,
    onToggleRow: (String) -> Unit,
    onSelectVisible: (Collection<String>) -> Unit,
    onClearSelection: () -> Unit,
    onApplySemester: (Boolean) -> Unit,
    onApplyTimeSlots: (Boolean) -> Unit,
    onApplyDisplayConfig: (Boolean) -> Unit,
    onConfirmImport: () -> Unit,
    onCancelPreview: () -> Unit,
    onConfirmDuplicate: () -> Unit,
    onDismissDuplicate: () -> Unit,
    onConsumeError: () -> Unit,
    onConsumeDone: () -> Unit,
    protocol: AiApiProtocol = AiApiProtocol.OPENAI_CHAT,
    onProtocolChange: (AiApiProtocol) -> Unit = {},
    onOpenAiConfig: (() -> Unit)? = null,
    profiles: List<AiProviderProfile> = listOf(AiProviderProfile()),
    activeProfileId: String = AiProviderProfile.DEFAULT_ID,
    onSelectProfile: (String) -> Unit = {},
) {
    GlassScaffold(
        topBar = {
            GlassTopBar(
                title = "AI 导入",
                navigationIcon = {
                    GlassTopBarIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (onOpenAiConfig != null) {
                        GlassTopBarIconButton(onClick = onOpenAiConfig) {
                            Icon(Icons.Filled.Settings, contentDescription = "AI 接口")
                        }
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
            error?.let { msg ->
                AiStatusBanner(message = msg, isError = true, onDismiss = onConsumeError)
            }
            doneMessage?.let { msg ->
                AiStatusBanner(message = msg, isError = false, onDismiss = onConsumeDone)
            }

            if (configConfigured && !configExpanded) {
                AiConnectionStrip(
                    baseUrl = baseUrl,
                    model = model,
                    onConfigure = onToggleConfigExpanded,
                )
                if (profiles.size > 1) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        profiles.forEach { profile ->
                            LiquidFilterChip(
                                selected = profile.id == activeProfileId,
                                onClick = { onSelectProfile(profile.id) },
                                label = { Text(profile.name) },
                            )
                        }
                    }
                }
            } else {
                AiConfigSection(
                    protocol = protocol,
                    baseUrl = baseUrl,
                    model = model,
                    apiKey = apiKey,
                    configConfigured = configConfigured,
                    testingConnection = testingConnection,
                    connectionMessage = connectionMessage,
                    fetchingModels = fetchingModels,
                    availableModels = availableModels,
                    modelsFetched = modelsFetched,
                    onProtocolChange = onProtocolChange,
                    onToggleConfigExpanded = onToggleConfigExpanded,
                    onBaseUrlChange = onBaseUrlChange,
                    onModelChange = onModelChange,
                    onApiKeyChange = onApiKeyChange,
                    onApplyPreset = onApplyPreset,
                    onOpenProviderPicker = onOpenProviderPicker,
                    onFetchModels = onFetchModels,
                    onOpenModelPicker = onOpenModelPicker,
                    onSaveConfig = onSaveConfig,
                    onTestConnection = onTestConnection,
                    onClearApiKey = onClearApiKey,
                )
            }

            AiInputSection(
                textInput = textInput,
                attachedImages = attachedImages,
                parsing = parsing,
                busy = busy,
                onCaptureClick = onCaptureClick,
                onGalleryClick = onGalleryClick,
                onRemoveImage = onRemoveImage,
                onTextInputChange = onTextInputChange,
                onStartParse = onStartParse,
                onCancelParse = onCancelParse,
            )

            preview?.let { current ->
                SectionCard(title = "识别预览") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        aiWarnings.forEach { warn ->
                            Text(
                                warn,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
                            onMode = onMode,
                            onTarget = onTarget,
                            onToggleRow = onToggleRow,
                            onSelectVisible = onSelectVisible,
                            onClearSelection = onClearSelection,
                            onApplySemester = onApplySemester,
                            onApplyTimeSlots = onApplyTimeSlots,
                            onApplyDisplayConfig = onApplyDisplayConfig,
                            onConfirm = onConfirmImport,
                            onCancel = onCancelPreview,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmDuplicate) {
        LiquidAlertDialog(
            onDismissRequest = onDismissDuplicate,
            title = { Text("重复导入", style = MaterialTheme.typography.titleLarge) },
            text = { Text("该识别结果此前已导入过。确定要再次导入吗？") },
            confirmButton = {
                TextButton(onClick = onConfirmDuplicate) { Text("仍要导入") }
            },
            dismissButton = {
                TextButton(onClick = onDismissDuplicate) { Text("取消") }
            },
        )
    }

    if (showProviderPicker) {
        AiProviderPickerDialog(
            currentBaseUrl = baseUrl,
            onSelect = onApplyPreset,
            onDismiss = onDismissProviderPicker,
        )
    }

    if (showModelPicker) {
        AiModelPickerDialog(
            models = availableModels,
            selectedModelId = model,
            onSelect = onSelectModel,
            onDismiss = onDismissModelPicker,
        )
    }
}

@Composable
private fun AiStatusBanner(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit,
) {
    GlassSurface(
        shape = RoundedCornerShape(12.dp),
        material = GlassMaterial.CONTENT,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}

@Composable
private fun AiConnectionStrip(
    baseUrl: String,
    model: String,
    onConfigure: () -> Unit,
) {
    val hostLabel = remember(baseUrl) {
        runCatching { URI(baseUrl.trim()).host }.getOrNull().orEmpty().ifBlank { "未配置" }
    }
    val modelLabel = model.ifBlank { "默认模型" }
    GlassSurface(
        shape = RoundedCornerShape(16.dp),
        material = GlassMaterial.CONTENT,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AiImportTags.CONNECTION_STRIP)
            .semantics { contentDescription = "接口配置，$modelLabel，$hostLabel" },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "$modelLabel @ $hostLabel",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onConfigure) { Text("配置") }
        }
    }
}

@Composable
private fun AiConfigSection(
    protocol: AiApiProtocol,
    baseUrl: String,
    model: String,
    apiKey: String,
    configConfigured: Boolean,
    testingConnection: Boolean,
    connectionMessage: String?,
    fetchingModels: Boolean,
    availableModels: List<AiModelInfo>,
    modelsFetched: Boolean,
    onProtocolChange: (AiApiProtocol) -> Unit,
    onToggleConfigExpanded: () -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onApplyPreset: (AiProviderPreset) -> Unit,
    onOpenProviderPicker: () -> Unit,
    onFetchModels: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onSaveConfig: () -> Unit,
    onTestConnection: () -> Unit,
    onClearApiKey: () -> Unit,
) {
    var showApiKey by remember { mutableStateOf(false) }
    SectionCard(
        title = "接口配置",
        action = if (configConfigured) {
            { TextButton(onClick = onToggleConfigExpanded) { Text("收起") } }
        } else {
            null
        },
        modifier = Modifier.testTag(AiImportTags.CONFIG_SECTION),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AiApiProtocol.entries.forEach { item ->
                    LiquidFilterChip(
                        selected = protocol == item,
                        onClick = { onProtocolChange(item) },
                        label = { Text(item.displayName) },
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AiProviderPreset.QuickBuiltIns.forEach { preset ->
                    val selected = baseUrl.trim().equals(preset.baseUrl, ignoreCase = true)
                    LiquidFilterChip(
                        selected = selected,
                        onClick = { onApplyPreset(preset) },
                        modifier = Modifier.semantics {
                            if (!preset.supportsVision) {
                                contentDescription = "${preset.label}，仅文本"
                            }
                        },
                        label = { Text(preset.label) },
                    )
                }
                TextButton(
                    onClick = onOpenProviderPicker,
                    modifier = Modifier.testTag(AiImportTags.PROVIDER_PICKER_BUTTON),
                ) {
                    Text("全部 (${AiProviderPreset.BuiltIns.size})")
                }
            }

            LiquidOutlinedTextField(
                value = baseUrl,
                onValueChange = onBaseUrlChange,
                label = { Text("Base URL") },
                placeholder = { Text("https://api.openai.com/v1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if ('{' in baseUrl || '}' in baseUrl) {
                Text(
                    "请将链接中的 {…} 占位符替换为你的实际配置。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            LiquidOutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text("API Key") },
                placeholder = { Text("sk-...") },
                singleLine = true,
                visualTransformation = if (showApiKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    TextButton(onClick = { showApiKey = !showApiKey }) {
                        Text(if (showApiKey) "隐藏" else "显示")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(AiImportTags.API_KEY_INPUT),
            )

            val canFetchModels = !fetchingModels &&
                baseUrl.isNotBlank() &&
                apiKey.isNotBlank() &&
                '{' !in baseUrl &&
                '}' !in baseUrl
            if (!modelsFetched) {
                LiquidButton(
                    onClick = onFetchModels,
                    enabled = canFetchModels,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(AiImportTags.FETCH_MODELS_BUTTON),
                ) {
                    if (fetchingModels) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("获取中...")
                    } else {
                        Text("获取可用模型")
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LiquidOutlinedButton(
                        onClick = onFetchModels,
                        enabled = canFetchModels,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(AiImportTags.FETCH_MODELS_BUTTON),
                    ) {
                        if (fetchingModels) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("获取中...")
                        } else {
                            Text("获取可用模型")
                        }
                    }
                    if (availableModels.isNotEmpty()) {
                        LiquidOutlinedButton(
                            onClick = onOpenModelPicker,
                            modifier = Modifier
                                .weight(1f)
                                .testTag(AiImportTags.MODEL_PICKER_BUTTON),
                        ) {
                            Text("选择模型 (${availableModels.size})")
                        }
                    }
                }
            }

            connectionMessage?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (modelsFetched) {
                LiquidOutlinedTextField(
                    value = model,
                    onValueChange = onModelChange,
                    label = { Text("模型") },
                    placeholder = { Text("gpt-4o-mini") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(AiImportTags.MODEL_INPUT),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LiquidButton(
                        onClick = onSaveConfig,
                        modifier = Modifier.weight(1f),
                    ) { Text("保存配置") }
                    LiquidOutlinedButton(
                        onClick = onTestConnection,
                        enabled = !testingConnection,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (testingConnection) "测试中..." else "测试连接")
                    }
                }
            }
            if (apiKey.isNotBlank() || configConfigured) {
                TextButton(
                    onClick = onClearApiKey,
                    modifier = Modifier.align(Alignment.End),
                ) { Text("清除已保存 Key") }
            }
        }
    }
}

@Composable
private fun AiInputSection(
    textInput: String,
    attachedImages: List<AiImagePayload>,
    parsing: Boolean,
    busy: Boolean,
    onCaptureClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onRemoveImage: (Int) -> Unit,
    onTextInputChange: (String) -> Unit,
    onStartParse: () -> Unit,
    onCancelParse: () -> Unit,
) {
    SectionCard(
        title = "导入内容",
        modifier = Modifier.testTag(AiImportTags.INPUT_SECTION),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LiquidOutlinedButton(
                    onClick = onCaptureClick,
                    enabled = !parsing && attachedImages.size < AiImportViewModel.MAX_IMAGES,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag(AiImportTags.CAPTURE_BUTTON),
                ) {
                    Text("拍照")
                }
                LiquidOutlinedButton(
                    onClick = onGalleryClick,
                    enabled = !parsing && attachedImages.size < AiImportViewModel.MAX_IMAGES,
                    modifier = Modifier
                        .weight(1.4f)
                        .height(48.dp)
                        .testTag(AiImportTags.GALLERY_BUTTON),
                ) {
                    Text("相册选图（${attachedImages.size}/${AiImportViewModel.MAX_IMAGES}）")
                }
            }

            if (attachedImages.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    attachedImages.forEachIndexed { index, payload ->
                        GlassSurface(
                            shape = RoundedCornerShape(10.dp),
                            material = GlassMaterial.CONTENT,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${payload.displayName.ifBlank { "图片 ${index + 1}" }} (${payload.bytes.size / 1024} KB)",
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = { onRemoveImage(index) },
                                    enabled = !parsing,
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "移除图片",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            LiquidOutlinedTextField(
                value = textInput,
                onValueChange = onTextInputChange,
                label = { Text("文本或补充说明") },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LiquidButton(
                    onClick = onStartParse,
                    enabled = !parsing && !busy,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag(AiImportTags.PARSE_BUTTON),
                ) {
                    if (parsing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("正在识别...")
                    } else {
                        Text("开始 AI 识别")
                    }
                }
                if (parsing) {
                    LiquidOutlinedButton(
                        onClick = onCancelParse,
                        modifier = Modifier.height(48.dp),
                    ) { Text("取消识别") }
                }
            }
        }
    }
}

@Composable
private fun AiProviderPickerDialog(
    currentBaseUrl: String,
    onSelect: (AiProviderPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            AiProviderPreset.BuiltIns
        } else {
            AiProviderPreset.BuiltIns.filter { preset ->
                preset.label.contains(needle, ignoreCase = true) ||
                    preset.id.contains(needle, ignoreCase = true) ||
                    preset.baseUrl.contains(needle, ignoreCase = true)
            }
        }
    }
    LiquidAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择服务商", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LiquidOutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索服务商") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (filtered.isEmpty()) {
                    Text(
                        "无匹配项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(filtered, key = { it.id }) { preset ->
                            val selected = currentBaseUrl.trim().equals(preset.baseUrl, ignoreCase = true)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(preset) }
                                    .semantics {
                                        val tags = buildList {
                                            add(preset.label)
                                            if (!preset.supportsVision) add("仅文本")
                                            if (preset.requiresPlaceholder) add("需填占位符")
                                        }
                                        contentDescription = tags.joinToString("，")
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                AiProviderAvatar(
                                    name = preset.label,
                                    baseUrl = preset.baseUrl,
                                    protocol = preset.protocol,
                                    size = 28.dp,
                                    presetId = preset.id,
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            text = preset.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (selected) {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                    Text(
                                        text = preset.baseUrl,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun AiModelPickerDialog(
    models: List<AiModelInfo>,
    selectedModelId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(models, query) {
        val needle = query.trim()
        if (needle.isEmpty()) models
        else models.filter {
            it.id.contains(needle, ignoreCase = true) || it.ownedBy.contains(needle, ignoreCase = true)
        }
    }
    LiquidAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("可用模型 (${models.size})", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LiquidOutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索模型") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (filtered.isEmpty()) {
                    Text(
                        "无匹配项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(filtered, key = { it.id }) { info ->
                            val selected = info.id == selectedModelId.trim()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(info.id) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = info.id,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (selected) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
