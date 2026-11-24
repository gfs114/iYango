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
class GradeSelectionViewModelTest {
    private val account = MutableStateFlow<SchoolConnection?>(SchoolConnection(id = "one", accountSubject = "student", status = SchoolConnectionStatus.ACTIVE))
    private val rows = MutableStateFlow(listOf(
        GradeRecord(id=1, courseName="Same", courseCode="A", credit=2.0, scoreText="80", gradePoint=3.0,isNew=true),
        GradeRecord(id=2, courseName="Same", courseCode="A", credit=1.0, scoreText="100"),
        GradeRecord(id=3, courseName="Text", credit=2.0, scoreText="合格"),
        GradeRecord(id=4, courseName="Zero", credit=0.0, scoreText="99"),
        GradeRecord(id=5, courseName="PublicElective", credit=1.0, scoreText="60", attribute="公选"),
    ))
    private val snapshot = MutableStateFlow<GradeSnapshot?>(GradeSnapshot(id=10, fetchedAt=100, complete=true))
    private var requests=0
    private inline fun <reified T> fake(crossinline block:(String,Array<out Any?>)->Any?):T =
        Proxy.newProxyInstance(T::class.java.classLoader,arrayOf(T::class.java)) { _, method, args ->
            block(method.name,args ?: emptyArray())
        } as T
    private fun vm() = GradesViewModel(
        auth=fake { name,_ -> when(name) {
            "observeActiveConnection" -> account
            "observeConnections" -> account.map { listOfNotNull(it) }
            "getConnection" -> account.value
            else -> error(name)
        } },
        repository=fake { name,_ -> when(name) {
            "observeSnapshot" -> snapshot
            "observeRecords" -> rows
            "observeSemesters" -> flowOf(listOf("term"))
            "observeUnreadCount" -> flowOf(0)
            "markChangesRead" -> { rows.value=rows.value.map { it.copy(isNew=false,isModified=false) }; Unit }
            else -> error("Unexpected grade write: $name")
        } },
        query=object : GradeQueryPort {
            override suspend fun listTerms(connectionId: String) =
                Result.success(listOf(GradeTerm("term", "term"), GradeTerm("other", "other")))
            override suspend fun query(connectionId: String, semesterValue: String): GradeQueryResult =
                error("Unexpected school query")
        },
        refreshCoordinator=fake { name,_ -> when(name) {
            "getState" -> MutableStateFlow(RefreshState())
            "refresh" -> { requests++; RefreshTaskState(outcome=RefreshOutcome.FAILED,message="offline") }
            else -> error(name)
        } },
    )
    @After fun cleanup() { Dispatchers.resetMain() }
    @Test fun `failed manual refresh shows the failure in the transient result notice`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = vm(); runCurrent()
        vm.refresh(); runCurrent()
        assertEquals("offline", vm.state.value.message)
        assertFalse(vm.state.value.querying)
    }
    @Test fun `selected rows reuse weighted rules without merging duplicate records or inventing GPA`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm=vm(); runCurrent(); val context=vm.state.value.selectionContext
        vm.beginSelection(context)
        assertTrue(vm.state.value.statisticsRecords.isEmpty())
        vm.toggleSelection(1,context); vm.toggleSelection(2,context)
        val stats=GradeStatisticsCalculator.calculate(vm.state.value.statisticsRecords)
        assertEquals(2,stats.includedCount); assertEquals(260.0/3,stats.weightedAverage!!,.0001)
        assertEquals(3.0,stats.averageGradePoint!!,.0001)
        vm.clearSelection(context); vm.toggleSelection(2,context)
        assertNull(GradeStatisticsCalculator.calculate(vm.state.value.statisticsRecords).averageGradePoint)
        vm.selectAll(context); vm.setIncludeOptional(false)
        val excluded=GradeStatisticsCalculator.calculate(vm.state.value.statisticsRecords,vm.state.value.includeOptional)
        assertEquals(2,excluded.includedCount); assertEquals(3,excluded.excludedCount)
        vm.exitSelection(context)
        assertEquals(rows.value,vm.state.value.statisticsRecords)
        assertEquals(0,requests)
    }
    @Test fun `sort read marks and failed refresh preserve selection but new snapshot and range reset`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm=vm(); runCurrent(); val context=vm.state.value.selectionContext
        vm.beginSelection(context); vm.toggleSelection(1,context)
        vm.setSort(GradeSort.SCORE_DESC); vm.markRead(); runCurrent()
        vm.refresh(); runCurrent()
        assertEquals(setOf(1L),vm.state.value.selectedRecordIds)
        rows.value=rows.value.map { it.copy(id=it.id+10) }; snapshot.value=snapshot.value!!.copy(id=11,fetchedAt=200)
        runCurrent()
        assertTrue(vm.state.value.selectedRecordIds.isEmpty())
        assertEquals("已更新成绩，请重新选择",vm.state.value.selectionNotice)
        vm.toggleSelection(11,context) // stale callback from the previous snapshot
        assertTrue(vm.state.value.selectedRecordIds.isEmpty())
        val fresh=vm.state.value.selectionContext
        vm.toggleSelection(11,fresh)
        vm.selectDraftRange("other"); runCurrent()
        assertEquals("other", vm.state.value.confirmedRange)
        assertEquals(1, requests)
        assertTrue(vm.state.value.selectedRecordIds.isEmpty())
        assertTrue(vm.state.value.selectionMode)
    }
    @Test fun `account change invalidates selection callbacks while expired cache remains usable`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm=vm(); runCurrent(); val context=vm.state.value.selectionContext
        vm.beginSelection(context); vm.toggleSelection(1,context)
        account.value=account.value!!.copy(status=SchoolConnectionStatus.EXPIRED); runCurrent()
        assertEquals(setOf(1L),vm.state.value.selectedRecordIds)
        account.value=account.value!!.copy(id="two",accountSubject="other"); runCurrent()
        assertFalse(vm.state.value.selectionMode)
        vm.beginSelection(context); assertFalse(vm.state.value.selectionMode)
        vm.beginSelection(vm.state.value.selectionContext)
        vm.toggleSelection(2,vm.state.value.selectionContext)
        assertEquals(setOf(2L),vm.state.value.selectedRecordIds)
    }
    @Test fun `selecting the same range preserves selection but a replacement snapshot alone invalidates it`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm=vm(); runCurrent()
        val context=vm.state.value.selectionContext
        vm.beginSelection(context); vm.toggleSelection(1,context)
        vm.selectDraftRange(vm.state.value.confirmedRange); runCurrent()
        assertEquals(setOf(1L),vm.state.value.selectedRecordIds)
        snapshot.value=snapshot.value!!.copy(id=99)
        runCurrent()
        assertTrue(vm.state.value.selectedRecordIds.isEmpty())
        assertNotEquals(context,vm.state.value.selectionContext)
        assertEquals("已更新成绩，请重新选择",vm.state.value.selectionNotice)
        vm.toggleSelection(1,vm.state.value.selectionContext)
        rows.value=emptyList()
        snapshot.value=snapshot.value!!.copy(id=100)
        runCurrent()
        assertFalse(vm.state.value.selectionMode)
        assertTrue(vm.state.value.selectedRecordIds.isEmpty())
        assertEquals("已更新成绩，请重新选择",vm.state.value.selectionNotice)
    }

    @Test fun `opens on all semesters and only exposes semesters that have grades`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = vm(); runCurrent()
        assertEquals("", vm.state.value.confirmedRange)
        assertEquals("", vm.state.value.draftRange)
        assertEquals(listOf(GradeTerm("term", "term")), vm.state.value.terms)
        vm.selectDraftRange("term"); runCurrent()
        assertEquals("term", vm.state.value.confirmedRange)
        assertEquals("term", vm.state.value.draftRange)
        vm.selectDraftRange(""); runCurrent()
        assertEquals("", vm.state.value.confirmedRange)
    }

    @Test fun `attribute selection supports multiple groups manual exceptions and exact weighted scores`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        rows.value = rows.value.take(3).mapIndexed { index, row -> row.copy(
            attribute = if (index < 2) " 必修 " else "选修", scoreValue = if (index == 2) 60.0 else null,
        ) }
        val vm = vm(); runCurrent(); val context = vm.state.value.selectionContext
        vm.beginSelection(context)
        vm.toggleAttributeSelection("必修", context)
        assertEquals(setOf(1L, 2L), vm.state.value.selectedRecordIds)
        assertEquals(260.0 / 3, GradeStatisticsCalculator.calculate(vm.state.value.statisticsRecords).weightedAverage!!, .0001)
        vm.toggleAttributeSelection("选修", context)
        assertEquals(setOf(1L, 2L, 3L), vm.state.value.selectedRecordIds)
        assertEquals(76.0, GradeStatisticsCalculator.calculate(vm.state.value.statisticsRecords).weightedAverage!!, .0001)
        vm.toggleSelection(1, context)
        vm.toggleAttributeSelection("必修", context) // A partial group selects its missing rows.
        assertEquals(setOf(1L, 2L, 3L), vm.state.value.selectedRecordIds)
        vm.toggleAttributeSelection("必修", context)
        assertEquals(setOf(3L), vm.state.value.selectedRecordIds)
        assertEquals(0, requests)
    }

    @Test fun `public elective attribute selection enables inclusion while preserving the user override`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = vm(); runCurrent(); val context = vm.state.value.selectionContext
        vm.beginSelection(context)
        vm.toggleAttributeSelection("公选", context)
        assertEquals(setOf(5L), vm.state.value.selectedRecordIds)
        assertTrue(vm.state.value.includeOptional)
        assertEquals(60.0, GradeStatisticsCalculator.calculate(vm.state.value.statisticsRecords,
            vm.state.value.includeOptional).weightedAverage!!, .0001)
        vm.setIncludeOptional(false)
        assertNull(GradeStatisticsCalculator.calculate(vm.state.value.statisticsRecords,
            vm.state.value.includeOptional).weightedAverage)
        vm.toggleAttributeSelection("公选", context)
        assertTrue(vm.state.value.selectedRecordIds.isEmpty())
        assertFalse(vm.state.value.includeOptional)
        assertEquals(0, requests)
    }

    @Test fun `unmarked rows remain selectable and stale attribute actions cannot affect a new context`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        rows.value = rows.value + GradeRecord(id = 0, attribute = "无效记录")
        val vm = vm(); runCurrent(); val context = vm.state.value.selectionContext
        vm.toggleAttributeSelection("", context)
        assertTrue(vm.state.value.selectedRecordIds.isEmpty())
        vm.beginSelection(context)
        vm.toggleAttributeSelection(" ", context)
        assertEquals(setOf(1L, 2L, 3L, 4L), vm.state.value.selectedRecordIds)
        vm.toggleAttributeSelection("无效记录", context)
        vm.toggleAttributeSelection("不存在", context)
        assertEquals(setOf(1L, 2L, 3L, 4L), vm.state.value.selectedRecordIds)
        snapshot.value = snapshot.value!!.copy(id = 99); runCurrent()
        vm.toggleAttributeSelection("公选", context)
        assertEquals(setOf(1L, 2L, 3L, 4L), vm.state.value.selectedRecordIds)
        assertFalse(vm.state.value.includeOptional)
        val fresh = vm.state.value.selectionContext
        account.value = account.value!!.copy(id = "other", accountSubject = "other"); runCurrent()
        vm.beginSelection(vm.state.value.selectionContext)
        vm.toggleAttributeSelection("公选", fresh)
        assertTrue(vm.state.value.selectedRecordIds.isEmpty())
        assertFalse(vm.state.value.includeOptional)
        assertEquals(0, requests)
    }

}
