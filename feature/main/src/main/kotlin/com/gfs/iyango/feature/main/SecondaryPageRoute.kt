package com.gfs.iyango.feature.main

import com.gfs.iyango.feature.main.mine.MineCategory

/**
 * 二级/三级页路由契约（多 Activity 架构 P2）。
 *
 * 与原单 Activity `AppNavHost` 的路由字符串一一对应；[parse] 供 `:app` 写入
 * `SecondaryPageActivity` Intent Extra（extra 键与原 `SavedStateHandle` 键完全一致）。
 *
 * 参数规则：缺失或空值回退默认（与原 NavHost `defaultValue` 语义一致）；
 * 出现但非法的参数值，以及未知/白名单外的基础路由，一律返回 `null` 拒收。
 */
sealed interface SecondaryPageRoute {

    /** 规范化路由字符串（写 Intent Extra 后由 [parse] 幂等还原）。 */
    val route: String

    /** `"editor?courseId={courseId}&copyFromId={copyFromId}"`；0 = 新建。 */
    data class Editor(
        val courseId: Long = 0L,
        val copyFromId: Long = 0L,
    ) : SecondaryPageRoute {
        override val route: String = "editor?courseId=$courseId&copyFromId=$copyFromId"
    }

    /** `"courseraw/{timetableId}"`。 */
    data class CourseRaw(val timetableId: Long = 0L) : SecondaryPageRoute {
        override val route: String = "courseraw/$timetableId"
    }

    /** `"display?perTimetable={perTimetable}"`。 */
    data class Display(val perTimetable: Boolean = false) : SecondaryPageRoute {
        override val route: String = "display?perTimetable=$perTimetable"
    }

    /** `"classroom"` 或带预填时段的 `"classroom?week=..."`。 */
    data class Classroom(
        val week: Int = 0,
        val dayOfWeek: Int = 0,
        val startSection: Int = 0,
        val endSection: Int = 0,
    ) : SecondaryPageRoute {
        override val route: String = if (week == 0 && dayOfWeek == 0 && startSection == 0 && endSection == 0) {
            "classroom"
        } else {
            "classroom?week=$week&day=$dayOfWeek&startSection=$startSection&endSection=$endSection"
        }
    }

    /** `"aiconfig/detail/{profileId}"`；三级页，单个 AI 服务商详情配置（原生跨 Activity 预测返回）。 */
    data class AiProviderDetail(val profileId: String) : SecondaryPageRoute {
        override val route: String = "aiconfig/detail/$profileId"
    }

    /** 无参白名单路由（含 mine 系分类页）。 */
    data class Plain(val id: String) : SecondaryPageRoute {
        override val route: String = id
    }

    companion object {

        private val PLAIN_ROUTES: Set<String> = buildSet {
            MineCategory.entries.forEach { add(it.route) }
            addAll(
                listOf(
                    "courses", "school", "schoolsync", "synchistory", "exams",
                    "timetable", "timetablecompare", "semester", "homesettings", "wallpaper", "font", "liquidglass",
                    "dockglass", "dockglass/dev", "reminder", "importexport", "aiimport", "aiconfig", "aiassistant",
                    "scheduleexport", "semestersummary", "settingsbackup", "creditprogress",
                    "about", "appupdate", "runtimelogs",
                ),
            )
        }

        /** 解析路由字符串；未知/非法路由（含非法参数值）返回 null。 */
        fun parse(raw: String?): SecondaryPageRoute? {
            if (raw.isNullOrBlank()) return null
            val trimmed = raw.trim()
            val base = trimmed.substringBefore('?')
            val query = trimmed.substringAfter('?', "")
            return when {
                base == "editor" -> {
                    val params = queryMap(query)
                    Editor(
                        courseId = strictLong(params, "courseId") ?: return null,
                        copyFromId = strictLong(params, "copyFromId") ?: return null,
                    )
                }
                base == "courseraw" -> if (query.isEmpty()) CourseRaw(0L) else null
                base.startsWith("courseraw/") -> {
                    // 仅允许干净的 courseraw/{timetableId} 路径，带多余查询串或空白段一律拒收。
                    if (query.isNotEmpty()) return null
                    val segment = base.substringAfter('/')
                    CourseRaw(timetableId = segment.toLongOrNull() ?: return null)
                }
                base == "display" -> {
                    val params = queryMap(query)
                    Display(perTimetable = strictBoolean(params, "perTimetable") ?: return null)
                }
                base == "classroom" -> {
                    val params = queryMap(query)
                    Classroom(
                        week = strictInt(params, "week") ?: return null,
                        dayOfWeek = strictInt(params, "day") ?: return null,
                        startSection = strictInt(params, "startSection") ?: return null,
                        endSection = strictInt(params, "endSection") ?: return null,
                    )
                }
                base.startsWith("aiconfig/detail/") -> {
                    if (query.isNotEmpty()) return null
                    val segment = base.removePrefix("aiconfig/detail/")
                    val valid = segment.isNotBlank() && segment.all { it.isLetterOrDigit() || it == '-' || it == '_' }
                    if (valid) AiProviderDetail(segment) else null
                }
                else -> if (query.isEmpty() && base in PLAIN_ROUTES) Plain(base) else null
            }
        }

        /** 解析查询串为键值映射（空串/无等号的项忽略）。 */
        private fun queryMap(query: String): Map<String, String> =
            if (query.isEmpty()) emptyMap()
            else query.split('&').mapNotNull { pair ->
                val index = pair.indexOf('=')
                if (index <= 0) null else pair.substring(0, index) to pair.substring(index + 1)
            }.toMap()

        /** 可选 Long 参数：缺失或空值回退默认；出现但非法返回 null（整体拒收）。 */
        private fun strictLong(params: Map<String, String>, key: String): Long? {
            val value = params[key] ?: return 0L
            if (value.isBlank()) return 0L
            return value.toLongOrNull()
        }

        /** 可选 Int 参数：缺失或空值回退默认；出现但非法返回 null（整体拒收）。 */
        private fun strictInt(params: Map<String, String>, key: String): Int? {
            val value = params[key] ?: return 0
            if (value.isBlank()) return 0
            return value.toIntOrNull()
        }

        /** 可选 Boolean 参数：缺失或空值回退默认；出现但非法返回 null（整体拒收）。 */
        private fun strictBoolean(params: Map<String, String>, key: String): Boolean? {
            val value = params[key] ?: return false
            if (value.isBlank()) return false
            return value.toBooleanStrictOrNull()
        }
    }
}
