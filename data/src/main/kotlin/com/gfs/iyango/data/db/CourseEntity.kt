package com.gfs.iyango.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.gfs.iyango.core.model.WeekType

/**
 * 课程定义的持久化行，字段与 `core:model` 的 [com.gfs.iyango.core.model.Course] 对齐，
 * 但**不持久化** `timeUnconfirmed`：该标记只用于读取结果（时间待定展示），无对应列，
 * 由 [com.gfs.iyango.data.repository.Mappers] 映射回模型时恒为 false。
 *
 * 其余约定：
 * - [weekType] 由 Room 内建枚举↔String 转换，存储 "EVERY"/"ODD"/"EVEN"；
 * - [selectedWeeks] 由 [Converters] 以逗号文本存储（如 "2,4,8"）；
 * - [overrideOfId] > 0 表示单次调整记录（对另一课程在 [overrideWeek] 周的临时改动），
 *   通过 [overrideOfId] 引用同表内课程，不设外键（参考实现允许指向任意行）；
 * - **单次调整唯一约束（P2-01，v2 起生效）**：唯一索引 `index_course_override` 建在
 *   `(timetableId, overrideOfId, overrideWeek)` 上。由于 SQLite 唯一索引把 NULL 视为
 *   互不相同，普通课程行（overrideOfId=0）的这两列以 **NULL** 落库（见
 *   [com.gfs.iyango.data.repository.Mappers]），从而获得"部分唯一"语义——普通课程可
 *   任意多条并存，同一 (overrideOfId, overrideWeek) 至多一条。应用层双保险见
 *   `CourseValidator.validateOverrideUniqueness`。
 */
@Entity(
    tableName = "course",
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
        Index(
            value = ["timetableId", "overrideOfId", "overrideWeek"],
            unique = true,
            name = "index_course_override",
        ),
    ],
)
data class CourseEntity(
    /** 自增主键；0 表示待生成。 */
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 所属课表 id；外键级联删除。 */
    val timetableId: Long,
    val name: String,
    val teacher: String,
    val classroom: String,
    /** 名称缩写。 */
    val abbreviation: String,
    /** 备注，最多 300 字。 */
    val remark: String,
    /** 星期几，1=周一 … 7=周日。 */
    val dayOfWeek: Int,
    /** 起始节次（含）。 */
    val startSection: Int,
    /** 结束节次（含）。 */
    val endSection: Int,
    /** 展示投影前的原始起始节次（0=未投影）。 */
    val sourceStartSection: Int,
    /** 展示投影前的原始结束节次（0=未投影）。 */
    val sourceEndSection: Int,
    /** 起始教学周（含）。 */
    val startWeek: Int,
    /** 结束教学周（含）。 */
    val endWeek: Int,
    /** 周次类型：每周/单周/双周（枚举以名称字符串存储）。 */
    val weekType: WeekType,
    /** 额外指定的上课周列表；空表示不额外筛选（逗号分隔存储）。 */
    val selectedWeeks: List<Int>,
    /** 卡片强调色（#RRGGBB）。 */
    val color: String,
    /** 学分，非负。 */
    val credit: Double,
    /** 是否户外课程。 */
    val isOutdoor: Boolean,
    /** 关闭后的课程不参与任何周的发生计算。 */
    val enabled: Boolean,
    /** 上课时间模式：0=按节次+时间表，1=自定义钟点，2=单次指定日期。 */
    val timeMode: Int,
    /** 自定义开始时间（HH:mm）。 */
    val startTime: String,
    /** 自定义结束时间（HH:mm）。 */
    val endTime: String,
    /** 单次指定日期（yyyy-MM-dd），timeMode=2 时有效。 */
    val date: String,
    /** 轮换周期长度（≥1）。 */
    val cycleLength: Int,
    /** 轮换相位；0=不启用轮换，启用时 1..cycleLength。 */
    val cycleWeek: Int,
    /**
     * 被单次调整的原课程 id；0=本条不是调整记录。
     *
     * **可空存储**：0 语义由映射层以 NULL 落库（见类 KDoc 的唯一索引说明），
     * 映射层保证模型侧恒为非空 Long（0 表示非调整记录）。
     */
    val overrideOfId: Long?,
    /**
     * 单次调整生效的教学周；overrideOfId > 0 时必须 ≥ 1。
     *
     * **可空存储**：与 [overrideOfId] 同步以 NULL 表示"非调整记录"。
     */
    val overrideWeek: Int?,
    /** 分组键；非空且相邻字段等值时允许合并相邻节次。 */
    val groupKey: String,
    /** 学校稳定来源键；空=本机课程。见 [com.gfs.iyango.core.model.Course.sourceKey]。 */
    val sourceKey: String = "",
)
