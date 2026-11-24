package com.gfs.iyango.feature.main.school

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.domain.RefreshCoordinatorPort
import com.gfs.iyango.core.domain.RefreshResource
import com.gfs.iyango.core.domain.SchoolAuthPort
import com.gfs.iyango.core.model.SchoolConnection
import com.gfs.iyango.core.model.SchoolLoginResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal enum class SchoolLoginEntry(val title: String) { OTHER("登录其他账号"), HISTORY("重新登录") }

internal sealed interface AccountProblem {
    val message: String
    data class Action(override val message: String) : AccountProblem
    data class Login(override val message: String, val rejectedCredentials: Boolean = false) : AccountProblem
    data class Restore(val connectionId: String, override val message: String) : AccountProblem
}

/**
 * 教务账号页 ViewModel（F01）。
 *
 * 提交条件由 UI 从账号/密码/提交状态派生；本类再做一次原子校验。
 * 每次登录/重登带请求序号，只有最新一次操作能更新提示，避免切换账号时
 * 旧结果覆盖新账号的界面。
 *
 * 登录成功后的课表/资料同步在后台静默执行（与鸿蒙登录页一致）：
 * 失败只经刷新协调器落诊断日志，本页不追加任何 UI。
 */
@HiltViewModel
class SchoolAccountViewModel @Inject constructor(
    private val auth: SchoolAuthPort,
    private val refreshCoordinator: RefreshCoordinatorPort,
) : ViewModel() {

    val connections: StateFlow<List<SchoolConnection>?> = auth.observeConnections()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val activeConnection: StateFlow<SchoolConnection?> = auth.observeActiveConnection()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val account = MutableStateFlow("")
    val password = MutableStateFlow("")
    val rememberPassword = MutableStateFlow(true)
    internal val loginEntry = MutableStateFlow<SchoolLoginEntry?>(null)

    val submitting = MutableStateFlow(false)
    val busyConnectionId = MutableStateFlow<String?>(null)
    internal val restoringId = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)
    internal val error = MutableStateFlow<AccountProblem?>(null)

    val confirmDelete = MutableStateFlow<SchoolConnection?>(null)
    val confirmClearHistory = MutableStateFlow(false)
    val logoutDialog = MutableStateFlow(false)

    private var requestSeq = 0
    private var formRevision = 0
    private val isBusy: Boolean get() = submitting.value || busyConnectionId.value != null || restoringId.value != null

    val canSubmit: Boolean
        get() = account.value.trim().isNotEmpty() && password.value.isNotEmpty() && !isBusy

    fun setAccount(value: String) {
        if (isBusy) return
        formRevision++
        account.value = value
        error.value = null
    }

    fun setPassword(value: String) {
        if (isBusy) return
        password.value = value
        error.value = null
    }

    fun setRememberPassword(value: Boolean) {
        if (isBusy) return
        rememberPassword.value = value
    }

    fun openLoginForm() {
        if (isBusy) return
        formRevision++
        account.value = ""
        password.value = ""
        error.value = null
        message.value = null
        loginEntry.value = SchoolLoginEntry.OTHER
    }

    fun closeLoginForm() {
        formRevision++
        loginEntry.value = null
        error.value = null
        password.value = ""
    }

    fun submitLogin() {
        if (isBusy) return
        val accountValue = account.value.trim()
        val passwordValue = password.value
        if (accountValue.isEmpty() || passwordValue.isEmpty()) {
            error.value = AccountProblem.Login("请输入教务账号和密码")
            return
        }
        val seq = ++requestSeq
        formRevision++
        submitting.value = true
        message.value = "正在登录教务系统…"
        error.value = null
        viewModelScope.launch {
            val result = try {
                auth.login(accountValue, passwordValue, rememberPassword.value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                SchoolLoginResult.Failed("登录失败，请稍后重试")
            }
            if (seq != requestSeq) return@launch
            submitting.value = false
            password.value = ""
            when (result) {
                is SchoolLoginResult.Success -> {
                    account.value = ""
                    loginEntry.value = null
                    message.value = "已登录：${result.connection.profile.displayName.ifEmpty { result.connection.maskedAccount }}"
                    // 登录后的附加动作（课表/资料同步）后台静默执行；失败只进诊断日志。
                    silentPostLoginSync()
                }
                is SchoolLoginResult.Rejected -> {
                    message.value = null
                    error.value = AccountProblem.Login(result.message, rejectedCredentials = true)
                }
                is SchoolLoginResult.Failed -> {
                    message.value = null
                    error.value = AccountProblem.Login(result.message)
                }
            }
        }
    }

    /**
     * 登录成功后的静默收尾（与鸿蒙登录页 runSync 对齐）：刷新课表与个人资料。
     * 不等待结果、不弹任何提示；失败由刷新协调器记录到诊断日志，页面保持单一用途。
     */
    private fun silentPostLoginSync() {
        viewModelScope.launch { runCatching { refreshCoordinator.refresh(RefreshResource.TIMETABLE) } }
        viewModelScope.launch { runCatching { refreshCoordinator.refresh(RefreshResource.PROFILE) } }
    }

    fun restore(connectionId: String) {
        if (isBusy) return
        formRevision++
        val seq = ++requestSeq
        restoringId.value = connectionId
        message.value = null
        error.value = null
        viewModelScope.launch {
            val result = try {
                auth.restoreSession(connectionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                SchoolLoginResult.Failed("恢复会话失败，请稍后重试")
            }
            if (seq != requestSeq) return@launch
            restoringId.value = null
            when (result) {
                is SchoolLoginResult.Success -> {
                    account.value = ""
                    password.value = ""
                    loginEntry.value = null
                    message.value = "已切换账号"
                    silentPostLoginSync()
                }
                is SchoolLoginResult.Rejected -> {
                    message.value = null
                    error.value = AccountProblem.Restore(connectionId, result.message)
                }
                is SchoolLoginResult.Failed -> {
                    message.value = null
                    error.value = AccountProblem.Restore(connectionId, result.message)
                }
            }
        }
    }

    fun refreshProfile() {
        val connection = activeConnection.value ?: return
        if (isBusy) return
        val seq = ++requestSeq
        busyConnectionId.value = connection.id
        message.value = "正在获取个人信息…"
        error.value = null
        viewModelScope.launch {
            var timedOut = false
            val result = try {
                withTimeout(PROFILE_REFRESH_TIMEOUT_MILLIS) {
                    auth.fetchProfile(connection.id)
                }
            } catch (_: TimeoutCancellationException) {
                timedOut = true
                Result.failure(IllegalStateException("个人信息获取超时"))
            } catch (cancelled: CancellationException) {
                if (seq == requestSeq) busyConnectionId.value = null
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
            if (seq != requestSeq) return@launch
            busyConnectionId.value = null
            if (timedOut) {
                message.value = null
                error.value = AccountProblem.Action("个人信息获取超时，请稍后重试")
                return@launch
            }
            result.onSuccess {
                message.value = "个人信息已更新"
            }.onFailure {
                message.value = null
                error.value = AccountProblem.Action("个人信息暂未获取，请稍后重试")
            }
        }
    }

    fun requestLogout() {
        if (isBusy) return
        logoutDialog.value = true
    }

    fun dismissLogout() {
        logoutDialog.value = false
    }

    fun logout(clearSavedPassword: Boolean) {
        if (isBusy) return
        val connection = activeConnection.value ?: return
        logoutDialog.value = false
        val seq = ++requestSeq
        busyConnectionId.value = connection.id
        message.value = null
        error.value = null
        viewModelScope.launch {
            runCatching { auth.logout(connection.id, clearSavedPassword) }
                .onFailure {
                    if (it is CancellationException) throw it
                    busyConnectionId.value = null
                    if (seq == requestSeq) error.value = AccountProblem.Action("退出失败，请稍后重试")
                    return@launch
                }
            if (seq == requestSeq) {
                busyConnectionId.value = null
                formRevision++
                account.value = ""
                password.value = ""
                message.value = "已退出登录，本地课表保留"
            }
        }
    }

    fun requestDelete(connection: SchoolConnection) {
        if (isBusy) return
        confirmDelete.value = connection
    }

    fun dismissDelete() {
        confirmDelete.value = null
    }

    /**
     * 点历史账号：把账号回填到登录表单并展开表单（与鸿蒙 pickHistory 一致）。
     *
     * 先展示学号，再从 Keystore 读取当时的登录账号替换（异步期间用户已改写则不动）；
     * 不回填密码，也不触发会话恢复；恢复会话仍走行内的"重登"入口。
     */
    fun fillFromHistory(connection: SchoolConnection) {
        if (isBusy) return
        val revision = ++formRevision
        val fallback = connection.accountSubject
        account.value = fallback
        password.value = ""
        loginEntry.value = SchoolLoginEntry.HISTORY
        error.value = null
        message.value = null
        viewModelScope.launch {
            val saved = runCatching { auth.historyAccount(connection.id) }.getOrNull()
            if (saved.isNullOrBlank() || saved == fallback) return@launch
            if (revision == formRevision && loginEntry.value == SchoolLoginEntry.HISTORY && account.value == fallback) account.value = saved
        }
    }

    fun requestClearHistory() {
        if (isBusy) return
        confirmClearHistory.value = true
    }

    fun dismissClearHistory() {
        confirmClearHistory.value = false
    }

    /** 清空历史账号：删除除当前登录账号外的全部连接与保存凭据。 */
    fun clearHistory() {
        if (isBusy) return
        confirmClearHistory.value = false
        val keepId = activeConnection.value?.id.orEmpty()
        busyConnectionId.value = keepId
        error.value = null
        message.value = null
        viewModelScope.launch {
            runCatching { auth.clearHistory(keepId) }
                .onFailure {
                    if (it is CancellationException) throw it
                    error.value = AccountProblem.Action("清空历史失败，请稍后重试")
                }
                .onSuccess {
                    message.value = if (keepId.isEmpty()) "已清空历史账号" else "已清空历史账号，当前登录账号保留"
                }
            busyConnectionId.value = null
        }
    }

    fun deleteConnection() {
        if (isBusy) return
        val connection = confirmDelete.value ?: return
        confirmDelete.value = null
        val seq = ++requestSeq
        busyConnectionId.value = connection.id
        error.value = null
        message.value = null
        viewModelScope.launch {
            runCatching { auth.deleteConnection(connection.id) }
                .onFailure {
                    if (it is CancellationException) throw it
                    busyConnectionId.value = null
                    if (seq == requestSeq) error.value = AccountProblem.Action("删除账号失败，请稍后重试")
                    return@launch
                }
            if (seq == requestSeq) {
                busyConnectionId.value = null
                message.value = "已删除账号与保存的凭据；本地课表不会被删除"
            }
        }
    }

    fun consumeMessage() {
        message.value = null
    }

    fun consumeError() {
        error.value = null
    }

    private companion object {
        const val PROFILE_REFRESH_TIMEOUT_MILLIS = 30_000L
    }
}
