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

internal object AppUpdateManifestParser {

    fun parse(jsonText: String): ParsedUpdateManifest? {
        if (jsonText.isBlank()) return null
        val root = runCatching { MANIFEST_JSON.parseToJsonElement(jsonText) as? JsonObject }.getOrNull()
            ?: return null

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

        val notes = firstString(root, listOf("releaseNotes", "release_notes", "changelog", "notes", "body"))
            .orEmpty()
            .trim()
        val publishedAt = firstString(root, listOf("publishedAt", "published_at", "date"))
            .orEmpty()
            .trim()
        val forceUpdate = firstBoolean(root, listOf("forceUpdate", "force_update", "force")) ?: false
        val minSupported = firstLong(root, listOf("minSupportedVersionCode", "min_supported_version_code")) ?: 0L

        val explicitDownload = firstString(
            root,
            listOf("downloadUrl", "download_url", "apkUrl", "apk_url", "url"),
        ).orEmpty().trim()
        val assetDownload = extractGithubApkAssetUrl(root["assets"] as? JsonArray)
        val downloadUrl = listOf(explicitDownload, assetDownload)
            .firstOrNull { isSafeExternalUrl(it) }
            .orEmpty()

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
            ),
            minSupportedVersionCode = minSupported.coerceAtLeast(0L),
        )
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

    private fun extractGithubApkAssetUrl(assets: JsonArray?): String {
        if (assets == null) return ""
        val urls = assets.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val url = (obj["browser_download_url"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                .orEmpty()
            val name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            (name to url).takeIf { url.isNotEmpty() }
        }
        val apk = urls.firstOrNull { (name, url) ->
            name.endsWith(".apk", ignoreCase = true) || url.endsWith(".apk", ignoreCase = true)
        }
        return (apk ?: urls.firstOrNull())?.second.orEmpty()
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
