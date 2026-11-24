package com.gfs.iyango.feature.main.school

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.domain.CalendarCoordinatorPort
import com.gfs.iyango.core.domain.ReminderCoordinatorPort
import com.gfs.iyango.core.domain.SchoolAuthPort
import com.gfs.iyango.core.domain.SchoolSyncCommitResult
import com.gfs.iyango.core.domain.SchoolSyncCommitSummary
import com.gfs.iyango.core.domain.SchoolSyncGroup
import com.gfs.iyango.core.domain.SchoolSyncPrepareResult
import com.gfs.iyango.core.domain.SchoolSyncPreview
import com.gfs.iyango.core.domain.SchoolTimetablePort
import com.gfs.iyango.core.domain.SchoolTimetableSyncPort
import com.gfs.iyango.core.model.SchoolConnection
import com.gfs.iyango.core.model.SchoolTermOption
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 教务课表同步页 ViewModel（F02 / E02）。
 *
 * 操作带请求序号：切换学期、重新获取或提交时，旧响应不得回写界面。
 * 提交成功后触发提醒与日历重算；重算失败不回滚已写入的课表，只保留提示。
 */
@HiltViewModel
class SchoolTimetableSyncViewModel @Inject constructor(
    auth: SchoolAuthPort,
    private val timetable: SchoolTimetablePort,
    private val sync: SchoolTimetableSyncPort,
    private val reminders: ReminderCoordinatorPort,
    private val calendars: CalendarCoordinatorPort,
) : ViewModel() {

    val activeConnection: StateFlow<SchoolConnection?> = auth.observeActiveConnection()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val terms = MutableStateFlow<List<SchoolTermOption>>(emptyList())
    val selectedTerm = MutableStateFlow("")
    val preview = MutableStateFlow<SchoolSyncPreview?>(null)
    val selectedRowKeys = MutableStateFlow<Set<String>>(emptySet())
    val startDate = MutableStateFlow("")
    val totalWeeks = MutableStateFlow("20")

    val loadingTerms = MutableStateFlow(false)
    val loadingPreview = MutableStateFlow(false)
    val committing = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)
    val summary = MutableStateFlow<SchoolSyncCommitSummary?>(null)

    private var operationSeq = 0
    private var connectedId = ""

    fun onConnectionAvailable(connectionId: String?) {
        if (connectionId.isNullOrEmpty()) {
            reset()
            return
        }
        if (connectedId == connectionId) return
        connectedId = connectionId
        resetForConnection()
        loadTerms(connectionId)
    }

    private fun reset() {
        connectedId = ""
        operationSeq++
        terms.value = emptyList()
        selectedTerm.value = ""
        preview.value = null
        selectedRowKeys.value = emptySet()
        summary.value = null
        message.value = null
        error.value = null
        loadingTerms.value = false
        loadingPreview.value = false
        committing.value = false
    }

    private fun resetForConnection() {
        operationSeq++
        terms.value = emptyList()
        selectedTerm.value = ""
        preview.value = null
        selectedRowKeys.value = emptySet()
        summary.value = null
        message.value = null
        error.value = null
    }

    private fun loadTerms(connectionId: String) {
        val ticket = ++operationSeq
        loadingTerms.value = true
        terms.value = emptyList()
        selectedTerm.value = ""
        preview.value = null
        summary.value = null
        error.value = null
        viewModelScope.launch {
            val result = timetable.listTerms(connectionId)
            if (ticket != operationSeq) return@launch
            loadingTerms.value = false
            result.onSuccess { options ->
                terms.value = options
                if (options.isNotEmpty()) selectedTerm.value = options.first().value
            }.onFailure { failure ->
                error.value = failure.message ?: "学期列表读取失败，请稍后重试"
            }
        }
    }

    fun selectTerm(term: String) {
        if (selectedTerm.value == term) return
        selectedTerm.value = term
        preview.value = null
        selectedRowKeys.value = emptySet()
        summary.value = null
        error.value = null
        message.value = null
    }

    fun refreshTerms() {
        if (connectedId.isNotEmpty()) loadTerms(connectedId)
    }

    fun fetchPreview() {
        val connectionId = activeConnection.value?.id ?: connectedId
        if (connectionId.isEmpty()) {
            error.value = "请先在教务账号页登录"
            return
        }
        val term = selectedTerm.value
        if (term.isEmpty()) {
            error.value = "请选择学期"
            return
        }
        val ticket = ++operationSeq
        loadingPreview.value = true
        preview.value = null
        summary.value = null
        error.value = null
        message.value = null
        viewModelScope.launch {
            val result = sync.prepare(connectionId, term)
            if (ticket != operationSeq) return@launch
            loadingPreview.value = false
            when (result) {
                is SchoolSyncPrepareResult.Ready -> {
                    val ready = result.preview
                    preview.value = ready
                    selectedRowKeys.value = ready.courses.mapTo(HashSet()) { it.rowKey }
                    startDate.value = ready.defaultStartDate
                    totalWeeks.value = ready.defaultTotalWeeks.toString()
                }
                is SchoolSyncPrepareResult.SessionExpired -> {
                    error.value = result.message
                }
                is SchoolSyncPrepareResult.Failed -> {
                    error.value = result.message
                }
            }
        }
    }

    fun toggleArrangement(rowKey: String) {
        selectedRowKeys.update { current ->
            if (rowKey in current) current - rowKey else current + rowKey
        }
    }

    fun toggleGroup(group: SchoolSyncGroup) {
        val keys = group.arrangements.mapTo(HashSet()) { it.rowKey }
        selectedRowKeys.update { current ->
            if (current.containsAll(keys)) current - keys else current + keys
        }
    }

    fun setStartDate(value: String) {
        startDate.value = value
    }

    fun setTotalWeeks(value: String) {
        totalWeeks.value = value.filter { it.isDigit() }.take(2)
    }

    fun commit() {
        val ready = preview.value ?: return
        if (committing.value) return
        val parsedDate = runCatching { LocalDate.parse(startDate.value.trim()) }.getOrNull()
        if (parsedDate == null) {
            error.value = "开学日期格式应为 yyyy-MM-dd"
            return
        }
        val weeks = totalWeeks.value.toIntOrNull()
        if (weeks == null || weeks !in 1..30) {
            error.value = "总周数应在 1–30 之间"
            return
        }
        if (selectedRowKeys.value.isEmpty()) {
            error.value = "请至少选择一条课程安排"
            return
        }
        val ticket = ++operationSeq
        committing.value = true
        error.value = null
        message.value = null
        summary.value = null
        val chosenCount = selectedRowKeys.value.size
        viewModelScope.launch {
            val result = sync.commit(
                preview = ready,
                startDate = parsedDate.toString(),
                totalWeeks = weeks,
                selectedRowKeys = selectedRowKeys.value,
            )
            if (ticket != operationSeq) return@launch
            committing.value = false
            when (result) {
                is SchoolSyncCommitResult.Success -> {
                    preview.value = null
                    selectedRowKeys.value = emptySet()
                    summary.value = result.summary
                    val written = result.summary
                    message.value = if (written.firstBaseline) {
                        "首次同步完成：写入 $chosenCount 条安排"
                    } else {
                        "同步完成：更新 ${written.modified}，新增 ${written.added}，移除 ${written.removed}"
                    }
                    // 系统重算失败不回滚课表；下一次业务写入或手动刷新会再次对齐。
                    runCatching { reminders.refresh() }
                    runCatching { calendars.refresh() }
                }
                is SchoolSyncCommitResult.Conflict -> {
                    preview.value = null
                    selectedRowKeys.value = emptySet()
                    error.value = result.message
                }
                is SchoolSyncCommitResult.SessionExpired -> {
                    error.value = result.message
                }
                is SchoolSyncCommitResult.Failed -> {
                    error.value = result.message
                }
            }
        }
    }

    fun consumeError() {
        error.value = null
    }
}
