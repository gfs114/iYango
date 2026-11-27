package com.gfs.iyango.data.update

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.gfs.iyango.core.domain.AppUpdateCheckState
import com.gfs.iyango.core.domain.AppUpdateDownloadState
import com.gfs.iyango.core.domain.AppUpdatePort
import com.gfs.iyango.core.domain.AppUpdateRelease
import com.gfs.iyango.core.domain.AppVersionComparator
import com.gfs.iyango.core.domain.DiagnosticLogPort
import com.gfs.iyango.core.domain.UpdateDownloadMirror
import com.gfs.iyango.core.model.DiagnosticEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 基于独立 [OkHttpClient] 的应用更新检测仓储。
 *
 * - 每次打开应用（[checkOnLaunch]）在节流窗口外自动检测更新；
 * - 未配置 [AppUpdateConfig.UPDATE_CHECK_URL] 且无调试覆盖时静默返回 [AppUpdateCheckState.NotConfigured]；
 * - 启动自动检测失败仅记录诊断日志与内部状态，不弹出打扰用户的错误弹窗。
 */
@Singleton
class HttpAppUpdateRepository internal constructor(
    private val diagnostics: DiagnosticLogPort,
    private val clock: Clock,
    private val currentVersionCodeProvider: () -> Long,
    private val currentVersionNameProvider: () -> String,
    private val prefsProvider: () -> SharedPreferences,
    private val defaultEndpointProvider: () -> String,
    private val httpGet: suspend (url: String, userAgent: String) -> String,
    private val downloadCacheDirProvider: () -> File = {
        File(System.getProperty("java.io.tmpdir"), "iyango_updates")
    },
    private val httpDownload: suspend (
        url: String,
        userAgent: String,
        destFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long, bytesPerSecond: Long) -> Unit,
    ) -> Unit = ::defaultHttpDownload,
) : AppUpdatePort {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        diagnostics: DiagnosticLogPort,
        clock: Clock,
    ) : this(
        diagnostics = diagnostics,
        clock = clock,
        currentVersionCodeProvider = { readPackageVersion(context).second },
        currentVersionNameProvider = { readPackageVersion(context).first },
        prefsProvider = { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) },
        defaultEndpointProvider = { AppUpdateConfig.UPDATE_CHECK_URL },
        httpGet = ::defaultHttpGet,
        downloadCacheDirProvider = { File(context.cacheDir, "app_updates") },
    )

    private val mutex = Mutex()
    private val historyMutex = Mutex()
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    @Volatile
    private var activeDownloadToken: Long = 0L

    private val _state = MutableStateFlow<AppUpdateCheckState>(AppUpdateCheckState.Idle)
    override val state: StateFlow<AppUpdateCheckState> = _state.asStateFlow()

    private val _promptRelease = MutableStateFlow<AppUpdateRelease?>(null)
    override val promptRelease: StateFlow<AppUpdateRelease?> = _promptRelease.asStateFlow()

    override val currentVersionName: String
        get() = currentVersionNameProvider().ifBlank { FALLBACK_VERSION_NAME }

    override val currentVersionCode: Long
        get() = currentVersionCodeProvider()

    private val _downloadState = MutableStateFlow<AppUpdateDownloadState>(AppUpdateDownloadState.Idle)
    override val downloadState: StateFlow<AppUpdateDownloadState> = _downloadState.asStateFlow()

    private val _downloadMirror = MutableStateFlow(
        UpdateDownloadMirror.fromId(prefsProvider().getString(KEY_DOWNLOAD_MIRROR, null)),
    )
    override val downloadMirror: StateFlow<UpdateDownloadMirror> = _downloadMirror.asStateFlow()

    private val _autoCheckEnabled = MutableStateFlow(
        prefsProvider().getBoolean(KEY_AUTO_CHECK_ENABLED, true),
    )
    override val autoCheckEnabled: StateFlow<Boolean> = _autoCheckEnabled.asStateFlow()

    private val _releaseHistory = MutableStateFlow<List<AppUpdateRelease>>(emptyList())
    override val releaseHistory: StateFlow<List<AppUpdateRelease>> = _releaseHistory.asStateFlow()

    private val _historyLoading = MutableStateFlow(false)
    override val historyLoading: StateFlow<Boolean> = _historyLoading.asStateFlow()

    private var lastAutoCheckAt: Long? = null
    private var dismissedSessionVersionKey: String? = null

    override suspend fun configuredUrl(): String = withContext(Dispatchers.IO) {
        effectiveEndpoint()
    }

    override suspend fun checkOnLaunch(force: Boolean) {
        if (!force && !_autoCheckEnabled.value) return
        executeCheck(manual = false, forceAuto = force)
    }

    override suspend fun checkManually(): AppUpdateCheckState =
        executeCheck(manual = true, forceAuto = true)

    override suspend fun loadReleaseHistory(force: Boolean) {
        withContext(Dispatchers.IO) {
            historyMutex.withLock {
                if (!force && _releaseHistory.value.isNotEmpty()) return@withLock
                val listUrl = AppUpdateManifestParser.deriveReleasesListUrl(effectiveEndpoint())
                    ?: return@withLock
                if (!AppUpdateManifestParser.isSafeExternalUrl(listUrl)) return@withLock
                val startedAt = clock.millis()
                _historyLoading.value = true
                try {
                    val ua = "iYango/$currentVersionName (Android)"
                    val body = httpGet(listUrl, ua)
                    val releases = AppUpdateManifestParser.parseReleaseList(body)
                    _releaseHistory.value = releases
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val msg = error.message?.trim()?.takeIf { it.isNotEmpty() } ?: "获取历史版本失败"
                    recordDiagnostic("FAILED", error.javaClass.simpleName, startedAt, msg)
                } finally {
                    _historyLoading.value = false
                }
            }
        }
    }

    override fun dismissPrompt() {
        val current = _promptRelease.value
        if (current != null && !current.forceUpdate) {
            dismissedSessionVersionKey = current.versionKey
        }
        _promptRelease.value = null
    }

    override suspend fun ignoreVersion(versionKey: String) {
        withContext(Dispatchers.IO) {
            val clean = versionKey.trim()
            if (clean.isNotEmpty()) {
                prefsProvider().edit().putString(KEY_IGNORED_VERSION, clean).apply()
            }
            if (_promptRelease.value?.versionKey == clean) {
                _promptRelease.value = null
            }
        }
    }

    override suspend fun setCustomEndpointOverride(url: String) {
        withContext(Dispatchers.IO) {
            val trimmed = url.trim()
            val editor = prefsProvider().edit()
            if (trimmed.isEmpty()) {
                editor.remove(KEY_CUSTOM_ENDPOINT)
            } else {
                editor.putString(KEY_CUSTOM_ENDPOINT, trimmed)
            }
            editor.apply()
            lastAutoCheckAt = null
            _releaseHistory.value = emptyList()
        }
    }

    override suspend fun setDownloadMirror(mirror: UpdateDownloadMirror) {
        withContext(Dispatchers.IO) {
            prefsProvider().edit().putString(KEY_DOWNLOAD_MIRROR, mirror.id).apply()
            _downloadMirror.value = mirror
        }
    }

    override suspend fun setAutoCheckEnabled(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            prefsProvider().edit().putBoolean(KEY_AUTO_CHECK_ENABLED, enabled).apply()
            _autoCheckEnabled.value = enabled
        }
    }

    override fun startDownload(release: AppUpdateRelease) {
        val versionKey = release.versionKey
        val rawUrl = release.downloadUrl.trim()
        if (!AppUpdateManifestParser.isSafeExternalUrl(rawUrl)) {
            _downloadState.value = AppUpdateDownloadState.Failed(
                versionKey = versionKey,
                message = "当前版本未附带安装包，请使用浏览器打开",
            )
            return
        }
        val resolvedUrl = _downloadMirror.value.resolveUrl(rawUrl)
        if (!AppUpdateManifestParser.isSafeExternalUrl(resolvedUrl)) {
            _downloadState.value = AppUpdateDownloadState.Failed(
                versionKey = versionKey,
                message = "下载地址无效，请切换下载源重试",
            )
            return
        }

        val token = activeDownloadToken + 1L
        activeDownloadToken = token
        downloadJob?.cancel()
        _downloadState.value = AppUpdateDownloadState.Downloading(
            versionKey = versionKey,
            downloadedBytes = 0L,
            totalBytes = release.apkSizeBytes.coerceAtLeast(0L),
            bytesPerSecond = 0L,
        )

        downloadJob = downloadScope.launch {
            val dir = downloadCacheDirProvider()
            val targetFile = File(dir, buildApkFileName(versionKey))
            val partFile = File(dir, "${targetFile.name}.part")
            val startedAt = clock.millis()
            try {
                if (!dir.exists() && !dir.mkdirs()) {
                    throw IOException("无法创建下载目录")
                }
                if (targetFile.exists() && targetFile.length() > 0L && hasApkMagicHeader(targetFile)) {
                    val expectedSize = release.apkSizeBytes
                    if (expectedSize <= 0L || targetFile.length() == expectedSize) {
                        if (activeDownloadToken == token) {
                            _downloadState.value = AppUpdateDownloadState.ReadyToInstall(
                                versionKey = versionKey,
                                apkFilePath = targetFile.absolutePath,
                                fileSizeBytes = targetFile.length(),
                            )
                        }
                        return@launch
                    }
                }

                cleanCacheDirectory(dir, keepName = null)

                val ua = "iYango/$currentVersionName (Android)"
                var lastEmitAt = 0L
                httpDownload(resolvedUrl, ua, partFile) { downloaded, total, speed ->
                    if (activeDownloadToken != token) return@httpDownload
                    val now = System.nanoTime()
                    if (now - lastEmitAt >= PROGRESS_THROTTLE_NANOS || (total > 0L && downloaded >= total)) {
                        lastEmitAt = now
                        val resolvedTotal = if (total > 0L) total else release.apkSizeBytes.coerceAtLeast(0L)
                        _downloadState.value = AppUpdateDownloadState.Downloading(
                            versionKey = versionKey,
                            downloadedBytes = downloaded,
                            totalBytes = resolvedTotal,
                            bytesPerSecond = speed,
                        )
                    }
                }

                if (!partFile.exists() || partFile.length() == 0L || !hasApkMagicHeader(partFile)) {
                    partFile.delete()
                    if (activeDownloadToken == token) {
                        _downloadState.value = AppUpdateDownloadState.Failed(
                            versionKey = versionKey,
                            message = "下载文件无效，请切换下载源重试",
                        )
                    }
                    return@launch
                }

                if (targetFile.exists()) targetFile.delete()
                if (!partFile.renameTo(targetFile)) {
                    partFile.copyTo(targetFile, overwrite = true)
                    partFile.delete()
                }

                if (activeDownloadToken == token) {
                    _downloadState.value = AppUpdateDownloadState.ReadyToInstall(
                        versionKey = versionKey,
                        apkFilePath = targetFile.absolutePath,
                        fileSizeBytes = targetFile.length(),
                    )
                }
            } catch (cancelled: CancellationException) {
                partFile.delete()
                throw cancelled
            } catch (error: Exception) {
                partFile.delete()
                val msg = error.message?.trim()?.takeIf { it.isNotEmpty() } ?: "下载失败"
                recordDiagnostic("FAILED", error.javaClass.simpleName, startedAt, msg)
                if (activeDownloadToken == token) {
                    _downloadState.value = AppUpdateDownloadState.Failed(
                        versionKey = versionKey,
                        message = msg,
                    )
                }
            }
        }
    }

    override fun cancelDownload() {
        activeDownloadToken += 1L
        downloadJob?.cancel()
        downloadJob = null
        _downloadState.value = AppUpdateDownloadState.Idle
        downloadScope.launch {
            runCatching {
                val dir = downloadCacheDirProvider()
                dir.listFiles { file -> file.name.endsWith(".part") }?.forEach { it.delete() }
            }
        }
    }

    override fun resetDownloadState() {
        cancelDownload()
    }

    private suspend fun executeCheck(manual: Boolean, forceAuto: Boolean): AppUpdateCheckState =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val now = clock.millis()
                if (!manual && !forceAuto) {
                    val last = lastAutoCheckAt
                    if (last != null && (now - last) in 0L until LAUNCH_GUARD_MILLIS) {
                        return@withLock _state.value
                    }
                }
                val endpoint = effectiveEndpoint()
                if (endpoint.isEmpty()) {
                    _state.value = AppUpdateCheckState.NotConfigured
                    return@withLock _state.value
                }
                if (!AppUpdateManifestParser.isSafeExternalUrl(endpoint)) {
                    val failed = AppUpdateCheckState.Failed("更新地址必须使用 HTTPS")
                    _state.value = failed
                    return@withLock failed
                }
                if (!manual) {
                    lastAutoCheckAt = now
                } else {
                    dismissedSessionVersionKey = null
                }
                _state.value = AppUpdateCheckState.Checking
                val currentCode = currentVersionCodeProvider()
                val currentName = currentVersionNameProvider()
                val ua = "iYango/${currentName.ifBlank { FALLBACK_VERSION_NAME }} (Android)"
                try {
                    val body = httpGet(endpoint, ua)
                    val parsed = AppUpdateManifestParser.parse(body)
                        ?: run {
                            val msg = "更新信息格式无效"
                            recordDiagnostic("FAILED", "InvalidManifest", now, msg)
                            return@withLock AppUpdateCheckState.Failed(msg).also { _state.value = it }
                        }
                    val newer = AppVersionComparator.isNewer(
                        remoteVersionCode = parsed.release.versionCode,
                        remoteVersionName = parsed.release.versionName,
                        currentVersionCode = currentCode,
                        currentVersionName = currentName,
                    )
                    if (!newer) {
                        return@withLock AppUpdateCheckState.UpToDate(currentName, clock.millis())
                            .also { _state.value = it }
                    }
                    val force = AppVersionComparator.isForceRequired(
                        forceUpdate = parsed.release.forceUpdate,
                        minSupportedVersionCode = parsed.minSupportedVersionCode,
                        currentVersionCode = currentCode,
                    )
                    val resolvedRelease = parsed.release.copy(forceUpdate = force)
                    val available = AppUpdateCheckState.Available(resolvedRelease, clock.millis())
                    _state.value = available

                    val ignoredKey = prefsProvider().getString(KEY_IGNORED_VERSION, null).orEmpty()
                    val shouldPrompt = manual ||
                        force ||
                        (resolvedRelease.versionKey != ignoredKey &&
                            resolvedRelease.versionKey != dismissedSessionVersionKey)
                    if (shouldPrompt) {
                        _promptRelease.value = resolvedRelease
                    }
                    available
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val msg = error.message?.trim()?.takeIf { it.isNotEmpty() } ?: "检查更新失败"
                    recordDiagnostic("FAILED", error.javaClass.simpleName, now, msg)
                    AppUpdateCheckState.Failed(msg).also { _state.value = it }
                }
            }
        }

    private fun effectiveEndpoint(): String {
        val custom = prefsProvider().getString(KEY_CUSTOM_ENDPOINT, null).orEmpty().trim()
        return custom.ifEmpty { defaultEndpointProvider().trim() }
    }

    private suspend fun recordDiagnostic(
        outcome: String,
        errorType: String,
        startedAt: Long,
        message: String,
    ) {
        runCatching {
            diagnostics.record(
                DiagnosticEvent(
                    timestamp = clock.millis(),
                    category = "update",
                    durationMs = (clock.millis() - startedAt).coerceAtLeast(0L),
                    outcome = outcome,
                    errorType = errorType,
                    message = message.take(160),
                ),
            )
        }
    }

    private companion object {
        const val PREFS_NAME = "iyango_update_prefs"
        const val KEY_IGNORED_VERSION = "ignored_version_key"
        const val KEY_CUSTOM_ENDPOINT = "custom_endpoint_override"
        const val KEY_DOWNLOAD_MIRROR = "download_mirror"
        const val KEY_AUTO_CHECK_ENABLED = "auto_check_enabled"
        const val FALLBACK_VERSION_NAME = "1.1"
        const val LAUNCH_GUARD_MILLIS = 10_000L
        const val MAX_BODY_BYTES = 512 * 1024
        const val PROGRESS_THROTTLE_NANOS = 120_000_000L
        const val SPEED_WINDOW_NANOS = 400_000_000L

        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }

        private val downloadClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }

        private fun readPackageVersion(context: Context): Pair<String, Long> {
            val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
            val name = info?.versionName ?: FALLBACK_VERSION_NAME
            val code = when {
                info == null -> 0L
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> info.longVersionCode
                else -> {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                }
            }
            return name to code
        }

        private fun buildApkFileName(versionKey: String): String {
            val sanitized = versionKey.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "latest" }
            return "iyango-$sanitized.apk"
        }

        private fun hasApkMagicHeader(file: File): Boolean {
            if (!file.isFile || file.length() < 4L) return false
            return runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    val magic = ByteArray(4)
                    val read = raf.read(magic)
                    read == 4 &&
                        magic[0] == 0x50.toByte() &&
                        magic[1] == 0x4B.toByte() &&
                        magic[2] == 0x03.toByte() &&
                        magic[3] == 0x04.toByte()
                }
            }.getOrDefault(false)
        }

        private fun cleanCacheDirectory(dir: File, keepName: String?) {
            dir.listFiles()?.forEach { file ->
                if (file.name != keepName && (file.name.endsWith(".apk") || file.name.endsWith(".part"))) {
                    file.delete()
                }
            }
        }

        private suspend fun defaultHttpGet(url: String, userAgent: String): String {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .get()
                .build()
            return suspendCancellableCoroutine { continuation ->
                val call = httpClient.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(e)
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use { res ->
                            if (!res.isSuccessful) {
                                if (continuation.isActive) {
                                    continuation.resumeWithException(IOException("HTTP ${res.code}"))
                                }
                                return
                            }
                            val body = res.body
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            body.byteStream().use { stream ->
                                while (true) {
                                    val read = stream.read(buffer)
                                    if (read <= 0) break
                                    if (output.size() + read > MAX_BODY_BYTES) {
                                        if (continuation.isActive) {
                                            continuation.resumeWithException(IOException("更新清单体积超出限制"))
                                        }
                                        return
                                    }
                                    output.write(buffer, 0, read)
                                }
                            }
                            if (continuation.isActive) {
                                continuation.resume(output.toByteArray().toString(Charsets.UTF_8))
                            }
                        }
                    }
                })
            }
        }

        private suspend fun defaultHttpDownload(
            url: String,
            userAgent: String,
            destFile: File,
            onProgress: (downloadedBytes: Long, totalBytes: Long, bytesPerSecond: Long) -> Unit,
        ) {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "*/*")
                .header("User-Agent", userAgent)
                .get()
                .build()
            suspendCancellableCoroutine<Unit> { continuation ->
                val call = downloadClient.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(e)
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use { res ->
                            if (!res.isSuccessful) {
                                if (continuation.isActive) {
                                    continuation.resumeWithException(IOException("HTTP ${res.code}"))
                                }
                                return
                            }
                            try {
                                destFile.parentFile?.mkdirs()
                                val body = res.body
                                val totalBytes = body.contentLength().coerceAtLeast(0L)
                                val buffer = ByteArray(16 * 1024)
                                var downloadedBytes = 0L
                                var windowStartNanos = System.nanoTime()
                                var windowBytes = 0L
                                var currentSpeed = 0L
                                FileOutputStream(destFile).use { output ->
                                    body.byteStream().use { input ->
                                        while (continuation.isActive) {
                                            val read = input.read(buffer)
                                            if (read <= 0) break
                                            output.write(buffer, 0, read)
                                            downloadedBytes += read
                                            windowBytes += read
                                            val nowNanos = System.nanoTime()
                                            val elapsed = nowNanos - windowStartNanos
                                            if (elapsed >= SPEED_WINDOW_NANOS) {
                                                currentSpeed = (windowBytes * 1_000_000_000L) / elapsed.coerceAtLeast(1L)
                                                windowStartNanos = nowNanos
                                                windowBytes = 0L
                                            }
                                            onProgress(downloadedBytes, totalBytes, currentSpeed)
                                        }
                                    }
                                }
                                if (continuation.isActive) {
                                    onProgress(downloadedBytes, totalBytes, currentSpeed)
                                    continuation.resume(Unit)
                                }
                            } catch (error: Exception) {
                                if (continuation.isActive) {
                                    continuation.resumeWithException(error)
                                }
                            }
                        }
                    }
                })
            }
        }
    }
}
