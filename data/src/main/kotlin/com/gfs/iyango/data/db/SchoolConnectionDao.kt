package com.gfs.iyango.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 学校连接 DAO。 */
@Dao
interface SchoolConnectionDao {

    @Query("SELECT * FROM school_connection ORDER BY lastLoginAt DESC, createdAt DESC")
    fun observeAll(): Flow<List<SchoolConnectionEntity>>

    @Query("SELECT * FROM school_connection WHERE status = 'ACTIVE' ORDER BY lastLoginAt DESC LIMIT 1")
    fun observeActive(): Flow<SchoolConnectionEntity?>

    @Query("SELECT * FROM school_connection WHERE status = 'ACTIVE' ORDER BY lastLoginAt DESC LIMIT 1")
    suspend fun getActive(): SchoolConnectionEntity?

    @Query("SELECT * FROM school_connection WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SchoolConnectionEntity?

    @Query("SELECT * FROM school_connection ORDER BY lastLoginAt DESC, createdAt DESC")
    suspend fun getAll(): List<SchoolConnectionEntity>

    @Query("SELECT * FROM school_connection WHERE accountSubject = :subject LIMIT 1")
    suspend fun findBySubject(subject: String): SchoolConnectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SchoolConnectionEntity)

    @Query("UPDATE school_connection SET status = 'LOGGED_OUT' WHERE id != :keepId")
    suspend fun deactivateOthers(keepId: String)

    @Query("UPDATE school_connection SET status = :status, lastError = :error WHERE id = :id")
    suspend fun updateStatus(id: String, status: com.gfs.iyango.core.model.SchoolConnectionStatus, error: String)

    @Query("UPDATE school_connection SET sessionRevision = sessionRevision + 1 WHERE id = :id")
    suspend fun bumpSessionRevision(id: String)

    @Query("DELETE FROM school_connection WHERE id = :id")
    suspend fun deleteById(id: String)
}
