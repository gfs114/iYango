package com.gfs.iyango.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 学期 DAO。一个课表可有多个学期，"当前学期"建议至多一个（isCurrent）。
 */
@Dao
interface SemesterDao {

    /** 观察指定课表的全部学期，锚点日期新的在前。 */
    @Query("SELECT * FROM semester WHERE timetableId = :timetableId ORDER BY startDate DESC")
    fun observeByTimetable(timetableId: Long): Flow<List<SemesterEntity>>

    /** 观察指定课表的当前学期（isCurrent=1 首行）；未设置时发射 null。 */
    @Query("SELECT * FROM semester WHERE timetableId = :timetableId AND isCurrent = 1 LIMIT 1")
    fun observeCurrent(timetableId: Long): Flow<SemesterEntity?>

    /** 读取指定课表的当前学期（isCurrent=1 首行）；未设置返回 null（导出/导入用）。 */
    @Query("SELECT * FROM semester WHERE timetableId = :timetableId AND isCurrent = 1 LIMIT 1")
    suspend fun getCurrent(timetableId: Long): SemesterEntity?

    /** 读取指定课表的全部学期，锚点日期新的在前（测试/统计用）。 */
    @Query("SELECT * FROM semester WHERE timetableId = :timetableId ORDER BY startDate DESC")
    suspend fun getAllByTimetable(timetableId: Long): List<SemesterEntity>

    /** 观察全部课表的学期（自动选表需要跨课表判断教学周）。 */
    @Query("SELECT * FROM semester ORDER BY timetableId ASC, startDate DESC")
    fun observeAll(): Flow<List<SemesterEntity>>

    /** 读取全部课表的学期（自动选表一次性解析用）。 */
    @Query("SELECT * FROM semester ORDER BY timetableId ASC, startDate DESC")
    suspend fun getAll(): List<SemesterEntity>

    /**
     * 新增或更新学期（按主键存在与否决定 insert/update）。
     *
     * @return 新插入行的 rowId；走更新分支时 Room 返回 -1，调用方应回退使用 entity.id。
     */
    @Upsert
    suspend fun upsert(entity: SemesterEntity): Long

    /** 删除指定课表的全部学期（学期重建/同步替换时先清后写）。 */
    @Query("DELETE FROM semester WHERE timetableId = :timetableId")
    suspend fun deleteByTimetable(timetableId: Long)

    /** 按 id 删除单个学期（学期管理页删除）。 */
    @Query("DELETE FROM semester WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 取消指定课表内全部学期的当前标记（设新当前学期前调用）。 */
    @Query("UPDATE semester SET isCurrent = 0 WHERE timetableId = :timetableId")
    suspend fun clearCurrent(timetableId: Long)
}
