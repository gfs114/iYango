package com.gfs.iyango.data.repository

import androidx.room.withTransaction
import com.gfs.iyango.core.domain.AiChatHistoryPort
import com.gfs.iyango.core.domain.AiChatRole
import com.gfs.iyango.core.domain.AiChatSessionSummary
import com.gfs.iyango.core.domain.AssistantActionStatus
import com.gfs.iyango.core.domain.AssistantPendingAction
import com.gfs.iyango.core.domain.AssistantToolTrace
import com.gfs.iyango.core.domain.AssistantWritePayload
import com.gfs.iyango.core.domain.PersistedAiChatMessage
import com.gfs.iyango.core.model.AdjustmentType
import com.gfs.iyango.core.model.WeekType
import com.gfs.iyango.data.db.AiChatMessageEntity
import com.gfs.iyango.data.db.AiChatSessionEntity
import com.gfs.iyango.data.db.IYangoDatabase
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 基于 Room 的 AI 助理多会话与消息历史持久化实现。
 */
@Singleton
class RoomAiChatHistoryRepository @Inject constructor(
    private val db: IYangoDatabase,
    private val clock: Clock,
) : AiChatHistoryPort {

    private val dao get() = db.aiChatDao()

    override fun observeSessions(): Flow<List<AiChatSessionSummary>> =
        dao.observeSessionsWithCount().map { rows ->
            rows.map { row ->
                AiChatSessionSummary(
                    id = row.id,
                    title = row.title,
                    createdAt = row.createdAt,
                    updatedAt = row.updatedAt,
                    messageCount = row.messageCount,
                )
            }
        }

    override fun observeMessages(sessionId: String): Flow<List<PersistedAiChatMessage>> =
        dao.observeMessages(sessionId).map { rows ->
            rows.map { it.toDomain() }
        }

    override suspend fun createSession(title: String): AiChatSessionSummary {
        val now = clock.millis()
        val id = UUID.randomUUID().toString()
        val cleanTitle = title.trim().ifEmpty { "新对话" }.take(40)
        val entity = AiChatSessionEntity(
            id = id,
            title = cleanTitle,
            createdAt = now,
            updatedAt = now,
        )
        dao.upsertSession(entity)
        return AiChatSessionSummary(
            id = id,
            title = cleanTitle,
            createdAt = now,
            updatedAt = now,
            messageCount = 0,
        )
    }

    override suspend fun renameSession(sessionId: String, title: String) {
        val cleanTitle = title.trim().ifEmpty { return }.take(40)
        dao.updateSessionTitle(sessionId, cleanTitle, clock.millis())
    }

    override suspend fun appendMessage(message: PersistedAiChatMessage) {
        db.withTransaction {
            val existing = dao.getSession(message.sessionId)
            val createdAt = message.createdAt.takeIf { it > 0L } ?: clock.millis()
            if (existing == null) {
                dao.upsertSession(
                    AiChatSessionEntity(
                        id = message.sessionId,
                        title = "新对话",
                        createdAt = createdAt,
                        updatedAt = createdAt,
                    ),
                )
            } else {
                dao.touchSession(message.sessionId, maxOf(existing.updatedAt, createdAt))
            }
            dao.upsertMessage(message.copy(createdAt = createdAt).toEntity())
        }
    }

    override suspend fun updateActionStatus(
        messageId: String,
        actionId: String,
        status: AssistantActionStatus,
        resultMessage: String,
    ) {
        db.withTransaction {
            val entity = dao.getMessage(messageId) ?: return@withTransaction
            val actions = decodeActions(entity.pendingActionsJson)
            if (actions.none { it.id == actionId }) return@withTransaction
            val updated = actions.map { action ->
                if (action.id == actionId) {
                    action.copy(status = status, resultMessage = resultMessage)
                } else {
                    action
                }
            }
            dao.upsertMessage(entity.copy(pendingActionsJson = encodeActions(updated)))
        }
    }

    override suspend fun clearSessionMessages(sessionId: String) {
        dao.deleteMessagesForSession(sessionId)
    }

    override suspend fun deleteSession(sessionId: String) {
        dao.deleteSession(sessionId)
    }

    private fun PersistedAiChatMessage.toEntity(): AiChatMessageEntity = AiChatMessageEntity(
        id = id,
        sessionId = sessionId,
        role = role.name,
        text = text,
        toolTracesJson = encodeTraces(toolTraces),
        pendingActionsJson = encodeActions(pendingActions),
        isError = isError,
        imageCount = imageCount,
        createdAt = createdAt,
    )

    private fun AiChatMessageEntity.toDomain(): PersistedAiChatMessage = PersistedAiChatMessage(
        id = id,
        sessionId = sessionId,
        role = runCatching { AiChatRole.valueOf(role) }.getOrDefault(AiChatRole.ASSISTANT),
        text = text,
        toolTraces = decodeTraces(toolTracesJson),
        pendingActions = decodeActions(pendingActionsJson),
        isError = isError,
        imageCount = imageCount,
        createdAt = createdAt,
    )

    @Serializable
    private data class ToolTraceDto(
        val toolName: String = "",
        val label: String = "",
        val success: Boolean = false,
    )

    @Serializable
    private data class PendingActionDto(
        val id: String = "",
        val title: String = "",
        val summaryLines: List<String> = emptyList(),
        val status: String = AssistantActionStatus.PENDING.name,
        val resultMessage: String = "",
        val kind: String = "",
        val courseId: Long = 0L,
        val timetableId: Long = 0L,
        val sourceCourseId: Long = 0L,
        val examId: Long = 0L,
        val name: String = "",
        val teacher: String = "",
        val classroom: String = "",
        val dayOfWeek: Int = 1,
        val startSection: Int = 1,
        val endSection: Int = 1,
        val startWeek: Int = 1,
        val endWeek: Int = 16,
        val weekType: String = WeekType.EVERY.name,
        val timeMode: Int = 0,
        val startTime: String = "",
        val endTime: String = "",
        val date: String = "",
        val remark: String = "",
        val sourceDate: String = "",
        val adjustmentType: String = AdjustmentType.MOVE.name,
        val targetDate: String = "",
        val targetStartSection: Int = 0,
        val targetEndSection: Int = 0,
        val targetClassroom: String = "",
        val note: String = "",
        val subject: String = "",
        val location: String = "",
        val seat: String = "",
    )

    private companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encodeTraces(traces: List<AssistantToolTrace>): String =
            if (traces.isEmpty()) "[]"
            else JSON.encodeToString(traces.map { ToolTraceDto(it.toolName, it.label, it.success) })

        fun decodeTraces(raw: String): List<AssistantToolTrace> =
            if (raw.isBlank() || raw == "[]") emptyList()
            else runCatching {
                JSON.decodeFromString<List<ToolTraceDto>>(raw).map {
                    AssistantToolTrace(toolName = it.toolName, label = it.label, success = it.success)
                }
            }.getOrDefault(emptyList())

        fun encodeActions(actions: List<AssistantPendingAction>): String =
            if (actions.isEmpty()) "[]"
            else JSON.encodeToString(actions.map { it.toDto() })

        fun decodeActions(raw: String): List<AssistantPendingAction> =
            if (raw.isBlank() || raw == "[]") emptyList()
            else runCatching {
                JSON.decodeFromString<List<PendingActionDto>>(raw).mapNotNull { it.toDomainOrNull() }
            }.getOrDefault(emptyList())

        private fun AssistantPendingAction.toDto(): PendingActionDto = when (val p = payload) {
            is AssistantWritePayload.AddCourse -> PendingActionDto(
                id = id,
                title = title,
                summaryLines = summaryLines,
                status = status.name,
                resultMessage = resultMessage,
                kind = "ADD_COURSE",
                name = p.name,
                teacher = p.teacher,
                classroom = p.classroom,
                dayOfWeek = p.dayOfWeek,
                startSection = p.startSection,
                endSection = p.endSection,
                startWeek = p.startWeek,
                endWeek = p.endWeek,
                weekType = p.weekType.name,
                timeMode = p.timeMode,
                startTime = p.startTime,
                endTime = p.endTime,
                date = p.date,
                remark = p.remark,
            )
            is AssistantWritePayload.EditCourse -> PendingActionDto(
                id = id,
                title = title,
                summaryLines = summaryLines,
                status = status.name,
                resultMessage = resultMessage,
                kind = "EDIT_COURSE",
                courseId = p.courseId,
                timetableId = p.timetableId,
                name = p.name,
                teacher = p.teacher,
                classroom = p.classroom,
                dayOfWeek = p.dayOfWeek,
                startSection = p.startSection,
                endSection = p.endSection,
                startWeek = p.startWeek,
                endWeek = p.endWeek,
                weekType = p.weekType.name,
                remark = p.remark,
            )
            is AssistantWritePayload.DeleteCourse -> PendingActionDto(
                id = id,
                title = title,
                summaryLines = summaryLines,
                status = status.name,
                resultMessage = resultMessage,
                kind = "DELETE_COURSE",
                courseId = p.courseId,
                timetableId = p.timetableId,
                name = p.courseName,
            )
            is AssistantWritePayload.AdjustCourse -> PendingActionDto(
                id = id,
                title = title,
                summaryLines = summaryLines,
                status = status.name,
                resultMessage = resultMessage,
                kind = "ADJUST_COURSE",
                timetableId = p.timetableId,
                sourceCourseId = p.sourceCourseId,
                name = p.courseName,
                sourceDate = p.sourceDate,
                adjustmentType = p.type.name,
                targetDate = p.targetDate,
                targetStartSection = p.targetStartSection,
                targetEndSection = p.targetEndSection,
                targetClassroom = p.targetClassroom,
                note = p.note,
            )
            is AssistantWritePayload.AddExam -> PendingActionDto(
                id = id,
                title = title,
                summaryLines = summaryLines,
                status = status.name,
                resultMessage = resultMessage,
                kind = "ADD_EXAM",
                subject = p.subject,
                date = p.date,
                startTime = p.startTime,
                endTime = p.endTime,
                location = p.location,
                seat = p.seat,
                note = p.note,
            )
            is AssistantWritePayload.EditExam -> PendingActionDto(
                id = id,
                title = title,
                summaryLines = summaryLines,
                status = status.name,
                resultMessage = resultMessage,
                kind = "EDIT_EXAM",
                examId = p.examId,
                subject = p.subject,
                date = p.date,
                startTime = p.startTime,
                endTime = p.endTime,
                location = p.location,
                seat = p.seat,
                note = p.note,
            )
            is AssistantWritePayload.DeleteExam -> PendingActionDto(
                id = id,
                title = title,
                summaryLines = summaryLines,
                status = status.name,
                resultMessage = resultMessage,
                kind = "DELETE_EXAM",
                examId = p.examId,
                subject = p.subject,
            )
        }

        private fun PendingActionDto.toDomainOrNull(): AssistantPendingAction? {
            val payload = when (kind) {
                "ADD_COURSE" -> AssistantWritePayload.AddCourse(
                    name = name,
                    teacher = teacher,
                    classroom = classroom,
                    dayOfWeek = dayOfWeek,
                    startSection = startSection,
                    endSection = endSection,
                    startWeek = startWeek,
                    endWeek = endWeek,
                    weekType = runCatching { WeekType.valueOf(weekType) }.getOrDefault(WeekType.EVERY),
                    timeMode = timeMode,
                    startTime = startTime,
                    endTime = endTime,
                    date = date,
                    remark = remark,
                )
                "EDIT_COURSE" -> AssistantWritePayload.EditCourse(
                    courseId = courseId,
                    timetableId = timetableId,
                    name = name,
                    teacher = teacher,
                    classroom = classroom,
                    dayOfWeek = dayOfWeek,
                    startSection = startSection,
                    endSection = endSection,
                    startWeek = startWeek,
                    endWeek = endWeek,
                    weekType = runCatching { WeekType.valueOf(weekType) }.getOrDefault(WeekType.EVERY),
                    remark = remark,
                )
                "DELETE_COURSE" -> AssistantWritePayload.DeleteCourse(
                    courseId = courseId,
                    timetableId = timetableId,
                    courseName = name,
                )
                "ADJUST_COURSE" -> AssistantWritePayload.AdjustCourse(
                    timetableId = timetableId,
                    sourceCourseId = sourceCourseId,
                    courseName = name,
                    sourceDate = sourceDate,
                    type = runCatching { AdjustmentType.valueOf(adjustmentType) }.getOrDefault(AdjustmentType.MOVE),
                    targetDate = targetDate,
                    targetStartSection = targetStartSection,
                    targetEndSection = targetEndSection,
                    targetClassroom = targetClassroom,
                    note = note,
                )
                "ADD_EXAM" -> AssistantWritePayload.AddExam(
                    subject = subject,
                    date = date,
                    startTime = startTime,
                    endTime = endTime,
                    location = location,
                    seat = seat,
                    note = note,
                )
                "EDIT_EXAM" -> AssistantWritePayload.EditExam(
                    examId = examId,
                    subject = subject,
                    date = date,
                    startTime = startTime,
                    endTime = endTime,
                    location = location,
                    seat = seat,
                    note = note,
                )
                "DELETE_EXAM" -> AssistantWritePayload.DeleteExam(
                    examId = examId,
                    subject = subject,
                )
                else -> return null
            }
            return AssistantPendingAction(
                id = id,
                title = title,
                summaryLines = summaryLines,
                payload = payload,
                status = runCatching { AssistantActionStatus.valueOf(status) }.getOrDefault(AssistantActionStatus.PENDING),
                resultMessage = resultMessage,
            )
        }
    }
}
