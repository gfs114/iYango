package com.gfs.iyango.feature.main.coursemanage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.domain.ScheduleDataPort
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.Timetable
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed interface CourseManageUiState {
    data object Loading : CourseManageUiState
    data object NoTimetable : CourseManageUiState
    data class Ready(
        val timetable: Timetable,
        val semester: Semester?,
        val totalCount: Int,
        val arrangementCount: Int,
        val rows: List<ManagedCourse>,
        val selectedIds: Set<Long>,
        val query: String,
        val weekFilter: Int,
        val dayFilter: Int,
        val selectionMode: Boolean,
        val busy: Boolean,
        val message: String,
        val pendingDeletion: CourseDeleteRequest?,
    ) : CourseManageUiState {
        val selectedCount: Int get() = rows.count { it.ids.all(selectedIds::contains) }
        val canSelectAll: Boolean get() = rows.isNotEmpty() && selectedCount != rows.size
        val hasFilters: Boolean get() = query.isNotBlank() || weekFilter > 0 || dayFilter > 0
    }
}

private data class ManageControls(
    val query: String = "", val week: Int = 0, val day: Int = 0,
    val selectedIds: Set<Long> = emptySet(), val selectionMode: Boolean = false,
    val busy: Boolean = false, val message: String = "",
    val pendingDeletion: CourseDeleteRequest? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CourseManageViewModel @Inject constructor(
    private val timetableRepository: TimetableRepositoryPort,
    private val scheduleData: ScheduleDataPort,
) : ViewModel() {
    private val controls = MutableStateFlow(ManageControls())
    private var tableId: Long? = null

    // Switch table and data together, so the new table can never see old-table rows.
    val state: StateFlow<CourseManageUiState> = timetableRepository.observeCurrent().flatMapLatest { table ->
        if (tableId != table?.id) {
            tableId = table?.id
            controls.value = ManageControls(busy = controls.value.busy)
        }
        if (table == null) flowOf(CourseManageUiState.NoTimetable)
        else combine(scheduleData.observeSemester(table.id), scheduleData.observeCourses(table.id), controls) { term, courses, filter ->
            val groups = managedCourses(courses.filter { it.timetableId == table.id })
            val rows = groups.filter { it.matches(filter.query, filter.week, filter.day, term) }
            CourseManageUiState.Ready(
                timetable = table, semester = term, totalCount = groups.size,
                arrangementCount = groups.sumOf { it.arrangements.size }, rows = rows,
                selectedIds = filter.selectedIds intersect rows.flatMap { it.ids }.toSet(),
                query = filter.query, weekFilter = filter.week, dayFilter = filter.day,
                selectionMode = filter.selectionMode, busy = filter.busy, message = filter.message,
                pendingDeletion = filter.pendingDeletion,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CourseManageUiState.Loading)

    fun setQuery(value: String) = changeFilter { copy(query = value) }
    fun setWeek(value: Int) = changeFilter { copy(week = value.coerceAtLeast(0)) }
    fun setDay(value: Int) = changeFilter { copy(day = value.coerceIn(0, 7)) }
    fun resetFilters() = changeFilter { copy(query = "", week = 0, day = 0) }
    private fun changeFilter(change: ManageControls.() -> ManageControls) {
        if (!controls.value.busy) controls.update { it.change().copy(selectedIds = emptySet(), pendingDeletion = null) }
    }
    fun startSelection() { controls.update { it.copy(selectionMode = true) } }
    fun toggleSelected(id: Long) {
        if (controls.value.busy) return
        val group = (state.value as? CourseManageUiState.Ready)?.rows?.find { it.id == id } ?: return
        controls.update { it.copy(selectionMode = true, selectedIds =
            if (it.selectedIds.containsAll(group.ids)) it.selectedIds - group.ids else it.selectedIds + group.ids) }
    }
    fun selectAllVisible() {
        if (controls.value.busy) return
        val ready = state.value as? CourseManageUiState.Ready ?: return
        controls.update { it.copy(selectionMode = true, selectedIds = ready.rows.flatMap { row -> row.ids }.toSet()) }
    }
    fun clearSelection() {
        if (!controls.value.busy) controls.update { it.copy(selectedIds = emptySet(), selectionMode = false, pendingDeletion = null) }
    }
    fun clearMessage() { controls.update { it.copy(message = "") } }
    fun dismissDelete() { controls.update { it.copy(pendingDeletion = null) } }
    fun requestDelete() { selection()?.let { request -> controls.update { it.copy(pendingDeletion = request) } } }

    private fun selection(): CourseDeleteRequest? {
        if (controls.value.busy) return null
        val ready = state.value as? CourseManageUiState.Ready ?: return null
        val selected = ready.rows.filter { controls.value.selectedIds.containsAll(it.ids) }
        return selected.takeIf { it.isNotEmpty() }?.let { CourseDeleteRequest(ready.timetable.id, it) }
    }
    fun confirmDelete() {
        val request = controls.value.pendingDeletion ?: return
        perform(request, "删除") { scheduleData.deleteCourses(request.timetableId, request.ids) }
    }
    fun deleteSelected() { requestDelete(); confirmDelete() }

    fun copySelected() {
        val request = selection() ?: return
        perform(request, "复制") {
            val copies = request.groups.flatMap { group ->
                val copyKey = "course-copy-${UUID.randomUUID()}"
                group.arrangements.map { it.copy(id = 0, name = "${it.name} 副本", groupKey = copyKey,
                    sourceKey = "", overrideOfId = 0, overrideWeek = 0) }
            }
            scheduleData.saveCourses(copies)
        }
    }
    private fun perform(request: CourseDeleteRequest, verb: String, action: suspend () -> Unit) {
        if (controls.value.busy) return
        controls.update { it.copy(busy = true, pendingDeletion = null, message = "") }
        viewModelScope.launch {
            try {
                check(timetableRepository.observeCurrent().first()?.id == request.timetableId) { "课表已切换，请重新选择课程" }
                val latest = scheduleData.observeCourses(request.timetableId).first().filter { it.id in request.ids }.toSet()
                check(latest == request.groups.flatMap { it.arrangements }.toSet()) { "课程已变化，请重新选择后操作" }
                action()
                if (tableId == request.timetableId) controls.update {
                    it.copy(selectedIds = emptySet(), selectionMode = false, message = "已$verb ${request.groups.size} 门课程")
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (tableId == request.timetableId) controls.update { it.copy(message = error.message ?: "${verb}失败，请重试") }
            } finally {
                controls.update { it.copy(busy = false) }
            }
        }
    }
}
