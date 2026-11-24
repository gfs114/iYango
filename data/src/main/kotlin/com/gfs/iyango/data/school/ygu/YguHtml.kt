package com.gfs.iyango.data.school.ygu

/**
 * 极简 HTML 树解析器，行为对齐 HarmonyOS 版的 YguHtml。
 *
 * 只用于读学校页面里的表单/表格/文本；不执行脚本、不跟随外部链接。
 * 解析失败时尽量保留已读节点，由上层按结构缺失分诊。
 */
internal class YguHtmlNode(
    val tag: String,
    val attributes: String = "",
    val value: String = "",
) {
    val children: MutableList<YguHtmlNode> = mutableListOf()

    fun attribute(name: String): String {
        val pattern = Regex(
            "(?:^|\\s)" + Regex.escape(name) +
                "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
            RegexOption.IGNORE_CASE,
        )
        val match = pattern.find(attributes) ?: return ""
        return YguHtml.decode(match.groupValues[1].ifEmpty {
            match.groupValues[2].ifEmpty { match.groupValues[3] }
        })
    }

    fun hasAttribute(name: String): Boolean =
        Regex(
            "(?:^|\\s)" + Regex.escape(name) + "(?:\\s*=|\\s|/|>|$)",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(attributes)

    fun text(): String {
        if (tag == "#text") return YguHtml.decode(value)
        if (tag == "script" || tag == "style" || tag == "input" || hidden()) return ""
        if (tag == "br" || tag == "hr") return "\n"
        val result = StringBuilder()
        for (child in children) result.append(child.text())
        return if (tag == "div" || tag == "p" || tag == "li" || tag == "tr") {
            "$result\n"
        } else {
            result.toString()
        }
    }

    fun hidden(): Boolean =
        Regex("display\\s*:\\s*none", RegexOption.IGNORE_CASE)
            .containsMatchIn(attribute("style")) ||
            attribute("class").split(Regex("\\s+")).contains("kbcontent1")

    fun descendants(tag: String, stopNestedTables: Boolean = false): List<YguHtmlNode> {
        val result = mutableListOf<YguHtmlNode>()
        for (child in children) {
            if (child.hidden() || child.tag == "script" || child.tag == "style") continue
            if (stopNestedTables && child.tag == "table") continue
            if (child.tag == tag) result.add(child)
            result.addAll(child.descendants(tag, stopNestedTables))
        }
        return result
    }
}

internal object YguHtml {

    private val TOKEN = Regex("<!--[\\s\\S]*?-->|<[^>]*>|[^<]+")
    private val CLOSING = Regex("^</\\s*([A-Za-z0-9]+)")
    private val OPENING = Regex("^<\\s*([A-Za-z0-9]+)([\\s\\S]*?)>")
    private val VOID_TAGS = setOf(
        "br", "hr", "input", "img", "meta", "link", "wbr", "source", "frame",
    )

    fun parse(html: String): YguHtmlNode {
        val root = YguHtmlNode("root")
        if (html.length > 2_097_152) return root
        val stack = ArrayDeque<YguHtmlNode>()
        stack.addLast(root)
        var count = 0
        for (match in TOKEN.findAll(html)) {
            if (count++ >= 50_000) break
            val token = match.value
            val current = stack.last()
            if (token.startsWith("</")) {
                val closing = CLOSING.find(token) ?: continue
                val tag = closing.groupValues[1].lowercase()
                for (index in stack.indices.reversed()) {
                    if (index > 0 && stack[index].tag == tag) {
                        while (stack.size > index) stack.removeLast()
                        break
                    }
                }
            } else if (token.startsWith("<")) {
                val opening = OPENING.find(token) ?: continue
                val node = YguHtmlNode(opening.groupValues[1].lowercase(), opening.groupValues[2])
                current.children.add(node)
                if (node.tag !in VOID_TAGS && !token.endsWith("/>") && stack.size < 100) {
                    stack.addLast(node)
                }
            } else {
                current.children.add(YguHtmlNode("#text", "", token))
            }
        }
        return root
    }

    fun decode(text: String): String {
        var result = Regex("&#(x[0-9a-f]+|[0-9]+);", RegexOption.IGNORE_CASE)
            .replace(text) { match ->
                val code = match.groupValues[1]
                val value = if (code.startsWith("x", ignoreCase = true)) {
                    code.substring(1).toIntOrNull(16)
                } else {
                    code.toIntOrNull(10)
                }
                if (value != null && value in 1..1_114_111) String(Character.toChars(value)) else match.value
            }
        result = result.replace(Regex("&nbsp;", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("&quot;", RegexOption.IGNORE_CASE), "\"")
            .replace(Regex("&#39;|&apos;", RegexOption.IGNORE_CASE), "'")
            .replace(Regex("&lt;", RegexOption.IGNORE_CASE), "<")
            .replace(Regex("&gt;", RegexOption.IGNORE_CASE), ">")
            .replace(Regex("&amp;", RegexOption.IGNORE_CASE), "&")
        return result
    }
}
