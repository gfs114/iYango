package com.gfs.iyango.feature.main.schedule

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
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleInteractionViewModelTest {
    private val date = LocalDate.of(2026, 10, 5)
    private val a = Timetable(id = 1, name = "A", isCurrent = true)
    private val b = Timetable(id = 2, name = "B")
    private val semester = Semester(id = 1, timetableId = 1, startDate = date, totalWeeks = 4)
    private val current = MutableStateFlow<Timetable?>(a)
    private val semesters = MutableStateFlow<Semester?>(semester)
    private val hidden = CancelOccurrence.create(2, 21, date.toString())
    private val incoming = MoveOccurrence.create(2, 22, date.minusWeeks(1).toString(), date.plusDays(1), 1, 1)
    private val visible = CancelOccurrence.create(1, 11, date.toString())
    private val outside = CancelOccurrence.create(1, 12, date.plusWeeks(1).toString())
    private val days = MutableStateFlow((0..27).map { offset ->
        val d = date.plusDays(offset.toLong())
        EffectiveScheduleDay(d, if (d.dayOfWeek.value == 2) b else a, semester,
            adjustments = if (d.dayOfWeek.value == 2) listOf(hidden, incoming) else listOf(visible, outside))
    })
    private val removedAnchors = mutableListOf<Triple<Long, Long, String>>()
    private val removedDates = mutableListOf<Pair<Long, String>>()
    private val insertedDays = mutableListOf<TemporaryDay>()
    private var replaced: Pair<OccurrenceAdjustmentExpected, OccurrenceAdjustment>? = null
    private var failBrowseMetadata = false
    private var failResolve = false
    private var failDelete = false
    private var railsBuilt = 0
    private inline fun <reified T> fake(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            when (method.name) {
                "toString" -> T::class.java.simpleName
                "hashCode" -> 1
                "equals" -> false
                else -> call(method.name, args ?: emptyArray())
            }
        } as T
    private fun viewModel(
        browser: TermBrowseController? = null,
        coordinator: RefreshCoordinatorPort? = null,
        clockOverride: Clock = Clock.fixed(Instant.parse("2026-10-05T08:00:00Z"), ZoneOffset.UTC),
    ) = ScheduleViewModel(
        termBrowser = browser,
        browseDispatcher = StandardTestDispatcher(),
        timetableRepository = fake { name, _ -> when (name) {
            "observeCurrent" -> current
            "observeAll" -> flowOf(if (failBrowseMetadata) listOf(a.copy(connectionId = "school", schoolTerm = "old")) else listOf(a, b))
            else -> error(name)
        } },
        scheduleData = fake { name, _ -> when (name) {
            "observeSemester" -> if (failBrowseMetadata) error("metadata unavailable") else semesters
            "observeTimeSlots" -> flowOf(emptyList<TimeSlot>())
            "observeCourses" -> flowOf(emptyList<Course>())
            "timeRailRows" -> { railsBuilt++; emptyList<TimeRailRow>() }
            else -> error(name)
        } },
        adjustmentRepository = fake { name, args -> when (name) {
            "observeAdjustments" -> flowOf(if (args[0] == 1L) listOf(visible, outside) else listOf(hidden, incoming))
            "observeTemporaryDays" -> flowOf(emptyList<TemporaryDay>())
            "deleteAnchor" -> { removedAnchors += Triple(args[0] as Long, args[1] as Long, args[2] as String); Unit }
            "deleteTemporaryDay" -> { if (failDelete) error("DB failure"); removedDates += (args[0] as Long) to (args[1] as String); Unit }
            "insertTemporaryDay" -> { insertedDays += args[0] as TemporaryDay; 1L }
            "replaceOccurrence" -> { replaced = (args[0] as OccurrenceAdjustmentExpected) to (args[1] as OccurrenceAdjustment); AdjustmentReplaceResult.Stale }
            else -> error("Unexpected write: $name")
        } },
        effectiveSchedule = fake { name, args -> when (name) {
            "observeSelectedRange" -> days
            "resolveSelectedRange" -> days.value.filter { it.date in args[0] as ClosedRange<LocalDate> }
            "resolveDay" -> { if (failResolve) error("DB failure"); days.value.first { it.date == args[0] } }
            else -> error(name)
        } },
        appSettings = fake { name, _ -> when (name) {
            "getDisplaySettings" -> flowOf(DisplaySettingsBundle())
            "getAppPreferences" -> flowOf(AppPreferences())
            else -> error(name)
        } },
        wallpaperStore = fake { name, _ -> when (name) { "resolve" -> null; else -> error(name) } },
        refreshCoordinator = coordinator ?: fake { name, _ -> when (name) { "getState" -> MutableStateFlow(RefreshState()); else -> error(name) } },
        clock = clockOverride,
        examRepository = fake { name, _ -> when (name) { "observeInRange" -> flowOf(emptyList<Exam>()); else -> error(name) } },
    )
    private fun TestScope.start(): ScheduleViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent()
        assertTrue(vm.state.value is ScheduleUiState.Ready)
        return vm
    }
    @After fun reset() { Dispatchers.resetMain() }
    @Test fun `menu apply and clear resolve the selected date owner rather than root`() = runTest {
        val vm = start()
        vm.applyTemporaryDay(date.plusDays(1), 1)
        runCurrent()
        assertEquals(2L, insertedDays.single().timetableId)
        assertEquals(1L, insertedDays.single().sourceTimetableId)
        assertEquals(listOf(2L to date.plusDays(1).toString()), removedDates)
        removedDates.clear()
        vm.clearTemporaryDay(date.plusDays(1))
        runCurrent()
        assertEquals(listOf(2L to date.plusDays(1).toString()), removedDates)
    }
    @Test fun `restore week preserves hidden owner adjustments and other weeks`() = runTest {
        val vm = start()
        vm.restoreDisplayedWeek()
        runCurrent()
        assertEquals(setOf(Triple(1L,11L,date.toString()), Triple(2L,22L,date.minusWeeks(1).toString())), removedAnchors.toSet())
        assertFalse(removedDates.contains(2L to date.toString()))
        assertTrue(removedDates.contains(2L to date.plusDays(1).toString()))
    }
    @Test fun `clear and explicit detail restore report database failures`() = runTest {
        val vm = start()
        failResolve = true
        vm.clearTemporaryDay(date)
        runCurrent()
        assertEquals("恢复未完成，请重试", vm.message.value)
        vm.consumeMessage()
        failDelete = true
        vm.restoreTemporaryDay(2, date)
        runCurrent()
        assertEquals("恢复未完成，请重试", vm.message.value)
        assertTrue(removedDates.isEmpty())
    }
    @Test fun `cached real pages survive week navigation and old root callbacks cannot select a week`() = runTest {
        val vm = start()
        val initial = vm.state.value as ScheduleUiState.Ready
        assertEquals(listOf(date, date.plusWeeks(1), date.plusWeeks(2), date.plusWeeks(3)), initial.weeks.map { it.weekStart })
        val builds = railsBuilt
        vm.pickWeek(3)
        runCurrent()
        assertEquals(3, (vm.state.value as ScheduleUiState.Ready).data.displayedWeek)
        assertEquals(builds, railsBuilt)
        vm.backToCurrentWeek()
        runCurrent()
        assertEquals(1, (vm.state.value as ScheduleUiState.Ready).data.displayedWeek)
        current.value = b
        runCurrent()
        vm.pickPagerWeek(initial.data.context, 4)
        runCurrent()
        assertEquals(1, (vm.state.value as ScheduleUiState.Ready).data.displayedWeek)
    }
    @Test fun `drag submits explicit original snapshot even after unrelated detail capture and reports stale`() = runTest {
        val vm = start()
        val data = (vm.state.value as ScheduleUiState.Ready).data
        val c = Course(id = 7, timetableId = 1, name = "Source", startSection = 1, endSection = 2)
        val occurrence = CourseOccurrence("exact", c, date, null, null, false, date.minusWeeks(1), true)
        val expected = OccurrenceAdjustmentExpected(c, occurrence, emptyList())
        val source = ScheduleDragSource(data.context, expected, 2)
        val target = days.value[7]
        vm.captureDetail(data.copy(effectiveDays = emptyList()))
        val error = vm.confirmDrag(ScheduleDropCandidate(source, ScheduleDropSlot(1,3,4),target,2))
        assertEquals("课程或目标日期已变化，请重新拖动", error)
        assertEquals(expected.copy(targetDay=target), replaced!!.first)
        assertEquals(date.minusWeeks(1).toString(), replaced!!.second.sourceDate)
        assertEquals(date.plusWeeks(1).toString(), replaced!!.second.targetDate)
        current.value = b
        runCurrent()
        replaced = null
        assertNotNull(vm.confirmDrag(ScheduleDropCandidate(source,ScheduleDropSlot(1,3,4),target,2)))
        assertNull(replaced)
    }
    @Test fun `semester switch resets selected week and rejects old pager session`() = runTest {
        val vm = start()
        val old = (vm.state.value as ScheduleUiState.Ready).data.context
        vm.pickWeek(4)
        runCurrent()
        semesters.value = semester.copy(id = 99, name = "New semester", totalWeeks = 2)
        runCurrent()
        vm.pickPagerWeek(old, 2)
        runCurrent()
        val data = (vm.state.value as ScheduleUiState.Ready).data
        assertEquals(1, data.displayedWeek)
        assertEquals(99L, data.semester!!.id)
    }
    @Test fun `unpublished explicit pick cannot be overwritten by old pager settled callback`() = runTest {
        val vm = start()
        val context = (vm.state.value as ScheduleUiState.Ready).data.context
        vm.pickWeek(4)
        vm.pickPagerWeek(context, 2)
        runCurrent()
        assertEquals(4, (vm.state.value as ScheduleUiState.Ready).data.displayedWeek)
    }
    @Test fun `unpublished back to current cannot be overwritten by old pager settled callback`() = runTest {
        val vm = start()
        vm.pickWeek(4)
        runCurrent()
        val context = (vm.state.value as ScheduleUiState.Ready).data.context
        vm.backToCurrentWeek()
        vm.pickPagerWeek(context, 2)
        runCurrent()
        assertEquals(1, (vm.state.value as ScheduleUiState.Ready).data.displayedWeek)
    }
    @Test fun `published newer navigation still refuses callbacks carrying an old request version`() = runTest {
        val vm = start()
        val old = (vm.state.value as ScheduleUiState.Ready).data
        vm.pickWeek(4)
        runCurrent()
        vm.pickPagerWeek(old.context, 2, old.navigationVersion)
        runCurrent()
        assertEquals(4, (vm.state.value as ScheduleUiState.Ready).data.displayedWeek)
    }
    @Test fun `term browsing refresh never syncs or switches global table and restores current week`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var fetches=0
        var response = SchoolTimetableData(termValue = "old")
        var pendingFetch: CompletableDeferred<SchoolTimetableResult>? = null
        val school = object: SchoolTimetablePort {
            override suspend fun listTerms(connectionId:String)=Result.success(listOf(SchoolTermOption("old")))
            override suspend fun listCampuses(connectionId:String)=Result.success(emptyList<SchoolCampusOption>())
            override suspend fun fetchTimetable(connectionId:String,termValue:String,campusCode:String):SchoolTimetableResult {
                fetches++
                return pendingFetch?.await() ?: SchoolTimetableResult.Success(response)
            }
        }
        val cache=object:TermBrowseCachePort {
            override suspend fun readTerms(accountKey:String)=emptyList<SchoolTermOption>()
            override suspend fun writeTerms(accountKey:String,terms:List<SchoolTermOption>) {}
            override suspend fun readTimetable(accountKey:String,term:String):SchoolTimetableData?=null
            override suspend fun writeTimetable(accountKey:String,term:String,data:SchoolTimetableData) {}
        }
        val browser=TermBrowseController(school,cache,backgroundScope, StandardTestDispatcher(testScheduler))
        val vm=viewModel(browser)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent(); vm.pickWeek(3); runCurrent()
        browser.accountChanged(SchoolConnection(id="school",status=SchoolConnectionStatus.ACTIVE)); runCurrent()
        vm.browseTerm("old"); runCurrent()
        assertTrue((vm.state.value as ScheduleUiState.Ready).data.readOnly)
        assertEquals(1,(vm.state.value as ScheduleUiState.Ready).data.displayedWeek)
        vm.applyTemporaryDay(date,2); vm.clearTemporaryDay(date); vm.restoreDisplayedWeek(); vm.restoreTemporaryDay(1,date)
        vm.deleteCourse(Course(id=11,timetableId=1),CourseDeleteScope.DELETE_ARRANGEMENT,1)
        val firstBrowse = vm.state.value as ScheduleUiState.Ready
        val firstBrowseRails = railsBuilt
        vm.pickWeek(2); runCurrent()
        assertEquals("week navigation must reuse the semester projection", firstBrowseRails, railsBuilt)
        assertSame(firstBrowse.weeks, (vm.state.value as ScheduleUiState.Ready).weeks)
        pendingFetch = CompletableDeferred()
        vm.refresh(); runCurrent()
        assertEquals("loading must reuse the semester projection", firstBrowseRails, railsBuilt)
        assertSame(firstBrowse.weeks, (vm.state.value as ScheduleUiState.Ready).weeks)
        pendingFetch!!.complete(SchoolTimetableResult.Success(response.copy(fetchedAt = 42, rawPayload = "changed transport HTML")))
        pendingFetch = null
        runCurrent()
        assertEquals("unchanged refresh must reuse the semester projection", firstBrowseRails, railsBuilt)
        assertSame(firstBrowse.weeks, (vm.state.value as ScheduleUiState.Ready).weeks)
        assertEquals(2,fetches)
        response = response.copy(courses = listOf(SchoolCourseData(name = "New snapshot", endWeek = 20)))
        vm.refresh(); runCurrent()
        assertEquals("changed courses invalidate the semester projection", firstBrowseRails + 20, railsBuilt)
        assertNotSame(firstBrowse.weeks, (vm.state.value as ScheduleUiState.Ready).weeks)
        assertEquals("New snapshot", (vm.state.value as ScheduleUiState.Ready).data.courses.single().name)
        assertTrue(insertedDays.isEmpty()); assertTrue(removedDates.isEmpty()); assertTrue(removedAnchors.isEmpty())
        vm.pickWeek(4); runCurrent(); vm.returnToCurrentTimetable(); runCurrent()
        assertEquals(3,(vm.state.value as ScheduleUiState.Ready).data.displayedWeek)
        assertEquals(a,current.value)
        vm.browseTerm("old"); runCurrent(); assertEquals(4,(vm.state.value as ScheduleUiState.Ready).data.displayedWeek)
        failBrowseMetadata = true
        vm.browseTerm("old"); runCurrent()
        assertTrue(vm.state.value is ScheduleUiState.Error)
        failBrowseMetadata = false
        vm.retry(); runCurrent()
        assertTrue(vm.state.value is ScheduleUiState.Ready)
        vm.returnToCurrentTimetable(); runCurrent()
        assertEquals(3, (vm.state.value as ScheduleUiState.Ready).data.displayedWeek)

    }

    private class ControlledRefresh : RefreshCoordinatorPort {
        override val state = MutableStateFlow(RefreshState())
        var calls = 0
        var result: suspend () -> RefreshTaskState = {
            RefreshTaskState(outcome = RefreshOutcome.FAILED, message = "offline")
        }
        override suspend fun refresh(resource: RefreshResource, queryFingerprint: String): RefreshTaskState {
            assertEquals(RefreshResource.TIMETABLE, resource)
            calls++
            return result()
        }
        override fun onForeground() {}
        override fun onSessionChanged() {}
        override fun onLocalDataChanged(resource: RefreshResource) {}
    }

    @Test fun `pull refresh immediately follows current week despite failure and stale pager callbacks`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val coordinator = ControlledRefresh()
        val vm = viewModel(coordinator = coordinator)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent()
        vm.pickWeek(3); runCurrent()
        val stale = (vm.state.value as ScheduleUiState.Ready).data
        vm.pullRefresh()
        vm.pickPagerWeek(stale.context, 4, stale.navigationVersion)
        runCurrent()
        assertEquals(1, coordinator.calls)
        assertEquals(1, (vm.state.value as ScheduleUiState.Ready).data.displayedWeek)
        assertNull(vm.message.value)
        vm.completePull(vm.localPull.value!!.id)
        assertFalse(vm.pullMessage.value!!.contains("成功"))
        assertTrue(vm.pullMessage.value!!.contains("教务同步未完成"))
        val pending = CompletableDeferred<RefreshTaskState>()
        coordinator.result = { pending.await() }
        vm.pullRefresh(); runCurrent()
        vm.pickWeek(2); runCurrent()
        pending.complete(RefreshTaskState(outcome = RefreshOutcome.SUCCESS)); runCurrent()
        assertEquals(2, (vm.state.value as ScheduleUiState.Ready).data.displayedWeek)
    }

    @Test fun `pull refresh leaves historical browsing and fences its late response even offline`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var schoolFetches = 0
        val late = CompletableDeferred<SchoolTimetableResult>()
        val school = object : SchoolTimetablePort {
            override suspend fun listTerms(connectionId: String) = Result.success(listOf(SchoolTermOption("old")))
            override suspend fun listCampuses(connectionId: String) = Result.success(emptyList<SchoolCampusOption>())
            override suspend fun fetchTimetable(connectionId: String, termValue: String, campusCode: String): SchoolTimetableResult {
                schoolFetches++
                return if (schoolFetches == 1) SchoolTimetableResult.Success(SchoolTimetableData(termValue = termValue))
                    else withContext(NonCancellable) { late.await() }
            }
        }
        val cache = object : TermBrowseCachePort {
            override suspend fun readTerms(accountKey: String) = emptyList<SchoolTermOption>()
            override suspend fun writeTerms(accountKey: String, terms: List<SchoolTermOption>) {}
            override suspend fun readTimetable(accountKey: String, term: String): SchoolTimetableData? = null
            override suspend fun writeTimetable(accountKey: String, term: String, data: SchoolTimetableData) {}
        }
        val browser = TermBrowseController(school, cache, backgroundScope, StandardTestDispatcher(testScheduler))
        val coordinator = ControlledRefresh().apply { result = { error("offline transport") } }
        val vm = viewModel(browser, coordinator)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent(); vm.pickWeek(3); runCurrent()
        browser.accountChanged(SchoolConnection(id = "school", status = SchoolConnectionStatus.ACTIVE)); runCurrent()
        vm.browseTerm("old"); runCurrent(); vm.pickWeek(4); runCurrent()
        val stale = (vm.state.value as ScheduleUiState.Ready).data
        vm.refresh(); runCurrent() // A chooser refresh remains scoped to the historical term.
        assertEquals("old", browser.state.value.selected)
        assertEquals(0, coordinator.calls)
        vm.pullRefresh(); runCurrent()
        assertNull(browser.state.value.selected)
        assertEquals(1, coordinator.calls)
        assertEquals(1, (vm.state.value as ScheduleUiState.Ready).data.displayedWeek)
        late.complete(SchoolTimetableResult.Success(SchoolTimetableData(termValue = "old", termLabel = "late")))
        runCurrent()
        vm.pickPagerWeek(stale.context, 4, stale.navigationVersion); runCurrent()
        assertEquals(1, (vm.state.value as ScheduleUiState.Ready).data.displayedWeek)
        assertFalse((vm.state.value as ScheduleUiState.Ready).data.readOnly)
        assertEquals(2, schoolFetches)
        assertEquals(a, current.value)
    }

    @Test fun `grid transition finishes silently while sync keeps refreshing and cancelled feedback stays silent`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pending = CompletableDeferred<RefreshTaskState>()
        val coordinator = ControlledRefresh().apply {
            state.value = RefreshState(mapOf(RefreshResource.TIMETABLE to RefreshTaskState(outcome = RefreshOutcome.RUNNING)))
            result = { pending.await() }
        }
        val vm = viewModel(coordinator = coordinator)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.refreshing.collect() }
        runCurrent()
        vm.pullRefresh(); runCurrent()
        val request = vm.localPull.value!!
        vm.pullRefresh(); runCurrent()
        assertEquals(request, vm.localPull.value)
        assertEquals(1, coordinator.calls)
        assertNull(vm.pullMessage.value)
        vm.completePull(request.id); runCurrent()
        assertNull(vm.localPull.value)
        assertNull(vm.pullMessage.value)
        assertTrue(vm.refreshing.value)
        vm.pullRefresh(); runCurrent()
        assertEquals(1, coordinator.calls)
        assertNull(vm.localPull.value)
        vm.cancelPull(); runCurrent()
        assertFalse(vm.refreshing.value)
        pending.complete(RefreshTaskState(outcome = RefreshOutcome.FAILED, sessionExpired = true)); runCurrent()
        assertNull(vm.pullMessage.value)
        assertFalse(vm.sessionExpired.value)
        assertNull(vm.message.value)
    }

    @Test fun `school sync reports one result only after sync and persistence complete`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pending = CompletableDeferred<RefreshTaskState>()
        val coordinator = ControlledRefresh().apply { result = { pending.await() } }
        val vm = viewModel(coordinator = coordinator)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.refreshing.collect() }
        val notices = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.pullMessage.filterNotNull().collect { notices += it } }
        runCurrent(); vm.pullRefresh(); runCurrent()
        vm.completePull(vm.localPull.value!!.id)
        runCurrent()
        assertTrue(notices.isEmpty())
        assertTrue(vm.refreshing.value)
        pending.complete(RefreshTaskState(outcome = RefreshOutcome.SUCCESS)); runCurrent()
        assertEquals(listOf("教务课表同步成功，已保存到本地"), notices)
        assertFalse(vm.refreshing.value)
    }

    @Test fun `empty local timetable stays silent until first school sync finishes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        current.value = null
        val pending = CompletableDeferred<RefreshTaskState>()
        val coordinator = ControlledRefresh().apply { result = { pending.await() } }
        val vm = viewModel(coordinator = coordinator)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent(); vm.pullRefresh(); runCurrent()
        vm.completePull(vm.localPull.value!!.id); runCurrent()
        assertNull(vm.localPull.value)
        assertNull(vm.pullMessage.value)
        pending.complete(RefreshTaskState(outcome = RefreshOutcome.SUCCESS)); runCurrent()
        assertEquals("教务课表同步成功，已保存到本地", vm.pullMessage.value)
        assertNull(vm.message.value)
    }

    @Test fun `inactive pull cleanup leaves an unrelated scoped manual refresh unchanged`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pending = CompletableDeferred<RefreshTaskState>()
        val coordinator = ControlledRefresh().apply { result = { pending.await() } }
        val vm = viewModel(coordinator = coordinator)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent(); vm.refresh(); runCurrent()
        vm.cancelPull() // Content disposal without any pull must not invalidate a manual refresh.
        pending.complete(RefreshTaskState(outcome = RefreshOutcome.SUCCESS)); runCurrent()
        assertEquals("教务课表同步成功，已保存到本地", vm.message.value)
    }

    @Test fun `grid navigation cannot replace or repeat a completed school sync result`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val coordinator = ControlledRefresh().apply { result = { RefreshTaskState(outcome = RefreshOutcome.SUCCESS) } }
        val vm = viewModel(coordinator = coordinator)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent()
        semesters.value = null
        runCurrent(); vm.pullRefresh(); runCurrent()
        assertEquals("教务课表同步成功，已保存到本地", vm.pullMessage.value)
        vm.consumePullMessage()
        vm.completePull(vm.localPull.value!!.id)
        assertNull(vm.pullMessage.value)
        semesters.value = semester.copy(startDate = date.minusYears(1))
        runCurrent(); vm.pullRefresh(); runCurrent()
        vm.completePull(vm.localPull.value!!.id)
        assertEquals("教务课表同步成功，已保存到本地", vm.pullMessage.value)
    }

    @Test fun `unbound account produces a login notice instead of local success`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val coordinator = ControlledRefresh().apply {
            result = { RefreshTaskState(outcome = RefreshOutcome.FAILED, sessionExpired = true,
                message = "请先登录阳光学院教务系统") }
        }
        val vm = viewModel(coordinator = coordinator)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent(); vm.pullRefresh(); runCurrent()
        vm.completePull(vm.localPull.value!!.id)
        assertEquals("请先登录阳光学院教务系统", vm.pullMessage.value)
        assertFalse(vm.sessionExpired.value)
    }

    @Test fun `write failure after school fetch never reports sync or save success`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pending = CompletableDeferred<RefreshTaskState>()
        val coordinator = ControlledRefresh().apply { result = { pending.await() } }
        val vm = viewModel(coordinator = coordinator)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.refreshing.collect() }
        val notices = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.pullMessage.filterNotNull().collect { notices += it } }
        runCurrent(); vm.pullRefresh(); runCurrent()
        vm.completePull(vm.localPull.value!!.id); runCurrent()
        pending.complete(RefreshTaskState(outcome = RefreshOutcome.FAILED, message = "写入课表失败")); runCurrent()
        assertEquals(listOf("教务同步未完成：写入课表失败，本地课表已保留"), notices)
        assertFalse(vm.refreshing.value)
    }

    @Test fun `cancelPull releases pull lock immediately so returning to tab can pull again and refreshClock updates now`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var currentInstant = Instant.parse("2026-10-05T08:00:00Z")
        val mutableClock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant() = currentInstant
        }
        val firstPending = CompletableDeferred<RefreshTaskState>()
        val secondPending = CompletableDeferred<RefreshTaskState>()
        var callIndex = 0
        val coordinator = ControlledRefresh().apply {
            result = {
                if (callIndex++ == 0) firstPending.await() else secondPending.await()
            }
        }
        val vm = viewModel(coordinator = coordinator, clockOverride = mutableClock)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.refreshing.collect() }
        runCurrent()

        vm.pullRefresh(); runCurrent()
        assertEquals(1, coordinator.calls)
        assertTrue(vm.refreshing.value)

        vm.cancelPull(); runCurrent()
        assertFalse(vm.refreshing.value)
        assertNull(vm.localPull.value)

        vm.pullRefresh(); runCurrent()
        assertEquals(2, coordinator.calls)
        assertTrue(vm.refreshing.value)
        secondPending.complete(RefreshTaskState(outcome = RefreshOutcome.SUCCESS)); runCurrent()
        assertEquals("教务课表同步成功，已保存到本地", vm.pullMessage.value)
        assertFalse(vm.refreshing.value)

        currentInstant = Instant.parse("2026-10-05T11:30:00Z")
        vm.refreshClock(); runCurrent()
        assertEquals(11, (vm.state.value as ScheduleUiState.Ready).data.now.hour)
        assertEquals(30, (vm.state.value as ScheduleUiState.Ready).data.now.minute)
    }

}
