package com.gfs.iyango.feature.main.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.designsystem.theme.CourseSwatch
import com.gfs.iyango.core.domain.EffectiveSchedulePort
import com.gfs.iyango.core.domain.WallpaperStore
import com.gfs.iyango.feature.main.schedule.*
import java.time.Clock
import java.time.LocalDateTime
import com.gfs.iyango.core.domain.AppSettingsPort
import com.gfs.iyango.core.domain.ScheduleDataPort
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.DisplaySettingsBundle
import com.gfs.iyango.core.model.ScheduleDisplaySettings
import com.gfs.iyango.core.model.Timetable
import com.gfs.iyango.feature.main.schedule.ScheduleDisplayProjection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 课表显示设置 ViewModel（F09）。
 *
 * 全局默认与按课表覆盖都写入 [AppSettingsPort]；界面只改配置，不触碰课程行。
 * 配色覆盖以稳定课程组键保存，切周/刷新不会重新随机分配。
 */
@HiltViewModel
class DisplaySettingsViewModel @Inject constructor(
    private val appSettings: AppSettingsPort,
    private val timetableRepository: TimetableRepositoryPort,
    private val scheduleData: ScheduleDataPort,
    private val effectiveSchedule: EffectiveSchedulePort,
    private val wallpaperStore: WallpaperStore,
    private val clock: Clock,
    private val examRepository: com.gfs.iyango.core.domain.ExamRepositoryPort,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** 从课表页溢出菜单进入时默认编辑"仅本课表"。 */
    val initialPerTimetable: Boolean = savedStateHandle.get<Boolean>("perTimetable") ?: false

    private val bundle: StateFlow<DisplaySettingsBundle> = appSettings.displaySettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, DisplaySettingsBundle())

    private val timetable: StateFlow<Timetable?> = timetableRepository.observeCurrent()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val courses: StateFlow<List<Course>> = timetable
        .flatMapLatest { table ->
            if (table == null) flowOf(emptyList()) else scheduleData.observeCourses(table.id)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Same selected-day projection as the formal timetable; no sample rows or writes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val preview: StateFlow<ScheduleUiState> = timetable.flatMapLatest { table ->
        if (table == null) flowOf<ScheduleUiState>(ScheduleUiState.NoTimetable)
        else combine(scheduleData.observeSemester(table.id), scheduleData.observeTimeSlots(table.id),
            scheduleData.observeCourses(table.id)) { semester, slots, courses -> Triple(semester, slots, courses) }
            .flatMapLatest { (semester, slots, courses) ->
                if (semester == null) flowOf<ScheduleUiState>(ScheduleUiState.NoSemester)
                else effectiveSchedule.observeSelectedRange(semester.startDate..
                    semester.startDate.plusDays(semester.totalWeeks.coerceIn(1, 30) * 7L - 1))
                    .combine(examRepository.observeInRange(semester.startDate.minusDays(2),
                        semester.startDate.plusDays(semester.totalWeeks.coerceIn(1,30) * 7L + 1))) { days, exams ->
                        ScheduleUiState.Ready(buildScheduleWeekData(table, semester, slots, courses,
                        scheduleData::timeRailRows, WeekFlipDirection.JUMP, LocalDateTime.now(clock), days, null).let { data ->
                            data.copy(exams = ExamWeekProjection.project(exams,data.weekStart,clock.zone,data.sectionCount,data::slotsForDay))
                        }) }
            }
    }.catch { emit(ScheduleUiState.Error(it.message ?: "课表读取失败", true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduleUiState.FirstLoading)

    val wallpaper: StateFlow<ScheduleWallpaper> = appSettings.appPreferences.map { prefs ->
        ScheduleWallpaper(wallpaperStore.resolve(prefs.timetableWallpaperPath),
            wallpaperStore.resolve(prefs.timetableWallpaperPathDark), prefs.wallpaperScrim, prefs.wallpaperBlur)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduleWallpaper())

    val uiState: StateFlow<DisplaySettingsUiState> = combine(bundle, timetable) { settings, table ->
        DisplaySettingsUiState(
            timetableName = table?.name.orEmpty(),
            hasTimetable = table != null,
            hasOverride = table != null && settings.byTimetable.containsKey(table.id),
            global = settings.global,
            effective = table?.let { settings.forTimetable(it.id) } ?: settings.global,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DisplaySettingsUiState())

    /** 保存设置；[perTimetable] 为 true 时只覆盖当前课表，否则改全局默认。 */
    fun save(settings: ScheduleDisplaySettings, perTimetable: Boolean) {
        val tableId = timetable.value?.id ?: 0L
        viewModelScope.launch {
            val sanitized = settings.sanitized()
            if (perTimetable && tableId > 0L) {
                appSettings.updateTimetableDisplay(tableId, sanitized)
            } else {
                appSettings.updateGlobalDisplay(sanitized)
            }
        }
    }

    /** 删除当前课表的覆盖，回落到全局默认。 */
    fun removeOverride() {
        val tableId = timetable.value?.id ?: return
        viewModelScope.launch { appSettings.updateTimetableDisplay(tableId, null) }
    }

    /** 全局恢复出厂默认（不删除课程/学期/登录）。 */
    fun resetGlobal() {
        viewModelScope.launch {
            appSettings.updateGlobalDisplay(ScheduleDisplaySettings())
        }
    }

    /**
     * 用户明确"重新配色"：按稳定课程组键排序后从 12 色板顺序取色。
     * 只写配置，不修改课程行；同一份课表重复执行结果一致。
     */
    fun reassignColors(settings: ScheduleDisplaySettings, perTimetable: Boolean) {
        val keys = courses.value
            .filter { it.enabled }
            .map { ScheduleDisplayProjection.courseColorKey(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
        if (keys.isEmpty()) return
        val overrides = keys.mapIndexed { index, key ->
            key to CourseSwatch.accents[index % CourseSwatch.accents.size]
        }.toMap()
        save(
            settings.copy(
                courseColorOverrides = overrides,
                paletteVersion = settings.paletteVersion + 1,
            ),
            perTimetable,
        )
    }

    fun clearColors(settings: ScheduleDisplaySettings, perTimetable: Boolean) {
        save(settings.copy(courseColorOverrides = emptyMap()), perTimetable)
    }
}

/**
 * 显示设置页状态。
 *
 * @property global 全局默认配置。
 * @property effective 当前课表的有效配置（覆盖优先）。
 * @property hasOverride 当前课表是否已有独立覆盖。
 */
data class DisplaySettingsUiState(
    val timetableName: String = "",
    val hasTimetable: Boolean = false,
    val hasOverride: Boolean = false,
    val global: ScheduleDisplaySettings = ScheduleDisplaySettings(),
    val effective: ScheduleDisplaySettings = ScheduleDisplaySettings(),
)
