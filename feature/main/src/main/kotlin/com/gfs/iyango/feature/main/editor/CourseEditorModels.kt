package com.gfs.iyango.feature.main.editor

import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.CourseValidator
import com.gfs.iyango.core.model.WeekType

/**
 * 课程编辑表单的纯数据模型（契约 §5）。
 *
 * 表单分为"基本信息"（整门课共享）与"上课安排"列表（每条落成一门 [Course] 行）。
 * 颜色用 `#RRGGBB` 字符串；学分用原始文本输入，组装时再解析。
 */
data class CourseEditorForm(
    val name: String = "",
    val abbreviation: String = "",
    val credit: String = "",
    val isOutdoor: Boolean = false,
    val enabled: Boolean = true,
    val remark: String = "",
    val color: String = CourseEditorDefaults.COLOR,
    val arrangements: List<ArrangementDraft> = listOf(ArrangementDraft()),
    /** 整门课的分组键；新建/复制时为空，保存前由调用方经 [buildCourses] 的回退值补齐。 */
    val groupKey: String = "",
)

/** 一条"上课安排"草稿。 */
data class ArrangementDraft(
    val teacher: String = "",
    val classroom: String = "",
    /** 时间模式：0=按节次，1=每周自定义时间，2=指定日期。 */
    val timeMode: Int = 0,
    /** 星期几，1=周一 … 7=周日（timeMode 0/1 用）。 */
    val dayOfWeek: Int = 1,
    val startSection: Int = 1,
    val endSection: Int = 2,
    /** 自定义钟点 HH:mm（timeMode=1 用）。 */
    val startTime: String = "08:00",
    val endTime: String = "09:40",
    /** 指定日期 yyyy-MM-dd（timeMode=2 用）。 */
    val date: String = "",
    val startWeek: Int = 1,
    val endWeek: Int = 20,
    val weekType: WeekType = WeekType.EVERY,
    /** 额外指定周；空表示不额外筛选。 */
    val selectedWeeks: List<Int> = emptyList(),
    /** 轮换周期长度；1=不轮换。 */
    val cycleLength: Int = 1,
    /** 轮换相位；0=不启用。 */
    val cycleWeek: Int = 0,
    /** 展示投影前的原始起始节次（0=未投影）；编辑既有行时原样保留。 */
    val sourceStartSection: Int = 0,
    /** 展示投影前的原始结束节次（0=未投影）；编辑既有行时原样保留。 */
    val sourceEndSection: Int = 0,
    /** 学校稳定来源键（仅教务同步写入）；新行恒为空，避免被同步覆盖。 */
    val sourceKey: String = "",
    /** 被单次调整的原课程 id；仅既有行携带，新行恒为 0。 */
    val overrideOfId: Long = 0,
    /** 单次调整生效的教学周；[overrideOfId] > 0 时有效。 */
    val overrideWeek: Int = 0,
)

/** 编辑器默认常量。 */
object CourseEditorDefaults {
    /** 默认强调色（与 core:model Course.color 默认一致）。 */
    const val COLOR = "#3E7BFA"

    /** 周次选择器的周数上限（学期总周数未取到时的展示上限）。 */
    const val MAX_WEEK = 30
}

/**
 * 把表单组装为待保存的 [Course] 列表（纯函数，可单测）。
 *
 * - 基本信息字段复制到每条安排；
 * - 学分文本解析失败按 0 处理（展示层负责提示，组装不抛错）；
 * - **编辑模式**：[baseId] > 0 时第 0 条安排保留该 id（原地更新，保住单次调整
 *   记录的 `overrideOfId` 引用），其余安排作为新行插入；新建模式全部 id=0；
 * - **同步来源字段**（`groupKey`/`sourceKey`/`sourceStartSection`/`sourceEndSection`/
 *   `overrideOfId`/`overrideWeek`）随草稿携带：既有行原样保留，保证下次同步仍能按
 *   `sourceKey` 对上、单次调整不错挂；新增行一律视为本机课程（来源键与调整引用清空）；
 * - 整组 `groupKey`：表单已携带时直接沿用；为空（新建课程或无分组键的旧数据）时取
 *   [fallbackGroupKey]，多条安排因此共享同一组键；
 * - `timeUnconfirmed` 是读取期标记、不持久化（见 `core:model` Course KDoc），组装恒为 false。
 */
fun buildCourses(
    form: CourseEditorForm,
    timetableId: Long,
    baseId: Long = 0,
    fallbackGroupKey: String = "",
): List<Course> {
    val credit = form.credit.trim().toDoubleOrNull() ?: 0.0
    val groupKey = form.groupKey.ifBlank { fallbackGroupKey }
    return form.arrangements.mapIndexed { index, arrangement ->
        val id = if (index == 0 && baseId > 0) baseId else 0
        Course(
            id = id,
            timetableId = timetableId,
            name = form.name.trim(),
            teacher = arrangement.teacher.trim(),
            classroom = arrangement.classroom.trim(),
            abbreviation = form.abbreviation.trim(),
            remark = form.remark.trim(),
            dayOfWeek = arrangement.dayOfWeek,
            startSection = arrangement.startSection,
            endSection = arrangement.endSection,
            sourceStartSection = arrangement.sourceStartSection,
            sourceEndSection = arrangement.sourceEndSection,
            startWeek = arrangement.startWeek,
            endWeek = arrangement.endWeek,
            weekType = arrangement.weekType,
            selectedWeeks = arrangement.selectedWeeks.sorted(),
            color = form.color,
            credit = credit,
            isOutdoor = form.isOutdoor,
            enabled = form.enabled,
            timeMode = arrangement.timeMode,
            startTime = if (arrangement.timeMode == 1) arrangement.startTime else "",
            endTime = if (arrangement.timeMode == 1) arrangement.endTime else "",
            date = if (arrangement.timeMode == 2) arrangement.date else "",
            cycleLength = arrangement.cycleLength,
            cycleWeek = arrangement.cycleWeek,
            overrideOfId = if (id > 0) arrangement.overrideOfId else 0,
            overrideWeek = if (id > 0) arrangement.overrideWeek else 0,
            groupKey = groupKey,
            sourceKey = arrangement.sourceKey,
        )
    }
}

/**
 * 对组装结果做逐条校验，返回第一条问题文案；全部合法返回 null（纯函数，可单测）。
 */
fun firstValidationError(courses: List<Course>): String? {
    for (course in courses) {
        val problem = CourseValidator.validate(course)
        if (problem != null) {
            return problem
        }
    }
    return null
}

/** 周次摘要文案（"1-16 周 单周" / "第 2,4,6 周"），列表行与安排卡共用。 */
fun weekSummary(arrangement: ArrangementDraft): String {
    val base = if (arrangement.selectedWeeks.isEmpty()) {
        val range = if (arrangement.startWeek == arrangement.endWeek) {
            "${arrangement.startWeek}"
        } else {
            "${arrangement.startWeek}-${arrangement.endWeek}"
        }
        val type = when (arrangement.weekType) {
            WeekType.EVERY -> "每周"
            WeekType.ODD -> "单周"
            WeekType.EVEN -> "双周"
        }
        "$range 周 $type"
    } else {
        "第 ${arrangement.selectedWeeks.sorted().joinToString("、")} 周"
    }
    val cycle = if (arrangement.cycleWeek > 0 && arrangement.cycleLength > 1) {
        " 每 ${arrangement.cycleLength} 周第 ${arrangement.cycleWeek} 周"
    } else {
        ""
    }
    return base + cycle
}
