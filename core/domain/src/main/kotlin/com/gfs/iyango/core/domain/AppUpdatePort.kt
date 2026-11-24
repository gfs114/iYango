package com.gfs.iyango.core.domain

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
) {
    val actionUrl: String get() = downloadUrl.ifBlank { pageUrl }

    val versionKey: String
        get() = if (versionCode > 0) "code:$versionCode" else "name:${versionName.trim()}"
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
    suspend fun configuredUrl(): String
    suspend fun checkOnLaunch(force: Boolean = false)
    suspend fun checkManually(): AppUpdateCheckState
    fun dismissPrompt()
    suspend fun ignoreVersion(versionKey: String)
    suspend fun setCustomEndpointOverride(url: String)
}
