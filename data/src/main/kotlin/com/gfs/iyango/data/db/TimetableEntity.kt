package com.gfs.iyango.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 课表定义的持久化行，字段与 `core:model` 的 [com.gfs.iyango.core.model.Timetable] 一一对应。
 *
 * 语义约束（与模型 KDoc 对齐）：
 * - [enabledDays] 取值周一=1 … 周日=7，由 [Converters] 以逗号文本存储；
 * - [cycleWeek] = 0 表示不启用轮换；启用时取值 1..[cycleLength]；
 * - "当前课表"不变式由仓储层维护：全表至多一行 [isCurrent] = true
 *   （[TimetableDao.setCurrent] 在事务内先清零再置位）。
 */
@Entity(
    tableName = "timetable",
    indices = [Index(value = ["isCurrent"])],
)
data class TimetableEntity(
    /** 自增主键；0 表示待生成。 */
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 来源："LOCAL"（本地手填）/"YGU"（教务同步）。 */
    val source: String,
    /** 学年学期字符串（同步用）。 */
    val schoolTerm: String,
    /** 同步连接 id。 */
    val connectionId: String,
    /** 是否为当前课表；全表至多一行为 true。 */
    val isCurrent: Boolean,
    /** 最近同步时间（ISO 文本），本地课表为空。 */
    val lastSyncAt: String,
    /** 分层分组名（分层自动切换用，空=不参与）。 */
    val groupName: String,
    /** 是否启用分层自动切换。 */
    val autoEnabled: Boolean,
    /** 生效星期集合（1..7），逗号分隔存储。 */
    val enabledDays: List<Int>,
    /** 轮换周期长度（≥1）。 */
    val cycleLength: Int,
    /** 轮换相位；0=不启用轮换。 */
    val cycleWeek: Int,
)
