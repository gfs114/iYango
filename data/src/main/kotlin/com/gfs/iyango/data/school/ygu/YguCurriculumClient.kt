package com.gfs.iyango.data.school.ygu

import com.gfs.iyango.core.model.CurriculumProgressSnapshot
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class YguCurriculumClientStatus {
    OK,
    LOGIN,
    RESTRICTED,
    INVALID,
    ERROR,
}

internal data class YguCurriculumOutcome(
    val status: YguCurriculumClientStatus,
    val snapshot: CurriculumProgressSnapshot? = null,
    val message: String = "",
    val cookieHeader: String = "",
)

/**
 * 阳光学院培养方案及学业完成情况 (`xxwcqkOnkclb.do?isdb=0`) HTTP 客户端。
 */
internal class YguCurriculumClient(
    private val get: suspend (String, String, String, Boolean) -> YguHttpResponse = YguHttp::get,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {

    private val cookieJar = YguCookieJar()

    suspend fun fetch(cookieHeader: String): YguCurriculumOutcome {
        cookieJar.clear()
        if (cookieJar.importHeader(cookieHeader).isNotEmpty() || !cookieJar.has("bzb_jsxsd")) {
            return YguCurriculumOutcome(
                status = YguCurriculumClientStatus.LOGIN,
                message = "登录状态已失效，请返回教务登录后重试",
            )
        }
        return try {
            var url = YguConfig.CURRICULUM_PROGRESS
            var referer = YguConfig.HOME
            var hops = 0
            val visited = linkedSetOf<String>()
            var response: YguHttpResponse
            while (true) {
                currentCoroutineContext().ensureActive()
                if (hops++ >= MAX_HOPS || !visited.add(url) || !YguConfig.allowedRequestUrl(url)) {
                    return finish(YguCurriculumClientStatus.INVALID, message = "培养方案页面跳转异常，请稍后重试")
                }
                response = get(url, cookieJar.header(), referer, false)
                cookieJar.absorb(response.setCookies)
                if (!cookieJar.has("bzb_jsxsd") || response.status == 401 || YguSession.isLoginPage(response.body)) {
                    return finish(YguCurriculumClientStatus.LOGIN, message = "登录状态已失效，请返回教务登录后重试")
                }
                if (response.status == 403) {
                    return finish(YguCurriculumClientStatus.RESTRICTED, message = "学校暂未开放培养方案查询，请稍后重试")
                }
                if (response.status == 0 || response.status >= 500) {
                    return finish(
                        YguCurriculumClientStatus.ERROR,
                        message = response.errorMessage.ifEmpty { "培养方案网络暂不可用，请检查网络后重试" },
                    )
                }
                if (response.status in 300..399) {
                    val location = response.location
                    if (location.isEmpty()) {
                        return finish(YguCurriculumClientStatus.INVALID, message = "培养方案页面暂不可读取，请稍后重试")
                    }
                    if (YguGradesQuery.isLoginDestination(location)) {
                        return finish(YguCurriculumClientStatus.LOGIN, message = "登录状态已失效，请返回教务登录后重试")
                    }
                    val resolved = YguSession.resolveUrl(location, url)
                    if (resolved.isEmpty() || !YguConfig.allowedRequestUrl(resolved)) {
                        return finish(YguCurriculumClientStatus.INVALID, message = "培养方案页面跳转越界，请稍后重试")
                    }
                    referer = url
                    url = resolved
                    continue
                }
                break
            }

            if (response.status !in 200..299 || response.body.isEmpty()) {
                return finish(YguCurriculumClientStatus.INVALID, message = "培养方案页面暂不可读取，请稍后重试")
            }

            val snapshot = YguCurriculumParser.parse(response.body, fetchedAtEpochMillis = clockMillis())
                ?: return if (YguSession.isLoginPage(response.body)) {
                    finish(YguCurriculumClientStatus.LOGIN, message = "登录状态已失效，请返回教务登录后重试")
                } else {
                    finish(YguCurriculumClientStatus.INVALID, message = "未能从教务页面解析培养方案数据")
                }

            finish(YguCurriculumClientStatus.OK, snapshot = snapshot)
        } finally {
            cookieJar.clear()
        }
    }

    private fun finish(
        status: YguCurriculumClientStatus,
        snapshot: CurriculumProgressSnapshot? = null,
        message: String = "",
    ): YguCurriculumOutcome = YguCurriculumOutcome(
        status = status,
        snapshot = snapshot,
        message = message,
        cookieHeader = cookieJar.header(),
    )

    private companion object {
        const val MAX_HOPS = 4
    }
}
