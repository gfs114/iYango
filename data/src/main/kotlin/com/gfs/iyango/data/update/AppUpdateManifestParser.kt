package com.gfs.iyango.data.update

import com.gfs.iyango.core.domain.AppUpdateRelease
import java.net.URI
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

internal data class ParsedUpdateManifest(
    val release: AppUpdateRelease,
    val minSupportedVersionCode: Long = 0,
)

private val MANIFEST_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private val PRIVATE_172_HOST = Regex("^172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}$")

private data class ExtractedApkAsset(
    val url: String = "",
    val name: String = "",
    val sizeBytes: Long = 0L,
)

internal object AppUpdateManifestParser {

    fun parse(jsonText: String): ParsedUpdateManifest? {
        if (jsonText.isBlank()) return null
        val root = runCatching { MANIFEST_JSON.parseToJsonElement(jsonText) as? JsonObject }.getOrNull()
            ?: return null
        return parseReleaseObject(root)
    }

    fun parseReleaseList(jsonText: String): List<AppUpdateRelease> {
        if (jsonText.isBlank()) return emptyList()
        val array = runCatching { MANIFEST_JSON.parseToJsonElement(jsonText) as? JsonArray }.getOrNull()
            ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            if (firstBoolean(obj, listOf("draft")) == true) return@mapNotNull null
            parseReleaseObject(obj)?.release
        }
    }

    fun deriveReleasesListUrl(latestEndpoint: String): String? {
        val trimmed = latestEndpoint.trim().trimEnd('/')
        if (!trimmed.endsWith("/releases/latest", ignoreCase = true)) return null
        return "${trimmed.dropLast("/latest".length)}?per_page=10"
    }

    private fun parseReleaseObject(root: JsonObject): ParsedUpdateManifest? {
        val versionCode = firstLong(root, listOf("versionCode", "version_code")) ?: 0L
        val rawTagOrVersion = firstString(root, listOf("versionName", "version_name", "tag_name", "tagName", "version"))
            .orEmpty()
            .trim()
        val releaseTitle = firstString(root, listOf("name")).orEmpty().trim()
        val versionName = rawTagOrVersion
            .removePrefix("v")
            .removePrefix("V")
            .ifBlank { releaseTitle.removePrefix("v").removePrefix("V") }

        if (versionCode <= 0L && versionName.isBlank()) return null

        val rawNotes = firstString(root, listOf("releaseNotes", "release_notes", "changelog", "notes", "body"))
            .orEmpty()
            .trim()
        val notes = extractInAppReleaseNotes(rawNotes)
        val publishedAt = firstString(root, listOf("publishedAt", "published_at", "date"))
            .orEmpty()
            .trim()
        val forceUpdate = firstBoolean(root, listOf("forceUpdate", "force_update", "force")) ?: false
        val minSupported = firstLong(root, listOf("minSupportedVersionCode", "min_supported_version_code")) ?: 0L

        val explicitDownload = firstString(
            root,
            listOf("downloadUrl", "download_url", "apkUrl", "apk_url", "url"),
        ).orEmpty().trim()
        val asset = extractGithubApkAsset(root["assets"] as? JsonArray)
        val downloadUrl = listOf(explicitDownload, asset.url)
            .firstOrNull { isSafeExternalUrl(it) }
            .orEmpty()

        val apkSizeBytes = (firstLong(root, listOf("apkSizeBytes", "size")) ?: asset.sizeBytes)
            .coerceAtLeast(0L)
        val apkFileName = firstString(root, listOf("apkFileName", "fileName"))
            .orEmpty()
            .trim()
            .ifEmpty { asset.name }

        val pageUrl = firstString(root, listOf("pageUrl", "page_url", "html_url"))
            .orEmpty()
            .trim()
            .takeIf { isSafeExternalUrl(it) }
            .orEmpty()

        return ParsedUpdateManifest(
            release = AppUpdateRelease(
                versionCode = versionCode.coerceAtLeast(0L),
                versionName = versionName,
                releaseNotes = notes,
                downloadUrl = downloadUrl,
                pageUrl = pageUrl,
                publishedAt = publishedAt,
                forceUpdate = forceUpdate,
                apkSizeBytes = apkSizeBytes,
                apkFileName = apkFileName,
            ),
            minSupportedVersionCode = minSupported.coerceAtLeast(0L),
        )
    }

    internal fun extractInAppReleaseNotes(rawBody: String): String {
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) return ""
        val headingRegex = Regex("^#{1,3}\\s+(.+)$")
        val kept = mutableListOf<String>()
        var skipSection = false
        for (rawLine in trimmed.lines()) {
            val line = rawLine.trimEnd()
            val stripped = line.trim()
            val headingMatch = headingRegex.matchEntire(stripped)
            if (headingMatch != null) {
                val headingText = headingMatch.groupValues[1].trim()
                val lower = headingText.lowercase(Locale.ROOT)
                if (lower.contains("download") || headingText.contains("下载")) {
                    skipSection = true
                    continue
                }
                skipSection = false
                if (lower == "更新内容" || lower == "changelog") {
                    continue
                }
                kept += stripped
                continue
            }
            if (skipSection) continue
            if (stripped.startsWith("**Full Changelog**:", ignoreCase = true) ||
                stripped.startsWith("Full Changelog:", ignoreCase = true)
            ) {
                continue
            }
            kept += line
        }
        val normalized = kept
            .dropWhile { it.isBlank() }
            .dropLastWhile { it.isBlank() }
            .joinToString("\n")
            .trim()
        return normalized.ifEmpty { trimmed }
    }

    internal fun isSafeExternalUrl(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
        val host = uri.host?.trim()?.removePrefix("[")?.removeSuffix("]")?.lowercase(Locale.ROOT).orEmpty()
        if (host.isEmpty()) return false
        return when (scheme) {
            "https" -> true
            "http" -> host == "localhost" ||
                host == "127.0.0.1" ||
                host == "10.0.2.2" ||
                host.startsWith("10.") ||
                host.startsWith("192.168.") ||
                PRIVATE_172_HOST.matches(host)
            else -> false
        }
    }

    private fun extractGithubApkAsset(assets: JsonArray?): ExtractedApkAsset {
        if (assets == null) return ExtractedApkAsset()
        val entries = assets.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val url = (obj["browser_download_url"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                .orEmpty()
            val name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            val size = firstLong(obj, listOf("size")) ?: 0L
            ExtractedApkAsset(url = url, name = name, sizeBytes = size).takeIf { url.isNotEmpty() }
        }
        val apk = entries.firstOrNull { entry ->
            entry.name.endsWith(".apk", ignoreCase = true) || entry.url.endsWith(".apk", ignoreCase = true)
        }
        return apk ?: entries.firstOrNull() ?: ExtractedApkAsset()
    }

    private fun firstString(obj: JsonObject, keys: List<String>): String? {
        for (key in keys) {
            val primitive = obj[key] as? JsonPrimitive ?: continue
            if (primitive.isString) return primitive.content
        }
        return null
    }

    private fun firstLong(obj: JsonObject, keys: List<String>): Long? {
        for (key in keys) {
            val primitive = obj[key] as? JsonPrimitive ?: continue
            primitive.longOrNull?.let { return it }
            if (primitive.isString) {
                primitive.content.trim().toLongOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun firstBoolean(obj: JsonObject, keys: List<String>): Boolean? {
        for (key in keys) {
            val primitive = obj[key] as? JsonPrimitive ?: continue
            primitive.booleanOrNull?.let { return it }
            if (primitive.isString) {
                when (primitive.content.trim().lowercase(Locale.ROOT)) {
                    "true", "1" -> return true
                    "false", "0" -> return false
                }
            }
        }
        return null
    }
}
