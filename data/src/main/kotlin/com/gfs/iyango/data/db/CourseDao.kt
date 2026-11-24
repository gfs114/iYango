package com.gfs.iyango.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 课程 DAO。课程行包含基础课与单次调整记录（overrideOfId > 0），统一存本表。
 */
@Dao
interface CourseDao {

    /** 按（星期，起始节次）升序观察指定课表的全部课程（含停用与调整记录）。 */
    @Query("SELECT * FROM course WHERE timetableId = :timetableId ORDER BY dayOfWeek ASC, startSection ASC")
    fun observeByTimetable(timetableId: Long): Flow<List<CourseEntity>>

    /** 按（星期，起始节次）升序读取指定课表的全部课程（含停用与调整记录；导入/导出/唯一性预检用）。 */
    @Query("SELECT * FROM course WHERE timetableId = :timetableId ORDER BY dayOfWeek ASC, startSection ASC")
    suspend fun getAllByTimetable(timetableId: Long): List<CourseEntity>

    /** 删除指定课表的全部课程（替换导入先清后写；级联不受影响）。 */
    @Query("DELETE FROM course WHERE timetableId = :timetableId")
    suspend fun deleteByTimetable(timetableId: Long)

    /**
     * 新增或更新课程（按主键存在与否决定 insert/update）。
     *
     * @return 新插入行的 rowId；走更新分支时 Room 返回 -1，调用方应回退使用 entity.id。
     */
    @Upsert
    suspend fun upsert(entity: CourseEntity): Long

    /** 批量新增或更新课程。 */
    @Upsert
    suspend fun upsertAll(entities: List<CourseEntity>)

    /** 按主键读取单条课程；不存在返回 null。 */
    @Query("SELECT * FROM course WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): CourseEntity?

    /** 删除单条课程（删除调整记录不影响被调整的基础课）。 */
    @Query("DELETE FROM course WHERE id = :id")
    suspend fun deleteById(id: Long)
}
