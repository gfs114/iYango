package com.gfs.iyango.core.domain

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/** 从 Wallpaper Engine 安装包或视频文件中提取出的媒体类型。 */
enum class WallpaperMediaType {
    VIDEO,
    IMAGE,
}

/** 媒体路径扩展名识别辅助（用于区分循环视频壁纸与静态图片壁纸）。 */
object WallpaperMediaKind {
    private val VIDEO_EXTENSIONS = setOf("mp4", "webm")

    fun isVideoPath(path: String?): Boolean {
        val clean = path?.trim()?.substringBefore('?')?.lowercase(Locale.ROOT) ?: return false
        val ext = clean.substringAfterLast('.', "")
        return ext in VIDEO_EXTENSIONS
    }
}

/** 解析安装包后实际写出的最优媒体信息。 */
data class ExtractedPackageEntry(
    val entryName: String,
    val extension: String,
    val mediaType: WallpaperMediaType,
    val byteCount: Long,
    val hasScenePackage: Boolean,
)

/** 在 `.tex` 纹理头部定位到的内嵌 MP4 切片（偏移与已知长度，`-1L` 表示持续到流尾）。 */
internal data class TexMp4Slice(
    val offsetInEntry: Long,
    val length: Long,
)

/**
 * Wallpaper Engine 安装包（`.pkg` / `.mpkg` / `.zip` / `.tex` 封装）流式提取器（纯 JVM 实现）。
 *
 * - 支持 Wallpaper Engine 二进制 `PKGVxxxx` / `PKGMxxxx` / `MPKGxxxx` 容器：按头部索引跳过前置字节并
 *   流式写出最优视频（含 `TEXB0004` 内嵌 MP4 视频纹理）或高清预览图，不把整包加载进内存；
 * - 兼容 `.zip` 封装与独立 `.tex` 视频纹理文件；
 * - 对非主图资源（遮罩、粒子、缩略图、图标等）施加评分惩罚，避免误将遮罩或特效图提取为主壁纸；
 * - 若包内含 `.pkg` / `.tex` / `scene.json` 等 3D 场景资源但无视频流，会标记
 *   [ExtractedPackageEntry.hasScenePackage]，供上层提示用户已提取 3D 场景静态预览图。
 */
object WallpaperEnginePackageParser {

    private const val MAX_ENTRY_COUNT = 10_000
    private const val MAX_NAME_BYTES = 1024
    private const val MAX_VIDEO_BYTES = 150L * 1024L * 1024L
    private const val MAX_IMAGE_BYTES = 30L * 1024L * 1024L
    private const val BUFFER_BYTES = 16 * 1024
    private const val TEX_HEADER_SCAN_BYTES = 4096
    private const val MIN_TEX_VIDEO_BYTES = 512L

    private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "mov")
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
    private val PENALIZED_ASSET_TOKENS = listOf(
        "mask",
        "alpha",
        "normal",
        "bump",
        "particle",
        "sprite",
        "thumb",
        "icon",
    )

    private data class BinaryEntry(
        val name: String,
        val offset: Long,
        val size: Long,
        val extension: String,
        val mediaType: WallpaperMediaType?,
    )

    private data class CandidateScore(
        val priority: Int,
        val size: Long,
    ) : Comparable<CandidateScore> {
        override fun compareTo(other: CandidateScore): Int {
            val byPriority = priority.compareTo(other.priority)
            return if (byPriority != 0) byPriority else size.compareTo(other.size)
        }
    }

    fun extractBestMedia(
        input: InputStream,
        outputFactory: (extension: String) -> OutputStream,
    ): ExtractedPackageEntry? {
        val buffered = BufferedInputStream(input, BUFFER_BYTES)
        buffered.mark(8)
        val head = ByteArray(4)
        val readHead = readAtMost(buffered, head)
        if (readHead < 4) return null
        buffered.reset()

        val isZip = head[0] == 0x50.toByte() &&
            head[1] == 0x4B.toByte() &&
            head[2] == 0x03.toByte() &&
            head[3] == 0x04.toByte()
        if (isZip) {
            return extractFromZip(buffered, outputFactory)
        }
        val isStandaloneTex = head[0] == 'T'.code.toByte() &&
            head[1] == 'E'.code.toByte() &&
            head[2] == 'X'.code.toByte() &&
            head[3] == 'V'.code.toByte()
        return if (isStandaloneTex) {
            extractFromStandaloneTex(buffered, outputFactory)
        } else {
            extractFromBinaryPackage(buffered, outputFactory)
        }
    }

    /**
     * 在 `TEXV...TEXB...` 纹理头部字节窗口中定位内嵌 MP4（`[boxSize BE]["ftyp"]`）。
     * `boxSize` 约束在 `16..128` 且为 4 的倍数以过滤随机文本碰撞；
     * 前置小端 uint32 若落在合理视频区间则作为精确长度以截断尾部元数据。
     */
    internal fun locateEmbeddedMp4InTexHeader(
        header: ByteArray,
        headerLen: Int,
        totalEntrySize: Long = -1L,
    ): TexMp4Slice? {
        val safeLen = minOf(headerLen, header.size)
        if (safeLen < 32) return null
        if (header[0] != 'T'.code.toByte() ||
            header[1] != 'E'.code.toByte() ||
            header[2] != 'X'.code.toByte() ||
            header[3] != 'V'.code.toByte()
        ) {
            return null
        }
        if (!containsAsciiFourBytes(header, safeLen, 'T', 'E', 'X', 'B')) return null

        for (pos in 8..(safeLen - 8)) {
            if (header[pos] != 'f'.code.toByte() ||
                header[pos + 1] != 't'.code.toByte() ||
                header[pos + 2] != 'y'.code.toByte() ||
                header[pos + 3] != 'p'.code.toByte()
            ) {
                continue
            }
            val boxSize = ((header[pos - 4].toLong() and 0xFFL) shl 24) or
                ((header[pos - 3].toLong() and 0xFFL) shl 16) or
                ((header[pos - 2].toLong() and 0xFFL) shl 8) or
                (header[pos - 1].toLong() and 0xFFL)
            if (boxSize !in 16L..128L || boxSize % 4L != 0L) continue

            val mp4Offset = (pos - 4).toLong()
            val declaredLen = if (mp4Offset >= 4L) {
                (header[pos - 8].toLong() and 0xFFL) or
                    ((header[pos - 7].toLong() and 0xFFL) shl 8) or
                    ((header[pos - 6].toLong() and 0xFFL) shl 16) or
                    ((header[pos - 5].toLong() and 0xFFL) shl 24)
            } else {
                -1L
            }

            if (totalEntrySize > 0L) {
                val maxRemaining = totalEntrySize - mp4Offset
                if (maxRemaining !in 64L..MAX_VIDEO_BYTES) continue
                val mp4Length = if (declaredLen in maxOf(64L, boxSize)..maxRemaining) {
                    declaredLen
                } else {
                    maxRemaining
                }
                return TexMp4Slice(offsetInEntry = mp4Offset, length = mp4Length)
            }

            val mp4Length = if (declaredLen in maxOf(64L, boxSize)..MAX_VIDEO_BYTES) {
                declaredLen
            } else {
                -1L
            }
            return TexMp4Slice(offsetInEntry = mp4Offset, length = mp4Length)
        }
        return null
    }

    private fun extractFromStandaloneTex(
        input: BufferedInputStream,
        outputFactory: (extension: String) -> OutputStream,
    ): ExtractedPackageEntry? {
        val headBuf = ByteArray(TEX_HEADER_SCAN_BYTES)
        val headLen = readAtMost(input, headBuf)
        val slice = locateEmbeddedMp4InTexHeader(headBuf, headLen, -1L) ?: return null
        val written = streamTexSlice(headBuf, headLen, slice, input, outputFactory) ?: return null
        return ExtractedPackageEntry(
            entryName = "wallpaper.tex",
            extension = "mp4",
            mediaType = WallpaperMediaType.VIDEO,
            byteCount = written,
            hasScenePackage = true,
        )
    }

    private fun extractFromBinaryPackage(
        input: BufferedInputStream,
        outputFactory: (extension: String) -> OutputStream,
    ): ExtractedPackageEntry? {
        val magicLen = readUint32Le(input) ?: return null
        if (magicLen !in 4L..32L) return null
        val magicBytes = readExactBytes(input, magicLen.toInt()) ?: return null
        val magic = magicBytes.toString(Charsets.US_ASCII)
        if (!magic.startsWith("PKGV") && !magic.startsWith("PKGM") && !magic.startsWith("MPKG")) {
            return null
        }

        val entryCount = readUint32Le(input)?.takeIf { it in 1L..MAX_ENTRY_COUNT.toLong() }?.toInt()
            ?: return null
        val entries = ArrayList<BinaryEntry>(entryCount)
        for (i in 0 until entryCount) {
            val nameLen = readUint32Le(input)?.takeIf { it in 1L..MAX_NAME_BYTES.toLong() }?.toInt()
                ?: return null
            val nameBytes = readExactBytes(input, nameLen) ?: return null
            val name = nameBytes.toString(Charsets.UTF_8)
            val offset = readUint32Le(input) ?: return null
            val size = readUint32Le(input) ?: return null
            val ext = extensionOf(name)
            entries += BinaryEntry(
                name = name,
                offset = offset,
                size = size,
                extension = ext,
                mediaType = mediaTypeOf(ext),
            )
        }

        val hasScenePackage = entries.any { entry -> isSceneEntryName(entry.name) }

        val bestDirectVideo = entries
            .mapNotNull { entry ->
                if (entry.mediaType != WallpaperMediaType.VIDEO) return@mapNotNull null
                if (entry.size <= 0L || entry.size > MAX_VIDEO_BYTES) return@mapNotNull null
                entry to scoreOf(entry.name, entry.extension, WallpaperMediaType.VIDEO, entry.size)
            }
            .maxByOrNull { it.second }
            ?.first
        if (bestDirectVideo != null) {
            if (!skipBytesReliably(input, bestDirectVideo.offset)) return null
            val copied = outputFactory(bestDirectVideo.extension).use { output ->
                copyExactBytes(input, bestDirectVideo.size, output)
            }
            if (copied != bestDirectVideo.size) return null
            return ExtractedPackageEntry(
                entryName = bestDirectVideo.name,
                extension = bestDirectVideo.extension,
                mediaType = WallpaperMediaType.VIDEO,
                byteCount = copied,
                hasScenePackage = hasScenePackage,
            )
        }

        val texCandidates = entries.filter { entry ->
            entry.extension == "tex" && entry.size in MIN_TEX_VIDEO_BYTES..MAX_VIDEO_BYTES
        }
        if (texCandidates.isNotEmpty()) {
            val hasNonPenalizedTex = texCandidates.any { !isPenalizedAssetPath(it.name) }
            val eligibleTex = texCandidates.filter { !hasNonPenalizedTex || !isPenalizedAssetPath(it.name) }
            val eligibleImages = entries.filter { entry ->
                entry.mediaType == WallpaperMediaType.IMAGE && entry.size in 1L..MAX_IMAGE_BYTES
            }
            val walkList = (eligibleTex + eligibleImages).sortedBy { it.offset }

            var cursor = 0L
            var bestImageScore: CandidateScore? = null
            var bestImageEntry: BinaryEntry? = null
            var bestImageBytes: ByteArray? = null

            for (entry in walkList) {
                if (entry.offset < cursor) continue
                val gap = entry.offset - cursor
                if (!skipBytesReliably(input, gap)) return null
                cursor = entry.offset

                if (entry.extension == "tex") {
                    val headLen = minOf(TEX_HEADER_SCAN_BYTES.toLong(), entry.size).toInt()
                    val headBytes = readExactBytes(input, headLen) ?: return null
                    cursor += headLen
                    val slice = locateEmbeddedMp4InTexHeader(headBytes, headLen, entry.size)
                    if (slice != null) {
                        val written = streamTexSlice(headBytes, headLen, slice, input, outputFactory)
                            ?: return null
                        return ExtractedPackageEntry(
                            entryName = entry.name,
                            extension = "mp4",
                            mediaType = WallpaperMediaType.VIDEO,
                            byteCount = written,
                            hasScenePackage = hasScenePackage,
                        )
                    }
                    val remaining = entry.size - headLen
                    if (!skipBytesReliably(input, remaining)) return null
                    cursor += remaining
                } else {
                    val score = scoreOf(entry.name, entry.extension, WallpaperMediaType.IMAGE, entry.size)
                    if (bestImageScore == null || score > bestImageScore) {
                        val imageBytes = readExactBytes(input, entry.size.toInt()) ?: return null
                        cursor += entry.size
                        bestImageScore = score
                        bestImageEntry = entry
                        bestImageBytes = imageBytes
                    } else {
                        if (!skipBytesReliably(input, entry.size)) return null
                        cursor += entry.size
                    }
                }
            }

            if (bestImageEntry != null && bestImageBytes != null) {
                outputFactory(bestImageEntry.extension).use { output ->
                    output.write(bestImageBytes)
                }
                return ExtractedPackageEntry(
                    entryName = bestImageEntry.name,
                    extension = bestImageEntry.extension,
                    mediaType = WallpaperMediaType.IMAGE,
                    byteCount = bestImageBytes.size.toLong(),
                    hasScenePackage = hasScenePackage,
                )
            }
            return null
        }

        val bestImage = entries
            .mapNotNull { entry ->
                if (entry.mediaType != WallpaperMediaType.IMAGE) return@mapNotNull null
                if (entry.size <= 0L || entry.size > MAX_IMAGE_BYTES) return@mapNotNull null
                entry to scoreOf(entry.name, entry.extension, WallpaperMediaType.IMAGE, entry.size)
            }
            .maxByOrNull { it.second }
            ?.first ?: return null

        if (!skipBytesReliably(input, bestImage.offset)) return null
        val copied = outputFactory(bestImage.extension).use { output ->
            copyExactBytes(input, bestImage.size, output)
        }
        if (copied != bestImage.size) return null

        return ExtractedPackageEntry(
            entryName = bestImage.name,
            extension = bestImage.extension,
            mediaType = WallpaperMediaType.IMAGE,
            byteCount = copied,
            hasScenePackage = hasScenePackage,
        )
    }

    private fun extractFromZip(
        input: BufferedInputStream,
        outputFactory: (extension: String) -> OutputStream,
    ): ExtractedPackageEntry? {
        var hasScenePackage = false
        var bestScore: CandidateScore? = null
        var bestName = ""
        var bestExt = ""
        var bestType: WallpaperMediaType? = null
        var bestBytes: ByteArray? = null

        ZipInputStream(input).use { zip ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.orEmpty()
                if (isSceneEntryName(name)) {
                    hasScenePackage = true
                }
                val ext = extensionOf(name)
                val declaredSize = entry.size

                if (ext == "tex") {
                    if (declaredSize > MAX_VIDEO_BYTES) continue
                    val headBuf = ByteArray(TEX_HEADER_SCAN_BYTES)
                    val headLen = readAtMost(zip, headBuf)
                    val slice = locateEmbeddedMp4InTexHeader(headBuf, headLen, declaredSize) ?: continue
                    val sliceOffset = slice.offsetInEntry.toInt()
                    if (sliceOffset !in 0 until headLen) continue
                    val maxAllowed = if (slice.length > 0L) slice.length else MAX_VIDEO_BYTES
                    val inHeadCount = headLen - sliceOffset
                    val writeFromHead = minOf(inHeadCount.toLong(), maxAllowed).toInt()
                    val candidateOut = ByteArrayOutputStream()
                    candidateOut.write(headBuf, sliceOffset, writeFromHead)
                    var total = writeFromHead.toLong()
                    var overflow = false
                    if (total < maxAllowed) {
                        while (true) {
                            val maxRead = minOf(buffer.size.toLong(), maxAllowed - total).toInt()
                            if (maxRead <= 0) break
                            val read = zip.read(buffer, 0, maxRead)
                            if (read <= 0) break
                            total += read
                            if (total > MAX_VIDEO_BYTES) {
                                overflow = true
                                break
                            }
                            candidateOut.write(buffer, 0, read)
                        }
                        if (!overflow && slice.length <= 0L && total >= MAX_VIDEO_BYTES && zip.read() != -1) {
                            overflow = true
                        }
                    }
                    if (overflow || total <= 0L) continue
                    if (slice.length > 0L && total != slice.length) continue
                    val score = scoreOf(name, "mp4", WallpaperMediaType.VIDEO, total, fromTex = true)
                    if (bestScore == null || score > bestScore) {
                        bestScore = score
                        bestName = name
                        bestExt = "mp4"
                        bestType = WallpaperMediaType.VIDEO
                        bestBytes = candidateOut.toByteArray()
                    }
                    continue
                }

                val type = mediaTypeOf(ext) ?: continue
                val limit = if (type == WallpaperMediaType.VIDEO) MAX_VIDEO_BYTES else MAX_IMAGE_BYTES
                if (declaredSize > limit) continue

                val candidateOut = ByteArrayOutputStream()
                var total = 0L
                var overflow = false
                while (true) {
                    val read = zip.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > limit) {
                        overflow = true
                        break
                    }
                    candidateOut.write(buffer, 0, read)
                }
                if (overflow || total <= 0L) continue
                val score = scoreOf(name, ext, type, total)
                if (bestScore == null || score > bestScore) {
                    bestScore = score
                    bestName = name
                    bestExt = ext
                    bestType = type
                    bestBytes = candidateOut.toByteArray()
                }
            }
        }

        val finalBytes = bestBytes ?: return null
        val finalType = bestType ?: return null
        outputFactory(bestExt).use { output ->
            output.write(finalBytes)
        }
        return ExtractedPackageEntry(
            entryName = bestName,
            extension = bestExt,
            mediaType = finalType,
            byteCount = finalBytes.size.toLong(),
            hasScenePackage = hasScenePackage,
        )
    }

    private fun streamTexSlice(
        headBytes: ByteArray,
        headLen: Int,
        slice: TexMp4Slice,
        remainingInput: InputStream,
        outputFactory: (extension: String) -> OutputStream,
    ): Long? {
        val sliceOffset = slice.offsetInEntry.toInt()
        if (sliceOffset !in 0 until headLen) return null
        val inHeadCount = headLen - sliceOffset
        val targetTotal = slice.length
        if (targetTotal > 0L) {
            val writeFromHead = minOf(inHeadCount.toLong(), targetTotal).toInt()
            val remainingFromStream = targetTotal - writeFromHead
            val totalWritten = outputFactory("mp4").use { output ->
                output.write(headBytes, sliceOffset, writeFromHead)
                if (remainingFromStream > 0L) {
                    writeFromHead + copyExactBytes(remainingInput, remainingFromStream, output)
                } else {
                    writeFromHead.toLong()
                }
            }
            return totalWritten.takeIf { it == targetTotal }
        }
        val cap = MAX_VIDEO_BYTES
        val writeFromHead = minOf(inHeadCount.toLong(), cap).toInt()
        val totalWritten = outputFactory("mp4").use { output ->
            output.write(headBytes, sliceOffset, writeFromHead)
            var total = writeFromHead.toLong()
            val buffer = ByteArray(BUFFER_BYTES)
            while (total < cap) {
                val maxRead = minOf(buffer.size.toLong(), cap - total).toInt()
                val read = remainingInput.read(buffer, 0, maxRead)
                if (read <= 0) break
                output.write(buffer, 0, read)
                total += read
            }
            total
        }
        return totalWritten.takeIf { it >= 64L }
    }

    private fun scoreOf(
        name: String,
        extension: String,
        mediaType: WallpaperMediaType,
        size: Long,
        fromTex: Boolean = false,
    ): CandidateScore {
        val baseName = name.substringAfterLast('/').lowercase(Locale.ROOT)
        val priority = when (mediaType) {
            WallpaperMediaType.VIDEO -> when {
                !fromTex && !baseName.startsWith("preview") && (extension == "mp4" || extension == "webm") -> 6
                fromTex || extension == "mp4" || extension == "webm" -> 5
                else -> 4
            }
            WallpaperMediaType.IMAGE -> when {
                isPenalizedAssetPath(name) -> 1
                baseName.startsWith("preview") -> 2
                else -> 3
            }
        }
        return CandidateScore(priority = priority, size = size)
    }

    private fun isSceneEntryName(name: String): Boolean =
        name.endsWith(".pkg", ignoreCase = true) ||
            name.endsWith(".tex", ignoreCase = true) ||
            name.substringAfterLast('/').equals("scene.json", ignoreCase = true)

    private fun isPenalizedAssetPath(path: String): Boolean {
        val lower = path.lowercase(Locale.ROOT)
        return PENALIZED_ASSET_TOKENS.any { token -> lower.contains(token) }
    }

    private fun containsAsciiFourBytes(
        buffer: ByteArray,
        length: Int,
        c0: Char,
        c1: Char,
        c2: Char,
        c3: Char,
    ): Boolean {
        if (length < 4) return false
        val b0 = c0.code.toByte()
        val b1 = c1.code.toByte()
        val b2 = c2.code.toByte()
        val b3 = c3.code.toByte()
        for (i in 0..(length - 4)) {
            if (buffer[i] == b0 && buffer[i + 1] == b1 && buffer[i + 2] == b2 && buffer[i + 3] == b3) {
                return true
            }
        }
        return false
    }

    private fun extensionOf(name: String): String =
        name.substringAfterLast('/', name).substringAfterLast('.', "").lowercase(Locale.ROOT)

    private fun mediaTypeOf(extension: String): WallpaperMediaType? = when (extension) {
        in VIDEO_EXTENSIONS -> WallpaperMediaType.VIDEO
        in IMAGE_EXTENSIONS -> WallpaperMediaType.IMAGE
        else -> null
    }

    private fun readUint32Le(input: InputStream): Long? {
        val bytes = readExactBytes(input, 4) ?: return null
        return (bytes[0].toLong() and 0xFFL) or
            ((bytes[1].toLong() and 0xFFL) shl 8) or
            ((bytes[2].toLong() and 0xFFL) shl 16) or
            ((bytes[3].toLong() and 0xFFL) shl 24)
    }

    private fun readExactBytes(input: InputStream, count: Int): ByteArray? {
        val out = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(out, offset, count - offset)
            if (read <= 0) return null
            offset += read
        }
        return out
    }

    private fun readAtMost(input: InputStream, dest: ByteArray): Int {
        var offset = 0
        while (offset < dest.size) {
            val read = input.read(dest, offset, dest.size - offset)
            if (read <= 0) break
            offset += read
        }
        return offset
    }

    private fun skipBytesReliably(input: InputStream, count: Long): Boolean {
        if (count <= 0L) return true
        val discard = ByteArray(BUFFER_BYTES)
        var remaining = count
        while (remaining > 0L) {
            val toRead = minOf(discard.size.toLong(), remaining).toInt()
            val read = input.read(discard, 0, toRead)
            if (read <= 0) return false
            remaining -= read
        }
        return true
    }

    private fun copyExactBytes(input: InputStream, count: Long, output: OutputStream): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var remaining = count
        var total = 0L
        while (remaining > 0L) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read <= 0) break
            output.write(buffer, 0, read)
            total += read
            remaining -= read
        }
        return total
    }
}
