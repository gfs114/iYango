package com.gfs.iyango.feature.main.exam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import com.gfs.iyango.core.domain.RefreshOutcome
import com.gfs.iyango.core.domain.ExamCountdown
import com.gfs.iyango.core.domain.ExamPhase
import com.gfs.iyango.core.domain.ExamRepositoryPort
import com.gfs.iyango.core.domain.RefreshCoordinatorPort
import com.gfs.iyango.core.domain.RefreshResource
import com.gfs.iyango.core.model.Exam
import com.gfs.iyango.core.model.ExamStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDateTime
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 考试列表筛选。 */
enum class ExamFilter(val label: String) {
    UPCOMING("近期"),
    ALL("全部"),
    ENDED("已结束"),
}

/** 考试列表状态（E01）。 */
data class ExamListUiState(
    val filter: ExamFilter = ExamFilter.UPCOMING,
    val exams: List<Exam> = emptyList(),
    val now: LocalDateTime = LocalDateTime.now(),
    val message: String = "",
)

/**
 * 考试列表 ViewModel：全部考试按近期/全部/已结束分组，30 秒重算倒计时，
 * 下拉刷新走 F05 协调器（学校考试导入未接入时与本地一致）。
 */
@HiltViewModel
class ExamListViewModel @Inject constructor(
    private val repository: ExamRepositoryPort,
    private val refreshCoordinator: RefreshCoordinatorPort,
    private val clock: Clock,
) : ViewModel() {

    private val filter = MutableStateFlow(ExamFilter.UPCOMING)
    private val message = MutableStateFlow("")
    private val manualRefreshing = MutableStateFlow(false)
    private val manualRefreshTick = MutableStateFlow(0L)

    /** 下拉刷新结果顶部胶囊提示。 */
    val refreshNotice = MutableStateFlow<String?>(null)

    private val tick = combine(
        flow {
            while (true) {
                emit(Unit)
                delay(30_000)
            }
        },
        manualRefreshTick,
    ) { _, _ ->
        LocalDateTime.now(clock)
    }

    val state: StateFlow<ExamListUiState> = combine(
        repository.observeAll(),
        filter,
        message,
        tick,
    ) { all, selected, text, now ->
        ExamListUiState(
            filter = selected,
            exams = arrange(all, selected, now),
            now = now,
            message = text,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExamListUiState())

    val refreshing: StateFlow<Boolean> = manualRefreshing.asStateFlow()

    fun setFilter(value: ExamFilter) {
        filter.value = value
    }

    fun clearMessage() {
        message.value = ""
    }

    fun consumeRefreshNotice() {
        refreshNotice.value = null
    }

    fun refresh() {
        if (manualRefreshing.value) return
        manualRefreshing.value = true
        refreshNotice.value = null
        manualRefreshTick.value += 1
        viewModelScope.launch {
            try {
                val result = refreshCoordinator.refresh(RefreshResource.EXAMS)
                refreshNotice.value = result.message.ifBlank {
                    if (result.outcome == RefreshOutcome.SUCCESS) "刷新成功"
                    else "刷新未完成，本地考试已保留"
                }
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                refreshNotice.value = "刷新已取消，请重试"
            } catch (_: Exception) {
                refreshNotice.value = "刷新失败，本地考试已保留"
            } finally {
                manualRefreshing.value = false
            }
        }
    }

    fun save(exam: Exam) {
        viewModelScope.launch {
            runCatching {
                val previous = repository.observeAll().first()
                repository.save(exam)
                previous
            }.onSuccess { previous ->
                val overlap = previous.firstOrNull { it.id != exam.id && overlaps(it, exam) }
                message.value = if (overlap != null) {
                    "已保存；与「${overlap.subject}」时间重叠，请留意"
                } else {
                    "已保存「${exam.subject}」"
                }
            }.onFailure { message.value = it.message ?: "保存失败" }
        }
    }

    /** 仅作提示：同一天且时刻区间相交（任一方缺时刻则同日即提示），不自动改排。 */
    private fun overlaps(left: Exam, right: Exam): Boolean {
        if (left.status == ExamStatus.CANCELLED) return false
        if (left.date != right.date) return false
        val leftStart = left.startTimeOrNull()
        val leftEnd = left.endTimeOrNull()
        val rightStart = right.startTimeOrNull()
        val rightEnd = right.endTimeOrNull()
        if (leftStart == null || leftEnd == null || rightStart == null || rightEnd == null) {
            return true
        }
        return leftStart < rightEnd && rightStart < leftEnd
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            runCatching { repository.delete(id) }
                .onSuccess { message.value = "已删除考试" }
                .onFailure { message.value = it.message ?: "删除失败" }
        }
    }

    private fun arrange(all: List<Exam>, selected: ExamFilter, now: LocalDateTime): List<Exam> {
        val upcoming = all
            .filter { ExamCountdown.phase(it, now) == ExamPhase.UPCOMING || ExamCountdown.phase(it, now) == ExamPhase.IN_PROGRESS }
            .sortedWith(compareBy({ it.date }, { it.startTime }))
        val ended = all
            .filter { it !in upcoming }
            .sortedByDescending { it.date }
        return when (selected) {
            ExamFilter.UPCOMING -> upcoming
            ExamFilter.ALL -> upcoming + ended
            ExamFilter.ENDED -> ended
        }
    }
}
