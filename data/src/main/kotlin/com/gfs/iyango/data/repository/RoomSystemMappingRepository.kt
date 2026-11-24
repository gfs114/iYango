package com.gfs.iyango.data.repository

import com.gfs.iyango.core.domain.SystemMappingPort
import com.gfs.iyango.core.domain.WidgetConfigPort
import com.gfs.iyango.core.model.SystemMapping
import com.gfs.iyango.core.model.SystemMappingKind
import com.gfs.iyango.core.model.WidgetConfig
import com.gfs.iyango.data.db.IYangoDatabase
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** [SystemMappingPort] 的 Room 实现。 */
@Singleton
class RoomSystemMappingRepository @Inject constructor(
    private val db: IYangoDatabase,
) : SystemMappingPort {

    override suspend fun all(kind: SystemMappingKind, ownerId: String): List<SystemMapping> =
        db.systemMappingDao().getByKind(kind, ownerId).map { it.toModel() }

    override suspend fun upsert(mapping: SystemMapping): Long {
        val rowId = db.systemMappingDao().insert(mapping.toEntity())
        return if (rowId > 0) rowId else mapping.id
    }

    override suspend fun delete(kind: SystemMappingKind, occurrenceId: String, ownerId: String) {
        db.systemMappingDao().delete(kind, occurrenceId, ownerId)
    }

    override suspend fun deleteKind(kind: SystemMappingKind, ownerId: String) {
        db.systemMappingDao().deleteByKind(kind, ownerId)
    }
}

/** [WidgetConfigPort] 的 Room 实现。 */
@Singleton
class RoomWidgetConfigRepository @Inject constructor(
    private val db: IYangoDatabase,
) : WidgetConfigPort {

    override fun observeAll() = db.widgetConfigDao().observeAll().map { rows -> rows.map { it.toModel().normalized() } }

    override suspend fun all(): List<WidgetConfig> =
        db.widgetConfigDao().getAll().map { it.toModel().normalized() }

    override suspend fun get(widgetId: Int): WidgetConfig? =
        db.widgetConfigDao().get(widgetId)?.toModel()?.normalized()

    override suspend fun save(config: WidgetConfig) {
        db.widgetConfigDao().upsert(config.normalized().toEntity())
    }

    override suspend fun delete(widgetId: Int) {
        db.widgetConfigDao().delete(widgetId)
    }
}
