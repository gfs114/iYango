package com.gfs.iyango.data.repository

import android.content.Context
import android.os.Build
import com.gfs.iyango.core.domain.DiagnosticExport
import com.gfs.iyango.core.domain.DiagnosticLogPort
import com.gfs.iyango.core.domain.DiagnosticLogSummary
import com.gfs.iyango.core.model.DiagnosticEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [DiagnosticLogPort] 的文件实现（F18）。
 *
 * 日志是 JSON Lines：一行一个事件，写入时即做脱敏与截断。单文件超过
 * [MAX_FILE_BYTES] 轮替到 `events.1.jsonl`（覆盖更旧的一份），磁盘占用上限约
 * 两倍文件上限；内存只保留最近 [MAX_EVENTS] 条供页面展示。
 */
@Singleton
class FileDiagnosticLog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) : DiagnosticLogPort {

    private val dir: File get() = File(context.filesDir, DIR_NAME)
    private val currentFile: File get() = File(dir, FILE_CURRENT)
    private val rotatedFile: File get() = File(dir, FILE_ROTATED)

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _recent = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    private val _summary = MutableStateFlow(DiagnosticLogSummary())

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    init {
        scope.launch { mutex.withLock { reloadLocked() } }
    }

    override fun observeRecent(limit: Int): Flow<List<DiagnosticEvent>> =
        _recent.map { events -> events.take(limit.coerceIn(1, MAX_EVENTS)) }

    override val summary = _summary.asStateFlow()

    override suspend fun record(event: DiagnosticEvent) = mutex.withLock {
        val clean = DiagnosticRedaction.redact(event)
        val line = json.encodeToString(DiagnosticEventDto.serializer(), clean.toDto()) + "\n"
        withContext(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                if (currentFile.length() + line.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) {
                    rotateLocked()
                }
                currentFile.appendText(line, Charsets.UTF_8)
            }
        }
        _recent.value = (listOf(clean) + _recent.value).take(MAX_EVENTS)
        refreshSummaryLocked()
    }

    override suspend fun export(): DiagnosticExport = mutex.withLock {
        reloadLocked()
        val events = _recent.value
        val header = buildString {
            appendLine("iYango 诊断日志")
            appendLine("导出时间：${TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(clock.millis()).atZone(clock.zone))}")
            appendLine("应用版本：${appVersion()}")
            appendLine("系统版本：Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
            appendLine("记录条数：${events.size}（本地最多保留 $MAX_EVENTS 条，磁盘约 ≤ ${MAX_FILE_BYTES * 2 / 1024} KB）")
            appendLine(
                "内容范围：仅操作类别、不可逆脱敏的连接标识、任务代次、耗时、结果与错误类别；" +
                    "不含口令、Cookie、完整成绩、完整个人信息或网页响应。",
            )
            appendLine()
            events.forEach { event ->
                appendLine(
                    buildString {
                        append(TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(event.timestamp).atZone(clock.zone)))
                        append(" | ").append(event.category.ifBlank { "-" })
                        append(" | ").append(event.outcome.ifBlank { "-" })
                        if (event.connectionHash.isNotBlank()) {
                            append(" | conn=").append(event.connectionHash)
                        }
                        if (event.taskRevision > 0) append(" | rev=").append(event.taskRevision)
                        if (event.durationMs > 0) append(" | ").append(event.durationMs).append("ms")
                        if (event.errorType.isNotBlank()) append(" | ").append(event.errorType)
                        if (event.message.isNotBlank()) append(" | ").append(event.message)
                    },
                )
            }
        }
        DiagnosticExport(
            fileName = FILE_NAME_FORMATTER.format(
                Instant.ofEpochMilli(clock.millis()).atZone(clock.zone),
            ),
            text = header,
        )
    }

    override suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching { currentFile.delete() }
            runCatching { rotatedFile.delete() }
        }
        _recent.value = emptyList()
        refreshSummaryLocked()
    }

    private suspend fun reloadLocked() {
        val events = withContext(Dispatchers.IO) { readFile(rotatedFile) + readFile(currentFile) }
            .sortedByDescending { it.timestamp }
            .take(MAX_EVENTS)
        _recent.value = events
        refreshSummaryLocked()
    }

    private fun readFile(file: File): List<DiagnosticEvent> {
        if (!file.isFile) return emptyList()
        return runCatching {
            file.useLines { lines ->
                lines.mapNotNull { line ->
                    if (line.isBlank()) return@mapNotNull null
                    runCatching {
                        json.decodeFromString(DiagnosticEventDto.serializer(), line).toModel()
                    }.getOrNull()
                }.toList()
            }
        }.getOrDefault(emptyList())
    }

    private fun rotateLocked() {
        if (!currentFile.exists()) return
        if (rotatedFile.exists()) rotatedFile.delete()
        if (!currentFile.renameTo(rotatedFile)) {
            currentFile.copyTo(rotatedFile, overwrite = true)
            currentFile.delete()
        }
    }

    private suspend fun refreshSummaryLocked() {
        val bytes = withContext(Dispatchers.IO) {
            currentFile.length().coerceAtLeast(0) + rotatedFile.length().coerceAtLeast(0)
        }
        _summary.value = DiagnosticLogSummary(
            count = _recent.value.size,
            bytes = bytes,
            lastEventAt = _recent.value.firstOrNull()?.timestamp ?: 0,
        )
    }

    private fun appVersion(): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${info.versionName ?: "?"} ($code)"
    }.getOrDefault("未知")

    private companion object {
        const val DIR_NAME = "diagnostics"
        const val FILE_CURRENT = "events.jsonl"
        const val FILE_ROTATED = "events.1.jsonl"
        const val MAX_FILE_BYTES = 96 * 1024L
        const val MAX_EVENTS = 200

        val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val FILE_NAME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("'iyango-diagnostics-'yyyyMMdd-HHmm'.txt'")
    }
}

/** 记录前与导出前共用的脱敏规则。 */
internal object DiagnosticRedaction {

    private val SECRET_ASSIGNMENT = Regex(
        "(?i)(password|passwd|pwd|cookie|authorization|token|session(id)?)\\s*[:=]\\s*[^\\s,;]+",
    )
    private val HTML_MARKER = Regex("(?i)<!doctype|<html|</html|<body|</body")
    private val LONG_OPAQUE = Regex("[A-Za-z0-9+/]{80,}={0,2}")
    private val CONNECTION_HASH = Regex("^[0-9a-fA-F]{8,64}$")
    private val SAFE_TOKEN = Regex("[^A-Za-z0-9._/\\-]")

    fun redact(event: DiagnosticEvent): DiagnosticEvent = event.copy(
        category = sanitizeToken(event.category, 32),
        connectionHash = sanitizeConnectionHash(event.connectionHash),
        taskRevision = event.taskRevision.coerceAtLeast(0),
        durationMs = event.durationMs.coerceAtLeast(0),
        outcome = sanitizeToken(event.outcome, 16),
        errorType = sanitizeToken(event.errorType, 48),
        message = sanitizeMessage(event.message),
        timestamp = event.timestamp,
    )

    fun sanitizeMessage(raw: String): String {
        val text = raw.replace('\n', ' ').replace('\r', ' ').trim()
        if (text.isEmpty()) return ""
        if (HTML_MARKER.containsMatchIn(text)) return "[已省略网页响应]"
        return text
            .replace(SECRET_ASSIGNMENT, "$1=***")
            .replace(LONG_OPAQUE, "[已省略长串]")
            .take(MAX_MESSAGE_LENGTH)
    }

    fun sanitizeConnectionHash(raw: String): String {
        if (raw.isBlank()) return ""
        return if (CONNECTION_HASH.matches(raw)) {
            raw.lowercase().take(16)
        } else {
            sha256(raw).take(16)
        }
    }

    private fun sanitizeToken(raw: String, maxLength: Int): String =
        raw.replace(SAFE_TOKEN, "").take(maxLength)

    private fun sha256(raw: String): String =
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private const val MAX_MESSAGE_LENGTH = 180
}

@Serializable
internal data class DiagnosticEventDto(
    val t: Long = 0,
    val c: String = "",
    val h: String = "",
    val r: Int = 0,
    val d: Long = 0,
    val o: String = "",
    val e: String = "",
    val m: String = "",
)

internal fun DiagnosticEvent.toDto(): DiagnosticEventDto = DiagnosticEventDto(
    t = timestamp,
    c = category,
    h = connectionHash,
    r = taskRevision,
    d = durationMs,
    o = outcome,
    e = errorType,
    m = message,
)

internal fun DiagnosticEventDto.toModel(): DiagnosticEvent = DiagnosticEvent(
    timestamp = t,
    category = c,
    connectionHash = h,
    taskRevision = r,
    durationMs = d,
    outcome = o,
    errorType = e,
    message = m,
)
