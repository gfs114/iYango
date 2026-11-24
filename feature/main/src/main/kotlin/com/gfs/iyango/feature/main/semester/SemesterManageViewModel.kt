package com.gfs.iyango.feature.main.semester

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.domain.ScheduleDataPort
import com.gfs.iyango.core.domain.ScheduleDefaults
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.TimeSlot
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 学期与节次管理页 ViewModel（契约 §7）。
 *
 * 学期动作：新建/编辑（底部 Sheet：名称/开学日期/总周数 1-30）、设为当前、删除。
 * 作息动作：逐节编辑起止时间、恢复默认 11 节（[ScheduleDefaults]）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SemesterManageViewModel @Inject constructor(
    private val timetableRepository: TimetableRepositoryPort,
    private val scheduleData: ScheduleDataPort,
) : ViewModel() {

    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    /** 当前课表的学期列表（空表示尚无课表）。 */
    val semesters: StateFlow<List<Semester>> = timetableRepository.observeCurrent()
        .flatMapLatest { timetable ->
            if (timetable == null) flowOf(emptyList()) else {
                scheduleData.observeAllSemesters(timetable.id)
            }
        }
        .catch { error.value = "读取学期失败：${it.message}" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前课表的作息时间表。 */
    val timeSlots: StateFlow<List<TimeSlot>> = timetableRepository.observeCurrent()
        .flatMapLatest { timetable ->
            if (timetable == null) flowOf(emptyList()) else {
                scheduleData.observeTimeSlots(timetable.id)
            }
        }
        .catch { error.value = "读取作息失败：${it.message}" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 新建或更新学期；isCurrent=true 时数据层保证单当前。 */
    fun saveSemester(draft: SemesterDraft) {
        viewModelScope.launch {
            busy.value = true
            runCatching {
                val timetable = timetableRepository.observeCurrent()
                val current = timetable.first()
                checkNotNull(current) { "尚未创建课表" }
                scheduleData.saveSemester(
                    Semester(
                        id = draft.id,
                        timetableId = current.id,
                        name = draft.name.trim().ifEmpty { "新学期" },
                        startDate = draft.startDate,
                        totalWeeks = draft.totalWeeks.coerceIn(1, 30),
                        isCurrent = draft.isCurrent,
                    )
                )
            }.onFailure { error.value = it.message ?: "保存失败" }
            busy.value = false
        }
    }

    /** 把 [id] 设为当前学期（保存 isCurrent=true 副本）。 */
    fun setCurrent(id: Long) {
        val target = semesters.value.firstOrNull { it.id == id } ?: return
        saveSemester(
            SemesterDraft(
                id = target.id,
                name = target.name,
                startDate = target.startDate,
                totalWeeks = target.totalWeeks,
                isCurrent = true,
            )
        )
    }

    /** 删除学期。 */
    fun deleteSemester(id: Long) {
        viewModelScope.launch {
            runCatching { scheduleData.deleteSemester(id) }
                .onFailure { error.value = it.message ?: "删除失败" }
        }
    }

    /** 整体保存作息表（逐节编辑后的批量提交）。 */
    fun saveTimeSlots(slots: List<TimeSlot>) {
        viewModelScope.launch {
            busy.value = true
            runCatching {
                val current = timetableRepository.observeCurrent().first()
                checkNotNull(current) { "尚未创建课表" }
                scheduleData.saveTimeSlots(current.id, slots)
            }.onFailure { error.value = it.message ?: "保存失败" }
            busy.value = false
        }
    }

    /** 恢复默认 11 节作息（整体替换）。 */
    fun resetTimeSlots() {
        viewModelScope.launch {
            busy.value = true
            runCatching {
                val current = timetableRepository.observeCurrent().first()
                checkNotNull(current) { "尚未创建课表" }
                scheduleData.saveTimeSlots(current.id, ScheduleDefaults.defaultTimeSlots(current.id))
            }.onFailure { error.value = it.message ?: "恢复失败" }
            busy.value = false
        }
    }

    fun consumeError() {
        error.value = null
    }

    /** 学期编辑弹层载荷（新建时 id=0）。 */
    data class SemesterDraft(
        val id: Long = 0,
        val name: String = "",
        val startDate: LocalDate = LocalDate.now(),
        val totalWeeks: Int = 20,
        val isCurrent: Boolean = true,
    )
}
