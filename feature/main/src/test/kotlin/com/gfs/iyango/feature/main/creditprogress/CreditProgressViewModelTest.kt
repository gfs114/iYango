package com.gfs.iyango.feature.main.creditprogress

import com.gfs.iyango.core.domain.CreditGradeSourceSnapshot
import com.gfs.iyango.core.domain.CreditProgressCalculator
import com.gfs.iyango.core.domain.CreditProgressPort
import com.gfs.iyango.core.domain.GradeCoverageMetadata
import com.gfs.iyango.core.domain.GradeRepositoryPort
import com.gfs.iyango.core.domain.RefreshCoordinatorPort
import com.gfs.iyango.core.domain.RefreshOutcome
import com.gfs.iyango.core.domain.RefreshResource
import com.gfs.iyango.core.domain.RefreshState
import com.gfs.iyango.core.domain.RefreshTaskState
import com.gfs.iyango.core.domain.SchoolAuthPort
import com.gfs.iyango.core.domain.SettingsBackupExport
import com.gfs.iyango.core.domain.SettingsBackupPort
import com.gfs.iyango.core.domain.SettingsBackupPreview
import com.gfs.iyango.core.domain.SettingsBackupReadResult
import com.gfs.iyango.core.model.CreditAccountPlan
import com.gfs.iyango.core.model.CreditAccountPlanBackup
import com.gfs.iyango.core.model.CreditBackupPreview
import com.gfs.iyango.core.model.CreditCategoryPlan
import com.gfs.iyango.core.model.CreditCoverageStatus
import com.gfs.iyango.core.model.CreditRecordAnchor
import com.gfs.iyango.core.model.CreditResolutionAction
import com.gfs.iyango.core.model.CreditResolutionRule
import com.gfs.iyango.core.model.CreditSplitAssignment
import com.gfs.iyango.core.model.CreditValueStatus
import com.gfs.iyango.core.model.GradeRecord
import com.gfs.iyango.core.model.GradeSnapshot
import com.gfs.iyango.core.model.SchoolConnection
import com.gfs.iyango.core.model.SchoolConnectionStatus
import com.gfs.iyango.core.model.SchoolLoginResult
import com.gfs.iyango.core.model.SchoolProfile
import com.gfs.iyango.core.model.defaultCreditCategories
import com.gfs.iyango.feature.main.settings.SettingsBackupViewModel
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CreditProgressViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `saveTotalTarget validates input saves scale 2 BigDecimal and clears blank`() = runTest(testDispatcher) {
        val env = TestEnv()
        val collector = backgroundScope.launch { env.viewModel.uiState.collect {} }

        assertFalse(env.viewModel.saveTotalTarget("-5", ""))
        assertTrue(env.viewModel.uiState.value.notice.contains("非负数字"))

        assertFalse(env.viewModel.saveTotalTarget("abc", ""))
        assertFalse(env.viewModel.saveTotalTarget("1.2.3", ""))

        assertTrue(env.viewModel.saveTotalTarget("160", "2024级工学"))
        assertEquals(BigDecimal("160.00"), env.viewModel.uiState.value.plan.totalTargetCredits)
        assertEquals("2024级工学", env.viewModel.uiState.value.plan.userNote)

        assertTrue(env.viewModel.saveTotalTarget("   ", "备注清空目标"))
        assertNull(env.viewModel.uiState.value.plan.totalTargetCredits)
        assertEquals("备注清空目标", env.viewModel.uiState.value.plan.userNote)

        collector.cancel()
    }

    @Test
    fun `category save delete and reset operations update plan cleanly`() = runTest(testDispatcher) {
        val env = TestEnv()
        val collector = backgroundScope.launch { env.viewModel.uiState.collect {} }

        assertFalse(env.viewModel.saveCategory(null, "   ", "10", emptySet(), 4))
        assertFalse(env.viewModel.saveCategory(null, "实训", "bad", emptySet(), 4))

        assertTrue(
            env.viewModel.saveCategory(
                existingCategoryId = "practice_custom",
                displayName = "创新实践",
                targetInput = "8.5",
                mappedRawAttributes = setOf("实践", "实训"),
                sortOrder = 4,
            ),
        )
        val added = env.viewModel.uiState.value.plan.categories.firstOrNull { it.categoryId == "practice_custom" }
        assertNotNull(added)
        assertEquals(BigDecimal("8.50"), added?.targetCredits)
        assertEquals(setOf("实践", "实训"), added?.mappedRawAttributes)

        env.viewModel.deleteCategory("practice_custom")
        assertTrue(env.viewModel.uiState.value.plan.categories.none { it.categoryId == "practice_custom" })

        env.viewModel.deleteCategory("required")
        assertTrue(env.viewModel.uiState.value.plan.categories.none { it.categoryId == "required" })

        env.viewModel.resetCategoriesToDefault()
        assertEquals(
            defaultCreditCategories().map { it.categoryId },
            env.viewModel.uiState.value.plan.categories.map { it.categoryId },
        )

        collector.cancel()
    }

    @Test
    fun `merge split exclude post-restore verification and deleteRule update state`() = runTest(testDispatcher) {
        val env = TestEnv()
        val recordA = sampleRecord(
            semester = "2024-2025-1",
            courseCode = "CS101",
            courseName = "大学英语",
            credit = 2.0,
            scoreText = "85",
            scoreValue = 85.0,
            attribute = "必修",
            rawCourseAttribute = "必修",
            schoolRecordId = "rec-a",
        )
        val recordB = sampleRecord(
            semester = "2024-2025-2",
            courseCode = "CS101",
            courseName = "大学英语",
            credit = 3.0,
            scoreText = "90",
            scoreValue = 90.0,
            attribute = "必修",
            rawCourseAttribute = "必修",
            schoolRecordId = "rec-b",
        )
        env.sourceFlow.value = verifiedSource(listOf(recordA, recordB))
        val collector = backgroundScope.launch { env.viewModel.uiState.collect {} }

        val pending = env.viewModel.uiState.value.actionablePendingCourses.single()
        val anchorB = CreditRecordAnchor.buildAnchorKey(recordB)
        env.viewModel.resolveMergeSingleCourse(
            group = pending,
            assignedCategoryId = "required",
            chosenAnchorKey = anchorB,
            manualPassConfirmed = false,
            note = "同一门课核对",
        )

        assertTrue(env.viewModel.uiState.value.actionablePendingCourses.isEmpty())
        assertEquals(BigDecimal("3.00"), env.viewModel.uiState.value.report.earnedTotalCredits)
        val ruleKey = env.viewModel.findAssociatedRuleKey(env.viewModel.uiState.value.report.countedCourses.single().groupKey)
        assertNotNull(ruleKey)

        // Simulate backup restore requiring post-restore confirmation
        val savedRule = env.viewModel.uiState.value.plan.rules.single()
        env.creditPort.saveResolutionRule(
            env.accountKey,
            savedRule.copy(requiresPostRestoreVerification = true),
        )
        assertEquals(1, env.viewModel.uiState.value.actionablePendingCourses.size)

        env.viewModel.confirmPostRestoreRule(savedRule.ruleKey)
        assertTrue(env.viewModel.uiState.value.actionablePendingCourses.isEmpty())

        // Delete rule returns it to pending
        env.viewModel.deleteRule(savedRule.ruleKey)
        assertEquals(1, env.viewModel.uiState.value.actionablePendingCourses.size)

        // Split into 2 independent courses
        val reopened = env.viewModel.uiState.value.actionablePendingCourses.single()
        env.viewModel.resolveSplitSeparateCourses(
            group = reopened,
            assignments = listOf(
                CreditSplitAssignment(
                    anchorKey = CreditRecordAnchor.buildAnchorKey(recordA),
                    occurrenceIndex = 0,
                    customGroupKey = "${reopened.groupKey}#1",
                    assignedCategoryId = "required",
                    expectedFingerprint = CreditRecordAnchor.buildContentFingerprint(recordA),
                ),
                CreditSplitAssignment(
                    anchorKey = CreditRecordAnchor.buildAnchorKey(recordB),
                    occurrenceIndex = 0,
                    customGroupKey = "${reopened.groupKey}#2",
                    assignedCategoryId = "general_elective",
                    expectedFingerprint = CreditRecordAnchor.buildContentFingerprint(recordB),
                ),
            ),
        )
        assertEquals(BigDecimal("5.00"), env.viewModel.uiState.value.report.earnedTotalCredits)
        assertNotNull(env.viewModel.findAssociatedRuleKey("${reopened.groupKey}#2"))

        env.viewModel.deleteRule(reopened.suggestedRuleKey)
        env.viewModel.resolveExcludeFromPlan(env.viewModel.uiState.value.actionablePendingCourses.single())
        assertEquals(BigDecimal("0.00"), env.viewModel.uiState.value.report.earnedTotalCredits)
        assertEquals(1, env.viewModel.uiState.value.report.notCountedCourses.size)

        collector.cancel()
    }

    @Test
    fun `logged-out connection preserves user target credits while coverage remains NOT_QUERIED`() = runTest(testDispatcher) {
        val env = TestEnv()
        env.creditPort.updateTotalTargetCredits(env.accountKey, BigDecimal("160.00"))
        env.connectionsFlow.value = listOf(
            SchoolConnection(
                id = "conn-1",
                schoolId = "YGU",
                accountSubject = env.accountKey,
                maskedAccount = "2024****001",
                status = SchoolConnectionStatus.LOGGED_OUT,
                profile = SchoolProfile(displayName = "李同学", studentNo = env.accountKey),
            ),
        )
        env.sourceFlow.value = CreditGradeSourceSnapshot(accountKey = env.accountKey)
        val collector = backgroundScope.launch { env.viewModel.uiState.collect {} }

        val state = env.viewModel.uiState.value
        assertTrue(state.hasConnection)
        assertFalse(state.loggedIn)
        assertEquals(BigDecimal("160.00"), state.plan.totalTargetCredits)
        assertEquals(CreditCoverageStatus.NOT_QUERIED, state.report.coverageStatus)
        assertEquals(BigDecimal("160.00"), state.report.totalTargetCredits)

        collector.cancel()
    }

    @Test
    fun `legacyRefreshOnly items are counted in legacyRefreshOnlyCount and excluded from actionablePendingCourses`() = runTest(testDispatcher) {
        val env = TestEnv()
        val legacyZero = sampleRecord(
            semester = "2023-2024-1",
            courseCode = "LEG1",
            courseName = "劳动实践",
            credit = 0.0,
            rawCreditText = "0",
            creditStatus = CreditValueStatus.LEGACY_UNKNOWN_ZERO,
            scoreText = "优秀",
            scoreValue = 95.0,
            attribute = "必修",
            rawCourseAttribute = "必修",
            rawAttributesKnown = false,
            schoolRecordId = "l-1",
        )
        val legacyAttr = sampleRecord(
            semester = "2023-2024-1",
            courseCode = "LEG2",
            courseName = "大学体育",
            credit = 1.0,
            rawCreditText = "1",
            creditStatus = CreditValueStatus.VALID,
            scoreText = "80",
            scoreValue = 80.0,
            attribute = "必修",
            rawCourseAttribute = "必修",
            rawAttributesKnown = false,
            schoolRecordId = "l-2",
        )
        val actionableExemption = sampleRecord(
            semester = "2024-2025-1",
            courseCode = "ACT1",
            courseName = "免修确认课",
            credit = 2.0,
            rawCreditText = "2",
            scoreText = "免修",
            scoreValue = null,
            attribute = "必修",
            rawCourseAttribute = "必修",
            rawAttributesKnown = true,
            schoolRecordId = "a-1",
        )
        env.sourceFlow.value = verifiedSource(listOf(legacyZero, legacyAttr, actionableExemption))
        val collector = backgroundScope.launch { env.viewModel.uiState.collect {} }

        val state = env.viewModel.uiState.value
        assertEquals(CreditCoverageStatus.LEGACY_CACHE_NEEDS_REFRESH, state.report.coverageStatus)
        assertEquals(2, state.legacyRefreshOnlyCount)
        assertEquals(1, state.actionablePendingCourses.size)
        assertEquals("ACT1", state.actionablePendingCourses.single().courseCode)

        collector.cancel()
    }

    @Test
    fun `SettingsBackupViewModel overwriteCreditPlans defaults false resets on readFile and forwards on restore`() = runTest(testDispatcher) {
        val fakeBackup = FakeSettingsBackupPort()
        val vm = SettingsBackupViewModel(fakeBackup)

        assertFalse(vm.overwriteCreditPlans.value)

        vm.overwriteCreditPlans.value = true
        vm.readFile(byteArrayOf(1, 2, 3))
        assertFalse(vm.overwriteCreditPlans.value)
        assertNotNull(vm.preview.value)

        vm.overwriteCreditPlans.value = true
        vm.restore()
        assertEquals(true, fakeBackup.lastOverwriteCreditPlans)
        assertNull(vm.preview.value)
    }

    private fun verifiedSource(records: List<GradeRecord>) = CreditGradeSourceSnapshot(
        accountKey = "2024001",
        allSemesterSnapshot = GradeSnapshot(
            accountKey = "2024001",
            semester = GradeRepositoryPort.ALL_SEMESTERS,
            fetchedAt = 10_000L,
            complete = true,
            queryFingerprint = GradeRepositoryPort.ALL_SEMESTERS,
            targetSemesters = listOf("2024-2025-1", "2024-2025-2"),
            queriedSemesters = listOf("2024-2025-1", "2024-2025-2"),
        ),
        allSemesterRecords = records,
    )

    private fun sampleRecord(
        semester: String,
        courseCode: String,
        courseName: String,
        credit: Double,
        scoreText: String,
        scoreValue: Double?,
        attribute: String,
        rawCourseAttribute: String = attribute,
        rawCreditText: String = credit.toString(),
        creditStatus: CreditValueStatus = CreditValueStatus.VALID,
        rawAttributesKnown: Boolean = true,
        schoolRecordId: String,
    ) = GradeRecord(
        accountKey = "2024001",
        semester = semester,
        courseName = courseName,
        courseCode = courseCode,
        credit = credit,
        scoreText = scoreText,
        scoreValue = scoreValue,
        attribute = attribute,
        schoolRecordId = schoolRecordId,
        rawCreditText = rawCreditText,
        creditStatus = creditStatus,
        rawCourseAttribute = rawCourseAttribute,
        rawAttributesKnown = rawAttributesKnown,
    )

    private class TestEnv {
        val accountKey = "2024001"
        val connectionsFlow = MutableStateFlow(
            listOf(
                SchoolConnection(
                    id = "conn-1",
                    schoolId = "YGU",
                    accountSubject = accountKey,
                    maskedAccount = "2024****001",
                    status = SchoolConnectionStatus.ACTIVE,
                    profile = SchoolProfile(displayName = "李同学", studentNo = accountKey),
                ),
            ),
        )
        val sourceFlow = MutableStateFlow(CreditGradeSourceSnapshot(accountKey = accountKey))
        val creditPort = InMemoryCreditProgressPort()
        val refreshCoordinator = FakeRefreshCoordinator()
        val gradeRepo = FakeGradeRepository(sourceFlow)
        val schoolAuth = FakeSchoolAuth(connectionsFlow)

        val viewModel = CreditProgressViewModel(
            schoolAuth = schoolAuth,
            gradeRepository = gradeRepo,
            creditProgress = creditPort,
            refreshCoordinator = refreshCoordinator,
        )
    }

    private class InMemoryCreditProgressPort : CreditProgressPort {
        private val plans = MutableStateFlow<Map<String, CreditAccountPlan>>(emptyMap())

        override fun observeAccountPlan(accountKey: String): Flow<CreditAccountPlan> =
            plans.map { map -> map[accountKey] ?: CreditAccountPlan(accountKey = accountKey) }

        override suspend fun getAccountPlan(accountKey: String): CreditAccountPlan =
            plans.value[accountKey] ?: CreditAccountPlan(accountKey = accountKey)

        private fun mutate(accountKey: String, transform: (CreditAccountPlan) -> CreditAccountPlan) {
            val current = plans.value[accountKey] ?: CreditAccountPlan(accountKey = accountKey)
            plans.value = plans.value + (accountKey to transform(current))
        }

        override suspend fun updateTotalTargetCredits(accountKey: String, totalTargetCredits: BigDecimal?) {
            mutate(accountKey) { it.copy(totalTargetCredits = totalTargetCredits) }
        }

        override suspend fun updateUserNote(accountKey: String, userNote: String) {
            mutate(accountKey) { it.copy(userNote = userNote) }
        }

        override suspend fun saveCategoryPlan(accountKey: String, category: CreditCategoryPlan) {
            mutate(accountKey) { plan ->
                val remaining = plan.categories.filterNot { it.categoryId == category.categoryId }
                plan.copy(categories = (remaining + category).sortedBy { c -> c.sortOrder })
            }
        }

        override suspend fun deleteCategoryPlan(accountKey: String, categoryId: String) {
            mutate(accountKey) { plan ->
                plan.copy(categories = plan.categories.filterNot { it.categoryId == categoryId })
            }
        }

        override suspend fun resetCategoriesToDefault(accountKey: String) {
            mutate(accountKey) { it.copy(categories = defaultCreditCategories()) }
        }

        override suspend fun saveResolutionRule(accountKey: String, rule: CreditResolutionRule) {
            mutate(accountKey) { plan ->
                val remaining = plan.rules.filterNot { it.ruleKey == rule.ruleKey }
                plan.copy(rules = remaining + rule)
            }
        }

        override suspend fun deleteResolutionRule(accountKey: String, ruleKey: String) {
            mutate(accountKey) { plan ->
                plan.copy(rules = plan.rules.filterNot { it.ruleKey == ruleKey })
            }
        }

        override suspend fun clearPostRestoreVerification(accountKey: String, ruleKey: String) {
            mutate(accountKey) { plan ->
                plan.copy(
                    rules = plan.rules.map { rule ->
                        if (rule.ruleKey == ruleKey) rule.copy(requiresPostRestoreVerification = false) else rule
                    },
                )
            }
        }

        override suspend fun verifyRestoredRulesAgainstGrades(accountKey: String, records: List<GradeRecord>) = Unit
        override suspend fun migrateAccountIdentity(fromAccountKey: String, toAccountKey: String, schoolId: String) = Unit
        override suspend fun deleteAccount(accountKey: String) = Unit
        override suspend fun exportAllPlans(): List<CreditAccountPlanBackup> = emptyList()
        override suspend fun previewBackupImport(
            backups: List<CreditAccountPlanBackup>,
            knownAccountKeysByFingerprint: Map<String, String>,
        ): CreditBackupPreview = CreditBackupPreview()
        override suspend fun importBackupPlans(
            backups: List<CreditAccountPlanBackup>,
            overwriteConflicts: Boolean,
            knownAccountKeysByFingerprint: Map<String, String>,
        ) = Unit
    }

    private class FakeGradeRepository(
        private val sourceFlow: StateFlow<CreditGradeSourceSnapshot>,
    ) : GradeRepositoryPort {
        override fun observeSnapshot(accountKey: String, semester: String): Flow<GradeSnapshot?> = flowOf(null)
        override fun observeRecords(accountKey: String, semester: String): Flow<List<GradeRecord>> = flowOf(emptyList())
        override fun observeSemesters(accountKey: String): Flow<List<String>> = flowOf(emptyList())
        override fun observeCreditProgressSource(accountKey: String): Flow<CreditGradeSourceSnapshot> = sourceFlow
        override suspend fun saveCompleteResult(
            accountKey: String,
            semester: String,
            records: List<GradeRecord>,
            queryFingerprint: String,
            parserVersion: Int,
        ) = Unit
        override suspend fun saveCompleteResultWithCoverage(
            accountKey: String,
            semester: String,
            records: List<GradeRecord>,
            queryFingerprint: String,
            parserVersion: Int,
            coverageMetadata: GradeCoverageMetadata,
        ) = Unit
        override suspend fun markChangesRead(accountKey: String, semester: String) = Unit
        override fun observeUnreadCount(accountKey: String): Flow<Int> = flowOf(0)
        override suspend fun deleteAccount(accountKey: String) = Unit
    }

    private class FakeSchoolAuth(
        private val connectionsFlow: StateFlow<List<SchoolConnection>>,
    ) : SchoolAuthPort {
        override fun observeConnections(): Flow<List<SchoolConnection>> = connectionsFlow
        override fun observeActiveConnection(): Flow<SchoolConnection?> = connectionsFlow.map { it.firstOrNull() }
        override suspend fun getConnection(connectionId: String): SchoolConnection? = null
        override suspend fun historyAccount(connectionId: String): String? = null
        override suspend fun login(account: String, password: String, rememberPassword: Boolean): SchoolLoginResult =
            SchoolLoginResult.Failed("unused")
        override suspend fun restoreSession(connectionId: String): SchoolLoginResult =
            SchoolLoginResult.Failed("unused")
        override suspend fun fetchProfile(connectionId: String): Result<SchoolProfile> =
            Result.failure(UnsupportedOperationException())
        override suspend fun logout(connectionId: String, clearSavedPassword: Boolean) = Unit
        override suspend fun deleteConnection(connectionId: String) = Unit
        override suspend fun clearHistory(keepConnectionId: String) = Unit
    }

    private class FakeRefreshCoordinator : RefreshCoordinatorPort {
        override val state: StateFlow<RefreshState> = MutableStateFlow(RefreshState())
        override fun onForeground() = Unit
        override fun onSessionChanged() = Unit
        override suspend fun refresh(resource: RefreshResource, queryFingerprint: String): RefreshTaskState =
            RefreshTaskState(resource = resource, outcome = RefreshOutcome.SUCCESS)
        override fun onLocalDataChanged(resource: RefreshResource) = Unit
    }

    private class FakeSettingsBackupPort : SettingsBackupPort {
        var lastOverwriteCreditPlans: Boolean? = null

        override suspend fun export(includeWallpaper: Boolean): SettingsBackupExport =
            SettingsBackupExport(fileName = "b.iyango", bytes = byteArrayOf(), includesWallpaper = false)

        override suspend fun read(bytes: ByteArray): SettingsBackupReadResult =
            SettingsBackupReadResult.Ready(
                SettingsBackupPreview(
                    token = "tok-1",
                    formatVersion = 2,
                    exportedAt = 1000L,
                    includesWallpaper = false,
                    themeMode = "SYSTEM",
                    accentHex = null,
                    defaultStartTab = 0,
                    navigationLayout = "DOCK",
                    animationsEnabled = true,
                    displayOverrideCount = 0,
                    reminderEnabled = false,
                    reminderLeadMinutes = 15,
                    issues = emptyList(),
                    creditBackupPreview = CreditBackupPreview(matchedPlansCount = 1),
                ),
            )

        override suspend fun restore(
            token: String,
            includeWallpaper: Boolean,
            overwriteCreditPlans: Boolean,
        ): Result<Unit> {
            lastOverwriteCreditPlans = overwriteCreditPlans
            return Result.success(Unit)
        }

        override suspend fun resetSettings(): Result<Unit> = Result.success(Unit)
    }
}
