package com.gfs.iyango.data.school.ygu

/**
 * 成绩表格解析，行为对齐 HarmonyOS 的 `YguGradesParser`。
 *
 * 只认“有课程名称与成绩列”的表格；登录页、评教限制页、系统错误页分别返回对应状态。
 * 表头/单元格数量不一致、成绩页为空却没有明确空态时一律判为不可读，绝不猜半份结果。
 */
internal object YguGradesParser {

    private const val MAX_HTML_LENGTH = 1_048_576

    private val SCRIPT_STYLE = Regex(
        "<script\\b[^>]*>[\\s\\S]*?</script\\s*>|<style\\b[^>]*>[\\s\\S]*?</style\\s*>|<!--[\\s\\S]*?-->",
        RegexOption.IGNORE_CASE,
    )
    private val TOKEN = Regex("<!--[\\s\\S]*?-->|<[^>]*>|[^<]+", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("[\\s\\u3000]+")
    private val CELL_WHITESPACE = Regex("[\\t\\r\\n ]+")
    private val HEADER_NOISE = Regex("[\\s\\u3000:：*]")
    private val EMPTY_TEXT = Regex(
        "暂无成绩|暂无数据|暂无记录|暂无符合条件的记录|没有查询到(?:符合条件的)?(?:成绩|数据|记录)|" +
            "未查询到(?:符合条件的)?(?:成绩|数据|记录)|无相关成绩|noresults|norecords",
        RegexOption.IGNORE_CASE,
    )
    private val LOGIN_PAGE = Regex(
        "<title[^>]*>\\s*登录\\s*</title>|LoginToXkLdap|请先登录系统|name\\s*=\\s*[\"']?userPassword",
        RegexOption.IGNORE_CASE,
    )
    private val RESTRICTED_TEXT = Regex("未完成评教|未评教|未注册|未开通|无权限|权限不足|没有权限|未选课|暂不能查询")
    private val ERROR_TEXT = Regex(
        "系统错误|服务器错误|查询失败|操作失败|发生异常|系统异常|页面不存在|404NotFound|" +
            "500InternalServerError|请求错误",
        RegexOption.IGNORE_CASE,
    )

    fun parse(html: String): YguGradesParseResult {
        if (html.isEmpty() || html.length > MAX_HTML_LENGTH || count(html, "<") > 50_000) {
            return invalid()
        }
        val visibleHtml = SCRIPT_STYLE.replace(html, "")
        if (TOKEN.findAll(visibleHtml).take(50_000).count() >= 50_000) return invalid()
        val root = YguHtml.parse(visibleHtml)
        val visibleText = WHITESPACE.replace(root.text(), "")
        if (LOGIN_PAGE.containsMatchIn(visibleHtml)) {
            return result(YguGradesStatus.LOGIN, "登录状态已失效，请返回教务登录后重试")
        }
        if (RESTRICTED_TEXT.containsMatchIn(visibleText)) {
            return result(YguGradesStatus.RESTRICTED, "学校暂未开放成绩查询，请完成相关事项后重试")
        }
        if (ERROR_TEXT.containsMatchIn(visibleText)) return invalid()
        if (!balancedTables(visibleHtml)) return invalid()

        for (table in root.descendants("table")) {
            val rows = table.descendants("tr", stopNestedTables = true)
            for (index in rows.indices) {
                val header = cellsOf(rows[index])
                val columns = findColumns(header)
                if (columns.courseName < 0 || columns.score < 0) continue
                return readTable(rows, index, columns)
            }
        }
        return invalid()
    }

    private data class Columns(
        val rowNumber: Int = -1,
        val term: Int = -1,
        val courseCode: Int = -1,
        val courseName: Int = -1,
        val offeringUnit: Int = -1,
        val score: Int = -1,
        val scoreFlag: Int = -1,
        val credit: Int = -1,
        val totalHours: Int = -1,
        val gradePoint: Int = -1,
        val retakeTerm: Int = -1,
        val assessmentMethod: Int = -1,
        val examType: Int = -1,
        val courseAttribute: Int = -1,
        val courseType: Int = -1,
        val generalElectiveType: Int = -1,
    )

    private fun readTable(
        rows: List<YguHtmlNode>,
        headerIndex: Int,
        columns: Columns,
    ): YguGradesParseResult {
        val header = cellsOf(rows[headerIndex])
        val records = mutableListOf<YguGradeRow>()
        for (index in headerIndex + 1 until rows.size) {
            val cells = cellsOf(rows[index])
            val rowText = WHITESPACE.replace(nodeText(rows[index]), "")
            if (rowText.isEmpty()) continue
            if (EMPTY_TEXT.containsMatchIn(rowText) && records.isEmpty() &&
                soleEmptyPlaceholder(rows, index, header.size, cells)
            ) {
                return result(YguGradesStatus.EMPTY, "本学期暂无成绩记录")
            }
            if (cells.size == header.size && sameHeader(cells, header)) continue
            if (cells.size != header.size) return invalid()
            val row = YguGradeRow(
                rowNumber = valueAt(cells, columns.rowNumber),
                term = valueAt(cells, columns.term),
                courseCode = valueAt(cells, columns.courseCode),
                courseName = valueAt(cells, columns.courseName),
                offeringUnit = valueAt(cells, columns.offeringUnit),
                score = valueAt(cells, columns.score),
                scoreFlag = valueAt(cells, columns.scoreFlag),
                credit = valueAt(cells, columns.credit),
                totalHours = valueAt(cells, columns.totalHours),
                gradePoint = valueAt(cells, columns.gradePoint),
                retakeTerm = valueAt(cells, columns.retakeTerm),
                assessmentMethod = valueAt(cells, columns.assessmentMethod),
                examType = valueAt(cells, columns.examType),
                courseAttribute = valueAt(cells, columns.courseAttribute),
                courseType = valueAt(cells, columns.courseType),
                generalElectiveType = valueAt(cells, columns.generalElectiveType),
            )
            if (row.courseName.isEmpty() || row.score.isEmpty()) return invalid()
            records += row
        }
        if (records.isEmpty()) return invalid()
        return result(YguGradesStatus.OK, "").copy(records = records)
    }

    private fun findColumns(cells: List<YguHtmlNode>): Columns {
        val labels = cells.map { normalizeHeader(nodeText(it)) }
        fun column(vararg candidates: String): Int {
            for (candidate in candidates) {
                val index = labels.indexOf(candidate)
                if (index >= 0) return index
            }
            return -1
        }
        return Columns(
            rowNumber = column("序号"),
            term = column("学期", "学年学期", "开课学期"),
            courseCode = column("课程代码", "课程编号", "课程号"),
            courseName = column("课程名称", "课程名", "课程"),
            offeringUnit = column("开课单位"),
            score = column("成绩", "总评成绩", "课程成绩", "总成绩"),
            scoreFlag = column("成绩标识"),
            credit = column("学分", "课程学分"),
            totalHours = column("总学时"),
            gradePoint = column("绩点", "课程绩点", "成绩绩点"),
            retakeTerm = column("补重学期"),
            assessmentMethod = column("考核方式"),
            examType = column("考试性质", "考试类型"),
            courseAttribute = column("课程属性"),
            courseType = column("课程性质", "课程类别"),
            generalElectiveType = column("通选课类别"),
        )
    }

    private fun cellsOf(row: YguHtmlNode): List<YguHtmlNode> =
        row.children.filter { (it.tag == "td" || it.tag == "th") && !it.hidden() }

    private fun valueAt(cells: List<YguHtmlNode>, index: Int): String {
        if (index < 0 || index >= cells.size) return ""
        return CELL_WHITESPACE.replace(nodeText(cells[index]).trim(), " ")
    }

    private fun nodeText(node: YguHtmlNode): String {
        if (node.tag == "#text") return YguHtml.decode(node.value)
        if (node.hidden() || node.tag == "script" || node.tag == "style" || node.tag == "input") return ""
        if (node.tag == "br") return " "
        val builder = StringBuilder()
        for (child in node.children) {
            if (child.tag != "table") builder.append(nodeText(child))
        }
        return builder.toString()
    }

    private fun normalizeHeader(value: String): String = HEADER_NOISE.replace(value, "")

    private fun sameHeader(cells: List<YguHtmlNode>, header: List<YguHtmlNode>): Boolean {
        for (index in cells.indices) {
            if (normalizeHeader(nodeText(cells[index])) != normalizeHeader(nodeText(header[index]))) {
                return false
            }
        }
        return true
    }

    private fun soleEmptyPlaceholder(
        rows: List<YguHtmlNode>,
        rowIndex: Int,
        headerLength: Int,
        cells: List<YguHtmlNode>,
    ): Boolean {
        if (cells.size != 1 || cells[0].attribute("colspan") != headerLength.toString()) return false
        for (index in rowIndex + 1 until rows.size) {
            if (WHITESPACE.replace(nodeText(rows[index]), "").isNotEmpty()) return false
        }
        return true
    }

    private fun balancedTables(html: String): Boolean {
        for (tag in listOf("table", "tr", "td", "th")) {
            val opening = count(html, "<\\s*$tag\\b")
            val closing = count(html, "</\\s*$tag\\s*>")
            if (opening != closing || (tag == "table" && opening == 0)) return false
        }
        return true
    }

    private fun count(text: String, pattern: String): Int =
        Regex(pattern, RegexOption.IGNORE_CASE).findAll(text).count()

    private fun result(status: YguGradesStatus, message: String) =
        YguGradesParseResult(status = status, message = message)

    private fun invalid() =
        result(YguGradesStatus.INVALID, "成绩页面暂不可读取，请稍后重试")
}
