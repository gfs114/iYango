package com.gfs.iyango.data.school.ygu

import com.gfs.iyango.core.model.ClassroomBuilding
import com.gfs.iyango.core.model.ClassroomDetailRequest
import com.gfs.iyango.core.model.ClassroomMetadata
import com.gfs.iyango.core.model.ClassroomQuery
import com.gfs.iyango.core.model.ClassroomResult
import java.net.URLEncoder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 教室查询网络客户端，行为对齐 HarmonyOS 的 `YguClassroomClient`。
 *
 * 只访问四个已验证端点（jsjy_query / jsjy_query2 / jsjy_processAjax / jsjy_jszyqk）；
 * 查询按官方 initTable 的 19 字段完整周表协议发送，详情按 13 字段协议发送。
 * Cookie 只在内存罐里轮换，方法返回更新后的会话头，由仓库写回 Keystore。
 */
internal class YguClassroomClient {

    private val cookieJar = YguCookieJar()

    fun cookieHeader(): String = cookieJar.header()

    suspend fun fetchMetadata(cookieHeader: String): YguClassroomMetadataLoad {
        cookieJar.clear()
        if (cookieJar.importHeader(cookieHeader).isNotEmpty() || !cookieJar.has(SESSION_COOKIE)) {
            return metadataResult(YguClassroomClientStatus.LOGIN, MESSAGE_LOGIN)
        }
        return try {
            currentCoroutineContext().ensureActive()
            val response = YguHttp.get(URL_METADATA, cookieJar.header(), YguConfig.BASE + "/")
            cookieJar.absorb(response.setCookies)
            currentCoroutineContext().ensureActive()
            val classified = classifyMetadata(response)
            if (classified != null) return withSession(classified)
            val parsed = YguClassroomMetadata.parse(response.body)
            val output = when (parsed.status) {
                YguClassroomMetadataStatus.OK -> YguClassroomMetadataLoad(
                    status = YguClassroomClientStatus.OK,
                    metadata = parsed.options.toMetadata(),
                )
                YguClassroomMetadataStatus.LOGIN -> metadataResult(
                    YguClassroomClientStatus.LOGIN,
                    parsed.message.ifEmpty { MESSAGE_LOGIN },
                )
                YguClassroomMetadataStatus.UNSUPPORTED -> metadataResult(
                    YguClassroomClientStatus.UNSUPPORTED,
                    parsed.message.ifEmpty { MESSAGE_UNSUPPORTED },
                )
                YguClassroomMetadataStatus.INVALID -> metadataResult(
                    YguClassroomClientStatus.INVALID,
                    parsed.message.ifEmpty { MESSAGE_INVALID },
                )
            }
            withSession(output)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            metadataResult(YguClassroomClientStatus.ERROR, MESSAGE_NETWORK)
        } finally {
            cookieJar.clear()
        }
    }

    suspend fun fetchBuildings(cookieHeader: String, campus: String): YguClassroomBuildingsLoad {
        cookieJar.clear()
        if (cookieJar.importHeader(cookieHeader).isNotEmpty() || !cookieJar.has(SESSION_COOKIE)) {
            return buildingsResult(YguClassroomClientStatus.LOGIN, MESSAGE_LOGIN)
        }
        return try {
            currentCoroutineContext().ensureActive()
            val url = URL_BUILDINGS + "?xqid=" + encode(campus) + "&requestType=jxl"
            val response = YguHttp.get(url, cookieJar.header(), URL_METADATA, schoolAjax = true)
            cookieJar.absorb(response.setCookies)
            currentCoroutineContext().ensureActive()
            val classified = classifyMetadata(response)
            val output = if (classified != null) {
                buildingsResult(classified.status, classified.message)
            } else {
                val options = YguClassroomMetadata.parseBuildings(response.body)
                if (options == null) {
                    buildingsResult(YguClassroomClientStatus.INVALID, "暂时无法识别教学楼列表，请重试")
                } else {
                    YguClassroomBuildingsLoad(
                        status = YguClassroomClientStatus.OK,
                        buildings = options.map { ClassroomBuilding(it.value, it.label) },
                    )
                }
            }
            withSession(output)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            buildingsResult(YguClassroomClientStatus.ERROR, "教学楼列表加载失败，请重试或选择全部教学楼")
        } finally {
            cookieJar.clear()
        }
    }

    suspend fun query(cookieHeader: String, query: ClassroomQuery): YguClassroomQueryLoad {
        cookieJar.clear()
        if (cookieJar.importHeader(cookieHeader).isNotEmpty() || !cookieJar.has(SESSION_COOKIE)) {
            return queryResult(YguClassroomClientStatus.LOGIN, MESSAGE_LOGIN)
        }
        return try {
            currentCoroutineContext().ensureActive()
            val page = requestQuery(query)
            currentCoroutineContext().ensureActive()
            val output = if (page.status != YguClassroomClientStatus.OK) {
                queryResult(page.status, page.message)
            } else {
                mapParsed(YguClassroomParser.parse(page.body), query)
            }
            withSession(output)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            queryResult(YguClassroomClientStatus.ERROR, MESSAGE_NETWORK)
        } finally {
            cookieJar.clear()
        }
    }

    suspend fun fetchCourseDetail(
        cookieHeader: String,
        request: ClassroomDetailRequest,
    ): YguClassroomDetailLoad {
        cookieJar.clear()
        if (cookieJar.importHeader(cookieHeader).isNotEmpty() || !cookieJar.has(SESSION_COOKIE)) {
            return detailResult(YguClassroomClientStatus.LOGIN, MESSAGE_LOGIN)
        }
        return try {
            currentCoroutineContext().ensureActive()
            val response = YguHttp.post(
                URL_DETAIL,
                detailBody(request),
                cookieJar.header(),
                URL_METADATA,
                schoolAjax = true,
            )
            cookieJar.absorb(response.setCookies)
            currentCoroutineContext().ensureActive()
            val classified = classifyMetadata(response)
            val output = if (classified != null) {
                detailResult(classified.status, classified.message)
            } else {
                mapDetail(YguClassroomCourseDetail.parse(response.body, request))
            }
            withSession(output)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            detailResult(YguClassroomClientStatus.ERROR, "课程详情加载失败，请重试")
        } finally {
            cookieJar.clear()
        }
    }

    private suspend fun requestQuery(query: ClassroomQuery): Page {
        val first = YguHttp.post(
            URL_QUERY,
            queryBody(query),
            cookieJar.header(),
            URL_METADATA,
            schoolAjax = true,
        )
        cookieJar.absorb(first.setCookies)
        currentCoroutineContext().ensureActive()
        val page = classifyQuery(first)
        if (page.followUrl.isEmpty()) return page
        val follow = YguHttp.get(page.followUrl, cookieJar.header(), URL_QUERY)
        cookieJar.absorb(follow.setCookies)
        currentCoroutineContext().ensureActive()
        return classifyFollow(follow)
    }

    private fun classifyMetadata(response: YguHttpResponse): YguClassroomMetadataLoad? {
        if (!cookieJar.has(SESSION_COOKIE)) {
            return metadataResult(YguClassroomClientStatus.LOGIN, MESSAGE_LOGIN)
        }
        if (response.status == 401 || YguSession.isLoginPage(response.body)) {
            return metadataResult(YguClassroomClientStatus.LOGIN, MESSAGE_LOGIN)
        }
        if (response.status in 300..399) {
            if (isLoginDestination(response.location)) {
                return metadataResult(YguClassroomClientStatus.LOGIN, MESSAGE_LOGIN)
            }
            return metadataResult(YguClassroomClientStatus.UNSUPPORTED, MESSAGE_UNSUPPORTED)
        }
        if (response.status == 403) {
            return metadataResult(YguClassroomClientStatus.RESTRICTED, MESSAGE_RESTRICTED)
        }
        if (response.status == 0 || response.status >= 500) {
            return metadataResult(YguClassroomClientStatus.ERROR, MESSAGE_NETWORK)
        }
        if (response.status !in 200..299) {
            return metadataResult(YguClassroomClientStatus.UNSUPPORTED, MESSAGE_UNSUPPORTED)
        }
        return null
    }

    private fun classifyQuery(response: YguHttpResponse): Page {
        if (!cookieJar.has(SESSION_COOKIE)) {
            return Page(YguClassroomClientStatus.LOGIN, MESSAGE_LOGIN)
        }
        if (response.status == 401 || YguSession.isLoginPage(response.body)) {
            return Page(YguClassroomClientStatus.LOGIN, MESSAGE_LOGIN)
        }
        if (response.status in 300..399) {
            if (response.location.isEmpty()) {
                return Page(YguClassroomClientStatus.UNSUPPORTED, MESSAGE_UNSUPPORTED)
            }
            if (isLoginDestination(response.location)) {
                return Page(YguClassroomClientStatus.LOGIN, MESSAGE_LOGIN)
            }
            val resolved = YguSession.resolveUrl(response.location, URL_QUERY)
            if (resolved.isEmpty() || !isAllowedPage(resolved)) {
                return Page(YguClassroomClientStatus.UNSUPPORTED, MESSAGE_UNSUPPORTED)
            }
            return Page(YguClassroomClientStatus.UNSUPPORTED, "", resolved)
        }
        if (response.status == 403) {
            return Page(YguClassroomClientStatus.RESTRICTED, MESSAGE_RESTRICTED)
        }
        if (response.status == 0 || response.status >= 500) {
            return Page(YguClassroomClientStatus.ERROR, MESSAGE_NETWORK)
        }
        if (response.status !in 200..299) {
            return Page(YguClassroomClientStatus.UNSUPPORTED, MESSAGE_UNSUPPORTED)
        }
        return Page(YguClassroomClientStatus.OK, "", body = response.body)
    }

    /** 跟随那一跳只用于定性：非登录/非网络错误一律判“暂不支持”，不发布数据。 */
    private fun classifyFollow(response: YguHttpResponse): Page {
        if (!cookieJar.has(SESSION_COOKIE)) {
            return Page(YguClassroomClientStatus.LOGIN, MESSAGE_LOGIN)
        }
        if (response.status == 401 || YguSession.isLoginPage(response.body)) {
            return Page(YguClassroomClientStatus.LOGIN, MESSAGE_LOGIN)
        }
        if (response.status == 0 || response.status >= 500) {
            return Page(YguClassroomClientStatus.ERROR, MESSAGE_NETWORK)
        }
        return Page(YguClassroomClientStatus.UNSUPPORTED, MESSAGE_UNSUPPORTED)
    }

    private fun mapParsed(parsed: YguClassroomParseResult, query: ClassroomQuery): YguClassroomQueryLoad =
        when (parsed.status) {
            YguClassroomParseStatus.OK -> YguClassroomQueryLoad(
                status = YguClassroomClientStatus.OK,
                result = ClassroomResult(
                    query = query,
                    fetchedAt = System.currentTimeMillis(),
                    periodGroups = parsed.periodGroups,
                    rooms = parsed.rooms,
                ),
            )
            YguClassroomParseStatus.EMPTY -> YguClassroomQueryLoad(
                status = YguClassroomClientStatus.EMPTY,
                message = parsed.message,
                result = ClassroomResult(
                    query = query,
                    fetchedAt = System.currentTimeMillis(),
                    periodGroups = parsed.periodGroups,
                ),
            )
            YguClassroomParseStatus.LOGIN -> queryResult(
                YguClassroomClientStatus.LOGIN,
                parsed.message.ifEmpty { MESSAGE_LOGIN },
            )
            YguClassroomParseStatus.INVALID -> queryResult(
                YguClassroomClientStatus.INVALID,
                parsed.message.ifEmpty { MESSAGE_INVALID },
            )
        }

    private fun mapDetail(parsed: YguClassroomDetailResult): YguClassroomDetailLoad = when (parsed.status) {
        YguClassroomDetailStatus.OK -> YguClassroomDetailLoad(
            status = YguClassroomClientStatus.OK,
            courses = parsed.courses,
        )
        YguClassroomDetailStatus.EMPTY -> detailResult(YguClassroomClientStatus.EMPTY, parsed.message)
        YguClassroomDetailStatus.UNSUPPORTED -> detailResult(
            YguClassroomClientStatus.UNSUPPORTED,
            parsed.message,
        )
        YguClassroomDetailStatus.INVALID -> detailResult(
            YguClassroomClientStatus.INVALID,
            parsed.message.ifEmpty { MESSAGE_INVALID },
        )
    }

    private fun queryBody(query: ClassroomQuery): String {
        val values = listOf(
            query.termValue,
            query.campusCode,
            "",
            query.buildingCode,
            "",
            query.roomType,
            "PQ==",
            "",
            "",
            query.timeModeId,
            query.week.toString(),
            "",
            "",
            "1,2,3,4,5,6,7",
            query.allSectionCodes.joinToString(","),
            "",
            "jszq",
            "1",
            "0",
        )
        return QUERY_KEYS.indices.joinToString("&") { index ->
            "${QUERY_KEYS[index]}=${encode(values[index])}"
        }
    }

    private fun detailBody(request: ClassroomDetailRequest): String {
        val day = request.dayOfWeek.toString()
        val values = listOf(
            request.termValue,
            request.roomId,
            day + request.rawSectionCode,
            "jszq",
            request.week.toString(),
            "1,2,3,4,5,6,7",
            request.allSectionCodes.joinToString(","),
            "",
            "add",
            request.timeModeId,
            day,
            request.sourceStartTime.ifEmpty { request.sectionStartTime },
            request.sourceEndTime.ifEmpty { request.sectionEndTime },
        )
        return DETAIL_KEYS.indices.joinToString("&") { index ->
            "${DETAIL_KEYS[index]}=${encode(values[index])}"
        }
    }

    private fun isAllowedPage(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#')
        return path == URL_METADATA || path == URL_QUERY
    }

    private fun isLoginDestination(url: String): Boolean {
        val input = url.trim()
        if (input == "/jsxsd/" || input == "/jsxsd" || input.startsWith("/jsxsd/xk/LoginToXk") ||
            input == YguConfig.ORIGIN + "/jsxsd/" || input == YguConfig.ORIGIN + "/jsxsd"
        ) {
            return true
        }
        val resolved = YguSession.resolveUrl(url, URL_METADATA)
        if (resolved.isEmpty()) return false
        val tail = resolved.substring(YguConfig.ORIGIN.length).substringBefore('?')
        return tail == "/jsxsd/" || tail == "/jsxsd" || tail.startsWith("/jsxsd/xk/LoginToXk")
    }

    private fun YguClassroomOptions.toMetadata(): ClassroomMetadata = ClassroomMetadata(
        terms = semesters.toList(),
        campuses = campuses.toList(),
        buildings = buildings.toList(),
        roomTypes = roomTypes.toList(),
        timeModes = timeModes.toList(),
        weeks = weeks.toList(),
        days = days.toList(),
        sections = periods.toList(),
    )

    private fun withSession(load: YguClassroomMetadataLoad): YguClassroomMetadataLoad =
        load.copy(
            cookieHeader = if (load.status == YguClassroomClientStatus.LOGIN) "" else cookieJar.header(),
        )

    private fun withSession(load: YguClassroomBuildingsLoad): YguClassroomBuildingsLoad =
        load.copy(
            cookieHeader = if (load.status == YguClassroomClientStatus.LOGIN) "" else cookieJar.header(),
        )

    private fun withSession(load: YguClassroomQueryLoad): YguClassroomQueryLoad =
        load.copy(
            cookieHeader = if (load.status == YguClassroomClientStatus.LOGIN) "" else cookieJar.header(),
        )

    private fun withSession(load: YguClassroomDetailLoad): YguClassroomDetailLoad =
        load.copy(
            cookieHeader = if (load.status == YguClassroomClientStatus.LOGIN) "" else cookieJar.header(),
        )

    private fun metadataResult(status: YguClassroomClientStatus, message: String): YguClassroomMetadataLoad =
        YguClassroomMetadataLoad(status = status, message = message)

    private fun buildingsResult(status: YguClassroomClientStatus, message: String): YguClassroomBuildingsLoad =
        YguClassroomBuildingsLoad(status = status, message = message)

    private fun queryResult(status: YguClassroomClientStatus, message: String): YguClassroomQueryLoad =
        YguClassroomQueryLoad(status = status, message = message)

    private fun detailResult(status: YguClassroomClientStatus, message: String): YguClassroomDetailLoad =
        YguClassroomDetailLoad(status = status, message = message)

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())
            .replace("+", "%20")
            .replace("%21", "!")
            .replace("%27", "'")
            .replace("%28", "(")
            .replace("%29", ")")
            .replace("%7E", "~")

    private data class Page(
        val status: YguClassroomClientStatus,
        val message: String,
        val followUrl: String = "",
        val body: String = "",
    )

    private companion object {
        const val SESSION_COOKIE = "bzb_jsxsd"

        val URL_METADATA: String = YguConfig.BASE + "/kbxx/jsjy_query"
        val URL_QUERY: String = YguConfig.BASE + "/kbxx/jsjy_query2"
        val URL_BUILDINGS: String = YguConfig.BASE + "/kbxx/jsjy_processAjax"
        val URL_DETAIL: String = YguConfig.BASE + "/kbxx/jsjy_jszyqk"

        const val MESSAGE_LOGIN = "登录状态已失效，请返回教务登录后重试"
        const val MESSAGE_NETWORK = "教室查询网络暂不可用，请检查网络后重试"
        const val MESSAGE_UNSUPPORTED = "学校教务页面暂不支持，请稍后重试"
        const val MESSAGE_INVALID = "暂时无法识别教务返回的数据"
        const val MESSAGE_RESTRICTED = "学校暂未开放教室查询，请完成相关事项后重试"

        val QUERY_KEYS = listOf(
            "xnxqh", "xqbh", "jxqbh", "jxlbh", "jsbh", "jslx", "bjfh", "rnrs", "yx",
            "kbjcmsid", "selectZc", "startdate", "enddate", "selectXq", "selectJc",
            "syjs0601id", "typewhere", "qsxq", "jyms",
        )
        val DETAIL_KEYS = listOf(
            "xnxqh", "jsbh", "kcsj", "typewhere", "selectZc", "selectXq", "selectJc",
            "jszt", "type", "kbjcmsid", "xq", "kssj", "jssj",
        )
    }
}
