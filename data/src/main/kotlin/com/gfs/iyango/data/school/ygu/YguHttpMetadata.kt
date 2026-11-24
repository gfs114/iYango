package com.gfs.iyango.data.school.ygu

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * HTTP 版课表元数据读取，行为对齐 HarmonyOS 的 `YguHttpMetadata`。
 *
 * 在入口页里找到唯一一个 action 指向课表端点、且学期/模板合法的表单，
 * 读取学期、模板、`zc` 与学期下拉，构建不带周次过滤的整表地址。
 * 多个不同作用域返回 `multiple`，登录页/无表单返回 `login`/`unknown`。
 */
internal object YguHttpMetadata {

    private val TERM = Regex("^\\d{4}-\\d{4}-[1-3]$")
    private val TEMPLATE = Regex("^[-A-Za-z0-9_]{1,128}$")
    private val CONTROL = Regex("[\\u0000-\\u001f\\u007f]")

    fun parse(html: String, pageUrl: String): YguTimetableMetadata {
        val result = YguTimetableMetadata()
        val unique = LinkedHashMap<String, YguTimetableMetadata>()
        val root = YguHtml.parse(html)
        for (form in root.descendants("form")) {
            val action = form.attribute("action").trim()
            if (action.isEmpty()) continue
            val actionUrl = YguSession.resolveUrl(action, pageUrl)
            if (actionUrl.split('?')[0] != YguConfig.COURSE_ENTRY) continue
            val term = fieldValue(form, "xnxq01id")
            val template = fieldValue(form, "kbjcmsid")
            if (!TERM.matches(term) || !TEMPLATE.matches(template)) continue
            val week = fieldValue(form, "zc")
            val sourceWeek = queryParam(pageUrl, "zc")
            val labels = fieldValue(form, "xsfMapListJsonStr")
            val options = optionValues(form, "xnxq01id")
            val metadata = YguTimetableMetadata(
                status = "timetable",
                source = pageUrl,
                schoolTerm = term,
                templateId = template,
                allWeeks = week.isEmpty() && sourceWeek.isEmpty(),
                semesters = options.map { it.first }.distinct().take(64),
                semesterLabels = options.toMap(),
                labels = labels,
                navigateUrl = navigateUrl(term, template, labels),
            )
            unique["$term|$template|${if (metadata.allWeeks) "1" else "0"}"] = metadata
        }
        if (unique.size > 1) {
            return result.copy(status = "multiple")
        }
        unique.values.firstOrNull()?.let { return it }
        return result.copy(status = if (YguSession.isLoginPage(html)) "login" else "unknown")
    }

    /** 不带周次过滤的整表地址，参数顺序与学校脚本一致。 */
    fun navigateUrl(term: String, template: String, labels: String): String {
        var url = YguConfig.COURSE_ENTRY +
            "?viweType=0&showallprint=0&showkchprint=0" +
            "&showkink=0&showfzmprint=0&baseUrl=" + encode("/jsxsd")
        if (labels.isNotEmpty() && labels.length <= 4096 && !CONTROL.containsMatchIn(labels)) {
            url += "&xsfMapListJsonStr=" + encode(labels)
        }
        return url + "&xnxq01id=" + encode(term) + "&zc=&kbjcmsid=" + encode(template)
    }

    private fun fieldValue(form: YguHtmlNode, name: String): String {
        val candidates = form.descendants("input") +
            form.descendants("select") +
            form.descendants("textarea")
        val found = candidates.filter { it.attribute("name") == name }
        if (found.size != 1) return ""
        val node = found.first()
        if (node.tag == "select") return selectValue(node)
        return node.attribute("value")
    }

    private fun selectValue(select: YguHtmlNode): String {
        val options = select.descendants("option")
        options.firstOrNull { it.hasAttribute("selected") }?.let { return optionValue(it) }
        return options.firstOrNull()?.let { optionValue(it) }.orEmpty()
    }

    private fun optionValue(option: YguHtmlNode): String =
        if (option.hasAttribute("value")) option.attribute("value") else option.text()

    private fun optionValues(form: YguHtmlNode, name: String): List<Pair<String, String>> {
        val terms = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()
        for (select in form.descendants("select")) {
            if (select.attribute("name") != name) continue
            val options = select.descendants("option")
            // 页面预选的学期排在最前，调用方无需再猜“当前学期”。
            val ordered = options.firstOrNull { it.hasAttribute("selected") }
                ?.let { selected -> listOf(selected) + options.filter { it !== selected } }
                ?: options
            for (option in ordered) {
                val value = option.attribute("value")
                if (!TERM.matches(value) || !seen.add(value) || terms.size >= 64) continue
                val label = option.text().trim().ifEmpty { value }
                terms += value to label
            }
        }
        return terms
    }

    /**
     * 子页面的学期/模板/完整周次不得与父页面矛盾。
     */
    fun scopeMatches(html: String, url: String, schoolTerm: String, templateId: String): Boolean {
        val query = url.indexOf('?')
        if (query >= 0) {
            val body = url.substring(query + 1).substringBefore('#')
            for (part in body.split('&')) {
                if (part.isEmpty()) continue
                val separator = part.indexOf('=')
                val key = decode(if (separator < 0) part else part.substring(0, separator)) ?: return false
                val value = decode(if (separator < 0) "" else part.substring(separator + 1)) ?: return false
                if (!scopeValueMatches(key, value, schoolTerm, templateId)) return false
            }
        }
        val root = YguHtml.parse(html)
        val fields = root.descendants("input") + root.descendants("select") + root.descendants("textarea")
        for (field in fields) {
            val value = when (field.tag) {
                "select" -> selectValue(field)
                "textarea" -> field.text()
                else -> field.attribute("value")
            }
            if (!scopeValueMatches(field.attribute("name"), value, schoolTerm, templateId)) return false
        }
        return true
    }

    private fun scopeValueMatches(key: String, value: String, schoolTerm: String, templateId: String): Boolean =
        when (key) {
            "zc" -> value.isEmpty()
            "xnxq01id" -> value == schoolTerm
            "kbjcmsid" -> value == templateId
            else -> true
        }

    fun queryParam(url: String, key: String): String {
        val query = url.indexOf('?')
        if (query < 0) return ""
        for (part in url.substring(query + 1).substringBefore('#').split('&')) {
            val separator = part.indexOf('=')
            val name = decode(if (separator < 0) part else part.substring(0, separator)) ?: continue
            if (name != key) continue
            return decode(if (separator < 0) "" else part.substring(separator + 1)).orEmpty()
        }
        return ""
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun decode(value: String): String? =
        runCatching {
            URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        }.getOrNull()
}
