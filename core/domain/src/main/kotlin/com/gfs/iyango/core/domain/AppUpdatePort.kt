package com.gfs.iyango.core.domain

import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

/**
 * 远端版本发布信息。
 */
data class AppUpdateRelease(
    val versionCode: Long = 0,
    val versionName: String = "",
    val releaseNotes: String = "",
    val downloadUrl: String = "",
    val pageUrl: String = "",
    val publishedAt: String = "",
    val forceUpdate: Boolean = false,
    val apkSizeBytes: Long = 0,
    val apkFileName: String = "",
) {
    val actionUrl: String get() = downloadUrl.ifBlank { pageUrl }

    val versionKey: String
        get() = if (versionCode > 0) "code:$versionCode" else "name:${versionName.trim()}"
}

/**
 * APK 下载源镜像选择。
 */
enum class UpdateDownloadMirror(
    val id: String,
    val label: String,
) {
    DIRECT("direct", "直连 GitHub"),
    GHPROXY("ghproxy", "镜像 1"),
    GHFAST("ghfast", "镜像 2");

    fun resolveUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        val isGitHubAsset = trimmed.startsWith("https://github.com/") ||
            trimmed.startsWith("https://objects.githubusercontent.com/")
        if (!isGitHubAsset) return trimmed
        return when (this) {
            DIRECT -> trimmed
            GHPROXY -> "https://gh-proxy.com/$trimmed"
            GHFAST -> "https://ghfast.top/$trimmed"
        }
    }

    companion object {
        fun fromId(id: String?): UpdateDownloadMirror =
            entries.firstOrNull { it.id == id } ?: DIRECT
    }
}

/**
 * 应用内 APK 下载状态。
 */
sealed interface AppUpdateDownloadState {
    data object Idle : AppUpdateDownloadState

    data class Downloading(
        val versionKey: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long,
    ) : AppUpdateDownloadState {
        val progress: Float?
            get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else null
    }

    data class ReadyToInstall(
        val versionKey: String,
        val apkFilePath: String,
        val fileSizeBytes: Long,
    ) : AppUpdateDownloadState

    data class Failed(
        val versionKey: String,
        val message: String,
    ) : AppUpdateDownloadState
}

/**
 * 应用更新检测状态。
 */
sealed interface AppUpdateCheckState {
    data object Idle : AppUpdateCheckState
    data object Checking : AppUpdateCheckState
    data object NotConfigured : AppUpdateCheckState
    data class UpToDate(val currentVersionName: String, val checkedAtMillis: Long) : AppUpdateCheckState
    data class Available(val release: AppUpdateRelease, val checkedAtMillis: Long) : AppUpdateCheckState
    data class Failed(val message: String) : AppUpdateCheckState
}

/**
 * 应用版本比较工具（纯 JVM 实现）。
 */
object AppVersionComparator {

    fun isNewer(
        remoteVersionCode: Long,
        remoteVersionName: String,
        currentVersionCode: Long,
        currentVersionName: String,
    ): Boolean {
        if (remoteVersionCode > 0 && currentVersionCode > 0) {
            return remoteVersionCode > currentVersionCode
        }
        val remoteParts = parseNumericSegments(remoteVersionName)
        val currentParts = parseNumericSegments(currentVersionName)
        if (remoteParts.isEmpty() || currentParts.isEmpty()) return false
        val maxSize = maxOf(remoteParts.size, currentParts.size)
        for (index in 0 until maxSize) {
            val remote = remoteParts.getOrElse(index) { 0L }
            val current = currentParts.getOrElse(index) { 0L }
            if (remote != current) return remote > current
        }
        return false
    }

    fun isForceRequired(
        forceUpdate: Boolean,
        minSupportedVersionCode: Long,
        currentVersionCode: Long,
    ): Boolean = forceUpdate ||
        (minSupportedVersionCode > 0 && currentVersionCode > 0 && currentVersionCode < minSupportedVersionCode)

    private fun parseNumericSegments(versionName: String): List<Long> {
        val cleaned = versionName.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
            .trim()
        if (cleaned.isEmpty()) return emptyList()
        val parts = cleaned.split('.')
        val parsed = parts.mapNotNull { it.trim().toLongOrNull() }
        return if (parsed.size == parts.size) parsed else emptyList()
    }
}

/**
 * 应用更新检查端口（每次打开自动检测 + 关于页手动检测）。
 */
interface AppUpdatePort {
    val state: StateFlow<AppUpdateCheckState>
    val promptRelease: StateFlow<AppUpdateRelease?>
    val currentVersionName: String
    val currentVersionCode: Long
    val downloadState: StateFlow<AppUpdateDownloadState>
    val downloadMirror: StateFlow<UpdateDownloadMirror>
    val autoCheckEnabled: StateFlow<Boolean>
    val releaseHistory: StateFlow<List<AppUpdateRelease>>
    val historyLoading: StateFlow<Boolean>
    suspend fun configuredUrl(): String
    suspend fun checkOnLaunch(force: Boolean = false)
    suspend fun checkManually(): AppUpdateCheckState
    suspend fun loadReleaseHistory(force: Boolean = false)
    fun dismissPrompt()
    suspend fun ignoreVersion(versionKey: String)
    suspend fun setCustomEndpointOverride(url: String)
    suspend fun setDownloadMirror(mirror: UpdateDownloadMirror)
    suspend fun setAutoCheckEnabled(enabled: Boolean)
    fun startDownload(release: AppUpdateRelease)
    fun cancelDownload()
    fun resetDownloadState()
}

/**
 * 提取并清洗 GitHub Release 更新说明，仅保留应用内可读的变更日志正文。
 */
object AppUpdateReleaseNotesFormatter {

    private val HEADING_REGEX = Regex("^#{1,3}\\s+(.+)$")
    private val MARKDOWN_LINK_REGEX = Regex("\\[([^\\]]+)]\\([^)]+\\)")

    fun extractChangelog(rawBody: String): String {
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) return ""
        val kept = mutableListOf<String>()
        var skipSection = false
        for (rawLine in trimmed.lines()) {
            val line = rawLine.trimEnd()
            val stripped = line.trim()
            val headingMatch = HEADING_REGEX.matchEntire(stripped)
            if (headingMatch != null) {
                val headingText = headingMatch.groupValues[1].trim()
                val lower = headingText.lowercase(Locale.ROOT)
                if (lower.contains("download") || headingText.contains("下载")) {
                    skipSection = true
                    continue
                }
                skipSection = false
                if (lower == "更新内容" || lower == "changelog" || lower == "what's changed") {
                    continue
                }
                kept += stripped
                continue
            }
            if (skipSection) continue
            if (stripped.startsWith("|") && stripped.endsWith("|")) {
                continue
            }
            if (stripped.contains("SHA256SUMS", ignoreCase = true) || stripped.contains("核对安装包")) {
                continue
            }
            if (stripped.startsWith("**Full Changelog**:", ignoreCase = true) ||
                stripped.startsWith("Full Changelog:", ignoreCase = true)
            ) {
                continue
            }
            kept += line
        }
        return kept
            .dropWhile { it.isBlank() }
            .dropLastWhile { it.isBlank() }
            .joinToString("\n")
            .trim()
    }

    fun stripMarkdownLinks(text: String): String =
        MARKDOWN_LINK_REGEX.replace(text, "$1")
}
