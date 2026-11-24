package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.SchoolCourseData
import com.gfs.iyango.core.model.TimeSlot

/**
 * 预览中的一条上课安排。
 *
 * [rowKey] 在单次预览内稳定且唯一，仅用于勾选状态；真正写入数据库的稳定身份是
 * [SchoolCourseData.sourceKey]。
 */
data class SchoolSyncArrangement(
    val rowKey: String = "",
    val course: SchoolCourseData = SchoolCourseData(),
)

/** 预览中的课程组：同课程、同教师、同教室与周次的多条安排聚在一起展示。 */
data class SchoolSyncGroup(
    val key: String = "",
    val name: String = "",
    val teacher: String = "",
    val classroom: String = "",
    val arrangements: List<SchoolSyncArrangement> = emptyList(),
)

/**
 * 本次同步的目标课表。
 *
 * [timetableId] = 0 表示将新建课表；[adopted] 为 true 表示复用了同账号此前
 * 已同步、但连接 id 不同的同学期课表（重登后不会新建重复课表）。
 */
data class SchoolSyncTarget(
    val timetableId: Long = 0,
    val name: String = "",
    val adopted: Boolean = false,
    val isCurrent: Boolean = false,
    val existingCourses: Int = 0,
)

/**
 * 一次“获取 → 预览”的结果（尚未写库）。
 *
 * [baseSignature] 冻结了目标课表 + 学期 + 作息 + 课程 + 上次学校快照的摘要；
 * [commit] 会在写入事务内重新计算并比较，避免抓取前的快照覆盖预览期间的本地编辑。
 *
 * [officialTimes] 为 true 表示学期精确命中学校官方作息，[timeSlots] 为 11 节官方网格；
 * 为 false 时 [timeSlots] 只是本机默认作息，提交时仅在目标尚无作息时写入。
 */
data class SchoolSyncPreview(
    val connectionId: String = "",
    val termValue: String = "",
    val termLabel: String = "",
    val semesterName: String = "",
    val target: SchoolSyncTarget = SchoolSyncTarget(),
    val courses: List<SchoolSyncArrangement> = emptyList(),
    val groups: List<SchoolSyncGroup> = emptyList(),
    val warnings: List<String> = emptyList(),
    val defaultStartDate: String = "",
    val defaultTotalWeeks: Int = 20,
    val existingStartDate: String = "",
    val baseSignature: String = "",
    val officialTimes: Boolean = false,
    val timeUnconfirmed: Boolean = false,
    val timeSlots: List<TimeSlot> = emptyList(),
    val fetchedAt: Long = 0,
)

/** [SchoolTimetableSyncPort.prepare] 的结果。 */
sealed interface SchoolSyncPrepareResult {
    data class Ready(val preview: SchoolSyncPreview) : SchoolSyncPrepareResult

    /** 登录态失效；调用方应回到教务账号页重新登录。 */
    data class SessionExpired(val message: String) : SchoolSyncPrepareResult

    data class Failed(val message: String, val retryable: Boolean = true) : SchoolSyncPrepareResult
}

/**
 * 一次同步提交的摘要（E02）。
 *
 * [firstBaseline] 为 true 时不把整张课表包装成大量新增，只提示首次同步条数。
 * [disabled] 是因学校安排消失但仍被单次调整引用的基础课数量（保留并停用）。
 */
data class SchoolSyncCommitSummary(
    val timetableId: Long = 0,
    val timetableName: String = "",
    val created: Boolean = false,
    val adopted: Boolean = false,
    val firstBaseline: Boolean = false,
    val added: Int = 0,
    val modified: Int = 0,
    val removed: Int = 0,
    val disabled: Int = 0,
    val localKept: Int = 0,
    val warnings: List<String> = emptyList(),
    val batchId: Long = 0,
    val syncedAt: String = "",
)

/** [SchoolTimetableSyncPort.commit] 的结果。 */
sealed interface SchoolSyncCommitResult {
    data class Success(val summary: SchoolSyncCommitSummary) : SchoolSyncCommitResult

    /** 预览后目标课表或本地数据发生变化；不得覆盖，需重新获取。 */
    data class Conflict(val message: String) : SchoolSyncCommitResult

    data class SessionExpired(val message: String) : SchoolSyncCommitResult

    data class Failed(val message: String, val retryable: Boolean = true) : SchoolSyncCommitResult
}

/**
 * 教务课表同步端口（F02 / E02）。
 *
 * 流程：`prepare` 只读网络与本地状态并生成预览；`commit` 在单个 Room 事务内
 * 重新校验 [SchoolSyncPreview.baseSignature] 后写入课程、学期、作息、同步批次与快照。
 * 网络失败、解析不完整或预览后本地有写入时，旧课表保持不变。
 */
interface SchoolTimetableSyncPort {

    /** 读取指定学期的完整课表并生成预览；不写库。 */
    suspend fun prepare(connectionId: String, termValue: String): SchoolSyncPrepareResult

    /**
     * 写入预览结果。
     *
     * @param startDate 用户确认的开学日期（yyyy-MM-dd）。
     * @param totalWeeks 用户确认的总周数（1..30）。
     * @param selectedRowKeys 要写入的安排行键；null=全部。未勾选的旧学校安排会被移除，
     *   但被单次调整引用的基础课保留并停用。
     */
    suspend fun commit(
        preview: SchoolSyncPreview,
        startDate: String,
        totalWeeks: Int,
        selectedRowKeys: Set<String>? = null,
    ): SchoolSyncCommitResult

    /**
     * 后台快速同步（仅刷新已有绑定/同账号同学期课表），使用现有学期日期与总周数。
     * 尚未建立绑定时由调用方传入学校入口页当前学期，实现方直接新建课表并同步。
     */
    suspend fun quickSync(connectionId: String, termValue: String): SchoolSyncCommitResult
}
