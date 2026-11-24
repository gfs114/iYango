package com.gfs.iyango.data.school.ygu

/** 学校会话判定与同站 URL 归一化。 */
internal object YguSession {

    private val LOGIN_PAGE = Regex(
        "<title[^>]*>\\s*登录\\s*</title>|LoginToXkLdap|请先登录系统|name\\s*=\\s*[\"']?userPassword",
        RegexOption.IGNORE_CASE,
    )
    private val AUTHENTICATED = Regex(
        "(?:href|src)\\s*=\\s*[\"'][^\"']*(?:method=exit|logout|Logout|kbxx_xzb_ljjy\\.jsp)",
        RegexOption.IGNORE_CASE,
    )
    private val CONTENT_CLASS = Regex("class\\s*=\\s*[\"'][^\"']*\\bkbcontent\\b", RegexOption.IGNORE_CASE)

    fun isLoginPage(body: String): Boolean = LOGIN_PAGE.containsMatchIn(body)

    fun isAuthenticated(body: String): Boolean {
        if (isLoginPage(body)) return false
        return AUTHENTICATED.containsMatchIn(body) || CONTENT_CLASS.containsMatchIn(body)
    }

    /**
     * 把页面里的链接规范成学校站点内的绝对地址；任何越界/编码绕过都返回空串。
     */
    fun resolveUrl(link: String, fromUrl: String): String {
        val input = YguHtml.decode(link).trim()
        if (input.isEmpty() || Regex("[\\\\\\r\\n\\u0000-\\u0020]").containsMatchIn(input)) return ""
        val absolute: String = when {
            input.startsWith("https://") -> input
            input.startsWith("//") -> "https:$input"
            Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(input) -> return ""
            input.startsWith("/") -> YguConfig.ORIGIN + input
            else -> {
                val path = fromUrl.substringBefore('?').substringBefore('#')
                path.substring(0, path.lastIndexOf('/') + 1) + input
            }
        }
        if (!absolute.startsWith("${YguConfig.ORIGIN}/jsxsd/")) return ""
        val tail = absolute.substring(YguConfig.ORIGIN.length)
        val queryIndex = tail.indexOfFirst { it == '?' || it == '#' }
        val pathname = if (queryIndex < 0) tail else tail.substring(0, queryIndex)
        val suffix = if (queryIndex < 0) "" else tail.substring(queryIndex)
        if (Regex("%(?:2e|2f|5c|25|[01][0-9a-f]|7f)", RegexOption.IGNORE_CASE).containsMatchIn(pathname)) {
            return ""
        }
        val segments = ArrayDeque<String>()
        for (part in pathname.split('/')) {
            if (part == "..") {
                segments.removeLastOrNull()
            } else if (part.isNotEmpty() && part != ".") {
                segments.addLast(part)
            }
        }
        val normalized = "${YguConfig.ORIGIN}/${segments.joinToString("/")}$suffix"
        return if (normalized.startsWith("${YguConfig.ORIGIN}/jsxsd/")) normalized else ""
    }

    fun contentLinks(body: String, fromUrl: String): List<String> {
        val root = YguHtml.parse(body)
        val nodes = root.descendants("iframe") + root.descendants("frame") + root.descendants("a")
        val links = mutableListOf<String>()
        for (node in nodes) {
            val raw = node.attribute(if (node.tag == "a") "href" else "src")
            if (!raw.contains("kbxx") && !raw.contains("/kbcx/")) continue
            val url = resolveUrl(raw, fromUrl)
            if (url.isNotEmpty() && url !in links) links.add(url)
        }
        return links.take(3)
    }
}
