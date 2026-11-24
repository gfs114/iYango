package com.gfs.iyango.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.gfs.iyango.core.model.AdjustmentType

/** 以原始发生锚点索引的临时调整（F08）。 */
@Entity(
    tableName = "occurrence_adjustment",
    foreignKeys = [
        ForeignKey(
            entity = TimetableEntity::class,
            parentColumns = ["id"],
            childColumns = ["timetableId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["timetableId"]),
        Index(value = ["operationGroupId"]),
        Index(value = ["timetableId", "sourceDate"]),
    ],
)
data class OccurrenceAdjustmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timetableId: Long,
    val operationGroupId: String,
    val type: AdjustmentType,
    val sourceCourseId: Long,
    val sourceDate: String,
    val targetDate: String,
    val targetDayOfWeek: Int,
    val targetStartSection: Int,
    val targetEndSection: Int,
    val targetStartTime: String,
    val targetEndTime: String,
    val targetClassroom: String,
    val note: String,
    val createdAt: Long,
)

/** 临时整天课表：该日期基础安排整体被来源课表该日安排替换。 */
@Entity(
    tableName = "temporary_day",
    foreignKeys = [
        ForeignKey(
            entity = TimetableEntity::class,
            parentColumns = ["id"],
            childColumns = ["timetableId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["timetableId", "date"], unique = true),
        Index(value = ["operationGroupId"]),
    ],
)
data class TemporaryDayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timetableId: Long,
    val operationGroupId: String,
    val date: String,
    val sourceTimetableId: Long,
    val title: String,
    val createdAt: Long,
)
