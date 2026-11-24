package com.gfs.iyango.core.domain

/**
 * 时间轴的一行：节次标签 + 该节钟点。
 *
 * @property section 节次号（1..）。
 * @property label 节次标签：作息中的节名（"第N节"归一为 N），无节名时用节次号。
 * @property start 该节开始时间（HH:mm）；空表示作息缺失。
 * @property end 该节结束时间（HH:mm）；空表示作息缺失。
 * @property southStart 南北作息不一致时南区的开始时间；与 [northStart] 同时非空表示并列显示。
 * @property northStart 南北作息不一致时北区的开始时间。
 */
data class TimeRailRow(
    val section: Int,
    val label: String,
    val start: String = "",
    val end: String = "",
    val southStart: String = "",
    val northStart: String = "",
) {
    /** 该节是否需要并列南北两套时间。 */
    val showsCampusSplit: Boolean get() = southStart.isNotEmpty() && northStart.isNotEmpty()
}
