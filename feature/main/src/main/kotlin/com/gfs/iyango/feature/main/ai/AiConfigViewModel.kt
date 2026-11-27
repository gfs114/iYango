package com.gfs.iyango.feature.main.ai

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.domain.AiApiProtocol
import com.gfs.iyango.core.domain.AiConfigStorePort
import com.gfs.iyango.core.domain.AiModelInfo
import com.gfs.iyango.core.domain.AiProfilesSnapshot
import com.gfs.iyango.core.domain.AiProviderConfig
import com.gfs.iyango.core.domain.AiProviderPreset
import com.gfs.iyango.core.domain.AiProviderProfile
import com.gfs.iyango.core.domain.AiScheduleImportPort
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 独立「AI 接口」配置页 ViewModel。
 *
 * 与 AI 助理、AI 拍图导入共享同一套本机多档案加密配置，统一支持
 * OpenAI Chat、OpenAI Responses 与 Anthropic Messages 三种协议。
 */
@HiltViewModel
class AiConfigViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val aiConfigStore: AiConfigStorePort,
    private val aiImport: AiScheduleImportPort,
) : ViewModel() {

    constructor(
        aiConfigStore: AiConfigStorePort,
        aiImport: AiScheduleImportPort,
    ) : this(SavedStateHandle(), aiConfigStore, aiImport)

    val profiles = MutableStateFlow<List<AiProviderProfile>>(listOf(AiProviderProfile()))
    val activeProfileId = MutableStateFlow(AiProviderProfile.DEFAULT_ID)
    val editingProfileId = MutableStateFlow<String?>(
        savedStateHandle.get<String>("profileId")?.trim()?.takeIf { it.isNotEmpty() },
    )
    val profileName = MutableStateFlow(AiProviderProfile.DEFAULT_NAME)

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
    val showAddProviderSheet = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            runCatching { aiConfigStore.loadProfiles() }
                .onSuccess { snapshot ->
                    val currentEditingId = editingProfileId.value
                    if (currentEditingId == null) {
                        applySnapshot(snapshot)
                    } else {
                        profiles.value = snapshot.profiles
                        activeProfileId.value = snapshot.activeProfileId
                        val matching = snapshot.profiles.firstOrNull { it.id == currentEditingId }
                        if (matching != null) {
                            profileName.value = matching.name
                            applyLoadedConfig(matching.config)
                        } else {
                            editingProfileId.value = null
                            applySnapshot(snapshot)
                        }
                    }
                }
                .onFailure {
                    runCatching { aiConfigStore.load() }.onSuccess(::applyLoadedConfig)
                }
        }
    }

    fun openProviderDetail(profileId: String) {
        val target = profiles.value.firstOrNull { it.id == profileId }
            ?: profiles.value.firstOrNull { it.id == activeProfileId.value }
            ?: return
        clearTransientState()
        editingProfileId.value = target.id
        profileName.value = target.name
        applyLoadedConfig(target.config)
    }

    fun closeProviderDetail() {
        clearTransientState()
        editingProfileId.value = null
        val active = profiles.value.firstOrNull { it.id == activeProfileId.value }
        if (active != null) {
            profileName.value = active.name
            applyLoadedConfig(active.config)
        }
    }

    fun openAddProviderSheet() {
        showAddProviderSheet.value = true
    }

    fun dismissAddProviderSheet() {
        showAddProviderSheet.value = false
    }

    fun addProviderFromPreset(
        preset: AiProviderPreset,
        openDetailLocally: Boolean = true,
        onCreated: (String) -> Unit = {},
    ) {
        addProviderProfile(
            baseName = preset.label,
            config = AiProviderConfig(
                baseUrl = preset.baseUrl,
                model = preset.defaultModel,
                apiKey = "",
                protocol = preset.protocol,
            ),
            openDetailLocally = openDetailLocally,
            onCreated = onCreated,
        )
    }

    fun addCustomProvider(
        openDetailLocally: Boolean = true,
        onCreated: (String) -> Unit = {},
    ) {
        addProviderProfile(
            baseName = "自定义接口",
            config = AiProviderConfig(),
            openDetailLocally = openDetailLocally,
            onCreated = onCreated,
        )
    }

    private fun addProviderProfile(
        baseName: String,
        config: AiProviderConfig,
        openDetailLocally: Boolean = true,
        onCreated: (String) -> Unit = {},
    ) {
        val uniqueName = uniqueProfileName(baseName)
        val shouldActivate = profiles.value
            .firstOrNull { it.id == activeProfileId.value }
            ?.config
            ?.isConfigured != true
        val newProfile = AiProviderProfile(
            id = UUID.randomUUID().toString(),
            name = uniqueName,
            config = config,
        )
        viewModelScope.launch {
            clearTransientState()
            showAddProviderSheet.value = false
            runCatching { aiConfigStore.saveProfile(newProfile, activate = shouldActivate) }
                .onSuccess { snapshot ->
                    profiles.value = snapshot.profiles
                    activeProfileId.value = snapshot.activeProfileId
                    if (openDetailLocally) {
                        editingProfileId.value = newProfile.id
                        profileName.value = newProfile.name
                        applyLoadedConfig(newProfile.config)
                    }
                    onCreated(newProfile.id)
                }
                .onFailure { error.value = it.message ?: "添加服务商失败" }
        }
    }

    fun activateEditingProfile() {
        val current = currentConfig()
        viewModelScope.launch {
            error.value = null
            connectionMessage.value = null
            runCatching { persistEditingProfile(current, forceActivate = true) }
                .onSuccess {
                    configConfigured.value = current.isConfigured
                    connectionMessage.value = "已设为当前服务商"
                }
                .onFailure { error.value = it.message ?: "切换当前服务商失败" }
        }
    }

    fun selectProfile(profileId: String) {
        viewModelScope.launch {
            clearTransientState()
            runCatching {
                aiConfigStore.selectProfile(profileId)
                aiConfigStore.loadProfiles()
            }.onSuccess { snapshot ->
                val editingId = editingProfileId.value
                if (editingId == null || editingId == profileId) {
                    applySnapshot(snapshot)
                } else {
                    profiles.value = snapshot.profiles
                    activeProfileId.value = snapshot.activeProfileId
                }
            }.onFailure { error.value = it.message ?: "切换配置档案失败" }
        }
    }

    fun createNewProfile() {
        val nextIndex = profiles.value.size + 1
        val newProfile = AiProviderProfile(
            id = UUID.randomUUID().toString(),
            name = "配置 $nextIndex",
            config = AiProviderConfig(),
        )
        viewModelScope.launch {
            clearTransientState()
            runCatching { aiConfigStore.saveProfile(newProfile, activate = true) }
                .onSuccess { snapshot ->
                    applySnapshot(snapshot)
                    connectionMessage.value = "已新建档案「${newProfile.name}」"
                }
                .onFailure { error.value = it.message ?: "新建配置档案失败" }
        }
    }

    fun deleteActiveProfile() {
        deleteProfile(editingProfileId.value ?: activeProfileId.value)
    }

    fun deleteProfile(
        profileId: String,
        onDeleted: () -> Unit = {},
    ) {
        if (profiles.value.size <= 1) return
        viewModelScope.launch {
            clearTransientState()
            runCatching { aiConfigStore.deleteProfile(profileId) }
                .onSuccess { snapshot ->
                    if (editingProfileId.value == profileId) {
                        editingProfileId.value = null
                        applySnapshot(snapshot)
                    } else if (editingProfileId.value == null) {
                        applySnapshot(snapshot)
                    } else {
                        profiles.value = snapshot.profiles
                        activeProfileId.value = snapshot.activeProfileId
                    }
                    connectionMessage.value = "已删除档案"
                    onDeleted()
                }
                .onFailure { error.value = it.message ?: "删除配置档案失败" }
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
                    persistEditingProfile(current)
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
            runCatching { persistEditingProfile(current) }
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
                    runCatching { persistEditingProfile(current) }
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
        val targetId = editingProfileId.value ?: activeProfileId.value
        val isTargetActive = targetId == activeProfileId.value
        viewModelScope.launch {
            if (isTargetActive) {
                runCatching { aiConfigStore.clearApiKey() }
                    .onSuccess {
                        apiKey.value = ""
                        configConfigured.value = false
                        availableModels.value = emptyList()
                        runCatching { aiConfigStore.loadProfiles() }
                            .onSuccess { snapshot ->
                                if (editingProfileId.value == null) {
                                    applySnapshot(snapshot)
                                } else {
                                    profiles.value = snapshot.profiles
                                    activeProfileId.value = snapshot.activeProfileId
                                }
                            }
                        connectionMessage.value = "已清除 API Key"
                    }
                    .onFailure { error.value = it.message ?: "清除失败" }
            } else {
                val cleared = AiProviderProfile(
                    id = targetId,
                    name = profileName.value.trim().ifEmpty { AiProviderProfile.DEFAULT_NAME },
                    config = currentConfig().copy(apiKey = ""),
                )
                runCatching { aiConfigStore.saveProfile(cleared, activate = false) }
                    .onSuccess { snapshot ->
                        profiles.value = snapshot.profiles
                        activeProfileId.value = snapshot.activeProfileId
                        apiKey.value = ""
                        configConfigured.value = false
                        availableModels.value = emptyList()
                        connectionMessage.value = "已清除 API Key"
                    }
                    .onFailure { error.value = it.message ?: "清除失败" }
            }
        }
    }

    fun consumeError() {
        error.value = null
    }

    private fun clearTransientState() {
        connectionMessage.value = null
        availableModels.value = emptyList()
        showModelPicker.value = false
        showProviderPicker.value = false
        error.value = null
    }

    private fun uniqueProfileName(base: String): String {
        val cleanBase = base.trim().ifEmpty { AiProviderProfile.DEFAULT_NAME }
        val existing = profiles.value.map { it.name.trim() }.toSet()
        if (cleanBase !in existing) return cleanBase
        var suffix = 2
        while ("$cleanBase $suffix" in existing) {
            suffix++
        }
        return "$cleanBase $suffix"
    }

    private suspend fun persistEditingProfile(
        config: AiProviderConfig,
        forceActivate: Boolean = false,
    ) {
        val targetId = editingProfileId.value ?: activeProfileId.value
        val shouldActivate = forceActivate || targetId == activeProfileId.value
        val target = AiProviderProfile(
            id = targetId,
            name = profileName.value.trim().ifEmpty { AiProviderProfile.DEFAULT_NAME },
            config = config,
        )
        val updatedSnapshot = aiConfigStore.saveProfile(target, activate = shouldActivate)
        profiles.value = updatedSnapshot.profiles
        activeProfileId.value = updatedSnapshot.activeProfileId
    }

    private fun applySnapshot(snapshot: AiProfilesSnapshot) {
        profiles.value = snapshot.profiles
        activeProfileId.value = snapshot.activeProfileId
        val active = snapshot.activeProfile
        profileName.value = active.name
        applyLoadedConfig(active.config)
    }

    private fun applyLoadedConfig(loaded: AiProviderConfig) {
        protocol.value = loaded.protocol
        baseUrl.value = loaded.baseUrl
        model.value = loaded.model
        apiKey.value = loaded.apiKey
        configConfigured.value = loaded.isConfigured
    }

    private fun currentConfig(): AiProviderConfig = AiProviderConfig(
        baseUrl = baseUrl.value.trim(),
        model = model.value.trim(),
        apiKey = apiKey.value.trim(),
        protocol = protocol.value,
    )
}
