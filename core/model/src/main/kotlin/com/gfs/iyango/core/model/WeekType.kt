package com.gfs.iyango.core.model

/**
 * 课程在教学周序列上的重复规律。
 *
 * 语义约束：
 * - [ODD] 表示只在奇数教学周上课（第 1、3、5…周）；
 * - [EVEN] 表示只在偶数教学周上课（第 2、4、6…周）；
 * - 判定使用 `week % 2`，教学周从 1 开始计数（见 [CourseRules][com.gfs.iyango.core.domain.CourseRules]）。
 */
enum class WeekType(val code: Int) {
    /** 每周都上。 */
    EVERY(0),

    /** 仅单周（奇数周）上。 */
    ODD(1),

    /** 仅双周（偶数周）上。 */
    EVEN(2);

    companion object {
        /**
         * 按持久化编码还原枚举。
         *
         * @param code 持久化编码，合法取值 0/1/2（分别对应 EVERY/ODD/EVEN）。
         * @throws IllegalArgumentException 编码不在 0..2 范围内时抛出。
         */
        fun fromCode(code: Int): WeekType = entries.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("未知 WeekType 编码: $code")
    }
}
