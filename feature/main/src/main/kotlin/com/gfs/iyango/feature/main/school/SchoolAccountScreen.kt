@file:OptIn(ExperimentalMaterial3Api::class)

package com.gfs.iyango.feature.main.school

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.compose.NavigationBackHandler
import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassTopBarIconButton
import com.gfs.iyango.core.designsystem.component.RefreshNoticeCapsule
import com.gfs.iyango.core.model.SchoolConnection
import com.gfs.iyango.feature.main.backPreviewEnterLayer
import com.gfs.iyango.feature.main.backPreviewLayer
import com.gfs.iyango.feature.main.blockInputWhenInactive
import com.gfs.iyango.feature.main.rememberBackPreviewState

internal data class SchoolAccountUiState(
    val active: SchoolConnection? = null,
    val connections: List<SchoolConnection> = emptyList(),
    val account: String = "",
    val password: String = "",
    val rememberPassword: Boolean = true,
    val loginEntry: SchoolLoginEntry? = null,
    val submitting: Boolean = false,
    val busyConnectionId: String? = null,
    val message: String? = null,
    val problem: AccountProblem? = null,
    val restoringId: String? = null,
) {
    val history = (listOfNotNull(active) + connections).distinctBy { it.id }
    private val loginProblem get() = problem as? AccountProblem.Login
    val credentialError get() = loginProblem?.takeIf { it.rejectedCredentials }?.message
    val formError get() = loginProblem?.takeUnless { it.rejectedCredentials }?.message
    val busy get() = submitting || busyConnectionId != null || restoringId != null
    val canSubmit get() = account.isNotBlank() && password.isNotEmpty() && !busy
}

internal sealed interface AccountAction {
    data class AccountChanged(val value: String) : AccountAction
    data class PasswordChanged(val value: String) : AccountAction
    data class RememberChanged(val value: Boolean) : AccountAction
    data class Fill(val connection: SchoolConnection) : AccountAction
    data class Restore(val id: String) : AccountAction
    data class Delete(val connection: SchoolConnection) : AccountAction
    data object OpenLogin : AccountAction
    data object CloseLogin : AccountAction
    data object Submit : AccountAction
    data object Refresh : AccountAction
    data object Logout : AccountAction
    data object ClearHistory : AccountAction
    data object MessageShown : AccountAction
}

/** Opening this screen only reads account state; authentication stays in the existing ViewModel. */
@Composable
fun SchoolAccountScreen(
    onBack: () -> Unit,
    onOpenSync: () -> Unit,
    viewModel: SchoolAccountViewModel = hiltViewModel(),
) {
    val active by viewModel.activeConnection.collectAsStateWithLifecycle()
    val connections by viewModel.connections.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val password by viewModel.password.collectAsStateWithLifecycle()
    val remember by viewModel.rememberPassword.collectAsStateWithLifecycle()
    val loginEntry by viewModel.loginEntry.collectAsStateWithLifecycle()
    val submitting by viewModel.submitting.collectAsStateWithLifecycle()
    val restoringId by viewModel.restoringId.collectAsStateWithLifecycle()
    val busyId by viewModel.busyConnectionId.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val deleting by viewModel.confirmDelete.collectAsStateWithLifecycle()
    val clearing by viewModel.confirmClearHistory.collectAsStateWithLifecycle()
    val logout by viewModel.logoutDialog.collectAsStateWithLifecycle()

    val knownConnections = connections ?: return
    SchoolAccountContent(
        SchoolAccountUiState(active, knownConnections, account, password, remember, loginEntry,
            submitting, busyId, message, error, restoringId),
        onBack, onOpenSync,
    ) { action ->
        when (action) {
            is AccountAction.AccountChanged -> viewModel.setAccount(action.value)
            is AccountAction.PasswordChanged -> viewModel.setPassword(action.value)
            is AccountAction.RememberChanged -> viewModel.setRememberPassword(action.value)
            is AccountAction.Fill -> viewModel.fillFromHistory(action.connection)
            is AccountAction.Restore -> viewModel.restore(action.id)
            is AccountAction.Delete -> viewModel.requestDelete(action.connection)
            AccountAction.OpenLogin -> viewModel.openLoginForm()
            AccountAction.CloseLogin -> viewModel.closeLoginForm()
            AccountAction.Submit -> viewModel.submitLogin()
            AccountAction.Refresh -> viewModel.refreshProfile()
            AccountAction.Logout -> viewModel.requestLogout()
            AccountAction.ClearHistory -> viewModel.requestClearHistory()
            AccountAction.MessageShown -> viewModel.consumeMessage()
        }
    }
    deleting?.let { connection ->
        AccountConfirmation("删除账号？",
            "将删除 " + connection.accountDisplayName + " 的账号记录和保存凭据，本地课表保留。",
            "删除", viewModel::dismissDelete, viewModel::deleteConnection)
    }
    if (clearing) {
        AccountConfirmation("清空历史账号？",
            if (active != null) "当前登录账号与本地课表保留，其余历史账号和保存凭据将被删除。"
            else "将删除所有历史账号与保存凭据，本地课表保留。",
            "清空", viewModel::dismissClearHistory, viewModel::clearHistory)
    }
    if (logout) {
        var clearPassword by remember { mutableStateOf(true) }
        com.gfs.iyango.core.designsystem.liquidglass.LiquidAlertDialog(
            onDismissRequest = viewModel::dismissLogout,
            title = { Text("退出教务账号？") },
            text = { Column {
                Text("退出后保留本地课表，联网查询需重新登录。")
                RememberPasswordRow(clearPassword, true, "同时清除已保存密码") { clearPassword = it }
            } },
            confirmButton = { TextButton(onClick = { viewModel.logout(clearPassword) }) { Text("退出登录") } },
            dismissButton = { TextButton(onClick = viewModel::dismissLogout) { Text("取消") } },
        )
    }
}

private enum class AccountPage { OVERVIEW, MANAGE, LOGIN, HELP }

@Composable
internal fun SchoolAccountContent(
    state: SchoolAccountUiState,
    onBack: () -> Unit,
    onOpenSync: () -> Unit,
    onAction: (AccountAction) -> Unit,
) {
    var pages by rememberSaveable { mutableStateOf(listOf(AccountPage.OVERVIEW.name)) }
    val page = AccountPage.valueOf(pages.last())
    val pageStates = rememberSaveableStateHolder()
    val uriHandler = LocalUriHandler.current
    val latestState by rememberUpdatedState(state)
    val latestOnAction by rememberUpdatedState(onAction)
    val latestOnBack by rememberUpdatedState(onBack)
    var previousActiveId by remember { mutableStateOf(state.active?.id) }
    var previousEntry by remember { mutableStateOf(state.loginEntry) }
    fun push(next: AccountPage) {
        if (AccountPage.valueOf(pages.last()) != next) pages = pages + next.name
    }
    fun back() {
        val currentPage = AccountPage.valueOf(pages.last())
        if (currentPage == AccountPage.LOGIN || (currentPage == AccountPage.OVERVIEW && latestState.active == null)) {
            latestOnAction(AccountAction.CloseLogin)
        }
        if (pages.size > 1) pages = pages.dropLast(1) else latestOnBack()
    }
    val backPreview = rememberBackPreviewState()
    NavigationBackHandler(
        backPreview.eventState,
        isBackEnabled = pages.size > 1,
        onBackCompleted = { backPreview.complete { back() } },
    )
    LaunchedEffect(state.loginEntry, state.active?.id) {
        if (state.loginEntry != null && AccountPage.LOGIN.name !in pages) {
            pages = pages + AccountPage.LOGIN.name
        } else if (state.loginEntry == null) {
            if (state.active != null && (state.active.id != previousActiveId ||
                    (previousEntry != null && AccountPage.LOGIN.name in pages))) {
                pages = listOf(AccountPage.OVERVIEW.name)
            } else if (AccountPage.LOGIN.name in pages) {
                // The page stack can outlive the form after process death. Reopen its source.
                pages = pages.takeWhile { it != AccountPage.LOGIN.name }
            }
        }
        previousActiveId = state.active?.id
        previousEntry = state.loginEntry
    }
    fun openWebsite() { uriHandler.openUri("http://jwgl.ygu.edu.cn") }
    val previewingPrevious = backPreview.isPreviewing && pages.size > 1
    val previousPage = if (previewingPrevious) AccountPage.valueOf(pages[pages.size - 2]) else null
    Box(Modifier.fillMaxSize()) {
        if (previousPage != null && previousPage != page) {
            AccountSubPage(
                targetPage = previousPage,
                state = state,
                pageStates = pageStates,
                onBack = {},
                onOpenSync = {},
                onPush = {},
                onOpenWebsite = {},
                onAction = {},
                isPreview = true,
                modifier = Modifier
                    .focusProperties { canFocus = false }
                    .clearAndSetSemantics {}
                    .blockInputWhenInactive(false)
                    .backPreviewEnterLayer(backPreview, opaqueForeground = true),
            )
        }
        AccountSubPage(
            targetPage = page,
            state = state,
            pageStates = pageStates,
            onBack = { back() },
            onOpenSync = onOpenSync,
            onPush = { push(it) },
            onOpenWebsite = { openWebsite() },
            onAction = onAction,
            modifier = Modifier.backPreviewLayer(backPreview, cardStyle = true),
        )
    }
}

@Composable
private fun AccountSubPage(
    targetPage: AccountPage,
    state: SchoolAccountUiState,
    pageStates: SaveableStateHolder,
    onBack: () -> Unit,
    onOpenSync: () -> Unit,
    onPush: (AccountPage) -> Unit,
    onOpenWebsite: () -> Unit,
    onAction: (AccountAction) -> Unit,
    isPreview: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    val latestAction by rememberUpdatedState(onAction)
    val title = when (targetPage) {
        AccountPage.OVERVIEW -> if (state.active == null) "教务登录" else "教务账号"
        AccountPage.MANAGE -> "账号管理"
        AccountPage.LOGIN -> state.loginEntry?.title ?: "教务登录"
        AccountPage.HELP -> "登录帮助"
    }
    val canPullRefresh = targetPage == AccountPage.OVERVIEW && state.active != null
    val isRefreshingProfile = canPullRefresh && state.busyConnectionId == state.active?.id
    val showingLogin = targetPage == AccountPage.LOGIN || (targetPage == AccountPage.OVERVIEW && state.active == null)
    Box(modifier.fillMaxSize()) {
        if (showingLogin) {
            pageStates.SaveableStateProvider(targetPage.name) {
                SchoolLoginPage(
                    title = title, state = state, onAction = onAction,
                    onHelp = { onPush(AccountPage.HELP) }, onWebsite = onOpenWebsite,
                )
            }
            return@Box
        }
        val pullState = rememberPullToRefreshState()
        GlassScaffold(
            modifier = Modifier.imePadding(),
            topBar = { GlassTopBar(title = title, navigationIcon = {
                GlassTopBarIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            }) },
            snackbarHost = { SnackbarHost(snackbar) },
            overlay = { padding ->
                if (!isPreview) {
                    RefreshNoticeCapsule(
                        message = if (!state.busy) state.message else null,
                        onDismiss = { latestAction(AccountAction.MessageShown) },
                        topPadding = padding.calculateTopPadding(),
                    )
                }
            },
        ) { padding ->
            PullToRefreshBox(
                isRefreshing = isRefreshingProfile,
                onRefresh = { if (canPullRefresh && !state.busy) onAction(AccountAction.Refresh) },
                state = pullState,
                modifier = Modifier.fillMaxSize(),
                indicator = {
                    if (canPullRefresh) {
                        PullToRefreshDefaults.Indicator(
                            state = pullState,
                            isRefreshing = isRefreshingProfile,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = padding.calculateTopPadding()),
                        )
                    }
                },
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    pageStates.SaveableStateProvider(targetPage.name) {
                        Column(Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(padding).padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (state.busy && !isRefreshingProfile && state.restoringId == null) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(state.message ?: "正在处理…", style = MaterialTheme.typography.bodyMedium)
                            }
                            (state.problem as? AccountProblem.Action)?.let { AccountError(it.message) }
                            when (targetPage) {
                                AccountPage.OVERVIEW -> key(state.active?.id) { AccountOverview(state, onOpenSync, onAction) }
                                AccountPage.MANAGE -> AccountManagement(state, onAction)
                                AccountPage.LOGIN -> Unit
                                AccountPage.HELP -> {
                                    AccountHelp()
                                    AccountEntry("打开教务网站", enabled = !state.busy) {
                                        runCatching { onOpenWebsite() }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountConfirmation(title: String, description: String, action: String,
    onDismiss: () -> Unit, onConfirm: () -> Unit) {
    com.gfs.iyango.core.designsystem.liquidglass.LiquidAlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(description) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
