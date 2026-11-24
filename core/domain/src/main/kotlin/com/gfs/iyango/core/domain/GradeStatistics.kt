package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.GradeRecord
import com.gfs.iyango.core.model.GradeSort
import com.gfs.iyango.core.model.GradeStatistics

/**
 * 成绩本地统计与排序（F03）。
 *
 * 统计口径：
 * - 除公选课外，其余学分 > 0 且可取得数值/等级分数的记录均计入计算；
 * - 等级文本换算：优秀 95、良好 85、中等 75、及格 65、不及格 0；
 * - 重修/补考按记录条数参与，**不去重**；
 * - [includeOptional] 为 false 时排除公选课（含通选/公共选修）记录；
 * - 加权平均分 = Σ(分数×学分) / Σ学分；
 * - 平均绩点优先使用学校返回值；缺失时五级制按 4.5/3.5/2.5/1.5/0 换算，按学分加权。
 */
object GradeStatisticsCalculator {

    private val LEVEL_SCORES = mapOf(
        "优秀" to 95.0,
        "良好" to 85.0,
        "中等" to 75.0,
        "及格" to 65.0,
        "不及格" to 0.0,
    )

    private val PUBLIC_ELECTIVE_KEYWORDS = listOf(
        "公选",
        "公共选修",
        "通选",
        "通识选修",
        "通识教育选修",
    )

    fun isPublicElectiveAttribute(attribute: String): Boolean =
        PUBLIC_ELECTIVE_KEYWORDS.any { keyword -> attribute.contains(keyword) }

    fun isPublicElective(record: GradeRecord): Boolean =
        isPublicElectiveAttribute(record.attribute)

    fun levelScore(text: String): Double? = LEVEL_SCORES[
        when (val level = text.trim()) {
            "优" -> "优秀"
            "良" -> "良好"
            "中" -> "中等"
            else -> level
        }
    ]

    /** 纯等级成绩的数值可能是旧规则生成的缓存，计算时优先使用当前换算表。 */
    fun resolvedScore(record: GradeRecord): Double? =
        levelScore(record.scoreText) ?: record.scoreValue ?: parseScore(record.scoreText)

    /** 学校绩点优先；仅对五级制补齐本地换算值，不写回学校原始数据。 */
    fun resolvedGradePoint(record: GradeRecord): Double? =
        record.gradePoint ?: levelScore(record.scoreText)?.let { score ->
            ((score - 50.0) / 10.0).coerceAtLeast(0.0)
        }

    fun isIncluded(
        record: GradeRecord,
        includeOptional: Boolean = false,
    ): Boolean = record.credit > 0.0 &&
        resolvedScore(record) != null &&
        (includeOptional || !isPublicElective(record))

    fun calculate(
        records: List<GradeRecord>,
        includeOptional: Boolean = false,
    ): GradeStatistics {
        var excluded = 0
        var included = 0
        var scoreWeighted = 0.0
        var gpaWeighted = 0.0
        var gpaCredit = 0.0
        var totalCredit = 0.0
        records.forEach { record ->
            val score = resolvedScore(record)
            if (record.credit <= 0.0 || score == null || (!includeOptional && isPublicElective(record))) {
                excluded++
                return@forEach
            }
            included++
            totalCredit += record.credit
            scoreWeighted += score * record.credit
            resolvedGradePoint(record)?.let { gpa ->
                gpaWeighted += gpa * record.credit
                gpaCredit += record.credit
            }
        }
        return GradeStatistics(
            weightedAverage = scoreWeighted.takeIf { totalCredit > 0 }?.div(totalCredit),
            averageGradePoint = gpaWeighted.takeIf { gpaCredit > 0 }?.div(gpaCredit),
            totalCredits = totalCredit,
            includedCount = included,
            excludedCount = excluded,
            includeOptional = includeOptional,
        )
    }

    /**
     * 从文本成绩中解析数值（如“90”“90.5”“优秀（95）”取数值，
     * 纯等级文本按 优秀95/良好85/中等75/及格65/不及格0 换算）；无法解析返回 null。
     */
    fun parseScore(text: String): Double? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val candidates = Regex("\\d+(?:\\.\\d+)?").findAll(trimmed).map { it.value }.toList()
        candidates.lastOrNull()?.toDoubleOrNull()?.let { return it }
        return levelScore(trimmed)
    }
}

/** 成绩视图排序。返回新列表，不修改原始学校行顺序。 */
object GradeSorter {

    fun sort(records: List<GradeRecord>, sort: GradeSort): List<GradeRecord> = when (sort) {
        GradeSort.SCHOOL_ORDER -> records.sortedBy { it.rowOrder }
        GradeSort.SCORE_DESC -> records.sortedWith(
            compareByDescending<GradeRecord> {
                GradeStatisticsCalculator.resolvedScore(it)
            }.thenBy { it.rowOrder },
        )
        GradeSort.CREDIT_DESC -> records.sortedWith(
            compareByDescending<GradeRecord> { it.credit }.thenBy { it.rowOrder },
        )
    }
}
