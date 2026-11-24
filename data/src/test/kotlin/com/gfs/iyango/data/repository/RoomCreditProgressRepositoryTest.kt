package com.gfs.iyango.data.repository

import androidx.room.Room
import com.gfs.iyango.core.domain.AppSettingsPort
import com.gfs.iyango.core.domain.CalendarCoordinatorPort
import com.gfs.iyango.core.domain.CalendarSyncStatus
import com.gfs.iyango.core.domain.CreditProgressCalculator
import com.gfs.iyango.core.domain.CropPixelRegion
import com.gfs.iyango.core.domain.GradeCoverageMetadata
import com.gfs.iyango.core.domain.GradeQueryResult
import com.gfs.iyango.core.domain.GradeRepositoryPort
import com.gfs.iyango.core.domain.PreparedWallpaper
import com.gfs.iyango.core.domain.RefreshCoordinatorPort
import com.gfs.iyango.core.domain.RefreshResource
import com.gfs.iyango.core.domain.RefreshState
import com.gfs.iyango.core.domain.RefreshTaskState
import com.gfs.iyango.core.domain.ReminderCoordinatorPort
import com.gfs.iyango.core.domain.ReminderStatus
import com.gfs.iyango.core.domain.SchoolCredential
import com.gfs.iyango.core.domain.SchoolCredentialStore
import com.gfs.iyango.core.domain.SettingsBackupReadResult
import com.gfs.iyango.core.domain.ThemeSettings
import com.gfs.iyango.core.domain.ThemeSettingsPort
import com.gfs.iyango.core.domain.WallpaperStore
import com.gfs.iyango.core.model.AppPreferences
import com.gfs.iyango.core.model.CalendarSettings
import com.gfs.iyango.core.model.CreditCoverageStatus
import com.gfs.iyango.core.model.CreditRecordAnchor
import com.gfs.iyango.core.model.CreditResolutionAction
import com.gfs.iyango.core.model.CreditResolutionRule
import com.gfs.iyango.core.model.CreditValueStatus
import com.gfs.iyango.core.model.CurriculumStudyStatus
import com.gfs.iyango.core.model.DisplaySettingsBundle
import com.gfs.iyango.core.model.GradeRecord
import com.gfs.iyango.core.model.ReminderSettings
import com.gfs.iyango.core.model.ScheduleDisplaySettings
import com.gfs.iyango.core.model.SchoolConnectionStatus
import com.gfs.iyango.data.db.IYangoDatabase
import com.gfs.iyango.data.db.SchoolConnectionEntity
import com.gfs.iyango.data.school.ygu.YguConfig
import com.gfs.iyango.data.school.ygu.YguCurriculumClient
import com.gfs.iyango.data.school.ygu.YguCurriculumParser
import com.gfs.iyango.data.school.ygu.YguGradesClient
import com.gfs.iyango.data.school.ygu.YguHttpResponse
import java.math.BigDecimal
import java.net.URLDecoder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomCreditProgressRepositoryTest {

    private lateinit var db: IYangoDatabase
    private lateinit var gradeRepo: RoomGradeRepository
    private lateinit var creditRepo: RoomCreditProgressRepository
    private lateinit var credStore: InMemoryCredentialStore
    private lateinit var authRepo: RoomSchoolAuthRepository
    private var nowMillis = 1_700_000_000_000L
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = Instant.ofEpochMilli(nowMillis)
    }
    private val fakeRefresh = object : RefreshCoordinatorPort {
        override val state = MutableStateFlow(RefreshState())
        override fun onForeground() {}
        override fun onSessionChanged() {}
        override suspend fun refresh(resource: RefreshResource, queryFingerprint: String) =
            RefreshTaskState(resource = resource)
        override fun onLocalDataChanged(resource: RefreshResource) {}
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            IYangoDatabase::class.java,
        ).allowMainThreadQueries().build()
        gradeRepo = RoomGradeRepository(db, clock)
        creditRepo = RoomCreditProgressRepository(db, clock)
        credStore = InMemoryCredentialStore()
        authRepo = RoomSchoolAuthRepository(
            db = db,
            credentialStore = credStore,
            gradeRepository = gradeRepo,
            refreshCoordinator = fakeRefresh,
            clock = clock,
            creditProgressRepository = creditRepo,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun connectionEntity(
        id: String,
        accountSubject: String = "",
        status: SchoolConnectionStatus = SchoolConnectionStatus.ACTIVE,
        rememberPassword: Boolean = true,
    ) = SchoolConnectionEntity(
        id = id,
        schoolId = "YGU",
        accountSubject = accountSubject,
        maskedAccount = accountSubject.ifEmpty { id },
        sessionRevision = 1,
        status = status,
        credentialRef = id,
        rememberPassword = rememberPassword,
        createdAt = nowMillis,
        lastLoginAt = nowMillis,
        lastError = "",
        displayName = "",
        studentNo = accountSubject,
        college = "",
        major = "",
        className = "",
        gender = "",
        avatarUrl = "",
    )

    @Test
    fun `observeCreditProgressSource never mixes single semester records and detects partial invalidation`() = runBlocking {
        val termRecord = GradeRecord(
            accountKey = "2024001",
            semester = "2024-2025-1",
            courseCode = "C1",
            courseName = "高数",
            credit = 4.0,
            rawCreditText = "4.0",
            creditStatus = CreditValueStatus.VALID,
            scoreText = "85",
            rawCourseAttribute = "必修",
            rawAttributesKnown = true,
        )
        gradeRepo.saveCompleteResultWithCoverage(
            accountKey = "2024001",
            semester = "2024-2025-1",
            records = listOf(termRecord),
            queryFingerprint = "2024-2025-1",
            parserVersion = 1,
            coverageMetadata = GradeCoverageMetadata(
                targetSemesters = listOf("2024-2025-1"),
                queriedSemesters = listOf("2024-2025-1"),
            ),
        )

        val partialOnlySource = gradeRepo.observeCreditProgressSource("2024001").first()
        assertNull(partialOnlySource.allSemesterSnapshot)
        assertTrue(partialOnlySource.allSemesterRecords.isEmpty())
        assertTrue(partialOnlySource.hasAnySingleSemesterSnapshot)
        assertEquals(
            CreditCoverageStatus.PARTIAL_SEMESTER_ONLY,
            CreditProgressCalculator.calculate(partialOnlySource, creditRepo.getAccountPlan("2024001")).coverageStatus,
        )

        gradeRepo.saveCompleteResultWithCoverage(
            accountKey = "2024001",
            semester = GradeRepositoryPort.ALL_SEMESTERS,
            records = listOf(termRecord),
            queryFingerprint = "",
            parserVersion = 1,
            coverageMetadata = GradeCoverageMetadata(
                targetSemesters = listOf("2024-2025-1"),
                queriedSemesters = listOf("2024-2025-1"),
            ),
        )
        val verified = gradeRepo.observeCreditProgressSource("2024001").first()
        assertEquals(
            CreditCoverageStatus.FULL_CURRICULUM_VERIFIED,
            CreditProgressCalculator.calculate(verified, creditRepo.getAccountPlan("2024001")).coverageStatus,
        )

        // Partial update after all-semester snapshot must invalidate all-semester coverage freshness
        gradeRepo.saveCompleteResultWithCoverage(
            accountKey = "2024001",
            semester = "2024-2025-1",
            records = listOf(termRecord),
            queryFingerprint = "2024-2025-1",
            parserVersion = 1,
            coverageMetadata = GradeCoverageMetadata(
                targetSemesters = listOf("2024-2025-1"),
                queriedSemesters = listOf("2024-2025-1"),
            ),
        )
        val invalidated = gradeRepo.observeCreditProgressSource("2024001").first()
        assertEquals(
            CreditCoverageStatus.INVALIDATED_BY_PARTIAL_UPDATE,
            CreditProgressCalculator.calculate(invalidated, creditRepo.getAccountPlan("2024001")).coverageStatus,
        )
    }

    @Test
    fun `ygu query populates raw attributes direct empty unverified and truncated term count`() = runBlocking {
        db.schoolConnectionDao().upsert(connectionEntity("conn-ygu", accountSubject = "2024001"))
        credStore.write("conn-ygu", SchoolCredential(account = "alice", password = "pw", cookieHeader = "bzb_jsxsd=COOKIE1"))

        val queryRepo = YguGradeQueryRepository(db, credStore, authRepo)

        val emptyTableHtml = "<table><tr><th>开课学期</th><th>课程名称</th><th>成绩</th><th>学分</th></tr>" +
            "<tr><td colspan='4'>本学期暂无成绩记录</td></tr></table>"

        // 1. Direct "" returns EMPTY -> must set directAllEmptyUnverified = true
        val twoTermsForm = "<form method='post' action='/jsxsd/kscj/cjcx_list'><select name='kksj'>" +
            "<option value=''>全部学期</option>" +
            "<option value='2024-2025-1'>2024-2025-1</option></select></form>"
        queryRepo.createClient = {
            YguGradesClient(
                get = { _, _, _, _ -> YguHttpResponse(200, twoTermsForm, emptyList(), "") },
                post = { _, _, _, _, _ ->
                    YguHttpResponse(200, emptyTableHtml, emptyList(), "")
                },
            )
        }
        val emptyDirect = queryRepo.query("conn-ygu", "") as GradeQueryResult.Success
        assertTrue(emptyDirect.coverageMetadata.directAllEmptyUnverified)

        // 2. Direct "" fails -> term fallback with 42 terms -> truncatedTermCount == 2 and raw fields preserved
        val options42 = (1..42).joinToString("") { "<option value='term-$it'>term-$it</option>" }
        val bigForm = "<form method='post' action='/jsxsd/kscj/cjcx_list'><select name='kksj'>" +
            "<option value=''>全部学期</option>$options42</select></form>"
        queryRepo.createClient = {
            YguGradesClient(
                get = { _, _, _, _ -> YguHttpResponse(200, bigForm, emptyList(), "") },
                post = { _, body, _, _, _ ->
                    val term = URLDecoder.decode(body.substringAfter("kksj=").substringBefore('&'), "UTF-8")
                    when {
                        term.isEmpty() -> YguHttpResponse(500, "系统错误", emptyList(), "")
                        term == "term-1" -> YguHttpResponse(
                            200,
                            "<table><tr>" +
                                "<th>开课学期</th><th>课程编号</th><th>课程名称</th><th>成绩</th><th>学分</th>" +
                                "<th>课程属性</th><th>课程性质</th><th>通选课类别</th>" +
                                "</tr><tr>" +
                                "<td>term-1</td><td>C01</td><td>通识课</td><td>合格</td><td>0</td>" +
                                "<td>选修</td><td>通识类</td><td>人文</td>" +
                                "</tr></table>",
                            emptyList(),
                            "",
                        )
                        else -> YguHttpResponse(200, emptyTableHtml, emptyList(), "")
                    }
                },
            )
        }
        val fallbackResult = queryRepo.query("conn-ygu", "") as GradeQueryResult.Success
        assertEquals(2, fallbackResult.coverageMetadata.truncatedTermCount)
        assertEquals(42, fallbackResult.coverageMetadata.targetSemesters.size)
        assertEquals(40, fallbackResult.coverageMetadata.queriedSemesters.size)
        val record = fallbackResult.records.single()
        assertEquals("0", record.rawCreditText)
        assertEquals(CreditValueStatus.VALID, record.creditStatus)
        assertEquals("选修", record.rawCourseAttribute)
        assertEquals("通识类", record.rawCourseType)
        assertEquals("人文", record.rawGeneralElectiveType)
        assertTrue(record.rawAttributesKnown)
    }

    @Test
    fun `logout preserves credit plans while deleteConnection and clearHistory delete them and identity migrates`() = runBlocking {
        db.schoolConnectionDao().upsert(connectionEntity("conn-tmp-1", accountSubject = ""))
        creditRepo.updateTotalTargetCredits("conn-tmp-1", BigDecimal("160"))
        creditRepo.migrateAccountIdentity(fromAccountKey = "conn-tmp-1", toAccountKey = "2024001", schoolId = "YGU")
        assertNull(db.creditProgressDao().getPlan("conn-tmp-1"))
        assertEquals(BigDecimal("160.00"), creditRepo.getAccountPlan("2024001").totalTargetCredits)

        db.schoolConnectionDao().upsert(connectionEntity("conn-tmp-1", accountSubject = "2024001"))
        gradeRepo.saveCompleteResult("2024001", GradeRepositoryPort.ALL_SEMESTERS, emptyList(), "", 1)

        // Logout clears grades cache ONLY and keeps credit targets/categories/rules
        authRepo.logout("conn-tmp-1", clearSavedPassword = true)
        assertNull(gradeRepo.observeCreditProgressSource("2024001").first().allSemesterSnapshot)
        assertEquals(BigDecimal("160.00"), creditRepo.getAccountPlan("2024001").totalTargetCredits)

        // deleteConnection deletes credit plan permanently
        authRepo.deleteConnection("conn-tmp-1")
        assertNull(db.creditProgressDao().getPlan("2024001"))

        // clearHistory also deletes non-kept connection's credit plan
        db.schoolConnectionDao().upsert(connectionEntity("conn-keep", accountSubject = "2024002"))
        db.schoolConnectionDao().upsert(
            connectionEntity(
                "conn-old",
                accountSubject = "2024003",
                status = SchoolConnectionStatus.LOGGED_OUT,
                rememberPassword = false,
            ),
        )
        creditRepo.updateTotalTargetCredits("2024002", BigDecimal("150"))
        creditRepo.updateTotalTargetCredits("2024003", BigDecimal("140"))
        authRepo.clearHistory("conn-keep")
        assertNotNull(db.creditProgressDao().getPlan("2024002"))
        assertNull(db.creditProgressDao().getPlan("2024003"))
    }

    @Test
    fun `backup export conflict protection pending migration and post-restore verification round-trip`() = runBlocking {
        val sampleGrade = GradeRecord(
            accountKey = "2024999",
            semester = "2024-2025-1",
            courseCode = "CS101",
            courseName = "导论",
            credit = 2.0,
            rawCreditText = "2",
            creditStatus = CreditValueStatus.VALID,
            scoreText = "免修",
            rawCourseAttribute = "必修",
            rawAttributesKnown = true,
        )
        val fp = CreditRecordAnchor.buildContentFingerprint(sampleGrade)
        val rule = CreditResolutionRule(
            ruleKey = "code:CS101",
            action = CreditResolutionAction.MERGE_AS_SINGLE_COURSE,
            assignedCategoryId = "required",
            expectedFingerprints = listOf(fp),
            manualPassConfirmed = true,
        )
        creditRepo.updateTotalTargetCredits("2024999", BigDecimal("160"))
        creditRepo.saveResolutionRule("2024999", rule)

        val exported = creditRepo.exportAllPlans()
        assertEquals(1, exported.size)
        // Must contain hashed fingerprint, never plaintext accountKey
        assertFalse(exported.single().subjectFingerprint.contains("2024999"))

        // 1. Restore when unmatched -> stored under pending:<fp> and promotes on identity migration
        creditRepo.deleteAccount("2024999")
        val previewUnmatched = creditRepo.previewBackupImport(exported, emptyMap())
        assertEquals(0, previewUnmatched.matchedPlansCount)
        assertEquals(1, previewUnmatched.unmatchedPendingPlansCount)

        creditRepo.importBackupPlans(exported, overwriteConflicts = false, knownAccountKeysByFingerprint = emptyMap())
        creditRepo.migrateAccountIdentity(fromAccountKey = "conn-later", toAccountKey = "2024999", schoolId = "YGU")
        val promoted = creditRepo.getAccountPlan("2024999")
        assertEquals(BigDecimal("160.00"), promoted.totalTargetCredits)
        assertTrue(promoted.rules.single().requiresPostRestoreVerification)

        // Fresh grades verify matching fingerprint -> clears requiresPostRestoreVerification
        creditRepo.verifyRestoredRulesAgainstGrades("2024999", listOf(sampleGrade))
        assertFalse(creditRepo.getAccountPlan("2024999").rules.single().requiresPostRestoreVerification)

        // 2. Local conflict keeps local when overwriteConflicts = false, overwrites when true
        creditRepo.updateTotalTargetCredits("2024999", BigDecimal("175"))
        val knownMap = mapOf(exported.single().subjectFingerprint to "2024999")
        val previewConflict = creditRepo.previewBackupImport(exported, knownMap)
        assertEquals(1, previewConflict.conflicts.size)

        creditRepo.importBackupPlans(exported, overwriteConflicts = false, knownAccountKeysByFingerprint = knownMap)
        assertEquals(BigDecimal("175.00"), creditRepo.getAccountPlan("2024999").totalTargetCredits)

        creditRepo.importBackupPlans(exported, overwriteConflicts = true, knownAccountKeysByFingerprint = knownMap)
        val overwritten = creditRepo.getAccountPlan("2024999")
        assertEquals(BigDecimal("160.00"), overwritten.totalTargetCredits)
        assertTrue(overwritten.rules.single().requiresPostRestoreVerification)

        // 3. Full DefaultSettingsBackup round-trip + legacy v1 JSON compatibility
        val backupPort = createDefaultSettingsBackup()
        val exportedBytes = backupPort.export(includeWallpaper = false).bytes
        val exportText = exportedBytes.toString(Charsets.UTF_8)
        assertFalse(exportText.contains("2024999"))
        val ready = backupPort.read(exportedBytes) as SettingsBackupReadResult.Ready
        assertEquals(1, ready.preview.creditBackupPreview.conflicts.size)

        val legacyV1Json = """{"app":"iYango-Settings","version":1,"exportedAt":1000}""".toByteArray(Charsets.UTF_8)
        val legacyRead = backupPort.read(legacyV1Json)
        assertTrue(legacyRead is SettingsBackupReadResult.Ready)
    }

    @Test
    fun `ygu curriculum parser parses plan title totals all 5 groups statuses and ignores buggy hidden area`() {
        val snapshot = YguCurriculumParser.parse(sampleYguCurriculumHtml(), fetchedAtEpochMillis = 999L)
        assertNotNull(snapshot)
        snapshot!!
        assertEquals("2024 专业培养方案及教学计划", snapshot.planTitle)
        assertEquals(0, snapshot.totalEarnedCredits.compareTo(BigDecimal("107")))
        assertEquals(0, snapshot.totalInProgressCredits.compareTo(BigDecimal("21.75")))
        assertEquals(5, snapshot.groups.size)

        val publicRequired = snapshot.groups[0]
        assertEquals("公共必修课", publicRequired.courseNature)
        assertEquals(0, publicRequired.earnedCredits.compareTo(BigDecimal("61.5")))
        assertEquals(0, publicRequired.inProgressCredits.compareTo(BigDecimal("1.25")))
        assertEquals(1, publicRequired.completedCount)
        assertEquals(1, publicRequired.inProgressCount)
        assertEquals(1, publicRequired.pendingCount)
        assertEquals(0, publicRequired.pendingCredits.compareTo(BigDecimal("0.25")))

        val passed = publicRequired.courses[0]
        assertEquals("101305003", passed.courseCode)
        assertEquals("思想道德与法治", passed.courseName)
        assertEquals(CurriculumStudyStatus.COMPLETED, passed.studyStatus)
        assertTrue(passed.isPassed)
        assertEquals("70", passed.scoreText)

        val inProgress = publicRequired.courses[1]
        assertEquals(CurriculumStudyStatus.IN_PROGRESS, inProgress.studyStatus)
        assertFalse(inProgress.isPassed)

        val pending = publicRequired.courses[2]
        assertEquals(CurriculumStudyStatus.PENDING_STUDY, pending.studyStatus)
        assertFalse(pending.isPassed)

        assertEquals("公共选修课", snapshot.groups[1].courseNature)
        assertEquals("集中性实践教学", snapshot.groups[2].courseNature)
        assertEquals("专业必修课", snapshot.groups[3].courseNature)
        assertEquals("专业选修课", snapshot.groups[4].courseNature)
    }

    @Test
    fun `syncCurriculumProgress persists snapshot through Room and survives target updates and backup round-trip`() =
        runBlocking {
            val conn = SchoolConnectionEntity(
                id = "conn-curriculum",
                schoolId = "YGU",
                accountSubject = "2024999",
                maskedAccount = "2024***99",
                sessionRevision = 1,
                status = SchoolConnectionStatus.ACTIVE,
                credentialRef = "conn-curriculum",
                rememberPassword = true,
                createdAt = 100L,
                lastLoginAt = 100L,
                lastError = "",
                displayName = "李华",
                studentNo = "2024999",
                college = "人工智能",
                major = "软件工程",
                className = "1班",
                gender = "",
                avatarUrl = "",
            )
            db.schoolConnectionDao().upsert(conn)
            credStore.write(conn.id, SchoolCredential("2024999", "pw", "bzb_jsxsd=ok"))

            var requestedUrl = ""
            val syncRepo = RoomCreditProgressRepository(
                db = db,
                clock = clock,
                credentialStore = credStore,
            ).apply {
                createCurriculumClient = {
                    YguCurriculumClient(
                        get = { url, _, _, _ ->
                            requestedUrl = url
                            YguHttpResponse(
                                status = 200,
                                body = sampleYguCurriculumHtml(),
                                setCookies = emptyList(),
                                location = "",
                            )
                        },
                        clockMillis = clock::millis,
                    )
                }
            }

            val syncResult = syncRepo.syncCurriculumProgress(conn.id)
            assertTrue(syncResult.isSuccess)
            assertEquals(YguConfig.CURRICULUM_PROGRESS, requestedUrl)

            syncRepo.updateTotalTargetCredits("2024999", BigDecimal("160"))
            val afterTargetEdit = syncRepo.getAccountPlan("2024999")
            assertEquals(BigDecimal("160.00"), afterTargetEdit.totalTargetCredits)
            assertNotNull(afterTargetEdit.curriculumSnapshot)
            assertEquals("2024 专业培养方案及教学计划", afterTargetEdit.curriculumSnapshot?.planTitle)
            val publicReqCourses = afterTargetEdit.curriculumSnapshot?.groups
                ?.first { it.courseNature == "公共必修课" }
                ?.courses
                .orEmpty()
            assertEquals(listOf("101305003", "101307025", "101307026"), publicReqCourses.map { it.courseCode })

            val exported = syncRepo.exportAllPlans()
            assertEquals(1, exported.size)
            assertNotNull(exported.single().curriculumSnapshot)

            syncRepo.deleteAccount("2024999")
            syncRepo.importBackupPlans(
                backups = exported,
                overwriteConflicts = true,
                knownAccountKeysByFingerprint = mapOf(exported.single().subjectFingerprint to "2024999"),
            )
            val restored = syncRepo.getAccountPlan("2024999")
            assertEquals("2024 专业培养方案及教学计划", restored.curriculumSnapshot?.planTitle)
            assertEquals(5, restored.curriculumSnapshot?.groups?.size)

            var concurrentRequests = 0
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            syncRepo.createCurriculumClient = {
                YguCurriculumClient(
                    get = { _, _, _, _ ->
                        concurrentRequests++
                        entered.complete(Unit)
                        release.await()
                        YguHttpResponse(
                            status = 200,
                            body = sampleYguCurriculumHtml(),
                            setCookies = emptyList(),
                            location = "",
                        )
                    },
                    clockMillis = clock::millis,
                )
            }
            val first = coroutineScope {
                val a = async(Dispatchers.IO) {
                    syncRepo.syncCurriculumProgress(conn.id)
                }
                withTimeout(2_000) { entered.await() }
                val b = async(Dispatchers.IO) {
                    syncRepo.syncCurriculumProgress(conn.id)
                }
                release.complete(Unit)
                Pair(a.await(), b.await())
            }
            assertTrue(first.first.isSuccess)
            assertTrue(first.second.isSuccess)
            assertEquals(1, concurrentRequests)
        }

    private fun sampleYguCurriculumHtml(): String = """
        <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN">
        <html><body><input type="hidden" id="shouyedizhi" value="/jsxsd"/></body></html>
        <body class="qz-blue">
        <div class="mod-curriculum-system">
          <div class="mod-total-area">
            <div class="header-item">
              <h5 class="header-item-text">2024 专业培养方案及教学计划</h5>
            </div>
            <div class="total-list">
              <div class="list-header-tr">
                <div class="header-th"><span class="header-th-cell">课程性质</span></div>
                <div class="header-th"><span class="header-th-cell">已修学分</span></div>
                <div class="header-th"><span class="header-th-cell">正修读学分</span></div>
              </div>
              <div class="list-tr">
                <div class="list-td"><span class="list-td-cell jClass-item">公共必修课</span></div>
                <div class="list-td"><span class="list-td-cell">61.5</span></div>
                <div class="list-td"><span class="list-td-cell">1.25</span></div>
              </div>
              <div class="list-tr">
                <div class="list-td"><span class="list-td-cell jClass-item">公共选修课</span></div>
                <div class="list-td"><span class="list-td-cell">6.0</span></div>
                <div class="list-td"><span class="list-td-cell">4.5</span></div>
              </div>
              <div class="list-tr">
                <div class="list-td"><span class="list-td-cell jClass-item">集中性实践教学</span></div>
                <div class="list-td"><span class="list-td-cell">6.0</span></div>
                <div class="list-td"><span class="list-td-cell">0.0</span></div>
              </div>
              <div class="list-tr">
                <div class="list-td"><span class="list-td-cell jClass-item">专业必修课</span></div>
                <div class="list-td"><span class="list-td-cell">26.0</span></div>
                <div class="list-td"><span class="list-td-cell">6.0</span></div>
              </div>
              <div class="list-tr">
                <div class="list-td"><span class="list-td-cell jClass-item">专业选修课</span></div>
                <div class="list-td"><span class="list-td-cell">7.5</span></div>
                <div class="list-td"><span class="list-td-cell">10.0</span></div>
              </div>
              <div class="list-tr total-tr">
                <div class="list-td"><span class="list-td-cell">合计</span></div>
                <div class="list-td"><span class="list-td-cell">107.0</span></div>
                <div class="list-td"><span class="list-td-cell">21.75</span></div>
              </div>
            </div>
          </div>
          <div class="mod-item-detail box-shadow">
            <div class="header-content">
              <div class="header-content-title">
                <h5 class="header-title">
                  <span class="header-title-text">课程性质 : </span>
                  <b id="jClass-1" class="header-title-blod jClassName">公共必修课<i class="layui-icon jClassName">&nbsp;&nbsp;&#xe62f;</i></b>
                </h5>
              </div>
              <div class="header-content-area" style="display: none">
                <label class="study-info-label">学分要求 : 1.25</label>
              </div>
            </div>
            <div class="sub-table">
              <div class="sub-table-header-tr">
                <div class="header-th"><span class="header-th-cell">课程编号</span></div>
              </div>
              <div class="list-tr">
                <div class="list-td"><span class="list-td-cell">101305003</span></div>
                <div class="list-td"><span class="list-td-cell">思想道德与法治</span></div>
                <div class="list-td"><span class="list-td-cell">2.5</span></div>
                <div class="list-td"><span class="list-td-cell">必修</span></div>
                <div class="list-td"><span class="list-td-cell">公共必修课</span></div>
                <div class="list-td"><span class="list-td-cell">已修读</span></div>
                <div class="list-td is-pass"><span class="list-td-cell">70</span></div>
                <div class="list-td"><span class="list-td-cell"></span></div>
              </div>
              <div class="list-tr">
                <div class="list-td"><span class="list-td-cell">101305999</span></div>
                <div class="list-td"><span class="list-td-cell">思想道德与法治</span></div>
                <div class="list-td"><span class="list-td-cell">2.5</span></div>
                <div class="list-td"><span class="list-td-cell">必修</span></div>
                <div class="list-td"><span class="list-td-cell">公共必修课</span></div>
                <div class="list-td is-failed"><span class="list-td-cell">待修读</span></div>
                <div class="list-td"><span class="list-td-cell"></span></div>
                <div class="list-td"><span class="list-td-cell"></span></div>
              </div>
              <div class="list-tr">
                <div class="list-td"><span class="list-td-cell">101307025</span></div>
                <div class="list-td"><span class="list-td-cell">形势与政策(五)</span></div>
                <div class="list-td"><span class="list-td-cell">0.25</span></div>
                <div class="list-td"><span class="list-td-cell">必修</span></div>
                <div class="list-td"><span class="list-td-cell">公共必修课</span></div>
                <div class="list-td is-blue"><span class="list-td-cell">修读中</span></div>
                <div class="list-td"><span class="list-td-cell"></span></div>
                <div class="list-td"><span class="list-td-cell"></span></div>
              </div>
              <div class="list-tr">
                <div class="list-td"><span class="list-td-cell">101307026</span></div>
                <div class="list-td"><span class="list-td-cell">形势与政策(六)</span></div>
                <div class="list-td"><span class="list-td-cell">0.25</span></div>
                <div class="list-td"><span class="list-td-cell">必修</span></div>
                <div class="list-td"><span class="list-td-cell">公共必修课</span></div>
                <div class="list-td is-failed"><span class="list-td-cell">待修读</span></div>
                <div class="list-td"><span class="list-td-cell"></span></div>
                <div class="list-td"><span class="list-td-cell"></span></div>
              </div>
            </div>
          </div>
        </div>
        </body>
    """.trimIndent()

    private fun createDefaultSettingsBackup(): DefaultSettingsBackup {
        val fakeAppSettings = object : AppSettingsPort {
            private val display = MutableStateFlow(DisplaySettingsBundle())
            private val reminder = MutableStateFlow(ReminderSettings())
            private val calendar = MutableStateFlow(CalendarSettings())
            private val prefs = MutableStateFlow(AppPreferences())
            override val displaySettings = display
            override suspend fun updateDisplaySettings(bundle: DisplaySettingsBundle) { display.value = bundle }
            override suspend fun updateTimetableDisplay(timetableId: Long, settings: ScheduleDisplaySettings?) {}
            override val reminderSettings = reminder
            override suspend fun updateReminderSettings(settings: ReminderSettings) { reminder.value = settings }
            override val calendarSettings = calendar
            override suspend fun updateCalendarSettings(settings: CalendarSettings) { calendar.value = settings }
            override val appPreferences = prefs
            override suspend fun updateAppPreferences(preferences: AppPreferences) { prefs.value = preferences }
            override suspend fun exportNamespace(): String = ""
            override suspend fun reset() {}
        }
        val fakeThemeSettings = object : ThemeSettingsPort {
            override val settings = MutableStateFlow(ThemeSettings())
            override suspend fun update(settings: ThemeSettings) {}
        }
        val fakeWallpapers = object : WallpaperStore {
            override suspend fun prepare(sourceUri: String): PreparedWallpaper? = null
            override suspend fun commit(tempAbsolutePath: String, slot: String, crop: CropPixelRegion?): String? = null
            override suspend fun discard(tempAbsolutePath: String) {}
            override fun resolve(relativePath: String): String? = null
            override suspend fun delete(relativePath: String) {}
            override suspend fun readBytes(relativePath: String): ByteArray? = null
            override suspend fun prepareFromBytes(bytes: ByteArray): PreparedWallpaper? = null
        }
        val timetables = RoomTimetableRepository(db)
        val noopReminder = object : ReminderCoordinatorPort {
            override val status = MutableStateFlow(ReminderStatus())
            override suspend fun setEnabled(enabled: Boolean) {}
            override suspend fun setLeadMinutes(minutes: Int) {}
            override suspend fun setCourseEnabled(key: String, enabled: Boolean) {}
            override suspend fun refresh() {}
        }
        val noopCalendar = object : CalendarCoordinatorPort {
            override val status = MutableStateFlow(CalendarSyncStatus())
            override suspend fun setEnabled(enabled: Boolean) {}
            override suspend fun selectCalendar(calendarId: Long) {}
            override suspend fun refresh() {}
        }
        return DefaultSettingsBackup(
            appSettings = fakeAppSettings,
            themeSettings = fakeThemeSettings,
            wallpapers = fakeWallpapers,
            timetableRepository = timetables,
            reminderCoordinator = noopReminder,
            calendarCoordinator = noopCalendar,
            clock = clock,
            creditProgressRepository = creditRepo,
            schoolAuth = authRepo,
        )
    }

    private class InMemoryCredentialStore : SchoolCredentialStore {
        private val map = mutableMapOf<String, SchoolCredential>()
        override suspend fun write(credentialRef: String, credential: SchoolCredential) {
            map[credentialRef] = credential
        }

        override suspend fun read(credentialRef: String): SchoolCredential? = map[credentialRef]
        override suspend fun clear(credentialRef: String) {
            map.remove(credentialRef)
        }
    }
}
