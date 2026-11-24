package com.gfs.iyango.feature.main.today

import androidx.lifecycle.ViewModelStore
import com.gfs.iyango.core.domain.*
import com.gfs.iyango.core.model.*
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TodayAccountViewModelTest {
    private val date = LocalDate.of(2026, 11, 5)
    private val timetable = Timetable(id = 1, name = "本地课表", isCurrent = true)
    private val semester = Semester(id = 1, timetableId = 1, startDate = date.minusWeeks(8))
    private val account = SchoolConnection(
        id = "saved", maskedAccount = "202****001", status = SchoolConnectionStatus.ACTIVE,
        lastLoginAt = 100, profile = SchoolProfile(displayName = "测试同学", className = "一班"),
    )
    private val accounts = MutableStateFlow(listOf(account))
    private val stores = mutableListOf<ViewModelStore>()
    private var dayReads = 0
    private var monthReads = 0
    private val observedWindows = mutableListOf<ClosedRange<LocalDate>>()
    private val effectiveDays = MutableStateFlow(listOf(EffectiveScheduleDay(date, timetable, semester), EffectiveScheduleDay(date.plusDays(1), timetable, semester)))

    private inline fun <reified T> port(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        } as T

    private fun viewModel(connections: Flow<List<SchoolConnection>> = accounts,
        testClock: Clock = Clock.fixed(Instant.parse("2026-11-05T09:00:00Z"), ZoneOffset.UTC),
        rangeObservation: ((ClosedRange<LocalDate>) -> Flow<List<EffectiveScheduleDay>>)? = null,
    ) = TodayViewModel(
        timetableRepository = port { name, _ -> when (name) {
            "observeCurrent" -> flowOf(timetable)
            else -> error("Unexpected timetable operation: $name")
        } },
        scheduleData = port { name, _ -> when (name) {
            "observeSemester" -> flowOf(semester)
            "observeTimeSlots" -> flowOf(emptyList<TimeSlot>())
            "observeCourses" -> flowOf(emptyList<Course>())
            else -> error("Unexpected schedule operation: $name")
        } },
        examRepository = port { name, _ -> when (name) {
            "observeAll" -> flowOf(emptyList<Exam>())
            else -> error(name)
        } },
        adjustmentRepository = port { name, _ -> error("Unexpected adjustment: $name") },
        effectiveSchedule = port { name, args -> when (name) {
            "resolveDay" -> { dayReads++; EffectiveScheduleDay(args[0] as LocalDate, timetable, semester) }
            "observeSelectedRange" -> {
                @Suppress("UNCHECKED_CAST") val range = args[0] as ClosedRange<LocalDate>
                observedWindows += range
                if (range.endInclusive == range.start.plusDays(6)) { dayReads++; rangeObservation?.invoke(range) ?: effectiveDays }
                else { monthReads++; rangeObservation?.invoke(range) ?: flowOf(effectiveDays.value) }
            }
            else -> error(name)
        } },
        refreshCoordinator = port { name, _ -> when (name) {
            "getState" -> MutableStateFlow(RefreshState())
            else -> error("Account display must not refresh: $name")
        } },
        clock = testClock,
        appSettings = port { name, _ -> when (name) {
            "getDisplaySettings" -> flowOf(DisplaySettingsBundle())
            "getAppPreferences" -> flowOf(AppPreferences())
            else -> error(name)
        } },
        auth = port { name, _ -> when (name) {
            "observeConnections" -> connections
            else -> error("Account display must not log in or call the school: $name")
        } },
    ).also { vm -> stores += ViewModelStore().apply { put("today", vm) } }

    private fun TestScope.start(vm: TodayViewModel): Job {
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent()
        return job
    }

    private fun TodayViewModel.ready() = state.value as TodayUiState.Ready

    @After fun cleanup() {
        stores.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    @Test fun `fresh view models read saved account without logging in again`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val first = viewModel()
        start(first)
        assertEquals(account, first.ready().connection)
        stores.first().clear()
        runCurrent()
        val reopened = viewModel()
        start(reopened)
        assertEquals(account, reopened.ready().connection)
        assertEquals(timetable, reopened.ready().timetable)
    }

    @Test fun `profile account switch and logout update immediately without rebuilding schedule`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = viewModel()
        start(vm)
        val initialDayReads = dayReads
        val updated = account.copy(profile = SchoolProfile(displayName = "更新姓名"))
        accounts.value = listOf(updated)
        runCurrent()
        assertEquals(updated, vm.ready().connection)
        val other = account.copy(id = "other", lastLoginAt = 200, profile = SchoolProfile(displayName = "另一同学"))
        accounts.value = listOf(updated.copy(status = SchoolConnectionStatus.LOGGED_OUT), other)
        runCurrent()
        assertEquals(other, vm.ready().connection)
        accounts.value = accounts.value.map { it.copy(status = SchoolConnectionStatus.LOGGED_OUT) }
        runCurrent()
        assertNull(vm.ready().connection)
        assertEquals(initialDayReads, dayReads)
        assertEquals(timetable, vm.ready().timetable)
    }

    @Test fun `active account wins over history and expired or failed account keeps its identity`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val history = account.copy(id = "history", lastLoginAt = 999, status = SchoolConnectionStatus.LOGGED_OUT)
        val expired = account.copy(id = "expired", lastLoginAt = 300, status = SchoolConnectionStatus.EXPIRED)
        accounts.value = listOf(history, expired, account)
        val vm = viewModel()
        start(vm)
        assertEquals(account, vm.ready().connection)
        accounts.value = listOf(history, expired, account.copy(status = SchoolConnectionStatus.LOGGED_OUT))
        runCurrent()
        assertEquals(expired, vm.ready().connection)
        val failed = expired.copy(status = SchoolConnectionStatus.FAILED)
        accounts.value = listOf(history, failed)
        runCurrent()
        assertEquals(failed, vm.ready().connection)
    }

    @Test fun `resubscribing after background reads current account rather than stale saved UI`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = viewModel()
        val collecting = start(vm)
        collecting.cancel()
        advanceTimeBy(5_001)
        runCurrent()
        val expired = account.copy(status = SchoolConnectionStatus.EXPIRED)
        accounts.value = listOf(expired)
        start(vm)
        assertEquals(expired, vm.ready().connection)
    }

    @Test fun `first ready frame waits for persisted accounts instead of claiming unbound during loading`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val delayed = MutableSharedFlow<List<SchoolConnection>>(replay = 1)
        val vm = viewModel(delayed)
        start(vm)
        assertEquals(TodayUiState.FirstLoading, vm.state.value)
        delayed.emit(listOf(account))
        runCurrent()
        assertEquals(account, vm.ready().connection)
        delayed.emit(emptyList())
        runCurrent()
        assertNull(vm.ready().connection)
    }

    @Test fun `sixty second projections do not query effective days or reparse month and inactive clock stops`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val movingClock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant() = Instant.parse("2026-11-05T09:00:00Z").plusMillis(testScheduler.currentTime)
        }
        val vm = viewModel(testClock = movingClock)
        start(vm)
        vm.setTimingActive(true)
        runCurrent()
        val reads = dayReads to monthReads
        val initial = vm.ready().now
        advanceTimeBy(60_000); runCurrent()
        assertEquals(initial.plusSeconds(60), vm.ready().now)
        assertEquals(reads, dayReads to monthReads)
        vm.setTimingActive(false); runCurrent()
        val stopped = vm.ready().now
        advanceTimeBy(60_000); runCurrent()
        assertEquals(stopped, vm.ready().now)
        vm.setTimingActive(true); runCurrent()
        assertEquals(initial.plusSeconds(120), vm.ready().now)
        vm.setTimingActive(false); runCurrent()
    }

    @Test fun `effective occurrence changes update today and tomorrow immediately without a clock tick`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = viewModel(); start(vm)
        val moved = CourseOccurrence("moved", Course(name = "移动课程"), date.plusDays(1), 600, 660, false)
        effectiveDays.value = listOf(EffectiveScheduleDay(date, timetable, semester),
            EffectiveScheduleDay(date.plusDays(1), timetable, semester, occurrences = listOf(moved)))
        runCurrent()
        assertTrue(vm.ready().todayRows.isEmpty())
        assertEquals(moved, vm.ready().tomorrowRows.single().occurrence)
        effectiveDays.value = effectiveDays.value.map { it.copy(occurrences = emptyList()) }; runCurrent()
        assertTrue(vm.ready().tomorrowRows.isEmpty())
    }

    @Test fun `hiding the seconds card retains minute boundary updates without second emissions`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val movingClock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant() = Instant.parse("2026-11-05T09:00:00Z").plusMillis(testScheduler.currentTime)
        }
        val vm = viewModel(testClock = movingClock); start(vm)
        val observed = mutableListOf<java.time.LocalDateTime>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.timing.collect { observed += it.now } }
        vm.setTimingActive(true, seconds = false); runCurrent()
        val initialCount = observed.size
        advanceTimeBy(59_000); runCurrent()
        assertEquals(initialCount, observed.size)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(initialCount + 1, observed.size)
        assertEquals(date.atTime(9, 1), observed.last())
        assertEquals(1, dayReads)
        vm.setTimingActive(false); runCurrent()
    }

    @Test fun `midnight renews seven day window once while retaining same month observation`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val movingClock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant() = Instant.parse("2026-11-05T23:59:59Z").plusMillis(testScheduler.currentTime)
        }
        val vm = viewModel(testClock = movingClock); start(vm)
        vm.setTimingActive(true); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(date.plusDays(1), vm.ready().today)
        assertEquals(date.plusDays(1)..date.plusDays(7), observedWindows.last())
        assertEquals(2, dayReads)
        assertEquals(1, monthReads)
        vm.setTimingActive(false); runCurrent()
    }

    @Test fun `timezone refresh uses new zone rather than singleton clock zone`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = viewModel(); start(vm)
        vm.refreshNow(java.time.ZoneId.of("Asia/Shanghai")); runCurrent()
        assertEquals(17, vm.ready().now.hour)
    }

    @Test fun `initial calendar read failure is an explicit error rather than unhandled sharing exception`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var failCalendar = true
        val vm = viewModel(rangeObservation = { range -> flow {
            if (range.endInclusive != range.start.plusDays(6) && failCalendar) error("calendar database unavailable")
            emit(effectiveDays.value)
        } })
        start(vm)
        assertTrue(vm.state.value is TodayUiState.Error)
        failCalendar = false
        vm.retryLocalLoad(); runCurrent()
        assertEquals(account, vm.ready().connection)
    }

    @Test fun `effective source failure after ready replaces stale schedule with explicit error`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fail = MutableStateFlow(false)
        val vm = viewModel(rangeObservation = { range ->
            if (range.endInclusive == range.start.plusDays(6)) fail.map {
                if (it) error("effective database unavailable")
                effectiveDays.value
            } else flowOf(effectiveDays.value)
        })
        start(vm)
        assertEquals(account, vm.ready().connection)
        fail.value = true; runCurrent()
        assertTrue(vm.state.value is TodayUiState.Error)
        fail.value = false; runCurrent()
        assertTrue(vm.state.value is TodayUiState.Error)
        vm.retryLocalLoad(); runCurrent()
        assertEquals(account, vm.ready().connection)
    }

    @Test fun `returning to homepage rebuilds failed synchronous observer without network refresh`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var fail = true
        val vm = viewModel(rangeObservation = { range ->
            if (range.endInclusive == range.start.plusDays(6) && fail) error("cannot open effective observer")
            flowOf(effectiveDays.value)
        })
        start(vm)
        assertTrue(vm.state.value is TodayUiState.Error)
        fail = false
        vm.resumeLocalObservation(); runCurrent()
        assertEquals(account, vm.ready().connection)
    }

    @Test fun `local retry restarts account read after outer state failure without network refresh`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var failAccount = true
        val vm = viewModel(connections = flow {
            if (failAccount) error("account database unavailable")
            emit(listOf(account))
        })
        start(vm)
        assertTrue(vm.state.value is TodayUiState.Error)
        failAccount = false
        vm.retryLocalLoad(); runCurrent()
        assertEquals(account, vm.ready().connection)
    }
}
