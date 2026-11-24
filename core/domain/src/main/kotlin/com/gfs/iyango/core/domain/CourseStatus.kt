package com.gfs.iyango.core.domain

/**
 * 课程相对当前时刻的状态。
 */
enum class CourseStatus {
    /** 课程所在日期还在将来，或今天尚未进入开课窗口。 */
    NOT_STARTED,

    /** 即将开始：距开课不足 [CourseStatusCalculator.UPCOMING_WINDOW_MINUTES] 分钟。 */
    UPCOMING,

    /** 正在上课。 */
    IN_PROGRESS,

    /** 已经结束。 */
    FINISHED,
}
