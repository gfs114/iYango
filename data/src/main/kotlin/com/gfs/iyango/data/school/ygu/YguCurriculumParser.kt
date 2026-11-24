package com.gfs.iyango.data.school.ygu

import com.gfs.iyango.core.model.CurriculumCourseItem
import com.gfs.iyango.core.model.CurriculumNatureGroup
import com.gfs.iyango.core.model.CurriculumProgressSnapshot
import com.gfs.iyango.core.model.CurriculumStudyStatus
import com.gfs.iyango.core.model.deduplicateCoursesByName
import com.gfs.iyango.core.model.deduplicateCurriculumCourses
import com.gfs.iyango.core.model.parseCreditBigDecimal
import java.math.BigDecimal

/**
 * 解析阳光学院「专业培养方案及教学计划 / 学业完成情况」(`xxwcqkOnkclb.do?isdb=0`) HTML。
 *
 * - 解析 `.mod-total-area` 的方案标题、各课程性质的已修/正修读学分与总合计；
 * - 解析每个 `.mod-item-detail` 下的 `.sub-table` 8 列课程明细；
 * - 明确忽略隐藏的 `<div class="header-content-area" style="display: none">`（教务后端该隐藏块把正修读学分错写为学分要求）。
 */
internal object YguCurriculumParser {

    private data class SummaryCredits(
        val nature: String,
        val earnedCredits: BigDecimal,
        val inProgressCredits: BigDecimal,
    )

    private val PUA_ICON_REGEX = Regex("[\\uE000-\\uF8FF]")
    private val WHITESPACE_REGEX = Regex("[\\s\\u00A0]+")

    fun parse(
        html: String,
        fetchedAtEpochMillis: Long = 0L,
    ): CurriculumProgressSnapshot? {
        if (html.isBlank() || YguSession.isLoginPage(html)) return null
        val root = YguHtml.parse(html)

        val totalArea = findDescendantsByClass(root, "mod-total-area").firstOrNull()
        val detailBlocks = findDescendantsByClass(root, "mod-item-detail")
        if (totalArea == null && detailBlocks.isEmpty()) return null

        var planTitle = ""
        val summaryList = mutableListOf<SummaryCredits>()
        var totalEarnedCredits: BigDecimal? = null
        var totalInProgressCredits: BigDecimal? = null

        if (totalArea != null) {
            planTitle = findDescendantsByClass(totalArea, "header-item-text")
                .firstOrNull()
                ?.let { cleanText(it.text()) }
                .orEmpty()
            val totalListNode = findDescendantsByClass(totalArea, "total-list").firstOrNull()
            if (totalListNode != null) {
                val rows = findDescendantsByClass(totalListNode, "list-tr")
                for (row in rows) {
                    val cells = rowCells(row, "list-td")
                    if (cells.size < 3) continue
                    val col0 = cleanText(cells[0].text())
                    val col1 = parseCreditBigDecimal(cleanText(cells[1].text()))
                    val col2 = parseCreditBigDecimal(cleanText(cells[2].text()))
                    if (hasClass(row, "total-tr") || col0 == "合计") {
                        totalEarnedCredits = col1
                        totalInProgressCredits = col2
                    } else if (col0.isNotEmpty()) {
                        summaryList += SummaryCredits(
                            nature = col0,
                            earnedCredits = col1 ?: BigDecimal.ZERO,
                            inProgressCredits = col2 ?: BigDecimal.ZERO,
                        )
                    }
                }
            }
        }

        val summaryByNature = summaryList.associateBy { it.nature }
        val coursesByNature = LinkedHashMap<String, List<CurriculumCourseItem>>()
        for (block in detailBlocks) {
            val natureTitle = extractDetailNatureTitle(block)
            val subTable = findDescendantsByClass(block, "sub-table").firstOrNull() ?: continue
            val rows = findDescendantsByClass(subTable, "list-tr")
            val parsedCourses = mutableListOf<CurriculumCourseItem>()
            for (row in rows) {
                val cells = rowCells(row, "list-td")
                if (cells.size < 7) continue
                val courseCode = cleanText(cells[0].text())
                val courseName = cleanText(cells[1].text())
                if (courseCode.isEmpty() && courseName.isEmpty()) continue
                val creditText = cleanText(cells[2].text())
                val credit = parseCreditBigDecimal(creditText) ?: BigDecimal.ZERO
                val courseAttribute = cleanText(cells[3].text())
                val rowNature = cleanText(cells[4].text()).ifEmpty { natureTitle }
                val studyStatus = CurriculumStudyStatus.fromRawText(cleanText(cells[5].text()))
                val scoreText = cleanText(cells[6].text())
                val isPassed = hasClass(cells[6], "is-pass") ||
                    (studyStatus == CurriculumStudyStatus.COMPLETED && scoreText.isNotEmpty())
                val remark = if (cells.size >= 8) cleanText(cells[7].text()) else ""
                parsedCourses += CurriculumCourseItem(
                    courseCode = courseCode,
                    courseName = courseName,
                    credit = credit,
                    creditText = creditText,
                    courseAttribute = courseAttribute,
                    courseNature = rowNature,
                    studyStatus = studyStatus,
                    scoreText = scoreText,
                    isPassed = isPassed,
                    remark = remark,
                )
            }
            val resolvedNature = natureTitle.ifEmpty {
                parsedCourses.firstOrNull()?.courseNature.orEmpty()
            }
            if (resolvedNature.isNotEmpty()) {
                coursesByNature[resolvedNature] = deduplicateCurriculumCourses(parsedCourses)
            }
        }

        if (summaryList.isEmpty() && coursesByNature.isEmpty()) return null

        val orderedNatures = linkedSetOf<String>()
        summaryList.forEach { orderedNatures += it.nature }
        coursesByNature.keys.forEach { orderedNatures += it }

        val groups = orderedNatures.map { nature ->
            val courses = coursesByNature[nature].orEmpty()
            val summary = summaryByNature[nature]
            val earned = summary?.earnedCredits ?: courses
                .asSequence()
                .filter { it.studyStatus == CurriculumStudyStatus.COMPLETED }
                .fold(BigDecimal.ZERO) { acc, item -> acc.add(item.credit) }
            val inProgress = summary?.inProgressCredits ?: courses
                .asSequence()
                .filter { it.studyStatus == CurriculumStudyStatus.IN_PROGRESS }
                .fold(BigDecimal.ZERO) { acc, item -> acc.add(item.credit) }
            CurriculumNatureGroup(
                courseNature = nature,
                earnedCredits = earned.stripTrailingZeros(),
                inProgressCredits = inProgress.stripTrailingZeros(),
                courses = courses,
            )
        }

        val resolvedTotalEarned = (totalEarnedCredits ?: groups.fold(BigDecimal.ZERO) { acc, g ->
            acc.add(g.earnedCredits)
        }).stripTrailingZeros()
        val resolvedTotalInProgress = (totalInProgressCredits ?: groups.fold(BigDecimal.ZERO) { acc, g ->
            acc.add(g.inProgressCredits)
        }).stripTrailingZeros()

        return CurriculumProgressSnapshot(
            planTitle = planTitle,
            totalEarnedCredits = resolvedTotalEarned,
            totalInProgressCredits = resolvedTotalInProgress,
            groups = groups,
            fetchedAtEpochMillis = fetchedAtEpochMillis,
        ).deduplicateCoursesByName()
    }

    private fun extractDetailNatureTitle(block: YguHtmlNode): String {
        val titleArea = findDescendantsByClass(block, "header-content-title").firstOrNull() ?: return ""
        val boldTitle = findDescendantsByClass(titleArea, "jClassName")
            .firstOrNull { it.tag == "b" }
            ?: titleArea.descendants("b").firstOrNull()
        val raw = boldTitle?.text() ?: return ""
        return cleanText(raw)
    }

    private fun rowCells(row: YguHtmlNode, cellClass: String): List<YguHtmlNode> {
        val direct = row.children.filter { !it.hidden() && hasClass(it, cellClass) }
        return if (direct.isNotEmpty()) direct else findDescendantsByClass(row, cellClass)
    }

    private fun findDescendantsByClass(
        node: YguHtmlNode,
        className: String,
    ): List<YguHtmlNode> {
        val result = mutableListOf<YguHtmlNode>()
        for (child in node.children) {
            if (child.hidden() || child.tag == "script" || child.tag == "style") continue
            if (hasClass(child, className)) result += child
            result += findDescendantsByClass(child, className)
        }
        return result
    }

    private fun hasClass(node: YguHtmlNode, className: String): Boolean {
        val raw = node.attribute("class")
        if (raw.isBlank()) return false
        return raw.split(WHITESPACE_REGEX).any { it == className }
    }

    private fun cleanText(raw: String): String =
        raw.replace(PUA_ICON_REGEX, "")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
}
