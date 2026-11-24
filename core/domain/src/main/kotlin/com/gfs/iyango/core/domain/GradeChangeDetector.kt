package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.GradeRecord
import java.util.Locale

/** 单门成绩变更类型。 */
enum class GradeChangeKind {
    NEWLY_PUBLISHED,
    SCORE_UPDATED,
}

/** 单门成绩变更详情。 */
data class GradeChangeItem(
    val record: GradeRecord,
    val kind: GradeChangeKind,
    val previousScoreText: String = "",
    val dedupeKey: String,
)

/** 受影响最新学期的绩点变动摘要。 */
data class GradeGpaDeltaSummary(
    val semester: String,
    val beforeTermGpa: Double?,
    val afterTermGpa: Double?,
    val deltaTermGpa: Double?,
    val afterCumulativeGpa: Double?,
)

/** 拟推送的新出分通知载荷。 */
data class GradeChangeNotificationPayload(
    val accountKey: String,
    val partition: String,
    val items: List<GradeChangeItem>,
    val gpaSummary: GradeGpaDeltaSummary?,
    val summaryTitle: String,
    val expandedLines: List<String>,
    val publicLockscreenText: String,
    val dedupeKeys: Set<String>,
)

/**
 * 新出分变动检测与 GPA 差值计算器（纯 JVM）。
 *
 * - 首次建立基线（`hadBaseline == false`）一律返回 null，绝不误报；
 * - 对 `*` 全学期分区自动从 `schoolRecordId` 解析真实学期后再按学期统计 GPA；
 * - 通知文案统一换行分段，绝不使用中圆点分隔符。
 */
object GradeChangeDetector {

    private const val MAX_PREVIEW_LINES = 4

    fun detect(
        accountKey: String,
        partition: String,
        hadBaseline: Boolean,
        previousRecords: List<GradeRecord>,
        newRecords: List<GradeRecord>,
        alreadyNotifiedKeys: Set<String>,
        includeOptional: Boolean = false,
    ): GradeChangeNotificationPayload? {
        if (!hadBaseline || accountKey.isBlank()) return null

        val normalizedPrev = previousRecords.map(::normalizeSemester)
        val normalizedNew = newRecords.map(::normalizeSemester)
        val prevByKey = normalizedPrev.associateBy { it.stableKey }

        val items = mutableListOf<GradeChangeItem>()
        val dedupeKeys = linkedSetOf<String>()

        for (record in normalizedNew) {
            val newScore = record.scoreText.trim()
            if (newScore.isEmpty()) continue
            val dedupeKey = "${record.stableKey}|$newScore"
            if (dedupeKey in alreadyNotifiedKeys) continue

            val old = prevByKey[record.stableKey]
            val oldScore = old?.scoreText?.trim().orEmpty()
            val kind = when {
                old == null || oldScore.isEmpty() -> GradeChangeKind.NEWLY_PUBLISHED
                oldScore != newScore -> GradeChangeKind.SCORE_UPDATED
                else -> null
            } ?: continue

            items += GradeChangeItem(
                record = record,
                kind = kind,
                previousScoreText = if (kind == GradeChangeKind.SCORE_UPDATED) oldScore else "",
                dedupeKey = dedupeKey,
            )
            dedupeKeys += dedupeKey
        }

        if (items.isEmpty()) return null

        val gpaSummary = computeGpaDelta(
            partition = partition,
            items = items,
            previousRecords = normalizedPrev,
            newRecords = normalizedNew,
            includeOptional = includeOptional,
        )

        val summaryTitle = buildTitle(items)
        val expandedLines = buildExpandedLines(items, gpaSummary)

        return GradeChangeNotificationPayload(
            accountKey = accountKey,
            partition = partition,
            items = items,
            gpaSummary = gpaSummary,
            summaryTitle = summaryTitle,
            expandedLines = expandedLines,
            publicLockscreenText = "有新的成绩发布，解锁查看详情",
            dedupeKeys = dedupeKeys,
        )
    }

    /** 兼容全学期 `*` 分区：优先保留自身真实学期，否则从 `ygu:<semester>|...` 中提取。 */
    fun resolveRealSemester(record: GradeRecord): String {
        val raw = record.semester.trim()
        if (raw.isNotEmpty() && raw != GradeRepositoryPort.ALL_SEMESTERS) return raw
        if (record.schoolRecordId.startsWith("ygu:")) {
            val extracted = record.schoolRecordId.removePrefix("ygu:").substringBefore('|').trim()
            if (extracted.isNotEmpty() && extracted != GradeRepositoryPort.ALL_SEMESTERS) return extracted
        }
        return raw
    }

    private fun normalizeSemester(record: GradeRecord): GradeRecord {
        val resolved = resolveRealSemester(record)
        return if (resolved == record.semester) record else record.copy(semester = resolved)
    }

    private fun computeGpaDelta(
        partition: String,
        items: List<GradeChangeItem>,
        previousRecords: List<GradeRecord>,
        newRecords: List<GradeRecord>,
        includeOptional: Boolean,
    ): GradeGpaDeltaSummary? {
        val affectedSemester = items
            .map { it.record.semester }
            .filter { it.isNotBlank() && it != GradeRepositoryPort.ALL_SEMESTERS }
            .maxOrNull()
            ?: partition.takeUnless { it == GradeRepositoryPort.ALL_SEMESTERS }
            ?: return null

        val beforeTermRecords = previousRecords.filter { it.semester == affectedSemester }
        val afterTermRecords = newRecords.filter { it.semester == affectedSemester }

        val beforeTermGpa = if (beforeTermRecords.isNotEmpty()) {
            GradeStatisticsCalculator.calculate(beforeTermRecords, includeOptional).averageGradePoint
        } else {
            null
        }
        val afterTermGpa = if (afterTermRecords.isNotEmpty()) {
            GradeStatisticsCalculator.calculate(afterTermRecords, includeOptional).averageGradePoint
        } else {
            null
        }
        val delta = if (beforeTermGpa != null && afterTermGpa != null) {
            afterTermGpa - beforeTermGpa
        } else {
            null
        }
        val afterCumulative = if (partition == GradeRepositoryPort.ALL_SEMESTERS) {
            GradeStatisticsCalculator.calculate(newRecords, includeOptional).averageGradePoint
        } else {
            null
        }
        if (afterTermGpa == null && afterCumulative == null) return null
        return GradeGpaDeltaSummary(
            semester = affectedSemester,
            beforeTermGpa = beforeTermGpa,
            afterTermGpa = afterTermGpa,
            deltaTermGpa = delta,
            afterCumulativeGpa = afterCumulative,
        )
    }

    private fun buildTitle(items: List<GradeChangeItem>): String {
        val newCount = items.count { it.kind == GradeChangeKind.NEWLY_PUBLISHED }
        val updateCount = items.count { it.kind == GradeChangeKind.SCORE_UPDATED }
        return when {
            updateCount == 0 -> "新增 $newCount 门成绩"
            newCount == 0 -> if (updateCount == 1) "成绩变动通知" else "更新 $updateCount 门成绩"
            else -> "更新 ${items.size} 门成绩"
        }
    }

    private fun buildExpandedLines(
        items: List<GradeChangeItem>,
        gpaSummary: GradeGpaDeltaSummary?,
    ): List<String> {
        val lines = mutableListOf<String>()
        items.take(MAX_PREVIEW_LINES).forEach { item ->
            val name = item.record.courseName.ifBlank { "未命名课程" }
            val score = item.record.scoreText.trim()
            val line = when (item.kind) {
                GradeChangeKind.NEWLY_PUBLISHED -> "$name $score"
                GradeChangeKind.SCORE_UPDATED -> "$name ${item.previousScoreText} → $score"
            }
            lines += line
        }
        if (items.size > MAX_PREVIEW_LINES) {
            lines += "等共 ${items.size} 门课程"
        }
        if (gpaSummary != null) {
            val summary = buildString {
                if (gpaSummary.afterTermGpa != null) {
                    append("${gpaSummary.semester} 学期绩点 ${formatGpa(gpaSummary.afterTermGpa)}")
                    gpaSummary.deltaTermGpa?.let { d ->
                        append(" (${formatDelta(d)})")
                    }
                }
                if (gpaSummary.afterCumulativeGpa != null) {
                    if (isNotEmpty()) append(" ")
                    append("总绩点 ${formatGpa(gpaSummary.afterCumulativeGpa)}")
                }
            }
            if (summary.isNotEmpty()) lines += summary
        }
        return lines
    }

    private fun formatGpa(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun formatDelta(delta: Double): String =
        if (delta >= 0.0) String.format(Locale.US, "+%.2f", delta)
        else String.format(Locale.US, "%.2f", delta)
}
