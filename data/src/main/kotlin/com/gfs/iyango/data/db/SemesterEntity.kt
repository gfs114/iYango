package com.gfs.iyango.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * 学期定义的持久化行，字段与 `core:model` 的 [com.gfs.iyango.core.model.Semester] 一一对应。
 *
 * [startDate] 存储为 ISO `yyyy-MM-dd` 文本（[Converters] 负责转换），
 * 是教学周计算的锚点：`floorDiv(date - startDate, 7) + 1`。
 */
@Entity(
    tableName = "semester",
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
data class SemesterEntity(
    /** 自增主键；0 表示待生成。 */
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 所属课表 id；外键级联删除。 */
    val timetableId: Long,
    val name: String,
    /** 学期第 1 周的锚点日期（ISO yyyy-MM-dd 文本）。 */
    val startDate: LocalDate,
    /** 学期总周数。 */
    val totalWeeks: Int,
    /** 是否为当前学期（同一课表内建议至多一行为 true）。 */
    val isCurrent: Boolean,
)
