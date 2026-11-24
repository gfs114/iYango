package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.OccurrenceAdjustment
import com.gfs.iyango.core.model.TemporaryDay
import kotlinx.coroutines.flow.Flow

/** 临时调整仓储端口（F08）。 */
interface AdjustmentRepositoryPort {

    fun observeAdjustments(timetableId: Long): Flow<List<OccurrenceAdjustment>>

    fun observeTemporaryDays(timetableId: Long): Flow<List<TemporaryDay>>

    /** Compare-and-replace one source anchor atomically; legacy implementations safely refuse. */
    suspend fun replaceOccurrence(expected: OccurrenceAdjustmentExpected, replacement: OccurrenceAdjustment): AdjustmentReplaceResult = AdjustmentReplaceResult.Stale

    /** 新增一条调整，返回持久化 id。 */
    suspend fun insert(adjustment: OccurrenceAdjustment): Long

    suspend fun insertTemporaryDay(day: TemporaryDay): Long

    /** 恢复一个操作组（移动/取消/整天），只删除本次操作产生的记录。 */
    suspend fun deleteGroup(operationGroupId: String)

    /** Restore exactly one anchor, never all records sharing its operation group. */
    suspend fun deleteAnchor(timetableId: Long, courseId: Long, sourceDate: String) { error("Precise restoration unavailable") }

    /** Restore exactly one owner/date, independent of operation groups. */
    suspend fun deleteTemporaryDay(timetableId: Long, date: String) { error("Precise restoration unavailable") }

    /** 删除某课表下全部临时调整（重置设置不触碰此数据）。 */
    suspend fun deleteAll(timetableId: Long)
}

/** Captured from the detail's effective day, not from the mutable displayed MOVE copy. */
data class OccurrenceAdjustmentExpected(
    val baseCourse: com.gfs.iyango.core.model.Course,
    val occurrence: CourseOccurrence,
    val adjustments: List<OccurrenceAdjustment>,
    val targetDay: EffectiveScheduleDay? = null,
)
sealed interface AdjustmentReplaceResult {
    data object Saved : AdjustmentReplaceResult
    data object Stale : AdjustmentReplaceResult
    data class Rejected(val message: String) : AdjustmentReplaceResult
}
fun List<EffectiveScheduleDay>.adjustmentExpected(course: com.gfs.iyango.core.model.Course, anchor: String): OccurrenceAdjustmentExpected? {
    val day = firstOrNull { day -> day.occurrences.any { it.course == course && it.anchorDate.toString() == anchor } } ?: return null
    val occurrence = day.occurrences.singleOrNull { it.course == course && it.anchorDate.toString() == anchor && !it.isTemporary } ?: return null
    val baseId = course.overrideOfId.takeIf { it > 0 } ?: course.id
    val base = day.baseCourses.firstOrNull { it.id == baseId } ?: return null
    return OccurrenceAdjustmentExpected(base, occurrence, day.adjustments.filter { it.sourceCourseId == baseId && it.sourceDate == anchor })
}
