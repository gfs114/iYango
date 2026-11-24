package com.gfs.iyango.data.school.ygu

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** 一次学校请求的原始结果；状态 0 表示请求未完成。 */
internal class YguHttpResponse(
    val status: Int,
    val body: String,
    val setCookies: List<String>,
    val location: String,
    val errorMessage: String = "",
) {
    val successful: Boolean
        get() = (status in 200..299 && body.isNotEmpty()) ||
            (status in 300..399 && location.isNotEmpty())
}

/**
 * 学校站点的 HTTP 传输层：单例连接池复用（Keep-Alive / TLS 会话复用 + 自动 Gzip）、
 * 固定 UA、手工 Cookie、禁用自动重定向。
 *
 * 所有 URL 在校验后才发出；请求失败只返回状态 0 与面向用户的简短原因，
 * 不记录 URL 查询串、凭据或响应正文。
 */
internal object YguHttp {

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    private const val CONNECT_TIMEOUT_SEC = 10L
    private const val READ_TIMEOUT_SEC = 20L
    private const val WRITE_TIMEOUT_SEC = 15L
    private const val MAX_BODY_BYTES = 2_097_152
    private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
    }

    suspend fun get(
        url: String,
        cookieHeader: String = "",
        referer: String = "",
        schoolAjax: Boolean = false,
    ): YguHttpResponse = request("GET", url, "", cookieHeader, referer, schoolAjax)

    suspend fun post(
        url: String,
        body: String,
        cookieHeader: String = "",
        referer: String = "",
        schoolAjax: Boolean = false,
    ): YguHttpResponse = request("POST", url, body, cookieHeader, referer, schoolAjax)

    private suspend fun request(
        method: String,
        url: String,
        body: String,
        cookieHeader: String,
        referer: String,
        schoolAjax: Boolean,
    ): YguHttpResponse {
        if (!YguConfig.allowedRequestUrl(url)) {
            return YguHttpResponse(0, "", emptyList(), "", "教务地址不在允许范围内")
        }
        val validTarget = runCatching { URL(url) }.getOrNull()
        if (validTarget == null || validTarget.protocol != "https" || validTarget.host != "jwgl.ygu.edu.cn") {
            return YguHttpResponse(0, "", emptyList(), "", "教务地址不在允许范围内")
        }
        val builder = Request.Builder()
            .url(url)
            .header(
                "Accept",
                if (schoolAjax) {
                    "application/json, text/javascript, */*; q=0.01"
                } else {
                    "text/html,application/json,*/*"
                },
            )
            .header("User-Agent", USER_AGENT)
            .header("Cache-Control", "no-cache")
        if (schoolAjax) builder.header("X-Requested-With", "XMLHttpRequest")
        if (cookieHeader.isNotEmpty()) builder.header("Cookie", cookieHeader)
        if (referer.isNotEmpty()) builder.header("Referer", referer)

        if (method == "POST") {
            builder.post(body.toByteArray(Charsets.UTF_8).toRequestBody(FORM_MEDIA_TYPE))
        } else {
            builder.get()
        }

        val request = builder.build()
        return withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                val call = client.newCall(request)
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) {
                            cont.resume(YguHttpResponse(0, "", emptyList(), "", describeFailure(e)))
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val parsed = response.use { res ->
                            try {
                                val status = res.code
                                val setCookies = res.headers("Set-Cookie")
                                val location = res.header("Location").orEmpty()
                                val text = res.body.byteStream().use(::readLimited)
                                YguHttpResponse(status, text, setCookies, location)
                            } catch (error: Exception) {
                                YguHttpResponse(0, "", emptyList(), "", describeFailure(error))
                            }
                        }
                        if (cont.isActive) cont.resume(parsed)
                    }
                })
            }
        }
    }

    private fun readLimited(stream: InputStream): String {
        val buffer = ByteArray(8192)
        val output = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BODY_BYTES) break
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun describeFailure(error: Exception): String = when (error) {
        is java.net.SocketTimeoutException -> "网络请求超时"
        is javax.net.ssl.SSLException -> "安全连接失败，请检查网络后重试"
        is java.net.UnknownHostException -> "无法连接教务服务器，请检查网络后重试"
        else -> "网络请求失败，请检查网络后重试"
    }
}
