package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.AdjustmentType
import com.gfs.iyango.core.model.OccurrenceAdjustment
import com.gfs.iyango.core.model.TemporaryDay
import java.time.LocalDate
import java.util.UUID

/** 生成一次临时操作的操作组 id（移动/取消/整天覆盖可整组恢复）。 */
fun newAdjustmentGroupId(): String = UUID.randomUUID().toString()

/** F08 跨日/跨周移动：压制源发生并在目标日期投放。 */
object MoveOccurrence {

    fun create(
        timetableId: Long,
        sourceCourseId: Long,
        sourceDate: String,
        targetDate: LocalDate,
        targetStartSection: Int,
        targetEndSection: Int,
        targetStartTime: String = "",
        targetEndTime: String = "",
        targetClassroom: String = "",
        note: String = "",
        operationGroupId: String = newAdjustmentGroupId(),
        now: Long = System.currentTimeMillis(),
    ): OccurrenceAdjustment = OccurrenceAdjustment(
        timetableId = timetableId,
        operationGroupId = operationGroupId,
        type = AdjustmentType.MOVE,
        sourceCourseId = sourceCourseId,
        sourceDate = sourceDate,
        targetDate = targetDate.toString(),
        targetDayOfWeek = targetDate.dayOfWeek.value,
        targetStartSection = targetStartSection,
        targetEndSection = maxOf(targetStartSection, targetEndSection),
        targetStartTime = targetStartTime,
        targetEndTime = targetEndTime,
        targetClassroom = targetClassroom,
        note = note,
        createdAt = now,
    )
}

/** F08 取消某一次发生（不删除课程本体）。 */
object CancelOccurrence {

    fun create(
        timetableId: Long,
        sourceCourseId: Long,
        sourceDate: String,
        note: String = "",
        operationGroupId: String = newAdjustmentGroupId(),
        now: Long = System.currentTimeMillis(),
    ): OccurrenceAdjustment = OccurrenceAdjustment(
        timetableId = timetableId,
        operationGroupId = operationGroupId,
        type = AdjustmentType.CANCEL,
        sourceCourseId = sourceCourseId,
        sourceDate = sourceDate,
        note = note,
        createdAt = now,
    )
}

/** F08 临时整天课表：用来源课表该日的实际安排替换目标日期。 */
object ApplyTemporaryDay {

    fun create(
        timetableId: Long,
        date: LocalDate,
        sourceTimetableId: Long,
        title: String = "",
        operationGroupId: String = newAdjustmentGroupId(),
        now: Long = System.currentTimeMillis(),
    ): TemporaryDay = TemporaryDay(
        timetableId = timetableId,
        operationGroupId = operationGroupId,
        date = date.toString(),
        sourceTimetableId = sourceTimetableId,
        title = title,
        createdAt = now,
    )
}
