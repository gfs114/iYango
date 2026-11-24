package com.gfs.iyango.feature.main

import android.graphics.Color as AndroidColor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.domain.AppSettingsPort
import com.gfs.iyango.core.domain.CalendarClearResult
import com.gfs.iyango.core.domain.CalendarCoordinatorPort
import com.gfs.iyango.core.domain.CalendarSyncStatus
import com.gfs.iyango.core.domain.ClassroomRepositoryPort
import com.gfs.iyango.core.domain.GradeCheckOutcome
import com.gfs.iyango.core.domain.GradeCheckTrigger
import com.gfs.iyango.core.domain.GradeMonitorCoordinatorPort
import com.gfs.iyango.core.domain.GradeMonitorStatus
import com.gfs.iyango.core.domain.ImportWriteMode
import com.gfs.iyango.core.domain.QrCodeEncoder
import com.gfs.iyango.core.domain.QrEncodeResult
import com.gfs.iyango.core.domain.ReminderCoordinatorPort
import com.gfs.iyango.core.domain.ReminderStatus
import com.gfs.iyango.core.domain.ScheduleExportResult
import com.gfs.iyango.core.domain.ScheduleImportCommitResult
import com.gfs.iyango.core.domain.ScheduleImportItem
import com.gfs.iyango.core.domain.ScheduleImportOptions
import com.gfs.iyango.core.domain.ScheduleImportPreview
import com.gfs.iyango.core.domain.ScheduleImportReadResult
import com.gfs.iyango.core.domain.ScheduleImportTargetInfo
import com.gfs.iyango.core.domain.ScheduleShareCodec
import com.gfs.iyango.core.domain.ScheduleShareExportResult
import com.gfs.iyango.core.domain.ScheduleTransferPort
import com.gfs.iyango.core.domain.SchoolAuthPort
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.model.AppPreferences
import com.gfs.iyango.core.model.CalendarSettings
import com.gfs.iyango.core.model.ClassroomBuilding
import com.gfs.iyango.core.model.ClassroomCell
import com.gfs.iyango.core.model.ClassroomCellState
import com.gfs.iyango.core.model.ClassroomDetail
import com.gfs.iyango.core.model.ClassroomDetailRequest
import com.gfs.iyango.core.model.ClassroomMetadata
import com.gfs.iyango.core.model.ClassroomOption
import com.gfs.iyango.core.model.ClassroomPeriodGroup
import com.gfs.iyango.core.model.ClassroomQuery
import com.gfs.iyango.core.model.ClassroomResult
import com.gfs.iyango.core.model.ClassroomRoom
import com.gfs.iyango.core.model.DisplaySettingsBundle
import com.gfs.iyango.core.model.PinnedClassroomBuilding
import com.gfs.iyango.core.model.ReminderSettings
import com.gfs.iyango.core.model.ScheduleDisplaySettings
import com.gfs.iyango.core.model.SchoolConnection
import com.gfs.iyango.core.model.SchoolConnectionStatus
import com.gfs.iyango.core.model.SchoolLoginResult
import com.gfs.iyango.core.model.SchoolProfile
import com.gfs.iyango.core.model.Timetable
import com.gfs.iyango.feature.main.classroom.ClassroomViewModel
import com.gfs.iyango.feature.main.importexport.ImportExportViewModel
import com.gfs.iyango.feature.main.importexport.renderQrBitmap
import com.gfs.iyango.feature.main.settings.ReminderCalendarScreen
import com.gfs.iyango.feature.main.settings.ReminderCalendarViewModel
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThreeFeaturesUiAndViewModelTest {

    @get:Rule
    val compose = createComposeRule()

    private val store = ViewModelStore()

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `qr bitmap has pure white quiet zone corners and pure black dark modules`() {
        val encoded = QrCodeEncoder.encode("IYANGO-TT1:test_payload_1234567890") as QrEncodeResult.Ready
        val bmp = renderQrBitmap(encoded.matrix, targetSizePx = 400, quietZoneModules = 4)
        try {
            assertEquals(AndroidColor.WHITE, bmp.getPixel(0, 0))
            assertEquals(AndroidColor.WHITE, bmp.getPixel(bmp.width - 1, bmp.height - 1))
            val modulePx = bmp.width / (encoded.matrix.size + 8)
            // Corner of top-left finder pattern at (quietZone, quietZone) must be pure black
            val fx = 4 * modulePx + modulePx / 2
            val fy = 4 * modulePx + modulePx / 2
            assertEquals(AndroidColor.BLACK, bmp.getPixel(fx, fy))
        } finally {
            bmp.recycle()
        }
    }

    @Test
    fun `importExportViewModel handles share code generation and paste import`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val sampleJson = """{"meta":{"app":"ClassSchedule","version":1},"courses":[{"name":"高等数学","dayOfWeek":1,"startSection":1,"endSection":2,"startWeek":1,"endWeek":18,"weekType":0}]}"""
        val shareToken = ScheduleShareCodec.encode(sampleJson)
        var lastReadBytes: ByteArray? = null

        val transfer = object : ScheduleTransferPort {
            override suspend fun export(timetableId: Long) = ScheduleExportResult("", "", 0, 0)
            override suspend fun exportShareCode(timetableId: Long) = ScheduleShareExportResult(
                shareCode = shareToken,
                timetableName = "计算机2401课表",
                courseCount = 1,
                timeSlotCount = 10,
                byteLength = shareToken.length,
            )
            override suspend fun read(bytes: ByteArray): ScheduleImportReadResult {
                lastReadBytes = bytes
                return ScheduleImportReadResult.Ready(
                    ScheduleImportPreview(
                        courseCount = 1,
                        issueCount = 0,
                        issues = emptyList(),
                        semesterName = "2026-2027-1",
                        timeSlotCount = 10,
                        fileDigest = "digest-1",
                        duplicateExisting = false,
                        token = "digest-1",
                        items = listOf(ScheduleImportItem("r0", 1, "高等数学", "", "", 1, 1, 2, 1, 18, 0, false)),
                    ),
                )
            }
            override suspend fun inspectTarget(timetableId: Long): ScheduleImportTargetInfo? = null
            override suspend fun commit(token: String, options: ScheduleImportOptions) =
                ScheduleImportCommitResult(1, false, false, 1, emptyList())
        }
        val timetableRepo = object : TimetableRepositoryPort {
            private val current = Timetable(id = 1, name = "计算机2401课表", isCurrent = true)
            override fun observeAll(): Flow<List<Timetable>> = flowOf(listOf(current))
            override fun observeCurrent(): Flow<Timetable?> = flowOf(current)
            override suspend fun setCurrent(id: Long) = Unit
            override suspend fun upsert(timetable: Timetable) = 1L
            override suspend fun delete(id: Long) = Unit
            override suspend fun duplicate(id: Long) = 2L
        }

        val vm = ImportExportViewModel(transfer, timetableRepo).also { store.put("ie", it) }

        vm.requestShareCode()
        runCurrent()
        val share = vm.shareState.value
        assertNotNull(share)
        assertEquals(shareToken, share!!.shareCode)
        assertTrue(share.qrResult is QrEncodeResult.Ready)

        // Wrong kind code (glass preset) reports targeted message
        assertFalse(vm.importFromShareCode("IYANGO-GLASS-V1:abcdef1234567890"))
        assertTrue(vm.readError.value!!.contains("外观设置"))

        // Chat-wrapped share token decodes & triggers preview
        assertTrue(vm.importFromShareCode("快导入：\n$shareToken\n收到请回复"))
        runCurrent()
        assertEquals(sampleJson, String(lastReadBytes!!))
        assertEquals(1, vm.preview.value?.courseCount)
    }

    @Test
    fun `classroomViewModel filters zero-network on same day queries on cross day and pins buildings`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        // 2026-11-24 is Tuesday (dayOfWeek = 2), 09:00 UTC -> group 0 (08:20-09:55)
        val clock = Clock.fixed(Instant.parse("2026-11-24T09:00:00Z"), ZoneOffset.UTC)
        val prefsFlow = MutableStateFlow(AppPreferences())
        val appSettings = object : AppSettingsPort {
            override val displaySettings = flowOf(DisplaySettingsBundle())
            override suspend fun updateDisplaySettings(bundle: DisplaySettingsBundle) = Unit
            override suspend fun updateTimetableDisplay(timetableId: Long, settings: ScheduleDisplaySettings?) = Unit
            override val reminderSettings = flowOf(ReminderSettings())
            override suspend fun updateReminderSettings(settings: ReminderSettings) = Unit
            override val calendarSettings = flowOf(CalendarSettings())
            override suspend fun updateCalendarSettings(settings: CalendarSettings) = Unit
            override val appPreferences = prefsFlow
            override suspend fun updateAppPreferences(preferences: AppPreferences) {
                prefsFlow.value = preferences
            }
            override suspend fun exportNamespace(): String = ""
            override suspend fun reset() = Unit
        }
        val account = SchoolConnection(id = "c1", status = SchoolConnectionStatus.ACTIVE)
        val auth = object : SchoolAuthPort {
            override fun observeConnections(): Flow<List<SchoolConnection>> = flowOf(listOf(account))
            override fun observeActiveConnection(): Flow<SchoolConnection?> = flowOf(account)
            override suspend fun getConnection(connectionId: String) = account
            override suspend fun historyAccount(connectionId: String): String? = "2024001"
            override suspend fun login(account: String, password: String, rememberPassword: Boolean) =
                SchoolLoginResult.Success(SchoolConnection(id = "c1", status = SchoolConnectionStatus.ACTIVE))
            override suspend fun restoreSession(connectionId: String) =
                SchoolLoginResult.Success(SchoolConnection(id = "c1", status = SchoolConnectionStatus.ACTIVE))
            override suspend fun fetchProfile(connectionId: String) = Result.success(SchoolProfile())
            override suspend fun logout(connectionId: String, clearSavedPassword: Boolean) = Unit
            override suspend fun deleteConnection(connectionId: String) = Unit
            override suspend fun clearHistory(keepConnectionId: String) = Unit
        }
        val metadata = ClassroomMetadata(
            terms = listOf(ClassroomOption("2026-1", selected = true)),
            timeModes = listOf(ClassroomOption("1", selected = true)),
            campuses = listOf(ClassroomOption("C1", "本部", selected = true), ClassroomOption("C2", "南区")),
            weeks = listOf(ClassroomOption("1", selected = true)),
            days = listOf(ClassroomOption("1", "周一"), ClassroomOption("2", "周二", selected = true)),
            sections = listOf(ClassroomOption("0102", "第1-2节"), ClassroomOption("0304", "第3-4节")),
        )
        var queryCount = 0
        val groups = listOf(
            ClassroomPeriodGroup("0102", "第1-2节", "08:20", "09:55"),
            ClassroomPeriodGroup("0304", "第3-4节", "10:15", "11:50"),
        )
        val repo = object : ClassroomRepositoryPort {
            override suspend fun metadata(connectionId: String) = Result.success(metadata)
            override suspend fun buildings(connectionId: String, campusCode: String) = Result.success(
                if (campusCode == "C1") listOf(ClassroomBuilding("B1", "一教"), ClassroomBuilding("B2", "二教"))
                else listOf(ClassroomBuilding("S1", "实验楼"))
            )
            override suspend fun query(connectionId: String, query: ClassroomQuery): Result<ClassroomResult> {
                queryCount++
                return Result.success(
                    ClassroomResult(
                        query = query,
                        periodGroups = groups,
                        rooms = listOf(
                            ClassroomRoom(
                                name = "A101",
                                identifier = "A101",
                                cells = listOf(
                                    ClassroomCell(query.dayOfWeek, 0, ClassroomCellState.FREE),
                                    ClassroomCell(query.dayOfWeek, 1, ClassroomCellState.FREE),
                                ),
                            ),
                            ClassroomRoom(
                                name = "A102",
                                identifier = "A102",
                                cells = listOf(
                                    ClassroomCell(query.dayOfWeek, 0, ClassroomCellState.COURSE),
                                    ClassroomCell(query.dayOfWeek, 1, ClassroomCellState.FREE),
                                ),
                            ),
                        ),
                    ),
                )
            }
            override suspend fun detail(connectionId: String, request: ClassroomDetailRequest) = Result.success(ClassroomDetail())
        }

        val vm = ClassroomViewModel(auth, repo, appSettings, clock).also { store.put("cr", it) }
        runCurrent()
        assertEquals(1, queryCount)
        assertEquals(2, vm.state.value.filteredRooms.size)

        // Same day (Tuesday = 2) -> zero extra network call!
        vm.toggleAvailableNow()
        runCurrent()
        assertEquals(1, queryCount)
        assertEquals(listOf("A101"), vm.state.value.filteredRooms.map { it.room.name })

        // Switch day to Monday (1) & query; then toggle availableNow on -> aligns Tuesday & triggers 1 new query
        vm.toggleAvailableNow() // off
        vm.selectDay(1)
        vm.confirm()
        runCurrent()
        assertEquals(2, queryCount)
        vm.toggleAvailableNow()
        runCurrent()
        assertEquals(3, queryCount)
        assertEquals(2, vm.state.value.confirmed.dayOfWeek)

        // Pin building S1 on campus C2 & quick select from another campus
        vm.selectPinnedBuilding(PinnedClassroomBuilding("C2", "南区", "S1", "实验楼"))
        runCurrent()
        assertEquals("C2", vm.state.value.confirmed.campusCode)
        assertEquals("S1", vm.state.value.confirmed.buildingCode)
        assertEquals(4, queryCount)
    }

    @Test
    fun `reminderCalendarScreen shows grade notification card and triggers immediate check`() {
        var checkCalls = 0
        val monitorStatus = MutableStateFlow(GradeMonitorStatus(enabled = true, intervalHours = 4, notificationsAllowed = true, hasAccount = true))
        val gradeCoordinator = object : GradeMonitorCoordinatorPort {
            override val status = monitorStatus
            override suspend fun setEnabled(enabled: Boolean) { monitorStatus.value = monitorStatus.value.copy(enabled = enabled) }
            override suspend fun setIntervalHours(hours: Int) { monitorStatus.value = monitorStatus.value.copy(intervalHours = hours) }
            override suspend fun setHideScoreOnLockscreen(hide: Boolean) { monitorStatus.value = monitorStatus.value.copy(hideScoreOnLockscreen = hide) }
            override suspend fun checkOnTrigger(trigger: GradeCheckTrigger): GradeCheckOutcome {
                checkCalls++
                return GradeCheckOutcome.CHECKED_NO_CHANGES
            }
            override suspend fun syncSchedule() = Unit
            override suspend fun clearPostedNotification(accountKey: String) = Unit
        }
        val appSettings = object : AppSettingsPort {
            override val displaySettings = flowOf(DisplaySettingsBundle())
            override suspend fun updateDisplaySettings(bundle: DisplaySettingsBundle) = Unit
            override suspend fun updateTimetableDisplay(timetableId: Long, settings: ScheduleDisplaySettings?) = Unit
            override val reminderSettings = flowOf(ReminderSettings())
            override suspend fun updateReminderSettings(settings: ReminderSettings) = Unit
            override val calendarSettings = flowOf(CalendarSettings())
            override suspend fun updateCalendarSettings(settings: CalendarSettings) = Unit
            override val appPreferences = flowOf(AppPreferences())
            override suspend fun updateAppPreferences(preferences: AppPreferences) = Unit
            override suspend fun exportNamespace(): String = ""
            override suspend fun reset() = Unit
        }
        val reminderCoordinator = object : ReminderCoordinatorPort {
            override val status = MutableStateFlow(ReminderStatus())
            override suspend fun refresh() = Unit
            override suspend fun setEnabled(enabled: Boolean) = Unit
            override suspend fun setLeadMinutes(minutes: Int) = Unit
            override suspend fun setCourseEnabled(key: String, enabled: Boolean) = Unit
        }
        val calendarCoordinator = object : CalendarCoordinatorPort {
            override val status = MutableStateFlow(CalendarSyncStatus())
            override suspend fun refresh() = Unit
            override suspend fun setEnabled(enabled: Boolean) = Unit
            override suspend fun selectCalendar(calendarId: Long) = Unit
            override suspend fun clearCalendar(): CalendarClearResult = CalendarClearResult.Completed(0)
        }

        lateinit var vm: ReminderCalendarViewModel
        compose.runOnIdle {
            vm = ReminderCalendarViewModel(appSettings, reminderCoordinator, calendarCoordinator, gradeCoordinator)
                .also { store.put("rc-grade", it) }
        }
        compose.setContent {
            IYangoThemePreview {
                ReminderCalendarScreen(onBack = {}, viewModel = vm)
            }
        }
        compose.onNodeWithText("新出分通知").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("6 小时").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(6, monitorStatus.value.intervalHours) }
        compose.onNodeWithText("立即检查").performScrollTo().performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, checkCalls) }
        compose.onNodeWithText("检查完成，暂无新成绩").assertIsDisplayed()
    }
}
