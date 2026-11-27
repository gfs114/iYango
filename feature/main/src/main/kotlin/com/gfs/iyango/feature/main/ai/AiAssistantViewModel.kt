package com.gfs.iyango.feature.main.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.domain.AiChatHistoryPort
import com.gfs.iyango.core.domain.AiChatMessage
import com.gfs.iyango.core.domain.AiChatRole
import com.gfs.iyango.core.domain.AiChatSessionSummary
import com.gfs.iyango.core.domain.AiConfigStorePort
import com.gfs.iyango.core.domain.AiImagePayload
import com.gfs.iyango.core.domain.AiProviderConfig
import com.gfs.iyango.core.domain.AiProviderProfile
import com.gfs.iyango.core.domain.AssistantActionStatus
import com.gfs.iyango.core.domain.AssistantPendingAction
import com.gfs.iyango.core.domain.AssistantToolTrace
import com.gfs.iyango.core.domain.CampusAiAssistantPort
import com.gfs.iyango.core.domain.InMemoryAiChatHistoryPort
import com.gfs.iyango.core.domain.PersistedAiChatMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class AssistantChatItem(
    val id: String,
    val role: AiChatRole,
    val text: String,
    val toolTraces: List<AssistantToolTrace> = emptyList(),
    val pendingActions: List<AssistantPendingAction> = emptyList(),
    val isError: Boolean = false,
    val imageCount: Int = 0,
)

/**
 * 校园 AI 助理 ViewModel（支持多会话持久化、SSE 流式输出、多模态识图与多档案切换）。
 */
@HiltViewModel
class AiAssistantViewModel @Inject constructor(
    private val aiConfigStore: AiConfigStorePort,
    private val assistant: CampusAiAssistantPort,
    private val chatHistory: AiChatHistoryPort,
) : ViewModel() {

    internal constructor(
        aiConfigStore: AiConfigStorePort,
        assistant: CampusAiAssistantPort,
    ) : this(
        aiConfigStore = aiConfigStore,
        assistant = assistant,
        chatHistory = InMemoryAiChatHistoryPort(),
    )

    val config = MutableStateFlow(AiProviderConfig())
    val profiles = MutableStateFlow<List<AiProviderProfile>>(listOf(AiProviderProfile()))
    val activeProfileId = MutableStateFlow(AiProviderProfile.DEFAULT_ID)
    val sessions = MutableStateFlow<List<AiChatSessionSummary>>(emptyList())
    val activeSessionId = MutableStateFlow<String?>(null)

    val messages = MutableStateFlow<List<AssistantChatItem>>(emptyList())
    val inputText = MutableStateFlow("")
    val attachedImages = MutableStateFlow<List<AiImagePayload>>(emptyList())
    val sending = MutableStateFlow(false)
    val streamingText = MutableStateFlow("")
    val progressLabel = MutableStateFlow("")
    val error = MutableStateFlow<String?>(null)

    private var sendJob: Job? = null
    private var sessionObserverJob: Job? = null

    init {
        reloadConfig()
        observeSessionList()
    }

    fun reloadConfig() {
        viewModelScope.launch {
            runCatching { aiConfigStore.loadProfiles() }
                .onSuccess { snapshot ->
                    profiles.value = snapshot.profiles
                    activeProfileId.value = snapshot.activeProfileId
                    config.value = snapshot.activeProfile.config
                }
                .onFailure {
                    runCatching { aiConfigStore.load() }.onSuccess { loaded -> config.value = loaded }
                }
        }
    }

    fun selectProfile(profileId: String) {
        viewModelScope.launch {
            runCatching {
                val selected = aiConfigStore.selectProfile(profileId)
                val snapshot = aiConfigStore.loadProfiles()
                selected to snapshot
            }.onSuccess { (selected, snapshot) ->
                profiles.value = snapshot.profiles
                activeProfileId.value = snapshot.activeProfileId
                config.value = selected
            }.onFailure {
                error.value = it.message ?: "切换 AI 档案失败"
            }
        }
    }

    fun addImages(items: List<AiImagePayload>, skippedCount: Int = 0) {
        if (items.isNotEmpty()) {
            attachedImages.value = (attachedImages.value + items).take(MAX_ATTACHED_IMAGES)
        }
        if (skippedCount > 0) {
            error.value = "部分图片超出大小或格式不支持，已跳过 $skippedCount 张"
        }
    }

    fun removeImage(index: Int) {
        val current = attachedImages.value
        if (index in current.indices) {
            attachedImages.value = current.toMutableList().apply { removeAt(index) }
        }
    }

    fun createNewSession() {
        cancelSending()
        viewModelScope.launch {
            val created = chatHistory.createSession("新对话")
            switchToSession(created.id)
        }
    }

    fun switchToSession(sessionId: String) {
        if (activeSessionId.value == sessionId && sessionObserverJob != null) return
        cancelSending()
        activeSessionId.value = sessionId
        sessionObserverJob?.cancel()
        sessionObserverJob = viewModelScope.launch {
            chatHistory.observeMessages(sessionId).collectLatest { persisted ->
                messages.value = persisted.map { msg ->
                    AssistantChatItem(
                        id = msg.id,
                        role = msg.role,
                        text = msg.text,
                        toolTraces = msg.toolTraces,
                        pendingActions = msg.pendingActions,
                        isError = msg.isError,
                        imageCount = msg.imageCount,
                    )
                }
            }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            chatHistory.deleteSession(sessionId)
            if (activeSessionId.value == sessionId) {
                sessionObserverJob?.cancel()
                sessionObserverJob = null
                activeSessionId.value = null
                messages.value = emptyList()
            }
        }
    }

    fun send(textOverride: String? = null) {
        if (sending.value) return
        val imagesToSend = if (textOverride == null) attachedImages.value else emptyList()
        val rawContent = (textOverride ?: inputText.value).trim()
        val content = rawContent.ifEmpty {
            if (imagesToSend.isNotEmpty()) "请识别图片内容并协助处理。" else ""
        }
        if (content.isEmpty() && imagesToSend.isEmpty()) return

        val currentConfig = config.value
        if (!currentConfig.isConfigured) {
            error.value = "请先配置 AI 接口"
            return
        }

        val priorHistory = buildHistoryForModel(messages.value)
        val userItemId = UUID.randomUUID().toString()
        val userItem = AssistantChatItem(
            id = userItemId,
            role = AiChatRole.USER,
            text = content,
            imageCount = imagesToSend.size,
        )
        appendMessageDedup(userItem)
        if (textOverride == null) {
            inputText.value = ""
            attachedImages.value = emptyList()
        }

        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            sending.value = true
            streamingText.value = ""
            progressLabel.value = "思考中"
            error.value = null
            var targetSessionId: String? = null
            try {
                targetSessionId = ensureSessionForPrompt(content)
                val now = System.currentTimeMillis()
                chatHistory.appendMessage(
                    PersistedAiChatMessage(
                        id = userItemId,
                        sessionId = targetSessionId,
                        role = AiChatRole.USER,
                        text = content,
                        imageCount = imagesToSend.size,
                        createdAt = now,
                    ),
                )

                val output = assistant.sendMessageStream(
                    config = currentConfig,
                    history = priorHistory,
                    userText = content,
                    images = imagesToSend,
                    onToolProgress = { label ->
                        streamingText.value = ""
                        progressLabel.value = label
                    },
                    onTextDelta = { delta ->
                        streamingText.value += delta
                    },
                )

                val assistantItem = if (output.errorMessage != null) {
                    AssistantChatItem(
                        id = UUID.randomUUID().toString(),
                        role = AiChatRole.ASSISTANT,
                        text = output.errorMessage.orEmpty(),
                        toolTraces = output.toolTraces,
                        pendingActions = output.pendingActions,
                        isError = true,
                    )
                } else {
                    AssistantChatItem(
                        id = UUID.randomUUID().toString(),
                        role = AiChatRole.ASSISTANT,
                        text = output.replyText.ifBlank { "已完成操作" },
                        toolTraces = output.toolTraces,
                        pendingActions = output.pendingActions,
                    )
                }
                appendMessageDedup(assistantItem)
                chatHistory.appendMessage(
                    PersistedAiChatMessage(
                        id = assistantItem.id,
                        sessionId = targetSessionId,
                        role = AiChatRole.ASSISTANT,
                        text = assistantItem.text,
                        toolTraces = assistantItem.toolTraces,
                        pendingActions = assistantItem.pendingActions,
                        isError = assistantItem.isError,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            } catch (_: CancellationException) {
                // User cancelled active turn.
            } catch (failure: Exception) {
                val errItem = AssistantChatItem(
                    id = UUID.randomUUID().toString(),
                    role = AiChatRole.ASSISTANT,
                    text = failure.message ?: "请求失败",
                    isError = true,
                )
                appendMessageDedup(errItem)
                targetSessionId?.let { sid ->
                    runCatching {
                        chatHistory.appendMessage(
                            PersistedAiChatMessage(
                                id = errItem.id,
                                sessionId = sid,
                                role = AiChatRole.ASSISTANT,
                                text = errItem.text,
                                isError = true,
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
            } finally {
                sending.value = false
                streamingText.value = ""
                progressLabel.value = ""
            }
        }
    }

    fun cancelSending() {
        sendJob?.cancel()
        sendJob = null
        sending.value = false
        streamingText.value = ""
        progressLabel.value = ""
    }

    fun confirmAction(messageId: String, actionId: String) {
        val targetAction = messages.value
            .firstOrNull { it.id == messageId }
            ?.pendingActions
            ?.firstOrNull { it.id == actionId }
            ?: return
        if (targetAction.status != AssistantActionStatus.PENDING) return

        viewModelScope.launch {
            val result = assistant.executePendingAction(targetAction)
            val newStatus: AssistantActionStatus
            val newMessage: String
            if (result.isSuccess) {
                newStatus = AssistantActionStatus.CONFIRMED
                newMessage = result.getOrNull().orEmpty()
            } else {
                newStatus = AssistantActionStatus.FAILED
                newMessage = result.exceptionOrNull()?.message ?: "执行失败"
            }
            updateActionState(messageId, actionId) { current ->
                current.copy(status = newStatus, resultMessage = newMessage)
            }
            chatHistory.updateActionStatus(messageId, actionId, newStatus, newMessage)
        }
    }

    fun cancelAction(messageId: String, actionId: String) {
        updateActionState(messageId, actionId) { current ->
            if (current.status != AssistantActionStatus.PENDING) current
            else current.copy(status = AssistantActionStatus.CANCELLED, resultMessage = "已取消")
        }
        viewModelScope.launch {
            chatHistory.updateActionStatus(
                messageId = messageId,
                actionId = actionId,
                status = AssistantActionStatus.CANCELLED,
                resultMessage = "已取消",
            )
        }
    }

    fun clearConversation() {
        cancelSending()
        val currentSession = activeSessionId.value
        messages.value = emptyList()
        error.value = null
        if (currentSession != null) {
            viewModelScope.launch {
                chatHistory.clearSessionMessages(currentSession)
            }
        }
    }

    fun consumeError() {
        error.value = null
    }

    private fun observeSessionList() {
        viewModelScope.launch {
            chatHistory.observeSessions().collect { list ->
                sessions.value = list
                val current = activeSessionId.value
                if (current == null && list.isNotEmpty() && !sending.value && messages.value.isEmpty()) {
                    switchToSession(list.first().id)
                }
            }
        }
    }

    private suspend fun ensureSessionForPrompt(firstPrompt: String): String {
        val current = activeSessionId.value
        if (current != null) {
            val summary = sessions.value.firstOrNull { it.id == current }
            if (summary != null && summary.title == "新对话" && messages.value.size <= 1) {
                chatHistory.renameSession(current, firstPrompt.take(18))
            }
            return current
        }
        val created = chatHistory.createSession(firstPrompt.take(18).ifBlank { "新对话" })
        switchToSession(created.id)
        return created.id
    }

    private fun appendMessageDedup(item: AssistantChatItem) {
        if (messages.value.none { it.id == item.id }) {
            messages.value = messages.value + item
        }
    }

    private fun updateActionState(
        messageId: String,
        actionId: String,
        transform: (AssistantPendingAction) -> AssistantPendingAction,
    ) {
        messages.value = messages.value.map { item ->
            if (item.id != messageId) item
            else item.copy(
                pendingActions = item.pendingActions.map { action ->
                    if (action.id == actionId) transform(action) else action
                },
            )
        }
    }

    private fun buildHistoryForModel(items: List<AssistantChatItem>): List<AiChatMessage> =
        items
            .filter { !it.isError && it.text.isNotBlank() }
            .takeLast(MAX_HISTORY_ITEMS)
            .map { item ->
                AiChatMessage(
                    role = item.role,
                    content = item.text,
                )
            }

    companion object {
        const val MAX_ATTACHED_IMAGES = 3
        private const val MAX_HISTORY_ITEMS = 20
    }
}
