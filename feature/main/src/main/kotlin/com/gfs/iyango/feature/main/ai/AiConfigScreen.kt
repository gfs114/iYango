@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.gfs.iyango.feature.main.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.gfs.iyango.core.domain.AiModelInfo
import com.gfs.iyango.core.domain.AiProviderPreset
import java.net.URI

internal object AiConfigTags {
    const val PROTOCOL_SECTION = "ai_config_protocol_section"
    const val BASE_URL_INPUT = "ai_config_base_url_input"
    const val ENDPOINT_PREVIEW = "ai_config_endpoint_preview"
    const val API_KEY_INPUT = "ai_config_api_key_input"
    const val FETCH_MODELS_BUTTON = "ai_config_fetch_models_button"
    const val MODEL_INPUT = "ai_config_model_input"
    const val SAVE_BUTTON = "ai_config_save_button"
    const val TEST_BUTTON = "ai_config_test_button"
    const val PROVIDER_PICKER_BUTTON = "ai_config_provider_picker_button"
}

@Composable
fun AiConfigScreen(
    onBack: () -> Unit,
    viewModel: AiConfigViewModel = hiltViewModel(),
) {
    val protocol by viewModel.protocol.collectAsStateWithLifecycle()
    val baseUrl by viewModel.baseUrl.collectAsStateWithLifecycle()
    val model by viewModel.model.collectAsStateWithLifecycle()
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val configConfigured by viewModel.configConfigured.collectAsStateWithLifecycle()
    val testingConnection by viewModel.testingConnection.collectAsStateWithLifecycle()
    val connectionMessage by viewModel.connectionMessage.collectAsStateWithLifecycle()
    val fetchingModels by viewModel.fetchingModels.collectAsStateWithLifecycle()
    val availableModels by viewModel.availableModels.collectAsStateWithLifecycle()
    val showModelPicker by viewModel.showModelPicker.collectAsStateWithLifecycle()
    val showProviderPicker by viewModel.showProviderPicker.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.reload()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AiConfigContent(
        protocol = protocol,
        baseUrl = baseUrl,
        model = model,
        apiKey = apiKey,
        configConfigured = configConfigured,
        testingConnection = testingConnection,
        connectionMessage = connectionMessage,
        fetchingModels = fetchingModels,
        availableModels = availableModels,
        showModelPicker = showModelPicker,
        showProviderPicker = showProviderPicker,
        error = error,
        onBack = onBack,
        onProtocolChange = viewModel::selectProtocol,
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
        onConsumeError = viewModel::consumeError,
    )
}

@Composable
internal fun AiConfigContent(
    protocol: AiApiProtocol,
    baseUrl: String,
    model: String,
    apiKey: String,
    configConfigured: Boolean,
    testingConnection: Boolean,
    connectionMessage: String?,
    fetchingModels: Boolean,
    availableModels: List<AiModelInfo>,
    showModelPicker: Boolean,
    showProviderPicker: Boolean,
    error: String?,
    onBack: () -> Unit,
    onProtocolChange: (AiApiProtocol) -> Unit,
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
    onConsumeError: () -> Unit,
) {
    var showApiKey by remember { mutableStateOf(false) }
    val endpointLabel = remember(baseUrl, protocol) { buildEndpointPreview(baseUrl, protocol) }

    GlassScaffold(
        topBar = {
            GlassTopBar(
                title = "AI 接口",
                navigationIcon = {
                    GlassTopBarIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                error?.let { msg ->
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
                                text = msg,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onConsumeError) { Text("知道了") }
                        }
                    }
                }

                SectionCard(
                    title = "接口协议",
                    modifier = Modifier.testTag(AiConfigTags.PROTOCOL_SECTION),
                ) {
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
                }

                SectionCard(
                    title = "服务商",
                    action = {
                        TextButton(
                            onClick = onOpenProviderPicker,
                            modifier = Modifier.testTag(AiConfigTags.PROVIDER_PICKER_BUTTON),
                        ) {
                            Text("全部 (${AiProviderPreset.BuiltIns.size})")
                        }
                    },
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AiProviderPreset.QuickBuiltIns.forEach { preset ->
                            val selected = baseUrl.trim().equals(preset.baseUrl, ignoreCase = true) &&
                                protocol == preset.protocol
                            LiquidFilterChip(
                                selected = selected,
                                onClick = { onApplyPreset(preset) },
                                label = { Text(preset.label) },
                            )
                        }
                    }
                }

                SectionCard(title = "连接参数") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LiquidOutlinedTextField(
                            value = baseUrl,
                            onValueChange = onBaseUrlChange,
                            label = { Text("Base URL") },
                            placeholder = {
                                Text(
                                    if (protocol == AiApiProtocol.ANTHROPIC_MESSAGES) {
                                        "https://api.anthropic.com/v1"
                                    } else {
                                        "https://api.openai.com/v1"
                                    },
                                )
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(AiConfigTags.BASE_URL_INPUT),
                        )

                        Text(
                            text = endpointLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag(AiConfigTags.ENDPOINT_PREVIEW),
                        )

                        if ('{' in baseUrl || '}' in baseUrl) {
                            Text(
                                text = "请替换地址中的占位符",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        LiquidOutlinedTextField(
                            value = apiKey,
                            onValueChange = onApiKeyChange,
                            label = { Text("API Key") },
                            placeholder = {
                                Text(
                                    if (protocol == AiApiProtocol.ANTHROPIC_MESSAGES) {
                                        "sk-ant-..."
                                    } else {
                                        "sk-..."
                                    },
                                )
                            },
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
                                .testTag(AiConfigTags.API_KEY_INPUT),
                        )

                        val canFetchModels = !fetchingModels &&
                            baseUrl.isNotBlank() &&
                            apiKey.isNotBlank() &&
                            '{' !in baseUrl &&
                            '}' !in baseUrl
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            LiquidOutlinedButton(
                                onClick = onFetchModels,
                                enabled = canFetchModels,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag(AiConfigTags.FETCH_MODELS_BUTTON),
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
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("选择模型 (${availableModels.size})")
                                }
                            }
                        }

                        LiquidOutlinedTextField(
                            value = model,
                            onValueChange = onModelChange,
                            label = { Text("模型") },
                            placeholder = { Text("gpt-4o-mini") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(AiConfigTags.MODEL_INPUT),
                        )

                        connectionMessage?.let { msg ->
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            LiquidButton(
                                onClick = onSaveConfig,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag(AiConfigTags.SAVE_BUTTON),
                            ) { Text("保存配置") }
                            LiquidOutlinedButton(
                                onClick = onTestConnection,
                                enabled = !testingConnection,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag(AiConfigTags.TEST_BUTTON),
                            ) {
                                Text(if (testingConnection) "测试中..." else "测试连接")
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

                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showProviderPicker) {
        AiConfigProviderPickerDialog(
            currentBaseUrl = baseUrl,
            currentProtocol = protocol,
            onSelect = onApplyPreset,
            onDismiss = onDismissProviderPicker,
        )
    }

    if (showModelPicker) {
        AiConfigModelPickerDialog(
            models = availableModels,
            selectedModelId = model,
            onSelect = onSelectModel,
            onDismiss = onDismissModelPicker,
        )
    }
}

internal fun buildEndpointPreview(
    rawBaseUrl: String,
    protocol: AiApiProtocol,
): String {
    val suffix = when (protocol) {
        AiApiProtocol.OPENAI_CHAT -> "/chat/completions"
        AiApiProtocol.OPENAI_RESPONSES -> "/responses"
        AiApiProtocol.ANTHROPIC_MESSAGES -> "/messages"
    }
    val trimmed = rawBaseUrl.trim().trimEnd('/')
    if (trimmed.isEmpty()) return "POST $suffix"
    val path = runCatching { URI(trimmed).path }.getOrNull()?.trimEnd('/').orEmpty()
    val cleanPath = when {
        path.endsWith("/chat/completions", ignoreCase = true) ->
            path.dropLast("/chat/completions".length).trimEnd('/')
        path.endsWith("/responses", ignoreCase = true) ->
            path.dropLast("/responses".length).trimEnd('/')
        path.endsWith("/messages", ignoreCase = true) ->
            path.dropLast("/messages".length).trimEnd('/')
        else -> path
    }
    val finalPath = if (protocol == AiApiProtocol.ANTHROPIC_MESSAGES && cleanPath.isEmpty()) {
        "/v1/messages"
    } else {
        "$cleanPath$suffix"
    }
    return "POST $finalPath"
}

@Composable
private fun AiConfigProviderPickerDialog(
    currentBaseUrl: String,
    currentProtocol: AiApiProtocol,
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
                    preset.baseUrl.contains(needle, ignoreCase = true) ||
                    preset.protocol.displayName.contains(needle, ignoreCase = true)
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
                        text = "无匹配项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(filtered, key = { it.id }) { preset ->
                            val selected = currentBaseUrl.trim().equals(preset.baseUrl, ignoreCase = true) &&
                                currentProtocol == preset.protocol
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(preset) }
                                    .padding(vertical = 8.dp),
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
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = preset.protocol.shortLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (selected) {
                                        Spacer(Modifier.width(6.dp))
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
private fun AiConfigModelPickerDialog(
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
                        text = "无匹配项",
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
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
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
