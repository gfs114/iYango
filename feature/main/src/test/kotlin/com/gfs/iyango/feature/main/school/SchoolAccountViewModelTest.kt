package com.gfs.iyango.feature.main.school

import androidx.lifecycle.ViewModelStore
import com.gfs.iyango.core.domain.*
import com.gfs.iyango.core.model.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SchoolAccountViewModelTest {
    private val current = SchoolConnection(id = "current", maskedAccount = "20****01", status = SchoolConnectionStatus.ACTIVE)
    private val auth = FakeAuth(current)
    private val stores = mutableListOf<ViewModelStore>()
    private val refreshes = mutableListOf<RefreshResource>()

    private fun TestScope.start(): SchoolAccountViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val refresh = Proxy.newProxyInstance(RefreshCoordinatorPort::class.java.classLoader,
            arrayOf(RefreshCoordinatorPort::class.java)) { _, method, args ->
            when (method.name) {
                "refresh" -> { refreshes += args[0] as RefreshResource; RefreshTaskState() }
                else -> error("Unexpected refresh: ${method.name}")
            }
        } as RefreshCoordinatorPort
        return SchoolAccountViewModel(auth, refresh).also { vm ->
            stores += ViewModelStore().apply { put("account", vm) }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.activeConnection.collect() }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.connections.collect() }
            runCurrent()
        }
    }

    @After fun cleanup() { stores.forEach { it.clear() }; Dispatchers.resetMain() }

    @Test fun `viewing an existing account does not restore login or fetch profile`() = runTest {
        val vm = start()
        assertEquals(current, vm.activeConnection.value)
        assertTrue(auth.calls.isEmpty())
        assertNull(vm.message.value)
    }

    @Test fun `login excludes competing operations and can recover after rejection`() = runTest {
        val vm = start()
        val pending = CompletableDeferred<SchoolLoginResult>()
        auth.loginResult = { pending.await() }
        vm.openLoginForm(); vm.setAccount(" test-account "); vm.setPassword("test-password")
        vm.submitLogin(); vm.submitLogin(); vm.restore("other"); vm.refreshProfile(); vm.requestLogout()
        runCurrent()
        assertEquals(listOf("login:test-account"), auth.calls)
        assertFalse(vm.logoutDialog.value)
        pending.complete(SchoolLoginResult.Rejected("账号或密码不正确")); runCurrent()
        assertFalse(vm.submitting.value); assertEquals("", vm.password.value)
        assertEquals(AccountProblem.Login("账号或密码不正确", rejectedCredentials = true), vm.error.value)
        assertEquals(SchoolLoginEntry.OTHER, vm.loginEntry.value)
        auth.loginResult = { SchoolLoginResult.Success(current) }
        vm.setPassword("test-password"); vm.submitLogin(); runCurrent()
        assertNull(vm.loginEntry.value); assertNull(vm.error.value)
        assertEquals(listOf(RefreshResource.TIMETABLE, RefreshResource.PROFILE), refreshes)
    }

    @Test fun `history prefill from earlier selection cannot override a newer equal fallback`() = runTest {
        val vm = start()
        val first = CompletableDeferred<String?>()
        val second = CompletableDeferred<String?>()
        auth.historyResult = { if (it == "first") first.await() else second.await() }
        vm.fillFromHistory(SchoolConnection(id = "first", accountSubject = "shared")); runCurrent()
        vm.fillFromHistory(SchoolConnection(id = "second", accountSubject = "shared")); runCurrent()
        first.complete("obsolete-account"); runCurrent()
        assertEquals("shared", vm.account.value)
        second.complete("selected-account"); runCurrent()
        assertEquals("selected-account", vm.account.value)
        assertEquals(SchoolLoginEntry.HISTORY, vm.loginEntry.value)
        assertTrue(auth.calls.none { it.startsWith("restore") || it.startsWith("login") })
    }

    @Test fun `closing login clears password and prevents late history from filling a reopened form`() = runTest {
        val vm = start()
        val pending = CompletableDeferred<String?>()
        auth.historyResult = { pending.await() }
        vm.fillFromHistory(SchoolConnection(id = "history", accountSubject = "fallback")); runCurrent()
        vm.setPassword("test-password"); vm.closeLoginForm(); vm.openLoginForm()
        pending.complete("obsolete"); runCurrent()
        assertEquals("", vm.password.value); assertEquals("", vm.account.value)
    }

    @Test fun `typing invalidates a pending history read even when the value returns to its fallback`() = runTest {
        val vm = start()
        val pending = CompletableDeferred<String?>()
        auth.historyResult = { pending.await() }
        vm.fillFromHistory(SchoolConnection(id = "history", accountSubject = "fallback")); runCurrent()
        vm.setAccount("manual"); vm.setAccount("fallback")
        pending.complete("saved-account"); runCurrent()
        assertEquals("fallback", vm.account.value)
    }

    @Test fun `saved account restore excludes login and releases controls after failure`() = runTest {
        val vm = start()
        val pending = CompletableDeferred<SchoolLoginResult>()
        auth.restoreResult = { pending.await() }
        vm.setAccount("test-account"); vm.setPassword("test-password")
        vm.restore("history"); vm.submitLogin(); vm.requestDelete(current); runCurrent()
        assertEquals(listOf("restore:history"), auth.calls)
        assertNull(vm.confirmDelete.value)
        pending.complete(SchoolLoginResult.Failed("连接失败")); runCurrent()
        assertNull(vm.restoringId.value); assertEquals(AccountProblem.Restore("history", "连接失败"), vm.error.value)
        assertTrue(vm.canSubmit)
    }

    @Test fun `logout blocks repeat mutation and forwards clear password choice`() = runTest {
        val vm = start()
        val pending = CompletableDeferred<Unit>()
        auth.logoutResult = { pending.await() }
        vm.requestLogout(); vm.logout(clearSavedPassword = false)
        vm.logout(clearSavedPassword = true); vm.restore("history"); runCurrent()
        assertEquals(listOf("logout:current:false"), auth.calls)
        pending.complete(Unit); runCurrent()
        assertNull(vm.busyConnectionId.value); assertNull(vm.activeConnection.value)
        assertEquals("已退出登录，本地课表保留", vm.message.value)
    }

    @Test fun `clear history keeps current account and failure returns control to the user`() = runTest {
        val vm = start()
        auth.clearResult = { throw IllegalStateException("test failure") }
        vm.requestClearHistory(); vm.clearHistory(); runCurrent()
        assertEquals(listOf("clear:current"), auth.calls)
        assertEquals(current, vm.activeConnection.value)
        assertNull(vm.busyConnectionId.value); assertEquals(AccountProblem.Action("清空历史失败，请稍后重试"), vm.error.value)
    }

    @Test fun `delete needs explicit confirmation and cancelling does not mutate accounts`() = runTest {
        val vm = start()
        vm.requestDelete(current); vm.dismissDelete(); vm.deleteConnection(); runCurrent()
        assertTrue(auth.calls.isEmpty())
        vm.requestDelete(current); vm.deleteConnection(); runCurrent()
        assertEquals(listOf("delete:current"), auth.calls)
        assertNull(vm.busyConnectionId.value)
    }

    @Test fun `refreshProfile times out after 30s clears busy state and shows timeout error`() = runTest {
        auth.fetchProfileResult = { awaitCancellation() }
        val vm = start()

        vm.refreshProfile()
        runCurrent()
        assertEquals(current.id, vm.busyConnectionId.value)

        advanceTimeBy(30_001)
        runCurrent()

        assertNull(vm.busyConnectionId.value)
        assertNull(vm.message.value)
        val problem = vm.error.value as? AccountProblem.Action
        assertNotNull(problem)
        assertTrue(problem!!.message.contains("超时"))
    }

    private class FakeAuth(current: SchoolConnection) : SchoolAuthPort {
        val active = MutableStateFlow<SchoolConnection?>(current)
        val all = MutableStateFlow(listOf(current))
        val calls = mutableListOf<String>()
        var loginResult: suspend () -> SchoolLoginResult = { SchoolLoginResult.Success(current) }
        var restoreResult: suspend () -> SchoolLoginResult = { SchoolLoginResult.Success(current) }
        var historyResult: suspend (String) -> String? = { "saved-account" }
        var logoutResult: suspend () -> Unit = {}
        var clearResult: suspend () -> Unit = {}
        var fetchProfileResult: suspend () -> Result<SchoolProfile> = { Result.success(SchoolProfile()) }
        override fun observeConnections() = all
        override fun observeActiveConnection() = active
        override suspend fun getConnection(connectionId: String) = all.value.find { it.id == connectionId }
        override suspend fun historyAccount(connectionId: String): String? = historyResult(connectionId)
        override suspend fun login(account: String, password: String, rememberPassword: Boolean): SchoolLoginResult {
            calls += "login:$account"; return loginResult()
        }
        override suspend fun restoreSession(connectionId: String): SchoolLoginResult {
            calls += "restore:$connectionId"; return restoreResult()
        }
        override suspend fun fetchProfile(connectionId: String): Result<SchoolProfile> {
            calls += "profile:$connectionId"; return fetchProfileResult()
        }
        override suspend fun logout(connectionId: String, clearSavedPassword: Boolean) {
            calls += "logout:$connectionId:$clearSavedPassword"; logoutResult(); active.value = null
        }
        override suspend fun deleteConnection(connectionId: String) { calls += "delete:$connectionId" }
        override suspend fun clearHistory(keepConnectionId: String) { calls += "clear:$keepConnectionId"; clearResult() }
    }
}
