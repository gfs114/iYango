package com.gfs.iyango.feature.main.synchistory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.domain.SchoolAuthPort
import com.gfs.iyango.core.domain.SyncHistoryPort
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.model.SyncBatch
import com.gfs.iyango.core.model.SyncChange
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 同步记录页状态（E02）。 */
data class SyncHistoryUiState(
    val connectionId: String = "",
    val hasAccount: Boolean = connectionId.isNotEmpty(),
    val batches: List<SyncBatch> = emptyList(),
    val timetableNames: Map<Long, String> = emptyMap(),
    val selectedBatch: SyncBatch? = null,
    val changes: List<SyncChange> = emptyList(),
    val error: String = "",
) {
    val unreadCount: Int get() = batches.count { !it.read }
}

/**
 * 同步变更记录 ViewModel（E02）。
 *
 * 批次按当前教务连接隔离；打开批次即标记已读，清除只删除记录，不影响
 * 学校快照基线，因此下一次同步仍能正确比较。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class SyncHistoryViewModel @Inject constructor(
    auth: SchoolAuthPort,
    private val history: SyncHistoryPort,
    timetableRepository: TimetableRepositoryPort,
) : ViewModel() {

    private val connectionId = MutableStateFlow("")
    private val selectedBatchId = MutableStateFlow(0L)
    private val error = MutableStateFlow("")

    private val batches = connectionId.flatMapLatest { id ->
        if (id.isEmpty()) flowOf(emptyList()) else history.observeBatches(id)
    }

    private val timetableNames = timetableRepository.observeAll()
        .map { timetables -> timetables.associate { it.id to it.name } }

    private val selectedBatch = combine(batches, selectedBatchId) { list, id ->
        list.firstOrNull { it.id == id }
    }

    private val changes = selectedBatchId.flatMapLatest { id ->
        if (id <= 0L) flowOf(emptyList()) else history.observeChanges(id)
    }

    val state: StateFlow<SyncHistoryUiState> = combine(
        combine(connectionId, batches, timetableNames) { id, list, names ->
            Triple(id, list, names)
        },
        combine(selectedBatch, changes, error) { batch, detailChanges, message ->
            Triple(batch, detailChanges, message)
        },
    ) { listPart, detailPart ->
        SyncHistoryUiState(
            connectionId = listPart.first,
            batches = listPart.second,
            timetableNames = listPart.third,
            selectedBatch = detailPart.first,
            changes = detailPart.second,
            error = detailPart.third,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SyncHistoryUiState(),
    )

    init {
        viewModelScope.launch {
            auth.observeActiveConnection().collect { connection ->
                connectionId.value = connection?.id.orEmpty()
            }
        }
    }

    /** 查看批次前/后值；同时消除该批次的未读标记。 */
    fun openBatch(batch: SyncBatch) {
        selectedBatchId.value = batch.id
        if (!batch.read) {
            viewModelScope.launch {
                runCatching { history.markRead(batch.id) }
                    .onFailure { error.value = "标记已读失败，请稍后重试" }
            }
        }
    }

    fun closeBatch() {
        selectedBatchId.value = 0L
    }

    fun markAllRead() {
        val id = connectionId.value
        if (id.isEmpty()) return
        viewModelScope.launch {
            runCatching { history.markAllRead(id) }
                .onFailure { error.value = "标记已读失败，请稍后重试" }
        }
    }

    /** 清除当前账号的全部同步记录；学校快照保留。 */
    fun clearHistory() {
        val id = connectionId.value
        if (id.isEmpty()) return
        selectedBatchId.value = 0L
        viewModelScope.launch {
            runCatching { history.clear(id) }
                .onFailure { error.value = "清除记录失败，请稍后重试" }
        }
    }

    fun consumeError() {
        error.value = ""
    }
}
