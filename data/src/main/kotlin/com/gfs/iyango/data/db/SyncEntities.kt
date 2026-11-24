package com.gfs.iyango.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.gfs.iyango.core.model.SyncBatchResult
import com.gfs.iyango.core.model.SyncChangeType

/** 一次教务同步批次（E02）。 */
@Entity(
    tableName = "sync_batch",
    indices = [
        Index(value = ["connectionId", "timetableId"]),
        Index(value = ["semester"]),
    ],
)
data class SyncBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val connectionId: String,
    val timetableId: Long,
    val semester: String,
    val version: Int,
    val startedAt: Long,
    val finishedAt: Long,
    val result: SyncBatchResult,
    val changeCount: Int,
    val firstBaseline: Boolean,
    val message: String,
    val read: Boolean,
)

/** 字段级同步变化；批次删除时级联清理。 */
@Entity(
    tableName = "sync_change",
    foreignKeys = [
        ForeignKey(
            entity = SyncBatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["batchId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["batchId"])],
)
data class SyncChangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batchId: Long,
    val stableKey: String,
    val courseName: String,
    val changeType: SyncChangeType,
    val field: String,
    val beforeValue: String,
    val afterValue: String,
)

/** 上次完整学校快照（规范化摘要，用于差异比较）。 */
@Entity(
    tableName = "school_snapshot",
    indices = [
        Index(value = ["connectionId", "timetableId", "semester"], unique = true),
    ],
)
data class SchoolSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val connectionId: String,
    val timetableId: Long,
    val semester: String,
    val payloadJson: String,
    val digest: String,
    val savedAt: Long,
)
