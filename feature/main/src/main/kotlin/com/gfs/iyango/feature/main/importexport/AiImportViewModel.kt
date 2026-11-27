package com.gfs.iyango.feature.main.importexport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.domain.AiApiProtocol
import com.gfs.iyango.core.domain.AiConfigStorePort
import com.gfs.iyango.core.domain.AiImagePayload
import com.gfs.iyango.core.domain.AiImportParseResult
import com.gfs.iyango.core.domain.AiModelInfo
import com.gfs.iyango.core.domain.AiProviderConfig
import com.gfs.iyango.core.domain.AiProviderPreset
import com.gfs.iyango.core.domain.AiProviderProfile
import com.gfs.iyango.core.domain.AiScheduleImportPort
import com.gfs.iyango.core.domain.ImportWriteMode
import com.gfs.iyango.core.domain.ScheduleImportOptions
import com.gfs.iyango.core.domain.ScheduleImportPreview
import com.gfs.iyango.core.domain.ScheduleImportReadResult
import com.gfs.iyango.core.domain.ScheduleImportTargetInfo
import com.gfs.iyango.core.domain.ScheduleTransferPort
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.model.Timetable
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * BYOK 拍图 / 文本 AI 课表导入 ViewModel。
 *
 * 模型输出经本地标准化后直接交由 [ScheduleTransferPort.read] 生成标准预览，
 * 与文件导入共享逐条勾选、新建/追加/替换写入及防重检查契约。
 */
@HiltViewModel
class AiImportViewModel @Inject constructor(
    private val aiConfigStore: AiConfigStorePort,
    private val aiImport: AiScheduleImportPort,
    private val transfer: ScheduleTransferPort,
    private val timetableRepository: TimetableRepositoryPort,
    private val clock: Clock,
) : ViewModel() {

    val profiles = MutableStateFlow<List<AiProviderProfile>>(listOf(AiProviderProfile()))
    val activeProfileId = MutableStateFlow(AiProviderProfile.DEFAULT_ID)

    val protocol = MutableStateFlow(AiApiProtocol.OPENAI_CHAT)
    val baseUrl = MutableStateFlow(AiProviderConfig.DEFAULT_BASE_URL)
    val model = MutableStateFlow(AiProviderConfig.DEFAULT_MODEL)
    val apiKey = MutableStateFlow("")
    val configExpanded = MutableStateFlow(true)
    val configConfigured = MutableStateFlow(false)

    val testingConnection = MutableStateFlow(false)
    val connectionMessage = MutableStateFlow<String?>(null)
    val fetchingModels = MutableStateFlow(false)
    val availableModels = MutableStateFlow<List<AiModelInfo>>(emptyList())
    val modelsFetched = MutableStateFlow(false)
    val showModelPicker = MutableStateFlow(false)
    val showProviderPicker = MutableStateFlow(false)

    val textInput = MutableStateFlow("")
    val attachedImages = MutableStateFlow<List<AiImagePayload>>(emptyList())
    val aiWarnings = MutableStateFlow<List<String>>(emptyList())
    val parsing = MutableStateFlow(false)

    val preview = MutableStateFlow<ScheduleImportPreview?>(null)
    val mode = MutableStateFlow(ImportWriteMode.CREATE)
    val targetId = MutableStateFlow<Long?>(null)
    val targetInfo = MutableStateFlow<ScheduleImportTargetInfo?>(null)
    val selectedKeys = MutableStateFlow<Set<String>>(emptySet())
    val applySemester = MutableStateFlow(true)
    val applyTimeSlots = MutableStateFlow(true)
    val applyDisplayConfig = MutableStateFlow(false)

    val confirmDuplicate = MutableStateFlow(false)
    val doneMessage = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    val localTimetables: StateFlow<List<Timetable>> = timetableRepository.observeAll()
        .catch { error.value = "读取课表失败：${it.message}" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var parseJob: Job? = null

    init {
        reloadConfig()
    }

    fun reloadConfig() {
        viewModelScope.launch {
            runCatching { aiConfigStore.loadProfiles() }
                .onSuccess { snapshot ->
                    profiles.value = snapshot.profiles
                    activeProfileId.value = snapshot.activeProfileId
                    val loaded = snapshot.activeProfile.config
                    protocol.value = loaded.protocol
                    baseUrl.value = loaded.baseUrl
                    model.value = loaded.model
                    apiKey.value = loaded.apiKey
                    configConfigured.value = loaded.isConfigured
                    configExpanded.value = !loaded.isConfigured
                    modelsFetched.value = loaded.isConfigured
                }
                .onFailure {
                    runCatching { aiConfigStore.load() }
                        .onSuccess { loaded ->
                            protocol.value = loaded.protocol
                            baseUrl.value = loaded.baseUrl
                            model.value = loaded.model
                            apiKey.value = loaded.apiKey
                            configConfigured.value = loaded.isConfigured
                            configExpanded.value = !loaded.isConfigured
                            modelsFetched.value = loaded.isConfigured
                        }
                }
        }
    }

    fun selectProfile(profileId: String) {
        viewModelScope.launch {
            runCatching {
                val loaded = aiConfigStore.selectProfile(profileId)
                val snapshot = aiConfigStore.loadProfiles()
                loaded to snapshot
            }.onSuccess { (loaded, snapshot) ->
                profiles.value = snapshot.profiles
                activeProfileId.value = snapshot.activeProfileId
                protocol.value = loaded.protocol
                baseUrl.value = loaded.baseUrl
                model.value = loaded.model
                apiKey.value = loaded.apiKey
                configConfigured.value = loaded.isConfigured
                availableModels.value = emptyList()
                connectionMessage.value = null
            }.onFailure {
                error.value = it.message ?: "切换配置档案失败"
            }
        }
    }

    fun selectProtocol(value: AiApiProtocol) {
        if (protocol.value == value) return
        protocol.value = value
        availableModels.value = emptyList()
        connectionMessage.value = null
    }

    fun toggleConfigExpanded() {
        configExpanded.value = !configExpanded.value
    }

    fun applyPreset(preset: AiProviderPreset) {
        protocol.value = preset.protocol
        baseUrl.value = preset.baseUrl
        model.value = preset.defaultModel
        availableModels.value = emptyList()
        modelsFetched.value = false
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
                    modelsFetched.value = true
                    connectionMessage.value = "已获取 ${models.size} 个可用模型"
                    showModelPicker.value = true
                }
                .onFailure { failure ->
                    modelsFetched.value = true
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
                        "已保存地址与模型（尚未填写 API Key）"
                    }
                    if (current.isConfigured) {
                        configExpanded.value = false
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
                    configExpanded.value = true
                    availableModels.value = emptyList()
                    modelsFetched.value = false
                    connectionMessage.value = "已清除本机保存的 API Key"
                }
                .onFailure { error.value = it.message ?: "清除失败" }
        }
    }

    fun addImages(newImages: List<AiImagePayload>, skippedCount: Int = 0) {
        if (skippedCount > 0) {
            error.value = "有 $skippedCount 张图片无法解码或体积过大，已跳过"
        }
        if (newImages.isEmpty()) return
        val merged = (attachedImages.value + newImages).take(MAX_IMAGES)
        if (attachedImages.value.size + newImages.size > MAX_IMAGES) {
            error.value = "单次最多附加 $MAX_IMAGES 张图片"
        }
        attachedImages.value = merged
    }

    fun removeImage(index: Int) {
        val current = attachedImages.value
        if (index !in current.indices) return
        attachedImages.value = current.filterIndexed { idx, _ -> idx != index }
    }

    fun startParse() {
        if (parsing.value || busy.value) return
        val current = currentConfig()
        if (!current.isConfigured) {
            configExpanded.value = true
            error.value = "请先填写接口地址、模型名与 API Key"
            return
        }
        if (textInput.value.isBlank() && attachedImages.value.isEmpty()) {
            error.value = "请添加课表截图或输入需要解析的文本"
            return
        }
        parseJob?.cancel()
        parseJob = viewModelScope.launch {
            parsing.value = true
            error.value = null
            doneMessage.value = null
            aiWarnings.value = emptyList()
            try {
                // Persist latest provider settings transparently when starting recognition.
                runCatching { aiConfigStore.save(current) }
                configConfigured.value = current.isConfigured
                val referenceDate = LocalDate.now(clock).toString()
                when (val result = aiImport.parseSchedule(
                    config = current,
                    textInput = textInput.value,
                    images = attachedImages.value,
                    referenceDateIso = referenceDate,
                )) {
                    is AiImportParseResult.Failed -> {
                        error.value = result.message
                    }
                    is AiImportParseResult.Ready -> {
                        when (val readResult = transfer.read(result.scheduleJsonBytes)) {
                            is ScheduleImportReadResult.Failed -> {
                                error.value = readResult.message
                            }
                            is ScheduleImportReadResult.Ready -> {
                                val loaded = readResult.preview
                                aiWarnings.value = result.warnings
                                preview.value = loaded
                                selectedKeys.value = loaded.items.mapTo(HashSet()) { it.rowKey }
                                applyDisplayConfig.value = loaded.hasDisplayConfig
                                val defaultLocal = if (loaded.items.any { it.timeMode == 2 }) {
                                    localTimetables.value
                                        .ifEmpty { runCatching { timetableRepository.observeAll().first() }.getOrDefault(emptyList()) }
                                        .firstOrNull { it.source == "LOCAL" }
                                } else {
                                    null
                                }
                                if (defaultLocal != null) {
                                    mode.value = ImportWriteMode.APPEND
                                    applySemester.value = false
                                    applyTimeSlots.value = false
                                    setTarget(defaultLocal.id)
                                } else {
                                    mode.value = ImportWriteMode.CREATE
                                    targetId.value = null
                                    targetInfo.value = null
                                    applySemester.value = loaded.semesterName != null
                                    applyTimeSlots.value = loaded.timeSlotCount > 0
                                }
                            }
                        }
                    }
                }
            } catch (_: CancellationException) {
                // User cancelled recognition explicitly.
            } catch (failure: Exception) {
                error.value = failure.message ?: "识别失败"
            } finally {
                parsing.value = false
            }
        }
    }

    fun cancelParse() {
        parseJob?.cancel()
        parseJob = null
        parsing.value = false
    }

    fun dismissPreview() {
        preview.value = null
        aiWarnings.value = emptyList()
        confirmDuplicate.value = false
        selectedKeys.value = emptySet()
    }

    fun setMode(value: ImportWriteMode) {
        mode.value = value
        if (value == ImportWriteMode.CREATE) {
            targetId.value = null
            targetInfo.value = null
        } else if (targetId.value == null) {
            setTarget(localTimetables.value.firstOrNull { it.source == "LOCAL" }?.id)
        } else {
            refreshTargetInfo()
        }
        if (value == ImportWriteMode.APPEND) {
            applySemester.value = false
            applyTimeSlots.value = false
        } else {
            applySemester.value = preview.value?.semesterName != null
            applyTimeSlots.value = (preview.value?.timeSlotCount ?: 0) > 0
        }
    }

    fun setTarget(timetableId: Long?) {
        targetId.value = timetableId
        refreshTargetInfo()
    }

    private fun refreshTargetInfo() {
        val id = targetId.value
        if (id == null) {
            targetInfo.value = null
            return
        }
        viewModelScope.launch {
            targetInfo.value = runCatching { transfer.inspectTarget(id) }.getOrNull()
        }
    }

    fun toggleRow(rowKey: String) {
        selectedKeys.value = selectedKeys.value.let { current ->
            if (rowKey in current) current - rowKey else current + rowKey
        }
    }

    fun selectVisible(rowKeys: Collection<String>) {
        selectedKeys.value = selectedKeys.value + rowKeys
    }

    fun clearSelection() {
        selectedKeys.value = emptySet()
    }

    fun commit(force: Boolean = false) {
        val current = preview.value ?: return
        val frozenMode = mode.value
        val target = if (frozenMode == ImportWriteMode.CREATE) null else targetId.value
        if (frozenMode != ImportWriteMode.CREATE && target == null) {
            error.value = "请选择目标课表"
            return
        }
        viewModelScope.launch {
            if (current.duplicateExisting && !force) {
                confirmDuplicate.value = true
                return@launch
            }
            confirmDuplicate.value = false
            busy.value = true
            val snapshot = ScheduleImportOptions(
                mode = frozenMode,
                targetTimetableId = target,
                selectedRowKeys = selectedKeys.value,
                applySemester = applySemester.value,
                applyTimeSlots = applyTimeSlots.value,
                applyDisplayConfig = applyDisplayConfig.value,
                force = force,
            )
            runCatching {
                val result = transfer.commit(token = current.token, options = snapshot)
                doneMessage.value = buildString {
                    append(
                        when {
                            result.replaced -> "已替换课表，写入课程 ${result.courseCount} 条"
                            result.appended -> "已追加 ${result.courseCount} 条课程，原有课程保留"
                            else -> "已新建课表并导入 ${result.courseCount} 条课程"
                        },
                    )
                    result.issues.firstOrNull()?.let { append("；$it") }
                }
                preview.value = null
                aiWarnings.value = emptyList()
                selectedKeys.value = emptySet()
            }.onFailure { error.value = it.message ?: "导入失败" }
            busy.value = false
        }
    }

    fun confirmDuplicate() = commit(force = true)

    fun dismissDuplicate() {
        confirmDuplicate.value = false
    }

    fun consumeError() {
        error.value = null
    }

    fun consumeDone() {
        doneMessage.value = null
    }

    private fun currentConfig(): AiProviderConfig = AiProviderConfig(
        baseUrl = baseUrl.value.trim(),
        model = model.value.trim(),
        apiKey = apiKey.value.trim(),
        protocol = protocol.value,
    )

    companion object {
        const val MAX_IMAGES = 3
    }
}
