package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.CREDIT_ZERO
import com.gfs.iyango.core.model.CreditAccountPlan
import com.gfs.iyango.core.model.CreditCoverageStatus
import com.gfs.iyango.core.model.CreditRecordAnchor
import com.gfs.iyango.core.model.CreditResolutionAction
import com.gfs.iyango.core.model.CreditResolutionRule
import com.gfs.iyango.core.model.CreditSplitAssignment
import com.gfs.iyango.core.model.CreditValueStatus
import com.gfs.iyango.core.model.CurriculumCourseItem
import com.gfs.iyango.core.model.CurriculumNatureGroup
import com.gfs.iyango.core.model.CurriculumProgressSnapshot
import com.gfs.iyango.core.model.CurriculumStudyStatus
import com.gfs.iyango.core.model.GradeRecord
import com.gfs.iyango.core.model.GradeSnapshot
import com.gfs.iyango.core.model.NotCountedReason
import com.gfs.iyango.core.model.PendingReason
import com.gfs.iyango.core.model.UNCLASSIFIED_CATEGORY_ID
import com.gfs.iyango.core.model.defaultCreditCategories
import com.gfs.iyango.core.model.formatCreditBigDecimal
import com.gfs.iyango.core.model.parseCreditBigDecimal
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CreditProgressCalculatorTest {

    private fun sampleRecord(
        id: Long = 1L,
        semester: String = "2024-2025-1",
        courseCode: String = "CS101",
        courseName: String = "高等数学",
        credit: Double = 4.0,
        rawCreditText: String = credit.toString(),
        creditStatus: CreditValueStatus = CreditValueStatus.VALID,
        scoreText: String = "85",
        rawCourseAttribute: String = "必修",
        rawCourseType: String = "",
        rawGeneralElectiveType: String = "",
        rawAttributesKnown: Boolean = true,
        examType: String = "正常考试",
        schoolRecordId: String = "",
        rowOrder: Int = 0,
    ): GradeRecord = GradeRecord(
        id = id,
        accountKey = "2024001",
        semester = semester,
        courseName = courseName,
        courseCode = courseCode,
        credit = credit,
        rawCreditText = rawCreditText,
        creditStatus = creditStatus,
        scoreText = scoreText,
        scoreValue = null,
        gradePoint = null,
        attribute = rawCourseAttribute,
        rawCourseAttribute = rawCourseAttribute,
        rawCourseType = rawCourseType,
        rawGeneralElectiveType = rawGeneralElectiveType,
        rawAttributesKnown = rawAttributesKnown,
        examType = examType,
        schoolRecordId = schoolRecordId,
        rowOrder = rowOrder,
    )

    private fun verifiedSource(
        records: List<GradeRecord>,
        semesters: List<String> = listOf("2024-2025-1", "2024-2025-2"),
    ): CreditGradeSourceSnapshot = CreditGradeSourceSnapshot(
        accountKey = "2024001",
        allSemesterSnapshot = GradeSnapshot(
            accountKey = "2024001",
            semester = GradeRepositoryPort.ALL_SEMESTERS,
            fetchedAt = 1_000L,
            complete = true,
            targetSemesters = semesters,
            queriedSemesters = semesters,
            truncatedTermCount = 0,
            directAllEmptyUnverified = false,
            invalidatedByPartialUpdate = false,
        ),
        allSemesterRecords = records,
        hasAnySingleSemesterSnapshot = false,
    )

    @Test
    fun `retake fail then pass counts credit once and preserves both history records`() {
        val failed = sampleRecord(
            id = 1L,
            semester = "2024-2025-1",
            courseCode = "MATH101",
            courseName = "高等数学",
            rawCreditText = "4.0",
            scoreText = "55",
        )
        val passed = sampleRecord(
            id = 2L,
            semester = "2024-2025-2",
            courseCode = "MATH101",
            courseName = "高等数学",
            rawCreditText = "4.0",
            scoreText = "80",
        )

        val report = CreditProgressCalculator.calculate(
            source = verifiedSource(listOf(failed, passed)),
            plan = CreditAccountPlan(accountKey = "2024001"),
        )

        assertEquals(BigDecimal("4.00"), report.earnedTotalCredits)
        assertEquals(1, report.countedCourses.size)
        val counted = report.countedCourses.single()
        assertEquals(2, counted.historyRecords.size)
        assertTrue(counted.isRetakeMerged)
        assertEquals("80", counted.countedRecord.scoreText)
        assertTrue(report.notCountedCourses.isEmpty())
        assertTrue(report.pendingCourses.isEmpty())
    }

    @Test
    fun `two passing attempts of same courseCode still count credit once`() {
        val firstPass = sampleRecord(
            id = 1L,
            semester = "2024-2025-1",
            courseCode = "ENG101",
            courseName = "大学英语",
            rawCreditText = "3",
            scoreText = "68",
        )
        val secondPass = sampleRecord(
            id = 2L,
            semester = "2024-2025-2",
            courseCode = "ENG101",
            courseName = "大学英语",
            rawCreditText = "3",
            scoreText = "91",
        )

        val report = CreditProgressCalculator.calculate(
            source = verifiedSource(listOf(firstPass, secondPass)),
            plan = CreditAccountPlan(accountKey = "2024001"),
        )

        assertEquals(BigDecimal("3.00"), report.earnedTotalCredits)
        assertEquals(1, report.countedCourses.size)
        assertEquals("91", report.countedCourses.single().countedRecord.scoreText)
        assertEquals(2, report.countedCourses.single().historyRecords.size)
    }

    @Test
    fun `same courseName with different courseCode are counted separately`() {
        val part1 = sampleRecord(
            id = 1L,
            courseCode = "PE001",
            courseName = "体育",
            rawCreditText = "2",
            scoreText = "85",
            rawCourseAttribute = "必修",
        )
        val part2 = sampleRecord(
            id = 2L,
            courseCode = "PE002",
            courseName = "体育",
            rawCreditText = "3",
            scoreText = "88",
            rawCourseAttribute = "必修",
        )

        val report = CreditProgressCalculator.calculate(
            source = verifiedSource(listOf(part1, part2)),
            plan = CreditAccountPlan(accountKey = "2024001"),
        )

        assertEquals(BigDecimal("5.00"), report.earnedTotalCredits)
        assertEquals(2, report.countedCourses.size)
    }

    @Test
    fun `blank courseCode duplicate name goes to pending and resolves by manual merge or split`() {
        val a = sampleRecord(
            id = 1L,
            semester = "2024-2025-1",
            courseCode = "",
            courseName = "大学物理实验",
            rawCreditText = "2",
            scoreText = "82",
            rawCourseAttribute = "实践",
            schoolRecordId = "ygu:recA",
        )
        val b = sampleRecord(
            id = 2L,
            semester = "2024-2025-2",
            courseCode = "",
            courseName = "大学物理实验",
            rawCreditText = "2",
            scoreText = "90",
            rawCourseAttribute = "实践",
            schoolRecordId = "ygu:recB",
        )

        val pendingReport = CreditProgressCalculator.calculate(
            source = verifiedSource(listOf(a, b)),
            plan = CreditAccountPlan(accountKey = "2024001"),
        )
        assertEquals(CREDIT_ZERO, pendingReport.earnedTotalCredits)
        assertEquals(1, pendingReport.pendingCourses.size)
        val pending = pendingReport.pendingCourses.single()
        assertTrue(PendingReason.MISSING_COURSE_CODE_DUPLICATE_NAME in pending.reasons)

        // Merge as single course -> counted once (2 credits)
        val mergedReport = CreditProgressCalculator.calculate(
            source = verifiedSource(listOf(a, b)),
            plan = CreditAccountPlan(
                accountKey = "2024001",
                rules = listOf(
                    CreditResolutionRule(
                        ruleKey = pending.suggestedRuleKey,
                        action = CreditResolutionAction.MERGE_AS_SINGLE_COURSE,
                        assignedCategoryId = "practice",
                    ),
                ),
            ),
        )
        assertEquals(BigDecimal("2.00"), mergedReport.earnedTotalCredits)
        assertTrue(mergedReport.pendingCourses.isEmpty())

        // Split as separate courses -> 2 + 2 = 4 credits
        val splitReport = CreditProgressCalculator.calculate(
            source = verifiedSource(listOf(a, b)),
            plan = CreditAccountPlan(
                accountKey = "2024001",
                rules = listOf(
                    CreditResolutionRule(
                        ruleKey = pending.suggestedRuleKey,
                        action = CreditResolutionAction.SPLIT_INTO_SEPARATE_COURSES,
                        splitAssignments = listOf(
                            CreditSplitAssignment(
                                anchorKey = CreditRecordAnchor.buildAnchorKey(a),
                                occurrenceIndex = 0,
                                customGroupKey = "${pending.suggestedRuleKey}#1",
                                assignedCategoryId = "practice",
                                expectedFingerprint = CreditRecordAnchor.buildContentFingerprint(a),
                            ),
                            CreditSplitAssignment(
                                anchorKey = CreditRecordAnchor.buildAnchorKey(b),
                                occurrenceIndex = 1,
                                customGroupKey = "${pending.suggestedRuleKey}#2",
                                assignedCategoryId = "practice",
                                expectedFingerprint = CreditRecordAnchor.buildContentFingerprint(b),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(BigDecimal("4.00"), splitReport.earnedTotalCredits)
        assertEquals(2, splitReport.countedCourses.size)
        assertTrue(splitReport.pendingCourses.isEmpty())
    }

    @Test
    fun `retake attribute conflict enters pending and category rename keeps stable categoryId`() {
        val first = sampleRecord(
            id = 1L,
            semester = "2024-2025-1",
            courseCode = "CS201",
            courseName = "数据结构",
            rawCreditText = "3.5",
            scoreText = "52",
            rawCourseAttribute = "限选",
        )
        val retake = sampleRecord(
            id = 2L,
            semester = "2024-2025-2",
            courseCode = "CS201",
            courseName = "数据结构",
            rawCreditText = "3.5",
            scoreText = "78",
            rawCourseAttribute = "必修",
        )

        val conflictReport = CreditProgressCalculator.calculate(
            source = verifiedSource(listOf(first, retake)),
            plan = CreditAccountPlan(accountKey = "2024001"),
        )
        assertEquals(CREDIT_ZERO, conflictReport.earnedTotalCredits)
        assertEquals(1, conflictReport.pendingCourses.size)
        assertTrue(PendingReason.MULTI_MAPPED_COURSE_ATTRIBUTE in conflictReport.pendingCourses.single().reasons)

        val renamedCategories = defaultCreditCategories().map {
            if (it.categoryId == "required") {
                it.copy(displayName = "专业核心必修", targetCredits = BigDecimal("10.00"))
            } else {
                it
            }
        }
        val resolvedReport = CreditProgressCalculator.calculate(
            source = verifiedSource(listOf(first, retake)),
            plan = CreditAccountPlan(
                accountKey = "2024001",
                categories = renamedCategories,
                rules = listOf(
                    CreditResolutionRule(
                        ruleKey = conflictReport.pendingCourses.single().suggestedRuleKey,
                        action = CreditResolutionAction.MERGE_AS_SINGLE_COURSE,
                        assignedCategoryId = "required",
                    ),
                ),
            ),
        )
        assertEquals(BigDecimal("3.50"), resolvedReport.earnedTotalCredits)
        val requiredProgress = resolvedReport.categoryProgressList.first { it.category.categoryId == "required" }
        assertEquals("专业核心必修", requiredProgress.category.displayName)
        assertEquals(BigDecimal("3.50"), requiredProgress.earnedCredits)
        assertEquals(BigDecimal("6.50"), requiredProgress.remainingCredits)
    }

    @Test
    fun `score thresholds handle YGU labels status codes invalid strings and floating precision`() {
        val records = listOf(
            sampleRecord(id = 1L, courseCode = "C1", courseName = "C1", rawCreditText = "0.1", scoreText = "合格"),
            sampleRecord(id = 2L, courseCode = "C2", courseName = "C2", rawCreditText = "0.2", scoreText = "及格"),
            sampleRecord(id = 3L, courseCode = "C3", courseName = "C3", rawCreditText = "2", scoreText = "不合格"),
            sampleRecord(id = 4L, courseCode = "C4", courseName = "C4", rawCreditText = "2", scoreText = "免修"),
            sampleRecord(id = 5L, courseCode = "C5", courseName = "C5", rawCreditText = "2", scoreText = "缓考"),
            sampleRecord(id = 6L, courseCode = "C6", courseName = "C6", rawCreditText = "2", scoreText = "未出分"),
            sampleRecord(id = 7L, courseCode = "C7", courseName = "C7", rawCreditText = "2", scoreText = "缺考"),
            sampleRecord(id = 8L, courseCode = "C8", courseName = "C8", rawCreditText = "2", scoreText = "请评教"),
            sampleRecord(id = 9L, courseCode = "C9", courseName = "C9", rawCreditText = "2", scoreText = "NaN"),
            sampleRecord(id = 10L, courseCode = "C10", courseName = "C10", rawCreditText = "2", scoreText = "Infinity"),
            sampleRecord(id = 11L, courseCode = "C11", courseName = "C11", rawCreditText = "2", scoreText = "-5"),
            sampleRecord(id = 12L, courseCode = "C12", courseName = "C12", rawCreditText = "2", scoreText = "101"),
            sampleRecord(id = 13L, courseCode = "C13", courseName = "C13", rawCreditText = "0", scoreText = "优秀"),
        )

        val report = CreditProgressCalculator.calculate(
            source = verifiedSource(records),
            plan = CreditAccountPlan(accountKey = "2024001"),
        )

        // 0.1 + 0.2 + 0 = 0.30 exactly
        assertEquals(BigDecimal("0.30"), report.earnedTotalCredits)
        assertEquals("0.3", formatCreditBigDecimal(report.earnedTotalCredits))
        assertEquals(setOf("C1", "C2", "C13"), report.countedCourses.map { it.courseCode }.toSet())

        val notCountedByCode = report.notCountedCourses.associateBy { it.courseCode }
        assertEquals(NotCountedReason.FAILED_NO_RETAKE_PASS, notCountedByCode["C3"]?.reason)
        assertEquals(NotCountedReason.STATUS_CODE_NOT_FINISHED, notCountedByCode["C5"]?.reason)
        assertEquals(NotCountedReason.STATUS_CODE_NOT_FINISHED, notCountedByCode["C6"]?.reason)
        assertEquals(NotCountedReason.STATUS_CODE_NOT_FINISHED, notCountedByCode["C7"]?.reason)
        assertEquals(NotCountedReason.STATUS_CODE_NOT_FINISHED, notCountedByCode["C8"]?.reason)

        val pendingCodes = report.pendingCourses.map { it.courseCode }.toSet()
        assertEquals(setOf("C4", "C9", "C10", "C11", "C12"), pendingCodes)
    }

    @Test
    fun `credit anomalies and legacy zero enter pending while unclassified attributes contribute to total`() {
        val validUnclassified = sampleRecord(
            id = 1L,
            courseCode = "U1",
            courseName = "特种课程",
            rawCreditText = "2.5",
            scoreText = "88",
            rawCourseAttribute = "海外交流",
        )
        val missingCredit = sampleRecord(
            id = 2L,
            courseCode = "M1",
            courseName = "异常学分课",
            credit = 0.0,
            rawCreditText = "",
            creditStatus = CreditValueStatus.MISSING_OR_INVALID,
            scoreText = "80",
        )
        val legacyZero = sampleRecord(
            id = 3L,
            courseCode = "L1",
            courseName = "旧缓存课程",
            credit = 0.0,
            rawCreditText = "",
            creditStatus = CreditValueStatus.LEGACY_UNKNOWN_ZERO,
            scoreText = "90",
            rawAttributesKnown = false,
            rawCourseAttribute = "",
        )

        val report = CreditProgressCalculator.calculate(
            source = verifiedSource(listOf(validUnclassified, missingCredit, legacyZero)),
            plan = CreditAccountPlan(accountKey = "2024001"),
        )

        assertEquals(CreditCoverageStatus.LEGACY_CACHE_NEEDS_REFRESH, report.coverageStatus)
        assertEquals(BigDecimal("2.50"), report.earnedTotalCredits)
        assertEquals(1, report.countedCourses.size)
        assertEquals(UNCLASSIFIED_CATEGORY_ID, report.countedCourses.single().categoryId)
        assertTrue(report.categoryProgressList.any { it.category.categoryId == UNCLASSIFIED_CATEGORY_ID && it.earnedCredits == BigDecimal("2.50") })

        val pendingByCode = report.pendingCourses.associateBy { it.courseCode }
        assertTrue(PendingReason.CREDIT_VALUE_ABNORMAL in pendingByCode["M1"]!!.reasons)
        assertFalse(pendingByCode["M1"]!!.isLegacyRefreshOnly)
        assertTrue(pendingByCode["L1"]!!.isLegacyRefreshOnly)
    }

    @Test
    fun `target validation detects unset exceeded zero and sum mismatch states`() {
        val passed = listOf(
            sampleRecord(id = 1L, courseCode = "C1", courseName = "C1", rawCreditText = "4", scoreText = "85", rawCourseAttribute = "必修"),
        )
        val unset = CreditProgressCalculator.calculate(
            source = verifiedSource(passed),
            plan = CreditAccountPlan(accountKey = "2024001", totalTargetCredits = null),
        )
        assertNull(unset.totalTargetCredits)
        assertNull(unset.remainingTotalCredits)
        assertNull(unset.overallProgressRatio)

        val zeroTarget = CreditProgressCalculator.calculate(
            source = verifiedSource(passed),
            plan = CreditAccountPlan(accountKey = "2024001", totalTargetCredits = BigDecimal("0")),
        )
        assertEquals(BigDecimal("0.00"), zeroTarget.remainingTotalCredits)
        assertNull(zeroTarget.overallProgressRatio)

        val mismatchCategories = defaultCreditCategories().map {
            if (it.categoryId == "required") it.copy(targetCredits = BigDecimal("100")) else it.copy(targetCredits = BigDecimal("10"))
        }
        val exceededMismatch = CreditProgressCalculator.calculate(
            source = verifiedSource(passed),
            plan = CreditAccountPlan(
                accountKey = "2024001",
                totalTargetCredits = BigDecimal("3"),
                categories = mismatchCategories,
            ),
        )
        assertEquals(CREDIT_ZERO, exceededMismatch.remainingTotalCredits)
        assertEquals(1f, exceededMismatch.overallProgressRatio)
        assertTrue(exceededMismatch.targetValidation.sumMismatch)
        assertEquals(BigDecimal("-127.00"), exceededMismatch.targetValidation.difference)
    }

    @Test
    fun `all seven coverage statuses resolve accurately`() {
        assertEquals(
            CreditCoverageStatus.NOT_QUERIED,
            CreditProgressCalculator.calculate(
                CreditGradeSourceSnapshot("u", null, emptyList(), hasAnySingleSemesterSnapshot = false),
                CreditAccountPlan("u"),
            ).coverageStatus,
        )
        assertEquals(
            CreditCoverageStatus.PARTIAL_SEMESTER_ONLY,
            CreditProgressCalculator.calculate(
                CreditGradeSourceSnapshot("u", null, emptyList(), hasAnySingleSemesterSnapshot = true),
                CreditAccountPlan("u"),
            ).coverageStatus,
        )
        assertEquals(
            CreditCoverageStatus.TRUNCATED_SEMESTER_LIST,
            CreditProgressCalculator.calculate(
                CreditGradeSourceSnapshot(
                    "u",
                    GradeSnapshot(
                        accountKey = "u",
                        semester = GradeRepositoryPort.ALL_SEMESTERS,
                        complete = true,
                        targetSemesters = listOf("2024-1", "2024-2"),
                        queriedSemesters = listOf("2024-1"),
                        truncatedTermCount = 3,
                    ),
                    listOf(sampleRecord()),
                    false,
                ),
                CreditAccountPlan("u"),
            ).coverageStatus,
        )
        assertEquals(
            CreditCoverageStatus.DIRECT_ALL_EMPTY_UNVERIFIED,
            CreditProgressCalculator.calculate(
                CreditGradeSourceSnapshot(
                    "u",
                    GradeSnapshot(
                        accountKey = "u",
                        semester = GradeRepositoryPort.ALL_SEMESTERS,
                        complete = true,
                        directAllEmptyUnverified = true,
                    ),
                    emptyList(),
                    false,
                ),
                CreditAccountPlan("u"),
            ).coverageStatus,
        )
        assertEquals(
            CreditCoverageStatus.INVALIDATED_BY_PARTIAL_UPDATE,
            CreditProgressCalculator.calculate(
                CreditGradeSourceSnapshot(
                    "u",
                    GradeSnapshot(
                        accountKey = "u",
                        semester = GradeRepositoryPort.ALL_SEMESTERS,
                        complete = true,
                        targetSemesters = listOf("2024-1"),
                        queriedSemesters = listOf("2024-1"),
                        invalidatedByPartialUpdate = true,
                    ),
                    listOf(sampleRecord()),
                    false,
                ),
                CreditAccountPlan("u"),
            ).coverageStatus,
        )
        assertEquals(
            CreditCoverageStatus.LEGACY_CACHE_NEEDS_REFRESH,
            CreditProgressCalculator.calculate(
                CreditGradeSourceSnapshot(
                    "u",
                    GradeSnapshot(accountKey = "u", semester = GradeRepositoryPort.ALL_SEMESTERS, complete = true),
                    listOf(sampleRecord(rawAttributesKnown = false, creditStatus = CreditValueStatus.LEGACY_UNKNOWN_ZERO)),
                    false,
                ),
                CreditAccountPlan("u"),
            ).coverageStatus,
        )
        assertEquals(
            CreditCoverageStatus.FULL_CURRICULUM_VERIFIED,
            CreditProgressCalculator.calculate(verifiedSource(listOf(sampleRecord())), CreditAccountPlan("u")).coverageStatus,
        )
    }

    @Test
    fun `split rule survives swapped school row order but flags content drift and post-restore unverified state`() {
        val r1 = sampleRecord(
            id = 10L,
            semester = "2024-2025-1",
            courseCode = "DUP01",
            courseName = "创新训练",
            rawCreditText = "2",
            scoreText = "80",
            rawCourseAttribute = "必修",
            schoolRecordId = "ygu:dup#1",
            rowOrder = 0,
        )
        val r2 = sampleRecord(
            id = 11L,
            semester = "2024-2025-1",
            courseCode = "DUP01",
            courseName = "创新训练",
            rawCreditText = "2",
            scoreText = "92",
            rawCourseAttribute = "必修",
            schoolRecordId = "ygu:dup#2",
            rowOrder = 1,
        )
        val ruleKey = "code:DUP01"
        val splitRule = CreditResolutionRule(
            ruleKey = ruleKey,
            action = CreditResolutionAction.SPLIT_INTO_SEPARATE_COURSES,
            expectedFingerprints = listOf(
                CreditRecordAnchor.buildContentFingerprint(r1),
                CreditRecordAnchor.buildContentFingerprint(r2),
            ),
            splitAssignments = listOf(
                CreditSplitAssignment(
                    anchorKey = CreditRecordAnchor.buildAnchorKey(r1),
                    occurrenceIndex = 0,
                    customGroupKey = "dup_1",
                    assignedCategoryId = "required",
                    expectedFingerprint = CreditRecordAnchor.buildContentFingerprint(r1),
                    exclude = true,
                ),
                CreditSplitAssignment(
                    anchorKey = CreditRecordAnchor.buildAnchorKey(r2),
                    occurrenceIndex = 1,
                    customGroupKey = "dup_2",
                    assignedCategoryId = "practice",
                    expectedFingerprint = CreditRecordAnchor.buildContentFingerprint(r2),
                    exclude = false,
                ),
            ),
        )

        // Swapping row order & #1/#2 suffix in fresh fetch must NOT invalidate or mis-bind the split rule
        val swapped1 = r2.copy(id = 99L, schoolRecordId = "ygu:dup#1", rowOrder = 0)
        val swapped2 = r1.copy(id = 100L, schoolRecordId = "ygu:dup#2", rowOrder = 1)
        val swappedReport = CreditProgressCalculator.calculate(
            source = verifiedSource(listOf(swapped1, swapped2)),
            plan = CreditAccountPlan(accountKey = "2024001", rules = listOf(splitRule)),
        )
        assertTrue(swappedReport.pendingCourses.isEmpty())
        assertEquals(BigDecimal("2.00"), swappedReport.earnedTotalCredits)
        assertEquals("practice", swappedReport.countedCourses.single().categoryId)
        assertEquals(1, swappedReport.notCountedCourses.size)
        assertEquals(NotCountedReason.EXCLUDED_BY_USER_RULE, swappedReport.notCountedCourses.single().reason)

        // Changing score or attribute must trigger RULE_DRIFT_OR_POST_RESTORE
        val driftedReport = CreditProgressCalculator.calculate(
            source = verifiedSource(listOf(swapped1, swapped2.copy(scoreText = "83"))),
            plan = CreditAccountPlan(accountKey = "2024001", rules = listOf(splitRule)),
        )
        assertEquals(CREDIT_ZERO, driftedReport.earnedTotalCredits)
        assertEquals(1, driftedReport.pendingCourses.size)
        assertTrue(PendingReason.RULE_DRIFT_OR_POST_RESTORE in driftedReport.pendingCourses.single().reasons)

        // Restored rule before verification must also block auto-counting
        val unverifiedRestoreReport = CreditProgressCalculator.calculate(
            source = verifiedSource(listOf(r1, r2)),
            plan = CreditAccountPlan(
                accountKey = "2024001",
                rules = listOf(splitRule.copy(requiresPostRestoreVerification = true)),
            ),
        )
        assertEquals(CREDIT_ZERO, unverifiedRestoreReport.earnedTotalCredits)
        assertTrue(PendingReason.RULE_DRIFT_OR_POST_RESTORE in unverifiedRestoreReport.pendingCourses.single().reasons)
    }

    @Test
    fun `parseCreditBigDecimal rejects scientific notation NaN Infinity and negative numbers`() {
        assertNull(parseCreditBigDecimal(""))
        assertNull(parseCreditBigDecimal("NaN"))
        assertNull(parseCreditBigDecimal("Infinity"))
        assertNull(parseCreditBigDecimal("-1.0"))
        assertNull(parseCreditBigDecimal("1e2"))
        assertEquals(BigDecimal("0.00"), parseCreditBigDecimal("0"))
        assertEquals(BigDecimal("2.50"), parseCreditBigDecimal("2.5"))
    }

    @Test
    fun `curriculumSnapshot auto computes total target from earned plus inProgress plus pending and classifies practice courses`() {
        val snapshot = CurriculumProgressSnapshot(
            planTitle = "2024 专业培养方案及教学计划",
            totalEarnedCredits = BigDecimal("8"),
            totalInProgressCredits = BigDecimal("2"),
            groups = listOf(
                CurriculumNatureGroup(
                    courseNature = "公共必修课",
                    earnedCredits = BigDecimal("2"),
                    inProgressCredits = BigDecimal("2"),
                    courses = listOf(
                        CurriculumCourseItem(
                            courseCode = "REQ1",
                            courseName = "大学英语",
                            credit = BigDecimal("2"),
                            creditText = "2",
                            courseAttribute = "必修",
                            courseNature = "公共必修课",
                            studyStatus = CurriculumStudyStatus.COMPLETED,
                            scoreText = "85",
                            isPassed = true,
                        ),
                        CurriculumCourseItem(
                            courseCode = "REQ2",
                            courseName = "高数",
                            credit = BigDecimal("2"),
                            creditText = "2",
                            courseAttribute = "必修",
                            courseNature = "公共必修课",
                            studyStatus = CurriculumStudyStatus.IN_PROGRESS,
                        ),
                        CurriculumCourseItem(
                            courseCode = "REQ3",
                            courseName = "线性代数",
                            credit = BigDecimal("3"),
                            creditText = "3",
                            courseAttribute = "必修",
                            courseNature = "公共必修课",
                            studyStatus = CurriculumStudyStatus.PENDING_STUDY,
                        ),
                    ),
                ),
                CurriculumNatureGroup(
                    courseNature = "集中性实践教学",
                    earnedCredits = BigDecimal("6"),
                    inProgressCredits = BigDecimal("0"),
                    courses = listOf(
                        CurriculumCourseItem(
                            courseCode = "PRAC1",
                            courseName = "认识实习",
                            credit = BigDecimal("6"),
                            creditText = "6",
                            courseAttribute = "必修",
                            courseNature = "集中性实践教学",
                            studyStatus = CurriculumStudyStatus.COMPLETED,
                            scoreText = "优",
                            isPassed = true,
                        ),
                    ),
                ),
            ),
        )

        val records = listOf(
            sampleRecord(id = 1L, courseCode = "REQ1", courseName = "大学英语", rawCreditText = "2", scoreText = "85", rawCourseAttribute = "必修"),
            sampleRecord(id = 2L, courseCode = "PRAC1", courseName = "认识实习", rawCreditText = "6", scoreText = "优", rawCourseAttribute = "必修"),
        )
        val report = CreditProgressCalculator.calculate(
            source = verifiedSource(records),
            plan = CreditAccountPlan(accountKey = "2024001", curriculumSnapshot = snapshot),
        )

        assertEquals(BigDecimal("8.00"), report.earnedTotalCredits)
        // 8 (earned) + 2 (inProgress) + 3 (pending) = 13.00
        assertEquals(BigDecimal("13.00"), report.totalTargetCredits)

        val byCat = report.categoryProgressList.associateBy { it.category.categoryId }
        // REQ1=2 earned, REQ2=2 inProgress, REQ3=3 pending -> 7.00 total
        assertEquals(BigDecimal("2.00"), byCat["required"]!!.earnedCredits)
        assertEquals(BigDecimal("7.00"), byCat["required"]!!.targetCredits)
        // PRAC1 classified to practice by curriculum courseNature despite rawCourseAttribute="必修"
        assertEquals(BigDecimal("6.00"), byCat["practice"]!!.earnedCredits)
        assertEquals(BigDecimal("6.00"), byCat["practice"]!!.targetCredits)
    }

    @Test
    fun `curriculumSnapshot deduplicates same-name courses by keeping completed and dropping pending duplicate`() {
        val group = CurriculumNatureGroup(
            courseNature = "公共必修课",
            earnedCredits = BigDecimal("3"),
            inProgressCredits = BigDecimal.ZERO,
            courses = listOf(
                CurriculumCourseItem(
                    courseCode = "101303004",
                    courseName = "马克思主义基本原理",
                    credit = BigDecimal("3"),
                    creditText = "3",
                    courseAttribute = "必修",
                    courseNature = "公共必修课",
                    studyStatus = CurriculumStudyStatus.COMPLETED,
                    scoreText = "90",
                    isPassed = true,
                ),
                CurriculumCourseItem(
                    courseCode = "101303005",
                    courseName = "马克思主义基本原理",
                    credit = BigDecimal("2.5"),
                    creditText = "2.5",
                    courseAttribute = "必修",
                    courseNature = "公共必修课",
                    studyStatus = CurriculumStudyStatus.PENDING_STUDY,
                ),
            ),
        )
        assertEquals(BigDecimal.ZERO, group.pendingCredits)

        val snapshot = CurriculumProgressSnapshot(
            planTitle = "2024 专业培养方案及教学计划",
            totalEarnedCredits = BigDecimal("3"),
            totalInProgressCredits = BigDecimal.ZERO,
            groups = listOf(group),
        )
        val records = listOf(
            sampleRecord(
                id = 1L,
                courseCode = "101303004",
                courseName = "马克思主义基本原理",
                rawCreditText = "3",
                scoreText = "90",
                rawCourseAttribute = "必修",
            ),
        )
        val report = CreditProgressCalculator.calculate(
            source = verifiedSource(records),
            plan = CreditAccountPlan(accountKey = "2024001", curriculumSnapshot = snapshot),
        )

        assertEquals(BigDecimal("3.00"), report.earnedTotalCredits)
        assertEquals(BigDecimal("3.00"), report.totalTargetCredits)
        val required = report.categoryProgressList.first { it.category.categoryId == "required" }
        assertEquals(BigDecimal("3.00"), required.targetCredits)
    }
}
