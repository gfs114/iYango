package com.gfs.iyango.data.update

import com.gfs.iyango.core.domain.AppUpdateRelease
import com.gfs.iyango.core.domain.AppUpdateReleaseNotesFormatter
import com.gfs.iyango.core.domain.UpdateDownloadMirror
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

internal data class GitHubRepoRef(
    val owner: String,
    val repo: String,
)

internal enum class UpdateCandidateFormat {
    JSON,
    ATOM,
}

internal data class UpdateFetchCandidate(
    val url: String,
    val format: UpdateCandidateFormat,
)

private val MANIFEST_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private val PRIVATE_172_HOST = Regex("^172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}$")
private const val DEFAULT_RELEASE_APK_NAME = "iYango-release.apk"
private val GITHUB_LATEST_API_REGEX = Regex(
    "^https://api\\.github\\.com/repos/([^/\\s]+)/([^/\\s]+)/releases/latest/?$",
    RegexOption.IGNORE_CASE,
)
private val ATOM_ENTRY_REGEX = Regex("<entry>([\\s\\S]*?)</entry>")
private val ATOM_TAG_LINK_REGEX = Regex(
    """<link\b[^>]*\bhref\s*=\s*"(https://github\.com/[^"]+/releases/tag/([^"]+))"""",
)
private val ATOM_TITLE_REGEX = Regex("<title[^>]*>([\\s\\S]*?)</title>")
private val ATOM_UPDATED_REGEX = Regex("<updated[^>]*>([\\s\\S]*?)</updated>")
private val ATOM_CONTENT_REGEX = Regex("<content[^>]*>([\\s\\S]*?)</content>")
private val HTML_APK_LINK_REGEX = Regex(
    """<a\b[^>]*\bhref\s*=\s*"(https://[^"]+\.apk)"""",
    RegexOption.IGNORE_CASE,
)
private val HTML_TABLE_REGEX = Regex("<table[\\s\\S]*?</table>", RegexOption.IGNORE_CASE)
private val HTML_H2_REGEX = Regex("<h2[^>]*>([\\s\\S]*?)</h2>", RegexOption.IGNORE_CASE)
private val HTML_H3_REGEX = Regex("<h3[^>]*>([\\s\\S]*?)</h3>", RegexOption.IGNORE_CASE)
private val HTML_LI_REGEX = Regex("<li[^>]*>([\\s\\S]*?)</li>", RegexOption.IGNORE_CASE)
private val HTML_BLOCK_BREAK_REGEX = Regex(
    "<br\\s*/?>|</p>|</div>|</ul>|</ol>",
    RegexOption.IGNORE_CASE,
)
private val HTML_TAG_REGEX = Regex("<[^>]+>")
private val XML_ENTITY_REGEX = Regex("&(lt|gt|quot|apos|amp|#\\d+|#x[0-9a-fA-F]+);")

private data class ExtractedApkAsset(
    val url: String = "",
    val name: String = "",
    val sizeBytes: Long = 0L,
)

internal object AppUpdateManifestParser {

    fun extractGitHubRepo(endpoint: String): GitHubRepoRef? {
        val match = GITHUB_LATEST_API_REGEX.matchEntire(endpoint.trim()) ?: return null
        val owner = match.groupValues[1].trim()
        val repo = match.groupValues[2].trim()
        if (owner.isEmpty() || repo.isEmpty()) return null
        return GitHubRepoRef(owner = owner, repo = repo)
    }

    fun buildLatestCheckCandidates(
        endpoint: String,
        mirror: UpdateDownloadMirror,
    ): List<UpdateFetchCandidate> {
        val clean = endpoint.trim()
        val directApi = UpdateFetchCandidate(clean, UpdateCandidateFormat.JSON)
        val repo = extractGitHubRepo(clean) ?: return listOf(directApi)
        val manifestRaw = "https://github.com/${repo.owner}/${repo.repo}/releases/latest/download/update-manifest.json"
        val ghProxyManifest = UpdateFetchCandidate(
            url = UpdateDownloadMirror.GHPROXY.resolveUrl(manifestRaw),
            format = UpdateCandidateFormat.JSON,
        )
        val ghFastManifest = UpdateFetchCandidate(
            url = UpdateDownloadMirror.GHFAST.resolveUrl(manifestRaw),
            format = UpdateCandidateFormat.JSON,
        )
        val ghFastLatestJson = UpdateFetchCandidate(
            url = "https://ghfast.top/https://github.com/${repo.owner}/${repo.repo}/releases/latest",
            format = UpdateCandidateFormat.JSON,
        )
        val ghProxyAtom = UpdateFetchCandidate(
            url = "https://gh-proxy.com/https://github.com/${repo.owner}/${repo.repo}/releases.atom",
            format = UpdateCandidateFormat.ATOM,
        )
        return when (mirror) {
            UpdateDownloadMirror.DIRECT -> listOf(
                directApi,
                ghProxyManifest,
                ghFastManifest,
                ghProxyAtom,
                ghFastLatestJson,
            )

            UpdateDownloadMirror.GHPROXY -> listOf(
                ghProxyManifest,
                ghFastManifest,
                ghProxyAtom,
                ghFastLatestJson,
                directApi,
            )

            UpdateDownloadMirror.GHFAST -> listOf(
                ghFastManifest,
                ghProxyManifest,
                ghFastLatestJson,
                ghProxyAtom,
                directApi,
            )
        }
    }

    fun buildReleaseHistoryCandidates(
        endpoint: String,
        mirror: UpdateDownloadMirror,
    ): List<UpdateFetchCandidate> {
        val listUrl = deriveReleasesListUrl(endpoint) ?: return emptyList()
        val directList = UpdateFetchCandidate(listUrl, UpdateCandidateFormat.JSON)
        val repo = extractGitHubRepo(endpoint) ?: return listOf(directList)
        val ghProxyAtom = UpdateFetchCandidate(
            url = "https://gh-proxy.com/https://github.com/${repo.owner}/${repo.repo}/releases.atom",
            format = UpdateCandidateFormat.ATOM,
        )
        return if (mirror == UpdateDownloadMirror.DIRECT) {
            listOf(directList, ghProxyAtom)
        } else {
            listOf(ghProxyAtom, directList)
        }
    }

    fun parse(jsonText: String, fallbackRepo: GitHubRepoRef? = null): ParsedUpdateManifest? {
        if (jsonText.isBlank()) return null
        val root = runCatching { MANIFEST_JSON.parseToJsonElement(jsonText) as? JsonObject }.getOrNull()
            ?: return null
        return parseReleaseObject(root, fallbackRepo = fallbackRepo)
    }

    fun parseLatestFromAtom(xmlText: String): ParsedUpdateManifest? =
        parseReleaseAtomFeed(xmlText, requireApkAsset = true)
            .firstOrNull()
            ?.let { ParsedUpdateManifest(release = it) }

    fun parseReleaseAtomFeed(
        xmlText: String,
        requireApkAsset: Boolean = false,
    ): List<AppUpdateRelease> {
        if (xmlText.isBlank()) return emptyList()
        return ATOM_ENTRY_REGEX.findAll(xmlText).mapNotNull { entryMatch ->
            val block = entryMatch.groupValues[1]
            val linkMatch = ATOM_TAG_LINK_REGEX.find(block)
            val pageUrl = linkMatch?.groupValues?.getOrNull(1)?.trim()
                ?.takeIf { isSafeExternalUrl(it) }
                .orEmpty()
            val titleRaw = ATOM_TITLE_REGEX.find(block)?.groupValues?.getOrNull(1)
                ?.let(::decodeXmlEntities)
                ?.trim()
                .orEmpty()
            val rawTag = linkMatch?.groupValues?.getOrNull(2)?.trim()
                ?.ifBlank { null }
                ?: titleRaw.substringBefore(':').trim()
            val versionName = rawTag.removePrefix("v").removePrefix("V").trim()
            if (versionName.isBlank()) return@mapNotNull null

            val publishedAt = ATOM_UPDATED_REGEX.find(block)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val encodedHtml = ATOM_CONTENT_REGEX.find(block)?.groupValues?.getOrNull(1).orEmpty()
            val decodedHtml = decodeXmlEntities(encodedHtml)
            val apkUrl = HTML_APK_LINK_REGEX.find(decodedHtml)?.groupValues?.getOrNull(1)?.trim()
                ?.takeIf { isSafeExternalUrl(it) }
                .orEmpty()
            if (requireApkAsset && apkUrl.isBlank()) return@mapNotNull null

            val apkFileName = if (apkUrl.isNotBlank()) apkUrl.substringAfterLast('/') else ""
            val notes = htmlFragmentToReleaseNotes(decodedHtml)

            AppUpdateRelease(
                versionCode = 0L,
                versionName = versionName,
                releaseNotes = notes,
                downloadUrl = apkUrl,
                pageUrl = pageUrl,
                publishedAt = publishedAt,
                apkFileName = apkFileName,
            )
        }.toList()
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

    private fun parseReleaseObject(
        root: JsonObject,
        fallbackRepo: GitHubRepoRef? = null,
    ): ParsedUpdateManifest? {
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
        var downloadUrl = listOf(explicitDownload, asset.url)
            .firstOrNull { isSafeExternalUrl(it) }
            .orEmpty()

        val apkSizeBytes = (firstLong(root, listOf("apkSizeBytes", "size")) ?: asset.sizeBytes)
            .coerceAtLeast(0L)
        var apkFileName = firstString(root, listOf("apkFileName", "fileName"))
            .orEmpty()
            .trim()
            .ifEmpty { asset.name }

        var pageUrl = firstString(root, listOf("pageUrl", "page_url", "html_url"))
            .orEmpty()
            .trim()
            .takeIf { isSafeExternalUrl(it) }
            .orEmpty()

        if (fallbackRepo != null && rawTagOrVersion.isNotBlank()) {
            if (downloadUrl.isBlank()) {
                val derived = "https://github.com/${fallbackRepo.owner}/${fallbackRepo.repo}/releases/download/$rawTagOrVersion/$DEFAULT_RELEASE_APK_NAME"
                if (isSafeExternalUrl(derived)) downloadUrl = derived
            }
            if (apkFileName.isBlank()) {
                apkFileName = DEFAULT_RELEASE_APK_NAME
            }
            if (pageUrl.isBlank()) {
                val derivedPage = "https://github.com/${fallbackRepo.owner}/${fallbackRepo.repo}/releases/tag/$rawTagOrVersion"
                if (isSafeExternalUrl(derivedPage)) pageUrl = derivedPage
            }
        }

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

    private fun htmlFragmentToReleaseNotes(decodedHtml: String): String {
        if (decodedHtml.isBlank()) return ""
        val withoutTables = HTML_TABLE_REGEX.replace(decodedHtml, "\n")
        val withHeadings = HTML_H2_REGEX.replace(withoutTables) { "\n## ${it.groupValues[1].trim()}\n" }
            .let { HTML_H3_REGEX.replace(it) { m -> "\n### ${m.groupValues[1].trim()}\n" } }
            .let { HTML_LI_REGEX.replace(it) { m -> "\n- ${m.groupValues[1].trim()}" } }
            .let { HTML_BLOCK_BREAK_REGEX.replace(it, "\n") }
        val plainText = decodeXmlEntities(HTML_TAG_REGEX.replace(withHeadings, ""))
            .lines()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
        return extractInAppReleaseNotes(plainText)
    }

    private fun decodeXmlEntities(input: String): String {
        if (input.isEmpty() || !input.contains('&')) return input
        return XML_ENTITY_REGEX.replace(input) { match ->
            when (val token = match.groupValues[1]) {
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                "amp" -> "&"
                else -> {
                    val codePoint = when {
                        token.startsWith("#x", ignoreCase = true) -> token.drop(2).toIntOrNull(16)
                        token.startsWith("#") -> token.drop(1).toIntOrNull(10)
                        else -> null
                    }
                    if (codePoint != null && Character.isValidCodePoint(codePoint)) {
                        String(Character.toChars(codePoint))
                    } else {
                        match.value
                    }
                }
            }
        }
    }

    internal fun extractInAppReleaseNotes(rawBody: String): String =
        AppUpdateReleaseNotesFormatter.extractChangelog(rawBody)

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
