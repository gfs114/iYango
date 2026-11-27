package com.gfs.iyango.data.update

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.gfs.iyango.core.domain.AppUpdateCheckState
import com.gfs.iyango.core.domain.AppUpdatePort
import com.gfs.iyango.core.domain.AppUpdateRelease
import com.gfs.iyango.core.domain.AppVersionComparator
import com.gfs.iyango.core.domain.DiagnosticLogPort
import com.gfs.iyango.core.model.DiagnosticEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    )

    private val mutex = Mutex()
    private val _state = MutableStateFlow<AppUpdateCheckState>(AppUpdateCheckState.Idle)
    override val state: StateFlow<AppUpdateCheckState> = _state.asStateFlow()

    private val _promptRelease = MutableStateFlow<AppUpdateRelease?>(null)
    override val promptRelease: StateFlow<AppUpdateRelease?> = _promptRelease.asStateFlow()

    private var lastAutoCheckAt: Long? = null
    private var dismissedSessionVersionKey: String? = null

    override suspend fun configuredUrl(): String = withContext(Dispatchers.IO) {
        effectiveEndpoint()
    }

    override suspend fun checkOnLaunch(force: Boolean) {
        executeCheck(manual = false, forceAuto = force)
    }

    override suspend fun checkManually(): AppUpdateCheckState =
        executeCheck(manual = true, forceAuto = true)

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
        }
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
                val ua = "iYango/${currentName.ifBlank { "1.0" }} (Android)"
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
        const val LAUNCH_GUARD_MILLIS = 10_000L
        const val MAX_BODY_BYTES = 512 * 1024

        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }

        private fun readPackageVersion(context: Context): Pair<String, Long> {
            val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
            val name = info?.versionName ?: "1.0"
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
    }
}
