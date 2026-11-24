package com.gfs.iyango.feature.main

import androidx.lifecycle.ViewModelStore
import com.gfs.iyango.core.domain.*
import com.gfs.iyango.core.model.*
import com.gfs.iyango.feature.main.today.TodayViewModel
import com.gfs.iyango.feature.main.today.TodayUiState
import com.gfs.iyango.feature.main.exam.ExamListViewModel
import com.gfs.iyango.feature.main.classroom.ClassroomViewModel
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshFeedbackViewModelTest {
    private val store = ViewModelStore()
    private val table = Timetable(id = 1, name = "Test", isCurrent = true)
    private val semester = Semester(id = 1, timetableId = 1, startDate = LocalDate.now())
    private val coordinator = object : RefreshCoordinatorPort {
        override val state = MutableStateFlow(RefreshState())
        var outcome: suspend (RefreshResource) -> RefreshTaskState = { RefreshTaskState(it, RefreshOutcome.SUCCESS) }
        override suspend fun refresh(resource: RefreshResource, queryFingerprint: String) = outcome(resource)
        override fun onForeground() {}
        override fun onSessionChanged() {}
        override fun onLocalDataChanged(resource: RefreshResource) {}
    }
    private inline fun <reified T> port(crossinline block: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            block(method.name, args ?: emptyArray())
        } as T
    private fun exams(): ExamRepositoryPort = port { name, _ -> when (name) {
        "observeAll" -> flowOf(emptyList<Exam>())
        else -> error(name)
    } }
    private fun today() = TodayViewModel(
        timetableRepository = port { _, _ -> flowOf(table) },
        scheduleData = port { name, _ -> when (name) {
            "observeSemester" -> flowOf(semester)
            "observeTimeSlots" -> flowOf(emptyList<TimeSlot>())
            "observeCourses" -> flowOf(emptyList<Course>())
            else -> error(name)
        } },
        examRepository = exams(), adjustmentRepository = port { name, _ -> error(name) },
        effectiveSchedule = port { name, args -> when (name) {
            "resolveDay" -> EffectiveScheduleDay(args[0] as LocalDate, table, semester)
            "observeSelectedRange" -> {
                @Suppress("UNCHECKED_CAST")
                val range = args[0] as ClosedRange<LocalDate>
                flowOf(generateSequence(range.start) { it.plusDays(1) }
                    .takeWhile { it <= range.endInclusive }
                    .map { EffectiveScheduleDay(it, table, semester) }.toList())
            }
            else -> error(name)
        } },
        refreshCoordinator = coordinator, clock = Clock.systemUTC(),
        appSettings = port { _, _ -> flowOf(DisplaySettingsBundle()) },
        auth = port { _, _ -> flowOf(listOf(SchoolConnection(id = "test", status = SchoolConnectionStatus.ACTIVE))) },
    ).also { store.put("today", it) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }

    private fun classroom(query: suspend (ClassroomQuery) -> Result<ClassroomResult>): ClassroomViewModel {
        val account = SchoolConnection(id = "test", status = SchoolConnectionStatus.ACTIVE)
        val metadata = ClassroomMetadata(
            terms = listOf(ClassroomOption("term")), timeModes = listOf(ClassroomOption("mode")),
            weeks = listOf(ClassroomOption("1", selected = true)),
            days = listOf(ClassroomOption("1", selected = true)), sections = listOf(ClassroomOption("0102")),
        )
        val repository = object : ClassroomRepositoryPort {
            override suspend fun metadata(connectionId: String) = Result.success(metadata)
            override suspend fun buildings(connectionId: String, campusCode: String) = Result.success(emptyList<ClassroomBuilding>())
            override suspend fun query(connectionId: String, query: ClassroomQuery) = query(query)
            override suspend fun detail(connectionId: String, request: ClassroomDetailRequest) = Result.success(ClassroomDetail())
        }
        return ClassroomViewModel(port { name, _ -> when (name) {
            "observeActiveConnection" -> flowOf(account)
            "observeConnections" -> flowOf(listOf(account))
            "getConnection" -> account
            else -> error(name)
        } }, repository).also { store.put("classroom", it) }
    }

    @Test fun `classroom failed refresh preserves results and shows a visible failure notice`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var fail = false
        val vm = classroom { query -> if (fail) Result.failure(IllegalStateException("网络不可用"))
            else Result.success(ClassroomResult(query = query)) }
        runCurrent()
        val previous = vm.state.value.result
        fail = true; vm.refresh(); runCurrent()
        assertEquals("网络不可用", vm.state.value.refreshNotice)
        assertEquals(previous, vm.state.value.result)
        assertFalse(vm.state.value.querying)
    }

    @Test fun `classroom stalled request has a deadline and releases loading`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = classroom { awaitCancellation() }
        runCurrent()
        assertTrue(vm.state.value.querying)
        advanceTimeBy(30_001); runCurrent()
        assertFalse(vm.state.value.querying)
        assertTrue(vm.state.value.refreshNotice.contains("超时"))
    }

    @Test fun `today partial failure is visible and never claims full success`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        coordinator.outcome = { resource -> RefreshTaskState(resource,
            if (resource == RefreshResource.PROFILE) RefreshOutcome.FAILED else RefreshOutcome.SUCCESS,
            if (resource == RefreshResource.PROFILE) "资料刷新失败" else "") }
        val vm = today()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.refreshing.collect() }
        runCurrent()
        assertNotNull("Today fixture must load its active account before refreshing", (vm.state.value as? TodayUiState.Ready)?.connection)
        vm.refresh(); runCurrent()
        assertTrue(vm.message.value.orEmpty().contains("资料"))
        assertNotEquals("刷新成功", vm.message.value)
        assertFalse(vm.refreshing.value)
    }

    @Test fun `today exceptions produce a failure notice and stop loading`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        coordinator.outcome = { throw IllegalStateException("offline") }
        val vm = today()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.refreshing.collect() }
        runCurrent()
        assertNotNull("Today fixture must load its active account before refreshing", (vm.state.value as? TodayUiState.Ready)?.connection)
        vm.refresh(); runCurrent()
        assertTrue(vm.message.value.orEmpty().contains("未完成"))
        assertFalse(vm.refreshing.value)
    }

    @Test fun `background work cannot keep a completed today pull spinning`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        coordinator.state.value = RefreshState(mapOf(RefreshResource.PROFILE to
            RefreshTaskState(RefreshResource.PROFILE, RefreshOutcome.RUNNING)))
        val vm = today()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.refreshing.collect() }
        runCurrent()
        assertNotNull("Today fixture must load its active account before refreshing", (vm.state.value as? TodayUiState.Ready)?.connection)
        vm.refresh(); runCurrent()
        assertEquals("刷新成功", vm.message.value)
        assertFalse(vm.refreshing.value)
    }

    @Test fun `background work cannot keep a completed exam pull spinning`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        coordinator.state.value = RefreshState(mapOf(RefreshResource.EXAMS to
            RefreshTaskState(RefreshResource.EXAMS, RefreshOutcome.RUNNING)))
        val vm = ExamListViewModel(exams(), coordinator, Clock.systemUTC()).also { store.put("exam", it) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.refreshing.collect() }
        vm.refresh(); runCurrent()
        assertEquals("刷新成功", vm.refreshNotice.value)
        assertFalse(vm.refreshing.value)
    }

    @Test fun `exam failure does not show success and ends its spinner`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        coordinator.outcome = { RefreshTaskState(it, RefreshOutcome.FAILED, "刷新超时，本地考试已保留") }
        val vm = ExamListViewModel(exams(), coordinator, Clock.systemUTC()).also { store.put("exam", it) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.refreshing.collect() }
        vm.refresh(); runCurrent()
        assertEquals("刷新超时，本地考试已保留", vm.refreshNotice.value)
        assertFalse(vm.refreshing.value)
    }
}
