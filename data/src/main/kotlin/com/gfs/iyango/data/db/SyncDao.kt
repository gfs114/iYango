package com.gfs.iyango.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 同步批次 DAO。 */
@Dao
interface SyncBatchDao {

    @Query("SELECT * FROM sync_batch ORDER BY finishedAt DESC, id DESC")
    fun observeAll(): Flow<List<SyncBatchEntity>>

    @Query("SELECT * FROM sync_batch WHERE connectionId = :connectionId ORDER BY finishedAt DESC, id DESC")
    fun observeByConnection(connectionId: String): Flow<List<SyncBatchEntity>>

    @Query("SELECT COUNT(*) FROM sync_batch WHERE read = 0")
    fun observeUnreadCount(): Flow<Int>

    /** 指定课表/学期的最近批次版本；没有历史时返回 null。 */
    @Query(
        "SELECT MAX(version) FROM sync_batch WHERE connectionId = :connectionId " +
            "AND timetableId = :timetableId AND semester = :semester",
    )
    suspend fun maxVersion(connectionId: String, timetableId: Long, semester: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SyncBatchEntity): Long

    @Query("UPDATE sync_batch SET read = 1 WHERE id = :batchId")
    suspend fun markRead(batchId: Long)

    @Query("UPDATE sync_batch SET read = 1 WHERE connectionId = :connectionId")
    suspend fun markAllRead(connectionId: String)

    /** 每个账号只保留最近 [keep] 条批次；变化明细随外键级联清理。 */
    @Query(
        "DELETE FROM sync_batch WHERE connectionId = :connectionId AND id NOT IN (" +
            "SELECT id FROM sync_batch WHERE connectionId = :connectionId " +
            "ORDER BY finishedAt DESC, id DESC LIMIT :keep)",
    )
    suspend fun trim(connectionId: String, keep: Int)

    @Query("DELETE FROM sync_batch WHERE connectionId = :connectionId")
    suspend fun deleteByConnection(connectionId: String)
}

/** 同步变化 DAO。 */
@Dao
interface SyncChangeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<SyncChangeEntity>)

    @Query("SELECT * FROM sync_change WHERE batchId = :batchId ORDER BY id ASC")
    suspend fun getByBatch(batchId: Long): List<SyncChangeEntity>

    @Query("SELECT * FROM sync_change WHERE batchId = :batchId ORDER BY id ASC")
    fun observeByBatch(batchId: Long): Flow<List<SyncChangeEntity>>

    @Query("DELETE FROM sync_change WHERE batchId = :batchId")
    suspend fun deleteByBatch(batchId: Long)
}

/** 学校快照 DAO。 */
@Dao
interface SchoolSnapshotDao {

    @Query(
        "SELECT * FROM school_snapshot WHERE connectionId = :connectionId " +
            "AND timetableId = :timetableId AND semester = :semester LIMIT 1",
    )
    suspend fun get(connectionId: String, timetableId: Long, semester: String): SchoolSnapshotEntity?

    /**
     * 忽略连接 id 读取目标课表的最近快照。
     *
     * 同账号重登后连接 id 可能变化（或从历史连接接管课表），变化比较仍需沿用上次快照。
     */
    @Query(
        "SELECT * FROM school_snapshot WHERE timetableId = :timetableId " +
            "AND semester = :semester ORDER BY id DESC LIMIT 1",
    )
    suspend fun getByTarget(timetableId: Long, semester: String): SchoolSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SchoolSnapshotEntity): Long

    @Query("DELETE FROM school_snapshot WHERE connectionId = :connectionId AND timetableId = :timetableId")
    suspend fun delete(connectionId: String, timetableId: Long)

    @Query("DELETE FROM school_snapshot WHERE connectionId = :connectionId")
    suspend fun deleteByConnection(connectionId: String)
}
