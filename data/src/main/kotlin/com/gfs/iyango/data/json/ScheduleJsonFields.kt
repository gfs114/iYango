package com.gfs.iyango.data.json

/**
 * 读取其他课程表应用导出的文件时接受的字段名候选列表。
 *
 * 逐条对齐参考实现 `ScheduleJson.ets` 的 `ScheduleJsonFields`：解析按候选顺序取
 * **首个**存在的键，因此同一对象里同时出现多个键时以列表靠前者为准。
 */
object ScheduleJsonFields {

    /** 根对象里课程数组的候选键。 */
    val ROOT_COURSE_ARRAYS: List<String> = listOf("courses", "course", "list", "lessons", "data")

    val NAME: List<String> = listOf("name", "courseName", "course_name", "title")
    val TEACHER: List<String> = listOf("teacher", "teacherName", "jsxm", "tutor")
    val CLASSROOM: List<String> = listOf("classroom", "room", "location", "place", "cdmc")
    val DAY: List<String> = listOf("dayOfWeek", "day_of_week", "weekday", "xqj", "day")
    val START_SECTION: List<String> = listOf("startSection", "start_section", "sectionStart", "beginSection")
    val END_SECTION: List<String> = listOf("endSection", "end_section", "sectionEnd", "finishSection")

    /** 合并形式的节次文本（如 "1-2"、"[01-02]"、"1"）。 */
    val SECTION: List<String> = listOf("section", "sections", "jcs")

    val START_WEEK: List<String> = listOf("startWeek", "start_week", "weekStart", "beginWeek")
    val END_WEEK: List<String> = listOf("endWeek", "end_week", "weekEnd", "finishWeek")

    /** 周次文本（如 "1-16(单)"、"1-16周"、"1,3,5"）。 */
    val WEEKS: List<String> = listOf("weeks", "weekText", "zcd", "weekRange")
    val WEEK_TYPE: List<String> = listOf("weekType", "week_type", "oddEven", "type")
    val COLOR: List<String> = listOf("color", "colour", "backgroundColor")
    val REMARK: List<String> = listOf("remark", "note", "comment", "memo")

    /** 根对象里学期对象的候选键。 */
    val SEMESTER_OBJECTS: List<String> = listOf("semester", "term")
    val SEMESTER_NAME: List<String> = listOf("name", "semesterName", "title")
    val SEMESTER_START: List<String> = listOf("startDate", "start_date", "beginDate", "start")
    val SEMESTER_WEEKS: List<String> = listOf("totalWeeks", "total_week", "weeks", "weekCount")
    val SEMESTER_CURRENT: List<String> = listOf("isCurrent", "is_current", "current")

    /** 根对象里作息数组的候选键。 */
    val TIME_SLOT_ARRAYS: List<String> = listOf("timeSlots", "time_slots", "sections", "times")
    val SLOT_SECTION: List<String> = listOf("section", "index", "no")
    val SLOT_START: List<String> = listOf("startTime", "start_time", "start", "begin")
    val SLOT_END: List<String> = listOf("endTime", "end_time", "end", "finish")
}
