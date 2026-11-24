package com.gfs.iyango.data.school.ygu

/** 脱敏后的教务个人资料。 */
internal data class YguStudentProfile(
    val name: String = "",
    val className: String = "",
    val studentId: String = "",
)

internal class YguProfileRead(
    val profile: YguStudentProfile = YguStudentProfile(),
    val authenticated: Boolean = false,
    val expired: Boolean = false,
    val cookieHeader: String = "",
    val message: String = "",
)

/**
 * 个人资料读取：优先学籍卡片页，其次首页卡片。
 *
 * 只提取姓名/学号/班级三个展示字段；任何其他个人信息都留在页面里。
 * 遇到登录页或 Cookie 消失即判定会话失效。
 */
internal object YguProfile {

    private const val PROFILE_URL = "${YguConfig.BASE}/grxx/xsxx"
    private val SCRIPT_BLOCK = Regex("<script\\b[^>]*>[\\s\\S]*?</script\\s*>", RegexOption.IGNORE_CASE)
    private val STYLE_BLOCK = Regex("<style\\b[^>]*>[\\s\\S]*?</style\\s*>", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("\\s")
    private val MULTI_WHITESPACE = Regex("\\s+")
    private val INLINE_LABEL_VALUE = Regex("(?:^|\\s)([\\u4e00-\\u9fa5]{2,12})\\s*[:：]\\s*")
    private val HOME_CARD_PAIR = Regex("^([^:：]+)[:：]\\s*(.+)$")
    private val LABEL_STRIP = Regex("[\\s:：]")
    private val STUDENT_ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{1,31}$")
    private val CLASS_INVALID_CHARS = Regex("[\\r\\n<>:：]")
    private val NAME_PATTERN = Regex("^[\\u4e00-\\u9fa5·]{2,20}$")
    private val FAMILY_KEYWORDS = Regex("家庭成员|家庭情况")

    suspend fun fetchProfile(cookieHeader: String): YguProfileRead {
        val jar = YguCookieJar()
        if (jar.importHeader(cookieHeader).isNotEmpty() || !jar.has("bzb_jsxsd")) {
            return YguProfileRead(
                expired = true,
                message = "登录状态已失效，请重新登录",
            )
        }
        var profile = YguStudentProfile()
        var authenticated = false
        var expired = false
        var message = "个人信息暂未获取，请稍后重试"
        var currentCookie = jar.header()
        try {
            for (url in listOf(PROFILE_URL, YguConfig.PROFILE_HOME)) {
                val referer = if (url == YguConfig.PROFILE_HOME) YguConfig.HOME else YguConfig.WRAPPER
                val response = YguHttp.get(url, currentCookie, referer)
                jar.absorb(response.setCookies)
                currentCookie = jar.header()
                val landing = response.location.substringBefore('?')
                val resolved = YguSession.resolveUrl(response.location, url)
                if (YguSession.isLoginPage(response.body) || response.status == 401 ||
                    !jar.has("bzb_jsxsd") ||
                    (response.status in 300..399 && (
                        landing == YguConfig.LOGIN || landing == YguConfig.BASE ||
                            landing == "/jsxsd/" || landing == "/jsxsd" ||
                            resolved.contains("/xk/LoginToXk")
                        ))
                ) {
                    return YguProfileRead(
                        expired = true,
                        message = "登录状态已失效，请重新登录",
                    )
                }
                if (response.status == 200 && response.body.isNotEmpty()) {
                    profile = merge(profile, extractProfile(response.body))
                    authenticated = authenticated || profile.name.isNotEmpty() ||
                        profile.studentId.isNotEmpty() || YguSession.isAuthenticated(response.body)
                    message = if (profile.name.isNotEmpty() && profile.className.isNotEmpty() &&
                        profile.studentId.isNotEmpty()
                    ) {
                        ""
                    } else {
                        "个人信息未完整获取，空白项以学校实际返回为准，可点击重新获取"
                    }
                    if (message.isEmpty()) break
                } else if (!authenticated && (response.status == 0 || response.status >= 500)) {
                    message = "个人信息暂未获取，请稍后再试"
                }
            }
        } finally {
            jar.clear()
        }
        return YguProfileRead(profile, authenticated, expired, currentCookie, message)
    }

    fun extractProfile(html: String): YguStudentProfile {
        val empty = YguStudentProfile()
        if (YguSession.isLoginPage(html) || html.length > 2_097_152) return empty
        val visible = html
            .replace(SCRIPT_BLOCK, "")
            .replace(STYLE_BLOCK, "")
        val root = YguHtml.parse(visible)
        var profile = empty
        for (table in root.descendants("table")) {
            var familySection = false
            for (row in table.descendants("tr", stopNestedTables = true)) {
                val cells = cellsOf(row)
                if (isFamilyRow(cells) || cells.any { cell ->
                        cell.descendants("table").isEmpty() &&
                            FAMILY_KEYWORDS.containsMatchIn(cell.text().replace(WHITESPACE, ""))
                    }
                ) {
                    familySection = true
                }
                if (familySection) continue
                for (index in cells.indices) {
                    if (cells[index].descendants("table").isNotEmpty()) continue
                    val label = normalizedLabel(cells[index].text())
                    if (index + 1 < cells.size && cells[index + 1].descendants("table").isEmpty()) {
                        profile = assign(profile, label, cellValue(cells[index + 1]))
                    }
                    val text = cells[index].text().trim()
                    var match = INLINE_LABEL_VALUE.find(text)
                    while (match != null) {
                        val start = match.range.last + 1
                        val next = INLINE_LABEL_VALUE.find(text, start)
                        val value = text.substring(start, next?.range?.first ?: text.length).trim()
                        profile = assign(profile, match.groupValues[1], value)
                        match = next
                    }
                }
            }
        }
        return merge(profile, extractHomeCard(root))
    }

    fun extractName(html: String): String = extractProfile(html).name

    private fun extractHomeCard(root: YguHtmlNode): YguStudentProfile {
        val divs = root.descendants("div")
        val titles = divs.filter { node ->
            node.attribute("class").split(MULTI_WHITESPACE).contains("infoContentTitle")
        }
        if (titles.size != 1) return YguStudentProfile()
        val title = titles[0].text().trim()
        val separator = title.indexOf('-')
        if (separator < 0) return YguStudentProfile()
        var profile = assign(YguStudentProfile(), "姓名", title.substring(0, separator).trim())
        profile = assign(profile, "学号", title.substring(separator + 1).trim())
        if (profile.name.isEmpty() || profile.studentId.isEmpty()) return YguStudentProfile()
        val classes = mutableListOf<String>()
        for (node in divs) {
            if (!node.attribute("class").split(MULTI_WHITESPACE).contains("qz-detailtext")) continue
            val pair = HOME_CARD_PAIR.find(node.text().trim()) ?: continue
            val candidate = assign(YguStudentProfile(), normalizedLabel(pair.groupValues[1]), pair.groupValues[2].trim())
            if (candidate.className.isNotEmpty() && candidate.className !in classes) {
                classes.add(candidate.className)
            }
        }
        return profile.copy(className = if (classes.size == 1) classes[0] else "")
    }

    private fun merge(profile: YguStudentProfile, incoming: YguStudentProfile): YguStudentProfile {
        if (profile.name.isEmpty() && profile.studentId.isEmpty() &&
            (profile.className.isEmpty() || incoming.name.isNotEmpty() || incoming.studentId.isNotEmpty())
        ) {
            return incoming
        }
        if ((profile.name.isNotEmpty() && incoming.name.isNotEmpty() && profile.name != incoming.name) ||
            (profile.studentId.isNotEmpty() && incoming.studentId.isNotEmpty() &&
                profile.studentId != incoming.studentId)
        ) {
            return profile
        }
        if (!((profile.studentId.isNotEmpty() && profile.studentId == incoming.studentId) ||
                (profile.name.isNotEmpty() && profile.name == incoming.name))
        ) {
            return profile
        }
        return profile.copy(
            name = profile.name.ifEmpty { incoming.name },
            studentId = profile.studentId.ifEmpty { incoming.studentId },
            className = profile.className.ifEmpty { incoming.className },
        )
    }

    private fun normalizedLabel(text: String): String = text.replace(LABEL_STRIP, "")

    private fun cellValue(cell: YguHtmlNode): String {
        val text = cell.text().trim()
        if (text.isNotEmpty()) return text
        val inputs = cell.descendants("input")
        if (inputs.size == 1 && inputs[0].attribute("type").lowercase() in setOf("", "text")) {
            return inputs[0].attribute("value").trim()
        }
        return ""
    }

    private fun assign(profile: YguStudentProfile, label: String, value: String): YguStudentProfile {
        val ignored = setOf(
            "姓名", "性别", "学号", "班级", "所在班级", "行政班级", "姓名拼音",
            "与本人关系", "本人关系", "关系", "工作单位", "基本信息", "学籍卡片",
        )
        if (value.isEmpty() || normalizedLabel(value) in ignored) return profile
        return when {
            label == "姓名" && profile.name.isEmpty() && looksLikeName(value) ->
                profile.copy(name = value)
            label == "学号" && profile.studentId.isEmpty() &&
                STUDENT_ID_PATTERN.matches(value) ->
                profile.copy(studentId = value)
            label in setOf("班级", "所在班级", "行政班级") && profile.className.isEmpty() &&
                value.length <= 64 && !CLASS_INVALID_CHARS.containsMatchIn(value) ->
                profile.copy(className = value)
            else -> profile
        }
    }

    private fun isFamilyRow(cells: List<YguHtmlNode>): Boolean {
        val relations = setOf("与本人关系", "本人关系", "关系")
        for (index in cells.indices) {
            val compact = cells[index].text().replace(WHITESPACE, "")
            if (compact.isEmpty()) continue
            if (compact in relations) return true
            if (index > 0 && compact in setOf("父亲", "母亲", "本人")) return true
        }
        return false
    }

    private fun looksLikeName(value: String): Boolean =
        value.length in 2..20 && NAME_PATTERN.matches(value)

    private fun cellsOf(row: YguHtmlNode): List<YguHtmlNode> =
        row.children.filter { it.tag == "td" || it.tag == "th" }
}
