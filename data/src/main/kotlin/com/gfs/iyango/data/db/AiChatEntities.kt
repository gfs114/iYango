package com.gfs.iyango.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** AI 助理会话表。 */
@Entity(
    tableName = "ai_chat_session",
    indices = [Index(value = ["updatedAt"])],
)
data class AiChatSessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(defaultValue = "新对话") val title: String = "新对话",
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0L,
)

/** AI 助理消息表（外键级联删除所属会话，图片仅保存计数不落库原始字节）。 */
@Entity(
    tableName = "ai_chat_message",
    foreignKeys = [
        ForeignKey(
            entity = AiChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["createdAt"]),
    ],
)
data class AiChatMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    @ColumnInfo(defaultValue = "") val text: String = "",
    @ColumnInfo(defaultValue = "[]") val toolTracesJson: String = "[]",
    @ColumnInfo(defaultValue = "[]") val pendingActionsJson: String = "[]",
    @ColumnInfo(defaultValue = "0") val isError: Boolean = false,
    @ColumnInfo(defaultValue = "0") val imageCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0L,
)
