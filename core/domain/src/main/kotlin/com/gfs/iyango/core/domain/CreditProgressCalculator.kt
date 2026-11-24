package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.CREDIT_ZERO
import com.gfs.iyango.core.model.CountedCourseGroup
import com.gfs.iyango.core.model.CreditAccountPlan
import com.gfs.iyango.core.model.CreditCategoryPlan
import com.gfs.iyango.core.model.CreditCategoryProgress
import com.gfs.iyango.core.model.CreditCoverageStatus
import com.gfs.iyango.core.model.CreditPassThreshold
import com.gfs.iyango.core.model.CreditPassThreshold.ScoreEvaluation
import com.gfs.iyango.core.model.CreditProgressReport
import com.gfs.iyango.core.model.CreditRecordAnchor
import com.gfs.iyango.core.model.CreditResolutionAction
import com.gfs.iyango.core.model.CreditResolutionRule
import com.gfs.iyango.core.model.CreditSplitAssignment
import com.gfs.iyango.core.model.CreditTargetValidation
import com.gfs.iyango.core.model.CreditValueStatus
import com.gfs.iyango.core.model.CurriculumProgressSnapshot
import com.gfs.iyango.core.model.GradeRecord
import com.gfs.iyango.core.model.NotCountedCourseGroup
import com.gfs.iyango.core.model.NotCountedReason
import com.gfs.iyango.core.model.PendingCourseGroup
import com.gfs.iyango.core.model.PendingReason
import com.gfs.iyango.core.model.ResolvedRecordCredit
import com.gfs.iyango.core.model.UNCLASSIFIED_CATEGORY_ID
import com.gfs.iyango.core.model.deduplicateCoursesByName
import com.gfs.iyango.core.model.formatCreditBigDecimal
import com.gfs.iyango.core.model.resolveRecordCredit
import com.gfs.iyango.core.model.toCreditScale
import java.math.BigDecimal

/**
 * 纯领域学分完成进度计算器。
 *
 * - 全程使用 [BigDecimal]（scale = 2, HALF_UP），禁止用二进制浮点累计或比较；
 * - 不修改或写入学校成绩原文，人工消歧只在规则投影中生效；
 * - 不复用成绩统计器的 `includeOptional`/重修不去重口径。
 */
object CreditProgressCalculator {

    private val LEVEL_TIE_BREAK_RANK = mapOf(
        "优秀" to 95.0,
        "优" to 95.0,
        "良好" to 85.0,
        "良" to 85.0,
        "中等" to 75.0,
        "中" to 75.0,
        "及格" to 65.0,
        "合格" to 65.0,
    )

    fun bucketFingerprintsByRuleKey(records: List<GradeRecord>): Map<String, List<String>> =
        buildRawBuckets(records).associate { bucket ->
            bucket.ruleKey to bucket.records.map { CreditRecordAnchor.buildContentFingerprint(it) }.sorted()
        }

    fun calculate(
        source: CreditGradeSourceSnapshot,
        plan: CreditAccountPlan,
    ): CreditProgressReport {
        val coverage = resolveCoverage(source)
        val records = source.allSemesterRecords
        val rulesByKey = plan.rules.associateBy { it.ruleKey }
        val categories = plan.categories.sortedBy { it.sortOrder }
        val categoriesById = categories.associateBy { it.categoryId }
        val curriculum = plan.curriculumSnapshot?.deduplicateCoursesByName()
        val curriculumLookup = buildCurriculumNatureLookup(curriculum)

        val countedCourses = mutableListOf<CountedCourseGroup>()
        val notCountedCourses = mutableListOf<NotCountedCourseGroup>()
        val pendingCourses = mutableListOf<PendingCourseGroup>()

        val buckets = buildRawBuckets(records)
        for (bucket in buckets) {
            processBucket(
                bucket = bucket,
                rule = rulesByKey[bucket.ruleKey],
                categories = categories,
                categoriesById = categoriesById,
                curriculumLookup = curriculumLookup,
                countedOut = countedCourses,
                notCountedOut = notCountedCourses,
                pendingOut = pendingCourses,
            )
        }

        val countedTotalCredits = countedCourses
            .fold(CREDIT_ZERO) { acc, item -> (acc + item.earnedCredits).toCreditScale() }
        val earnedTotalCredits = if (coverage.status == CreditCoverageStatus.NOT_QUERIED && countedCourses.isEmpty() && curriculum != null) {
            curriculum.totalEarnedCredits.toCreditScale()
        } else {
            countedTotalCredits
        }

        val totalTarget = plan.totalTargetCredits?.toCreditScale()
            ?: curriculum?.totalCredits?.takeIf { it > BigDecimal.ZERO }?.toCreditScale()
        val remainingTotal = totalTarget?.let { target ->
            (target - earnedTotalCredits).coerceAtLeast(CREDIT_ZERO).toCreditScale()
        }
        val overallRatio = computeRatio(earnedTotalCredits, totalTarget)

        val categoryTargets = categories.mapNotNull { it.targetCredits?.toCreditScale() }
        val categoryTargetSum = categoryTargets
            .fold(CREDIT_ZERO) { acc, item -> (acc + item).toCreditScale() }
        val sumMismatch = totalTarget != null &&
            categoryTargets.isNotEmpty() &&
            categoryTargetSum.compareTo(totalTarget) != 0
        val targetValidation = CreditTargetValidation(
            hasCategoryTargets = categoryTargets.isNotEmpty(),
            categoryTargetSum = categoryTargetSum,
            totalTargetCredits = totalTarget,
            sumMismatch = sumMismatch,
            difference = totalTarget?.takeIf { sumMismatch }?.let { (it - categoryTargetSum).toCreditScale() },
        )

        val categoryProgressList = mutableListOf<CreditCategoryProgress>()
        for (category in categories) {
            val matchedCourses = countedCourses.filter { it.categoryId == category.categoryId }
            val matchedCurriculumGroups = curriculum?.groups
                ?.filter { it.courseNature in category.mappedRawAttributes }
                .orEmpty()
            val catEarned = if (coverage.status == CreditCoverageStatus.NOT_QUERIED && matchedCourses.isEmpty() && matchedCurriculumGroups.isNotEmpty()) {
                matchedCurriculumGroups.fold(CREDIT_ZERO) { acc, item -> (acc + item.earnedCredits).toCreditScale() }
            } else {
                matchedCourses.fold(CREDIT_ZERO) { acc, item -> (acc + item.earnedCredits).toCreditScale() }
            }
            val catTarget = category.targetCredits?.toCreditScale()
                ?: matchedCurriculumGroups
                    .takeIf { it.isNotEmpty() }
                    ?.fold(CREDIT_ZERO) { acc, item -> (acc + item.totalCredits).toCreditScale() }
                    ?.takeIf { it > BigDecimal.ZERO }
            val catRemaining = catTarget?.let { target ->
                (target - catEarned).coerceAtLeast(CREDIT_ZERO).toCreditScale()
            }
            val pendingForCategory = pendingCourses.count { pending ->
                pending.records.any { r ->
                    curriculumLookup.resolveRawAttribute(r, categories) in category.mappedRawAttributes
                }
            }
            categoryProgressList += CreditCategoryProgress(
                category = category,
                earnedCredits = catEarned,
                targetCredits = catTarget,
                remainingCredits = catRemaining,
                progressRatio = computeRatio(catEarned, catTarget),
                pendingCount = pendingForCategory,
                countedCourses = matchedCourses,
            )
        }

        val unclassifiedCourses = countedCourses.filter { it.categoryId == UNCLASSIFIED_CATEGORY_ID }
        if (unclassifiedCourses.isNotEmpty()) {
            val unclassifiedEarned = unclassifiedCourses
                .fold(CREDIT_ZERO) { acc, item -> (acc + item.earnedCredits).toCreditScale() }
            categoryProgressList += CreditCategoryProgress(
                category = CreditCategoryPlan(
                    categoryId = UNCLASSIFIED_CATEGORY_ID,
                    displayName = "未分类",
                    targetCredits = null,
                    mappedRawAttributes = emptySet(),
                    sortOrder = Int.MAX_VALUE,
                ),
                earnedCredits = unclassifiedEarned,
                targetCredits = null,
                remainingCredits = null,
                progressRatio = null,
                pendingCount = 0,
                countedCourses = unclassifiedCourses,
            )
        }

        val availableRawAttributes = records
            .filter { it.rawAttributesKnown }
            .flatMap { r ->
                listOf(
                    CreditRecordAnchor.primaryRawAttribute(r),
                    r.rawCourseAttribute.trim(),
                    r.rawCourseType.trim(),
                )
            }
            .filter { it.isNotBlank() }
            .toSortedSet()

        return CreditProgressReport(
            accountKey = source.accountKey.ifBlank { plan.accountKey },
            coverageStatus = coverage.status,
            coveredSemesterCount = coverage.coveredCount,
            targetSemesterCount = coverage.targetCount,
            truncatedTermCount = coverage.truncatedCount,
            fetchedAtEpochMillis = source.allSemesterSnapshot?.fetchedAt,
            earnedTotalCredits = earnedTotalCredits,
            totalTargetCredits = totalTarget,
            remainingTotalCredits = remainingTotal,
            overallProgressRatio = overallRatio,
            targetValidation = targetValidation,
            categoryProgressList = categoryProgressList,
            countedCourses = countedCourses,
            notCountedCourses = notCountedCourses,
            pendingCourses = pendingCourses,
            availableRawAttributes = availableRawAttributes,
        )
    }

    private data class CoverageSummary(
        val status: CreditCoverageStatus,
        val coveredCount: Int,
        val targetCount: Int,
        val truncatedCount: Int,
    )

    private fun resolveCoverage(source: CreditGradeSourceSnapshot): CoverageSummary {
        val snapshot = source.allSemesterSnapshot
            ?: return if (source.hasAnySingleSemesterSnapshot) {
                CoverageSummary(CreditCoverageStatus.PARTIAL_SEMESTER_ONLY, 0, 0, 0)
            } else {
                CoverageSummary(CreditCoverageStatus.NOT_QUERIED, 0, 0, 0)
            }

        val targetSet = snapshot.targetSemesters.filter { it.isNotBlank() }.toSet()
        val queriedSet = snapshot.queriedSemesters.filter { it.isNotBlank() }.toSet()
        val hasLegacyRecords = source.allSemesterRecords.any {
            !it.rawAttributesKnown || it.creditStatus == CreditValueStatus.LEGACY_UNKNOWN_ZERO
        }

        val status = when {
            snapshot.invalidatedByPartialUpdate ->
                CreditCoverageStatus.INVALIDATED_BY_PARTIAL_UPDATE
            snapshot.directAllEmptyUnverified ->
                CreditCoverageStatus.DIRECT_ALL_EMPTY_UNVERIFIED
            hasLegacyRecords || (targetSet.isEmpty() && snapshot.complete) ->
                CreditCoverageStatus.LEGACY_CACHE_NEEDS_REFRESH
            snapshot.truncatedTermCount > 0 ->
                CreditCoverageStatus.TRUNCATED_SEMESTER_LIST
            targetSet != queriedSet ->
                CreditCoverageStatus.PARTIAL_SEMESTER_ONLY
            else ->
                CreditCoverageStatus.FULL_CURRICULUM_VERIFIED
        }
        return CoverageSummary(
            status = status,
            coveredCount = queriedSet.size,
            targetCount = targetSet.size,
            truncatedCount = snapshot.truncatedTermCount.coerceAtLeast(0),
        )
    }

    private data class RawBucket(
        val ruleKey: String,
        val courseCode: String,
        val displayCourseName: String,
        val records: List<GradeRecord>,
        val hasBlankCodeAmbiguity: Boolean,
    )

    private fun buildRawBuckets(records: List<GradeRecord>): List<RawBucket> {
        val allNameCounts = HashMap<String, Int>()
        records.forEach { record ->
            val normName = CreditRecordAnchor.normalizeName(record.courseName)
            allNameCounts[normName] = (allNameCounts[normName] ?: 0) + 1
        }

        val grouped = LinkedHashMap<String, MutableList<GradeRecord>>()
        val ambiguousKeys = HashSet<String>()

        for (record in records) {
            val code = CreditRecordAnchor.normalizeCode(record.courseCode)
            val normName = CreditRecordAnchor.normalizeName(record.courseName)
            val bucketKey = when {
                code.isNotBlank() -> "code:$code"
                (allNameCounts[normName] ?: 0) > 1 -> {
                    "name:$normName".also { ambiguousKeys += it }
                }
                else -> "nocode:${CreditRecordAnchor.buildAnchorKey(record)}"
            }
            grouped.getOrPut(bucketKey) { mutableListOf() }.add(record)
        }

        return grouped.map { (key, list) ->
            val first = list.first()
            RawBucket(
                ruleKey = key,
                courseCode = CreditRecordAnchor.normalizeCode(first.courseCode),
                displayCourseName = CreditRecordAnchor.normalizeName(first.courseName),
                records = list,
                hasBlankCodeAmbiguity = key in ambiguousKeys,
            )
        }
    }

    private class CurriculumNatureLookup(
        private val byCode: Map<String, String>,
        private val byName: Map<String, String>,
    ) {
        fun resolveRawAttribute(record: GradeRecord, categories: List<CreditCategoryPlan>): String {
            val code = CreditRecordAnchor.normalizeCode(record.courseCode)
            val name = CreditRecordAnchor.normalizeName(record.courseName)
            val nature = (if (code.isNotEmpty()) byCode[code] else null) ?: byName[name]
            if (!nature.isNullOrBlank() && categories.any { nature in it.mappedRawAttributes }) {
                return nature
            }
            return CreditRecordAnchor.primaryRawAttribute(record)
        }
    }

    private fun buildCurriculumNatureLookup(
        snapshot: CurriculumProgressSnapshot?,
    ): CurriculumNatureLookup {
        if (snapshot == null) return CurriculumNatureLookup(emptyMap(), emptyMap())
        val byCode = LinkedHashMap<String, String>()
        val namesGrouped = LinkedHashMap<String, MutableSet<String>>()
        for (group in snapshot.groups) {
            for (course in group.courses) {
                val nature = course.courseNature.ifBlank { group.courseNature }.trim()
                if (nature.isEmpty()) continue
                val code = CreditRecordAnchor.normalizeCode(course.courseCode)
                if (code.isNotEmpty()) {
                    byCode.putIfAbsent(code, nature)
                }
                val name = CreditRecordAnchor.normalizeName(course.courseName)
                if (name.isNotEmpty()) {
                    namesGrouped.getOrPut(name) { linkedSetOf() }.add(nature)
                }
            }
        }
        val byName = namesGrouped.mapNotNull { (name, natures) ->
            natures.singleOrNull()?.let { name to it }
        }.toMap()
        return CurriculumNatureLookup(byCode = byCode, byName = byName)
    }

    private fun processBucket(
        bucket: RawBucket,
        rule: CreditResolutionRule?,
        categories: List<CreditCategoryPlan>,
        categoriesById: Map<String, CreditCategoryPlan>,
        curriculumLookup: CurriculumNatureLookup,
        countedOut: MutableList<CountedCourseGroup>,
        notCountedOut: MutableList<NotCountedCourseGroup>,
        pendingOut: MutableList<PendingCourseGroup>,
    ) {
        val currentFingerprints = bucket.records
            .map { CreditRecordAnchor.buildContentFingerprint(it) }
            .sorted()

        val hasRuleDrift = rule != null && (
            rule.requiresPostRestoreVerification ||
                (rule.expectedFingerprints.isNotEmpty() &&
                    rule.expectedFingerprints.sorted() != currentFingerprints)
            )
        val activeRule = rule?.takeUnless { hasRuleDrift }

        if (hasRuleDrift) {
            val message = if (rule.requiresPostRestoreVerification) {
                "来自备份的人工规则尚未完成来源成绩核对，暂不计入"
            } else {
                "来源成绩或属性已发生变化，原人工规则已失效，请重新确认"
            }
            pendingOut += PendingCourseGroup(
                groupKey = bucket.ruleKey,
                courseCode = bucket.courseCode,
                courseName = bucket.displayCourseName,
                records = bucket.records,
                reasons = setOf(PendingReason.RULE_DRIFT_OR_POST_RESTORE),
                detailText = message,
                suggestedRuleKey = bucket.ruleKey,
            )
            return
        }

        if (activeRule?.action == CreditResolutionAction.EXCLUDE_FROM_PLAN) {
            val rep = bucket.records.first()
            notCountedOut += NotCountedCourseGroup(
                groupKey = bucket.ruleKey,
                courseCode = bucket.courseCode,
                courseName = bucket.displayCourseName,
                representativeRecord = rep,
                records = bucket.records,
                reason = NotCountedReason.EXCLUDED_BY_USER_RULE,
                detailText = "人工确认：不计入当前学分进度",
                isManualConfirmed = true,
            )
            return
        }

        if (activeRule?.action == CreditResolutionAction.SPLIT_INTO_SEPARATE_COURSES) {
            val splitGroups = applySplitRule(bucket.records, activeRule.splitAssignments)
            if (splitGroups == null) {
                pendingOut += PendingCourseGroup(
                    groupKey = bucket.ruleKey,
                    courseCode = bucket.courseCode,
                    courseName = bucket.displayCourseName,
                    records = bucket.records,
                    reasons = setOf(PendingReason.RULE_DRIFT_OR_POST_RESTORE),
                    detailText = "拆分规则与当前成绩记录不匹配，请重新确认",
                    suggestedRuleKey = bucket.ruleKey,
                )
                return
            }
            for ((subKey, pair) in splitGroups) {
                val (subRecords, assignment) = pair
                if (assignment.exclude) {
                    notCountedOut += NotCountedCourseGroup(
                        groupKey = subKey,
                        courseCode = CreditRecordAnchor.normalizeCode(subRecords.first().courseCode),
                        courseName = CreditRecordAnchor.normalizeName(subRecords.first().courseName),
                        representativeRecord = subRecords.first(),
                        records = subRecords,
                        reason = NotCountedReason.EXCLUDED_BY_USER_RULE,
                        detailText = "人工确认：拆分后该条不计入",
                        isManualConfirmed = true,
                    )
                } else {
                    evaluateUnitGroup(
                        groupKey = subKey,
                        suggestedRuleKey = bucket.ruleKey,
                        courseCode = CreditRecordAnchor.normalizeCode(subRecords.first().courseCode),
                        displayCourseName = CreditRecordAnchor.normalizeName(subRecords.first().courseName),
                        records = subRecords,
                        hasBlankCodeAmbiguity = false,
                        activeRule = activeRule,
                        splitAssignment = assignment,
                        categories = categories,
                        categoriesById = categoriesById,
                        curriculumLookup = curriculumLookup,
                        countedOut = countedOut,
                        notCountedOut = notCountedOut,
                        pendingOut = pendingOut,
                    )
                }
            }
            return
        }

        evaluateUnitGroup(
            groupKey = bucket.ruleKey,
            suggestedRuleKey = bucket.ruleKey,
            courseCode = bucket.courseCode,
            displayCourseName = bucket.displayCourseName,
            records = bucket.records,
            hasBlankCodeAmbiguity = bucket.hasBlankCodeAmbiguity,
            activeRule = activeRule,
            splitAssignment = null,
            categories = categories,
            categoriesById = categoriesById,
            curriculumLookup = curriculumLookup,
            countedOut = countedOut,
            notCountedOut = notCountedOut,
            pendingOut = pendingOut,
        )
    }

    private fun applySplitRule(
        records: List<GradeRecord>,
        assignments: List<CreditSplitAssignment>,
    ): Map<String, Pair<List<GradeRecord>, CreditSplitAssignment>>? {
        if (assignments.size != records.size) return null
        val remaining = records.toMutableList()
        val result = LinkedHashMap<String, Pair<List<GradeRecord>, CreditSplitAssignment>>()

        // Prefer exact contentFingerprint match first so swapped school rows never mis-bind.
        val unmatchedAssignments = mutableListOf<CreditSplitAssignment>()
        for (assignment in assignments) {
            val matchedIndex = if (assignment.expectedFingerprint.isNotBlank()) {
                remaining.indexOfFirst {
                    CreditRecordAnchor.buildContentFingerprint(it) == assignment.expectedFingerprint
                }
            } else {
                -1
            }
            if (matchedIndex >= 0) {
                val rec = remaining.removeAt(matchedIndex)
                val key = assignment.customGroupKey.ifBlank { "split:${assignment.anchorKey}:${result.size}" }
                result[key] = listOf(rec) to assignment
            } else if (assignment.expectedFingerprint.isNotBlank()) {
                return null
            } else {
                unmatchedAssignments += assignment
            }
        }
        for (assignment in unmatchedAssignments) {
            val idx = remaining.indexOfFirst {
                CreditRecordAnchor.buildAnchorKey(it) == assignment.anchorKey
            }
            if (idx < 0) return null
            val rec = remaining.removeAt(idx)
            val key = assignment.customGroupKey.ifBlank { "split:${assignment.anchorKey}:${result.size}" }
            result[key] = listOf(rec) to assignment
        }
        if (remaining.isNotEmpty()) return null
        return result
    }

    private fun evaluateUnitGroup(
        groupKey: String,
        suggestedRuleKey: String,
        courseCode: String,
        displayCourseName: String,
        records: List<GradeRecord>,
        hasBlankCodeAmbiguity: Boolean,
        activeRule: CreditResolutionRule?,
        splitAssignment: CreditSplitAssignment?,
        categories: List<CreditCategoryPlan>,
        categoriesById: Map<String, CreditCategoryPlan>,
        curriculumLookup: CurriculumNatureLookup,
        countedOut: MutableList<CountedCourseGroup>,
        notCountedOut: MutableList<NotCountedCourseGroup>,
        pendingOut: MutableList<PendingCourseGroup>,
    ) {
        val pendingReasons = linkedSetOf<PendingReason>()
        val pendingNotes = mutableListOf<String>()

        val hasMergeRule = activeRule?.action == CreditResolutionAction.MERGE_AS_SINGLE_COURSE
        val isManual = activeRule != null
        val chosenAnchor = activeRule?.chosenAnchorKey?.takeIf { it.isNotBlank() }

        if (hasBlankCodeAmbiguity && !hasMergeRule) {
            pendingReasons += PendingReason.MISSING_COURSE_CODE_DUPLICATE_NAME
            pendingNotes += "缺失课程号且存在同名记录，请确认是否同课重修或独立课程"
        }

        val distinctNames = records.map { CreditRecordAnchor.normalizeName(it.courseName) }.distinct()
        if (distinctNames.size > 1 && !hasMergeRule) {
            pendingReasons += PendingReason.SAME_CODE_DIFF_NAME_OR_CREDIT
            pendingNotes += "同一课程号对应多个课程名称（${distinctNames.joinToString(" / ")}）"
        }

        if (records.size > 1 && !hasMergeRule && hasAmbiguousSameTermDuplicates(records)) {
            pendingReasons += PendingReason.SAME_TERM_DUPLICATE_RECORDS
            pendingNotes += "同一学期出现疑似重复记录，请确认仅计一次或拆分"
        }

        // Validate chosenAnchorKey if provided.
        val chosenRecords = if (chosenAnchor != null) {
            val matching = records.filter { CreditRecordAnchor.buildAnchorKey(it) == chosenAnchor }
            if (matching.isEmpty()) {
                pendingReasons += PendingReason.RULE_DRIFT_OR_POST_RESTORE
                pendingNotes += "指定的来源记录已变更，请重新确认"
                emptyList()
            } else {
                matching
            }
        } else {
            emptyList()
        }

        val creditSourceRecords = if (chosenRecords.isNotEmpty()) chosenRecords else records
        val resolvedCredits = creditSourceRecords.map { it to resolveRecordCredit(it) }
        val legacyZero = resolvedCredits.any { it.second is ResolvedRecordCredit.LegacyUnknownZero }
        val invalidCredits = resolvedCredits.mapNotNull { it.second as? ResolvedRecordCredit.InvalidOrMissing }
        val validCredits = resolvedCredits
            .mapNotNull { (it.second as? ResolvedRecordCredit.Valid)?.value }
            .distinct()

        if (legacyZero || invalidCredits.isNotEmpty()) {
            pendingReasons += PendingReason.CREDIT_VALUE_ABNORMAL
            pendingNotes += if (legacyZero) {
                "旧缓存零学分无法区分真实零与缺失，需更新全部学期"
            } else {
                invalidCredits.joinToString("；") { it.detail }
            }
        } else if (validCredits.size > 1 && chosenAnchor == null) {
            pendingReasons += PendingReason.SAME_CODE_DIFF_NAME_OR_CREDIT
            pendingNotes += "同课多条记录学分不一致（${validCredits.joinToString(" / ") { formatCreditBigDecimal(it) }}）"
        }

        // Evaluate scores for all records in group.
        val evaluations = records.map { it to CreditPassThreshold.evaluateScore(it) }
        val passedRecords = evaluations.mapNotNull { (r, ev) -> if (ev is ScoreEvaluation.Passed) r else null }
        val failedRecords = evaluations.mapNotNull { (r, ev) -> if (ev is ScoreEvaluation.Failed) r else null }
        val statusNotFinishedRecords = evaluations.mapNotNull { (r, ev) -> if (ev is ScoreEvaluation.StatusNotFinished) r to ev.statusText else null }
        val pendingScoreEvaluations = evaluations.mapNotNull { (r, ev) -> if (ev is ScoreEvaluation.PendingConfirmation) r to ev.reasonText else null }

        val manualPassConfirmed = splitAssignment?.manualPassConfirmed == true ||
            (hasMergeRule && activeRule.manualPassConfirmed)

        // Check category & attribute state.
        val explicitCategoryId = splitAssignment?.assignedCategoryId ?: activeRule?.assignedCategoryId
        val explicitCategory = explicitCategoryId?.let { categoriesById[it] }
        if (explicitCategoryId != null && explicitCategory == null) {
            pendingReasons += PendingReason.RULE_DRIFT_OR_POST_RESTORE
            pendingNotes += "所选目标分类已删除，请重新归类"
        }

        val anyUnknownRawAttrs = records.any { !it.rawAttributesKnown }
        if (anyUnknownRawAttrs && explicitCategory == null) {
            pendingReasons += PendingReason.LEGACY_ATTRIBUTE_UNKNOWN
            pendingNotes += "旧缓存缺少原始课程属性，请更新全部学期成绩"
        }

        var resolvedCategoryId: String? = explicitCategory?.categoryId
        var resolvedCategoryName: String? = explicitCategory?.displayName

        if (resolvedCategoryId == null && !anyUnknownRawAttrs && (passedRecords.isNotEmpty() || manualPassConfirmed)) {
            val rawAttrs = records.map { curriculumLookup.resolveRawAttribute(it, categories) }.distinct()
            if (rawAttrs.size > 1) {
                pendingReasons += PendingReason.MULTI_MAPPED_COURSE_ATTRIBUTE
                pendingNotes += "同组记录的课程属性不一致（${rawAttrs.joinToString(" / ") { it.ifEmpty { "空" } }}），请指定归属分类"
            } else {
                val attrKey = rawAttrs.singleOrNull().orEmpty()
                val matchedCategories = if (attrKey.isBlank()) {
                    emptyList()
                } else {
                    categories.filter { attrKey in it.mappedRawAttributes }
                }
                when {
                    matchedCategories.size > 1 -> {
                        pendingReasons += PendingReason.MULTI_MAPPED_COURSE_ATTRIBUTE
                        pendingNotes += "课程属性「$attrKey」同时映射了多个分类，请先解决映射冲突"
                    }
                    matchedCategories.size == 1 -> {
                        resolvedCategoryId = matchedCategories.single().categoryId
                        resolvedCategoryName = matchedCategories.single().displayName
                    }
                    else -> {
                        resolvedCategoryId = UNCLASSIFIED_CATEGORY_ID
                        resolvedCategoryName = "未分类"
                    }
                }
            }
        }

        // Decide outcome:
        // 1) If no passed records and not manually confirmed pass:
        if (passedRecords.isEmpty() && !manualPassConfirmed) {
            if (pendingScoreEvaluations.isNotEmpty()) {
                pendingReasons += PendingReason.SCORE_PENDING_CONFIRMATION
                pendingNotes += pendingScoreEvaluations.joinToString("；") { it.second }
            }
            if (pendingReasons.isEmpty()) {
                val allStatusOnly = failedRecords.isEmpty() && statusNotFinishedRecords.isNotEmpty()
                val reason = if (allStatusOnly) {
                    NotCountedReason.STATUS_CODE_NOT_FINISHED
                } else {
                    NotCountedReason.FAILED_NO_RETAKE_PASS
                }
                val rep = pickRepresentativeRecord(records, chosenAnchor)
                val detail = when (reason) {
                    NotCountedReason.STATUS_CODE_NOT_FINISHED ->
                        "状态为「${statusNotFinishedRecords.joinToString("、") { it.second }.ifEmpty { rep.scoreText }}」，未获得学分"
                    NotCountedReason.FAILED_NO_RETAKE_PASS ->
                        if (records.size > 1) "共 ${records.size} 条记录均未通过（最近：${rep.scoreText}），未计入学分"
                        else "成绩「${rep.scoreText}」未达通过标准，未计入学分"
                    NotCountedReason.EXCLUDED_BY_USER_RULE -> "人工确认不计入"
                }
                notCountedOut += NotCountedCourseGroup(
                    groupKey = groupKey,
                    courseCode = courseCode,
                    courseName = displayCourseName,
                    representativeRecord = rep,
                    records = records,
                    reason = reason,
                    detailText = detail,
                    isManualConfirmed = isManual,
                )
                return
            }
        } else if (passedRecords.isEmpty() && manualPassConfirmed) {
            // Manually confirmed exemptions only clear score pending reason when credit & category are clear.
            if (resolvedCategoryId == null && explicitCategoryId == null) {
                resolvedCategoryId = UNCLASSIFIED_CATEGORY_ID
                resolvedCategoryName = "未分类"
            }
        }

        if (pendingReasons.isNotEmpty()) {
            pendingOut += PendingCourseGroup(
                groupKey = groupKey,
                courseCode = courseCode,
                courseName = displayCourseName,
                records = records,
                reasons = pendingReasons,
                detailText = pendingNotes.distinct().joinToString("；"),
                suggestedRuleKey = suggestedRuleKey,
            )
            return
        }

        val countedCandidates = if (chosenRecords.isNotEmpty()) {
            val chosenPassed = chosenRecords.filter { CreditPassThreshold.evaluateScore(it) is ScoreEvaluation.Passed }
            chosenPassed.ifEmpty { chosenRecords }
        } else {
            passedRecords.ifEmpty { records }
        }

        val countedRecord = pickRepresentativeRecord(countedCandidates, chosenAnchor)
        val earnedCredit = validCredits.singleOrNull() ?: CREDIT_ZERO
        val retakeMerged = records.size > 1
        val history = records.sortedWith(compareBy<GradeRecord> { it.semester }.thenBy { it.rowOrder }.thenBy { it.id })
        val detail = buildString {
            if (retakeMerged) {
                val failNote = if (failedRecords.isNotEmpty()) {
                    "含 ${failedRecords.size} 条未通过记录，"
                } else if (passedRecords.size > 1) {
                    "共 ${passedRecords.size} 条通过记录，"
                } else {
                    ""
                }
                append("${failNote}按通过记录「成绩 ${countedRecord.scoreText}」仅计一次")
            } else {
                append("成绩 ${countedRecord.scoreText}")
            }
            if (isManual || manualPassConfirmed) {
                append("（人工确认）")
            }
        }

        countedOut += CountedCourseGroup(
            groupKey = groupKey,
            courseCode = courseCode,
            courseName = CreditRecordAnchor.normalizeName(countedRecord.courseName),
            countedRecord = countedRecord,
            earnedCredits = earnedCredit,
            categoryId = resolvedCategoryId ?: UNCLASSIFIED_CATEGORY_ID,
            categoryName = resolvedCategoryName ?: "未分类",
            historyRecords = history,
            isRetakeMerged = retakeMerged,
            isManualConfirmed = isManual || manualPassConfirmed,
            detailText = detail,
        )
    }

    private fun hasAmbiguousSameTermDuplicates(records: List<GradeRecord>): Boolean {
        val byTerm = records.groupBy { it.semester.trim() }
        for ((_, sameTermRows) in byTerm) {
            if (sameTermRows.size <= 1) continue
            val evals = sameTermRows.map { CreditPassThreshold.evaluateScore(it) }
            val passCount = evals.count { it is ScoreEvaluation.Passed }
            // Within the same semester: 2+ passing rows is ambiguous duplicate;
            // or multiple rows with identical examType (e.g., two 正常考试 rows) is ambiguous.
            if (passCount > 1) return true
            val distinctExamTypes = sameTermRows.map { it.examType.trim() }.distinct()
            if (distinctExamTypes.size < sameTermRows.size) return true
        }
        return false
    }

    private fun pickRepresentativeRecord(
        candidates: List<GradeRecord>,
        chosenAnchorKey: String?,
    ): GradeRecord {
        if (chosenAnchorKey != null) {
            candidates.firstOrNull { CreditRecordAnchor.buildAnchorKey(it) == chosenAnchorKey }
                ?.let { return it }
        }
        return candidates.maxWith(
            compareBy<GradeRecord>(
                { scoreRankOf(it) },
                { it.semester.trim() },
                { it.rowOrder },
                { it.id },
            ),
        )
    }

    private fun scoreRankOf(record: GradeRecord): Double {
        return when (val eval = CreditPassThreshold.evaluateScore(record)) {
            is ScoreEvaluation.Passed -> eval.numericScore?.toDouble()
                ?: LEVEL_TIE_BREAK_RANK[CreditPassThreshold.normalizeScoreText(record.scoreText)]
                ?: 60.0
            is ScoreEvaluation.Failed -> eval.numericScore?.toDouble() ?: -1.0
            is ScoreEvaluation.StatusNotFinished -> -10.0
            is ScoreEvaluation.PendingConfirmation -> -20.0
        }
    }

    private fun computeRatio(earned: BigDecimal, target: BigDecimal?): Float? {
        if (target == null || target.compareTo(BigDecimal.ZERO) <= 0) return null
        val ratio = earned.toDouble() / target.toDouble()
        return ratio.toFloat().coerceIn(0f, 1f)
    }
}
