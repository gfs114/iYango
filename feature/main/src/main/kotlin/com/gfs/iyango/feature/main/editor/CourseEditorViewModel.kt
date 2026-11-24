package com.gfs.iyango.feature.main.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.domain.AppSettingsPort
import com.gfs.iyango.core.domain.ConflictDetector
import com.gfs.iyango.core.domain.ScheduleDataPort
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.ScheduleDisplaySettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 课程编辑页 ViewModel（契约 §5）。
 *
 * 保存流程：组装 → 逐条 [com.gfs.iyango.core.model.CourseValidator.validate] →
 * 冲突预检（[ConflictDetector]，可弹窗继续）→ 单事务 [ScheduleDataPort.saveCourses]。
 *
 * 编辑模式：`SavedStateHandle["courseId"] > 0` 时加载该课程填充表单；保存时第 0 条
 * 安排保留原 id 原地更新（见 [buildCourses]）。
 */
@HiltViewModel
class CourseEditorViewModel @Inject constructor(
    private val timetableRepository: TimetableRepositoryPort,
    private val scheduleData: ScheduleDataPort,
    private val appSettings: AppSettingsPort,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** 当前课表生效的显示设置（含色板编辑值），供编辑页取色圆点使用。 */
    val displaySettings: StateFlow<ScheduleDisplaySettings> = combine(
        appSettings.displaySettings,
        timetableRepository.observeCurrent(),
    ) { bundle, table ->
        table?.let { bundle.forTimetable(it.id) } ?: bundle.global
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ScheduleDisplaySettings())

    /** 被编辑课程 id；0 = 新建。 */
    val courseId: Long = savedStateHandle.get<Long>("courseId") ?: 0L

    /** 复制来源课程 id；新建模式下非 0 时预填来源内容，保存生成新课程行。 */
    private val copyFromId: Long = savedStateHandle.get<Long>("copyFromId") ?: 0L

    private val history = EditorHistory<CourseEditorForm>()

    val form = MutableStateFlow(CourseEditorForm())
    val error = MutableStateFlow<String?>(null)

    /** 非 null 时展示冲突确认弹窗（继续 = 强制保存）。 */
    val conflictMessage = MutableStateFlow<String?>(null)

    /** 保存成功一次性事件；Screen 观察到 true 后返回上一页。 */
    val saved = MutableStateFlow(false)

    /** 初始表单（离开守卫的比较基准）。 */
    private var initialForm: CourseEditorForm = CourseEditorForm()

    val canUndo = MutableStateFlow(false)
    val canRedo = MutableStateFlow(false)

    init {
        // 编辑优先于复制：两者同时出现时按编辑处理，避免复制参数误改原课程。
        val loadId = if (courseId > 0) courseId else copyFromId
        if (loadId > 0) {
            viewModelScope.launch {
                val timetable = timetableRepository.observeCurrent().first() ?: return@launch
                val course = scheduleData.observeCourses(timetable.id).first()
                    .firstOrNull { it.id == loadId } ?: return@launch
                val loaded = course.toForm(asCopy = courseId == 0L && copyFromId > 0)
                initialForm = loaded
                form.value = loaded
            }
        } else {
            initialForm = form.value
        }
    }

    /** 表单是否相对初始状态有改动（离开守卫）。 */
    fun isDirty(): Boolean = form.value != initialForm

    /**
     * 用户编辑入口：先记录撤销快照（同标签连续输入自动合并），再应用变换。
     */
    fun update(tag: String, transform: (CourseEditorForm) -> CourseEditorForm) {
        history.push(form.value, tag)
        form.value = transform(form.value)
        refreshUndoRedo()
    }

    fun undo() {
        history.undo(form.value)?.let { previous ->
            form.value = previous
            refreshUndoRedo()
        }
    }

    fun redo() {
        history.redo(form.value)?.let { next ->
            form.value = next
            refreshUndoRedo()
        }
    }

    private fun refreshUndoRedo() {
        canUndo.value = history.canUndo
        canRedo.value = history.canRedo
    }

    /** 保存入口；未确认冲突时先弹确认，[force] = true 表示用户选择继续。 */
    fun save(force: Boolean = false) {
        error.value = null
        viewModelScope.launch {
            val timetable = timetableRepository.observeCurrent().first()
            if (timetable == null) {
                error.value = "尚未创建课表，请先在「我的-我的课表」中新建"
                return@launch
            }
            // 同步来源字段（groupKey/sourceKey 等）随草稿携带，避免编辑清空同步对账依据。
            val courses = buildCourses(
                form.value,
                timetable.id,
                baseId = courseId,
                fallbackGroupKey = fallbackGroupKey,
            )
            val invalid = firstValidationError(courses)
            if (invalid != null) {
                error.value = invalid
                return@launch
            }
            if (!force) {
                val existing = scheduleData.observeCourses(timetable.id).first()
                val conflicts = courses.flatMap { candidate ->
                    ConflictDetector.findConflicts(
                        candidate = candidate,
                        existing = existing,
                        ignoreId = if (courseId > 0) courseId else candidate.id,
                    )
                }
                val message = ConflictDetector.warningMessage(conflicts)
                if (message != null) {
                    conflictMessage.value = message
                    return@launch
                }
            }
            conflictMessage.value = null
            try {
                // 单事务写入：任一安排失败则整批回滚，不会留下半保存的课程组。
                scheduleData.saveCourses(courses)
                initialForm = form.value
                saved.value = true
            } catch (e: IllegalArgumentException) {
                error.value = e.message ?: "保存失败"
            }
        }
    }

    /** 冲突弹窗"继续添加"。 */
    fun confirmConflict() = save(force = true)

    /** 冲突弹窗"取消"。 */
    fun dismissConflict() {
        conflictMessage.value = null
    }

    /**
     * 表单未携带分组键（新建课程、复制课程或无分组键的旧数据）时使用的整组共用键。
     *
     * 每次编辑会话只生成一次：冲突确认后重试保存时沿用同一分组，
     * 格式与鸿蒙 `CourseEditDraft.fromCourses` 的 `course-<时间戳>-<随机数>` 一致。
     */
    private val fallbackGroupKey: String by lazy {
        "course-" + System.currentTimeMillis() + "-" + Random.nextInt(1_000_000)
    }

    private fun Course.toForm(asCopy: Boolean = false): CourseEditorForm = CourseEditorForm(
        name = name,
        abbreviation = abbreviation,
        credit = if (credit == 0.0) "" else trimZero(credit.toString()),
        isOutdoor = isOutdoor,
        enabled = enabled,
        remark = remark,
        color = color,
        groupKey = if (asCopy) "" else groupKey,
        arrangements = listOf(
            ArrangementDraft(
                teacher = teacher,
                classroom = classroom,
                timeMode = timeMode,
                dayOfWeek = dayOfWeek,
                startSection = startSection,
                endSection = endSection,
                startTime = startTime.ifEmpty { "08:00" },
                endTime = endTime.ifEmpty { "09:40" },
                date = date,
                startWeek = startWeek,
                endWeek = endWeek,
                weekType = weekType,
                selectedWeeks = selectedWeeks,
                cycleLength = cycleLength,
                cycleWeek = cycleWeek,
                sourceStartSection = sourceStartSection,
                sourceEndSection = sourceEndSection,
                // 复制生成独立课程：不再被教务同步按来源键覆盖，也不挂旧调整引用。
                sourceKey = if (asCopy) "" else sourceKey,
                overrideOfId = if (asCopy) 0 else overrideOfId,
                overrideWeek = if (asCopy) 0 else overrideWeek,
            )
        ),
    )

    private fun trimZero(text: String): String =
        if (text.endsWith(".0")) text.dropLast(2) else text
}
