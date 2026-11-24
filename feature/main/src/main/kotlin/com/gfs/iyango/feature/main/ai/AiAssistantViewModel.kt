package com.gfs.iyango.feature.main.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.domain.AiChatMessage
import com.gfs.iyango.core.domain.AiChatRole
import com.gfs.iyango.core.domain.AiConfigStorePort
import com.gfs.iyango.core.domain.AiProviderConfig
import com.gfs.iyango.core.domain.AssistantActionStatus
import com.gfs.iyango.core.domain.AssistantPendingAction
import com.gfs.iyango.core.domain.AssistantToolTrace
import com.gfs.iyango.core.domain.CampusAiAssistantPort
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class AssistantChatItem(
    val id: String,
    val role: AiChatRole,
    val text: String,
    val toolTraces: List<AssistantToolTrace> = emptyList(),
    val pendingActions: List<AssistantPendingAction> = emptyList(),
    val isError: Boolean = false,
)

/**
 * 校园 AI 助理 ViewModel。
 */
@HiltViewModel
class AiAssistantViewModel @Inject constructor(
    private val aiConfigStore: AiConfigStorePort,
    private val assistant: CampusAiAssistantPort,
) : ViewModel() {

    val config = MutableStateFlow(AiProviderConfig())
    val messages = MutableStateFlow<List<AssistantChatItem>>(emptyList())
    val inputText = MutableStateFlow("")
    val sending = MutableStateFlow(false)
    val progressLabel = MutableStateFlow("")
    val error = MutableStateFlow<String?>(null)

    private var sendJob: Job? = null

    init {
        reloadConfig()
    }

    fun reloadConfig() {
        viewModelScope.launch {
            runCatching { aiConfigStore.load() }
                .onSuccess { loaded -> config.value = loaded }
        }
    }

    fun send(textOverride: String? = null) {
        if (sending.value) return
        val content = (textOverride ?: inputText.value).trim()
        if (content.isEmpty()) return
        val currentConfig = config.value
        if (!currentConfig.isConfigured) {
            error.value = "请先配置 AI 接口"
            return
        }

        val priorHistory = buildHistoryForModel(messages.value)
        val userItem = AssistantChatItem(
            id = UUID.randomUUID().toString(),
            role = AiChatRole.USER,
            text = content,
        )
        messages.value = messages.value + userItem
        if (textOverride == null) {
            inputText.value = ""
        }

        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            sending.value = true
            progressLabel.value = "思考中"
            error.value = null
            try {
                val output = assistant.sendMessage(
                    config = currentConfig,
                    history = priorHistory,
                    userText = content,
                    onToolProgress = { label -> progressLabel.value = label },
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
                messages.value = messages.value + assistantItem
            } catch (_: CancellationException) {
                // User cancelled active turn.
            } catch (failure: Exception) {
                messages.value = messages.value + AssistantChatItem(
                    id = UUID.randomUUID().toString(),
                    role = AiChatRole.ASSISTANT,
                    text = failure.message ?: "请求失败",
                    isError = true,
                )
            } finally {
                sending.value = false
                progressLabel.value = ""
            }
        }
    }

    fun cancelSending() {
        sendJob?.cancel()
        sendJob = null
        sending.value = false
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
            updateActionState(messageId, actionId) { current ->
                result.fold(
                    onSuccess = { msg ->
                        current.copy(
                            status = AssistantActionStatus.CONFIRMED,
                            resultMessage = msg,
                        )
                    },
                    onFailure = { failure ->
                        current.copy(
                            status = AssistantActionStatus.FAILED,
                            resultMessage = failure.message ?: "执行失败",
                        )
                    },
                )
            }
        }
    }

    fun cancelAction(messageId: String, actionId: String) {
        updateActionState(messageId, actionId) { current ->
            if (current.status != AssistantActionStatus.PENDING) current
            else current.copy(status = AssistantActionStatus.CANCELLED, resultMessage = "已取消")
        }
    }

    fun clearConversation() {
        cancelSending()
        messages.value = emptyList()
        error.value = null
    }

    fun consumeError() {
        error.value = null
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

    private companion object {
        private const val MAX_HISTORY_ITEMS = 20
    }
}
