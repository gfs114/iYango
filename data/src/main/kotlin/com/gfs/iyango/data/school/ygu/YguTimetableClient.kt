package com.gfs.iyango.data.school.ygu

/**
 * 阳光学院课表 HTTP 客户端，行为对齐 HarmonyOS 的 `YguTimetableClient`。
 *
 * 一次读取从入口页拿到学期/模板，再用不带 `zc` 的整表地址读取完整课表；
 * 子页面（iframe/frame）按同源、作用域一致、最多 16 页 / 4 层收集。
 * 任何重定向、登录页或 Cookie 丢失都按登录态失效返回，不重试、不猜测。
 */
internal class YguTimetableClient(
    private val httpGet: suspend (String, String, String) -> YguHttpResponse = { url, cookie, referer ->
        YguHttp.get(url, cookie, referer)
    },
) {

    constructor() : this({ url, cookie, referer -> YguHttp.get(url, cookie, referer) })

    private val cookieJar = YguCookieJar()
    private var generation = 0

    /**
     * 读取入口页的学期/模板元数据（用于学期下拉），不做整表读取。
     */
    suspend fun readMetadata(cookieHeader: String): YguMetadataRead {
        clearSession()
        val generation = this.generation
        val opened = open(cookieHeader)
        if (opened != null) return opened
        if (!isCurrent(generation)) return cancelled()
        val entryUrl = YguConfig.COURSE_ENTRY + "?viweType=0"
        val entry = try {
            get(entryUrl)
        } catch (error: Exception) {
            return failed("无法读取教务页面，请检查网络后重试")
        }
        if (!isCurrent(generation)) return cancelled()
        val check = responseError(entry)
        if (check.message.isNotEmpty()) {
            return YguMetadataRead(errorMessage = check.message, expired = check.expired)
        }
        val metadata = YguHttpMetadata.parse(entry.body, entryUrl)
        return when (metadata.status) {
            "timetable" -> YguMetadataRead(metadata = metadata)
            "multiple" -> YguMetadataRead(
                errorMessage = "页面出现多个课表入口，请改用官方网页登录",
            )
            "login" -> YguMetadataRead(expired = true, errorMessage = "登录状态已失效，请重新登录")
            else -> YguMetadataRead(errorMessage = "课表入口未能识别，请改用官方网页登录")
        }
    }

    /**
     * 读取指定学期的完整课表；命中有效 [cachedMetadata] 时跳过入口页直接拉取整表（失败自动回退入口页）。
     *
     * @param term 学校实际学期值（来自入口页下拉）；必须存在于页面提供的选项中。
     */
    suspend fun readTimetable(
        cookieHeader: String,
        term: String,
        cachedMetadata: YguTimetableMetadata? = null,
        onStage: (String) -> Unit = {},
    ): YguDirectRead {
        clearSession()
        val generation = this.generation
        val opened = open(cookieHeader)
        if (opened != null) {
            return YguDirectRead(errorMessage = opened.errorMessage, expired = opened.expired)
        }

        if (cachedMetadata != null &&
            cachedMetadata.status == "timetable" &&
            cachedMetadata.templateId.isNotEmpty() &&
            term.isNotBlank() &&
            (term == cachedMetadata.schoolTerm || term in cachedMetadata.semesters)
        ) {
            val fastUrl = if (term == cachedMetadata.schoolTerm && cachedMetadata.navigateUrl.isNotBlank()) {
                cachedMetadata.navigateUrl
            } else {
                YguHttpMetadata.navigateUrl(term, cachedMetadata.templateId, cachedMetadata.labels)
            }
            if (fastUrl.isNotBlank()) {
                onStage("正在读取完整学期课表…")
                val fastTable = try {
                    get(fastUrl)
                } catch (_: Exception) {
                    return failedRead("无法读取教务页面，请检查网络后重试")
                }
                if (!isCurrent(generation)) return cancelledRead()
                val fastCheck = responseError(fastTable)
                if (fastCheck.expired) {
                    return YguDirectRead(errorMessage = fastCheck.message, expired = true)
                }
                if (fastTable.status == 0) {
                    return failedRead(fastCheck.message.ifEmpty { "无法读取教务页面，请检查网络后重试" })
                }
                if (fastCheck.message.isEmpty()) {
                    val fastConfirmed = YguHttpMetadata.parse(fastTable.body, fastUrl)
                    if (fastConfirmed.status == "timetable" && fastConfirmed.allWeeks &&
                        fastConfirmed.schoolTerm == term &&
                        fastConfirmed.templateId == cachedMetadata.templateId &&
                        YguHttpMetadata.scopeMatches(fastTable.body, fastUrl, term, fastConfirmed.templateId)
                    ) {
                        val metadata = cachedMetadata.copy(
                            schoolTerm = term,
                            navigateUrl = fastUrl,
                            allWeeks = true,
                        )
                        val read = readFramedTable(fastTable, fastUrl, metadata, generation)
                        if (isCurrent(generation) && read.payload != null) return read
                        if (read.expired) return read
                    }
                }
            }
        }

        onStage("正在读取学期与课表参数…")
        val entryUrl = YguConfig.COURSE_ENTRY + "?viweType=0"
        val entry = try {
            get(entryUrl)
        } catch (error: Exception) {
            return failedRead("无法读取教务页面，请检查网络后重试")
        }
        if (!isCurrent(generation)) return cancelledRead()
        val entryCheck = responseError(entry)
        if (entryCheck.message.isNotEmpty()) {
            return YguDirectRead(errorMessage = entryCheck.message, expired = entryCheck.expired)
        }
        val entryMetadata = YguHttpMetadata.parse(entry.body, entryUrl)
        when (entryMetadata.status) {
            "timetable" -> Unit
            "multiple" -> return failedRead("页面出现多个课表入口，请改用官方网页登录")
            "login" -> return YguDirectRead(expired = true, errorMessage = "登录状态已失效，请重新登录")
            else -> return failedRead("课表入口未能识别，请改用官方网页登录")
        }
        if (term.isBlank() || entryMetadata.semesters.isNotEmpty() && term !in entryMetadata.semesters) {
            return failedRead("学校未提供该学期，请刷新学期列表后重试")
        }
        val targetUrl = if (term == entryMetadata.schoolTerm) {
            entryMetadata.navigateUrl
        } else {
            YguHttpMetadata.navigateUrl(term, entryMetadata.templateId, entryMetadata.labels)
        }
        onStage("正在读取完整学期课表…")
        val table = try {
            get(targetUrl)
        } catch (error: Exception) {
            return failedRead("无法读取教务页面，请检查网络后重试")
        }
        if (!isCurrent(generation)) return cancelledRead()
        val tableCheck = responseError(table)
        if (tableCheck.message.isNotEmpty()) {
            return YguDirectRead(errorMessage = tableCheck.message, expired = tableCheck.expired)
        }
        val confirmed = YguHttpMetadata.parse(table.body, targetUrl)
        if (confirmed.status != "timetable" || !confirmed.allWeeks ||
            confirmed.schoolTerm != term || confirmed.templateId != entryMetadata.templateId ||
            !YguHttpMetadata.scopeMatches(table.body, targetUrl, term, confirmed.templateId)
        ) {
            return failedRead("完整周次参数未能确认，请改用官方网页登录")
        }
        val metadata = entryMetadata.copy(
            schoolTerm = term,
            navigateUrl = targetUrl,
            allWeeks = true,
        )
        val read = readFramedTable(table, targetUrl, metadata, generation)
        return if (isCurrent(generation)) read else cancelledRead()
    }

    fun clearSession() {
        generation++
        cookieJar.clear()
    }

    /** 本次读取后的 Cookie（可能已被服务端续期）；空串表示会话已不可用。 */
    fun cookieHeader(): String = cookieJar.header()

    /** 导入 Cookie；返回非 null 表示当前请求无法继续。 */
    private fun open(cookieHeader: String): YguMetadataRead? {
        val validation = cookieJar.importHeader(cookieHeader)
        if (validation.isNotEmpty()) {
            return YguMetadataRead(expired = true, errorMessage = validation)
        }
        if (!cookieJar.has("bzb_jsxsd")) {
            clearSession()
            return YguMetadataRead(
                expired = true,
                errorMessage = "登录状态中缺少 bzb_jsxsd，请重新登录后再读取课表",
            )
        }
        return null
    }

    private fun isCurrent(generation: Int): Boolean = this.generation == generation

    private fun cancelled(): YguMetadataRead = YguMetadataRead(errorMessage = "读取已取消")

    private fun cancelledRead(): YguDirectRead = YguDirectRead(errorMessage = "读取已取消")

    private fun failed(message: String): YguMetadataRead = YguMetadataRead(errorMessage = message)

    private fun failedRead(message: String): YguDirectRead = YguDirectRead(errorMessage = message)

    /**
     * 同源静态子页面收集，与官方收集器保持文档/深度边界。
     */
    private suspend fun readFramedTable(
        response: YguHttpResponse,
        url: String,
        metadata: YguTimetableMetadata,
        generation: Int,
    ): YguDirectRead {
        val pages = mutableListOf(FramePage(response, url, 0))
        val visited = mutableSetOf(url)
        var selected: YguDirectRead? = null
        var selectedCourses: List<YguEduCourse> = emptyList()
        var index = 0
        while (index < pages.size) {
            val page = pages[index++]
            if (!isCurrent(generation)) return cancelledRead()
            if (page.depth > 0) {
                val child = YguHttpMetadata.parse(page.response.body, page.url)
                if (!YguHttpMetadata.scopeMatches(
                        page.response.body, page.url, metadata.schoolTerm, metadata.templateId,
                    ) ||
                    child.status == "multiple" ||
                    (child.status == "timetable" &&
                        (!child.allWeeks || child.schoolTerm != metadata.schoolTerm ||
                            child.templateId != metadata.templateId))
                ) {
                    return failedRead("子页面的学期或完整周次不一致，本机课表已保留")
                }
            }
            for (parsed in YguTimetableParser.parseTables(page.response.body)) {
                if (parsed.issues.isNotEmpty() || (parsed.courses.isEmpty() && !parsed.verifiedEmpty)) {
                    return failedRead("课表课程未能完整解析，本机课表已保留")
                }
                if (selected != null && selectedCourses != parsed.courses) {
                    return failedRead("页面出现多张不同课表，本机课表已保留，请只打开一张课表")
                }
                if (selected == null) {
                    selected = YguDirectRead(
                        metadata = metadata,
                        payload = YguTimetablePayload(page.response.body, page.url),
                    )
                    selectedCourses = parsed.courses
                }
            }
            // 主页面已经完整时不再请求后备子页面。
            if (page.depth == 0 && selected != null) return selected
            val frames = frameUrls(YguHtml.parse(page.response.body), page.url)
            if (page.depth >= MAX_DEPTH && frames.isNotEmpty()) {
                return failedRead("课表子页面层级过多，本机课表已保留")
            }
            for (frame in frames) {
                if (frame in visited) continue
                if (visited.size >= MAX_PAGES) {
                    return failedRead("课表子页面过多，本机课表已保留")
                }
                visited += frame
                val fetched = try {
                    get(frame)
                } catch (error: Exception) {
                    return failedRead("无法读取教务页面，请检查网络后重试")
                }
                if (!isCurrent(generation)) return cancelledRead()
                val check = responseError(fetched)
                if (check.message.isNotEmpty()) {
                    return YguDirectRead(errorMessage = check.message, expired = check.expired)
                }
                pages += FramePage(fetched, frame, page.depth + 1)
            }
        }
        return selected ?: failedRead("课表内容未能读取，请重试或改用官方网页登录")
    }

    private fun frameUrls(node: YguHtmlNode, pageUrl: String): List<String> {
        val urls = mutableListOf<String>()
        collectFrameUrls(node, pageUrl, urls)
        return urls
    }

    private fun collectFrameUrls(node: YguHtmlNode, pageUrl: String, urls: MutableList<String>) {
        if (urls.size > MAX_PAGES) return
        if (node.hidden() || node.tag == "script" || node.tag == "style" || node.hasAttribute("hidden")) {
            return
        }
        if (HIDDEN_STYLE.containsMatchIn(node.attribute("style"))) return
        if (node.tag == "iframe" || node.tag == "frame") {
            val url = YguSession.resolveUrl(node.attribute("src"), pageUrl)
            if (url.isNotEmpty() && url !in urls) urls += url
            return
        }
        for (child in node.children) {
            collectFrameUrls(child, pageUrl, urls)
            if (urls.size > MAX_PAGES) return
        }
    }

    private suspend fun get(url: String): YguHttpResponse {
        if (YguSession.resolveUrl(url, YguConfig.WRAPPER) != url) {
            throw IllegalArgumentException("教务地址无效")
        }
        return httpGet(url, cookieJar.header(), YguConfig.WRAPPER)
    }

    private fun responseError(response: YguHttpResponse): ResponseCheck {
        if (response.status in 300..399) {
            return ResponseCheck("教务页面发生重定向，请重新登录后再读取课表", expired = true)
        }
        if (!response.successful || response.status !in 200..399) {
            return ResponseCheck("无法读取教务页面，请检查网络后重试")
        }
        if (YguSession.isLoginPage(response.body)) {
            return ResponseCheck("登录状态已失效，请重新登录后再读取课表", expired = true)
        }
        cookieJar.absorb(response.setCookies)
        if (!cookieJar.has("bzb_jsxsd")) {
            return ResponseCheck("登录状态已失效，请重新登录后再读取课表", expired = true)
        }
        return ResponseCheck()
    }

    private data class FramePage(val response: YguHttpResponse, val url: String, val depth: Int)

    private data class ResponseCheck(val message: String = "", val expired: Boolean = false)

    private companion object {
        const val MAX_DEPTH = 4
        const val MAX_PAGES = 16
        val HIDDEN_STYLE = Regex(
            "visibility\\s*:\\s*(?:hidden|collapse)|opacity\\s*:\\s*0(?:\\.0+)?\\s*(?:;|$)",
            RegexOption.IGNORE_CASE,
        )
    }
}
