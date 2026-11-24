package com.gfs.iyango.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 导入历史记录 DAO。记录只增不改：每次导入 append 一行。
 */
@Dao
interface ImportRecordDao {

    /** 观察指定课表的导入历史，最新导入在前。 */
    @Query("SELECT * FROM import_record WHERE timetableId = :timetableId ORDER BY importTime DESC, id DESC")
    fun observeByTimetable(timetableId: Long): Flow<List<ImportRecordEntity>>

    /** 读取指定课表的导入历史，最新导入在前（重复文件检测/展示用）。 */
    @Query("SELECT * FROM import_record WHERE timetableId = :timetableId ORDER BY importTime DESC, id DESC")
    suspend fun getAllByTimetable(timetableId: Long): List<ImportRecordEntity>

    /** 按来源摘要读取首条导入记录；不存在返回 null（重复文件检测用，source 存文件摘要）。 */
    @Query("SELECT * FROM import_record WHERE source = :source LIMIT 1")
    suspend fun getBySource(source: String): ImportRecordEntity?

    /** 追加一条导入记录，返回持久化后的行 id。 */
    @Insert
    suspend fun insert(entity: ImportRecordEntity): Long
}
