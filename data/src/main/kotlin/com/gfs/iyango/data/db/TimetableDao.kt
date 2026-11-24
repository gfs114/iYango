package com.gfs.iyango.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 课表 DAO。
 *
 * "当前课表"不变式（全表至多一行 isCurrent=1）由 [setCurrent] 的事务语义保证；
 * 写路径经由 `com.gfs.iyango.data.repository.RoomTimetableRepository`。
 */
@Dao
interface TimetableDao {

    /** 按 id 升序观察全部课表（含非当前），id 顺序即插入顺序，列表不跳动。 */
    @Query("SELECT * FROM timetable ORDER BY id ASC")
    fun observeAll(): Flow<List<TimetableEntity>>

    @Query("SELECT * FROM timetable ORDER BY id ASC")
    suspend fun getAll(): List<TimetableEntity>

    /** 读取当前课表（isCurrent=1 首行）；尚无当前课表时返回 null（同步默认学期用）。 */
    @Query("SELECT * FROM timetable WHERE isCurrent = 1 LIMIT 1")
    suspend fun getCurrent(): TimetableEntity?

    /** 观察当前课表（isCurrent=1 的首行）；尚无当前课表时发射 null。 */
    @Query("SELECT * FROM timetable WHERE isCurrent = 1 LIMIT 1")
    fun observeCurrent(): Flow<TimetableEntity?>

    /** 按主键读取单个课表；不存在返回 null（导出/替换导入用）。 */
    @Query("SELECT * FROM timetable WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): TimetableEntity?

    /** 按主键观察单个课表；不存在时发射 null。 */
    @Query("SELECT * FROM timetable WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<TimetableEntity?>

    /** 全表行数（演示种子幂等判断用）。 */
    @Query("SELECT COUNT(*) FROM timetable")
    suspend fun countAll(): Int

    /** 当前课表行数（新建课表是否需要自动成为当前）。 */
    @Query("SELECT COUNT(*) FROM timetable WHERE isCurrent = 1")
    suspend fun countCurrent(): Int

    /** 按教务绑定读取课表：同一连接、同一学校学期至多一张。 */
    @Query(
        "SELECT * FROM timetable WHERE source = 'YGU' AND connectionId = :connectionId " +
            "AND schoolTerm = :schoolTerm ORDER BY id ASC LIMIT 1",
    )
    suspend fun getBinding(connectionId: String, schoolTerm: String): TimetableEntity?

    /** 读取装有指定学校学期的全部教务课表（同账号重登时复用，避免重复建表）。 */
    @Query(
        "SELECT * FROM timetable WHERE source = 'YGU' AND schoolTerm = :schoolTerm " +
            "AND connectionId <> '' ORDER BY isCurrent DESC, id ASC",
    )
    suspend fun getYguByTerm(schoolTerm: String): List<TimetableEntity>

    /** 该连接最近一次同步的教务课表（后台自动刷新取学期用）。 */
    @Query(
        "SELECT * FROM timetable WHERE source = 'YGU' AND connectionId = :connectionId " +
            "AND schoolTerm <> '' ORDER BY isCurrent DESC, lastSyncAt DESC, id DESC LIMIT 1",
    )
    suspend fun getLatestYguBinding(connectionId: String): TimetableEntity?

    /**
     * 新增或更新课表（按主键存在与否决定 insert/update）。
     *
     * @return 新插入行的 rowId；走更新分支时 Room 返回 -1，调用方应回退使用 entity.id。
     */
    @Upsert
    suspend fun upsert(entity: TimetableEntity): Long

    /**
     * 把 [id] 设为当前课表：先清零全部 isCurrent，再置位目标行。
     *
     * @Transaction 保证两步原子执行，不会出现"0 个或多个当前课表"的中间态。
     */
    @Transaction
    suspend fun setCurrent(id: Long) {
        clearCurrentFlags()
        markCurrent(id)
    }

    /** 清零全部当前标记（供 [setCurrent] 事务内调用）。 */
    @Query("UPDATE timetable SET isCurrent = 0")
    suspend fun clearCurrentFlags()

    /** 置位指定行为当前（供 [setCurrent] 事务内调用）。 */
    @Query("UPDATE timetable SET isCurrent = 1 WHERE id = :id")
    suspend fun markCurrent(id: Long)

    /** 删除课表；其学期/作息/课程/导入记录由外键 ON DELETE CASCADE 级联清理。 */
    @Query("DELETE FROM timetable WHERE id = :id")
    suspend fun deleteById(id: Long)
}
