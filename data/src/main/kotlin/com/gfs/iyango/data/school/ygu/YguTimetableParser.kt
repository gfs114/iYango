package com.gfs.iyango.data.school.ygu

import com.gfs.iyango.core.model.WeekType

/** 一条从教务课表文本解析出的原始课程安排。 */
internal data class YguEduCourse(
    val name: String = "",
    val teacher: String = "",
    val classroom: String = "",
    val dayOfWeek: Int = 0,
    val startSection: Int = 0,
    val endSection: Int = 0,
    val startWeek: Int = 0,
    val endWeek: Int = 0,
    val weekType: WeekType = WeekType.EVERY,
    val sectionText: String = "",
)

/** 解析提示；不阻断其余可识别课程。 */
internal data class YguIssue(
    val message: String,
    val detail: String = "",
)

internal data class YguParseResult(
    val courses: List<YguEduCourse> = emptyList(),
    val issues: List<YguIssue> = emptyList(),
    /** Only a structurally complete, blank timetable grid can establish an empty result. */
    val verifiedEmpty: Boolean = false,
)

/**
 * 阳光学院课表 HTML 解析器，逐条移植自 HarmonyOS 的 `YguTimetableParser`。
 *
 * 只识别带完整星期表头的表格；合并单元格结构异常、跨星期单元格、节次/周次
 * 无法确定时产生提示并跳过该条，绝不猜测。
 */
internal object YguTimetableParser {

    private class GridCell(
        val node: YguHtmlNode,
        val row: Int,
        val column: Int,
        val rowSpan: Int,
        val colSpan: Int,
    )

    private class GridRow {
        val cells = arrayOfNulls<GridCell>(32)
        var sectionStart: Int = 0
        var sectionEnd: Int = 0
    }

    private class Grid {
        val rows = mutableListOf<GridRow>()
        val originals = mutableListOf<GridCell>()
        var valid = true
    }

    private data class WeekRange(val start: Int, val end: Int, val type: WeekType)

    private val DAY = Regex("^(?:星期|周|礼拜)\\s*([一二三四五六日天])")
    private val SECTION_LABEL = Regex(
        "^第?\\s*([0-9一二三四五六七八九十]{1,3})(?:\\s*([-~～,，、－])\\s*([0-9一二三四五六七八九十]{1,3}))?\\s*节?$",
    )
    private val EXPLICIT_SECTION = Regex(
        "(?:第|\\[|【)?\\s*[0-9一二三四五六七八九十]+[^\\r\\n\\[\\]【】周]*?节",
    )
    private val NON_EMPTY = Regex("\\n\\s*(?:[-—－]{3,}|={3,})\\s*\\n|\\n\\s*\\n")
    private val LOCATION_META = Regex(
        "[;；]\\s*(?=(?:授课教师|教师|老师|上课地点|教室|地点|场地|周次|上课周次|时间)\\s*[:：])",
    )
    private val TIME_LABEL = Regex("(^|[\\r\\n])\\s*时间\\s*[:：]")
    private val WEEK_LINE = Regex(
        "^(?:周次|上课周次)|\\d\\s*(?:[单双]?周|[（(]\\s*[单双周])",
    )
    private val WEEK_NUMBERS = Regex(
        "^\\d{1,2}(?:[-~～－]\\d{1,2})?(?:[,，、]\\d{1,2}(?:[-~～－]\\d{1,2})?)*",
    )
    private val WEEK_MARKERS = Regex(
        "^(?:\\((?:[单双])?周\\)(?:\\([单双](?:周)?\\))?|(?:[单双])?周(?:\\([单双](?:周)?\\))?|\\([单双](?:周)?\\))(?:[单双]周?)?",
    )

    fun parse(html: String): YguParseResult {
        var fallback: YguParseResult? = null
        for (parsed in parseTables(html)) {
            if (parsed.courses.isNotEmpty()) return parsed
            if (fallback == null) fallback = parsed
        }
        fallback?.let { return it }
        return YguParseResult(
            issues = listOf(
                YguIssue("未找到可识别的课表，请确认当前页面已显示完整课表", "需要星期表头和节次行"),
            ),
        )
    }

    fun parseTables(html: String): List<YguParseResult> {
        val results = mutableListOf<YguParseResult>()
        for (table in YguHtml.parse(html).descendants("table")) {
            parseTable(table)?.let { results += it }
        }
        return results
    }

    fun hasTimetable(html: String): Boolean = parse(html).courses.isNotEmpty()

    private fun parseTable(table: YguHtmlNode): YguParseResult? {
        val grid = buildGrid(table)
        val days = LinkedHashMap<Int, Int>()
        var headerRow = -1
        for (row in grid.rows.indices) {
            val candidate = LinkedHashMap<Int, Int>()
            val seen = HashSet<Int>()
            for (column in grid.rows[row].cells.indices) {
                val cell = grid.rows[row].cells[column] ?: continue
                if (cell.colSpan != 1) continue
                val day = dayOf(cell.node.text())
                if (day > 0) {
                    candidate[column] = day
                    seen += day
                }
            }
            if (seen.size >= 5 && seen.size == candidate.size) {
                days.putAll(candidate)
                headerRow = row
                break
            }
        }
        if (headerRow < 0) return null
        if (!grid.valid) {
            return YguParseResult(
                issues = listOf(YguIssue("课表合并单元格结构异常，无法可靠定位课程", "")),
            )
        }
        val firstDayColumn = days.keys.minOrNull() ?: 32
        for (row in headerRow + 1 until grid.rows.size) {
            for (column in 0 until firstDayColumn) {
                val label = grid.rows[row].cells[column] ?: continue
                val sections = sectionLabel(label.node.text())
                if (sections.size == 2) {
                    grid.rows[row].sectionStart = sections[0]
                    grid.rows[row].sectionEnd = sections[1]
                }
            }
        }
        val result = YguParseResult()
        val courses = result.courses.toMutableList()
        val issues = result.issues.toMutableList()
        for (cell in grid.originals) {
            val day = days[cell.column]
            if (day == null || cell.row <= headerRow || cell.node.text().trim().isEmpty()) continue
            if (cell.colSpan != 1) {
                issues += YguIssue("跳过跨越多个星期的课程单元格", "请对照网页确认")
                continue
            }
            val sections = cellSections(grid, cell)
            for (block in blocks(cell.node)) {
                parseBlock(
                    block,
                    day,
                    if (sections.size == 2) sections[0] else 0,
                    if (sections.size == 2) sections[1] else 0,
                    courses,
                    issues,
                )
            }
        }
        val verifiedEmpty = courses.isEmpty() && issues.isEmpty() &&
            isCompleteEmptyGrid(grid, days, headerRow)
        if (courses.isEmpty() && issues.isEmpty() && !verifiedEmpty) {
            issues += YguIssue("课表中没有课程或课程格式尚不支持", "")
        }
        return YguParseResult(courses, issues, verifiedEmpty)
    }

    private fun isCompleteEmptyGrid(grid: Grid, days: Map<Int, Int>, headerRow: Int): Boolean {
        if (headerRow == grid.rows.lastIndex) return false
        // Do not accept weekday-looking prose as evidence of an empty school grid.
        if (days.keys.any { column ->
                val header = grid.rows[headerRow].cells[column] ?: return false
                !Regex("^(?:星期|周|礼拜)\\s*[一二三四五六日天]$").matches(header.node.text().trim())
            }
        ) return false
        var nextSection = 1
        for (rowIndex in headerRow + 1 until grid.rows.size) {
            val row = grid.rows[rowIndex]
            if (row.sectionStart != nextSection || row.sectionEnd < row.sectionStart) return false
            nextSection = row.sectionEnd + 1
            for (column in days.keys) {
                val cell = row.cells[column] ?: return false
                if (cell.row <= headerRow || cell.colSpan != 1 || cell.node.text().trim().isNotEmpty()) {
                    return false
                }
            }
        }
        return true
    }

    private fun buildGrid(table: YguHtmlNode): Grid {
        val result = Grid()
        val rows = table.descendants("tr", stopNestedTables = true)
        if (rows.size > 100) {
            result.valid = false
            return result
        }
        repeat(rows.size) { result.rows += GridRow() }
        for (row in rows.indices) {
            var column = 0
            for (node in rows[row].children) {
                if (node.tag != "td" && node.tag != "th") continue
                while (column < 32 && result.rows[row].cells[column] != null) column++
                val rowSpan = span(node.attribute("rowspan"))
                val colSpan = span(node.attribute("colspan"))
                if (rowSpan < 1 || colSpan < 1 || row + rowSpan > rows.size || column + colSpan > 32) {
                    result.valid = false
                    return result
                }
                val cell = GridCell(node, row, column, rowSpan, colSpan)
                result.originals += cell
                for (offsetRow in 0 until rowSpan) {
                    for (offsetColumn in 0 until colSpan) {
                        val target = result.rows[row + offsetRow]
                        if (target.cells[column + offsetColumn] != null) {
                            result.valid = false
                            return result
                        }
                        target.cells[column + offsetColumn] = cell
                    }
                }
                column += colSpan
            }
        }
        return result
    }

    private fun span(text: String): Int {
        if (text.isEmpty()) return 1
        return if (Regex("^\\d+$").matches(text)) text.toIntOrNull() ?: 0 else 0
    }

    private fun dayOf(text: String): Int {
        val match = DAY.find(text.trim()) ?: return 0
        val character = match.groupValues[1]
        return if (character == "天") 7 else "一二三四五六日".indexOf(character) + 1
    }

    private fun sectionLabel(text: String): List<Int> {
        val match = SECTION_LABEL.matchEntire(text.trim()) ?: return emptyList()
        val first = sectionNumbers(match.groupValues[1])
        val last = if (match.groupValues[3].isEmpty()) first else sectionNumbers(match.groupValues[3])
        if (first.isEmpty() || last.isEmpty()) return emptyList()
        val start = first.first()
        val end = last.last()
        if (match.groupValues[2].isNotEmpty() && Regex("[,，、]").containsMatchIn(match.groupValues[2]) &&
            end != start + 1
        ) {
            return emptyList()
        }
        return if (start >= 1 && end >= start && end <= 20) listOf(start, end) else emptyList()
    }

    private fun sectionNumbers(text: String): List<Int> {
        if (Regex("^\\d{1,2}$").matches(text)) return listOfNotNull(text.toIntOrNull())
        val digits = "一二三四五六七八九"
        val ten = text.indexOf('十')
        if (ten >= 0) {
            val tens = if (ten == 0) 1 else digits.indexOf(text[0]) + 1
            val units = if (ten + 1 == text.length) 0 else digits.indexOf(text[ten + 1]) + 1
            return if (tens >= 1 && units >= 0) listOf(tens * 10 + units) else emptyList()
        }
        val values = mutableListOf<Int>()
        for (character in text) {
            val value = digits.indexOf(character) + 1
            if (value <= 0 || (values.isNotEmpty() && value != values.last() + 1)) return emptyList()
            values += value
        }
        return values
    }

    private fun cellSections(grid: Grid, cell: GridCell): List<Int> {
        val first = grid.rows[cell.row]
        if (first.sectionStart < 1) return emptyList()
        var end = first.sectionEnd
        var previousStart = first.sectionStart
        for (row in cell.row + 1 until cell.row + cell.rowSpan) {
            val next = grid.rows[row]
            if (next.sectionStart == previousStart && next.sectionEnd == end) continue
            if (next.sectionStart != end + 1 || next.sectionEnd < next.sectionStart) return emptyList()
            previousStart = next.sectionStart
            end = next.sectionEnd
        }
        return listOf(first.sectionStart, end)
    }

    private fun blocks(cell: YguHtmlNode): List<String> {
        val lists = cell.descendants("li").filter {
            it.attribute("class").split(Regex("\\s+")).contains("courselists-item")
        }
        val nested = mutableListOf<YguHtmlNode>()
        for (list in lists) nested += list.descendants("div")
        val content = cell.descendants("div").filter { node ->
            node !in nested && node.attribute("class").split(Regex("\\s+")).contains("kbcontent")
        }.toMutableList<YguHtmlNode>().also { it += lists }
        val nodes = if (content.isNotEmpty()) content else listOf(cell)
        val result = mutableListOf<String>()
        for (node in nodes) {
            val normalize = lists.indexOf(node) >= 0
            for (text in labelledText(node, normalize).split(NON_EMPTY)) {
                if (text.trim().isNotEmpty()) result += text.trim()
            }
        }
        return result
    }

    private fun labelledText(node: YguHtmlNode, normalizeWhitespace: Boolean = false): String {
        if (node.hidden() || node.tag == "script" || node.tag == "style") return ""
        if (node.tag == "#text") {
            return if (normalizeWhitespace) node.text().replace(Regex("\\s+"), " ") else node.text()
        }
        if (node.tag == "br") return node.text()
        if (node.tag == "hr") return "\n-----\n"
        val title = node.attribute("title")
        if (title in setOf("老师", "教师", "授课教师", "教室", "地点", "上课地点", "周次", "课程名称")) {
            return "$title：${node.text().trim()}"
        }
        val text = StringBuilder()
        for (child in node.children) text.append(labelledText(child, normalizeWhitespace))
        return if (node.tag == "div" || node.tag == "p") "$text\n" else text.toString()
    }

    private fun parseBlock(
        text: String,
        day: Int,
        startSection: Int,
        endSection: Int,
        output: MutableList<YguEduCourse>,
        issues: MutableList<YguIssue>,
    ) {
        val rawLines = text.split(Regex("[\\r\\n]+")).map { it.trim() }.filter { it.isNotEmpty() }
        val metadata = rawLines.drop(1).joinToString("\n")
            .replace(LOCATION_META, "\n")
            .replace(TIME_LABEL, "$1周次：")
        val lines = if (rawLines.isEmpty()) {
            emptyList()
        } else {
            listOf(rawLines.first()) + metadata.split(Regex("[\\r\\n]+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
        if (lines.isEmpty() || lines.first() in setOf("无", "暂无课程", "-")) return
        val name = lines.first().replace(Regex("^(?:课程名称|课程)\\s*[:：]\\s*"), "").trim()
        var parsedStart = startSection
        var parsedEnd = endSection
        val explicitSections = EXPLICIT_SECTION.findAll(lines.drop(1).joinToString("\n")).toList()
        if (explicitSections.size > 1) {
            issues += YguIssue("跳过包含多段节次、无法可靠确定上课时间的课程", name)
            return
        }
        if (explicitSections.size == 1) {
            val sections = sectionLabel(
                explicitSections.first().value.replace(Regex("[\\[【]"), ""),
            )
            parsedStart = if (sections.size == 2) sections[0] else 0
            parsedEnd = if (sections.size == 2) sections[1] else 0
        }
        if (name.isEmpty() || parsedStart < 1 || parsedEnd < parsedStart || parsedEnd > 20) {
            issues += YguIssue("跳过无法确定名称或节次的课程", name)
            return
        }
        val sectionText = "$parsedStart-$parsedEnd" + "节"
        var ranges = mutableListOf<WeekRange>()
        var weekLine = -1
        for (index in 1 until lines.size) {
            val line = lines[index]
            if (Regex("^(?:授课教师|教师|老师|上课地点|教室|地点|场地)\\s*[:：]").containsMatchIn(line)) {
                continue
            }
            if (!WEEK_LINE.containsMatchIn(line)) continue
            val cleaned = if (explicitSections.isEmpty()) line else line.replace(explicitSections.first().value, "")
            val parsedWeeks = weekLine(cleaned)
            if (parsedWeeks.isEmpty()) {
                ranges = mutableListOf()
                break
            }
            if (weekLine < 0) weekLine = index
            ranges += parsedWeeks
        }
        if (ranges.isEmpty()) {
            issues += YguIssue("跳过缺少有效周次的课程", name)
            return
        }
        var teacher = ""
        var classroom = ""
        for (index in 1 until lines.size) {
            val line = lines[index]
            if (Regex("^(?:授课教师|教师|老师)\\s*[:：]").containsMatchIn(line)) {
                teacher = line.replace(Regex("^(?:授课教师|教师|老师)\\s*[:：]\\s*"), "")
            } else if (Regex("^(?:上课地点|教室|地点|场地)\\s*[:：]").containsMatchIn(line)) {
                classroom = locationOnly(line.replace(Regex("^(?:上课地点|教室|地点|场地)\\s*[:：]\\s*"), ""))
            }
        }
        if (teacher.isEmpty() && lines.size > 1 && Regex("^[\\u4e00-\\u9fff·]{2,8}$").matches(lines[1])) {
            teacher = lines[1]
        }
        if (classroom.isEmpty() && weekLine in 0 until lines.lastIndex) {
            val room = locationOnly(lines[weekLine + 1])
            if (!Regex("周|节|^(?:教师|老师|课程)").containsMatchIn(room)) classroom = room
        }
        for (range in ranges) {
            output += YguEduCourse(
                name = name,
                teacher = teacher,
                classroom = classroom,
                dayOfWeek = day,
                startSection = parsedStart,
                endSection = parsedEnd,
                startWeek = range.start,
                endWeek = range.end,
                weekType = range.type,
                sectionText = sectionText,
            )
        }
    }

    private fun locationOnly(value: String): String = value.split(Regex("[;；]")).first().trim()

    private fun weekLine(line: String): List<WeekRange> {
        var remaining = line.replace(Regex("\\s+"), "")
            .replace("（", "(")
            .replace("）", ")")
            .replace(Regex("^(?:(?:周次(?:\\(节次\\))?|上课周次)[:：])+"), "")
            .replace(Regex("[\\[\\]【】]"), "")
        val ranges = mutableListOf<WeekRange>()
        while (remaining.isNotEmpty()) {
            val expression = WEEK_NUMBERS.matchAt(remaining, 0) ?: return emptyList()
            remaining = remaining.substring(expression.value.length)
            val marker = WEEK_MARKERS.matchAt(remaining, 0) ?: return emptyList()
            if (marker.value.contains('单') && marker.value.contains('双')) return emptyList()
            val parsed = weeks(expression.value, marker.value)
            if (parsed.isEmpty()) return emptyList()
            ranges += parsed
            remaining = remaining.substring(marker.value.length)
            if (remaining.isNotEmpty()) {
                if (!Regex("^[,，、]").containsMatchIn(remaining)) return emptyList()
                remaining = remaining.substring(1)
                if (remaining.isEmpty()) return emptyList()
            }
        }
        return ranges
    }

    private fun weeks(expression: String, marker: String): List<WeekRange> {
        val type = when {
            marker.contains('单') -> WeekType.ODD
            marker.contains('双') -> WeekType.EVEN
            else -> WeekType.EVERY
        }
        val selected = sortedSetOf<Int>()
        for (part in expression.replace(Regex("\\s+"), "").split(Regex("[,，、]"))) {
            val range = part.split(Regex("[-~～－]"))
            if (range.size > 2) return emptyList()
            val start = range[0].toIntOrNull() ?: return emptyList()
            val end = if (range.size == 1) start else range[1].toIntOrNull() ?: return emptyList()
            if (start < 1 || end > 30 || end < start) return emptyList()
            for (week in start..end) {
                if (type == WeekType.EVERY ||
                    (type == WeekType.ODD && week % 2 == 1) ||
                    (type == WeekType.EVEN && week % 2 == 0)
                ) {
                    selected += week
                }
            }
        }
        val values = selected.toList()
        val result = mutableListOf<WeekRange>()
        val step = if (type == WeekType.EVERY) 1 else 2
        for (value in values) {
            val previous = result.lastOrNull()
            if (previous != null && value == previous.end + step) {
                result[result.lastIndex] = previous.copy(end = value)
            } else {
                result += WeekRange(value, value, type)
            }
        }
        return result
    }
}
