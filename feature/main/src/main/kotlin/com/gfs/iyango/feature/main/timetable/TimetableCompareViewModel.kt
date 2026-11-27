package com.gfs.iyango.feature.main.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.domain.CourseRules
import com.gfs.iyango.core.domain.EffectiveSchedulePort
import com.gfs.iyango.core.domain.ScheduleDataPort
import com.gfs.iyango.core.domain.TimetableComparisonCalculator
import com.gfs.iyango.core.domain.TimetableComparisonResult
import com.gfs.iyango.core.domain.TimetableComparisonSource
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.model.Timetable
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TimetableCompareUiState(
    val timetables: List<Timetable> = emptyList(),
    val selectedIds: Set<Long> = emptySet(),
    val weekStartDate: LocalDate = LocalDate.now(),
    val anchorTeachingWeek: Int = 0,
    val minConsecutiveTwo: Boolean = true,
    val loading: Boolean = false,
    val result: TimetableComparisonResult? = null,
)

/**
 * 多课表「合表找空」ViewModel。
 */
@HiltViewModel
class TimetableCompareViewModel @Inject constructor(
    private val timetableRepository: TimetableRepositoryPort,
    private val effectiveSchedule: EffectiveSchedulePort,
    private val scheduleData: ScheduleDataPort,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    private val currentMonday: LocalDate =
        LocalDate.now(clock).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private val _state = MutableStateFlow(TimetableCompareUiState(weekStartDate = currentMonday))
    val state: StateFlow<TimetableCompareUiState> = _state.asStateFlow()

    private var initialSelected = false

    init {
        viewModelScope.launch {
            timetableRepository.observeAll().collect { all ->
                val sorted = all.sortedWith(compareByDescending<Timetable> { it.isCurrent }.thenBy { it.id })
                val currentIds = sorted.map { it.id }.toSet()
                val nextSelected = if (!initialSelected) {
                    initialSelected = true
                    sorted.take(2).map { it.id }.toSet()
                } else {
                    _state.value.selectedIds.intersect(currentIds)
                }
                _state.update { it.copy(timetables = sorted, selectedIds = nextSelected) }
                recompute()
            }
        }
    }

    fun toggleTimetable(id: Long) {
        _state.update { current ->
            val next = if (id in current.selectedIds) {
                current.selectedIds - id
            } else {
                current.selectedIds + id
            }
            current.copy(selectedIds = next)
        }
        viewModelScope.launch { recompute() }
    }

    fun previousWeek() {
        _state.update { it.copy(weekStartDate = it.weekStartDate.minusWeeks(1)) }
        viewModelScope.launch { recompute() }
    }

    fun nextWeek() {
        _state.update { it.copy(weekStartDate = it.weekStartDate.plusWeeks(1)) }
        viewModelScope.launch { recompute() }
    }

    fun jumpToCurrentWeek() {
        if (_state.value.weekStartDate == currentMonday) return
        _state.update { it.copy(weekStartDate = currentMonday) }
        viewModelScope.launch { recompute() }
    }

    fun setMinConsecutiveTwo(enabled: Boolean) {
        _state.update { it.copy(minConsecutiveTwo = enabled) }
    }

    private suspend fun recompute() {
        val current = _state.value
        val selected = current.timetables.filter { it.id in current.selectedIds }
        val weekStart = current.weekStartDate
        val weekEnd = weekStart.plusDays(6)

        if (selected.isEmpty()) {
            _state.update {
                it.copy(
                    loading = false,
                    anchorTeachingWeek = 0,
                    result = TimetableComparisonCalculator.compare(emptyList(), weekStart),
                )
            }
            return
        }

        _state.update { it.copy(loading = true) }
        val sources = selected.map { table ->
            val semester = runCatching { scheduleData.observeSemester(table.id).first() }.getOrNull()
            val slots = runCatching { scheduleData.observeTimeSlots(table.id).first() }.getOrDefault(emptyList())
            val occurrences = runCatching {
                effectiveSchedule.resolveTimetableRange(table.id, weekStart..weekEnd)
            }.getOrDefault(emptyList())
            TimetableComparisonSource(
                timetable = table,
                semester = semester,
                timeSlots = slots,
                occurrences = occurrences,
            )
        }

        val anchorSource = sources.firstOrNull { it.timetable.isCurrent } ?: sources.first()
        val teachingWeek = CourseRules.weekOfDate(anchorSource.semester, weekStart)
        val result = TimetableComparisonCalculator.compare(sources, weekStart)
        _state.update {
            it.copy(
                loading = false,
                anchorTeachingWeek = teachingWeek,
                result = result,
            )
        }
    }
}
