package com.gfs.iyango.feature.main.export

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.domain.AppSettingsPort
import com.gfs.iyango.core.domain.CourseOccurrence
import com.gfs.iyango.core.domain.CourseRules
import com.gfs.iyango.core.domain.EffectiveSchedulePort
import com.gfs.iyango.core.domain.IcsExporter
import com.gfs.iyango.core.domain.ScheduleDataPort
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.model.ScheduleDisplaySettings
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.TimeSlot
import com.gfs.iyango.core.model.Timetable
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 课表图片/ICS 导出（E04）。
 *
 * 页面打开时冻结一次"课表 + 学期 + 作息 + 显示配置 + 该周实例集合"快照；切换周次只
 * 重新解析所选周的实例，切换分享开关只重绘图片，两者都基于同一快照。图片与 ICS
 * 始终来自同一个 [ScheduleExportUiState.occurrences]，不会各算一套周规则。
 */
@HiltViewModel
class ScheduleExportViewModel @Inject constructor(
    private val timetableRepository: TimetableRepositoryPort,
    private val scheduleData: ScheduleDataPort,
    private val effectiveSchedule: EffectiveSchedulePort,
    private val appSettings: AppSettingsPort,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(ScheduleExportUiState())
    val state: StateFlow<ScheduleExportUiState> = _state.asStateFlow()

    /** 待保存的 ICS 内容；非 null 时 Screen 触发 SAF 写入。 */
    val pendingIcs = MutableStateFlow<PendingIcs?>(null)

    /** 待保存的图片文件名；非 null 时 Screen 触发 SAF 写入。 */
    val pendingImageName = MutableStateFlow<String?>(null)

    private var loadJob: Job? = null
    private var renderJob: Job? = null

    init {
        refresh()
    }

    /** 重新读取课表列表与当前课表快照（页面上的"刷新数据"）。 */
    fun refresh() {
        viewModelScope.launch {
            try {
                val tables = timetableRepository.observeAll().first()
                if (tables.isEmpty()) {
                    _state.update {
                        it.copy(
                            timetables = emptyList(),
                            selectedTimetableId = 0,
                            selectedTimetableName = "",
                            semester = null,
                            occurrences = emptyList(),
                        displayDays = emptyList(),
                            previewBitmap = null,
                            loading = false,
                            error = "还没有课表，请先创建或导入课表",
                        )
                    }
                    return@launch
                }
                _state.update { it.copy(timetables = tables) }
                val selected = tables.firstOrNull { it.id == _state.value.selectedTimetableId }
                    ?: tables.firstOrNull { it.isCurrent }
                    ?: tables.first()
                loadSnapshot(selected.id, keepWeek = selected.id == _state.value.selectedTimetableId, resetToggles = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "读取课表失败") }
            }
        }
    }

    fun selectTimetable(timetableId: Long) {
        if (timetableId == _state.value.selectedTimetableId && _state.value.semester != null) return
        loadSnapshot(timetableId, keepWeek = false, resetToggles = true)
    }

    fun selectWeek(week: Int) {
        val snapshot = _state.value
        val semester = snapshot.semester ?: return
        val target = week.coerceIn(1, semester.totalWeeks)
        if (target == snapshot.week) return
        _state.update {
            it.copy(
                week = target,
                icsEventCount = 0,
                icsSkipped = emptyList(),
                error = "",
            )
        }
        val start = weekStart(semester, target) ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val displayDays = withContext(Dispatchers.IO) {
                    effectiveSchedule.resolveTimetableDays(
                        snapshot.selectedTimetableId,
                        start..start.plusDays(6),
                    )
                }
                _state.update { it.copy(occurrences = displayDays.flatMap { day -> day.occurrences }, displayDays = displayDays, loading = false) }
                renderPreview()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "读取本周课表失败") }
            }
        }
    }

    fun setShowTeacher(value: Boolean) {
        _state.update { it.copy(showTeacher = value) }
        renderPreview()
    }

    fun setShowClassroom(value: Boolean) {
        _state.update { it.copy(showClassroom = value) }
        renderPreview()
    }

    fun setResolution(value: ExportResolution) {
        _state.update { it.copy(resolution = value) }
        renderPreview()
    }

    /** 当前冻结快照的预览位图（Screen 在 SAF 回调前取一次，避免保存时被重绘替换）。 */
    fun previewBitmap(): Bitmap? = _state.value.previewBitmap

    fun requestSaveImage() {
        val snapshot = _state.value
        if (snapshot.previewBitmap == null) {
            _state.update { it.copy(error = "图片还未生成完成") }
            return
        }
        pendingImageName.value = baseFileName(snapshot) + ".png"
    }

    fun consumePendingImage() {
        pendingImageName.value = null
    }

    /**
     * 生成 ICS 文件内容。
     *
     * 命名空间只在本机持久化一次，同一实例的 UID 在多次导出间保持不变；默认不附加
     * VALARM，避免与应用内提醒重复。
     */
    fun requestExportIcs() {
        val snapshot = _state.value
        if (snapshot.occurrences.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(busyIcs = true, error = "") }
            try {
                val namespace = appSettings.exportNamespace()
                val result = withContext(Dispatchers.IO) {
                    IcsExporter.export(
                        occurrences = snapshot.occurrences,
                        zoneId = clock.zone,
                        namespace = namespace,
                        now = LocalDateTime.now(clock),
                        includeReminder = false,
                    )
                }
                _state.update {
                    it.copy(
                        busyIcs = false,
                        icsEventCount = result.eventCount,
                        icsSkipped = result.skipped,
                    )
                }
                pendingIcs.value = PendingIcs(
                    bytes = result.content.toByteArray(Charsets.UTF_8),
                    fileName = baseFileName(snapshot) + ".ics",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busyIcs = false, error = e.message ?: "ICS 生成失败") }
            }
        }
    }

    fun consumePendingIcs() {
        pendingIcs.value = null
    }

    fun consumeError() {
        _state.update { it.copy(error = "") }
    }

    private fun loadSnapshot(timetableId: Long, keepWeek: Boolean, resetToggles: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = "", icsEventCount = 0, icsSkipped = emptyList()) }
            try {
                val table = timetableRepository.observeAll().first().firstOrNull { it.id == timetableId }
                    ?: error("课表不存在")
                val semester = scheduleData.observeSemester(timetableId).first()
                    ?: error("「${table.name}」还没有设置学期")
                val slots = scheduleData.observeTimeSlots(timetableId).first()
                val display = appSettings.displaySettings.first().forTimetable(timetableId)
                val week = if (keepWeek) {
                    _state.value.week.takeIf { it in 1..semester.totalWeeks } ?: currentWeek(semester)
                } else {
                    currentWeek(semester)
                }
                val start = weekStart(semester, week) ?: error("学期起始日期无效")
                val displayDays = withContext(Dispatchers.IO) {
                    effectiveSchedule.resolveTimetableDays(timetableId, start..start.plusDays(6))
                }
                _state.update {
                    it.copy(
                        selectedTimetableId = table.id,
                        selectedTimetableName = table.name,
                        semester = semester,
                        week = week,
                        timeSlots = slots,
                        display = display,
                        showTeacher = if (resetToggles) display.showTeacher else it.showTeacher,
                        showClassroom = if (resetToggles) display.showClassroom else it.showClassroom,
                        occurrences = displayDays.flatMap { day -> day.occurrences },
                        displayDays = displayDays,
                        loading = false,
                        error = "",
                    )
                }
                renderPreview()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = e.message ?: "读取课表失败",
                        semester = null,
                        week = 0,
                        occurrences = emptyList(),
                        displayDays = emptyList(),
                        previewBitmap = null,
                    )
                }
            }
        }
    }

    private fun renderPreview() {
        val snapshot = _state.value
        val semester = snapshot.semester ?: return
        val start = weekStart(semester, snapshot.week) ?: return
        renderJob?.cancel()
        _state.update { it.copy(rendering = true) }
        val request = ScheduleImageRequest(
            timetableName = snapshot.selectedTimetableName,
            source = snapshot.timetables.firstOrNull { it.id == snapshot.selectedTimetableId }?.source.orEmpty(),
            week = snapshot.week,
            weekStart = start,
            occurrences = snapshot.occurrences,
            timeSlots = snapshot.timeSlots,
            displayDays = snapshot.displayDays,
            display = snapshot.display,
            showTeacher = snapshot.showTeacher,
            showClassroom = snapshot.showClassroom,
            resolution = snapshot.resolution,
        )
        renderJob = viewModelScope.launch {
            try {
                val rendered = withContext(Dispatchers.Default) {
                    ScheduleImageRenderer.render(request)
                }
                _state.update {
                    it.copy(
                        previewBitmap = rendered.bitmap,
                        previewWidth = rendered.widthPx,
                        previewHeight = rendered.heightPx,
                        rendering = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(rendering = false, error = e.message ?: "图片生成失败") }
            }
        }
    }

    private fun currentWeek(semester: Semester): Int =
        CourseRules.weekOfDate(semester, LocalDate.now(clock)).takeIf { it > 0 } ?: 1

    private fun baseFileName(snapshot: ScheduleExportUiState): String {
        val name = snapshot.selectedTimetableName
            .replace(Regex("""[\\/:*?"<>|\s]+"""), "-")
            .trim('-')
            .take(32)
            .ifBlank { "课表" }
        val stamp = FILE_STAMP.format(LocalDateTime.now(clock))
        return "iYango-$name-W${snapshot.week}-$stamp"
    }

    /** 待写 ICS 文件载荷。 */
    data class PendingIcs(val bytes: ByteArray, val fileName: String)

    private companion object {
        val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
    }
}

private fun weekStart(semester: Semester, week: Int): LocalDate? =
    if (week < 1) null else semester.startDate.plusDays((week - 1) * 7L)

/**
 * 导出页状态。
 *
 * @property previewBitmap 冻结快照的离屏渲染结果；切换教师/教室/清晰度只替换它。
 * @property occurrences 与 ICS 共用的最终实例集合（含 F08 调整）。
 */
data class ScheduleExportUiState(
    val timetables: List<Timetable> = emptyList(),
    val selectedTimetableId: Long = 0,
    val selectedTimetableName: String = "",
    val semester: Semester? = null,
    val week: Int = 0,
    val occurrences: List<CourseOccurrence> = emptyList(),
    val timeSlots: List<TimeSlot> = emptyList(),
    val displayDays: List<com.gfs.iyango.core.domain.EffectiveScheduleDay> = emptyList(),
    val display: ScheduleDisplaySettings = ScheduleDisplaySettings(),
    val showTeacher: Boolean = true,
    val showClassroom: Boolean = true,
    val resolution: ExportResolution = ExportResolution.STANDARD,
    val loading: Boolean = false,
    val rendering: Boolean = false,
    val previewBitmap: Bitmap? = null,
    val previewWidth: Int = 0,
    val previewHeight: Int = 0,
    val error: String = "",
    val busyIcs: Boolean = false,
    val icsEventCount: Int = 0,
    val icsSkipped: List<String> = emptyList(),
) {
    val totalWeeks: Int get() = semester?.totalWeeks ?: 0
    val weekStart: LocalDate? get() = semester?.let { weekStart(it, week) }
    val hasTimetable: Boolean get() = timetables.isNotEmpty()
    val canSaveImage: Boolean get() = previewBitmap != null && !rendering
    val canExportIcs: Boolean get() = occurrences.isNotEmpty() && !busyIcs
}
