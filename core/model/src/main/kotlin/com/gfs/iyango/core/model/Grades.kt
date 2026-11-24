package com.gfs.iyango.core.model

/** 成绩查询可用的学期项（由学校 metadata 决定，不按当前月份猜测）。 */
data class GradeTerm(
    val value: String = "",
    val label: String = "",
)

/** 学分字段来源有效性，用于区分真实零学分、缺失/异常值和旧版未知零值。 */
enum class CreditValueStatus {
    VALID,
    MISSING_OR_INVALID,
    LEGACY_UNKNOWN_ZERO,
}

/**
 * 一条成绩记录。
 *
 * - [schoolRecordId] 优先作为身份；缺失时由 [stableKey] 组合课程号/学期/批次等字段；
 * - [scoreText] 保留学校原文（可能是“优秀”“合格”），[scoreValue] 仅在可解析时给出；
 * - [gradePoint] 是学校返回绩点；本地计算值不写回本字段；
 * - [rawCreditText]/[creditStatus]/[rawCourseAttribute]/[rawCourseType]/[rawGeneralElectiveType]
 *   供学分进度等纯规则模块追溯学校原文，不影响既有 [attribute] 与算分统计逻辑。
 */
data class GradeRecord(
    val id: Long = 0,
    val accountKey: String = "",
    val semester: String = "",
    val courseName: String = "",
    val courseCode: String = "",
    val credit: Double = 0.0,
    val scoreText: String = "",
    val scoreValue: Double? = null,
    val gradePoint: Double? = null,
    val attribute: String = "",
    val examType: String = "",
    val schoolRecordId: String = "",
    val rowOrder: Int = 0,
    val isNew: Boolean = false,
    val isModified: Boolean = false,
    val previousScoreText: String = "",
    val updateVersion: Int = 0,
    val updatedAt: Long = 0,
    val rawCreditText: String = "",
    val creditStatus: CreditValueStatus = CreditValueStatus.VALID,
    val rawCourseAttribute: String = attribute,
    val rawCourseType: String = "",
    val rawGeneralElectiveType: String = "",
    val rawAttributesKnown: Boolean = true,
) {
    /** 稳定身份：学校记录 id 优先，否则用可解释的字段组合。 */
    val stableKey: String
        get() = schoolRecordId.ifBlank {
            listOf(semester, courseCode, courseName, examType, attribute)
                .joinToString("|")
        }
}

/** 一次完整的成绩查询快照元信息。 */
data class GradeSnapshot(
    val id: Long = 0,
    val accountKey: String = "",
    val semester: String = "",
    val fetchedAt: Long = 0,
    val complete: Boolean = false,
    val parserVersion: Int = 1,
    val queryFingerprint: String = "",
    val targetSemesters: List<String> = emptyList(),
    val queriedSemesters: List<String> = emptyList(),
    val truncatedTermCount: Int = 0,
    val directAllEmptyUnverified: Boolean = false,
    val invalidatedByPartialUpdate: Boolean = false,
)

/** 成绩查询范围：空字符串表示“全部学期”。 */
data class GradeQuery(
    val semester: String = "",
)

/** 排序方式。 */
enum class GradeSort {
    /** 学校原始行顺序。 */
    SCHOOL_ORDER,

    /** 按分数降序（不可解析的成绩排在最后）。 */
    SCORE_DESC,

    /** 按学分降序。 */
    CREDIT_DESC,
}

/**
 * 本地参考统计。
 *
 * 口径：只纳入有可解析分数且学分 > 0 的记录；重修/补考按记录条数参与，不去重；
 * 公选课按 [includeOptional] 决定是否纳入（默认排除）。学校返回绩点与本地计算值分开。
 */
data class GradeStatistics(
    val weightedAverage: Double? = null,
    val averageGradePoint: Double? = null,
    val totalCredits: Double = 0.0,
    val includedCount: Int = 0,
    val excludedCount: Int = 0,
    val includeOptional: Boolean = false,
)
