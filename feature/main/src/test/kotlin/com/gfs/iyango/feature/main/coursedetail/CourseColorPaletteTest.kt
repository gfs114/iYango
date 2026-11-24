package com.gfs.iyango.feature.main.coursedetail

import com.gfs.iyango.core.model.Course
import com.gfs.iyango.feature.main.schedule.ScheduleDisplayProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseColorPaletteTest {

    @Test fun `palette defines 3 groups of 20 unique valid hex colors`() {
        assertEquals(3, CoursePaletteSet.entries.size)
        val all = CoursePaletteSet.entries.flatMap { set ->
            assertEquals(20, set.hexes.size)
            set.hexes
        }
        assertEquals(60, all.size)
        assertEquals(60, all.map { it.uppercase() }.distinct().size)
        all.forEach { hex ->
            assertTrue("Expected #RRGGBB for $hex", Regex("^#[0-9A-F]{6}$").matches(hex))
        }
    }

    @Test fun `findCourseColorCollision matches explicit color and overrides while ignoring self and disabled`() {
        val math = Course(id = 1, timetableId = 1, name = "高等数学", dayOfWeek = 1, startSection = 1, endSection = 2, color = "#e53935")
        val physics = Course(id = 2, timetableId = 1, name = "大学物理", dayOfWeek = 2, startSection = 3, endSection = 4, color = "#1E88E5")
        val chemistry = Course(id = 3, timetableId = 1, name = "无机化学", dayOfWeek = 3, startSection = 1, endSection = 2, color = "#43A047", enabled = false)
        val allCourses = listOf(math, physics, chemistry)

        // 本身使用 #E53935 不算撞色
        assertNull(findCourseColorCollision(math, "#E53935", allCourses, emptyMap()))

        // 命中显式 course.color（大小写不敏感）
        assertEquals("高等数学", findCourseColorCollision(physics, "#E53935", allCourses, emptyMap()))

        // 已停用课程忽略
        assertNull(findCourseColorCollision(math, "#43A047", allCourses, emptyMap()))

        // 覆盖调色板优先于原始 course.color
        val physicsKey = ScheduleDisplayProjection.courseColorKey(physics)
        val overrides = mapOf(physicsKey to "#8E24AA")
        assertNull(findCourseColorCollision(math, "#1E88E5", allCourses, overrides))
        assertEquals("大学物理", findCourseColorCollision(math, "#8e24aa", allCourses, overrides))
    }
}
