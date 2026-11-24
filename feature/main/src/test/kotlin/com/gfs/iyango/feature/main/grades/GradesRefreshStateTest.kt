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
class GradesRefreshStateTest {
    private val account = MutableStateFlow(SchoolConnection(id = "a", accountSubject = "student", status = SchoolConnectionStatus.ACTIVE))
    private val rows = listOf(GradeRecord(id = 1, semester = "term", courseName = "课程", credit = 2.0, scoreText = "90"))
    private var response: suspend () -> RefreshTaskState = { RefreshTaskState(outcome = RefreshOutcome.SUCCESS) }
    private inline fun <reified T> fake(crossinline block: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> block(method.name) } as T
    private fun vm() = GradesViewModel(
        auth = fake { name -> when (name) {
            "observeActiveConnection" -> account.map { it.takeIf { row -> row.status == SchoolConnectionStatus.ACTIVE } }
            "observeConnections" -> account.map { listOf(it) }
            "getConnection" -> account.value
            else -> error(name)
        } },
        repository = fake { name -> when (name) {
            "observeSnapshot" -> flowOf(GradeSnapshot(id = 1, complete = true))
            "observeRecords" -> flowOf(rows)
            "observeSemesters" -> flowOf(listOf("term"))
            "observeUnreadCount" -> flowOf(0)
            else -> error(name)
        } },
        query = fake { name -> when (name) {
            "listTerms" -> Result.success(listOf(GradeTerm("term")))
            else -> error(name)
        } },
        refreshCoordinator = object : RefreshCoordinatorPort {
            override val state = MutableStateFlow(RefreshState())
            override suspend fun refresh(resource: RefreshResource, queryFingerprint: String) = response()
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

    @Test fun `expired refresh uses one login notice and preserves cached grades`() = runTest {
        val vm = start()
        response = { RefreshTaskState(outcome = RefreshOutcome.FAILED, sessionExpired = true, message = "登录状态已失效，请返回教务登录后重试") }
        vm.refresh(); runCurrent()
        assertTrue(vm.state.value.needsLogin)
        assertFalse(vm.state.value.loggedIn)
        assertEquals("", vm.state.value.error)
        assertEquals("", vm.state.value.message)
        assertEquals(rows, vm.state.value.records)
        assertFalse(vm.state.value.querying)
    }
    @Test fun `account expiry outside the query also exposes the login notice`() = runTest {
        val vm = start()
        account.value = account.value.copy(status = SchoolConnectionStatus.EXPIRED); runCurrent()
        assertTrue(vm.state.value.needsLogin)
        assertEquals(rows, vm.state.value.records)
    }
    @Test fun `successful login clears the old login error without clearing cache`() = runTest {
        val vm = start()
        account.value = account.value.copy(status = SchoolConnectionStatus.EXPIRED); runCurrent()
        vm.refresh(); runCurrent()
        account.value = account.value.copy(status = SchoolConnectionStatus.ACTIVE, sessionRevision = 2); runCurrent()
        assertTrue(vm.state.value.loggedIn)
        assertFalse(vm.state.value.needsLogin)
        assertEquals("", vm.state.value.error)
        assertEquals("", vm.state.value.message)
        assertEquals(rows, vm.state.value.records)
    }
    @Test fun `cancelled worker releases refreshing and the next refresh can succeed`() = runTest {
        val vm = start()
        response = { throw CancellationException("worker cancelled") }
        vm.refresh(); runCurrent()
        assertFalse(vm.state.value.querying)
        assertEquals("刷新已取消，请重试", vm.state.value.message)
        response = { RefreshTaskState(outcome = RefreshOutcome.SUCCESS) }
        vm.refresh(); runCurrent()
        assertEquals("刷新成功", vm.state.value.message)
        assertEquals(rows, vm.state.value.records)
    }
    @Test fun `network failure remains an ordinary error and never expires a valid account`() = runTest {
        val vm = start()
        response = { RefreshTaskState(outcome = RefreshOutcome.FAILED, message = "网络不可用") }
        vm.refresh(); runCurrent()
        assertTrue(vm.state.value.loggedIn)
        assertFalse(vm.state.value.needsLogin)
        assertEquals("网络不可用", vm.state.value.error)
        assertFalse(vm.state.value.querying)
    }
    @Test fun `late expiry from an old query cannot overwrite a new login`() = runTest {
        val vm = start()
        val pending = CompletableDeferred<RefreshTaskState>()
        response = { withContext(NonCancellable) { pending.await() } }
        vm.refresh(); runCurrent()
        account.value = account.value.copy(sessionRevision = 2); runCurrent()
        pending.complete(RefreshTaskState(outcome = RefreshOutcome.FAILED, sessionExpired = true, message = "旧会话过期"))
        runCurrent()
        assertTrue(vm.state.value.loggedIn)
        assertFalse(vm.state.value.needsLogin)
        assertFalse(vm.state.value.querying)
        assertEquals("", vm.state.value.error)
        assertEquals("", vm.state.value.message)
    }
}
