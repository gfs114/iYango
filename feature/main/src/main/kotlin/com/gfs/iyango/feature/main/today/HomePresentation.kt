package com.gfs.iyango.feature.main.today

internal fun homeModuleTitle(id: String): String = when (id) {
    "profile" -> "资料卡"
    "today" -> "今日课程"
    "shortcuts" -> "快捷入口"
    "calendar" -> "日历"
    "tomorrow" -> "明日安排"
    "exams" -> "近期考试"
    else -> id
}

internal fun homeShortcutTitle(id: String): String = when (id) {
    "grades" -> "成绩"
    "credit_progress" -> "学分进度"
    "ai_assistant" -> "AI 助理"
    "import_export" -> "导入导出"
    "courses" -> "课程管理"
    "semester" -> "学期管理"
    "schedule" -> "课表"
    "exams" -> "考试安排"
    "classroom" -> "教室查询"
    "timetables" -> "我的课表"
    "school" -> "教务账号"
    "settings_backup" -> "设置备份与重置"
    else -> id
}
