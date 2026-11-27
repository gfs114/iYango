package com.gfs.iyango.core.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** AI 助理持久化会话摘要。 */
data class AiChatSessionSummary(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int = 0,
)

/** AI 助理持久化消息记录。 */
data class PersistedAiChatMessage(
    val id: String,
    val sessionId: String,
    val role: AiChatRole,
    val text: String,
    val toolTraces: List<AssistantToolTrace> = emptyList(),
    val pendingActions: List<AssistantPendingAction> = emptyList(),
    val isError: Boolean = false,
    val imageCount: Int = 0,
    val createdAt: Long,
)

/** AI 助理本地多会话持久化端口。 */
interface AiChatHistoryPort {
    fun observeSessions(): Flow<List<AiChatSessionSummary>>
    fun observeMessages(sessionId: String): Flow<List<PersistedAiChatMessage>>
    suspend fun createSession(title: String = "新对话"): AiChatSessionSummary
    suspend fun renameSession(sessionId: String, title: String)
    suspend fun appendMessage(message: PersistedAiChatMessage)
    suspend fun updateActionStatus(
        messageId: String,
        actionId: String,
        status: AssistantActionStatus,
        resultMessage: String,
    )
    suspend fun clearSessionMessages(sessionId: String)
    suspend fun deleteSession(sessionId: String)
}

/** 内存实现（供预览与无数据库单测使用）。 */
class InMemoryAiChatHistoryPort : AiChatHistoryPort {
    private val sessionsState = MutableStateFlow<List<AiChatSessionSummary>>(emptyList())
    private val messagesState = MutableStateFlow<List<PersistedAiChatMessage>>(emptyList())
    private var seq = 0L

    override fun observeSessions(): Flow<List<AiChatSessionSummary>> =
        sessionsState.map { list -> list.sortedByDescending { it.updatedAt } }

    override fun observeMessages(sessionId: String): Flow<List<PersistedAiChatMessage>> =
        messagesState.map { list ->
            list.filter { it.sessionId == sessionId }.sortedBy { it.createdAt }
        }

    override suspend fun createSession(title: String): AiChatSessionSummary {
        val now = ++seq
        val summary = AiChatSessionSummary(
            id = "session_$now",
            title = title.ifBlank { "新对话" },
            createdAt = now,
            updatedAt = now,
            messageCount = 0,
        )
        sessionsState.value = listOf(summary) + sessionsState.value
        return summary
    }

    override suspend fun renameSession(sessionId: String, title: String) {
        val clean = title.trim().ifBlank { return }
        sessionsState.value = sessionsState.value.map {
            if (it.id == sessionId) it.copy(title = clean, updatedAt = ++seq) else it
        }
    }

    override suspend fun appendMessage(message: PersistedAiChatMessage) {
        messagesState.value = messagesState.value + message
        val count = messagesState.value.count { it.sessionId == message.sessionId }
        sessionsState.value = sessionsState.value.map {
            if (it.id == message.sessionId) {
                it.copy(updatedAt = maxOf(it.updatedAt, message.createdAt), messageCount = count)
            } else {
                it
            }
        }
    }

    override suspend fun updateActionStatus(
        messageId: String,
        actionId: String,
        status: AssistantActionStatus,
        resultMessage: String,
    ) {
        messagesState.value = messagesState.value.map { msg ->
            if (msg.id != messageId) msg
            else msg.copy(
                pendingActions = msg.pendingActions.map { act ->
                    if (act.id == actionId) act.copy(status = status, resultMessage = resultMessage) else act
                },
            )
        }
    }

    override suspend fun clearSessionMessages(sessionId: String) {
        messagesState.value = messagesState.value.filterNot { it.sessionId == sessionId }
        sessionsState.value = sessionsState.value.map {
            if (it.id == sessionId) it.copy(messageCount = 0) else it
        }
    }

    override suspend fun deleteSession(sessionId: String) {
        messagesState.value = messagesState.value.filterNot { it.sessionId == sessionId }
        sessionsState.value = sessionsState.value.filterNot { it.id == sessionId }
    }
}
