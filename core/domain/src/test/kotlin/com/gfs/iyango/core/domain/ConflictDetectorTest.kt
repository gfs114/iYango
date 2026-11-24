package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.WeekType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ConflictDetector] 冲突检测测试。
 */
class ConflictDetectorTest {

    private fun course(
        id: Long,
        name: String,
        dayOfWeek: Int = 1,
        startSection: Int = 1,
        endSection: Int = 2,
        startWeek: Int = 1,
        endWeek: Int = 10,
        weekType: WeekType = WeekType.EVERY,
    ): Course = Course(
        id = id,
        name = name,
        dayOfWeek = dayOfWeek,
        startSection = startSection,
        endSection = endSection,
        startWeek = startWeek,
        endWeek = endWeek,
        weekType = weekType,
    )

    // ---------------------------------------------------------- sharedWeekCount

    @Test
    fun `共享周逐周计数`() {
        val every = course(id = 1, name = "每周", endWeek = 10)
        val odd = course(id = 2, name = "单周", weekType = WeekType.ODD)
        val even = course(id = 3, name = "双周", weekType = WeekType.EVEN)
        assertEquals(10, ConflictDetector.sharedWeekCount(every, every))
        assertEquals(5, ConflictDetector.sharedWeekCount(odd, every))
        assertEquals(0, ConflictDetector.sharedWeekCount(odd, even))
        assertEquals(0, ConflictDetector.sharedWeekCount(even, odd))
    }

    @Test
    fun `共享周按区间交集计数`() {
        val left = course(id = 1, name = "A", startWeek = 1, endWeek = 5)
        val right = course(id = 2, name = "B", startWeek = 3, endWeek = 8)
        assertEquals(3, ConflictDetector.sharedWeekCount(left, right))
        // 无交集。
        val early = course(id = 3, name = "C", startWeek = 1, endWeek = 2)
        val late = course(id = 4, name = "D", startWeek = 5, endWeek = 8)
        assertEquals(0, ConflictDetector.sharedWeekCount(early, late))
    }

    // ---------------------------------------------------------- sectionsOverlap

    @Test
    fun `节次闭区间重叠`() {
        val a = course(id = 1, name = "A", startSection = 1, endSection = 2)
        val b = course(id = 2, name = "B", startSection = 2, endSection = 3)
        val c = course(id = 3, name = "C", startSection = 3, endSection = 4)
        assertTrue(ConflictDetector.sectionsOverlap(a, b))
        assertTrue(ConflictDetector.sectionsOverlap(b, a))
        assertFalse(ConflictDetector.sectionsOverlap(a, c))
    }

    // ---------------------------------------------------------- conflictsIgnoring

    @Test
    fun `同日同节共享周即冲突`() {
        val candidate = course(id = 0, name = "候选")
        val existing = course(id = 1, name = "已有")
        assertTrue(ConflictDetector.conflictsIgnoring(candidate, existing, 0L))
    }

    @Test
    fun `ignoreId 使课程与自己不相撞`() {
        val edited = course(id = 7, name = "编辑中")
        assertFalse(ConflictDetector.conflictsIgnoring(edited, edited, 7L))
        // ignoreId 只对 id > 0 的课程生效。
        val fresh = course(id = 0, name = "未持久化")
        assertTrue(ConflictDetector.conflictsIgnoring(fresh, fresh, 0L))
    }

    @Test
    fun `不同星期不冲突`() {
        val candidate = course(id = 0, name = "候选", dayOfWeek = 1)
        val existing = course(id = 1, name = "已有", dayOfWeek = 2)
        assertFalse(ConflictDetector.conflictsIgnoring(candidate, existing, 0L))
    }

    @Test
    fun `节次不相交不冲突`() {
        val candidate = course(id = 0, name = "候选", startSection = 1, endSection = 1)
        val existing = course(id = 1, name = "已有", startSection = 2, endSection = 2)
        assertFalse(ConflictDetector.conflictsIgnoring(candidate, existing, 0L))
    }

    @Test
    fun `单双周不相交不冲突`() {
        val candidate = course(id = 0, name = "候选", weekType = WeekType.ODD)
        val existing = course(id = 1, name = "已有", weekType = WeekType.EVEN)
        assertFalse(ConflictDetector.conflictsIgnoring(candidate, existing, 0L))
    }

    // ---------------------------------------------------------- findConflicts

    @Test
    fun `findConflicts 按既有顺序返回并携带细节`() {
        // 候选未持久化（id=0），默认 ignoreId=candidate.id 不会误伤任何已有课程。
        val candidate = course(id = 0, name = "候选", startSection = 1, endSection = 3)
        val same = course(id = 1, name = "完全同节", startSection = 1, endSection = 3)
        val partial = course(id = 2, name = "部分重叠", startSection = 2, endSection = 4)
        val otherDay = course(id = 3, name = "别天", dayOfWeek = 2, startSection = 1, endSection = 3)
        val conflicts = ConflictDetector.findConflicts(candidate, listOf(same, otherDay, partial))
        // 顺序与 existing 一致；候选自身 id=5 被 ignoreId 默认忽略。
        assertEquals(listOf("完全同节", "部分重叠"), conflicts.map { it.existing.name })
        assertEquals(10, conflicts[0].sharedWeeks)
        assertTrue(conflicts[0].sameSections)
        assertEquals(10, conflicts[1].sharedWeeks)
        assertFalse(conflicts[1].sameSections)
    }

    @Test
    fun `显式 ignoreId 可忽略任一侧`() {
        val candidate = course(id = 5, name = "候选")
        val existing = course(id = 5, name = "旧记录")
        assertEquals(1, ConflictDetector.findConflicts(candidate, listOf(existing), ignoreId = 0L).size)
        assertEquals(0, ConflictDetector.findConflicts(candidate, listOf(existing), ignoreId = 5L).size)
    }

    // ---------------------------------------------------------- warningMessage

    @Test
    fun `warningMessage 文案`() {
        assertNull(ConflictDetector.warningMessage(emptyList()))
        val math = course(id = 1, name = "高等数学")
        val english = course(id = 2, name = "大学英语")
        assertEquals(
            "发现课程冲突：高等数学，是否继续添加？",
            ConflictDetector.warningMessage(listOf(CourseConflict(math, 10, true))),
        )
        assertEquals(
            "发现课程冲突：高等数学、大学英语，是否继续添加？",
            ConflictDetector.warningMessage(
                listOf(CourseConflict(math, 10, true), CourseConflict(english, 5, false)),
            ),
        )
        // 空白名称回退为"未命名课程"。
        val blank = course(id = 3, name = "  ")
        assertEquals(
            "发现课程冲突：未命名课程，是否继续添加？",
            ConflictDetector.warningMessage(listOf(CourseConflict(blank, 1, false))),
        )
    }
}
