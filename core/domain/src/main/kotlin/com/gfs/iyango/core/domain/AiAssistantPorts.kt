package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.AdjustmentType
import com.gfs.iyango.core.model.WeekType

/** 对话消息角色。 */
enum class AiChatRole {
    USER,
    ASSISTANT,
    TOOL,
}

/** 模型发起的工具调用。 */
data class AiToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

/** 通用对话消息（支持 Tool Calling 与用户附带多模态图片）。 */
data class AiChatMessage(
    val role: AiChatRole,
    val content: String = "",
    val toolCalls: List<AiToolCall> = emptyList(),
    val toolCallId: String = "",
    val toolName: String = "",
    val images: List<AiImagePayload> = emptyList(),
)

/** 传给模型的工具声明。 */
data class AiToolDefinition(
    val name: String,
    val description: String,
    val parametersSchemaJson: String,
)

/** 单轮对话请求返回。 */
sealed interface AiChatTurnResult {
    data class Reply(
        val text: String,
        val toolCalls: List<AiToolCall> = emptyList(),
    ) : AiChatTurnResult

    data class Failed(
        val message: String,
    ) : AiChatTurnResult
}

/** 统一三协议 AI 对话与 Tool Calling 端口。 */
interface AiChatPort {
    suspend fun completeTurn(
        config: AiProviderConfig,
        systemPrompt: String,
        messages: List<AiChatMessage>,
        tools: List<AiToolDefinition> = emptyList(),
    ): AiChatTurnResult

    /**
     * 流式发起单轮对话；收到文本增量时回调 [onTextDelta]。
     * 未覆写流式实现时自动回退到 [completeTurn]。
     */
    suspend fun streamTurn(
        config: AiProviderConfig,
        systemPrompt: String,
        messages: List<AiChatMessage>,
        tools: List<AiToolDefinition> = emptyList(),
        onTextDelta: (String) -> Unit = {},
    ): AiChatTurnResult {
        val result = completeTurn(config, systemPrompt, messages, tools)
        if (result is AiChatTurnResult.Reply && result.text.isNotEmpty()) {
            onTextDelta(result.text)
        }
        return result
    }
}

/** 待用户确认的写入操作状态。 */
enum class AssistantActionStatus {
    PENDING,
    CONFIRMED,
    CANCELLED,
    FAILED,
}

/** 待用户二次确认的本地数据库写入载荷。 */
sealed interface AssistantWritePayload {
    data class AddCourse(
        val name: String,
        val teacher: String = "",
        val classroom: String = "",
        val dayOfWeek: Int = 1,
        val startSection: Int = 1,
        val endSection: Int = 1,
        val startWeek: Int = 1,
        val endWeek: Int = 16,
        val weekType: WeekType = WeekType.EVERY,
        val timeMode: Int = 0,
        val startTime: String = "",
        val endTime: String = "",
        val date: String = "",
        val remark: String = "",
    ) : AssistantWritePayload

    data class EditCourse(
        val courseId: Long,
        val timetableId: Long,
        val name: String,
        val teacher: String = "",
        val classroom: String = "",
        val dayOfWeek: Int = 1,
        val startSection: Int = 1,
        val endSection: Int = 1,
        val startWeek: Int = 1,
        val endWeek: Int = 16,
        val weekType: WeekType = WeekType.EVERY,
        val remark: String = "",
    ) : AssistantWritePayload

    data class DeleteCourse(
        val courseId: Long,
        val timetableId: Long,
        val courseName: String,
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

    data class EditExam(
        val examId: Long,
        val subject: String,
        val date: String,
        val startTime: String = "",
        val endTime: String = "",
        val location: String = "",
        val seat: String = "",
        val note: String = "",
    ) : AssistantWritePayload

    data class DeleteExam(
        val examId: Long,
        val subject: String,
    ) : AssistantWritePayload
}

/** 单条待确认写入操作卡片。 */
data class AssistantPendingAction(
    val id: String,
    val title: String,
    val summaryLines: List<String>,
    val payload: AssistantWritePayload,
    val status: AssistantActionStatus = AssistantActionStatus.PENDING,
    val resultMessage: String = "",
)

/** 一次工具执行摘要（供聊天界面折叠/标签展示）。 */
data class AssistantToolTrace(
    val toolName: String,
    val label: String,
    val success: Boolean,
)

/** AI 助理完成一轮用户对话后的输出。 */
data class AssistantTurnOutput(
    val replyText: String,
    val toolTraces: List<AssistantToolTrace> = emptyList(),
    val pendingActions: List<AssistantPendingAction> = emptyList(),
    val errorMessage: String? = null,
)

/** 校园本地工具调用 AI 助理端口。 */
interface CampusAiAssistantPort {
    suspend fun sendMessage(
        config: AiProviderConfig,
        history: List<AiChatMessage>,
        userText: String,
        onToolProgress: (String) -> Unit = {},
    ): AssistantTurnOutput

    /**
     * 支持附带多模态图片与流式文本输出的助理对话入口。
     * 未覆写时自动回退至 [sendMessage]。
     */
    suspend fun sendMessageStream(
        config: AiProviderConfig,
        history: List<AiChatMessage>,
        userText: String,
        images: List<AiImagePayload> = emptyList(),
        onToolProgress: (String) -> Unit = {},
        onTextDelta: (String) -> Unit = {},
    ): AssistantTurnOutput {
        val output = sendMessage(config, history, userText, onToolProgress)
        if (output.replyText.isNotEmpty()) {
            onTextDelta(output.replyText)
        }
        return output
    }

    suspend fun executePendingAction(action: AssistantPendingAction): Result<String>
}
