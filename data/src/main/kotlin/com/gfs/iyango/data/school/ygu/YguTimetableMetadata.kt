package com.gfs.iyango.data.school.ygu

/**
 * 从教务课表入口页解析出的查询作用域。
 *
 * 与 HarmonyOS 版逐字段一致：学期、模板、是否全部周次与页面构建的整表地址。
 * [semesters] 保留页面学期下拉的实际后端值，[semesterLabels] 是同一批选项的展示文字。
 */
internal data class YguTimetableMetadata(
    val status: String = "unknown",
    val source: String = "",
    val navigateUrl: String = "",
    val schoolTerm: String = "",
    val templateId: String = "",
    val allWeeks: Boolean = false,
    val semesters: List<String> = emptyList(),
    val semesterLabels: Map<String, String> = emptyMap(),
    /** 原始 `xsfMapListJsonStr`，构建其他学期地址时原样回传。 */
    val labels: String = "",
)

/** 读取一次课表原始的 HTML 快照（未解析）。 */
internal data class YguTimetablePayload(
    val body: String,
    val url: String,
)

/** 直接读取课表的结果：元数据 + 原始页面，或面向用户的失败原因。 */
internal data class YguDirectRead(
    val metadata: YguTimetableMetadata = YguTimetableMetadata(),
    val payload: YguTimetablePayload? = null,
    val errorMessage: String = "",
    /** 失败原因是登录态失效（调用方应清除会话并回到登录）。 */
    val expired: Boolean = false,
)

/** 读取课表入口页元数据的结果。 */
internal data class YguMetadataRead(
    val metadata: YguTimetableMetadata = YguTimetableMetadata(),
    val errorMessage: String = "",
    val expired: Boolean = false,
)
