package com.gfs.iyango.feature.main.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.domain.ScheduleDataPort
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.model.Timetable
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 多课表管理页 ViewModel（契约 §6）。
 *
 * 支持动作：设为当前 / 新建 / 重命名 / 复制（含学期、作息、课程整包）/ 删除（仅 LOCAL）。
 */
@HiltViewModel
class TimetableManageViewModel @Inject constructor(
    private val timetableRepository: TimetableRepositoryPort,
    private val scheduleData: ScheduleDataPort,
) : ViewModel() {

    /** 待删除课表（非 null 时展示确认弹窗，携带影响面统计）。 */
    val pendingDelete = MutableStateFlow<PendingDelete?>(null)

    /** 操作错误提示（Toast/行内）。 */
    val error = MutableStateFlow<String?>(null)

    /** 操作完成后触发返回（新建/复制后可停留，此标志暂用于删除后刷新）。 */
    val busy = MutableStateFlow(false)

    val timetables: StateFlow<List<Timetable>> = timetableRepository.observeAll()
        .catch { error.value = "读取课表失败：${it.message}" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 把 [id] 设为当前课表。 */
    fun setCurrent(id: Long) {
        viewModelScope.launch {
            runCatching { timetableRepository.setCurrent(id) }
                .onFailure { error.value = "切换失败：${it.message}" }
        }
    }

    /** 新建空课表（名称默认"我的课表 N"，来源 LOCAL）。 */
    fun create(name: String) {
        viewModelScope.launch {
            val trimmed = name.trim().ifEmpty { "我的课表" }
            runCatching {
                val count = timetables.value.size
                timetableRepository.upsert(
                    Timetable(name = if (name.isBlank()) "$trimmed ${count + 1}" else trimmed),
                )
            }.onFailure { error.value = "新建失败：${it.message}" }
        }
    }

    /** 重命名课表。 */
    fun rename(id: Long, name: String) {
        viewModelScope.launch {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) {
                error.value = "名称不能为空"
                return@launch
            }
            runCatching {
                val target = timetableRepository.observeAll().first().firstOrNull { it.id == id }
                    ?: return@runCatching
                timetableRepository.upsert(target.copy(name = trimmed))
            }.onFailure { error.value = "重命名失败：${it.message}" }
        }
    }

    /** 保存分层规则（分组名/自动开关/生效星期/轮换）。 */
    fun saveRules(timetable: Timetable) {
        viewModelScope.launch {
            runCatching { timetableRepository.upsert(timetable) }
                .onFailure { error.value = "保存规则失败：${it.message}" }
        }
    }

    /** 预检删除影响面（弹确认框前调用）。 */
    fun requestDelete(id: Long) {
        viewModelScope.launch {
            val target = timetableRepository.observeAll().first().firstOrNull { it.id == id }
            if (target == null) {
                error.value = "课表不存在"
                return@launch
            }
            if (target.source != "LOCAL") {
                error.value = "仅本地课表可删除"
                return@launch
            }
            val courseCount = scheduleData.observeCourses(id).first().size
            val semester = scheduleData.observeSemester(id).first()
            pendingDelete.value = PendingDelete(
                timetable = target,
                courseCount = courseCount,
                semesterName = semester?.name,
            )
        }
    }

    /** 确认删除（级联清空其课程/学期/作息，由 data 层 FK CASCADE 保证）。 */
    fun confirmDelete() {
        val target = pendingDelete.value ?: return
        viewModelScope.launch {
            busy.value = true
            runCatching { timetableRepository.delete(target.timetable.id) }
                .onFailure { error.value = "删除失败：${it.message}" }
            busy.value = false
            pendingDelete.value = null
        }
    }

    fun dismissDelete() {
        pendingDelete.value = null
    }

    /**
     * 复制课表：学期/作息/课程整包单事务复制（data 层 [TimetableRepositoryPort.duplicate]），
     * 任一步失败整体回滚。
     */
    fun duplicate(id: Long) {
        viewModelScope.launch {
            busy.value = true
            runCatching { timetableRepository.duplicate(id) }
                .onFailure { error.value = "复制失败：${it.message}" }
            busy.value = false
        }
    }

    fun consumeError() {
        error.value = null
    }

    /** 删除确认弹窗载荷。 */
    data class PendingDelete(
        val timetable: Timetable,
        val courseCount: Int,
        val semesterName: String?,
    )
}
