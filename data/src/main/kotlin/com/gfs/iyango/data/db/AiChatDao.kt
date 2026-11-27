package com.gfs.iyango.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class AiChatSessionWithCountRow(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
)

@Dao
interface AiChatDao {

    @Query(
        "SELECT s.id, s.title, s.createdAt, s.updatedAt, COUNT(m.id) AS messageCount " +
            "FROM ai_chat_session s " +
            "LEFT JOIN ai_chat_message m ON m.sessionId = s.id " +
            "GROUP BY s.id " +
            "ORDER BY s.updatedAt DESC",
    )
    fun observeSessionsWithCount(): Flow<List<AiChatSessionWithCountRow>>

    @Query("SELECT * FROM ai_chat_message WHERE sessionId = :sessionId ORDER BY createdAt ASC, rowid ASC")
    fun observeMessages(sessionId: String): Flow<List<AiChatMessageEntity>>

    @Query("SELECT * FROM ai_chat_session WHERE id = :sessionId LIMIT 1")
    suspend fun getSession(sessionId: String): AiChatSessionEntity?

    @Query("SELECT * FROM ai_chat_message WHERE id = :messageId LIMIT 1")
    suspend fun getMessage(messageId: String): AiChatMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(entity: AiChatSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(entity: AiChatMessageEntity)

    @Query("UPDATE ai_chat_session SET title = :title, updatedAt = :updatedAt WHERE id = :sessionId")
    suspend fun updateSessionTitle(sessionId: String, title: String, updatedAt: Long)

    @Query("UPDATE ai_chat_session SET updatedAt = :updatedAt WHERE id = :sessionId")
    suspend fun touchSession(sessionId: String, updatedAt: Long)

    @Query("DELETE FROM ai_chat_message WHERE sessionId = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: String)

    @Query("DELETE FROM ai_chat_session WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)
}
