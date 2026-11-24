package com.gfs.iyango.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gfs.iyango.core.model.SystemMappingKind
import kotlinx.coroutines.flow.Flow

/** 系统记录映射 DAO。 */
@Dao
interface SystemMappingDao {

    @Query("SELECT * FROM system_mapping WHERE kind = :kind AND ownerId = :ownerId")
    suspend fun getByKind(kind: SystemMappingKind, ownerId: String): List<SystemMappingEntity>

    @Query("SELECT * FROM system_mapping WHERE ownerId = :ownerId")
    suspend fun getAll(ownerId: String): List<SystemMappingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SystemMappingEntity): Long

    @Query("DELETE FROM system_mapping WHERE kind = :kind AND occurrenceId = :occurrenceId AND ownerId = :ownerId")
    suspend fun delete(kind: SystemMappingKind, occurrenceId: String, ownerId: String)

    @Query("DELETE FROM system_mapping WHERE kind = :kind AND ownerId = :ownerId")
    suspend fun deleteByKind(kind: SystemMappingKind, ownerId: String)
}

/** 桌面组件配置 DAO。 */
@Dao
interface WidgetConfigDao {

    @Query("SELECT * FROM widget_config ORDER BY widgetId ASC")
    fun observeAll(): Flow<List<WidgetConfigEntity>>

    @Query("SELECT * FROM widget_config ORDER BY widgetId ASC")
    suspend fun getAll(): List<WidgetConfigEntity>

    @Query("SELECT * FROM widget_config WHERE widgetId = :widgetId LIMIT 1")
    suspend fun get(widgetId: Int): WidgetConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WidgetConfigEntity)

    @Query("DELETE FROM widget_config WHERE widgetId = :widgetId")
    suspend fun delete(widgetId: Int)
}

/** 教室缓存 DAO。 */
@Dao
interface ClassroomCacheDao {

    @Query("SELECT * FROM classroom_cache WHERE cacheKey = :cacheKey LIMIT 1")
    suspend fun get(cacheKey: String): ClassroomCacheEntity?

    @Query("SELECT * FROM classroom_cache WHERE connectionId = :connectionId AND termValue = :termValue AND kind = :kind")
    suspend fun getByKind(connectionId: String, termValue: String, kind: String): List<ClassroomCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ClassroomCacheEntity)

    @Query("DELETE FROM classroom_cache WHERE connectionId = :connectionId")
    suspend fun deleteByConnection(connectionId: String)
}
