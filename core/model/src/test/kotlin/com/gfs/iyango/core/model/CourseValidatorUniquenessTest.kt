package com.gfs.iyango.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [CourseValidator.validateOverrideUniqueness] 跨记录唯一性校验测试（P2-01）。
 */
class CourseValidatorUniquenessTest {

    private fun overrideCourse(
        id: Long = 0,
        timetableId: Long = 1,
        overrideOfId: Long = 10,
        overrideWeek: Int = 8,
    ) = Course(
        id = id,
        timetableId = timetableId,
        name = "高等数学",
        dayOfWeek = 3,
        startSection = 8,
        endSection = 8,
        startWeek = overrideWeek,
        endWeek = overrideWeek,
        overrideOfId = overrideOfId,
        overrideWeek = overrideWeek,
    )

    @Test
    fun `同课表同目标同周重复时拒绝`() {
        val existing = listOf(overrideCourse(id = 1))
        val candidate = overrideCourse(id = 0)
        assertEquals("该周已有针对此课程的调整", CourseValidator.validateOverrideUniqueness(candidate, existing))
    }

    @Test
    fun `不同周或不同目标不冲突`() {
        val existing = listOf(
            overrideCourse(id = 1, overrideWeek = 9),
            overrideCourse(id = 2, overrideOfId = 11, overrideWeek = 8),
        )
        assertNull(CourseValidator.validateOverrideUniqueness(overrideCourse(), existing))
    }

    @Test
    fun `更新自身记录不视为冲突`() {
        val existing = listOf(overrideCourse(id = 1))
        assertNull(CourseValidator.validateOverrideUniqueness(overrideCourse(id = 1), existing))
    }

    @Test
    fun `不同课表互不影响`() {
        val existing = listOf(overrideCourse(id = 1, timetableId = 2))
        assertNull(CourseValidator.validateOverrideUniqueness(overrideCourse(timetableId = 1), existing))
    }

    @Test
    fun `普通课程不参与唯一性校验`() {
        val normal = Course(timetableId = 1, name = "体育", startWeek = 1, endWeek = 16)
        assertNull(CourseValidator.validateOverrideUniqueness(normal, listOf(overrideCourse(id = 1))))
    }

    @Test
    fun `共享单课校验保持原有行为`() {
        assertNull(CourseValidator.validate(overrideCourse()))
        assertEquals(
            "临时换课来源或周次无效",
            CourseValidator.validate(overrideCourse().copy(overrideWeek = 0)),
        )
    }
}
