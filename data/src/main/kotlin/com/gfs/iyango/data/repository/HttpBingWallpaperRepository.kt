package com.gfs.iyango.data.repository

import com.gfs.iyango.core.domain.BingWallpaperInfo
import com.gfs.iyango.core.domain.BingWallpaperPort
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Singleton
class HttpBingWallpaperRepository @Inject constructor() : BingWallpaperPort {

    override suspend fun fetchToday(): Result<BingWallpaperInfo> = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (host in BING_HOSTS) {
            try {
                val endpoint = "$host$BING_API_PATH"
                val bytes = readBytes(endpoint, MAX_JSON_BYTES)
                val info = parseBingWallpaperJson(bytes.toString(Charsets.UTF_8), host)
                if (info != null) {
                    return@withContext Result.success(info)
                }
                lastError = IllegalStateException("必应壁纸响应数据为空")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                lastError = error
            }
        }
        Result.failure(lastError ?: IOException("无法获取必应每日壁纸"))
    }

    override suspend fun downloadImage(url: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("https://") && !cleanUrl.startsWith("http://")) {
            return@withContext Result.failure(IllegalArgumentException("无效的图片地址"))
        }
        try {
            val bytes = readBytes(cleanUrl, MAX_IMAGE_BYTES)
            if (bytes.isEmpty()) {
                Result.failure(IOException("下载的壁纸内容为空"))
            } else {
                Result.success(bytes)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun readBytes(endpoint: String, maxBytes: Int): ByteArray {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json,image/*;q=0.8,*/*;q=0.5")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code")
            }
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val output = ByteArrayOutputStream()
            conn.inputStream.use { stream ->
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    if (output.size() + read > maxBytes) {
                        throw IOException("响应体超过大小限制 (${maxBytes / (1024 * 1024)} MB)")
                    }
                    output.write(buffer, 0, read)
                }
            }
            return output.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
        const val MAX_JSON_BYTES = 512 * 1024
        const val MAX_IMAGE_BYTES = 15 * 1024 * 1024
        const val USER_AGENT = "iYango/1.0 (Android)"
        const val BING_API_PATH = "/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=zh-CN"
        val BING_HOSTS = listOf("https://cn.bing.com", "https://www.bing.com")
    }
}

private val BING_JSON = Json { ignoreUnknownKeys = true }

@Serializable
private data class BingApiResponse(
    val images: List<BingImageDto> = emptyList(),
)

@Serializable
private data class BingImageDto(
    val startdate: String = "",
    val url: String = "",
    val urlbase: String = "",
    val copyright: String = "",
    val title: String = "",
)

/** 解析微软 Bing HPImageArchive JSON，优先返回 UHD 原图链接与标准分辨率兜底链接。 */
internal fun parseBingWallpaperJson(
    jsonText: String,
    baseHost: String = "https://cn.bing.com",
): BingWallpaperInfo? {
    if (jsonText.isBlank()) return null
    val parsed = runCatching { BING_JSON.decodeFromString(BingApiResponse.serializer(), jsonText) }
        .getOrNull() ?: return null
    val item = parsed.images.firstOrNull() ?: return null
    val host = baseHost.trimEnd('/')
    fun fullUrl(pathOrUrl: String): String {
        val trimmed = pathOrUrl.trim()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "$host/${trimmed.trimStart('/')}"
        }
    }

    val urlBase = item.urlbase.trim()
    val stdUrl = fullUrl(item.url)
    val imageUrl: String
    val fallbackUrl: String
    if (urlBase.isNotEmpty()) {
        imageUrl = fullUrl("${urlBase}_UHD.jpg")
        fallbackUrl = if (stdUrl != imageUrl) stdUrl else ""
    } else if (stdUrl.isNotEmpty()) {
        imageUrl = stdUrl
        fallbackUrl = ""
    } else {
        return null
    }

    return BingWallpaperInfo(
        date = item.startdate.trim(),
        title = item.title.trim(),
        copyright = item.copyright.trim(),
        imageUrl = imageUrl,
        fallbackUrl = fallbackUrl,
    )
}
