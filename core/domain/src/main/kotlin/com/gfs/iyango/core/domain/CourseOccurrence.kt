package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.Course
import java.security.MessageDigest
import java.time.LocalDate

/**
 * 课程在具体某一天的一次"发生"（occurrence）。
 *
 * 这是今日页、周课表、提醒、日历与桌面组件的**唯一数据来源**：任何展示面都不得
 * 自行用 [Course] 推导某天是否有课，必须经过 [ResolveScheduleOccurrences]。
 *
 * @property occurrenceId 稳定实例身份，生成规则见 [stableId]。
 * @property course 来源课程（保留原始身份字段，含 id/overrideOfId）。
 * @property date 实际发生日期（跨周移动后是目标日期）。
 * @property startMinute 展示开始钟点（当日分钟数）；null 表示未知，必须显式呈现为
 *   "时间待定"，禁止静默套默认作息。
 * @property endMinute 展示结束钟点（当日分钟数）；null 语义同上。
 * @property isOverride 是否来自单次调整（`overrideOfId > 0` 的课程产生的实例）。
 * @property anchorDate 原始发生锚点日期；未移动时等于 [date]。跨周移动后仍指向原日期，
 *   用于提醒/日历定位旧系统记录。
 * @property isMoved 是否由跨周/跨日移动产生。
 * @property isTemporary 是否来自临时整天课表。
 */
data class CourseOccurrence(
    val occurrenceId: String,
    val course: Course,
    val date: LocalDate,
    val startMinute: Int?,
    val endMinute: Int?,
    val isOverride: Boolean,
    val anchorDate: LocalDate = date,
    val isMoved: Boolean = false,
    val isTemporary: Boolean = false,
) {
    companion object {
        /**
         * 实例的稳定身份 id（P5 提醒/日历契约的雏形）。
         *
         * 生成规则：
         * ```
         * occurrenceId = SHA-256(courseStableKey + "|" + date.toString()) 前 16 个十六进制字符
         * courseStableKey = course.id > 0
         *     ? "c:$courseId"
         *     : "g:${timetableId}:${dayOfWeek}:${startSection}:${endSection}:${name}"
         * ```
         *
         * 关键约束——**单次调课（换时间/换教室）不改变 occurrenceId**：调整课程的
         * `overrideOfId` 保证其 stableKey 解析到被调整原课程的 id，即输出 override 实例时
         * 使用 `"c:$overrideOfId"` 与该周实际日期计算，因此调整前后同一实例身份不变，
         * 下游（提醒、日历事件）可以安全地把 override 视为对同一实例的就地更新。
         *
         * 身份未消歧约束——同一基础课在同一周若存在多条单次调整（`overrideOfId` 与
         * `overrideWeek` 均相同的多条记录），它们会解析出**相同的 occurrenceId**
         * （公式只含基础课身份与日期，不含调整记录自身 id，无法区分彼此）。
         * 契约决定：P2 课程保存路径必须强制"每 `(overrideOfId, overrideWeek)` 至多一条"
         * （编辑器校验 + P2-01 数据库唯一索引）来保证身份唯一；P5 提醒/日历依赖此前提，
         * 在其落地前禁止把重复 identity 当作可区分实例处理。
         */
        fun stableId(course: Course, date: LocalDate): String {
            val stableKey = stableCourseKey(course)
            return sha256Hex16("$stableKey|$date")
        }

        /**
         * 以原始发生锚点计算身份。
         *
         * 跨周移动时，源发生与目标发生使用**同一个** occurrenceId（锚点仍是源日期），
         * 这样提醒/日历可以更新旧系统记录而不是追加一条新记录。
         */
        fun stableIdForAnchor(course: Course, anchorDate: LocalDate): String {
            val stableKey = stableCourseKey(course)
            return sha256Hex16("$stableKey|$anchorDate")
        }

        private fun stableCourseKey(course: Course): String = when {
            // 单次调整引用被调整原课程的身份，保证 occurrenceId 跨调整稳定。
            course.overrideOfId > 0 -> "c:${course.overrideOfId}"
            course.id > 0 -> "c:${course.id}"
            else ->
                "g:${course.timetableId}:${course.dayOfWeek}:${course.startSection}:" +
                    "${course.endSection}:${course.name}"
        }

        /** SHA-256 的前 16 个小写十六进制字符（UTF-8 编码输入）。 */
        private fun sha256Hex16(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            val builder = StringBuilder(digest.size * 2)
            for (byte in digest) {
                val value = byte.toInt() and 0xFF
                builder.append("0123456789abcdef"[value ushr 4])
                builder.append("0123456789abcdef"[value and 0x0F])
            }
            return builder.substring(0, 16)
        }
    }
}
