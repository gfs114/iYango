package com.gfs.iyango.feature.main.classroom

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.domain.ActiveSlotResolution
import com.gfs.iyango.core.domain.AppSettingsPort
import com.gfs.iyango.core.domain.ClassroomQuickFilter
import com.gfs.iyango.core.domain.ClassroomQuickFilterEngine
import com.gfs.iyango.core.domain.ClassroomRepositoryPort
import com.gfs.iyango.core.domain.ClassroomRoomProjection
import com.gfs.iyango.core.domain.OrderedBuilding
import com.gfs.iyango.core.domain.SchoolAuthPort
import com.gfs.iyango.core.model.AppPreferences
import com.gfs.iyango.core.model.CalendarSettings
import com.gfs.iyango.core.model.ClassroomBuilding
import com.gfs.iyango.core.model.ClassroomCell
import com.gfs.iyango.core.model.ClassroomCellState
import com.gfs.iyango.core.model.ClassroomCourseInfo
import com.gfs.iyango.core.model.ClassroomDetail
import com.gfs.iyango.core.model.ClassroomDetailRequest
import com.gfs.iyango.core.model.ClassroomMetadata
import com.gfs.iyango.core.model.ClassroomOccupancyKind
import com.gfs.iyango.core.model.ClassroomOption
import com.gfs.iyango.core.model.ClassroomPeriodGroup
import com.gfs.iyango.core.model.ClassroomQuery
import com.gfs.iyango.core.model.ClassroomResult
import com.gfs.iyango.core.model.ClassroomRoom
import com.gfs.iyango.core.model.DisplaySettingsBundle
import com.gfs.iyango.core.model.PinnedClassroomBuilding
import com.gfs.iyango.core.model.ReminderSettings
import com.gfs.iyango.core.model.ScheduleDisplaySettings
import com.gfs.iyango.core.model.SchoolConnection
import com.gfs.iyango.core.model.SchoolConnectionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 详情弹层的标题信息；与实际请求键分开保存，重试时仍显示原选中时段。 */
data class ClassroomDetailTarget(
    val roomName: String = "",
    val sectionLabel: String = "",
    val dayOfWeek: Int = 1,
    val state: ClassroomCellState = ClassroomCellState.UNKNOWN,
)

/** 教室查询页状态（F04）。 */
data class ClassroomUiState(
    val connectionId: String = "",
    val accountKey: String = "",
    val maskedAccount: String = "",
    val loggedIn: Boolean = false,
    val needsLogin: Boolean = false,
    val loadingMetadata: Boolean = false,
    val metadata: ClassroomMetadata = ClassroomMetadata(),
    val metadataReady: Boolean = false,
    val metadataError: String = "",
    val draft: ClassroomQuery = ClassroomQuery(),
    val confirmed: ClassroomQuery = ClassroomQuery(),
    val draftDirty: Boolean = false,
    val initialized: Boolean = false,
    val queryVersion: Int = 0,
    val buildings: List<ClassroomBuilding> = emptyList(),
    val buildingsLoading: Boolean = false,
    val buildingsError: String = "",
    val querying: Boolean = false,
    val result: ClassroomResult? = null,
    val resultKey: String = "",
    val queryError: String = "",
    val refreshNotice: String = "",
    val detailTarget: ClassroomDetailTarget? = null,
    val detail: ClassroomDetail? = null,
    val detailLoading: Boolean = false,
    val detailError: String = "",
    val detailKey: String = "",
    val quickFilter: ClassroomQuickFilter = ClassroomQuickFilter(),
    val pinnedBuildings: List<PinnedClassroomBuilding> = emptyList(),
    val orderedBuildings: List<OrderedBuilding> = emptyList(),
    val filteredRooms: List<ClassroomRoomProjection> = emptyList(),
    val activeSlot: ActiveSlotResolution? = null,
    val quickFilterEmptyReason: String = "",
) {
    val hasAccount: Boolean get() = connectionId.isNotEmpty()

    val canQuery: Boolean
        get() = hasAccount && loggedIn && metadataReady && initialized && !querying

    val isCurrentBuildingPinned: Boolean
        get() = draft.campusCode.isNotEmpty() &&
            draft.buildingCode.isNotEmpty() &&
            pinnedBuildings.any { it.campusCode == draft.campusCode && it.buildingCode == draft.buildingCode }
}

private object NoopClassroomAppSettingsPort : AppSettingsPort {
    override val displaySettings = flowOf(DisplaySettingsBundle())
    override suspend fun updateDisplaySettings(bundle: DisplaySettingsBundle) = Unit
    override suspend fun updateTimetableDisplay(timetableId: Long, settings: ScheduleDisplaySettings?) = Unit
    override val reminderSettings = flowOf(ReminderSettings())
    override suspend fun updateReminderSettings(settings: ReminderSettings) = Unit
    override val calendarSettings = flowOf(CalendarSettings())
    override suspend fun updateCalendarSettings(settings: CalendarSettings) = Unit
    override val appPreferences = flowOf(AppPreferences())
    override suspend fun updateAppPreferences(preferences: AppPreferences) = Unit
    override suspend fun exportNamespace(): String = ""
    override suspend fun reset() = Unit
}

/**
 * 教室查询 ViewModel（F04）。
 *
 * - 草稿与已确认条件分离；只有 [confirm] / [refresh] 才发查询；
 * - 校区变化立即清空教学楼选择并使旧字典请求失效；
 * - 查询结果按条件指纹与请求代次绑定，迟到响应不上屏；
 * - 详情按连接、学期/周、星期、教室、原始节次组与查询版本建键，
 *   旧查询的详情响应不会覆盖新结果；失败只重试当前时段；
 * - 本地快捷筛选与常用教学楼置顶零额外网络开销。
 */
@HiltViewModel
class ClassroomViewModel @Inject constructor(
    private val auth: SchoolAuthPort,
    private val repository: ClassroomRepositoryPort,
    private val appSettings: AppSettingsPort = NoopClassroomAppSettingsPort,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    private val _state = MutableStateFlow(ClassroomUiState())
    val state: StateFlow<ClassroomUiState> = _state.asStateFlow()

    private var metadataJob: Job? = null
    private var buildingJob: Job? = null
    private var queryJob: Job? = null
    private var detailJob: Job? = null
    private var metadataGeneration = 0
    private var buildingGeneration = 0
    private var queryGeneration = 0
    private var detailGeneration = 0
    private var lastDetailRequest: ClassroomDetailRequest? = null
    private var pendingPinToQuery: PinnedClassroomBuilding? = null
    private var currentAccountKey = ""
    private var currentConnectionId = ""

    init {
        viewModelScope.launch {
            appSettings.appPreferences
                .map { it.pinnedClassroomBuildings }
                .distinctUntilChanged()
                .collect { pinned ->
                    updateStateWithProjections { it.copy(pinnedBuildings = pinned) }
                }
        }
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
                    if (connection == null) detachAccount() else bindConnection(connection)
                }
        }
    }

    fun selectTerm(value: String) = updateDraft { it.copy(termValue = value) }

    fun selectTimeMode(value: String) = updateDraft { it.copy(timeModeId = value) }

    fun selectCampus(value: String) {
        pendingPinToQuery = null
        if (_state.value.draft.campusCode == value) return
        buildingJob?.cancel()
        buildingGeneration++
        updateDraft { it.copy(campusCode = value, buildingCode = "") }
        updateStateWithProjections { it.copy(buildings = emptyList(), buildingsError = "") }
        loadBuildings(value)
    }

    fun selectBuilding(value: String) {
        val current = _state.value
        if (value.isNotEmpty() && current.buildings.none { it.code == value }) return
        updateDraft { it.copy(buildingCode = value) }
    }

    fun selectRoomType(value: String) = updateDraft { it.copy(roomType = value) }

    fun selectWeek(value: Int) {
        if (_state.value.metadata.weeks.none { option -> weekOf(option.value) == value }) return
        updateDraft { it.copy(week = value) }
    }

    fun selectDay(value: Int) {
        if (_state.value.metadata.days.none { it.value.toIntOrNull() == value }) return
        updateDraft { it.copy(dayOfWeek = value) }
    }

    fun selectSection(value: String) {
        if (value.isNotEmpty() && _state.value.metadata.sections.none { it.value == value }) return
        updateDraft { it.copy(sectionCode = value) }
    }

    /** 切换「此刻可用」：同日已加载结果零网络重投影；跨日或未查询时对齐今日并发起查询。 */
    fun toggleAvailableNow() {
        val current = _state.value
        val nextEnabled = !current.quickFilter.availableNowOnly
        if (!nextEnabled) {
            updateStateWithProjections {
                it.copy(quickFilter = it.quickFilter.copy(availableNowOnly = false))
            }
            return
        }
        val today = LocalDate.now(clock).dayOfWeek.value
        val hasTodayDay = current.metadata.days.any { it.value.toIntOrNull() == today }
        val loadedToday = current.result != null &&
            current.confirmed.dayOfWeek == today &&
            current.confirmed.sectionCode.isEmpty() &&
            !current.draftDirty
        if (loadedToday) {
            updateStateWithProjections {
                it.copy(
                    quickFilter = it.quickFilter.copy(availableNowOnly = true),
                    queryError = "",
                )
            }
            return
        }
        if (!hasTodayDay) {
            _state.update { it.copy(queryError = "今日星期不在当前可选范围") }
            return
        }
        updateStateWithProjections { state ->
            val nextDraft = state.draft.copy(dayOfWeek = today, sectionCode = "")
            state.copy(
                draft = nextDraft,
                draftDirty = nextDraft.fingerprint != state.confirmed.fingerprint,
                quickFilter = state.quickFilter.copy(availableNowOnly = true),
                queryError = "",
            )
        }
        if (current.hasAccount && current.loggedIn && current.metadataReady && current.initialized) {
            confirm()
        }
    }

    /** 切换「连续空 2 节以上」：纯本地零网络重投影。 */
    fun toggleConsecutiveTwoPlus() {
        updateStateWithProjections { state ->
            val nextMin = if (state.quickFilter.minConsecutiveFreeGroups >= 2) 1 else 2
            state.copy(quickFilter = state.quickFilter.copy(minConsecutiveFreeGroups = nextMin))
        }
    }

    /** 置顶或取消置顶当前选中的教学楼。 */
    fun togglePinCurrentBuilding() {
        val current = _state.value
        val campusCode = current.draft.campusCode
        val buildingCode = current.draft.buildingCode
        if (campusCode.isEmpty() || buildingCode.isEmpty()) return
        val campusName = current.metadata.campuses
            .firstOrNull { it.value == campusCode }
            ?.label?.ifBlank { campusCode } ?: campusCode
        val buildingName = current.buildings
            .firstOrNull { it.code == buildingCode }
            ?.name?.ifBlank { buildingCode } ?: buildingCode
        val item = PinnedClassroomBuilding(
            campusCode = campusCode,
            campusName = campusName,
            buildingCode = buildingCode,
            buildingName = buildingName,
        )
        viewModelScope.launch {
            var overLimit = false
            appSettings.transformAppPreferences { prefs ->
                val exists = prefs.pinnedClassroomBuildings.any { it.key == item.key }
                if (exists) {
                    prefs.copy(
                        pinnedClassroomBuildings = prefs.pinnedClassroomBuildings.filterNot { it.key == item.key },
                    )
                } else if (prefs.pinnedClassroomBuildings.size >= AppPreferences.MAX_PINNED_BUILDINGS) {
                    overLimit = true
                    prefs
                } else {
                    prefs.copy(
                        pinnedClassroomBuildings = listOf(item) + prefs.pinnedClassroomBuildings,
                    )
                }
            }
            if (overLimit) {
                _state.update {
                    it.copy(refreshNotice = "最多置顶 ${AppPreferences.MAX_PINNED_BUILDINGS} 个常用教学楼")
                }
            }
        }
    }

    /** 直接移除某个已置顶教学楼。 */
    fun removePinnedBuilding(pin: PinnedClassroomBuilding) {
        viewModelScope.launch {
            appSettings.transformAppPreferences { prefs ->
                prefs.copy(
                    pinnedClassroomBuildings = prefs.pinnedClassroomBuildings.filterNot { it.key == pin.key },
                )
            }
        }
    }

    /** 点击常用教学楼一键切定位到目标校区/楼栋并查询。 */
    fun selectPinnedBuilding(pin: PinnedClassroomBuilding) {
        val current = _state.value
        if (!current.metadataReady) return
        if (current.metadata.campuses.none { it.value == pin.campusCode }) {
            _state.update { it.copy(queryError = "该常用教学楼所属校区已失效，请移除") }
            return
        }
        if (current.draft.campusCode == pin.campusCode && !current.buildingsLoading && current.buildings.isNotEmpty()) {
            if (current.buildings.none { it.code == pin.buildingCode }) {
                _state.update { it.copy(queryError = "教学楼「${pin.buildingName}」已失效，请移除") }
                return
            }
            pendingPinToQuery = null
            updateDraft { it.copy(buildingCode = pin.buildingCode) }
            confirm()
            return
        }
        pendingPinToQuery = pin
        buildingJob?.cancel()
        buildingGeneration++
        updateDraft { it.copy(campusCode = pin.campusCode, buildingCode = "") }
        updateStateWithProjections { it.copy(buildings = emptyList(), buildingsError = "", queryError = "") }
        loadBuildings(pin.campusCode)
    }

    /** 提交草稿；条件不变也重新走网络（等价显式刷新）。 */
    fun confirm() {
        val current = _state.value
        if (!current.hasAccount) return
        if (!current.loggedIn) {
            _state.update { it.copy(needsLogin = true, queryError = "登录已过期，请重新登录后再查询") }
            return
        }
        val problem = queryProblem(current)
        if (problem != null) {
            _state.update { it.copy(queryError = problem) }
            return
        }
        if (current.draft.fingerprint != current.confirmed.fingerprint) {
            clearDetail()
            updateStateWithProjections {
                it.copy(
                    confirmed = it.draft,
                    draftDirty = false,
                    queryError = "",
                    result = null,
                    resultKey = "",
                    initialized = true,
                )
            }
        }
        startQuery(_state.value.confirmed)
    }

    /** 下拉刷新/重新登录后重查已确认条件。 */
    fun refresh() {
        val current = _state.value
        if (!current.hasAccount || !current.initialized || current.querying) return
        if (!current.loggedIn) {
            _state.update { it.copy(needsLogin = true, queryError = "登录已过期，请重新登录后再查询",
                refreshNotice = "登录已过期，请重新登录后再查询") }
            return
        }
        startQuery(current.confirmed)
    }

    fun reloadMetadata() {
        if (currentConnectionId.isEmpty()) return
        if (!_state.value.loggedIn) {
            _state.update { it.copy(needsLogin = true, metadataError = "登录已过期，请重新登录后再试") }
            return
        }
        loadMetadata()
    }

    /** 教学楼列表加载失败后重试当前校区。 */
    fun reloadBuildings() {
        val campus = _state.value.draft.campusCode
        if (campus.isEmpty()) return
        loadBuildings(campus)
    }

    fun dismissQueryError() {
        _state.update { it.copy(queryError = "") }
    }

    fun consumeRefreshNotice() {
        _state.update { it.copy(refreshNotice = "") }
    }

    fun openDetail(room: ClassroomRoom, groupIndex: Int) {
        val current = _state.value
        val result = current.result ?: return
        val cell = room.cells.firstOrNull { it.groupIndex == groupIndex }
        val group = result.periodGroups.getOrNull(groupIndex) ?: return
        if (cell == null ||
            (cell.state != ClassroomCellState.COURSE && cell.state != ClassroomCellState.BORROWED)
        ) {
            return
        }
        if (!current.loggedIn) {
            _state.update { it.copy(needsLogin = true, queryError = "登录已过期，请重新登录后再查看详情") }
            return
        }
        val target = ClassroomDetailTarget(
            roomName = room.name,
            sectionLabel = group.label,
            dayOfWeek = cell.dayOfWeek,
            state = cell.state,
        )
        if (room.identifier.isBlank()) {
            lastDetailRequest = null
            _state.update {
                it.copy(
                    detailTarget = target,
                    detail = null,
                    detailLoading = false,
                    detailError = "教室标识缺失，暂不能查看详情",
                    detailKey = "",
                )
            }
            return
        }
        val query = result.query
        val request = ClassroomDetailRequest(
            termValue = query.termValue,
            timeModeId = query.timeModeId,
            week = query.week,
            dayOfWeek = cell.dayOfWeek,
            campusCode = query.campusCode,
            buildingCode = query.buildingCode,
            roomId = room.identifier,
            roomName = room.name,
            rawSectionCode = group.rawCode,
            sectionLabel = group.label,
            sectionStartTime = group.startTime,
            sectionEndTime = group.endTime,
            sourceStartTime = group.sourceStartTime,
            sourceEndTime = group.sourceEndTime,
            allSectionCodes = query.allSectionCodes,
            queryVersion = current.queryVersion,
        )
        startDetail(request, target)
    }

    fun retryDetail() {
        val current = _state.value
        val request = lastDetailRequest ?: return
        val target = current.detailTarget ?: return
        if (!current.loggedIn) {
            _state.update { it.copy(needsLogin = true) }
            return
        }
        if (request.queryVersion != current.queryVersion) {
            clearDetail()
            return
        }
        startDetail(request, target)
    }

    fun closeDetail() = clearDetail()

    private fun detachAccount() {
        metadataJob?.cancel()
        buildingJob?.cancel()
        queryJob?.cancel()
        detailJob?.cancel()
        metadataGeneration++
        buildingGeneration++
        queryGeneration++
        detailGeneration++
        lastDetailRequest = null
        pendingPinToQuery = null
        currentAccountKey = ""
        currentConnectionId = ""
        updateStateWithProjections { state -> ClassroomUiState(pinnedBuildings = state.pinnedBuildings) }
    }

    private fun bindConnection(connection: SchoolConnection) {
        val accountKey = connection.accountSubject.ifEmpty { connection.id }
        val changed = accountKey != currentAccountKey || connection.id != currentConnectionId
        currentAccountKey = accountKey
        currentConnectionId = connection.id
        val loggedIn = connection.status == SchoolConnectionStatus.ACTIVE

        if (changed) {
            metadataJob?.cancel()
            buildingJob?.cancel()
            queryJob?.cancel()
            detailJob?.cancel()
            metadataGeneration++
            buildingGeneration++
            queryGeneration++
            detailGeneration++
            lastDetailRequest = null
            pendingPinToQuery = null
            updateStateWithProjections { prev ->
                ClassroomUiState(
                    connectionId = connection.id,
                    accountKey = accountKey,
                    maskedAccount = connection.maskedAccount,
                    loggedIn = loggedIn,
                    needsLogin = !loggedIn,
                    loadingMetadata = loggedIn,
                    pinnedBuildings = prev.pinnedBuildings,
                )
            }
            if (loggedIn) loadMetadata()
            return
        }

        _state.update {
            it.copy(
                maskedAccount = connection.maskedAccount,
                loggedIn = loggedIn,
                needsLogin = if (loggedIn) false else it.needsLogin,
            )
        }
        if (loggedIn && !_state.value.metadataReady && !_state.value.loadingMetadata) {
            loadMetadata()
        }
    }

    private fun loadMetadata() {
        val connectionId = currentConnectionId
        val accountKey = currentAccountKey
        if (connectionId.isEmpty()) return
        metadataJob?.cancel()
        val generation = ++metadataGeneration
        _state.update { it.copy(loadingMetadata = true, metadataError = "") }
        metadataJob = viewModelScope.launch {
            val result: Result<ClassroomMetadata> = try {
                schoolRequest { repository.metadata(connectionId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
            if (!isMetadataCurrent(generation, accountKey)) return@launch
            result.onSuccess { metadata ->
                if (!metadata.ready) {
                    _state.update {
                        it.copy(
                            loadingMetadata = false,
                            metadata = metadata,
                            metadataReady = false,
                            metadataError = "学校未提供完整的教室查询条件，请稍后重试",
                        )
                    }
                    return@onSuccess
                }
                val defaults = defaultQuery(metadata)
                val problem = defaultProblem(defaults)
                _state.update {
                    it.copy(
                        loadingMetadata = false,
                        metadata = metadata,
                        metadataReady = true,
                        metadataError = "",
                        draft = defaults,
                        confirmed = defaults,
                        draftDirty = false,
                        initialized = true,
                        queryError = problem.orEmpty(),
                    )
                }
                if (defaults.campusCode.isNotEmpty()) loadBuildings(defaults.campusCode)
                if (problem == null) startQuery(defaults)
            }.onFailure { failure ->
                val expired = connectionStatus(connectionId) == SchoolConnectionStatus.EXPIRED
                _state.update {
                    it.copy(
                        loadingMetadata = false,
                        metadataError = failure.message ?: "教室查询条件加载失败，请稍后重试",
                        needsLogin = it.needsLogin || expired,
                    )
                }
            }
        }
    }

    private fun loadBuildings(campus: String) {
        buildingJob?.cancel()
        val generation = ++buildingGeneration
        if (campus.isEmpty()) {
            updateStateWithProjections { it.copy(buildings = emptyList(), buildingsLoading = false, buildingsError = "") }
            return
        }
        val connectionId = currentConnectionId
        _state.update { it.copy(buildings = emptyList(), buildingsLoading = true, buildingsError = "") }
        buildingJob = viewModelScope.launch {
            val result: Result<List<ClassroomBuilding>> = try {
                schoolRequest { repository.buildings(connectionId, campus) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
            if (generation != buildingGeneration || currentConnectionId != connectionId ||
                _state.value.draft.campusCode != campus
            ) {
                return@launch
            }
            result.onSuccess { buildings ->
                val pin = pendingPinToQuery?.takeIf { it.campusCode == campus }
                pendingPinToQuery = null
                val pinValid = pin != null && buildings.any { it.code == pin.buildingCode }
                updateStateWithProjections { state ->
                    val nextDraft = if (pinValid) {
                        state.draft.copy(buildingCode = pin.buildingCode)
                    } else {
                        state.draft
                    }
                    state.copy(
                        buildings = buildings,
                        buildingsLoading = false,
                        draft = nextDraft,
                        draftDirty = nextDraft.fingerprint != state.confirmed.fingerprint,
                        queryError = if (pin != null && !pinValid) {
                            "教学楼「${pin.buildingName}」已失效，请移除"
                        } else {
                            state.queryError
                        },
                    )
                }
                if (pinValid) confirm()
            }.onFailure { failure ->
                if (pendingPinToQuery?.campusCode == campus) pendingPinToQuery = null
                updateStateWithProjections {
                    it.copy(
                        buildings = emptyList(),
                        buildingsLoading = false,
                        buildingsError = failure.message ?: "教学楼列表加载失败，请重试或选择全部教学楼",
                    )
                }
            }
        }
    }

    private fun startQuery(query: ClassroomQuery) {
        queryJob?.cancel()
        detailJob?.cancel()
        detailGeneration++
        lastDetailRequest = null
        val connectionId = currentConnectionId
        val accountKey = currentAccountKey
        val generation = ++queryGeneration
        val version = _state.value.queryVersion + 1
        _state.update {
            it.copy(
                querying = true,
                queryError = "",
                refreshNotice = "",
                queryVersion = version,
                detailTarget = null,
                detail = null,
                detailLoading = false,
                detailError = "",
                detailKey = "",
            )
        }
        queryJob = viewModelScope.launch {
            val result: Result<ClassroomResult> = try {
                schoolRequest { repository.query(connectionId, query) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
            if (generation != queryGeneration || currentConnectionId != connectionId ||
                currentAccountKey != accountKey
            ) {
                return@launch
            }
            result.onSuccess { loaded ->
                updateStateWithProjections {
                    it.copy(
                        querying = false,
                        queryError = "",
                        refreshNotice = "刷新成功",
                        result = loaded,
                        resultKey = queryKey(connectionId, query, version),
                    )
                }
            }.onFailure { failure ->
                val expired = connectionStatus(connectionId) == SchoolConnectionStatus.EXPIRED
                val sameConditions = _state.value.confirmed.fingerprint == query.fingerprint
                updateStateWithProjections {
                    it.copy(
                        querying = false,
                        queryError = failure.message ?: "教室查询暂不可用，请稍后重试",
                        refreshNotice = failure.message ?: "教室查询暂不可用，请稍后重试",
                        needsLogin = it.needsLogin || expired,
                        result = if (sameConditions) it.result else null,
                        resultKey = if (sameConditions) it.resultKey else "",
                    )
                }
            }
        }
    }

    private fun startDetail(request: ClassroomDetailRequest, target: ClassroomDetailTarget) {
        detailJob?.cancel()
        val connectionId = currentConnectionId
        val generation = ++detailGeneration
        val key = request.key(connectionId)
        lastDetailRequest = request
        _state.update {
            it.copy(
                detailTarget = target,
                detail = null,
                detailLoading = true,
                detailError = "",
                detailKey = key,
            )
        }
        detailJob = viewModelScope.launch {
            val result: Result<ClassroomDetail> = try {
                schoolRequest { repository.detail(connectionId, request) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
            if (generation != detailGeneration || currentConnectionId != connectionId ||
                _state.value.detailKey != key
            ) {
                return@launch
            }
            result.onSuccess { detail ->
                _state.update { it.copy(detail = detail, detailLoading = false) }
            }.onFailure { failure ->
                val expired = connectionStatus(connectionId) == SchoolConnectionStatus.EXPIRED
                _state.update {
                    it.copy(
                        detailLoading = false,
                        detailError = failure.message ?: "课程详情加载失败，请重试",
                        needsLogin = it.needsLogin || expired,
                    )
                }
            }
        }
    }

    private fun clearDetail() {
        detailJob?.cancel()
        detailGeneration++
        lastDetailRequest = null
        _state.update {
            it.copy(
                detailTarget = null,
                detail = null,
                detailLoading = false,
                detailError = "",
                detailKey = "",
            )
        }
    }

    private fun updateDraft(transform: (ClassroomQuery) -> ClassroomQuery) {
        updateStateWithProjections {
            val draft = transform(it.draft)
            it.copy(draft = draft, draftDirty = draft.fingerprint != it.confirmed.fingerprint)
        }
    }

    private fun updateStateWithProjections(transform: (ClassroomUiState) -> ClassroomUiState) {
        val nowTime = LocalTime.now(clock)
        _state.update { previous ->
            val updated = transform(previous)
            val orderedBuildings = ClassroomQuickFilterEngine.orderBuildingsWithPins(
                campusCode = updated.draft.campusCode,
                buildings = updated.buildings,
                pinned = updated.pinnedBuildings,
            )
            val filterOutput = updated.result?.let {
                ClassroomQuickFilterEngine.project(it, updated.quickFilter, nowTime)
            }
            updated.copy(
                orderedBuildings = orderedBuildings,
                filteredRooms = filterOutput?.rooms.orEmpty(),
                activeSlot = filterOutput?.activeSlot,
                quickFilterEmptyReason = filterOutput?.emptyReason.orEmpty(),
            )
        }
    }

    private fun queryProblem(current: ClassroomUiState): String? {
        val query = current.draft
        if (query.termValue.isBlank() ||
            current.metadata.terms.none { it.value == query.termValue }
        ) {
            return "请选择有效学期"
        }
        if (query.timeModeId.isBlank() ||
            current.metadata.timeModes.none { it.value == query.timeModeId }
        ) {
            return "请选择有效时间模式"
        }
        if (query.week !in 1..60) return "请选择周次"
        if (query.dayOfWeek !in 1..7) return "请选择星期"
        if (query.allSectionCodes.isEmpty()) return "学校未提供节次选项，请稍后重试"
        if (query.campusCode.isNotEmpty() &&
            current.metadata.campuses.none { it.value == query.campusCode }
        ) {
            return "校区选项已失效，请重新选择后查询"
        }
        if (query.roomType.isNotEmpty() &&
            current.metadata.roomTypes.none { it.value == query.roomType }
        ) {
            return "教室类型选项已失效，请重新选择后查询"
        }
        if (query.sectionCode.isNotEmpty() &&
            current.metadata.sections.none { it.value == query.sectionCode }
        ) {
            return "节次选项已失效，请重新选择后查询"
        }
        if (query.buildingCode.isNotEmpty()) {
            if (current.buildingsLoading) return "教学楼列表仍在加载，请稍后查询"
            if (current.buildings.none { it.code == query.buildingCode }) {
                return "教学楼选项已失效或尚未加载，请重新选择后查询"
            }
        }
        return null
    }

    private fun defaultProblem(query: ClassroomQuery): String? {
        if (query.termValue.isBlank()) return "请选择学期"
        if (query.timeModeId.isBlank()) return "请选择时间模式"
        if (query.week !in 1..60) return "学校未提供默认周次，请选择周次后查询"
        if (query.dayOfWeek !in 1..7) return "请选择星期后查询"
        if (query.allSectionCodes.isEmpty()) return "学校未提供节次选项，请稍后重试"
        return null
    }

    private fun defaultQuery(metadata: ClassroomMetadata): ClassroomQuery {
        val term = pick(metadata.terms)
        val timeMode = pick(metadata.timeModes)
        val campus = pick(metadata.campuses)
        val roomType = pick(metadata.roomTypes)
        val week = weekDefault(metadata)
        val day = dayDefault(metadata)
        return ClassroomQuery(
            termValue = term,
            timeModeId = timeMode,
            campusCode = campus,
            buildingCode = "",
            roomType = roomType,
            week = week,
            dayOfWeek = day,
            sectionCode = "",
            allSectionCodes = metadata.sections.map { it.value },
        )
    }

    private fun pick(options: List<com.gfs.iyango.core.model.ClassroomOption>): String =
        options.firstOrNull { it.selected }?.value ?: options.firstOrNull()?.value.orEmpty()

    private fun weekDefault(metadata: ClassroomMetadata): Int {
        val selected = metadata.weeks.firstOrNull { it.selected }?.value?.let(::weekOf) ?: 0
        if (selected in 1..60) return selected
        return 0
    }

    private fun dayDefault(metadata: ClassroomMetadata): Int {
        val selected = metadata.days.filter { it.selected }
        if (selected.size == 1) return selected[0].value.toIntOrNull()?.takeIf { it in 1..7 } ?: 0
        val today = LocalDate.now(clock).dayOfWeek.value
        return if (metadata.days.any { it.value.toIntOrNull() == today }) today else 0
    }

    private fun weekOf(value: String): Int {
        val parsed = value.toIntOrNull() ?: return 0
        return if (parsed in 1..60) parsed else 0
    }

    /** Bound the UI wait even when a blocking school transport is slow to cancel. */
    private suspend fun <T> schoolRequest(block: suspend () -> T): T {
        val request = viewModelScope.async { runCatching { block() } }
        return try {
            withTimeout(30_000) { request.await().getOrThrow() }
        } catch (_: TimeoutCancellationException) {
            throw IllegalStateException("请求超时，请检查网络后重试")
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw IllegalStateException("请求已取消，请重试")
        } finally {
            request.cancel()
        }
    }

    private suspend fun connectionStatus(connectionId: String): SchoolConnectionStatus? = try {
        auth.getConnection(connectionId)?.status
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        null
    }

    private fun isMetadataCurrent(generation: Int, accountKey: String): Boolean =
        generation == metadataGeneration && currentAccountKey == accountKey &&
            currentConnectionId.isNotEmpty()

    private fun queryKey(connectionId: String, query: ClassroomQuery, version: Int): String =
        "$connectionId|${query.fingerprint}|$version"
}
