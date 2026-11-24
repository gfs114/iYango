package com.gfs.iyango.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 作息时间表一行的持久化行，字段与 `core:model` 的 [com.gfs.iyango.core.model.TimeSlot] 一一对应。
 *
 * 语义约束（与模型 KDoc 对齐）：
 * - [startTime]/[endTime] 为 `HH:mm`；[kind] = 2（休息/占位）允许两者同时为空；
 * - [section] 为正整数，同一课表内用于节次定位；
 * - [sortOrder] 为展示排序权重，0 表示回退到 [section]（查询统一按 [section] 升序）。
 */
@Entity(
    tableName = "time_slot",
    foreignKeys = [
        ForeignKey(
            entity = TimetableEntity::class,
            parentColumns = ["id"],
            childColumns = ["timetableId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["timetableId"])],
)
data class TimeSlotEntity(
    /** 自增主键；0 表示待生成。 */
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 所属课表 id；外键级联删除。 */
    val timetableId: Long,
    /** 节次号（≥1）。 */
    val section: Int,
    /** 该节开始时间（HH:mm）。 */
    val startTime: String,
    /** 该节结束时间（HH:mm）。 */
    val endTime: String,
    /** 节次名称。 */
    val name: String,
    /** 节点类型：0=上课节次，2=休息/占位。 */
    val kind: Int,
    /** 是否在默认视图中隐藏。 */
    val hidden: Boolean,
    /** 该节的默认占位课程名（空=无）。 */
    val defaultCourseName: String,
    /** 展示排序权重；0 回退到 [section]。 */
    val sortOrder: Double,
)
