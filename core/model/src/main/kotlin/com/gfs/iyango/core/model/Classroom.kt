package com.gfs.iyango.core.model

/** 教务下拉/复选选项：值逐字保留，展示名来自学校页面。 */
data class ClassroomOption(
    val value: String = "",
    val label: String = "",
    val selected: Boolean = false,
)

/** 教学楼选项：保留后端代码与展示名称双字段。 */
data class ClassroomBuilding(
    val code: String = "",
    val name: String = "",
)

/**
 * 教室查询条件（草稿与已确认条件分别持有）。
 *
 * - [termValue] / [timeModeId] / [week] / [dayOfWeek] 来自学校 metadata；
 * - [campusCode] / [buildingCode] / [roomType] 为空表示不过滤；
 * - [sectionCode] 是界面展示的节次组原始编码（如 `1112`），不改变后端请求；
 * - [allSectionCodes] 是查询请求携带的完整节次组编码列表（学校已验证的完整周表协议）。
 */
data class ClassroomQuery(
    val termValue: String = "",
    val timeModeId: String = "",
    val campusCode: String = "",
    val buildingCode: String = "",
    val roomType: String = "",
    val week: Int = 0,
    val dayOfWeek: Int = 0,
    val sectionCode: String = "",
    val allSectionCodes: List<String> = emptyList(),
) {
    /** 已确认条件指纹（不含连接；由调用方补充连接与版本）。 */
    val fingerprint: String
        get() = listOf(
            termValue,
            timeModeId,
            campusCode,
            buildingCode,
            roomType,
            week.toString(),
            dayOfWeek.toString(),
            sectionCode,
            allSectionCodes.joinToString(","),
        ).joinToString("|")
}

/** 元数据：查询条件可选项与默认值。 */
data class ClassroomMetadata(
    val terms: List<ClassroomOption> = emptyList(),
    val campuses: List<ClassroomOption> = emptyList(),
    val buildings: List<ClassroomOption> = emptyList(),
    val roomTypes: List<ClassroomOption> = emptyList(),
    val timeModes: List<ClassroomOption> = emptyList(),
    val weeks: List<ClassroomOption> = emptyList(),
    val days: List<ClassroomOption> = emptyList(),
    val sections: List<ClassroomOption> = emptyList(),
) {
    val ready: Boolean
        get() = terms.isNotEmpty() && timeModes.isNotEmpty() && weeks.isNotEmpty() &&
            days.isNotEmpty() && sections.isNotEmpty()
}

/** 查询响应中的一个节次组；展示时段对第十一节做过校正，原始时钟单独保留供详情请求。 */
data class ClassroomPeriodGroup(
    val rawCode: String = "",
    val label: String = "",
    val startTime: String = "",
    val endTime: String = "",
    val sourceStartTime: String = "",
    val sourceEndTime: String = "",
) {
    val isEleventh: Boolean get() = EleventhSection.isExact(rawCode)
}

/** 单个状态格。 */
enum class ClassroomCellState {
    FREE,
    COURSE,
    BORROWED,
    UNKNOWN,
    NOT_QUERIED,
}

data class ClassroomCell(
    val dayOfWeek: Int = 0,
    val groupIndex: Int = -1,
    val state: ClassroomCellState = ClassroomCellState.NOT_QUERIED,
)

/** 一间教室的一周占用。 */
data class ClassroomRoom(
    val name: String = "",
    val identifier: String = "",
    val seatInfo: String = "",
    val roomType: String = "",
    val cells: List<ClassroomCell> = emptyList(),
) {
    fun cell(dayOfWeek: Int, groupIndex: Int): ClassroomCell? =
        cells.firstOrNull { it.dayOfWeek == dayOfWeek && it.groupIndex == groupIndex }
}

/** 教室查询结果快照。 */
data class ClassroomResult(
    val query: ClassroomQuery = ClassroomQuery(),
    val fetchedAt: Long = 0,
    val periodGroups: List<ClassroomPeriodGroup> = emptyList(),
    val rooms: List<ClassroomRoom> = emptyList(),
    val stale: Boolean = false,
)

/** 占用类型（详情行）。 */
enum class ClassroomOccupancyKind {
    COURSE,
    BORROWED,
    UNKNOWN,
}

/**
 * 一条占用（用于详情请求与结果展示）。
 *
 * [rawSectionCode] 保留后端原始节次组码（如第十一节的 `1112`），展示名与时段另行计算。
 */
data class ClassroomOccupancy(
    val id: String = "",
    val classroom: String = "",
    val campusCode: String = "",
    val buildingCode: String = "",
    val week: Int = 1,
    val dayOfWeek: Int = 1,
    val rawSectionCode: String = "",
    val sectionLabel: String = "",
    val timeLabel: String = "",
    val kind: ClassroomOccupancyKind = ClassroomOccupancyKind.COURSE,
    val courseName: String = "",
    val className: String = "",
    val teacher: String = "",
    val applicant: String = "",
    val remark: String = "",
) {
    /** 详情请求键：连接、学期/周、星期、教室、原始节次组（查询版本由调用方附加）。 */
    fun detailKey(connectionId: String, term: String, queryVersion: Int = 0): String =
        listOf(
            connectionId,
            term,
            week,
            dayOfWeek,
            campusCode,
            buildingCode,
            classroom.ifBlank { id },
            rawSectionCode,
            queryVersion,
        ).joinToString("|")
}

/** 单条占用详情请求：13 字段协议需要的教室、节次组与完整组码列表。 */
data class ClassroomDetailRequest(
    val termValue: String = "",
    val timeModeId: String = "",
    val week: Int = 1,
    val dayOfWeek: Int = 1,
    val campusCode: String = "",
    val buildingCode: String = "",
    val roomId: String = "",
    val roomName: String = "",
    val rawSectionCode: String = "",
    val sectionLabel: String = "",
    val sectionStartTime: String = "",
    val sectionEndTime: String = "",
    val sourceStartTime: String = "",
    val sourceEndTime: String = "",
    val allSectionCodes: List<String> = emptyList(),
    val queryVersion: Int = 0,
) {
    fun key(connectionId: String): String = listOf(
        connectionId,
        termValue,
        week,
        dayOfWeek,
        campusCode,
        buildingCode,
        roomId.ifBlank { roomName },
        rawSectionCode,
        queryVersion,
    ).joinToString("|")
}

/** 一条课程/借用详情。 */
data class ClassroomCourseInfo(
    val kind: ClassroomOccupancyKind = ClassroomOccupancyKind.COURSE,
    val courseName: String = "",
    val roomName: String = "",
    val teacherName: String = "",
    val className: String = "",
    val time: String = "",
    val applicant: String = "",
    val remark: String = "",
)

/** 单条占用的详情结果。 */
data class ClassroomDetail(
    val key: String = "",
    val courses: List<ClassroomCourseInfo> = emptyList(),
)

/**
 * 第十一节兼容规则。
 *
 * 学校后端以 `1112` 表示“第十一节”（20:10–20:55）。展示统一为第十一节；
 * 请求仍使用原始组码。`1111`/短编码只有在同日、包含第 11 节且时间覆盖成立时才算匹配，
 * 不能把所有包含 “11” 的记录都当成第十一节。
 */
object EleventhSection {
    const val RAW_CODE = "1112"
    const val LABEL = "第十一节"
    const val START_TIME = "20:10"
    const val END_TIME = "20:55"
    const val SECTION = 11

    /** 是否为明确的第十一节原始组码。 */
    fun isExact(rawCode: String): Boolean = rawCode.trim() == RAW_CODE

    /** 元数据里的复选框名称是否明确表示第十一节合并组。 */
    fun matchesLabel(label: String): Boolean {
        val text = label.trim()
        return text == "第十一节" ||
            Regex("^第(?:十一二|十一十二|十一[、至\\-—~～]十二|11[、至\\-—~～]12)节$").matches(text)
    }

    /**
     * 兼容匹配：原始组码为 1111/1112，或包含第 11 节且展示时段与
     * 20:10–20:55 有交集。
     */
    fun matches(
        rawCode: String,
        startTime: String = "",
        endTime: String = "",
        sections: List<Int> = emptyList(),
    ): Boolean {
        val code = rawCode.trim()
        if (code == RAW_CODE) return true
        val containsEleven = 11 in sections || code.contains("11")
        if (!containsEleven) return false
        if (code == "1111" && startTime.isEmpty() && endTime.isEmpty()) return true
        val start = minutes(startTime) ?: return code == "1111"
        val end = minutes(endTime) ?: return code == "1111"
        val targetStart = minutes(START_TIME) ?: return false
        val targetEnd = minutes(END_TIME) ?: return false
        return start < targetEnd && end > targetStart
    }

    private fun minutes(text: String): Int? {
        if (!CourseValidator.isTimeValid(text)) return null
        return text.substring(0, 2).toInt() * 60 + text.substring(3, 5).toInt()
    }
}
