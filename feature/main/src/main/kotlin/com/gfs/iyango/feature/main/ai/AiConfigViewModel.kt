package com.gfs.iyango.feature.main.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.domain.AiApiProtocol
import com.gfs.iyango.core.domain.AiConfigStorePort
import com.gfs.iyango.core.domain.AiModelInfo
import com.gfs.iyango.core.domain.AiProviderConfig
import com.gfs.iyango.core.domain.AiProviderPreset
import com.gfs.iyango.core.domain.AiScheduleImportPort
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 独立「AI 接口」配置页 ViewModel。
 *
 * 与 AI 助理、AI 拍图导入共享同一套本机加密配置，统一支持
 * OpenAI Chat、OpenAI Responses 与 Anthropic Messages 三种协议。
 */
@HiltViewModel
class AiConfigViewModel @Inject constructor(
    private val aiConfigStore: AiConfigStorePort,
    private val aiImport: AiScheduleImportPort,
) : ViewModel() {

    val protocol = MutableStateFlow(AiApiProtocol.OPENAI_CHAT)
    val baseUrl = MutableStateFlow(AiProviderConfig.DEFAULT_BASE_URL)
    val model = MutableStateFlow(AiProviderConfig.DEFAULT_MODEL)
    val apiKey = MutableStateFlow("")
    val configConfigured = MutableStateFlow(false)

    val testingConnection = MutableStateFlow(false)
    val connectionMessage = MutableStateFlow<String?>(null)
    val fetchingModels = MutableStateFlow(false)
    val availableModels = MutableStateFlow<List<AiModelInfo>>(emptyList())
    val showModelPicker = MutableStateFlow(false)
    val showProviderPicker = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            runCatching { aiConfigStore.load() }
                .onSuccess { loaded ->
                    protocol.value = loaded.protocol
                    baseUrl.value = loaded.baseUrl
                    model.value = loaded.model
                    apiKey.value = loaded.apiKey
                    configConfigured.value = loaded.isConfigured
                }
        }
    }

    fun selectProtocol(value: AiApiProtocol) {
        if (protocol.value == value) return
        protocol.value = value
        connectionMessage.value = null
        availableModels.value = emptyList()

        val currentUrl = baseUrl.value.trim().trimEnd('/')
        val currentModel = model.value.trim()
        val isOpenAiDefaultUrl = currentUrl.isEmpty() ||
            currentUrl.equals("https://api.openai.com/v1", ignoreCase = true)
        val isAnthropicDefaultUrl = currentUrl.equals("https://api.anthropic.com/v1", ignoreCase = true)

        when (value) {
            AiApiProtocol.ANTHROPIC_MESSAGES -> {
                if (isOpenAiDefaultUrl) {
                    val preset = AiProviderPreset.BuiltIns.first { it.id == "anthropic" }
                    baseUrl.value = preset.baseUrl
                    model.value = preset.defaultModel
                }
            }
            AiApiProtocol.OPENAI_RESPONSES -> {
                if (isAnthropicDefaultUrl || currentUrl.isEmpty()) {
                    val preset = AiProviderPreset.BuiltIns.first { it.id == "openai-responses" }
                    baseUrl.value = preset.baseUrl
                    model.value = preset.defaultModel
                } else if (isOpenAiDefaultUrl && currentModel == AiProviderConfig.DEFAULT_MODEL) {
                    model.value = "gpt-5-mini"
                }
            }
            AiApiProtocol.OPENAI_CHAT -> {
                if (isAnthropicDefaultUrl || currentUrl.isEmpty()) {
                    val preset = AiProviderPreset.BuiltIns.first { it.id == "openai" }
                    baseUrl.value = preset.baseUrl
                    model.value = preset.defaultModel
                } else if (isOpenAiDefaultUrl && currentModel == "gpt-5-mini") {
                    model.value = AiProviderConfig.DEFAULT_MODEL
                }
            }
        }
    }

    fun applyPreset(preset: AiProviderPreset) {
        protocol.value = preset.protocol
        baseUrl.value = preset.baseUrl
        model.value = preset.defaultModel
        availableModels.value = emptyList()
        showProviderPicker.value = false
        connectionMessage.value = null
    }

    fun openProviderPicker() {
        showProviderPicker.value = true
    }

    fun dismissProviderPicker() {
        showProviderPicker.value = false
    }

    fun fetchAvailableModels() {
        if (fetchingModels.value) return
        val current = currentConfig()
        viewModelScope.launch {
            fetchingModels.value = true
            connectionMessage.value = null
            error.value = null
            aiImport.fetchModels(current)
                .onSuccess { models ->
                    runCatching { aiConfigStore.save(current) }
                    configConfigured.value = current.isConfigured
                    availableModels.value = models
                    connectionMessage.value = "已获取 ${models.size} 个可用模型"
                    showModelPicker.value = true
                }
                .onFailure { failure ->
                    connectionMessage.value = failure.message ?: "获取可用模型失败"
                }
            fetchingModels.value = false
        }
    }

    fun openModelPicker() {
        if (availableModels.value.isNotEmpty()) {
            showModelPicker.value = true
        }
    }

    fun dismissModelPicker() {
        showModelPicker.value = false
    }

    fun selectModel(modelId: String) {
        val clean = modelId.trim()
        if (clean.isNotEmpty()) {
            model.value = clean
        }
        showModelPicker.value = false
    }

    fun saveConfig() {
        val current = currentConfig()
        viewModelScope.launch {
            error.value = null
            connectionMessage.value = null
            runCatching { aiConfigStore.save(current) }
                .onSuccess {
                    configConfigured.value = current.isConfigured
                    connectionMessage.value = if (current.isConfigured) {
                        "接口配置已加密保存"
                    } else {
                        "已保存协议、地址与模型"
                    }
                }
                .onFailure { error.value = it.message ?: "保存配置失败" }
        }
    }

    fun testConnection() {
        if (testingConnection.value) return
        val current = currentConfig()
        viewModelScope.launch {
            testingConnection.value = true
            connectionMessage.value = null
            error.value = null
            aiImport.testConnection(current)
                .onSuccess { msg ->
                    runCatching { aiConfigStore.save(current) }
                    configConfigured.value = current.isConfigured
                    connectionMessage.value = msg
                }
                .onFailure { failure ->
                    connectionMessage.value = failure.message ?: "连接失败"
                }
            testingConnection.value = false
        }
    }

    fun clearApiKey() {
        viewModelScope.launch {
            runCatching { aiConfigStore.clearApiKey() }
                .onSuccess {
                    apiKey.value = ""
                    configConfigured.value = false
                    availableModels.value = emptyList()
                    connectionMessage.value = "已清除 API Key"
                }
                .onFailure { error.value = it.message ?: "清除失败" }
        }
    }

    fun consumeError() {
        error.value = null
    }

    private fun currentConfig(): AiProviderConfig = AiProviderConfig(
        baseUrl = baseUrl.value.trim(),
        model = model.value.trim(),
        apiKey = apiKey.value.trim(),
        protocol = protocol.value,
    )
}
