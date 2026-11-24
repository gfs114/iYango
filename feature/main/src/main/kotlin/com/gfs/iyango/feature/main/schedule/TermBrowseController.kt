package com.gfs.iyango.feature.main.schedule

import com.gfs.iyango.core.domain.*
import com.gfs.iyango.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns only the browsing session. There is deliberately no sync or current-table write port. */
class TermBrowseController(
    private val school: SchoolTimetablePort,
    private val cache: TermBrowseCachePort,
    private val scope: CoroutineScope,
    private val readDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    data class State(
        val account: SchoolConnection? = null,
        val terms: List<SchoolTermOption> = emptyList(),
        val selected: String? = null,
        val data: SchoolTimetableData? = null,
        val listLoading: Boolean = false,
        val loading: Boolean = false,
        val listError: String? = null,
        val error: String? = null,
        val refreshNotice: String? = null,
        val loginRequired: Boolean = false,
        val week: Int = 1,
        val revision: Long = 0,
        val navigationVersion: Long = 0,
    )
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var accountVersion = 0L
    private var selectionVersion = 0L
    private var listVersion = 0L
    private var listJob: Job? = null
    private var fetchJob: Job? = null
    private val weeks = mutableMapOf<Pair<String, String>, Int>()
    private fun key(account: SchoolConnection) = listOf(
        account.schoolId,
        account.accountSubject.ifBlank { account.id },
    ).joinToString("\u0000")

    fun accountChanged(account: SchoolConnection?) {
        val previous = mutable.value.account
        if (previous?.id == account?.id && previous?.schoolId == account?.schoolId &&
            previous?.accountSubject == account?.accountSubject && previous?.sessionRevision == account?.sessionRevision &&
            previous?.status == account?.status) return
        accountVersion++
        selectionVersion++
        listVersion++
        listJob?.cancel()
        fetchJob?.cancel()
        val sameIdentity = previous != null && account != null && key(previous) == key(account)
        if (sameIdentity) {
            mutable.value = mutable.value.copy(
                account = account,
                loading = false,
                listLoading = false,
                refreshNotice = null,
                loginRequired = account.status != SchoolConnectionStatus.ACTIVE,
                error = if (account.status != SchoolConnectionStatus.ACTIVE) {
                    "登录状态已失效，缓存已保留"
                } else mutable.value.error,
            )
            if (account.status == SchoolConnectionStatus.ACTIVE) loadTerms()
        } else {
            mutable.value = State(
                account = account,
                revision = mutable.value.revision + 1,
                loginRequired = account == null || account.status != SchoolConnectionStatus.ACTIVE,
            )
            if (account != null) loadTerms()
        }
    }

    fun loadTerms() {
        val account = mutable.value.account ?: return
        val version = accountVersion
        val request = ++listVersion
        listJob?.cancel()
        mutable.value = mutable.value.copy(listLoading = true, listError = null)
        listJob = scope.launch {
            val cached = cacheOrNull { cache.readTerms(key(account)) }.orEmpty()
            if (version != accountVersion || request != listVersion) return@launch
            if (cached.isNotEmpty()) mutable.value = mutable.value.copy(terms = cached)
            val result = try {
                schoolRequest { school.listTerms(account.id) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            if (version != accountVersion || request != listVersion) return@launch
            result.fold(onSuccess = { terms ->
                val valid = terms.filter { it.value.isNotBlank() }.distinctBy { it.value }
                currentCoroutineContext().ensureActive()
                cacheOrNull { cache.writeTerms(key(account), valid) }
                if (version == accountVersion && request == listVersion) {
                    mutable.value = mutable.value.copy(terms = valid, listLoading = false, listError = null)
                }
            }, onFailure = { error ->
                mutable.value = mutable.value.copy(
                    listLoading = false,
                    listError = error.message ?: "学期列表读取失败，请重试",
                    loginRequired = mutable.value.loginRequired || error is SessionExpiredException,
                )
            })
        }
    }

    fun select(term: String) {
        val current = mutable.value
        val account = current.account ?: return
        if (current.terms.none { it.value == term }) return
        selectionVersion++
        fetchJob?.cancel()
        mutable.value = current.copy(
            selected = term,
            data = null,
            loading = true,
            error = null,
            // Zero follows the school current term's actual week when its dates are known.
            week = weeks[key(account) to term] ?: if (current.terms.firstOrNull()?.value == term) 0 else 1,
            revision = current.revision + 1,
            navigationVersion = current.navigationVersion + 1,
        )
        fetch(term)
    }
    fun returnToCurrent() {
        selectionVersion++
        fetchJob?.cancel()
        mutable.value = mutable.value.copy(
            selected = null,
            data = null,
            loading = false,
            error = null,
            refreshNotice = null,
            revision = mutable.value.revision + 1,
        )
    }
    fun refresh() {
        if (mutable.value.loading) return
        mutable.value.selected?.let { fetch(it, showResult = true) }
    }
    fun consumeRefreshNotice() { mutable.value = mutable.value.copy(refreshNotice = null) }
    fun pickWeek(week: Int) {
        val current = mutable.value
        val account = current.account ?: return
        val term = current.selected ?: return
        weeks[key(account) to term] = week
        mutable.value = current.copy(week = week, navigationVersion = current.navigationVersion + 1)
    }
    private suspend fun <T> cacheOrNull(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun fetch(term: String, showResult: Boolean = false) {
        val account = mutable.value.account ?: return
        val av = accountVersion
        val sv = ++selectionVersion
        fetchJob?.cancel()
        mutable.value = mutable.value.copy(loading = true, error = null, refreshNotice = null)
        fetchJob = scope.launch {
            fun current() = av == accountVersion && sv == selectionVersion && mutable.value.selected == term
            val cached = cacheOrNull { cache.readTimetable(key(account), term) }?.takeIf { it.complete && it.termValue == term }
            if (!current()) return@launch
            if (mutable.value.data == null && cached != null) mutable.value = mutable.value.copy(data = cached)
            val result = try {
                schoolRequest { school.fetchTimetable(account.id, term) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                SchoolTimetableResult.Failed(error.message ?: "课表读取失败，请重试")
            }
            if (!current()) return@launch
            when (result) {
                is SchoolTimetableResult.Success -> {
                    if (!result.data.complete || result.data.termValue != term) {
                        mutable.value = mutable.value.copy(loading = false, error = "学校课表不完整，原缓存已保留",
                            refreshNotice = if (showResult) "学校课表不完整，原缓存已保留" else null)
                    } else {
                        currentCoroutineContext().ensureActive()
                        cacheOrNull { cache.writeTimetable(key(account), term, result.data) }
                        if (current()) mutable.value = mutable.value.copy(data = result.data, loading = false, error = null,
                            refreshNotice = if (showResult) "历史课表刷新成功" else null)
                    }
                }
                is SchoolTimetableResult.Failed -> mutable.value = mutable.value.copy(
                    loading = false,
                    error = result.message,
                    refreshNotice = if (showResult) result.message else null,
                )
                is SchoolTimetableResult.SessionExpired -> mutable.value = mutable.value.copy(
                    loading = false,
                    error = result.message,
                    loginRequired = true,
                    refreshNotice = if (showResult) result.message else null,
                )
            }
        }
    }

    private suspend fun <T> schoolRequest(block: suspend () -> T): T {
        val request = scope.async(readDispatcher) { runCatching { block() } }
        return try {
            withTimeout(30_000) { request.await().getOrThrow() }
        } catch (_: TimeoutCancellationException) {
            throw IllegalStateException("教务请求超时，原缓存已保留")
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw IllegalStateException("刷新已取消，请重试")
        } finally { request.cancel() }
    }
}
