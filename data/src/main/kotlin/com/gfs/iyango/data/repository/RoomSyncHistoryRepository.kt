package com.gfs.iyango.data.repository

import com.gfs.iyango.core.domain.SyncHistoryPort
import com.gfs.iyango.core.model.SyncBatch
import com.gfs.iyango.core.model.SyncChange
import com.gfs.iyango.data.db.IYangoDatabase
import com.gfs.iyango.data.db.SyncBatchEntity
import com.gfs.iyango.data.db.SyncChangeEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** [SyncHistoryPort] 的 Room 实现（E02）。 */
@Singleton
class RoomSyncHistoryRepository @Inject constructor(
    private val db: IYangoDatabase,
) : SyncHistoryPort {

    override fun observeBatches(connectionId: String): Flow<List<SyncBatch>> =
        db.syncBatchDao().observeByConnection(connectionId)
            .map { rows -> rows.map { it.toModel() } }

    override fun observeChanges(batchId: Long): Flow<List<SyncChange>> =
        db.syncChangeDao().observeByBatch(batchId)
            .map { rows -> rows.map { it.toModel() } }

    override suspend fun markRead(batchId: Long) {
        db.syncBatchDao().markRead(batchId)
    }

    override suspend fun markAllRead(connectionId: String) {
        db.syncBatchDao().markAllRead(connectionId)
    }

    override suspend fun clear(connectionId: String) {
        db.syncBatchDao().deleteByConnection(connectionId)
    }

    private fun SyncBatchEntity.toModel(): SyncBatch = SyncBatch(
        id = id,
        connectionId = connectionId,
        timetableId = timetableId,
        semester = semester,
        version = version,
        startedAt = startedAt,
        finishedAt = finishedAt,
        result = result,
        changeCount = changeCount,
        firstBaseline = firstBaseline,
        message = message,
        read = read,
    )

    private fun SyncChangeEntity.toModel(): SyncChange = SyncChange(
        id = id,
        batchId = batchId,
        stableKey = stableKey,
        courseName = courseName,
        changeType = changeType,
        field = field,
        beforeValue = beforeValue,
        afterValue = afterValue,
    )
}
