package com.gfs.iyango.feature.main.grades

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.domain.GradeMonitorCoordinatorPort
import com.gfs.iyango.core.domain.GradeQueryPort
import com.gfs.iyango.core.domain.GradeRepositoryPort
import com.gfs.iyango.core.domain.GradeStatisticsCalculator
import com.gfs.iyango.core.domain.NoopGradeMonitorCoordinator
import com.gfs.iyango.core.domain.RefreshCoordinatorPort
import com.gfs.iyango.core.domain.RefreshOutcome
import com.gfs.iyango.core.domain.RefreshResource
import com.gfs.iyango.core.domain.SchoolAuthPort
import com.gfs.iyango.core.model.GradeRecord
import com.gfs.iyango.core.model.GradeSnapshot
import com.gfs.iyango.core.model.GradeSort
import com.gfs.iyango.core.model.GradeTerm
import com.gfs.iyango.core.model.SchoolConnection
import com.gfs.iyango.core.model.SchoolConnectionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A row selection belongs to one displayed cache generation, not to a course name. */
data class GradeSelectionContext(val accountKey: String, val range: String, val revision: Long)

/** 成绩页状态（F03 + E03）。 */
data class GradesUiState(
    val connectionId: String = "",
    val accountKey: String = "",
    val maskedAccount: String = "",
    val loggedIn: Boolean = false,
    val terms: List<GradeTerm> = emptyList(),
    val loadingTerms: Boolean = false,
    val draftRange: String = "",
    val confirmedRange: String = "",
    val initialized: Boolean = false,
    val rangeTouched: Boolean = false,
    val records: List<GradeRecord> = emptyList(),
    val snapshot: GradeSnapshot? = null,
    val unreadCount: Int = 0,
    val sort: GradeSort = GradeSort.SCHOOL_ORDER,
    val includeOptional: Boolean = false,
    val querying: Boolean = false,
    val needsLogin: Boolean = false,
    val error: String = "",
    val message: String = "",
    val selectionMode: Boolean = false,
    val selectedRecordIds: Set<Long> = emptySet(),
    /** Attribute choices to reapply across semester/cache changes; current row checks remain authoritative. */
    val selectionAttributes: Set<String> = emptySet(),
    val selectionRevision: Long = 0,
    val selectionNotice: String = "",
) {
    /** 是否已有登录过的教务账号（会话过期仍可查看缓存）。 */
    val hasAccount: Boolean get() = connectionId.isNotEmpty()
    val selectionContext: GradeSelectionContext
        get() = GradeSelectionContext(accountKey, confirmedRange, selectionRevision)
    val statisticsRecords: List<GradeRecord>
        get() = if (selectionMode) records.filter { it.id in selectedRecordIds } else records
}

/**
 * 成绩查询 ViewModel（F03）。
 *
 * - 学期/范围选择立即生效；[refresh] 更新教务成绩；
 * - 已确认范围变化时重订阅对应分区的离线快照（E03），账号切换先清空页面再读新分区；
 * - 查询经 F05 协调器执行，查询指纹使用缓存分区键（`*` 表示全部学期），
 *   协调器据此共享任务并阻止旧会话响应覆盖。
 */
@HiltViewModel
class GradesViewModel @Inject constructor(
    private val auth: SchoolAuthPort,
    private val repository: GradeRepositoryPort,
    private val query: GradeQueryPort,
    private val refreshCoordinator: RefreshCoordinatorPort,
    private val gradeMonitorCoordinator: GradeMonitorCoordinatorPort = NoopGradeMonitorCoordinator,
) : ViewModel() {

    private val _state = MutableStateFlow(GradesUiState())
    val state: StateFlow<GradesUiState> = _state.asStateFlow()

    private var cacheJob: Job? = null
    private var semestersJob: Job? = null
    private var termsJob: Job? = null
    private var queryJob: Job? = null
    private var queryGeneration = 0L
    private var currentSessionRevision = -1
    private var currentAccountKey = ""
    private var currentConnectionId = ""
    private var schoolTerms: List<GradeTerm> = emptyList()
    private var cachedSemesters: List<String> = emptyList()
    private var rangeInitialized = false
    private var rangeTouched = false
    private var selectionGeneration = 0L

    init {
        viewModelScope.launch {
            combine(
                auth.observeActiveConnection(),
                auth.observeConnections(),
            ) { active, all ->
                active ?: all
                    .filter { it.status != SchoolConnectionStatus.LOGGED_OUT }
                    .maxByOrNull { it.lastLoginAt }
            }
                .distinctUntilChangedBy { connection ->
                    listOf(
                        connection?.id,
                        connection?.status,
                        connection?.sessionRevision,
                        connection?.accountSubject,
                    )
                }
                .collect { connection ->
                    if (connection == null) {
                        detachAccount()
                    } else {
                        bindConnection(connection)
                    }
                }
        }
    }

    fun selectDraftRange(value: String) {
        val activeSet = activeSemesters()
        val interval = GradeTermInterval.parse(value)
        if (GradeTermInterval.isIntervalValue(value) &&
            (interval == null || interval.start !in activeSet || interval.end !in activeSet)) {
            _state.update { it.copy(message = "请选择有效的起止学期") }
            return
        }
        val current = _state.value
        val knownTerm = value in activeSet || schoolTerms.any { it.value == value } ||
            value == current.confirmedRange || value == current.draftRange
        if (value.isNotEmpty() && interval == null && !knownTerm) {
            _state.update { it.copy(message = "请选择有效的学期") }
            return
        }
        rangeTouched = true
        if (value != current.confirmedRange) cancelQuery()
        rangeInitialized = true
        _state.update {
            resetRange(it, value).copy(
                draftRange = value,
                confirmedRange = value,
                rangeTouched = true,
                initialized = true,
                error = "",
                message = "",
            )
        }
        observeCache(value, reapplyAttributes = current.selectionMode && value != current.confirmedRange)
    }

    /** 下拉刷新：只重查已确认条件。 */
    fun refresh() {
        val current = _state.value
        if (!current.hasAccount || !rangeInitialized || queryJob?.isActive == true) return
        if (!current.loggedIn) {
            _state.update {
                it.copy(error = "", message = "", needsLogin = true)
            }
            return
        }
        startQuery(current.confirmedRange, showSuccessNotice = true)
    }

    fun beginSelection(context: GradeSelectionContext) {
        _state.update {
            if (context != it.selectionContext || it.records.none { row -> row.id > 0 }) it
            else it.copy(selectionMode = true, selectedRecordIds = emptySet(), selectionAttributes = emptySet(), selectionNotice = "")
        }
    }

    fun toggleSelection(id: Long, context: GradeSelectionContext) {
        _state.update {
            if (!it.selectionMode || context != it.selectionContext || id <= 0 || it.records.none { row -> row.id == id }) it
            else {
                val selected = if (id in it.selectedRecordIds) it.selectedRecordIds - id else it.selectedRecordIds + id
                val attribute = it.records.first { row -> row.id == id }.attribute.trim()
                it.copy(
                    selectedRecordIds = selected,
                    selectionAttributes = if (it.records.any { row -> row.id in selected && row.attribute.trim() == attribute }) {
                        it.selectionAttributes
                    } else it.selectionAttributes - attribute,
                    selectionNotice = "",
                )
            }
        }
    }

    fun selectAll(context: GradeSelectionContext) {
        _state.update {
            if (!it.selectionMode || context != it.selectionContext) it
            else it.copy(selectedRecordIds = it.records.map { row -> row.id }.filter { id -> id > 0 }.toSet(),
                selectionAttributes = gradeAttributeGroups(it.records).map { group -> group.value }.toSet(), selectionNotice = "")
        }
    }

    fun toggleAttributeSelection(attribute: String, context: GradeSelectionContext) {
        _state.update { state ->
            if (!state.selectionMode || context != state.selectionContext) return@update state
            val group = gradeAttributeGroups(state.records).firstOrNull { it.value == attribute.trim() }
                ?: return@update state
            val select = !state.selectedRecordIds.containsAll(group.recordIds)
            state.copy(
                selectedRecordIds = if (select) state.selectedRecordIds + group.recordIds
                    else state.selectedRecordIds - group.recordIds,
                selectionAttributes = if (select) state.selectionAttributes + group.value else state.selectionAttributes - group.value,
                includeOptional = state.includeOptional ||
                    (select && GradeStatisticsCalculator.isPublicElectiveAttribute(group.value)),
                selectionNotice = "",
            )
        }
    }

    fun clearSelection(context: GradeSelectionContext) {
        _state.update {
            if (context != it.selectionContext) it else it.copy(selectedRecordIds = emptySet(), selectionAttributes = emptySet(), selectionNotice = "")
        }
    }

    fun exitSelection(context: GradeSelectionContext) {
        _state.update {
            if (context != it.selectionContext) it
            else it.copy(selectionMode = false, selectedRecordIds = emptySet(), selectionAttributes = emptySet(), selectionNotice = "")
        }
    }

    private fun resetRange(state: GradesUiState, range: String): GradesUiState =
        if (state.confirmedRange == range) state else state.copy(
            records = emptyList(), snapshot = null,
            selectedRecordIds = emptySet(),
            selectionRevision = ++selectionGeneration, selectionNotice = "",
        )

    fun setSort(value: GradeSort) {
        _state.update { it.copy(sort = value) }
    }

    fun setIncludeOptional(value: Boolean) {
        _state.update { it.copy(includeOptional = value) }
    }

    fun markRead() {
        val current = _state.value
        if (!current.hasAccount) return
        viewModelScope.launch {
            runCatching {
                val interval = GradeTermInterval.parse(current.confirmedRange)
                if (interval != null) {
                    (cachedSemesters + current.records.map { it.semester }).distinct()
                        .filter(interval::contains).forEach { semester ->
                            repository.markChangesRead(current.accountKey, semester)
                        }
                } else {
                    repository.markChangesRead(current.accountKey, partitionOf(current.confirmedRange))
                }
                // “全部学期”汇总视图的未读可能来自各学期分区。
                if (current.confirmedRange.isEmpty()) {
                    repository.observeSemesters(current.accountKey).first().forEach { semester ->
                        repository.markChangesRead(current.accountKey, semester)
                    }
                }
                gradeMonitorCoordinator.clearPostedNotification(current.accountKey)
            }.onFailure {
                _state.update { state -> state.copy(message = "标记已读失败，请稍后重试") }
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = "") }
    }

    fun clearMessage() {
        _state.update { it.copy(message = "") }
    }

    /** 重新拉取学期 metadata；不影响已确认查询。 */
    fun reloadTerms() {
        if (currentConnectionId.isEmpty()) return
        viewModelScope.launch {
            val connection = auth.getConnection(currentConnectionId) ?: return@launch
            loadTerms(connection)
        }
    }

    private fun detachAccount() {
        cacheJob?.cancel()
        semestersJob?.cancel()
        termsJob?.cancel()
        cancelQuery()
        cacheJob = null
        semestersJob = null
        termsJob = null
        queryJob = null
        currentAccountKey = ""
        currentConnectionId = ""
        currentSessionRevision = -1
        schoolTerms = emptyList()
        cachedSemesters = emptyList()
        rangeInitialized = false
        rangeTouched = false
        _state.value = GradesUiState(selectionRevision = ++selectionGeneration)
    }

    private fun bindConnection(connection: SchoolConnection) {
        val accountKey = connection.accountSubject.ifEmpty { connection.id }
        val accountChanged = accountKey != currentAccountKey
        if (currentConnectionId != connection.id || currentSessionRevision != connection.sessionRevision) cancelQuery()
        currentConnectionId = connection.id
        currentSessionRevision = connection.sessionRevision

        if (accountChanged || !rangeInitialized) {
            cacheJob?.cancel()
            semestersJob?.cancel()
            termsJob?.cancel()
            cancelQuery()
            currentAccountKey = accountKey
            schoolTerms = emptyList()
            cachedSemesters = emptyList()
            rangeInitialized = false
            rangeTouched = false
            _state.value = GradesUiState(
                selectionRevision = ++selectionGeneration,
                connectionId = connection.id,
                accountKey = accountKey,
                maskedAccount = connection.maskedAccount,
                loggedIn = connection.status == SchoolConnectionStatus.ACTIVE,
                needsLogin = connection.status == SchoolConnectionStatus.EXPIRED,
                loadingTerms = true,
            )
            observeSemesters(accountKey)
            observeCache("")
            loadTerms(connection)
            return
        }

        _state.update {
            val loggedInAgain = connection.status == SchoolConnectionStatus.ACTIVE && (!it.loggedIn || it.needsLogin)
            it.copy(
                connectionId = connection.id,
                accountKey = accountKey,
                maskedAccount = connection.maskedAccount,
                loggedIn = connection.status == SchoolConnectionStatus.ACTIVE,
                needsLogin = connection.status == SchoolConnectionStatus.EXPIRED,
                error = if (loggedInAgain || connection.status == SchoolConnectionStatus.EXPIRED) "" else it.error,
                message = if (loggedInAgain || connection.status == SchoolConnectionStatus.EXPIRED) "" else it.message,
            )
        }
        if (connection.status == SchoolConnectionStatus.ACTIVE &&
            schoolTerms.isEmpty() && !_state.value.loadingTerms
        ) {
            loadTerms(connection)
        }
    }

    private fun observeSemesters(accountKey: String) {
        semestersJob?.cancel()
        semestersJob = viewModelScope.launch {
            repository.observeSemesters(accountKey).collect { semesters ->
                if (_state.value.accountKey != accountKey) return@collect
                cachedSemesters = semesters
                    .filter { it.isNotBlank() && it != GradeRepositoryPort.ALL_SEMESTERS }
                    .distinct()
                updateVisibleTerms()
            }
        }
    }

    private fun activeSemesters(records: List<GradeRecord> = _state.value.records): Set<String> =
        (cachedSemesters + records.map { it.semester })
            .filter { it.isNotBlank() && it != GradeRepositoryPort.ALL_SEMESTERS }
            .toSet()

    private fun updateVisibleTerms() {
        val activeSet = activeSemesters()
        val visible = if (activeSet.isEmpty()) {
            emptyList()
        } else {
            val fromSchool = schoolTerms.filter { it.value.isNotBlank() && it.value in activeSet }
            val covered = fromSchool.map { it.value }.toSet()
            val remaining = activeSet
                .filter { it !in covered }
                .sortedDescending()
                .map { GradeTerm(value = it, label = it) }
            fromSchool + remaining
        }
        _state.update { it.copy(terms = visible) }
    }

    private fun loadTerms(connection: SchoolConnection) {
        termsJob?.cancel()
        termsJob = viewModelScope.launch {
            val accountKey = currentAccountKey
            cachedSemesters = runCatching {
                repository.observeSemesters(accountKey).first()
            }.getOrDefault(emptyList())
                .filter { it.isNotBlank() && it != GradeRepositoryPort.ALL_SEMESTERS }
                .distinct()

            // 默认始终展示全部成绩（range = ""），不自动切到单个或无成绩学期。
            if (!rangeTouched) {
                applyDefaultRange("")
            } else {
                _state.update { it.copy(initialized = true, loadingTerms = false) }
            }
            updateVisibleTerms()

            val allSnapshot = runCatching {
                repository.observeSnapshot(accountKey, GradeRepositoryPort.ALL_SEMESTERS).first()
            }.getOrNull()
            val allRecords = runCatching {
                repository.observeRecords(accountKey, GradeRepositoryPort.ALL_SEMESTERS).first()
            }.getOrDefault(emptyList())

            if (connection.status != SchoolConnectionStatus.ACTIVE) {
                _state.update { it.copy(loadingTerms = false) }
                return@launch
            }

            if (allSnapshot == null && allRecords.isEmpty()) {
                startQuery("")
            }

            val result: Result<List<GradeTerm>> = try {
                query.listTerms(connection.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
            result.onSuccess { terms ->
                if (_state.value.accountKey != accountKey) return@onSuccess
                schoolTerms = terms.filter { it.value.isNotBlank() }
                _state.update { it.copy(loadingTerms = false) }
                updateVisibleTerms()
            }.onFailure {
                if (_state.value.accountKey == accountKey) {
                    _state.update { state ->
                        state.copy(
                            loadingTerms = false,
                            message = if (state.needsLogin) {
                                ""
                            } else if (state.terms.isEmpty()) {
                                "学期列表暂不可用，已显示本机缓存"
                            } else {
                                state.message
                            },
                        )
                    }
                }
            }
        }
    }

    private fun applyDefaultRange(value: String) {
        rangeInitialized = true
        _state.update {
            resetRange(it, value).copy(
                confirmedRange = value,
                draftRange = value,
                initialized = true,
                rangeTouched = false,
            )
        }
        observeCache(value)
    }

    private fun observeCache(range: String, reapplyAttributes: Boolean = false) {
        cacheJob?.cancel()
        val accountKey = currentAccountKey
        if (accountKey.isEmpty()) return
        val partition = partitionOf(range)
        val interval = GradeTermInterval.parse(range)
        cacheJob = viewModelScope.launch {
            var restoreSelection = reapplyAttributes
            combine(
                repository.observeSnapshot(accountKey, partition),
                repository.observeRecords(accountKey, partition),
                repository.observeUnreadCount(accountKey),
            ) { snapshot, records, unread ->
                val visible = if (interval == null) records else records.filter { interval.contains(it.semester) }
                CacheView(snapshot = snapshot, records = visible,
                    unread = if (interval == null) unread else visible.count { it.isNew || it.isModified })
            }.collect { view ->
                _state.update { state ->
                    if (state.accountKey != accountKey ||
                        state.confirmedRange != range
                    ) {
                        state
                    } else {
                        val replaced = state.snapshot != view.snapshot ||
                            state.records.map { it.id }.toSet() != view.records.map { it.id }.toSet()
                        val restore = state.selectionMode &&
                            (restoreSelection || (replaced && state.selectionAttributes.isNotEmpty()))
                        state.copy(
                            selectionMode = state.selectionMode && (restore || !replaced || view.records.isNotEmpty()),
                            selectionRevision = if (replaced) ++selectionGeneration else state.selectionRevision,
                            selectedRecordIds = when {
                                restore -> view.records.filter { it.id > 0 && it.attribute.trim() in state.selectionAttributes }
                                    .map { it.id }.toSet()
                                replaced -> emptySet()
                                else -> state.selectedRecordIds
                            },
                            selectionAttributes = if (replaced && !restore) emptySet() else state.selectionAttributes,
                            selectionNotice = if (restore) "" else if (replaced && state.selectedRecordIds.isNotEmpty()) {
                                "已更新成绩，请重新选择"
                            } else state.selectionNotice,
                            snapshot = view.snapshot,
                            records = view.records,
                            unreadCount = view.unread,
                        )
                    }
                }
                restoreSelection = false
                updateVisibleTerms()
            }
        }
    }

    private fun cancelQuery() {
        queryGeneration++
        queryJob?.cancel()
        queryJob = null
        _state.update { it.copy(querying = false) }
    }

    private fun startQuery(range: String, showSuccessNotice: Boolean = false) {
        cancelQuery()
        val generation = queryGeneration
        val accountKey = currentAccountKey
        val connectionId = currentConnectionId
        fun isCurrent() = generation == queryGeneration && accountKey == currentAccountKey &&
            connectionId == currentConnectionId && range == _state.value.confirmedRange
        _state.update { it.copy(querying = true, error = "", message = "") }
        queryJob = viewModelScope.launch {
            try {
                val task = refreshCoordinator.refresh(RefreshResource.GRADES, partitionOf(range))
                if (!isCurrent()) return@launch
                when (task.outcome) {
                    RefreshOutcome.SUCCESS -> _state.update {
                        it.copy(error = "", needsLogin = false,
                            message = if (showSuccessNotice) "刷新成功" else it.message)
                    }
                    RefreshOutcome.FAILED -> {
                        val status = auth.getConnection(connectionId)?.status
                        if (!isCurrent()) return@launch
                        val needsLogin = task.sessionExpired || status == SchoolConnectionStatus.EXPIRED
                        val failure = task.message.ifBlank { "查询失败，本机缓存已保留" }
                        _state.update {
                            it.copy(
                                loggedIn = it.loggedIn && !needsLogin,
                                needsLogin = needsLogin,
                                error = if (needsLogin) "" else failure,
                                message = if (showSuccessNotice && !needsLogin) failure else "",
                            )
                        }
                    }
                    else -> _state.update { it.copy(
                        message = if (showSuccessNotice) task.message.ifBlank { "刷新已取消，请重试" } else "") }
                }
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (isCurrent() && showSuccessNotice) {
                    _state.update { it.copy(message = "刷新已取消，请重试") }
                }
            } catch (failure: Exception) {
                if (isCurrent()) {
                    _state.update {
                        it.copy(error = "查询失败，本机缓存已保留",
                            message = if (showSuccessNotice) "查询失败，本机缓存已保留" else "")
                    }
                }
            } finally {
                if (generation == queryGeneration) _state.update { it.copy(querying = false) }
            }
        }
    }

    private data class CacheView(
        val snapshot: GradeSnapshot?,
        val records: List<GradeRecord>,
        val unread: Int,
    )

    private companion object {
        fun partitionOf(range: String): String =
            if (range.isEmpty() || GradeTermInterval.parse(range) != null) GradeRepositoryPort.ALL_SEMESTERS else range
    }
}
