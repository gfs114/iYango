package com.gfs.iyango.data.school.ygu

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 阳光学院成绩查询 HTTP 客户端，行为对齐 HarmonyOS 的 `YguGradesClient`。
 *
 * 请求预算：普通表单最多 6 次（入口、表单、提交、重定向），JSON 分页最多 104 次；
 * 任何越界页面、登录页、403 或页面结构异常都停止并给出可读原因，不猜测数据。
 */
internal class YguGradesClient(
    private val get: suspend (String, String, String, Boolean) -> YguHttpResponse = YguHttp::get,
    private val post: suspend (String, String, String, String, Boolean) -> YguHttpResponse = YguHttp::post,
) {

    private val cookieJar = YguCookieJar()

    /** 当前请求的临时 Cookie；请求结束后会清空，后续请求应使用结果中的 cookieHeader。 */
    fun cookieHeader(): String = cookieJar.header()

    suspend fun fetchOptions(cookieHeader: String): YguGradesOutcome =
        fetchInternal(cookieHeader, requestedTerm = null, metadataOnly = true)

    suspend fun fetch(cookieHeader: String, requestedTerm: String?): YguGradesOutcome =
        fetchInternal(cookieHeader, requestedTerm, metadataOnly = false)

    private suspend fun fetchInternal(
        cookieHeader: String,
        requestedTerm: String?,
        metadataOnly: Boolean,
    ): YguGradesOutcome {
        cookieJar.clear()
        if (cookieJar.importHeader(cookieHeader).isNotEmpty() || !cookieJar.has("bzb_jsxsd")) {
            return login("登录状态已失效，请返回教务登录后重试")
        }
        val budget = Budget()
        return try {
            var entryPage = requestPage(
                url = YguConfig.BASE + "/kscj/cjcx_frm",
                referer = "",
                budget = budget,
                postRedirect = false,
            )
            if (entryPage.status != YguGradesClientStatus.OK) return withSession(fromPage(entryPage))

            var form = YguGradesQuery.parseForm(entryPage.body, entryPage.url, requestedTerm)
            if (!form.supported) {
                var queryUrl = YguGradesQuery.discoverQueryUrl(entryPage.body, entryPage.url)
                if (queryUrl.isEmpty()) {
                    if (form.hasForms) return withSession(unsupportedForForm(form))
                    queryUrl = YguConfig.BASE + "/kscj/cjcx_query"
                }
                if (!YguGradesQuery.isAllowedPage(queryUrl) || queryUrl in budget.visited) {
                    return withSession(unsupportedForForm(form))
                }
                entryPage = requestPage(
                    url = queryUrl,
                    referer = YguConfig.BASE + "/kscj/cjcx_frm",
                    budget = budget,
                    postRedirect = false,
                )
                if (entryPage.status != YguGradesClientStatus.OK) return withSession(fromPage(entryPage))
                form = YguGradesQuery.parseForm(entryPage.body, entryPage.url, requestedTerm)
            }
            if (!form.supported || !YguGradesQuery.isGradesList(form.actionUrl)) {
                return withSession(unsupportedForForm(form))
            }
            if (metadataOnly) {
                return withSession(
                    YguGradesOutcome(
                        status = YguGradesClientStatus.OK,
                        semesters = form.semesters,
                        selectedTerm = form.selectedTerm,
                    ),
                )
            }
            if (form.isJson) {
                budget.limit = MAX_JSON_REQUESTS
                return withSession(fetchJsonPages(form, entryPage.url, budget))
            }
            currentCoroutineContext().ensureActive()
            if (budget.count >= budget.limit) {
                return withSession(failure("成绩查询网络暂不可用，请稍后重试"))
            }
            val body = YguGradesQuery.encodeFields(form.fields)
            val response = if (form.method == "POST") {
                post(form.actionUrl, body, cookieJar.header(), entryPage.url, false)
            } else {
                val separator = if (form.actionUrl.contains('?')) '&' else '?'
                get("${form.actionUrl}$separator$body", cookieJar.header(), entryPage.url, false)
            }
            budget.count++
            cookieJar.absorb(response.setCookies)
            if (!cookieJar.has("bzb_jsxsd")) {
                return withSession(login("登录状态已失效，请返回教务登录后重试"))
            }
            val submitted = when {
                form.method == "POST" && (response.status == 307 || response.status == 308) ->
                    page(YguGradesClientStatus.UNSUPPORTED, "学校成绩查询页面暂不支持，请稍后重试", form.actionUrl)

                form.method == "POST" && redirectTo(response, form.actionUrl) &&
                    !YguGradesQuery.isLoginDestination(response.location) -> {
                    val destination = YguSession.resolveUrl(response.location, form.actionUrl)
                    if (YguGradesQuery.isGradesList(destination)) {
                        requestPage(destination, form.actionUrl, budget, postRedirect = false)
                    } else {
                        page(
                            YguGradesClientStatus.UNSUPPORTED,
                            "学校成绩查询页面暂不支持，请稍后重试",
                            destination,
                        )
                    }
                }

                form.method == "GET" && redirectTo(response, form.actionUrl) &&
                    !YguGradesQuery.isLoginDestination(response.location) -> {
                    val destination = YguSession.resolveUrl(response.location, form.actionUrl)
                    if (YguGradesQuery.isAllowedPage(destination)) {
                        requestPage(destination, form.actionUrl, budget, postRedirect = false)
                    } else {
                        page(
                            YguGradesClientStatus.UNSUPPORTED,
                            "学校成绩查询页面暂不支持，请稍后重试",
                            destination,
                        )
                    }
                }

                else -> readResult(response, form.actionUrl, budget, wasPost = form.method == "POST")
            }
            if (submitted.status != YguGradesClientStatus.OK) {
                val failure = fromPage(submitted)
                return withSession(
                    failure.copy(
                        semesters = form.semesters,
                        selectedTerm = form.selectedTerm,
                    ),
                )
            }
            val parsed = YguGradesParser.parse(submitted.body)
            withSession(
                YguGradesOutcome(
                    status = parserStatus(parsed.status),
                    records = parsed.records,
                    semesters = form.semesters,
                    selectedTerm = form.selectedTerm,
                    message = parsed.message,
                ),
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            withSession(failure("成绩查询网络暂不可用，请检查网络后重试"))
        } finally {
            cookieJar.clear()
        }
    }

    private suspend fun requestPage(
        url: String,
        referer: String,
        budget: Budget,
        postRedirect: Boolean,
        schoolAjax: Boolean = false,
        metadataOnly: Boolean = false,
    ): Page {
        var currentUrl = url
        var currentReferer = referer
        var redirects = 0
        var followRedirect = postRedirect
        while (budget.count < budget.limit && redirects <= MAX_REDIRECTS) {
            currentCoroutineContext().ensureActive()
            if ((!schoolAjax && !YguGradesQuery.isAllowedPage(currentUrl)) ||
                (schoolAjax && !YguGradesQuery.isGradesList(currentUrl)) ||
                (metadataOnly && YguGradesQuery.isGradesList(currentUrl)) ||
                currentUrl in budget.visited
            ) {
                return page(
                    YguGradesClientStatus.UNSUPPORTED,
                    "学校成绩查询页面暂不支持，请稍后重试",
                    currentUrl,
                )
            }
            budget.visited += currentUrl
            val response = get(currentUrl, cookieJar.header(), currentReferer, schoolAjax)
            budget.count++
            cookieJar.absorb(response.setCookies)
            val next = readResult(response, currentUrl, budget, followRedirect)
            if (next.status != YguGradesClientStatus.UNSUPPORTED ||
                next.url == currentUrl || next.body.isNotEmpty()
            ) {
                return next
            }
            if (!schoolAjax) currentReferer = currentUrl
            if (schoolAjax && !YguGradesQuery.isGradesList(next.url)) {
                return page(
                    YguGradesClientStatus.UNSUPPORTED,
                    "学校成绩查询页面暂不支持，请稍后重试",
                    next.url,
                )
            }
            currentUrl = next.url
            redirects++
            followRedirect = false
        }
        return page(YguGradesClientStatus.ERROR, "成绩查询网络暂不可用，请稍后重试", currentUrl)
    }

    private suspend fun fetchJsonPages(
        form: YguGradesQueryForm,
        referer: String,
        budget: Budget,
    ): YguGradesOutcome {
        val firstUrl = jsonPageUrl(form, 1)
        if (!YguGradesQuery.isGradesList(firstUrl) || budget.count >= budget.limit) {
            return withForm(failure("成绩查询网络暂不可用，请稍后重试"), form)
        }
        val firstResponse = requestPage(
            url = firstUrl,
            referer = referer,
            budget = budget,
            postRedirect = false,
            schoolAjax = true,
        )
        if (firstResponse.status != YguGradesClientStatus.OK) {
            return withForm(fromPage(firstResponse), form)
        }
        val firstParsed = YguGradesJsonParser.parse(firstResponse.body)
        if (firstParsed.status == YguGradesStatus.EMPTY) {
            return if (firstParsed.total == 0) {
                withForm(empty(firstParsed.message), form)
            } else {
                withForm(invalid(), form)
            }
        }
        if (firstParsed.status != YguGradesStatus.OK) {
            return withForm(
                YguGradesOutcome(
                    status = parserStatus(firstParsed.status),
                    message = firstParsed.message,
                ),
                form,
            )
        }
        val total = firstParsed.total
        val firstExpected = minOf(20, total)
        val fingerprints = mutableSetOf<String>()
        if (firstExpected <= 0 || firstParsed.records.size != firstExpected ||
            !fingerprints.add(firstParsed.fingerprint)
        ) {
            return withForm(invalid(), form)
        }
        val records = mutableListOf<YguGradeRow>()
        for (record in firstParsed.records) {
            if (form.selectedTerm.isNotEmpty() && record.term.isNotEmpty() &&
                record.term != form.selectedTerm
            ) {
                return withForm(invalid(), form)
            }
            records += record
        }
        if (records.size == total) {
            return withForm(
                YguGradesOutcome(
                    status = YguGradesClientStatus.OK,
                    records = records,
                ),
                form,
            )
        }
        val totalPages = (total + 19) / 20
        if (totalPages > 100) {
            return withForm(invalid(), form)
        }
        if (budget.count + (totalPages - 1) > budget.limit) {
            return withForm(failure("成绩查询网络暂不可用，请稍后重试"), form)
        }

        val jarLock = Any()
        val semaphore = Semaphore(JSON_PAGE_CONCURRENCY)
        val extraPages = coroutineScope {
            (2..totalPages).map { pageNum ->
                async {
                    semaphore.withPermit {
                        currentCoroutineContext().ensureActive()
                        pageNum to requestJsonPageConcurrent(
                            url = jsonPageUrl(form, pageNum),
                            referer = referer,
                            budget = budget,
                            jarLock = jarLock,
                        )
                    }
                }
            }.awaitAll().sortedBy { it.first }
        }

        for ((_, response) in extraPages) {
            if (response.status != YguGradesClientStatus.OK) {
                return withForm(fromPage(response), form)
            }
            val parsed = YguGradesJsonParser.parse(response.body)
            if (parsed.status == YguGradesStatus.EMPTY) {
                return withForm(invalid(), form)
            }
            if (parsed.status != YguGradesStatus.OK) {
                return withForm(
                    YguGradesOutcome(
                        status = parserStatus(parsed.status),
                        message = parsed.message,
                    ),
                    form,
                )
            }
            if (parsed.total != total) {
                return withForm(invalid(), form)
            }
            val expected = minOf(20, total - records.size)
            if (expected <= 0 || parsed.records.size != expected ||
                !fingerprints.add(parsed.fingerprint)
            ) {
                return withForm(invalid(), form)
            }
            for (record in parsed.records) {
                if (form.selectedTerm.isNotEmpty() && record.term.isNotEmpty() &&
                    record.term != form.selectedTerm
                ) {
                    return withForm(invalid(), form)
                }
                records += record
            }
            if (records.size == total) {
                return withForm(
                    YguGradesOutcome(
                        status = YguGradesClientStatus.OK,
                        records = records,
                    ),
                    form,
                )
            }
        }
        return withForm(invalid(), form)
    }

    private suspend fun requestJsonPageConcurrent(
        url: String,
        referer: String,
        budget: Budget,
        jarLock: Any,
    ): Page {
        var currentUrl = url
        var redirects = 0
        while (redirects <= MAX_REDIRECTS) {
            currentCoroutineContext().ensureActive()
            val cookieSnapshot = synchronized(jarLock) {
                if (!YguGradesQuery.isGradesList(currentUrl) ||
                    budget.count >= budget.limit ||
                    currentUrl in budget.visited
                ) {
                    null
                } else {
                    budget.visited += currentUrl
                    budget.count++
                    cookieJar.header()
                }
            } ?: return page(
                YguGradesClientStatus.ERROR,
                "成绩查询网络暂不可用，请稍后重试",
                currentUrl,
            )

            val response = get(currentUrl, cookieSnapshot, referer, true)
            val next = synchronized(jarLock) {
                cookieJar.absorb(response.setCookies)
                readResult(response, currentUrl, budget, wasPost = false)
            }
            if (next.status != YguGradesClientStatus.UNSUPPORTED ||
                next.url == currentUrl || next.body.isNotEmpty()
            ) {
                return next
            }
            if (!YguGradesQuery.isGradesList(next.url)) {
                return page(
                    YguGradesClientStatus.UNSUPPORTED,
                    "学校成绩查询页面暂不支持，请稍后重试",
                    next.url,
                )
            }
            currentUrl = next.url
            redirects++
        }
        return page(YguGradesClientStatus.ERROR, "成绩查询网络暂不可用，请稍后重试", currentUrl)
    }

    private fun jsonPageUrl(form: YguGradesQueryForm, pageNumber: Int): String {
        val filters = YguGradesQuery.encodeFields(form.fields)
        val paging = "pageNum=$pageNumber&pageSize=20"
        return form.actionUrl + "?" + paging + if (filters.isNotEmpty()) "&$filters" else ""
    }

    private fun readResult(
        response: YguHttpResponse,
        requestUrl: String,
        budget: Budget,
        wasPost: Boolean,
    ): Page {
        if (!cookieJar.has("bzb_jsxsd")) {
            return page(YguGradesClientStatus.LOGIN, "登录状态已失效，请返回教务登录后重试", requestUrl)
        }
        if (response.status == 401 || YguSession.isLoginPage(response.body)) {
            return page(YguGradesClientStatus.LOGIN, "登录状态已失效，请返回教务登录后重试", requestUrl)
        }
        if (response.status == 403) {
            return page(
                YguGradesClientStatus.RESTRICTED,
                "学校暂未开放成绩查询，请完成相关事项后重试",
                requestUrl,
            )
        }
        if (response.status == 0 || response.status >= 500) {
            return page(
                YguGradesClientStatus.ERROR,
                "成绩查询网络暂不可用，请检查网络后重试",
                requestUrl,
            )
        }
        if (response.status in 300..399) {
            if (response.location.isEmpty()) {
                return page(
                    YguGradesClientStatus.UNSUPPORTED,
                    "学校成绩查询页面暂不支持，请稍后重试",
                    requestUrl,
                )
            }
            val resolved = YguSession.resolveUrl(response.location, requestUrl)
            if (YguGradesQuery.isLoginDestination(response.location)) {
                return page(YguGradesClientStatus.LOGIN, "登录状态已失效，请返回教务登录后重试", resolved)
            }
            if (!YguGradesQuery.isAllowedPage(resolved)) {
                return page(
                    YguGradesClientStatus.UNSUPPORTED,
                    "学校成绩查询页面暂不支持，请稍后重试",
                    resolved,
                )
            }
            if (wasPost && budget.count >= MAX_REQUESTS) {
                return page(
                    YguGradesClientStatus.ERROR,
                    "成绩查询网络暂不可用，请稍后重试",
                    resolved,
                )
            }
            return page(YguGradesClientStatus.UNSUPPORTED, "", resolved)
        }
        if (response.status !in 200..299) {
            return page(YguGradesClientStatus.ERROR, "成绩查询暂不可用，请稍后重试", requestUrl)
        }
        if (response.body.isEmpty()) {
            return page(YguGradesClientStatus.INVALID, "成绩页面暂不可读取，请稍后重试", requestUrl)
        }
        return page(YguGradesClientStatus.OK, "", requestUrl, response.body)
    }

    private fun redirectTo(response: YguHttpResponse, requestUrl: String): Boolean =
        (response.status == 302 || response.status == 303) && response.location.isNotEmpty() &&
            YguSession.resolveUrl(response.location, requestUrl).isNotEmpty()

    private fun parserStatus(status: YguGradesStatus): YguGradesClientStatus = when (status) {
        YguGradesStatus.OK -> YguGradesClientStatus.OK
        YguGradesStatus.EMPTY -> YguGradesClientStatus.EMPTY
        YguGradesStatus.LOGIN -> YguGradesClientStatus.LOGIN
        YguGradesStatus.RESTRICTED -> YguGradesClientStatus.RESTRICTED
        YguGradesStatus.INVALID -> YguGradesClientStatus.INVALID
    }

    private fun withSession(outcome: YguGradesOutcome): YguGradesOutcome =
        outcome.copy(
            cookieHeader = if (outcome.status == YguGradesClientStatus.LOGIN ||
                outcome.status == YguGradesClientStatus.CANCELLED
            ) {
                ""
            } else {
                cookieJar.header()
            },
        )

    private fun withForm(outcome: YguGradesOutcome, form: YguGradesQueryForm): YguGradesOutcome =
        outcome.copy(semesters = form.semesters, selectedTerm = form.selectedTerm)

    private fun fromPage(page: Page): YguGradesOutcome =
        YguGradesOutcome(status = page.status, message = page.message)

    private fun unsupportedForForm(form: YguGradesQueryForm): YguGradesOutcome =
        YguGradesOutcome(
            status = YguGradesClientStatus.UNSUPPORTED,
            semesters = form.semesters,
            selectedTerm = form.selectedTerm,
            message = form.message.ifEmpty { "学校成绩查询表单暂不支持，请稍后重试" },
        )

    private fun login(message: String): YguGradesOutcome =
        YguGradesOutcome(status = YguGradesClientStatus.LOGIN, message = message)

    private fun empty(message: String): YguGradesOutcome =
        YguGradesOutcome(status = YguGradesClientStatus.EMPTY, message = message)

    private fun invalid(): YguGradesOutcome =
        YguGradesOutcome(
            status = YguGradesClientStatus.INVALID,
            message = "成绩页面暂不可读取，请稍后重试",
        )

    private fun failure(message: String): YguGradesOutcome =
        YguGradesOutcome(status = YguGradesClientStatus.ERROR, message = message)

    private fun page(
        status: YguGradesClientStatus,
        message: String,
        url: String,
        body: String = "",
    ): Page = Page(status = status, message = message, url = url, body = body)

    private data class Page(
        val status: YguGradesClientStatus,
        val message: String,
        val url: String,
        val body: String = "",
    )

    private class Budget(
        var count: Int = 0,
        val visited: MutableSet<String> = mutableSetOf(),
        var limit: Int = MAX_REQUESTS,
    )

    private companion object {
        const val MAX_REQUESTS = 6
        const val MAX_JSON_REQUESTS = 104
        const val MAX_REDIRECTS = 3
        const val JSON_PAGE_CONCURRENCY = 4
    }
}
