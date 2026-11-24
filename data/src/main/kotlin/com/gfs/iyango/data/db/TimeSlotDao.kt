package com.gfs.iyango.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 作息时间表 DAO。整体替换流程：`upsertAll` 写入 + `deleteByTimetable` 清旧行。
 */
@Dao
interface TimeSlotDao {

    /** 按节次号升序观察指定课表的作息。 */
    @Query("SELECT * FROM time_slot WHERE timetableId = :timetableId ORDER BY section ASC")
    fun observeByTimetable(timetableId: Long): Flow<List<TimeSlotEntity>>

    /** 按节次号升序读取指定课表的作息（导出/导入用）。 */
    @Query("SELECT * FROM time_slot WHERE timetableId = :timetableId ORDER BY section ASC")
    suspend fun getByTimetable(timetableId: Long): List<TimeSlotEntity>

    /** 批量新增或更新（按主键存在与否逐行决定 insert/update）。 */
    @Upsert
    suspend fun upsertAll(entities: List<TimeSlotEntity>)

    /** 删除指定课表的全部作息行。 */
    @Query("DELETE FROM time_slot WHERE timetableId = :timetableId")
    suspend fun deleteByTimetable(timetableId: Long)
}
