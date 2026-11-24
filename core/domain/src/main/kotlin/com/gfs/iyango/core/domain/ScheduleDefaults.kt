package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.TimeSlot

/**
 * 课表数据的默认值（学期管理页"恢复默认"与新建课表播种共用）。
 *
 * 11 节作息与 data 层 DemoSeeder 的 SECTION_TIMES 逐行一致；放 core:domain
 * 是因为 feature 层只允许依赖领域端口/模型，不允许直接依赖 data。
 */
object ScheduleDefaults {

    /** 默认 11 节作息（节次顺序 → 起止钟点）。 */
    private val SECTION_TIMES = listOf(
        "08:00" to "08:45",
        "08:55" to "09:40",
        "10:00" to "10:45",
        "10:55" to "11:40",
        "14:00" to "14:45",
        "14:55" to "15:40",
        "16:00" to "16:45",
        "16:55" to "17:40",
        "19:00" to "19:45",
        "19:55" to "20:40",
        "20:50" to "21:35",
    )

    /**
     * 构造指定课表的默认作息时间表（全新 id，section 1..11）。
     */
    fun defaultTimeSlots(timetableId: Long): List<TimeSlot> =
        SECTION_TIMES.mapIndexed { index, (start, end) ->
            TimeSlot(
                id = 0,
                timetableId = timetableId,
                section = index + 1,
                startTime = start,
                endTime = end,
                name = "第${index + 1}节",
                kind = 0,
            )
        }
}
