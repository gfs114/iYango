package com.gfs.iyango.data.school.ygu

import java.net.URLEncoder
import java.util.Base64

/** 直接凭据登录的结果；rejected 表示学校明确拒绝账号密码。 */
internal sealed interface YguLoginOutcome {
    data class Success(val cookieHeader: String) : YguLoginOutcome
    data class Rejected(val message: String) : YguLoginOutcome
    data class Failed(val message: String) : YguLoginOutcome
}

/**
 * 官方页面“立即登录”按钮的同款登录流程。
 *
 * 登录页里的 scode/sxh 是每次响应新生成的编码材料；提交三次 Base64 段
 * 交错后的 encoded，随后手工跟随同源重定向并读取框架页确认会话。
 */
internal object YguDirectLogin {

    private const val SPLICE_LIMIT = 55
    private const val DOG_ENCODED = "IA=="
    private const val MAX_REDIRECTS = 8

    private val SCOPE = Regex("var\\s+scode\\s*=\\s*\"([A-Za-z0-9]{40,512})\"")
    private val SXH = Regex("var\\s+sxh\\s*=\\s*\"([0-9]{55})\"")
    private val SHOW_MSG = Regex("""\bid\s*=\s*["']showMsg["'][^>]*>([^<]{1,120})<""")
    private const val CREDENTIAL_FIELD = "(?:用户名|用户|[账帐][号户]|学号|密码)"
    private val CREDENTIAL_REJECTION = Regex(
        """^(?:登录失败[：:，,]\s*)?(?:您输入的)?$CREDENTIAL_FIELD(?:[或和、/]$CREDENTIAL_FIELD)?""" +
            """(?:输入)?(?:错误|不正确|有误|不存在)(?=[\s！!。，,；;.]|$)""",
    )

    suspend fun login(accountInput: String, passwordInput: String): YguLoginOutcome {
        val account = normalizeHalfWidth(accountInput.trim())
        val password = normalizeHalfWidth(passwordInput)
        if (account.isEmpty() || password.isEmpty()) {
            return YguLoginOutcome.Failed("请输入教务账号和密码")
        }
        val jar = YguCookieJar()

        val page = YguHttp.get(YguConfig.LOGIN, referer = YguConfig.LOGIN)
        jar.absorb(page.setCookies)
        val settledPage = followRedirects(jar, page, YguConfig.LOGIN)
        if (settledPage.status !in 200..299 || settledPage.body.isEmpty()) {
            val reason = settledPage.errorMessage.ifEmpty { "HTTP ${settledPage.status}" }
            return YguLoginOutcome.Failed("准备教务登录失败：$reason")
        }
        val scode = SCOPE.find(settledPage.body)?.groupValues?.get(1)
        val sxh = SXH.find(settledPage.body)?.groupValues?.get(1)
        if (scode == null || sxh == null) {
            return YguLoginOutcome.Failed("未能识别教务登录页，请稍后重试")
        }

        val encoded = buildEncoded(account, password, scode, sxh)
        val form = buildLoginForm(account, password, encoded)
        val logon = YguHttp.post(YguConfig.LOGON, form, jar.header(), YguConfig.LOGIN)
        jar.absorb(logon.setCookies)
        if (logon.status == 0 || logon.status >= 400) {
            val reason = logon.errorMessage.ifEmpty { "HTTP ${logon.status}" }
            return YguLoginOutcome.Failed("登录请求失败：$reason")
        }
        val settledLogon = followRedirects(jar, logon, YguConfig.LOGON)
        if (YguSession.isLoginPage(settledLogon.body)) {
            return loginPageFailure(settledLogon.body)
        }
        if (settledLogon.status in 200..299 &&
            YguSession.isAuthenticated(settledLogon.body) &&
            jar.has("bzb_jsxsd")
        ) {
            return YguLoginOutcome.Success(jar.header())
        }

        val wrapper = YguHttp.get(YguConfig.WRAPPER, jar.header(), YguConfig.LOGON)
        jar.absorb(wrapper.setCookies)
        val settledWrapper = followRedirects(jar, wrapper, YguConfig.WRAPPER)
        if (YguSession.isLoginPage(settledWrapper.body)) {
            return loginPageFailure(settledWrapper.body)
        }
        if (settledWrapper.status !in 200..299 || !YguSession.isAuthenticated(settledWrapper.body)) {
            return YguLoginOutcome.Failed("无法确认教务登录状态，请稍后重试")
        }
        if (!jar.has("bzb_jsxsd")) {
            return YguLoginOutcome.Failed("教务会话未能建立，请稍后重试")
        }
        return YguLoginOutcome.Success(jar.header())
    }

    /**
     * 教务登录页的表单字段。字段名来自学校页面约定，值全部来自本次登录输入，
     * 逐个 URL 编码后以 & 连接。
     */
    private fun buildLoginForm(account: String, password: String, encoded: String): String =
        listOf(
            "loginMethod" to "LoginToXkLdap",
            "userlanguage" to "0",
            "userAccount" to account,
            "userPassword" to password,
            "encoded" to encoded,
        ).joinToString("&") { (name, value) ->
            "$name=${URLEncoder.encode(value, "UTF-8")}"
        }

    /** 页面的编码函数：三段 Base64 用 %%% 连接后按 sxh 数字插入 scode 片段。 */
    fun buildEncoded(account: String, password: String, scode: String, sxh: String): String {
        val code = base64(account) + "%%%" + base64(password) + "%%%" + DOG_ENCODED
        return interleave(code, scode, sxh)
    }

    fun base64(input: String): String =
        if (input.isEmpty()) "" else Base64.getEncoder().encodeToString(input.toByteArray(Charsets.UTF_8))

    fun interleave(code: String, scode: String, sxh: String): String {
        var rest = scode
        val encoded = StringBuilder()
        for (i in code.indices) {
            if (i < SPLICE_LIMIT) {
                val digit = if (i < sxh.length) sxh[i].toString() else ""
                val take = if (digit.isEmpty()) 0 else digit.toIntOrNull() ?: 0
                encoded.append(code[i])
                encoded.append(rest.take(take))
                rest = rest.substring(take.coerceAtMost(rest.length))
            } else {
                encoded.append(code.substring(i))
                break
            }
        }
        return encoded.toString()
    }

    fun normalizeHalfWidth(value: String): String {
        val result = StringBuilder(value.length)
        for (character in value) {
            val code = character.code
            when {
                code in 0xFF01..0xFF5E -> result.append((code - 0xFEE0).toChar())
                code == 0x3000 -> result.append(' ')
                else -> result.append(character)
            }
        }
        return result.toString()
    }

    fun loginPageFailure(body: String): YguLoginOutcome {
        val schoolMessage = SHOW_MSG.find(body)?.groupValues?.get(1)?.let(YguHtml::decode)?.trim().orEmpty()
        return if (CREDENTIAL_REJECTION.containsMatchIn(schoolMessage)) {
            YguLoginOutcome.Rejected(schoolMessage)
        } else {
            YguLoginOutcome.Failed(schoolMessage.ifEmpty { "教务系统未确认登录，请稍后重试" })
        }
    }

    private suspend fun followRedirects(
        jar: YguCookieJar,
        response: YguHttpResponse,
        referer: String,
    ): YguHttpResponse {
        var current = response
        var currentUrl = referer
        repeat(MAX_REDIRECTS) {
            if (current.status !in 300..399) return current
            val location = current.location
            val target = if (location == "/" || location == "${YguConfig.ORIGIN}/") {
                "${YguConfig.ORIGIN}/"
            } else {
                YguSession.resolveUrl(location, currentUrl)
            }
            if (target.isEmpty()) {
                return YguHttpResponse(0, "", emptyList(), "", "教务重定向地址无效")
            }
            val next = YguHttp.get(target, jar.header(), currentUrl)
            jar.absorb(next.setCookies)
            current = next
            currentUrl = target
        }
        if (current.status in 300..399) {
            return YguHttpResponse(0, "", emptyList(), "", "教务重定向次数过多")
        }
        return current
    }
}
