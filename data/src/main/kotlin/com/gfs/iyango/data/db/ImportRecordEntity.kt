package com.gfs.iyango.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 导入历史记录的持久化行，字段与 `core:model` 的 [com.gfs.iyango.core.model.ImportRecord] 一一对应。
 */
@Entity(
    tableName = "import_record",
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
data class ImportRecordEntity(
    /** 自增主键；0 表示待生成。 */
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 导入目标课表 id；外键级联删除。 */
    val timetableId: Long,
    /** 导入来源（如 "YGU"）。 */
    val source: String,
    /** 导入时间（ISO 文本）。 */
    val importTime: String,
    /** 导入时携带的学期描述文本。 */
    val semester: String,
)
