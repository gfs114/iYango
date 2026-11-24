package com.gfs.iyango.core.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest

/** 学分精度统一基准：所有累计与比较保留 2 位小数，HALF_UP。 */
val CREDIT_ZERO: BigDecimal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)

const val CREDIT_SCALE = 2
const val UNCLASSIFIED_CATEGORY_ID = "__unclassified__"

fun BigDecimal.toCreditScale(): BigDecimal = setScale(CREDIT_SCALE, RoundingMode.HALF_UP)

/**
 * 从原始字符串直接解析为有限非负 [BigDecimal]（不经过 Double）。
 * 显式拒绝空白、`NaN`、`Infinity`、科学计数法、负数及其他非数字格式。
 */
fun parseCreditBigDecimal(raw: String): BigDecimal? {
    val trimmed = raw.replace('\u3000', ' ').trim()
    if (trimmed.isEmpty()) return null
    if (!STRICT_DECIMAL_REGEX.matches(trimmed)) return null
    val parsed = runCatching { BigDecimal(trimmed) }.getOrNull() ?: return null
    if (parsed.compareTo(BigDecimal.ZERO) < 0) return null
    return parsed.toCreditScale()
}

/** 格式化为去尾零的紧凑十进制字符串（例如 `12.00` -> `"12"`, `2.50` -> `"2.5"`）。 */
fun formatCreditBigDecimal(value: BigDecimal): String {
    val scaled = value.toCreditScale()
    if (scaled.compareTo(BigDecimal.ZERO) == 0) return "0"
    val stripped = scaled.stripTrailingZeros()
    return if (stripped.compareTo(BigDecimal.ZERO) == 0) "0" else stripped.toPlainString()
}

private val STRICT_DECIMAL_REGEX = Regex("^[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)$")

/**
 * 阳光学院（YGU）成绩通过判定口径：
 * - 百分制数值：[60, 100] 通过；[0, 60) 未通过；超出 [0, 100] 列为待确认。
 * - 等级文本先规范化（去除首尾与中间空白）后做精确完整匹配，严禁使用 `.contains("合格")` 子串判断。
 * - “缺考/缓考/未出分/取消/请评教”等非完成状态明确不计入。
 * - “免修/免考/未认定”及未知文本、空文本进入待确认，不自动计入。
 */
object CreditPassThreshold {

    const val MIN_PASS_SCORE = 60.0
    const val MAX_SCORE = 100.0
    const val SOURCE_DESCRIPTION = "阳光学院（YGU）教务百分制及五级制核验口径：百分制 60–100 达标，0–60 未通过"

    val PASS_TEXTS: Set<String> = setOf(
        "优秀", "优", "良好", "良", "中等", "中", "及格", "合格",
    )

    val FAIL_TEXTS: Set<String> = setOf(
        "不及格", "不合格",
    )

    val NOT_FINISHED_STATUS_TEXTS: Set<String> = setOf(
        "缺考", "缓考", "未出分", "取消", "请评教", "未考", "作弊", "违纪",
    )

    val PENDING_CONFIRMATION_TEXTS: Set<String> = setOf(
        "免修", "免考", "未认定",
    )

    private val PAREN_LEVEL_REGEX = Regex(
        "^(优秀|优|良好|良|中等|中|及格|不及格|合格|不合格)[(（]([-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))[)）]$",
    )
    private val SIXTY = BigDecimal("60")
    private val HUNDRED = BigDecimal("100")

    sealed interface ScoreEvaluation {
        data class Passed(val numericScore: BigDecimal?) : ScoreEvaluation
        data class Failed(val numericScore: BigDecimal?) : ScoreEvaluation
        data class StatusNotFinished(val statusText: String) : ScoreEvaluation
        data class PendingConfirmation(val reasonText: String) : ScoreEvaluation
    }

    fun normalizeScoreText(raw: String): String =
        raw.replace('\u3000', ' ').trim().replace(Regex("\\s+"), "")

    fun evaluateScore(record: GradeRecord): ScoreEvaluation {
        val normalized = normalizeScoreText(record.scoreText)
        if (normalized.isEmpty()) {
            return ScoreEvaluation.PendingConfirmation("成绩为空")
        }
        if (normalized in PASS_TEXTS) {
            return ScoreEvaluation.Passed(numericScore = null)
        }
        if (normalized in FAIL_TEXTS) {
            return ScoreEvaluation.Failed(numericScore = null)
        }
        if (normalized in NOT_FINISHED_STATUS_TEXTS) {
            return ScoreEvaluation.StatusNotFinished(normalized)
        }
        if (normalized in PENDING_CONFIRMATION_TEXTS) {
            return ScoreEvaluation.PendingConfirmation("需确认成绩状态：$normalized")
        }
        if (STRICT_DECIMAL_REGEX.matches(normalized)) {
            val score = runCatching { BigDecimal(normalized) }.getOrNull()
                ?: return ScoreEvaluation.PendingConfirmation("无法解析成绩数值：${record.scoreText.trim()}")
            return evaluateNumeric(score)
        }
        val parenMatch = PAREN_LEVEL_REGEX.matchEntire(normalized)
        if (parenMatch != null) {
            val numericPart = parenMatch.groupValues[2]
            val score = runCatching { BigDecimal(numericPart) }.getOrNull()
                ?: return ScoreEvaluation.PendingConfirmation("无法解析成绩数值：${record.scoreText.trim()}")
            return evaluateNumeric(score)
        }
        return ScoreEvaluation.PendingConfirmation("未知成绩文本：${record.scoreText.trim()}")
    }

    private fun evaluateNumeric(score: BigDecimal): ScoreEvaluation = when {
        score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(HUNDRED) > 0 ->
            ScoreEvaluation.PendingConfirmation("百分制分值超出范围：${score.stripTrailingZeros().toPlainString()}")
        score.compareTo(SIXTY) >= 0 -> ScoreEvaluation.Passed(score)
        else -> ScoreEvaluation.Failed(score)
    }
}

/** 从记录中提取可信十进制学分。 */
sealed interface ResolvedRecordCredit {
    data class Valid(val value: BigDecimal) : ResolvedRecordCredit
    data class InvalidOrMissing(val detail: String) : ResolvedRecordCredit
    data object LegacyUnknownZero : ResolvedRecordCredit
}

fun resolveRecordCredit(record: GradeRecord): ResolvedRecordCredit {
    return when (record.creditStatus) {
        CreditValueStatus.MISSING_OR_INVALID ->
            ResolvedRecordCredit.InvalidOrMissing(
                if (record.rawCreditText.isBlank()) "学分缺失" else "异常学分：${record.rawCreditText}",
            )
        CreditValueStatus.LEGACY_UNKNOWN_ZERO ->
            ResolvedRecordCredit.LegacyUnknownZero
        CreditValueStatus.VALID -> {
            if (!record.credit.isFinite() || record.credit < 0.0) {
                return ResolvedRecordCredit.InvalidOrMissing("学分数值异常")
            }
            if (record.rawCreditText.isNotBlank()) {
                val parsed = parseCreditBigDecimal(record.rawCreditText)
                    ?: return ResolvedRecordCredit.InvalidOrMissing("异常学分：${record.rawCreditText}")
                ResolvedRecordCredit.Valid(parsed)
            } else {
                ResolvedRecordCredit.Valid(BigDecimal.valueOf(record.credit).toCreditScale())
            }
        }
    }
}

/**
 * 稳定课程来源锚点与内容指纹。
 *
 * 锚点与指纹绝不包含易变的 `#ordinal`、列表 `rowOrder` 或本机 Room 自增 `id`，
 * 以便学校重复行换序时仍可唯一或经内容指纹校验识别。
 */
object CreditRecordAnchor {

    private val ORDINAL_SUFFIX = Regex("#\\d+$")

    fun normalizeCode(raw: String): String = raw.replace('\u3000', ' ').trim().uppercase()

    fun normalizeName(raw: String): String =
        raw.replace('\u3000', ' ').trim().replace(Regex("\\s+"), " ")

    fun baseSchoolRecordId(schoolRecordId: String): String =
        ORDINAL_SUFFIX.replace(schoolRecordId.trim(), "")

    fun primaryRawAttribute(record: GradeRecord): String {
        val attr = record.rawCourseAttribute.trim()
        val type = record.rawCourseType.trim()
        val elective = record.rawGeneralElectiveType.trim()
        return when {
            elective.isNotEmpty() && (attr.isEmpty() || attr == "选修" || attr == "任选" || attr == "公选" || attr == "通选") ->
                "公选"
            attr.isNotEmpty() -> attr
            type.isNotEmpty() -> type
            else -> ""
        }
    }

    fun buildAnchorKey(record: GradeRecord): String {
        val code = normalizeCode(record.courseCode)
        val name = normalizeName(record.courseName)
        val sem = record.semester.trim()
        val exam = record.examType.trim()
        val baseId = baseSchoolRecordId(record.schoolRecordId)
        val identity = baseId.ifBlank { listOf(sem, code, name, exam).joinToString("|") }
        return sha256Hex("anchor|$identity")
    }

    fun buildContentFingerprint(record: GradeRecord): String {
        val canonicalCredit = when (val credit = resolveRecordCredit(record)) {
            is ResolvedRecordCredit.Valid -> credit.value.toPlainString()
            is ResolvedRecordCredit.InvalidOrMissing -> "INVALID:${record.rawCreditText.trim()}"
            ResolvedRecordCredit.LegacyUnknownZero -> "LEGACY_ZERO"
        }
        val payload = listOf(
            record.semester.trim(),
            normalizeCode(record.courseCode),
            normalizeName(record.courseName),
            canonicalCredit,
            record.creditStatus.name,
            CreditPassThreshold.normalizeScoreText(record.scoreText),
            record.rawCourseAttribute.trim(),
            record.rawCourseType.trim(),
            record.rawGeneralElectiveType.trim(),
            record.examType.trim(),
            record.rawAttributesKnown.toString(),
        ).joinToString("|")
        return sha256Hex(payload)
    }

    /** 账号主体备份匹配指纹：使用学校标识与已验证账号主体计算，不含口令/明文账号/Cookie。 */
    fun accountSubjectFingerprint(schoolId: String, accountKey: String): String =
        sha256Hex("${schoolId.trim().uppercase()}:${accountKey.trim()}")

    fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** 人工待确认消歧动作。 */
enum class CreditResolutionAction {
    /** 确认为同一门课程（或指定代表记录、指定分类/核准免修），按单课只计一次。 */
    MERGE_AS_SINGLE_COURSE,
    /** 拆分为多门独立课程，分别归类或排除。 */
    SPLIT_INTO_SEPARATE_COURSES,
    /** 明确不计入当前学分进度计划。 */
    EXCLUDE_FROM_PLAN,
}

/** 拆分场景下每条来源记录的独立归属配置。 */
data class CreditSplitAssignment(
    val anchorKey: String,
    val occurrenceIndex: Int = 0,
    val customGroupKey: String,
    val assignedCategoryId: String? = null,
    val expectedFingerprint: String = "",
    val exclude: Boolean = false,
    val manualPassConfirmed: Boolean = false,
)

/** 一条待确认课程组的持久化人工确认规则。 */
data class CreditResolutionRule(
    val ruleKey: String,
    val action: CreditResolutionAction,
    val assignedCategoryId: String? = null,
    val chosenAnchorKey: String? = null,
    val splitAssignments: List<CreditSplitAssignment> = emptyList(),
    val expectedFingerprints: List<String> = emptyList(),
    val requiresPostRestoreVerification: Boolean = false,
    val manualPassConfirmed: Boolean = false,
    val note: String = "",
    val updatedAtEpochMillis: Long = 0L,
)

/** 用户可编辑的学分分类配置；以 [categoryId] 保持目标和规则与显示名称解耦。 */
data class CreditCategoryPlan(
    val categoryId: String,
    val displayName: String,
    val targetCredits: BigDecimal? = null,
    val mappedRawAttributes: Set<String> = emptySet(),
    val sortOrder: Int = 0,
)

fun defaultCreditCategories(): List<CreditCategoryPlan> = listOf(
    CreditCategoryPlan(
        categoryId = "required",
        displayName = "必修",
        targetCredits = null,
        mappedRawAttributes = setOf("必修", "公共必修", "专业必修", "公共基础必修", "公共必修课", "专业必修课"),
        sortOrder = 0,
    ),
    CreditCategoryPlan(
        categoryId = "major_elective",
        displayName = "选修 / 限选 / 任选",
        targetCredits = null,
        mappedRawAttributes = setOf("选修", "限选", "任选", "专业选修", "专选", "专业限选", "专业选修课"),
        sortOrder = 1,
    ),
    CreditCategoryPlan(
        categoryId = "general_elective",
        displayName = "通识选修 / 公选",
        targetCredits = null,
        mappedRawAttributes = setOf("公选", "通识选修", "公共选修", "通选", "公共选修课"),
        sortOrder = 2,
    ),
    CreditCategoryPlan(
        categoryId = "practice",
        displayName = "实践 / 实习 / 毕业设计",
        targetCredits = null,
        mappedRawAttributes = setOf("实践", "集中实践", "实习", "课程设计", "毕业设计", "毕业论文", "实验", "集中性实践教学"),
        sortOrder = 3,
    ),
)

/** 教务培养方案中单门课程的修读情况状态。 */
enum class CurriculumStudyStatus(val label: String) {
    COMPLETED("已修读"),
    IN_PROGRESS("修读中"),
    PENDING_STUDY("待修读"),
    UNKNOWN("未知");

    companion object {
        fun fromRawText(raw: String): CurriculumStudyStatus =
            when (raw.trim()) {
                "已修读" -> COMPLETED
                "修读中", "正修读" -> IN_PROGRESS
                "待修读", "未修读" -> PENDING_STUDY
                else -> UNKNOWN
            }
    }
}

/** 教务培养方案及学业完成情况（`xxwcqkOnkclb.do`）单门课程明细。 */
data class CurriculumCourseItem(
    val courseCode: String,
    val courseName: String,
    val credit: BigDecimal,
    val creditText: String,
    val courseAttribute: String,
    val courseNature: String,
    val studyStatus: CurriculumStudyStatus,
    val scoreText: String = "",
    val isPassed: Boolean = false,
    val remark: String = "",
)

internal fun normalizeCurriculumCourseName(raw: String): String =
    CreditRecordAnchor.normalizeName(raw)
        .replace('（', '(')
        .replace('）', ')')

private fun CurriculumStudyStatus.dedupPriority(): Int = when (this) {
    CurriculumStudyStatus.COMPLETED -> 0
    CurriculumStudyStatus.IN_PROGRESS -> 1
    CurriculumStudyStatus.PENDING_STUDY -> 2
    CurriculumStudyStatus.UNKNOWN -> 3
}

/** 按规范化课程名称去重培养方案课程列表，同名课程优先保留已修读（其次修读中、待修读）。 */
fun deduplicateCurriculumCourses(courses: List<CurriculumCourseItem>): List<CurriculumCourseItem> {
    if (courses.size <= 1) return courses
    val winnerByKey = LinkedHashMap<String, CurriculumCourseItem>()
    for (course in courses) {
        val key = normalizeCurriculumCourseName(course.courseName)
        if (key.isEmpty()) continue
        val existing = winnerByKey[key]
        if (existing == null || course.studyStatus.dedupPriority() < existing.studyStatus.dedupPriority()) {
            winnerByKey[key] = course
        }
    }
    val emittedKeys = HashSet<String>()
    return courses.filter { course ->
        val key = normalizeCurriculumCourseName(course.courseName)
        if (key.isEmpty()) {
            true
        } else if (winnerByKey[key] === course && emittedKeys.add(key)) {
            true
        } else {
            false
        }
    }
}

/** 教务培养方案按课程性质聚合的官方学分统计与课程列表。 */
data class CurriculumNatureGroup(
    val courseNature: String,
    val earnedCredits: BigDecimal = BigDecimal.ZERO,
    val inProgressCredits: BigDecimal = BigDecimal.ZERO,
    val courses: List<CurriculumCourseItem> = emptyList(),
) {
    private val effectiveCourses: List<CurriculumCourseItem>
        get() = deduplicateCurriculumCourses(courses)

    val completedCount: Int
        get() = effectiveCourses.count { it.studyStatus == CurriculumStudyStatus.COMPLETED }

    val inProgressCount: Int
        get() = effectiveCourses.count { it.studyStatus == CurriculumStudyStatus.IN_PROGRESS }

    val pendingCount: Int
        get() = effectiveCourses.count { it.studyStatus == CurriculumStudyStatus.PENDING_STUDY }

    val pendingCredits: BigDecimal
        get() = effectiveCourses
            .asSequence()
            .filter { it.studyStatus == CurriculumStudyStatus.PENDING_STUDY }
            .fold(BigDecimal.ZERO) { acc, item -> acc.add(item.credit) }
            .stripTrailingZeros()

    val totalCredits: BigDecimal
        get() = earnedCredits.add(inProgressCredits).add(pendingCredits).stripTrailingZeros()
}

/** 跨分组按课程名称去重培养方案快照中的课程列表，优先保留已修读课程。 */
fun CurriculumProgressSnapshot.deduplicateCoursesByName(): CurriculumProgressSnapshot {
    val allCourses = groups.flatMap { it.courses }
    if (allCourses.size <= 1) return this
    val winners = deduplicateCurriculumCourses(allCourses).toSet()
    val emittedKeys = HashSet<String>()
    val dedupedGroups = groups.map { group ->
        val filtered = group.courses.filter { course ->
            val key = normalizeCurriculumCourseName(course.courseName)
            if (key.isEmpty()) {
                true
            } else if (course in winners && emittedKeys.add(key)) {
                true
            } else {
                false
            }
        }
        if (filtered.size == group.courses.size) group else group.copy(courses = filtered)
    }
    return if (dedupedGroups == groups) this else copy(groups = dedupedGroups)
}

/** 教务官方培养方案及学业完成情况快照。 */
data class CurriculumProgressSnapshot(
    val planTitle: String = "",
    val totalEarnedCredits: BigDecimal = BigDecimal.ZERO,
    val totalInProgressCredits: BigDecimal = BigDecimal.ZERO,
    val groups: List<CurriculumNatureGroup> = emptyList(),
    val fetchedAtEpochMillis: Long = 0L,
) {
    val totalPendingCredits: BigDecimal
        get() = groups.fold(BigDecimal.ZERO) { acc, g -> acc.add(g.pendingCredits) }.stripTrailingZeros()

    val totalCredits: BigDecimal
        get() = totalEarnedCredits.add(totalInProgressCredits).add(totalPendingCredits).stripTrailingZeros()
}

/** 单个账号的学分目标、分类配置、备注、人工消歧规则及教务培养方案快照。 */
data class CreditAccountPlan(
    val accountKey: String = "",
    val schoolId: String = "YGU",
    val totalTargetCredits: BigDecimal? = null,
    val userNote: String = "",
    val categories: List<CreditCategoryPlan> = defaultCreditCategories(),
    val rules: List<CreditResolutionRule> = emptyList(),
    val updatedAtEpochMillis: Long = 0L,
    val curriculumSnapshot: CurriculumProgressSnapshot? = null,
)

/** 全培养周期成绩数据覆盖完备性状态。 */
enum class CreditCoverageStatus {
    /** 从未查询过任何成绩或退出后缓存已清除。 */
    NOT_QUERIED,
    /** 仅存在部分单学期缓存，缺失全学期快照。 */
    PARTIAL_SEMESTER_ONLY,
    /** 逐学期补查超出学校学期数上限，存在截断。 */
    TRUNCATED_SEMESTER_LIST,
    /** “全部学期”直查返回空结果且未逐学期确认。 */
    DIRECT_ALL_EMPTY_UNVERIFIED,
    /** 全学期缓存之后又单独刷新了局部学期，整体新鲜度已失效。 */
    INVALIDATED_BY_PARTIAL_UPDATE,
    /** 缓存来自数据库 v6 及更早版本，缺少真实零学分区分或原始属性三字段，需更新全部学期。 */
    LEGACY_CACHE_NEEDS_REFRESH,
    /** 学校提供的全部学期已逐一或整表核实并完整入库。 */
    FULL_CURRICULUM_VERIFIED,
}

/** 课程进入待确认清单的原因。 */
enum class PendingReason {
    MISSING_COURSE_CODE_DUPLICATE_NAME,
    SAME_CODE_DIFF_NAME_OR_CREDIT,
    SAME_TERM_DUPLICATE_RECORDS,
    SCORE_PENDING_CONFIRMATION,
    CREDIT_VALUE_ABNORMAL,
    LEGACY_ATTRIBUTE_UNKNOWN,
    MULTI_MAPPED_COURSE_ATTRIBUTE,
    RULE_DRIFT_OR_POST_RESTORE,
}

/** 课程进入“未通过或不计入”清单的原因。 */
enum class NotCountedReason {
    FAILED_NO_RETAKE_PASS,
    STATUS_CODE_NOT_FINISHED,
    EXCLUDED_BY_USER_RULE,
}

data class CountedCourseGroup(
    val groupKey: String,
    val courseCode: String,
    val courseName: String,
    val countedRecord: GradeRecord,
    val earnedCredits: BigDecimal,
    val categoryId: String,
    val categoryName: String,
    val historyRecords: List<GradeRecord> = emptyList(),
    val isRetakeMerged: Boolean = false,
    val isManualConfirmed: Boolean = false,
    val detailText: String = "",
)

data class NotCountedCourseGroup(
    val groupKey: String,
    val courseCode: String,
    val courseName: String,
    val representativeRecord: GradeRecord,
    val records: List<GradeRecord>,
    val reason: NotCountedReason,
    val detailText: String,
    val isManualConfirmed: Boolean = false,
)

data class PendingCourseGroup(
    val groupKey: String,
    val courseCode: String,
    val courseName: String,
    val records: List<GradeRecord>,
    val reasons: Set<PendingReason>,
    val detailText: String,
    val suggestedRuleKey: String,
) {
    /** 仅因旧版缓存升级缺少来源标识而待刷新确认的记录，应在顶部合并提示而非逐条展开。 */
    val isLegacyRefreshOnly: Boolean
        get() = reasons.isNotEmpty() && reasons.all {
            it == PendingReason.LEGACY_ATTRIBUTE_UNKNOWN ||
                (it == PendingReason.CREDIT_VALUE_ABNORMAL && records.all { r -> r.creditStatus == CreditValueStatus.LEGACY_UNKNOWN_ZERO })
        }
}

data class CreditCategoryProgress(
    val category: CreditCategoryPlan,
    val earnedCredits: BigDecimal,
    val targetCredits: BigDecimal?,
    val remainingCredits: BigDecimal?,
    val progressRatio: Float?,
    val pendingCount: Int,
    val countedCourses: List<CountedCourseGroup>,
)

data class CreditTargetValidation(
    val hasCategoryTargets: Boolean,
    val categoryTargetSum: BigDecimal,
    val totalTargetCredits: BigDecimal?,
    val sumMismatch: Boolean,
    val difference: BigDecimal?,
)

data class CreditProgressReport(
    val accountKey: String,
    val coverageStatus: CreditCoverageStatus,
    val coveredSemesterCount: Int,
    val targetSemesterCount: Int,
    val truncatedTermCount: Int,
    val fetchedAtEpochMillis: Long?,
    val earnedTotalCredits: BigDecimal,
    val totalTargetCredits: BigDecimal?,
    val remainingTotalCredits: BigDecimal?,
    val overallProgressRatio: Float?,
    val targetValidation: CreditTargetValidation,
    val categoryProgressList: List<CreditCategoryProgress>,
    val countedCourses: List<CountedCourseGroup>,
    val notCountedCourses: List<NotCountedCourseGroup>,
    val pendingCourses: List<PendingCourseGroup>,
    val availableRawAttributes: Set<String>,
)

/** 可跨设备迁移的学分计划备份载体，使用身份匹配指纹而非明文凭据/姓名。 */
data class CreditAccountPlanBackup(
    val schoolId: String = "YGU",
    val subjectFingerprint: String,
    val totalTargetCreditsText: String? = null,
    val userNote: String = "",
    val categories: List<CreditCategoryPlanBackup> = emptyList(),
    val rules: List<CreditResolutionRuleBackup> = emptyList(),
    val updatedAtEpochMillis: Long = 0L,
    val curriculumSnapshot: CurriculumProgressSnapshot? = null,
)

data class CreditCategoryPlanBackup(
    val categoryId: String,
    val displayName: String,
    val targetCreditsText: String? = null,
    val mappedRawAttributes: List<String> = emptyList(),
    val sortOrder: Int = 0,
)

data class CreditSplitAssignmentBackup(
    val anchorKey: String,
    val occurrenceIndex: Int = 0,
    val customGroupKey: String,
    val assignedCategoryId: String? = null,
    val expectedFingerprint: String = "",
    val exclude: Boolean = false,
    val manualPassConfirmed: Boolean = false,
)

data class CreditResolutionRuleBackup(
    val ruleKey: String,
    val action: CreditResolutionAction,
    val assignedCategoryId: String? = null,
    val chosenAnchorKey: String? = null,
    val splitAssignments: List<CreditSplitAssignmentBackup> = emptyList(),
    val expectedFingerprints: List<String> = emptyList(),
    val manualPassConfirmed: Boolean = false,
    val note: String = "",
    val updatedAtEpochMillis: Long = 0L,
)

data class CreditBackupConflict(
    val matchedAccountKey: String,
    val schoolId: String,
    val subjectFingerprint: String,
    val localUpdatedAtEpochMillis: Long,
    val backupUpdatedAtEpochMillis: Long,
    val localSummary: String,
    val backupSummary: String,
)

data class CreditBackupPreview(
    val matchedPlansCount: Int = 0,
    val unmatchedPendingPlansCount: Int = 0,
    val conflicts: List<CreditBackupConflict> = emptyList(),
)
