package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.GradeRecord
import com.gfs.iyango.core.model.GradeSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GradeStatisticsTest {

    @Test
    fun `school practice grade written as a single character contributes score and school GPA`() {
        val record = GradeRecord(semester = "2025-2026-2", courseName = "思政课社会实践（二）",
            credit = 1.0, scoreText = "优", scoreValue = null, gradePoint = 4.5, attribute = "必修")
        val stats = GradeStatisticsCalculator.calculate(listOf(record))
        assertEquals(1, stats.includedCount)
        assertEquals(0, stats.excludedCount)
        assertEquals(95.0, stats.weightedAverage ?: Double.NaN, .0001)
        assertEquals(4.5, stats.averageGradePoint ?: Double.NaN, .0001)
        assertTrue(GradeStatisticsCalculator.isIncluded(record))
    }

    @Test
    fun `abbreviated levels use the same score and GPA conversion as their full names`() {
        listOf("优" to "优秀", "良" to "良好", "中" to "中等").forEach { (short, full) ->
            assertEquals(full, GradeStatisticsCalculator.parseScore(full), GradeStatisticsCalculator.parseScore(" $short "))
            val record = GradeRecord(credit = 1.0, scoreText = " $short ", scoreValue = 0.0, attribute = "必修")
            assertEquals(GradeStatisticsCalculator.calculate(listOf(record.copy(scoreText = full))),
                GradeStatisticsCalculator.calculate(listOf(record)))
        }
    }

    @Test
    fun `等级成绩按规则换算且仅公选课默认排除`() {
        val records = listOf(
            GradeRecord(id = 1, courseName = "高数", credit = 2.0, scoreText = "优秀", attribute = "必修"),
            GradeRecord(id = 2, courseName = "大英", credit = 2.0, scoreText = "良好", attribute = "选修"),
            GradeRecord(id = 3, courseName = "体育", credit = 1.0, scoreText = "中等", attribute = "限选"),
            GradeRecord(id = 4, courseName = "实验", credit = 1.0, scoreText = "及格", attribute = "任选"),
            GradeRecord(id = 5, courseName = "重修课", credit = 1.0, scoreText = "不及格", attribute = "必修"),
            GradeRecord(id = 6, courseName = "电影鉴赏", credit = 2.0, scoreText = "90", attribute = "公选"),
            GradeRecord(id = 7, courseName = "通识课", credit = 2.0, scoreText = "92", attribute = "任选·公选"),
        )

        val defaultStats = GradeStatisticsCalculator.calculate(records)
        assertEquals(5, defaultStats.includedCount)
        assertEquals(2, defaultStats.excludedCount)
        assertEquals(7.0, defaultStats.totalCredits, 0.0001)
        // (95*2 + 85*2 + 75*1 + 65*1 + 0*1) / 7 = 500 / 7
        assertEquals(500.0 / 7.0, defaultStats.weightedAverage!!, 0.0001)
        assertTrue(GradeStatisticsCalculator.isIncluded(records[1], includeOptional = false))
        assertFalse(GradeStatisticsCalculator.isIncluded(records[5], includeOptional = false))
        assertFalse(GradeStatisticsCalculator.isIncluded(records[6], includeOptional = false))

        val withPublic = GradeStatisticsCalculator.calculate(records, includeOptional = true)
        assertEquals(7, withPublic.includedCount)
        assertEquals(0, withPublic.excludedCount)
        assertEquals(11.0, withPublic.totalCredits, 0.0001)
    }

    @Test
    fun `parseScore 解析数字与五级制等级并忽略无法识别文本`() {
        assertEquals(95.0, GradeStatisticsCalculator.parseScore("优秀")!!, 0.0001)
        assertEquals(85.0, GradeStatisticsCalculator.parseScore("良好")!!, 0.0001)
        assertEquals(75.0, GradeStatisticsCalculator.parseScore("中等")!!, 0.0001)
        assertEquals(65.0, GradeStatisticsCalculator.parseScore("及格")!!, 0.0001)
        assertEquals(0.0, GradeStatisticsCalculator.parseScore("不及格")!!, 0.0001)
        assertEquals(92.0, GradeStatisticsCalculator.parseScore("优秀（92）")!!, 0.0001)
        assertNull(GradeStatisticsCalculator.parseScore("合格"))
        assertNull(GradeStatisticsCalculator.parseScore("请评教"))
    }

    @Test
    fun `cached grade numbers use the current level mapping without requiring another query`() {
        val levels = listOf("优秀", "良好", "中等", "及格", "不及格")
        val expected = listOf(95.0, 85.0, 75.0, 65.0, 0.0)
        val records = levels.mapIndexed { index, level ->
            GradeRecord(id = index.toLong() + 1, credit = 1.0, scoreText = " $level ",
                scoreValue = if (level == "及格") 60.0 else 0.0,
                gradePoint = if (level == "及格") 1.5 else null)
        }
        assertEquals(expected, records.map { GradeStatisticsCalculator.resolvedScore(it) })
        val stats = GradeStatisticsCalculator.calculate(records)
        assertEquals(5, stats.includedCount)
        assertEquals(0, stats.excludedCount)
        assertEquals(5.0, stats.totalCredits, .0001)
        assertEquals(64.0, stats.weightedAverage!!, .0001)
        assertEquals(2.4, stats.averageGradePoint!!, .0001)
    }

    @Test
    fun `all five levels contribute GPA even when the school does not provide it`() {
        val levels = listOf("优秀", "良好", "中等", "及格", "不及格")
        val expected = listOf(4.5, 3.5, 2.5, 1.5, 0.0)
        levels.forEachIndexed { index, level ->
            val record = GradeRecord(credit = 2.0, scoreText = " $level ", attribute = "必修")
            val stats = GradeStatisticsCalculator.calculate(listOf(record))
            assertEquals(level, expected[index], stats.averageGradePoint ?: Double.NaN, .0001)
            assertEquals(1, stats.includedCount)
            assertNull(record.gradePoint)
        }
    }

    @Test
    fun `converted GPA uses credit weights and respects school values and public elective selection`() {
        val records = listOf(
            GradeRecord(credit = 2.0, scoreText = "90", gradePoint = 4.0, attribute = "必修"),
            GradeRecord(credit = 2.0, scoreText = "优秀", gradePoint = 4.25, attribute = "必修"),
            GradeRecord(credit = 1.0, scoreText = "良好", attribute = "限选"),
            GradeRecord(credit = 1.0, scoreText = "不及格", attribute = "必修"),
            GradeRecord(credit = 2.0, scoreText = "优秀", attribute = "公选"),
            GradeRecord(credit = 2.0, scoreText = "及格", attribute = "通识教育选修"),
            GradeRecord(credit = 1.0, scoreText = "100", attribute = "任选"),
            GradeRecord(credit = 2.0, scoreText = "合格", attribute = "必修"),
        )
        val stats = GradeStatisticsCalculator.calculate(records)
        assertEquals(5, stats.includedCount)
        assertEquals(3, stats.excludedCount)
        assertEquals(7.0, stats.totalCredits, .0001)
        // Numeric grades without a school GPA stay outside the GPA denominator; failed levels remain in it.
        assertEquals(20.0 / 6.0, stats.averageGradePoint ?: Double.NaN, .0001)
        val withPublic = GradeStatisticsCalculator.calculate(records, includeOptional = true)
        assertEquals(32.0 / 10.0, withPublic.averageGradePoint ?: Double.NaN, .0001)
        val onlyPublic = GradeStatisticsCalculator.calculate(records.subList(4, 6))
        assertNull(onlyPublic.averageGradePoint)
        assertEquals(2, onlyPublic.excludedCount)
    }

    @Test
    fun `score sorting uses the same level conversion as statistics and retains numeric grades`() {
        val records = listOf(
            GradeRecord(id = 1, credit = 1.0, scoreText = "及格", scoreValue = 60.0),
            GradeRecord(id = 2, credit = 1.0, scoreText = "62", scoreValue = 62.0),
            GradeRecord(id = 3, credit = 1.0, scoreText = "不及格", scoreValue = 0.0),
        )
        assertEquals(listOf(1L, 2L, 3L), GradeSorter.sort(records, GradeSort.SCORE_DESC).map { it.id })
        assertEquals(92.0, GradeStatisticsCalculator.resolvedScore(
            GradeRecord(scoreText = "优秀（92）", scoreValue = 92.0))!!, .0001)
    }
}
