package com.gfs.iyango.feature.main.grades

import com.gfs.iyango.core.domain.*
import com.gfs.iyango.core.model.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class GradeTermRangeViewModelTest {
    private val range = "range:2024-2025-1..2025-2026-1"
    private val account = MutableStateFlow(SchoolConnection(id = "a", accountSubject = "student", status = SchoolConnectionStatus.ACTIVE))
    private val rows = MutableStateFlow(listOf(
        row(1, "2023-2024-2", 2.0, 50.0, 1.0),
        row(2, "2024-2025-1", 2.0, 80.0, 3.0),
        row(3, "2024-2025-2", 3.0, 90.0, 4.0),
        row(4, "2024-2025-2", 2.0, 100.0, 5.0).copy(attribute = "公选"),
        row(5, "2025-2026-1", 1.0, 100.0, 5.0),
        row(6, "2025-2026-2", 4.0, 60.0, 2.0),
    ))
    private val terms = rows.value.map { it.semester }.distinct().sortedDescending()
    private val queries = mutableListOf<String>()
    private val marked = mutableListOf<String>()
    private var response: suspend () -> RefreshTaskState = { RefreshTaskState(outcome = RefreshOutcome.SUCCESS) }
    private fun row(id: Long, term: String, credit: Double, score: Double, gpa: Double) =
        GradeRecord(id = id, semester = term, courseName = "课程$id", credit = credit, scoreValue = score, gradePoint = gpa, isNew = true)
    private inline fun <reified T> fake(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> call(method.name, args ?: emptyArray()) } as T
    private fun vm() = GradesViewModel(
        auth = fake { name, _ -> when (name) {
            "observeActiveConnection" -> account.map { it.takeIf { row -> row.status == SchoolConnectionStatus.ACTIVE } }
            "observeConnections" -> account.map { listOf(it) }
            "getConnection" -> account.value
            else -> error(name)
        } },
        repository = fake { name, args -> when (name) {
            "observeSnapshot" -> flowOf(GradeSnapshot(id = 1, complete = true))
            "observeRecords" -> if (args[0] != "student") flowOf(emptyList<GradeRecord>())
                else rows.map { source -> source.filter { args[1] == "*" || it.semester == args[1] } }
            "observeSemesters" -> flowOf(if (args[0] == "student") terms else emptyList<String>())
            "observeUnreadCount" -> rows.map { source -> source.count { it.isNew || it.isModified } }
            "markChangesRead" -> {
                marked += args[1] as String
                rows.value = rows.value.map { if (it.semester == args[1] || args[1] == "*") it.copy(isNew = false, isModified = false) else it }
                Unit
            }
            else -> error(name)
        } },
        query = fake { name, _ -> when (name) {
            "listTerms" -> Result.success(terms.map { GradeTerm(it, it) })
            else -> error(name)
        } },
        refreshCoordinator = object : RefreshCoordinatorPort {
            override val state = MutableStateFlow(RefreshState())
            override suspend fun refresh(resource: RefreshResource, queryFingerprint: String): RefreshTaskState {
                queries += queryFingerprint
                return response()
            }
            override fun onForeground() {}
            override fun onSessionChanged() {}
            override fun onLocalDataChanged(resource: RefreshResource) {}
        },
    )
    private fun TestScope.start(): GradesViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return vm().also { runCurrent() }
    }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun `inclusive range computes exactly the three requested semesters without querying`() = runTest {
        val vm = start()
        vm.selectDraftRange(range); runCurrent()
        assertEquals(range, vm.state.value.confirmedRange)
        assertEquals(listOf(2L, 3L, 4L, 5L), vm.state.value.records.map { it.id })
        val stats = GradeStatisticsCalculator.calculate(vm.state.value.statisticsRecords, vm.state.value.includeOptional)
        assertEquals(3, stats.includedCount); assertEquals(1, stats.excludedCount)
        assertEquals(530.0 / 6, stats.weightedAverage!!, .0001)
        assertEquals(23.0 / 6, stats.averageGradePoint!!, .0001)
        assertEquals(6.0, stats.totalCredits, .0001)
        vm.setIncludeOptional(true)
        assertEquals(91.25, GradeStatisticsCalculator.calculate(vm.state.value.statisticsRecords, true).weightedAverage!!, .0001)
        assertTrue(queries.isEmpty())
    }
    @Test fun `range query refreshes the complete source but keeps records and selection scoped`() = runTest {
        val vm = start(); vm.selectDraftRange(range); runCurrent()
        assertTrue(queries.isEmpty())
        vm.refresh(); runCurrent()
        assertEquals(listOf("*"), queries)
        vm.refresh(); runCurrent()
        assertEquals(listOf("*", "*"), queries)
        assertEquals(range, vm.state.value.confirmedRange)
        vm.beginSelection(vm.state.value.selectionContext)
        vm.selectAll(vm.state.value.selectionContext)
        assertEquals(setOf(2L, 3L, 4L, 5L), vm.state.value.selectedRecordIds)
        vm.selectDraftRange("2025-2026-2"); runCurrent()
        assertTrue(vm.state.value.selectionMode)
        assertEquals(setOf(6L), vm.state.value.selectedRecordIds)
        assertEquals(listOf(6L), vm.state.value.records.map { it.id })
        vm.selectDraftRange(""); runCurrent()
        assertEquals(6, vm.state.value.records.size)
    }
    @Test fun `expired account can calculate a cached range without network access`() = runTest {
        account.value = account.value.copy(status = SchoolConnectionStatus.EXPIRED)
        val vm = start(); vm.selectDraftRange(range); runCurrent()
        assertEquals(4, vm.state.value.records.size)
        assertTrue(vm.state.value.needsLogin)
        assertTrue(queries.isEmpty())
    }

    @Test fun `attribute selection stays within the displayed range and works with expired cache`() = runTest {
        rows.value = rows.value.map { it.copy(attribute = "必修") }
        account.value = account.value.copy(status = SchoolConnectionStatus.EXPIRED)
        val vm = start(); vm.selectDraftRange(range); runCurrent()
        val context = vm.state.value.selectionContext
        vm.beginSelection(context); vm.toggleAttributeSelection("必修", context)
        assertEquals(setOf(2L, 3L, 4L, 5L), vm.state.value.selectedRecordIds)
        assertEquals(91.25, GradeStatisticsCalculator.calculate(vm.state.value.statisticsRecords).weightedAverage!!, .0001)
        vm.selectDraftRange("2025-2026-2"); runCurrent()
        vm.beginSelection(vm.state.value.selectionContext)
        vm.toggleAttributeSelection("必修", context)
        assertTrue(vm.state.value.selectedRecordIds.isEmpty())
        vm.toggleAttributeSelection("必修", vm.state.value.selectionContext)
        assertEquals(setOf(6L), vm.state.value.selectedRecordIds)
        assertTrue(queries.isEmpty())
    }
    @Test fun `range read action marks only included semesters`() = runTest {
        val vm = start(); vm.selectDraftRange(range); runCurrent()
        assertEquals(4, vm.state.value.unreadCount)
        vm.markRead(); runCurrent()
        assertEquals(setOf("2024-2025-1", "2024-2025-2", "2025-2026-1"), marked.toSet())
        assertEquals(0, vm.state.value.unreadCount)
        assertTrue(rows.value.first().isNew && rows.value.last().isNew)
    }

    @Test fun `switching semesters keeps selection mode and reapplies the chosen attributes`() = runTest {
        rows.value = rows.value.map { if (it.attribute.isBlank()) it.copy(attribute = "必修") else it }
        val vm = start(); vm.selectDraftRange("2024-2025-2"); runCurrent()
        val old = vm.state.value.selectionContext
        vm.beginSelection(old); vm.toggleAttributeSelection("必修", old)
        vm.selectDraftRange("2025-2026-1"); runCurrent()
        assertTrue(vm.state.value.selectionMode)
        assertEquals(setOf(5L), vm.state.value.selectedRecordIds)
        assertEquals(100.0, GradeStatisticsCalculator.calculate(vm.state.value.statisticsRecords).weightedAverage!!, .0001)
        vm.toggleAttributeSelection("必修", old)
        assertEquals(setOf(5L), vm.state.value.selectedRecordIds)
        vm.selectDraftRange(range); runCurrent()
        assertEquals(setOf(2L, 3L, 5L), vm.state.value.selectedRecordIds)
        assertTrue(queries.isEmpty())
    }

    @Test fun `attributes absent in one semester survive returning to a matching semester`() = runTest {
        val vm = start(); vm.selectDraftRange("2024-2025-2"); runCurrent()
        vm.beginSelection(vm.state.value.selectionContext)
        vm.toggleAttributeSelection("公选", vm.state.value.selectionContext)
        vm.selectDraftRange("2025-2026-1"); runCurrent()
        assertTrue(vm.state.value.selectionMode)
        assertTrue(vm.state.value.selectedRecordIds.isEmpty())
        vm.selectDraftRange("2024-2025-2"); runCurrent()
        assertEquals(setOf(4L), vm.state.value.selectedRecordIds)
        vm.clearSelection(vm.state.value.selectionContext)
        vm.selectDraftRange(""); runCurrent()
        assertTrue(vm.state.value.selectionMode)
        assertTrue(vm.state.value.selectedRecordIds.isEmpty())
    }

    @Test fun `replacement cache after a semester switch reapplies attributes with fresh record ids`() = runTest {
        rows.value = rows.value.map { if (it.attribute.isBlank()) it.copy(attribute = "必修") else it }
        val vm = start()
        vm.beginSelection(vm.state.value.selectionContext)
        vm.toggleAttributeSelection("必修", vm.state.value.selectionContext)
        vm.selectDraftRange("2025-2026-1"); runCurrent()
        val old = vm.state.value.selectionContext
        rows.value = rows.value.map { it.copy(id = it.id + 100) }; runCurrent()
        assertTrue(vm.state.value.selectionMode)
        assertEquals(setOf(105L), vm.state.value.selectedRecordIds)
        vm.toggleAttributeSelection("必修", old)
        assertEquals(setOf(105L), vm.state.value.selectedRecordIds)
        assertEquals("", vm.state.value.selectionNotice)
    }

    @Test fun `rapid range changes use the latest range and preserve a partially adjusted attribute`() = runTest {
        rows.value = rows.value.map { it.copy(attribute = "必修") }
        val vm = start(); vm.selectDraftRange(range); runCurrent()
        vm.beginSelection(vm.state.value.selectionContext)
        vm.toggleAttributeSelection("必修", vm.state.value.selectionContext)
        vm.toggleSelection(2, vm.state.value.selectionContext)
        vm.selectDraftRange("2024-2025-1")
        vm.selectDraftRange("2025-2026-2")
        runCurrent()
        assertTrue(vm.state.value.selectionMode)
        assertEquals(setOf(6L), vm.state.value.selectedRecordIds)
        vm.exitSelection(vm.state.value.selectionContext)
        vm.selectDraftRange(range); runCurrent()
        assertFalse(vm.state.value.selectionMode)
        assertTrue(vm.state.value.selectedRecordIds.isEmpty())
    }

    @Test fun `an empty cached semester keeps the workflow and attributes for the next switch`() = runTest {
        rows.value = rows.value.filter { it.semester != "2025-2026-2" }.map { it.copy(attribute = "必修") }
        val vm = start()
        vm.beginSelection(vm.state.value.selectionContext)
        vm.toggleAttributeSelection("必修", vm.state.value.selectionContext)
        vm.selectDraftRange("2025-2026-2"); runCurrent()
        assertTrue(vm.state.value.selectionMode)
        assertTrue(vm.state.value.records.isEmpty())
        assertTrue(vm.state.value.selectedRecordIds.isEmpty())
        vm.selectDraftRange("2024-2025-1"); runCurrent()
        assertEquals(setOf(2L), vm.state.value.selectedRecordIds)
        assertTrue(queries.isEmpty())
    }

    @Test fun `manually deselecting the last course of an attribute stops carrying that attribute`() = runTest {
        rows.value = rows.value.map { it.copy(attribute = "必修") }
        val vm = start(); vm.selectDraftRange("2024-2025-1"); runCurrent()
        vm.beginSelection(vm.state.value.selectionContext)
        vm.toggleAttributeSelection("必修", vm.state.value.selectionContext)
        vm.toggleSelection(2, vm.state.value.selectionContext)
        vm.selectDraftRange("2025-2026-2"); runCurrent()
        assertTrue(vm.state.value.selectionMode)
        assertTrue(vm.state.value.selectedRecordIds.isEmpty())
    }
    @Test fun `invalid or unavailable endpoints do not replace the current calculation`() = runTest {
        val vm = start(); vm.selectDraftRange(range); runCurrent()
        vm.selectDraftRange("range:2025-2026-1..2024-2025-1"); runCurrent()
        assertEquals(range, vm.state.value.confirmedRange)
        vm.selectDraftRange("range:2022-2023-1..2024-2025-1"); runCurrent()
        assertEquals(range, vm.state.value.confirmedRange)
        assertEquals(4, vm.state.value.records.size)
        assertTrue(queries.isEmpty())
    }
    @Test fun `failed refresh preserves filtered records and account change clears the range`() = runTest {
        val vm = start(); vm.selectDraftRange(range); runCurrent()
        response = { RefreshTaskState(outcome = RefreshOutcome.FAILED, message = "离线") }
        vm.refresh(); runCurrent()
        assertEquals(4, vm.state.value.records.size)
        assertEquals("离线", vm.state.value.message)
        account.value = account.value.copy(id = "b", accountSubject = "another"); runCurrent()
        assertEquals("", vm.state.value.confirmedRange)
        assertTrue(vm.state.value.records.isEmpty())
    }
}
