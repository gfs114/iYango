package com.gfs.iyango.core.model

/** 一次同步批次的结果。 */
enum class SyncBatchResult {
    SUCCESS,
    PARTIAL,
    FAILED,
}

/**
 * 一次教务同步批次。
 *
 * [firstBaseline] 为 true 时表示首次建立基线：只显示“首次同步 N 条安排”，
 * 不把整张课表包装成大量新增。
 */
data class SyncBatch(
    val id: Long = 0,
    val connectionId: String = "",
    val timetableId: Long = 0,
    val semester: String = "",
    val version: Int = 1,
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    val result: SyncBatchResult = SyncBatchResult.SUCCESS,
    val changeCount: Int = 0,
    val firstBaseline: Boolean = false,
    val message: String = "",
    val read: Boolean = false,
)

/** 变化类型。 */
enum class SyncChangeType {
    ADDED,
    REMOVED,
    MODIFIED,
    AMBIGUOUS,
}

/**
 * 字段级同步变化。
 *
 * [stableKey] 优先来自学校稳定课程/安排 ID；[field] 为业务字段名（classroom/time 等），
 * [beforeValue]/[afterValue] 保留展示文本。
 */
data class SyncChange(
    val id: Long = 0,
    val batchId: Long = 0,
    val stableKey: String = "",
    val courseName: String = "",
    val changeType: SyncChangeType = SyncChangeType.MODIFIED,
    val field: String = "",
    val beforeValue: String = "",
    val afterValue: String = "",
)

/** 上次完整学校快照的规范化摘要（用于变化比较，不参与学期课表解析）。 */
data class SchoolSnapshot(
    val id: Long = 0,
    val connectionId: String = "",
    val timetableId: Long = 0,
    val semester: String = "",
    val payloadJson: String = "",
    val digest: String = "",
    val savedAt: Long = 0,
)
