package com.gfs.iyango.data.school.ygu

/** 会话验证结论。 */
internal sealed interface YguSessionState {
    data class Active(val cookieHeader: String) : YguSessionState
    data object Expired : YguSessionState
    data class Failed(val message: String) : YguSessionState
}

/**
 * 用框架页确认 Cookie 会话是否仍可用；这是课表/成绩/教室读取前的统一门槛。
 */
internal object YguSessionClient {

    private const val MAX_REDIRECTS = 3

    suspend fun verify(cookieHeader: String): YguSessionState {
        val jar = YguCookieJar()
        if (jar.importHeader(cookieHeader).isNotEmpty() || !jar.has("bzb_jsxsd")) {
            return YguSessionState.Expired
        }
        var response = YguHttp.get(YguConfig.WRAPPER, jar.header(), YguConfig.HOME)
        jar.absorb(response.setCookies)
        var url = YguConfig.WRAPPER
        var redirects = 0
        while (response.status in 300..399 && redirects < MAX_REDIRECTS) {
            val target = YguSession.resolveUrl(response.location, url)
            if (target.isEmpty()) return YguSessionState.Failed("登录状态确认失败，请稍后重试")
            response = YguHttp.get(target, jar.header(), url)
            jar.absorb(response.setCookies)
            url = target
            redirects++
        }
        if (YguSession.isLoginPage(response.body) || !jar.has("bzb_jsxsd")) {
            return YguSessionState.Expired
        }
        if (response.status !in 200..299) {
            return if (response.status == 0) {
                YguSessionState.Failed(response.errorMessage.ifEmpty { "教务网络暂不可用，请检查网络后重试" })
            } else {
                YguSessionState.Failed("登录状态确认失败，请稍后重试")
            }
        }
        if (!YguSession.isAuthenticated(response.body)) {
            return YguSessionState.Expired
        }
        return YguSessionState.Active(jar.header())
    }
}
