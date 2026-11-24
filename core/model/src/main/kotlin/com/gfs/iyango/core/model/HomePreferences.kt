package com.gfs.iyango.core.model

/** Persisted IDs never depend on display text or navigation routes. Append new modules here. */
enum class HomeModule(val id: String) {
    PROFILE("profile"), TODAY("today"), SHORTCUTS("shortcuts"), CALENDAR("calendar"), TOMORROW("tomorrow"), EXAMS("exams");
}

/** Only destinations implemented by the app are registered. */
enum class HomeShortcut(val id: String) {
    GRADES("grades"), IMPORT_EXPORT("import_export"), COURSES("courses"), SEMESTER("semester"),
    SCHEDULE("schedule"), EXAMS("exams"), CLASSROOM("classroom"), TIMETABLES("timetables"), SCHOOL("school"), SETTINGS_BACKUP("settings_backup"),
    CREDIT_PROGRESS("credit_progress"), AI_ASSISTANT("ai_assistant");
}

data class HomePreferences(
    val moduleOrder: List<String> = HomeModule.entries.map { it.id },
    val hiddenModules: Set<String> = emptySet(),
    val quickActions: List<String> = listOf("grades", "import_export", "courses", "semester"),
    val version: Int = 1,
) {
    fun sanitized(): HomePreferences {
        if (version != 1) return HomePreferences()
        val modules = HomeModule.entries.map { it.id }
        val shortcuts = HomeShortcut.entries.map { it.id }.toSet()
        return copy(
            moduleOrder = (moduleOrder.filter { it in modules } + modules).distinct(),
            hiddenModules = hiddenModules.intersect(modules.toSet()),
            quickActions = quickActions.filter { it in shortcuts }.distinct().take(8),
        )
    }
    fun isVisible(module: HomeModule): Boolean = module.id !in hiddenModules
}
