package com.gfs.iyango.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 考试 DAO；日期字段为 ISO yyyy-MM-dd，可直接做字符串比较。 */
@Dao
interface ExamDao {

    @Query("SELECT * FROM exam ORDER BY date ASC, startTime ASC, id ASC")
    fun observeAll(): Flow<List<ExamEntity>>

    @Query(
        "SELECT * FROM exam WHERE date <= :to AND " +
            "(CASE WHEN endDate != '' THEN endDate ELSE date END) >= :from " +
            "ORDER BY date ASC, startTime ASC, id ASC",
    )
    fun observeInRange(from: String, to: String): Flow<List<ExamEntity>>

    @Query("SELECT * FROM exam WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ExamEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ExamEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ExamEntity>)

    @Query("DELETE FROM exam WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM exam WHERE source = 'SCHOOL' AND connectionId = :connectionId AND semester = :semester")
    suspend fun deleteSchoolExams(connectionId: String, semester: String)

    @Query("SELECT * FROM exam WHERE source = 'SCHOOL' AND connectionId = :connectionId AND semester = :semester")
    suspend fun getSchoolExams(connectionId: String, semester: String): List<ExamEntity>
}
