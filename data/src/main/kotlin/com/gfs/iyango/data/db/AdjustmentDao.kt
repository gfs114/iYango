package com.gfs.iyango.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 临时调整 DAO。 */
@Dao
interface OccurrenceAdjustmentDao {

    @Query("SELECT * FROM occurrence_adjustment WHERE timetableId = :timetableId ORDER BY sourceDate ASC, id ASC")
    fun observeByTimetable(timetableId: Long): Flow<List<OccurrenceAdjustmentEntity>>

    @Query("SELECT * FROM occurrence_adjustment WHERE timetableId = :timetableId ORDER BY sourceDate ASC, id ASC")
    suspend fun getByTimetable(timetableId: Long): List<OccurrenceAdjustmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: OccurrenceAdjustmentEntity): Long

    @Query("DELETE FROM occurrence_adjustment WHERE timetableId = :timetableId AND sourceCourseId = :courseId AND sourceDate = :sourceDate")
    suspend fun deleteAnchor(timetableId: Long, courseId: Long, sourceDate: String)

    @Query("DELETE FROM occurrence_adjustment WHERE operationGroupId = :groupId")
    suspend fun deleteByGroup(groupId: String)

    @Query("DELETE FROM occurrence_adjustment WHERE timetableId = :timetableId")
    suspend fun deleteByTimetable(timetableId: Long)
}

/** 临时整天 DAO。 */
@Dao
interface TemporaryDayDao {

    @Query("SELECT * FROM temporary_day WHERE timetableId = :timetableId ORDER BY date ASC")
    fun observeByTimetable(timetableId: Long): Flow<List<TemporaryDayEntity>>

    @Query("SELECT * FROM temporary_day WHERE timetableId = :timetableId ORDER BY date ASC")
    suspend fun getByTimetable(timetableId: Long): List<TemporaryDayEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TemporaryDayEntity): Long

    @Query("DELETE FROM temporary_day WHERE timetableId = :timetableId AND date = :date")
    suspend fun deleteDate(timetableId: Long, date: String)

    @Query("DELETE FROM temporary_day WHERE operationGroupId = :groupId")
    suspend fun deleteByGroup(groupId: String)

    @Query("DELETE FROM temporary_day WHERE timetableId = :timetableId")
    suspend fun deleteByTimetable(timetableId: Long)
}
