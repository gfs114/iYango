package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.AdjustmentType
import com.gfs.iyango.core.model.WeekType

enum class AiChatRole {
    USER,
    ASSISTANT,
    TOOL,
}

data class AiToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

data class AiChatMessage(
    val role: AiChatRole,
    val content: String = "",
    val toolCalls: List<AiToolCall> = emptyList(),
    val toolCallId: String = "",
    val toolName: String = "",
)

data class AiToolDefinition(
    val name: String,
    val description: String,
    val parametersSchemaJson: String,
)

sealed interface AiChatTurnResult {
    data class Reply(
        val text: String,
        val toolCalls: List<AiToolCall> = emptyList(),
    ) : AiChatTurnResult

    data class Failed(
        val message: String,
    ) : AiChatTurnResult
}

/**
 * 统一多协议对话与 Tool Calling 端口（支持 OpenAI Chat / OpenAI Responses / Anthropic Messages）。
 */
interface AiChatPort {
    suspend fun completeTurn(
        config: AiProviderConfig,
        systemPrompt: String,
        messages: List<AiChatMessage>,
        tools: List<AiToolDefinition> = emptyList(),
    ): AiChatTurnResult
}

enum class AssistantActionStatus {
    PENDING,
    CONFIRMED,
    CANCELLED,
    FAILED,
}

sealed interface AssistantWritePayload {
    data class AddCourse(
        val name: String,
        val teacher: String = "",
        val classroom: String = "",
        val dayOfWeek: Int = 1,
        val startSection: Int = 1,
        val endSection: Int = 2,
        val startWeek: Int = 1,
        val endWeek: Int = 16,
        val weekType: WeekType = WeekType.EVERY,
        val timeMode: Int = 0,
        val startTime: String = "",
        val endTime: String = "",
        val date: String = "",
        val remark: String = "",
    ) : AssistantWritePayload

    data class AdjustCourse(
        val timetableId: Long,
        val sourceCourseId: Long,
        val courseName: String,
        val sourceDate: String,
        val type: AdjustmentType,
        val targetDate: String = "",
        val targetStartSection: Int = 0,
        val targetEndSection: Int = 0,
        val targetClassroom: String = "",
        val note: String = "",
    ) : AssistantWritePayload

    data class AddExam(
        val subject: String,
        val date: String,
        val startTime: String = "",
        val endTime: String = "",
        val location: String = "",
        val seat: String = "",
        val note: String = "",
    ) : AssistantWritePayload
}

data class AssistantPendingAction(
    val id: String,
    val title: String,
    val summaryLines: List<String>,
    val payload: AssistantWritePayload,
    val status: AssistantActionStatus = AssistantActionStatus.PENDING,
    val resultMessage: String = "",
)

data class AssistantToolTrace(
    val toolName: String,
    val label: String,
    val success: Boolean,
)

data class AssistantTurnOutput(
    val replyText: String,
    val toolTraces: List<AssistantToolTrace> = emptyList(),
    val pendingActions: List<AssistantPendingAction> = emptyList(),
    val errorMessage: String? = null,
)

/**
 * 本地 Tool-Calling 校园 AI 助理端口。
 */
interface CampusAiAssistantPort {
    suspend fun sendMessage(
        config: AiProviderConfig,
        history: List<AiChatMessage>,
        userText: String,
        onToolProgress: (String) -> Unit = {},
    ): AssistantTurnOutput

    suspend fun executePendingAction(action: AssistantPendingAction): Result<String>
}
