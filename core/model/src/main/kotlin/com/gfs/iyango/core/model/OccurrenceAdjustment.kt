package com.gfs.iyango.core.model

/** 临时调整类型。 */
enum class AdjustmentType {
    /** 跨日/跨周移动：压制源发生并在目标日期投放。 */
    MOVE,

    /** 取消某一次发生。 */
    CANCEL,

    /** 临时整天课表：替换某一天的全部安排。 */
    TEMPORARY_DAY,
}

/**
 * 以“原始发生锚点”为索引的临时调整。
 *
 * 与旧 `Course.overrideOfId/overrideWeek` 语义并存：
 * - [sourceCourseId] 指向基础课程行；
 * - [sourceDate] 是被调整的那次发生的**实际日期**（不可变锚点）；
 * - MOVE 同时记录目标日期/星期/节次；
 * - CANCEL 只压制源发生；
 * - [operationGroupId] 让“跨周移动 + 目标投放”作为一组恢复/撤销；
 * - [temporaryDaySourceTimetableId] 仅 TEMPORARY_DAY 使用，表示复制来源课表。
 */
data class OccurrenceAdjustment(
    val id: Long = 0,
    val timetableId: Long = 0,
    val operationGroupId: String = "",
    val type: AdjustmentType = AdjustmentType.MOVE,
    val sourceCourseId: Long = 0,
    val sourceDate: String = "",
    val targetDate: String = "",
    val targetDayOfWeek: Int = 0,
    val targetStartSection: Int = 0,
    val targetEndSection: Int = 0,
    val targetStartTime: String = "",
    val targetEndTime: String = "",
    val targetClassroom: String = "",
    val note: String = "",
    val createdAt: Long = 0,
)

/**
 * 一次临时整天课表操作。
 *
 * 该日期的原始发生被整体压制，改为展示来源课表该日的安排（固定为来源时刻）。
 */
data class TemporaryDay(
    val id: Long = 0,
    val timetableId: Long = 0,
    val operationGroupId: String = "",
    val date: String = "",
    val sourceTimetableId: Long = 0,
    val title: String = "",
    val createdAt: Long = 0,
)
