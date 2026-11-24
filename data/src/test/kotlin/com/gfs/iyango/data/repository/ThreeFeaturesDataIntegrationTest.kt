package com.gfs.iyango.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.gfs.iyango.core.domain.AppSettingsPort
import com.gfs.iyango.core.domain.DiagnosticExport
import com.gfs.iyango.core.domain.DiagnosticLogPort
import com.gfs.iyango.core.domain.DiagnosticLogSummary
import com.gfs.iyango.core.domain.GradeChangeNotificationPayload
import com.gfs.iyango.core.domain.GradeCheckOutcome
import com.gfs.iyango.core.domain.GradeCheckSchedulerPort
import com.gfs.iyango.core.domain.GradeCheckTrigger
import com.gfs.iyango.core.domain.GradeCurrentSemesterResolverPort
import com.gfs.iyango.core.domain.GradeNotificationPort
import com.gfs.iyango.core.domain.GradeQueryPort
import com.gfs.iyango.core.domain.GradeQueryResult
import com.gfs.iyango.core.domain.GradeRepositoryPort
import com.gfs.iyango.core.domain.ImportWriteMode
import com.gfs.iyango.core.domain.RefreshOutcome
import com.gfs.iyango.core.domain.RefreshResource
import com.gfs.iyango.core.domain.RefreshState
import com.gfs.iyango.core.domain.RefreshTaskState
import com.gfs.iyango.core.domain.ScheduleImportOptions
import com.gfs.iyango.core.domain.ScheduleImportReadResult
import com.gfs.iyango.core.domain.ScheduleShareCodec
import com.gfs.iyango.core.domain.ScheduleShareDecodeResult
import com.gfs.iyango.core.domain.SchoolAuthPort
import com.gfs.iyango.core.domain.SessionExpiredException
import com.gfs.iyango.core.model.AppPreferences
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.DiagnosticEvent
import com.gfs.iyango.core.model.DisplaySettingsBundle
import com.gfs.iyango.core.model.GradeRecord
import com.gfs.iyango.core.model.GradeTerm
import com.gfs.iyango.core.model.PinnedClassroomBuilding
import com.gfs.iyango.core.model.ReminderSettings
import com.gfs.iyango.core.model.ScheduleDisplaySettings
import com.gfs.iyango.core.model.SchoolConnection
import com.gfs.iyango.core.model.SchoolConnectionStatus
import com.gfs.iyango.core.model.SchoolLoginResult
import com.gfs.iyango.core.model.SchoolProfile
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.TimeSlot
import com.gfs.iyango.core.model.Timetable
import com.gfs.iyango.core.model.WeekType
import com.gfs.iyango.data.db.IYangoDatabase
import com.gfs.iyango.data.db.SchoolConnectionEntity
import com.gfs.iyango.data.json.ExportScheduleService
import com.gfs.iyango.data.json.ImportScheduleService
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThreeFeaturesDataIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: IYangoDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), IYangoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `share code roundtrip preserves monday slot 1 endWeek 18 override and omits displayConfig`() = runTest {
        val dataPort = RoomScheduleDataPort(db)
        val timetableRepo = RoomTimetableRepository(db)
        val tid = timetableRepo.upsert(Timetable(name = "测试原课表", isCurrent = true))
        dataPort.saveSemester(
            Semester(timetableId = tid, name = "2026-2027-1", startDate = LocalDate.of(2026, 9, 1), totalWeeks = 18, isCurrent = true),
        )
        dataPort.saveTimeSlots(
            tid,
            listOf(
                TimeSlot(timetableId = tid, section = 1, startTime = "08:20", endTime = "09:05"),
                TimeSlot(timetableId = tid, section = 2, startTime = "09:10", endTime = "09:55"),
            ),
        )
        val savedIds = dataPort.saveCourses(
            listOf(
                Course(
                    timetableId = tid,
                    name = "高等数学",
                    teacher = "王老师",
                    classroom = "A101",
                    dayOfWeek = 1,
                    startSection = 1,
                    endSection = 2,
                    startWeek = 1,
                    endWeek = 18,
                    weekType = WeekType.ODD,
                ),
                Course(
                    timetableId = tid,
                    name = "计算机导论",
                    teacher = "李老师",
                    classroom = "B203",
                    dayOfWeek = 3,
                    startSection = 1,
                    endSection = 1,
                    startWeek = 5,
                    endWeek = 18,
                    selectedWeeks = listOf(5, 7, 9, 18),
                ),
            ),
        )
        // Add an override referencing the first course id
        dataPort.saveCourses(
            listOf(
                Course(
                    timetableId = tid,
                    name = "高等数学",
                    teacher = "王老师",
                    classroom = "C305",
                    dayOfWeek = 2,
                    startSection = 3,
                    endSection = 4,
                    startWeek = 4,
                    endWeek = 4,
                    overrideOfId = savedIds[0],
                    overrideWeek = 4,
                ),
            ),
        )

        val appSettings = DataStoreAppSettings(
            PreferenceDataStoreFactory.create { File(tempFolder.newFolder("ds1"), "app.preferences_pb") },
        )
        appSettings.updateTimetableDisplay(tid, ScheduleDisplaySettings(showWeekends = false, textSizeSp = 15f))

        val transferPort = RoomScheduleTransferPort(
            exportService = ExportScheduleService(db),
            importService = ImportScheduleService(db),
            appSettings = appSettings,
        )

        val exported = transferPort.exportShareCode(tid)
        val decoded = ScheduleShareCodec.decode(exported.shareCode) as ScheduleShareDecodeResult.Success
        val readResult = transferPort.read(decoded.jsonBytes) as ScheduleImportReadResult.Ready

        assertEquals(0, readResult.preview.issueCount)
        assertFalse(readResult.preview.hasDisplayConfig)
        assertEquals(3, readResult.preview.courseCount)
        assertEquals(2, readResult.preview.timeSlotCount)
        assertEquals("2026-2027-1", readResult.preview.semesterName)

        val commitResult = transferPort.commit(
            token = readResult.preview.token,
            options = ScheduleImportOptions(mode = ImportWriteMode.CREATE),
        )
        assertEquals(3, commitResult.courseCount)

        val importedCourses = db.courseDao().getAllByTimetable(commitResult.timetableId).map { it.toModel() }
        val math = importedCourses.first { it.name == "高等数学" && it.overrideOfId == 0L }
        assertEquals(1, math.dayOfWeek)
        assertEquals(1, math.startSection)
        assertEquals(18, math.endWeek)
        assertEquals(WeekType.ODD, math.weekType)

        val intro = importedCourses.first { it.name == "计算机导论" }
        assertEquals(5, intro.startWeek)
        assertEquals(18, intro.endWeek)
        assertEquals(listOf(5, 7, 9, 18), intro.selectedWeeks)

        val overrideRow = importedCourses.first { it.overrideOfId > 0L }
        assertEquals(math.id, overrideRow.overrideOfId)
        assertEquals(4, overrideRow.overrideWeek)

        val importedSlots = db.timeSlotDao().getByTimetable(commitResult.timetableId)
        assertEquals(listOf(1, 2), importedSlots.map { it.section })
    }

    @Test
    fun `datastore and legacy backup dto preserve pinned buildings and grade settings`() = runTest {
        val ds = DataStoreAppSettings(
            PreferenceDataStoreFactory.create { File(tempFolder.newFolder("ds2"), "app.preferences_pb") },
        )
        val pin = PinnedClassroomBuilding("C1", "本部", "B1", "一教")
        ds.updateAppPreferences(AppPreferences(pinnedClassroomBuildings = listOf(pin)))
        ds.updateReminderSettings(
            ReminderSettings(
                gradeNotifyEnabled = true,
                gradeCheckIntervalHours = 6,
                gradeNotifyHideScoreOnLockscreen = false,
                gradeSprintEnabled = true,
                gradeSprintIntervalMinutes = 15,
                gradeSprintUntilMillis = 1_800_000_000_000L,
                gradeSprintDurationDays = 5,
                gradeContextTriggerEnabled = false,
            ),
        )

        assertEquals(listOf(pin), ds.appPreferences.first().pinnedClassroomBuildings)
        val rem = ds.reminderSettings.first()
        assertTrue(rem.gradeNotifyEnabled)
        assertEquals(6, rem.gradeCheckIntervalHours)
        assertFalse(rem.gradeNotifyHideScoreOnLockscreen)
        assertTrue(rem.gradeSprintEnabled)
        assertEquals(15, rem.gradeSprintIntervalMinutes)
        assertEquals(1_800_000_000_000L, rem.gradeSprintUntilMillis)
        assertEquals(5, rem.gradeSprintDurationDays)
        assertFalse(rem.gradeContextTriggerEnabled)

        // Legacy backup without sprint fields defaults safely
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val legacyBackupPrefs = json.decodeFromString(PreferencesDto.serializer(), """{"defaultStartTab":1}""")
        val merged = legacyBackupPrefs.applyTo(AppPreferences(pinnedClassroomBuildings = listOf(pin)))
        assertEquals(listOf(pin), merged.pinnedClassroomBuildings)

        val backupRem = json.decodeFromString(ReminderDto.serializer(), """{"gradeNotifyEnabled":true,"gradeSprintEnabled":true,"gradeSprintIntervalMinutes":15,"gradeSprintDurationDays":3}""").toModel()
        assertTrue(backupRem.gradeNotifyEnabled)
        assertTrue(backupRem.gradeSprintEnabled)
        assertEquals(15, backupRem.gradeSprintIntervalMinutes)
        assertEquals(3, backupRem.gradeSprintDurationDays)
        assertTrue(backupRem.gradeContextTriggerEnabled)
    }

    @Test
    fun `gradeMonitorCoordinator handles baseline silent dedupe session pause and manual isolation`() = kotlinx.coroutines.runBlocking {
        var nowMs = Instant.parse("2026-11-24T08:00:00Z").toEpochMilli()
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
        }
        val appSettings = DataStoreAppSettings(
            PreferenceDataStoreFactory.create { File(tempFolder.newFolder("ds3"), "app.preferences_pb") },
        )
        appSettings.updateReminderSettings(ReminderSettings(gradeNotifyEnabled = true, gradeCheckIntervalHours = 4))

        val notifiedStore = GradeNotifiedStore(
            PreferenceDataStoreFactory.create { File(tempFolder.newFolder("ds4"), "notify.preferences_pb") },
        )
        val bridge = GradeRefreshNotificationBridge()
        val gradeRepo = RoomGradeRepository(db, clock)

        val connEntity = SchoolConnectionEntity(
            id = "c1", schoolId = "ygu", accountSubject = "sub1", maskedAccount = "2024***1",
            sessionRevision = 1, status = SchoolConnectionStatus.ACTIVE, credentialRef = "ref",
            rememberPassword = true, createdAt = 1, lastLoginAt = 1, lastError = "",
            displayName = "张三", studentNo = "2024001", college = "", major = "", className = "", gender = "", avatarUrl = "",
        )
        db.schoolConnectionDao().upsert(connEntity)
        val connModel = SchoolConnection(id = "c1", accountSubject = "sub1", status = SchoolConnectionStatus.ACTIVE, rememberPassword = true)
        val connFlow = MutableStateFlow(connModel)
        val auth = object : SchoolAuthPort {
            override fun observeConnections(): Flow<List<SchoolConnection>> = connFlow.map { listOf(it) }
            override fun observeActiveConnection(): Flow<SchoolConnection?> = connFlow
            override suspend fun getConnection(connectionId: String) = connFlow.value
            override suspend fun historyAccount(connectionId: String): String? = "2024001"
            override suspend fun login(account: String, password: String, rememberPassword: Boolean): SchoolLoginResult =
                SchoolLoginResult.Success(connFlow.value)
            override suspend fun restoreSession(connectionId: String): SchoolLoginResult =
                SchoolLoginResult.Success(connFlow.value)
            override suspend fun fetchProfile(connectionId: String): Result<SchoolProfile> =
                Result.success(SchoolProfile())
            override suspend fun logout(connectionId: String, clearSavedPassword: Boolean) = Unit
            override suspend fun deleteConnection(connectionId: String) = Unit
            override suspend fun clearHistory(keepConnectionId: String) = Unit
        }

        var nextQueryResult: GradeQueryResult = GradeQueryResult.Success(
            records = listOf(
                GradeRecord(accountKey = "sub1", semester = "*", courseName = "线代", courseCode = "M2", credit = 2.0, scoreText = "80", gradePoint = 3.0, attribute = "必修", schoolRecordId = "ygu:2026-2027-1|M2"),
            ),
            complete = true,
        )
        var queryCalls = 0
        val queryPort = object : GradeQueryPort {
            override suspend fun listTerms(connectionId: String) = Result.success(emptyList<GradeTerm>())
            override suspend fun query(connectionId: String, semesterValue: String): GradeQueryResult {
                queryCalls++
                return nextQueryResult
            }
        }
        val handler = GradeRefreshHandler(db = db, query = queryPort, repository = gradeRepo, notificationBridge = bridge)
        val registry = RefreshHandlerRegistry().apply { register(handler) }
        val refreshCoord = AppRefreshCoordinator(registry, object : DiagnosticLogPort {
            override fun observeRecent(limit: Int): Flow<List<DiagnosticEvent>> = flowOf(emptyList())
            override val summary: Flow<DiagnosticLogSummary> = flowOf(DiagnosticLogSummary())
            override suspend fun record(event: DiagnosticEvent) = Unit
            override suspend fun export(): DiagnosticExport = DiagnosticExport("diag.txt", "")
            override suspend fun clear() = Unit
        }, clock)

        val posted = mutableListOf<GradeChangeNotificationPayload>()
        val postedPrivacyFlags = mutableListOf<Boolean>()
        val cancelledAccounts = mutableListOf<String>()
        val notifPort = object : GradeNotificationPort {
            override fun notificationsAllowed() = true
            override fun showGradeChange(payload: GradeChangeNotificationPayload, hideScoreOnLockscreen: Boolean): Boolean {
                posted += payload
                postedPrivacyFlags += hideScoreOnLockscreen
                return true
            }
            override fun cancelForAccount(accountKey: String) {
                cancelledAccounts += accountKey
            }
            override fun cancelAll() = Unit
        }
        var scheduledDelays = mutableListOf<Long>()
        var cancelCalls = 0
        var hasNet = true
        val scheduler = object : GradeCheckSchedulerPort {
            override fun scheduleNext(delayMillis: Long) { scheduledDelays += delayMillis }
            override fun cancel() { cancelCalls++ }
            override fun hasNetwork() = hasNet
        }

        val monitor = DefaultGradeMonitorCoordinator(
            appSettings = appSettings,
            schoolAuth = auth,
            refreshCoordinator = refreshCoord,
            notificationPort = notifPort,
            schedulerPort = scheduler,
            notifiedStore = notifiedStore,
            notificationBridge = bridge,
            clock = clock,
        )

        // Manual refresh on Grades page alone must never notify
        assertEquals(GradeCheckOutcome.IDLE, monitor.checkOnTrigger(GradeCheckTrigger.USER_MANUAL_IN_GRADES_PAGE))

        // First baseline sync -> CHECKED_NO_CHANGES, 0 notifications
        assertEquals(GradeCheckOutcome.CHECKED_NO_CHANGES, monitor.checkOnTrigger(GradeCheckTrigger.BACKGROUND_PERIODIC))
        assertEquals(0, posted.size)

        // New grade arrives -> NOTIFIED with GPA delta
        nowMs += 40 * 60_000L
        nextQueryResult = GradeQueryResult.Success(
            records = listOf(
                GradeRecord(accountKey = "sub1", semester = "*", courseName = "线代", courseCode = "M2", credit = 2.0, scoreText = "80", gradePoint = 3.0, attribute = "必修", schoolRecordId = "ygu:2026-2027-1|M2"),
                GradeRecord(accountKey = "sub1", semester = "*", courseName = "高数", courseCode = "M1", credit = 2.0, scoreText = "92", gradePoint = 4.2, attribute = "必修", schoolRecordId = "ygu:2026-2027-1|M1"),
            ),
            complete = true,
        )
        assertEquals(GradeCheckOutcome.NOTIFIED, monitor.checkOnTrigger(GradeCheckTrigger.FOREGROUND_SILENT))
        assertEquals(1, posted.size)
        assertEquals("新增 1 门成绩", posted.single().summaryTitle)

        // Immediate repeat foreground check respects 30min cooldown
        assertEquals(GradeCheckOutcome.SKIPPED_COOLDOWN, monitor.checkOnTrigger(GradeCheckTrigger.FOREGROUND_SILENT))

        // SessionExpired pauses monitor & cancels scheduler
        nextQueryResult = GradeQueryResult.SessionExpired("登录过期")
        connFlow.value = connModel.copy(status = SchoolConnectionStatus.EXPIRED)
        assertEquals(GradeCheckOutcome.PAUSED_SESSION_EXPIRED, monitor.checkOnTrigger(GradeCheckTrigger.BACKGROUND_PERIODIC))
        assertTrue(cancelCalls >= 1)

        // Circuit breaker: subsequent background check returns PAUSED_SESSION_EXPIRED without hitting school server
        val callsAtPause = queryCalls
        assertEquals(GradeCheckOutcome.PAUSED_SESSION_EXPIRED, monitor.checkOnTrigger(GradeCheckTrigger.BACKGROUND_PERIODIC))
        assertEquals(callsAtPause, queryCalls)
    }

    @Test
    fun `sprint mode queries current semester enforces daytime quiet hours context triggers and auto downgrades`() = kotlinx.coroutines.runBlocking {
        var nowMs = Instant.parse("2026-11-24T10:00:00Z").toEpochMilli()
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
        }
        val appSettings = DataStoreAppSettings(
            PreferenceDataStoreFactory.create { File(tempFolder.newFolder("ds5"), "app.preferences_pb") },
        )
        val notifiedStore = GradeNotifiedStore(
            PreferenceDataStoreFactory.create { File(tempFolder.newFolder("ds6"), "notify.preferences_pb") },
        )
        val bridge = GradeRefreshNotificationBridge()
        val gradeRepo = RoomGradeRepository(db, clock)
        db.schoolConnectionDao().upsert(
            SchoolConnectionEntity(
                id = "c2", schoolId = "ygu", accountSubject = "sub2", maskedAccount = "2024***2",
                sessionRevision = 1, status = SchoolConnectionStatus.ACTIVE, credentialRef = "ref",
                rememberPassword = true, createdAt = 1, lastLoginAt = 1, lastError = "",
                displayName = "李四", studentNo = "2024002", college = "", major = "", className = "", gender = "", avatarUrl = "",
            ),
        )
        val connFlow = MutableStateFlow(
            SchoolConnection(id = "c2", accountSubject = "sub2", status = SchoolConnectionStatus.ACTIVE, rememberPassword = true),
        )
        val auth = object : SchoolAuthPort {
            override fun observeConnections(): Flow<List<SchoolConnection>> = connFlow.map { listOf(it) }
            override fun observeActiveConnection(): Flow<SchoolConnection?> = connFlow
            override suspend fun getConnection(connectionId: String) = connFlow.value
            override suspend fun historyAccount(connectionId: String): String? = "2024002"
            override suspend fun login(account: String, password: String, rememberPassword: Boolean): SchoolLoginResult =
                SchoolLoginResult.Success(connFlow.value)
            override suspend fun restoreSession(connectionId: String): SchoolLoginResult =
                SchoolLoginResult.Success(connFlow.value)
            override suspend fun fetchProfile(connectionId: String): Result<SchoolProfile> =
                Result.success(SchoolProfile())
            override suspend fun logout(connectionId: String, clearSavedPassword: Boolean) = Unit
            override suspend fun deleteConnection(connectionId: String) = Unit
            override suspend fun clearHistory(keepConnectionId: String) = Unit
        }

        val queriedSemesters = java.util.concurrent.CopyOnWriteArrayList<String>()
        var records = listOf(
            GradeRecord(accountKey = "sub2", semester = "*", courseName = "线代", courseCode = "M2", credit = 2.0, scoreText = "80", gradePoint = 3.0, attribute = "必修", schoolRecordId = "ygu:2026-2027-1|M2"),
        )
        val queryPort = object : GradeQueryPort {
            override suspend fun listTerms(connectionId: String) = Result.success(emptyList<GradeTerm>())
            override suspend fun query(connectionId: String, semesterValue: String): GradeQueryResult {
                queriedSemesters += semesterValue
                return GradeQueryResult.Success(records = records, complete = true)
            }
        }
        val handler = GradeRefreshHandler(db = db, query = queryPort, repository = gradeRepo, notificationBridge = bridge)
        val registry = RefreshHandlerRegistry().apply { register(handler) }
        val refreshCoord = AppRefreshCoordinator(
            registry,
            object : DiagnosticLogPort {
                override fun observeRecent(limit: Int): Flow<List<DiagnosticEvent>> = flowOf(emptyList())
                override val summary: Flow<DiagnosticLogSummary> = flowOf(DiagnosticLogSummary())
                override suspend fun record(event: DiagnosticEvent) = Unit
                override suspend fun export(): DiagnosticExport = DiagnosticExport("diag.txt", "")
                override suspend fun clear() = Unit
            },
            clock,
        )
        val posted = mutableListOf<GradeChangeNotificationPayload>()
        val notifPort = object : GradeNotificationPort {
            override fun notificationsAllowed() = true
            override fun showGradeChange(payload: GradeChangeNotificationPayload, hideScoreOnLockscreen: Boolean): Boolean {
                posted += payload
                return true
            }
            override fun cancelForAccount(accountKey: String) = Unit
            override fun cancelAll() = Unit
        }
        val scheduledDelays = mutableListOf<Long>()
        var onWifi = false
        var contextTriggersActive = false
        val scheduler = object : GradeCheckSchedulerPort {
            override fun scheduleNext(delayMillis: Long) { scheduledDelays += delayMillis }
            override fun cancel() = Unit
            override fun hasNetwork() = true
            override fun isOnWifi() = onWifi
            override fun setContextTriggersActive(enabled: Boolean) { contextTriggersActive = enabled }
        }
        val semesterResolver = object : GradeCurrentSemesterResolverPort {
            override suspend fun resolveCurrentSemester(connectionId: String, accountKey: String) = "2026-2027-1"
        }

        val monitor = DefaultGradeMonitorCoordinator(
            appSettings = appSettings,
            schoolAuth = auth,
            refreshCoordinator = refreshCoord,
            notificationPort = notifPort,
            schedulerPort = scheduler,
            notifiedStore = notifiedStore,
            notificationBridge = bridge,
            clock = clock,
            semesterResolver = semesterResolver,
        )

        // Establish initial ALL_SEMESTERS baseline before sprint
        monitor.setEnabled(true)
        assertEquals(GradeCheckOutcome.CHECKED_NO_CHANGES, monitor.checkOnTrigger(GradeCheckTrigger.BACKGROUND_PERIODIC))
        assertEquals("", queriedSemesters.last())

        // Activate 15-min sprint for 3 days
        monitor.setSprintDurationDays(3)
        monitor.setSprintIntervalMinutes(15)
        monitor.setSprintEnabled(true)
        assertEquals(15 * 60_000L, scheduledDelays.last())
        assertTrue(contextTriggersActive)
        assertTrue(monitor.status.value.sprintActive)

        // Wi-Fi trigger requires Wi-Fi transport; when connected, queries only "2026-2027-1" and uses ALL_SEMESTERS baseline
        assertEquals(GradeCheckOutcome.SKIPPED_NO_NETWORK, monitor.checkOnTrigger(GradeCheckTrigger.WIFI_CONNECTED))
        onWifi = true
        nowMs += 31 * 60_000L
        records = records + GradeRecord(
            accountKey = "sub2",
            semester = "2026-2027-1",
            courseName = "高数",
            courseCode = "M1",
            credit = 2.0,
            scoreText = "92",
            gradePoint = 4.2,
            attribute = "必修",
            schoolRecordId = "ygu:2026-2027-1|M1",
        )
        assertEquals(GradeCheckOutcome.NOTIFIED, monitor.checkOnTrigger(GradeCheckTrigger.WIFI_CONNECTED))
        assertTrue(queriedSemesters.contains("2026-2027-1"))
        assertEquals("新增 1 门成绩", posted.single().summaryTitle)

        // 10-minute foreground cooldown during sprint
        nowMs += 5 * 60_000L
        assertEquals(GradeCheckOutcome.SKIPPED_COOLDOWN, monitor.checkOnTrigger(GradeCheckTrigger.FOREGROUND_SILENT))
        nowMs += 6 * 60_000L
        assertEquals(GradeCheckOutcome.CHECKED_NO_CHANGES, monitor.checkOnTrigger(GradeCheckTrigger.FOREGROUND_SILENT))

        // Quiet hours (23:00 UTC) blocks background/context triggers and reschedules to 08:00 next morning
        nowMs = Instant.parse("2026-11-24T23:00:00Z").toEpochMilli()
        assertEquals(GradeCheckOutcome.SKIPPED_QUIET_HOURS, monitor.checkOnTrigger(GradeCheckTrigger.POWER_CONNECTED))
        assertEquals(GradeCheckOutcome.SKIPPED_QUIET_HOURS, monitor.checkOnTrigger(GradeCheckTrigger.BACKGROUND_PERIODIC))
        assertEquals(9 * 3_600_000L, scheduledDelays.last())

        // Disabling context triggers blocks Wi-Fi & Power
        nowMs = Instant.parse("2026-11-25T10:00:00Z").toEpochMilli()
        monitor.setContextTriggerEnabled(false)
        assertFalse(contextTriggersActive)
        assertEquals(GradeCheckOutcome.SKIPPED_DISABLED, monitor.checkOnTrigger(GradeCheckTrigger.WIFI_CONNECTED))

        // Advancing past 3-day sprint deadline auto-downgrades sprint and reverts to ALL_SEMESTERS ("")
        nowMs += 4 * 24 * 3_600_000L
        queriedSemesters.clear()
        assertEquals(GradeCheckOutcome.CHECKED_NO_CHANGES, monitor.checkOnTrigger(GradeCheckTrigger.BACKGROUND_PERIODIC))
        assertEquals(listOf(""), queriedSemesters.toList())
        assertFalse(appSettings.reminderSettings.first().gradeSprintEnabled)
        assertEquals(4 * 3_600_000L, scheduledDelays.last())
    }
}
