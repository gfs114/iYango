package com.gfs.iyango.data.school.ygu

import java.net.URLEncoder

/**
 * 成绩查询表单识别与编码，行为对齐 HarmonyOS 的 `YguGradesQuery`。
 *
 * 支持两种页面：传统 `form[action=cjcx_list]`，以及课表新版
 * `dataSearch` + `cjcx_table` 的 JSON 分页页。只接受学期（kksj）唯一、
 * 不含密码控件、action 指向成绩列表的页面。
 */
internal object YguGradesQuery {

    private const val MAX_HTML_LENGTH = 1_048_576

    private const val UNSUPPORTED_MESSAGE = "学校成绩查询表单暂不支持，请稍后重试"

    private val SCRIPT_STYLE = Regex(
        "<script\\b[^>]*>[\\s\\S]*?</script\\s*>|<style\\b[^>]*>[\\s\\S]*?</style\\s*>|<!--[\\s\\S]*?-->",
        RegexOption.IGNORE_CASE,
    )
    private val TOKEN = Regex("<!--[\\s\\S]*?-->|<[^>]*>|[^<]+", RegexOption.IGNORE_CASE)
    private val FIELD_WHITESPACE = Regex("[\\t\\r\\n ]+")
    private val NEWLINE_REGEX = Regex("\\r?\\n")
    private val FORM_OPEN_REGEX = Regex("<\\s*form\\b", RegexOption.IGNORE_CASE)
    private val FORM_CLOSE_REGEX = Regex("</\\s*form\\s*>", RegexOption.IGNORE_CASE)

    fun parseForm(html: String, fromUrl: String, requestedTerm: String?): YguGradesQueryForm {
        val empty = YguGradesQueryForm(message = UNSUPPORTED_MESSAGE)
        if (html.isEmpty() || html.length > MAX_HTML_LENGTH || fromUrl.isEmpty()) return empty
        val visible = SCRIPT_STYLE.replace(html, "")
        if (TOKEN.findAll(visible).take(50_000).count() >= 50_000 ||
            FORM_OPEN_REGEX.findAll(visible).count() != FORM_CLOSE_REGEX.findAll(visible).count()
        ) {
            return empty
        }
        val root = YguHtml.parse(visible)
        val forms = root.descendants("form")
        val output = empty.copy(hasForms = forms.isNotEmpty())
        if (isModernPage(fromUrl, root)) return parseModern(html, fromUrl, requestedTerm, root, output)

        val candidates = forms.filter {
            isGradesList(YguSession.resolveUrl(it.attribute("action"), fromUrl))
        }
        if (candidates.size != 1) return output
        val form = candidates[0]
        val method = form.attribute("method").trim().lowercase()
        val encoding = form.attribute("enctype").trim().lowercase()
        if ((method != "get" && method != "post") ||
            (encoding.isNotEmpty() && encoding != "application/x-www-form-urlencoded")
        ) {
            return output
        }
        if (form.descendants("input").any { it.attribute("type").lowercase() == "password" }) {
            return output
        }

        val fields = mutableListOf<YguGradesQueryField>()
        val semesters = mutableListOf<YguSemesterOption>()
        val counts = SelectCount()
        if (!collectFields(form, fields, semesters, counts)) return output
        if (counts.count != 1 || semesters.isEmpty()) return output
        val semesterSelect = form.descendants("select").filter { it.attribute("name") == "kksj" }
        if (semesterSelect.size != 1) return output
        val defaultTerm = selectedOptionValue(semesterSelect[0])
        val targetTerm = requestedTerm ?: defaultTerm ?: semesters.first().value
        if (semesters.none { it.value == targetTerm }) {
            return output.copy(
                message = "学期列表已更新，请重新选择",
                semesters = semesters,
                selectedTerm = defaultTerm ?: semesters.first().value,
            )
        }
        val adjusted = fields.map { field ->
            if (field.name == "kksj") field.copy(value = targetTerm) else field
        }
        if (adjusted.none { it.name == "kksj" }) return output

        var actionUrl = YguSession.resolveUrl(form.attribute("action"), fromUrl)
        val fragment = actionUrl.indexOf('#')
        if (fragment >= 0) actionUrl = actionUrl.substring(0, fragment)
        if (hasQueryFieldConflict(actionUrl, adjusted)) return output
        return output.copy(
            supported = true,
            message = "",
            actionUrl = actionUrl,
            method = method.uppercase(),
            fields = adjusted,
            semesters = semesters,
            selectedTerm = targetTerm,
        )
    }

    fun encodeFields(fields: List<YguGradesQueryField>): String =
        fields.joinToString("&") { "${encode(it.name)}=${encode(it.value)}" }

    fun discoverQueryUrl(html: String, fromUrl: String): String {
        if (html.isEmpty() || html.length > MAX_HTML_LENGTH) return ""
        val visible = SCRIPT_STYLE.replace(html, "")
        if (TOKEN.findAll(visible).take(50_000).count() >= 50_000) return ""
        val root = YguHtml.parse(visible)
        val nodes = root.descendants("iframe") + root.descendants("frame") + root.descendants("a")
        for (node in nodes) {
            val raw = node.attribute(if (node.tag == "a") "href" else "src")
            val resolved = YguSession.resolveUrl(raw, fromUrl)
            if (isGradesQuery(resolved)) return resolved
        }
        return ""
    }

    fun isAllowedPage(url: String): Boolean =
        isGradesForm(url) || isGradesQuery(url) || isGradesList(url)

    fun isGradesList(url: String): Boolean = route(url) == "/jsxsd/kscj/cjcx_list"

    fun isLoginDestination(url: String): Boolean {
        val input = YguHtml.decode(url).trim()
        if (input == "/jsxsd/" || input == "/jsxsd" ||
            input.startsWith("/jsxsd/xk/LoginToXk") ||
            input == YguConfig.ORIGIN + "/jsxsd/" || input == YguConfig.ORIGIN + "/jsxsd"
        ) {
            return true
        }
        val resolved = YguSession.resolveUrl(url, YguConfig.BASE + "/kscj/cjcx_frm")
        val path = resolved.removePrefix(YguConfig.ORIGIN).substringBefore('?')
        return path == "/jsxsd/" || path == "/jsxsd" || path.startsWith("/jsxsd/xk/LoginToXk")
    }

    private fun isModernPage(fromUrl: String, root: YguHtmlNode): Boolean {
        if (route(fromUrl) != "/jsxsd/kscj/cjcx_frm" ||
            !fromUrl.startsWith(YguConfig.ORIGIN + "/jsxsd/kscj/cjcx_frm")
        ) {
            return false
        }
        val forms = root.descendants("form")
        val tables = root.descendants("table")
        val formMatch = forms.count { it.attribute("id") == "dataSearch" && it.attribute("lay-filter") == "search" }
        val tableMatch = tables.count {
            it.attribute("id") == "cjcx_table" &&
                it.attribute("lay-filter") == "test" &&
                it.attribute("data-search-id") == "search"
        }
        return formMatch > 0 || tableMatch > 0
    }

    private fun parseModern(
        html: String,
        fromUrl: String,
        requestedTerm: String?,
        root: YguHtmlNode,
        output: YguGradesQueryForm,
    ): YguGradesQueryForm {
        val forms = root.descendants("form")
        val tables = root.descendants("table")
        if (route(fromUrl) != "/jsxsd/kscj/cjcx_frm" ||
            !fromUrl.startsWith(YguConfig.ORIGIN + "/jsxsd/kscj/cjcx_frm") ||
            forms.size != 1 || tables.size != 1
        ) {
            return output
        }
        val form = forms[0]
        val table = tables[0]
        if (form.attribute("id") != "dataSearch" || form.attribute("lay-filter") != "search" ||
            table.attribute("id") != "cjcx_table" || table.attribute("lay-filter") != "test" ||
            table.attribute("data-search-id") != "search" || form.hasAttribute("method") ||
            (form.hasAttribute("action") && form.attribute("action").trim().isNotEmpty()) ||
            form.descendants("input").any { it.attribute("type").lowercase() == "password" }
        ) {
            return output
        }
        val fields = mutableListOf<YguGradesQueryField>()
        val semesters = mutableListOf<YguSemesterOption>()
        val counts = SelectCount()
        if (!collectFields(form, fields, semesters, counts) || counts.count != 1 ||
            semesters.isEmpty() || !hasUniqueNamedFields(form, fields)
        ) {
            return output
        }
        val semesterSelect = form.descendants("select").filter { it.attribute("name") == "kksj" }
        if (semesterSelect.size != 1) return output
        val defaultTerm = selectedOptionValue(semesterSelect[0]) ?: return output
        val selectedTerm = requestedTerm ?: defaultTerm
        if (semesters.none { it.value == selectedTerm }) {
            return output.copy(
                semesters = semesters,
                selectedTerm = defaultTerm,
                message = "学期列表已更新，请重新选择",
            )
        }
        var foundMode = false
        var foundCrossTerm = false
        val adjusted = fields.map { field ->
            if (field.name == "xsfs" && field.value == "all") foundMode = true
            if (field.name == "sfxsbcxq" && field.value == "1") foundCrossTerm = true
            if (field.name == "kksj") field.copy(value = selectedTerm) else field
        }
        if (!foundMode || !foundCrossTerm || adjusted.any { it.name == "pageNum" || it.name == "pageSize" }) {
            return output
        }
        return output.copy(
            supported = true,
            isJson = true,
            actionUrl = YguConfig.BASE + "/kscj/cjcx_list",
            method = "GET",
            fields = adjusted,
            semesters = semesters,
            selectedTerm = selectedTerm,
            message = "",
        )
    }

    private fun hasUniqueNamedFields(form: YguHtmlNode, fields: List<YguGradesQueryField>): Boolean {
        val names = mutableListOf<String>()
        val controls = form.descendants("input") + form.descendants("select") + form.descendants("textarea")
        for (control in controls) {
            val name = control.attribute("name")
            if (name.isEmpty() || control.hasAttribute("disabled")) continue
            if (name in names) return false
            names += name
        }
        return fields.all { it.name in names }
    }

    private fun collectFields(
        node: YguHtmlNode,
        fields: MutableList<YguGradesQueryField>,
        semesters: MutableList<YguSemesterOption>,
        counts: SelectCount,
    ): Boolean {
        for (control in node.children) {
            when (control.tag) {
                "input" -> if (!appendInput(control, fields)) return false
                "select" -> if (!appendSelect(control, fields, semesters, counts)) return false
                "textarea" -> if (!appendTextarea(control, fields)) return false
                "script", "style", "form" -> Unit
                else -> if (!collectFields(control, fields, semesters, counts)) return false
            }
        }
        return true
    }

    private fun appendInput(input: YguHtmlNode, fields: MutableList<YguGradesQueryField>): Boolean {
        if (input.hasAttribute("disabled")) return true
        val name = input.attribute("name")
        val type = input.attribute("type").lowercase()
        if (name.isEmpty() || type in setOf(
                "password", "button", "submit", "reset", "file", "image",
            )
        ) {
            return true
        }
        if ((type == "checkbox" || type == "radio") && !input.hasAttribute("checked")) return true
        fields += YguGradesQueryField(
            name = name,
            value = if (input.hasAttribute("value")) {
                input.attribute("value")
            } else if (type == "checkbox" || type == "radio") {
                "on"
            } else {
                ""
            },
        )
        return true
    }

    private fun appendSelect(
        select: YguHtmlNode,
        fields: MutableList<YguGradesQueryField>,
        semesters: MutableList<YguSemesterOption>,
        counts: SelectCount,
    ): Boolean {
        if (select.hasAttribute("disabled")) return true
        val name = select.attribute("name")
        if (name.isEmpty()) return true
        if (select.hasAttribute("multiple")) return false
        val enabled = select.descendants("option").filter {
            !it.hasAttribute("disabled") && !it.hidden() && it.hasAttribute("value")
        }
        if (enabled.isEmpty()) return false
        val selected = enabled.filter { it.hasAttribute("selected") }
        if (selected.size > 1) return false
        val chosen = selected.firstOrNull() ?: enabled.first()
        fields += YguGradesQueryField(name, chosen.attribute("value"))
        if (name == "kksj") {
            counts.count++
            for (option in enabled) {
                val entry = YguSemesterOption(
                    value = option.attribute("value"),
                    label = FIELD_WHITESPACE.replace(option.text().trim(), " "),
                )
                if (entry.label.isEmpty()) return false
                if (semesters.any { it.value == entry.value }) return false
                semesters += entry
            }
        }
        return true
    }

    private fun appendTextarea(textarea: YguHtmlNode, fields: MutableList<YguGradesQueryField>): Boolean {
        if (textarea.hasAttribute("disabled") || textarea.attribute("name").isEmpty()) return true
        fields += YguGradesQueryField(
            name = textarea.attribute("name"),
            value = textarea.text().replace(NEWLINE_REGEX, "\r\n"),
        )
        return true
    }

    private fun selectedOptionValue(select: YguHtmlNode): String? {
        val options = select.descendants("option").filter {
            !it.hasAttribute("disabled") && !it.hidden() && it.hasAttribute("value")
        }
        val selected = options.filter { it.hasAttribute("selected") }
        if (selected.size > 1) return null
        return selected.firstOrNull()?.attribute("value") ?: options.firstOrNull()?.attribute("value")
    }

    private fun hasQueryFieldConflict(url: String, fields: List<YguGradesQueryField>): Boolean {
        val queryIndex = url.indexOf('?')
        if (queryIndex < 0) return false
        val query = url.substring(queryIndex + 1).substringBefore('#')
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val rawName = pair.substringBefore('=')
            val name = runCatching {
                java.net.URLDecoder.decode(rawName.replace("+", " "), Charsets.UTF_8.name())
            }.getOrElse { return true }
            if (fields.any { it.name == name }) return true
        }
        return false
    }

    private fun isGradesForm(url: String): Boolean = route(url) == "/jsxsd/kscj/cjcx_frm"

    private fun isGradesQuery(url: String): Boolean = route(url) == "/jsxsd/kscj/cjcx_query"

    private fun route(url: String): String {
        if (!url.startsWith(YguConfig.ORIGIN + "/jsxsd/")) return ""
        val tail = url.removePrefix(YguConfig.ORIGIN)
        val index = tail.indexOfFirst { it == '?' || it == '#' }
        return if (index < 0) tail else tail.substring(0, index)
    }

    /** 与 `encodeURIComponent` 对齐：空格编码为 %20，而非表单的 +。 */
    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())
            .replace("+", "%20")
            .replace("%21", "!")
            .replace("%27", "'")
            .replace("%28", "(")
            .replace("%29", ")")
            .replace("%7E", "~")

    private class SelectCount(var count: Int = 0)
}
