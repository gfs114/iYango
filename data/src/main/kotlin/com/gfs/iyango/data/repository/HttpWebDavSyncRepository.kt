package com.gfs.iyango.data.repository

import android.content.Context
import com.gfs.iyango.core.domain.WebDavBackupPreview
import com.gfs.iyango.core.domain.WebDavConfig
import com.gfs.iyango.core.domain.WebDavCredentialStore
import com.gfs.iyango.core.domain.WebDavPreset
import com.gfs.iyango.core.domain.WebDavRemoteBackup
import com.gfs.iyango.core.domain.WebDavSyncPort
import com.gfs.iyango.data.backup.FullCloudBackupCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 基于 OkHttp + Keystore 的 WebDAV 云备份同步仓储。
 */
@Singleton
class HttpWebDavSyncRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentialStore: WebDavCredentialStore,
    private val backupCodec: FullCloudBackupCodec,
) : WebDavSyncPort {

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private val _config = MutableStateFlow(loadStoredConfig())
    override val config: StateFlow<WebDavConfig> = _config.asStateFlow()

    override suspend fun saveConfig(config: WebDavConfig, password: String?) {
        withContext(Dispatchers.IO) {
            val cleaned = config.copy(
                serverUrl = config.serverUrl.trim(),
                username = config.username.trim(),
                directory = config.directory.trim().trim('/'),
            )
            persistConfig(cleaned)
            _config.value = cleaned
            if (password != null) {
                credentialStore.savePassword(password)
            }
        }
    }

    override suspend fun testConnection(config: WebDavConfig, password: String?): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val pwd = password?.takeIf { it.isNotEmpty() } ?: credentialStore.loadPassword()
                val dirUrl = WebDavUrlSecurity.buildDirectoryUrl(config.serverUrl, config.directory)
                    .getOrThrow()
                ensureRemoteDirectory(dirUrl, config.username.trim(), pwd)
                val response = propfind(dirUrl, config.username.trim(), pwd)
                if (response !in 200..299) {
                    error("WebDAV 连接失败（HTTP $response）")
                }
            }
        }

    override suspend fun backupNow(): Result<String> = withContext(Dispatchers.IO) {
        val current = _config.value
        val result = runCatching {
            val pwd = credentialStore.loadPassword()
            val dirUrl = WebDavUrlSecurity.buildDirectoryUrl(current.serverUrl, current.directory)
                .getOrThrow()
            ensureRemoteDirectory(dirUrl, current.username.trim(), pwd)
            val bytes = backupCodec.exportBytes()
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val fileName = "iyango-backup-$timestamp.json"
            val fileUrl = "$dirUrl/$fileName"
            val request = Request.Builder()
                .url(fileUrl)
                .header("Authorization", Credentials.basic(current.username.trim(), pwd))
                .put(bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error("上传备份失败（HTTP ${resp.code}）")
                }
            }
            val now = System.currentTimeMillis()
            val updated = current.copy(lastSyncAtMillis = now, lastError = "")
            persistConfig(updated)
            _config.value = updated
            fileName
        }
        result.onFailure { err ->
            val updated = current.copy(lastError = err.message ?: "上传备份失败")
            persistConfig(updated)
            _config.value = updated
        }
        result
    }

    override suspend fun listBackups(): Result<List<WebDavRemoteBackup>> = withContext(Dispatchers.IO) {
        runCatching {
            val current = _config.value
            val pwd = credentialStore.loadPassword()
            val dirUrl = WebDavUrlSecurity.buildDirectoryUrl(current.serverUrl, current.directory)
                .getOrThrow()
            val auth = Credentials.basic(current.username.trim(), pwd)
            val body = """
                <?xml version="1.0" encoding="utf-8" ?>
                <D:propfind xmlns:D="DAV:">
                  <D:prop>
                    <D:getcontentlength/>
                    <D:getlastmodified/>
                  </D:prop>
                </D:propfind>
            """.trimIndent().toRequestBody("application/xml; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(dirUrl)
                .header("Authorization", auth)
                .header("Depth", "1")
                .method("PROPFIND", body)
                .build()
            val xml = client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 207) {
                    error("读取远端目录失败（HTTP ${resp.code}）")
                }
                resp.body.string()
            }
            WebDavPropfindParser.parse(xml, dirUrl)
        }
    }

    override suspend fun downloadPreview(
        remote: WebDavRemoteBackup,
    ): Result<Pair<WebDavBackupPreview, ByteArray>> = withContext(Dispatchers.IO) {
        runCatching {
            val current = _config.value
            val pwd = credentialStore.loadPassword()
            val targetUrl = WebDavUrlSecurity.resolveSafeFileUrl(current.serverUrl, remote.href, current.directory, remote.fileName)
                .getOrThrow()
            val request = Request.Builder()
                .url(targetUrl)
                .header("Authorization", Credentials.basic(current.username.trim(), pwd))
                .get()
                .build()
            val bytes = client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error("下载备份失败（HTTP ${resp.code}）")
                }
                val raw = resp.body.bytes()
                if (raw.size > MAX_BACKUP_BYTES) {
                    error("备份文件超过 15MB 限制")
                }
                raw
            }
            val preview = backupCodec.preview(remote.fileName, bytes)
            preview to bytes
        }
    }

    override suspend fun restoreFromBytes(bytes: ByteArray): Result<WebDavBackupPreview> =
        withContext(Dispatchers.IO) {
            runCatching {
                backupCodec.restoreFromBytes("webdav-restore.json", bytes)
            }
        }

    override suspend fun syncIfDue() {
        val current = _config.value
        if (!current.autoSyncEnabled) return
        if (current.serverUrl.isBlank() || current.username.isBlank()) return
        val elapsed = System.currentTimeMillis() - current.lastSyncAtMillis
        if (elapsed < AUTO_SYNC_INTERVAL_MS) return
        val pwd = credentialStore.loadPassword()
        if (pwd.isBlank()) return
        backupNow()
    }

    private fun ensureRemoteDirectory(dirUrl: String, username: String, password: String) {
        val code = mkcol(dirUrl, username, password)
        if (code in 200..299 || code == 405) return
        if (code == 409) {
            val parent = dirUrl.substringBeforeLast('/')
            if (parent.startsWith("http", ignoreCase = true)) {
                mkcol(parent, username, password)
                mkcol(dirUrl, username, password)
            }
        }
    }

    private fun mkcol(url: String, username: String, password: String): Int {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(username, password))
            .method("MKCOL", ByteArray(0).toRequestBody(null))
            .build()
        return client.newCall(request).execute().use { it.code }
    }

    private fun propfind(url: String, username: String, password: String): Int {
        val body = """
            <?xml version="1.0" encoding="utf-8" ?>
            <D:propfind xmlns:D="DAV:"><D:prop><D:resourcetype/></D:prop></D:propfind>
        """.trimIndent().toRequestBody("application/xml; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(username, password))
            .header("Depth", "0")
            .method("PROPFIND", body)
            .build()
        return client.newCall(request).execute().use { it.code }
    }

    private fun loadStoredConfig(): WebDavConfig {
        val presetName = prefs.getString("preset", WebDavPreset.JIANGUOYUN.name)
            ?: WebDavPreset.JIANGUOYUN.name
        val preset = runCatching { WebDavPreset.valueOf(presetName) }
            .getOrDefault(WebDavPreset.JIANGUOYUN)
        return WebDavConfig(
            preset = preset,
            serverUrl = prefs.getString("serverUrl", WebDavPreset.JIANGUOYUN.defaultUrl)
                ?: WebDavPreset.JIANGUOYUN.defaultUrl,
            username = prefs.getString("username", "").orEmpty(),
            directory = prefs.getString("directory", "iYango").orEmpty().ifBlank { "iYango" },
            autoSyncEnabled = prefs.getBoolean("autoSyncEnabled", false),
            lastSyncAtMillis = prefs.getLong("lastSyncAtMillis", 0L),
            lastError = prefs.getString("lastError", "").orEmpty(),
        )
    }

    private fun persistConfig(config: WebDavConfig) {
        prefs.edit()
            .putString("preset", config.preset.name)
            .putString("serverUrl", config.serverUrl)
            .putString("username", config.username)
            .putString("directory", config.directory)
            .putBoolean("autoSyncEnabled", config.autoSyncEnabled)
            .putLong("lastSyncAtMillis", config.lastSyncAtMillis)
            .putString("lastError", config.lastError)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "iyango_webdav_config"
        private const val AUTO_SYNC_INTERVAL_MS = 6L * 60L * 60L * 1000L
        private const val MAX_BACKUP_BYTES = 15 * 1024 * 1024
    }
}

internal object WebDavUrlSecurity {

    fun validateServerUrl(raw: String): Result<URI> = runCatching {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "WebDAV 地址不能为空" }
        val uri = URI(trimmed)
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        require(host.isNotEmpty()) { "WebDAV 地址缺少主机名" }
        when (scheme) {
            "https" -> uri
            "http" -> {
                require(isPrivateLanHost(host)) {
                    "公网 WebDAV 必须使用 HTTPS，明文 HTTP 仅允许局域网或本机地址"
                }
                uri
            }
            else -> error("不支持的协议：$scheme，请使用 HTTPS")
        }
    }

    fun buildDirectoryUrl(serverUrl: String, directory: String): Result<String> = runCatching {
        val uri = validateServerUrl(serverUrl).getOrThrow()
        val base = uri.toString().trimEnd('/')
        val cleanDir = directory.trim().trim('/')
        if (cleanDir.isEmpty()) base else "$base/$cleanDir"
    }

    fun resolveSafeFileUrl(
        serverUrl: String,
        href: String,
        directory: String,
        fileName: String,
    ): Result<String> = runCatching {
        val dirUrl = buildDirectoryUrl(serverUrl, directory).getOrThrow()
        val serverUri = validateServerUrl(serverUrl).getOrThrow()
        if (href.startsWith("http://", ignoreCase = true) || href.startsWith("https://", ignoreCase = true)) {
            val hrefUri = URI(href)
            require(hrefUri.host?.equals(serverUri.host, ignoreCase = true) == true) {
                "远端文件路径超出当前服务器域名"
            }
            hrefUri.toString()
        } else if (href.startsWith("/")) {
            val portPart = if (serverUri.port != -1) ":${serverUri.port}" else ""
            "${serverUri.scheme}://${serverUri.host}$portPart$href"
        } else {
            val safeName = fileName.trimStart('/')
            "$dirUrl/$safeName"
        }
    }

    internal fun isPrivateLanHost(host: String): Boolean {
        val clean = host.trim('[', ']')
        if (clean == "localhost" || clean == "127.0.0.1" || clean == "::1" || clean.endsWith(".local")) {
            return true
        }
        val ipv4 = runCatching { InetAddress.getByName(clean) as? Inet4Address }.getOrNull() ?: return false
        val b = ipv4.address
        val first = b[0].toInt() and 0xFF
        val second = b[1].toInt() and 0xFF
        return first == 10 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168) ||
            (first == 127)
    }
}

internal object WebDavPropfindParser {

    private val responseRegex = Regex(
        """<(?:\w+:)?response\b[^>]*>(.*?)</(?:\w+:)?response>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val hrefRegex = Regex(
        """<(?:\w+:)?href\b[^>]*>(.*?)</(?:\w+:)?href>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val lengthRegex = Regex(
        """<(?:\w+:)?getcontentlength\b[^>]*>(\d+)</(?:\w+:)?getcontentlength>""",
        RegexOption.IGNORE_CASE,
    )
    private val modifiedRegex = Regex(
        """<(?:\w+:)?getlastmodified\b[^>]*>(.*?)</(?:\w+:)?getlastmodified>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    fun parse(xml: String, dirUrl: String): List<WebDavRemoteBackup> {
        val dirPath = runCatching { URI(dirUrl).path.trimEnd('/') }.getOrDefault("")
        return responseRegex.findAll(xml).mapNotNull { match ->
            val block = match.groupValues[1]
            val rawHref = hrefRegex.find(block)?.groupValues?.getOrNull(1)?.trim() ?: return@mapNotNull null
            val cleanHref = rawHref.trimEnd('/')
            if (cleanHref.isEmpty() || (dirPath.isNotEmpty() && cleanHref.endsWith(dirPath))) {
                return@mapNotNull null
            }
            val fileName = cleanHref.substringAfterLast('/')
            if (!fileName.endsWith(".json", ignoreCase = true)) return@mapNotNull null
            val size = lengthRegex.find(block)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            val modified = modifiedRegex.find(block)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            WebDavRemoteBackup(
                fileName = fileName,
                href = rawHref,
                sizeBytes = size,
                lastModified = modified,
            )
        }.sortedByDescending { it.fileName }.toList()
    }
}
