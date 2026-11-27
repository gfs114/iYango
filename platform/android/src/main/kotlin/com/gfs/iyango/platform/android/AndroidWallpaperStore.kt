package com.gfs.iyango.platform.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.gfs.iyango.core.domain.CropPixelRegion
import com.gfs.iyango.core.domain.ImportedWallpaperMedia
import com.gfs.iyango.core.domain.PreparedWallpaper
import com.gfs.iyango.core.domain.WallpaperEnginePackageParser
import com.gfs.iyango.core.domain.WallpaperMediaType
import com.gfs.iyango.core.domain.WallpaperStore
import com.gfs.iyango.core.domain.WallpaperTone
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [WallpaperStore] 的 Android 实现（F10）。
 *
 * 流程分两段：[prepare]/[prepareFromBytes] 只做"两次采样解码 → EXIF 转正 →
 * 限制长边 → 亮度分析 → 写 cache 临时文件"，不做任何裁剪；[commit] 按裁剪页
 * 给出的像素窗口裁剪后写入 `filesDir/wallpapers`。
 *
 * 正式文件按"槽位 + 时间戳"唯一命名：同路径覆盖会让按路径做键的图片缓存
 * 命中旧位图（界面不刷新，参考实现踩过的坑），唯一命名天然规避；提交成功后
 * 顺带清理同槽位的旧文件。
 */
@Singleton
class AndroidWallpaperStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : WallpaperStore {

    override suspend fun prepare(sourceUri: String): PreparedWallpaper? = withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(sourceUri)
            if (uri.scheme.isNullOrEmpty()) return@runCatching null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val boundsStream = context.contentResolver.openInputStream(uri) ?: return@runCatching null
            boundsStream.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_SOURCE_DIMEN)
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return@runCatching null

            val oriented = applyExifOrientation(uri, decoded)
            val scaled = downscale(oriented, MAX_OUTPUT_WIDTH, MAX_OUTPUT_HEIGHT)
            val prepared = writeTemp(scaled)

            if (scaled !== oriented) scaled.recycle()
            if (oriented !== decoded) oriented.recycle()
            decoded.recycle()
            prepared
        }.getOrNull()
    }

    override suspend fun prepareFromBytes(bytes: ByteArray): PreparedWallpaper? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_SOURCE_DIMEN)
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: return@runCatching null
            // 备份中的图片由本应用生成（无 EXIF 朝向、已按目标比例裁好），仅做限界。
            val scaled = downscale(decoded, MAX_OUTPUT_WIDTH, MAX_OUTPUT_HEIGHT)
            val prepared = writeTemp(scaled)
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
            prepared
        }.getOrNull()
    }

    override suspend fun commit(
        tempAbsolutePath: String,
        slot: String,
        crop: CropPixelRegion?,
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val temp = File(tempAbsolutePath)
            if (!temp.isFile) return@runCatching null
            val source = BitmapFactory.decodeFile(temp.absolutePath) ?: return@runCatching null
            val finalBitmap = applyCrop(source, crop) ?: source

            val dir = File(context.filesDir, SAVED_DIR).apply { mkdirs() }
            val safeSlot = sanitizeSlot(slot)
            val targetName = "wallpaper_${safeSlot}_${System.currentTimeMillis()}.jpg"
            val target = File(dir, targetName)
            val incoming = File(dir, ".incoming_${System.currentTimeMillis()}.jpg")
            FileOutputStream(incoming).use { stream ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            }
            if (target.exists() && !target.delete()) {
                incoming.delete()
                return@runCatching null
            }
            if (!incoming.renameTo(target)) {
                incoming.copyTo(target, overwrite = true)
                incoming.delete()
            }
            temp.delete()
            if (finalBitmap !== source) finalBitmap.recycle()
            source.recycle()
            // 唯一命名后旧文件成为孤儿，按槽位前缀清理（轮播图槽位允许多张并存，不删同前缀旧图）。
            if (!safeSlot.startsWith(WallpaperStore.SLOT_CAROUSEL_PREFIX)) {
                cleanupSlotFiles(dir, "wallpaper_${safeSlot}_", target.name)
            }
            "$SAVED_DIR/${target.name}"
        }.getOrNull()
    }

    override suspend fun extractAccentHex(relativePath: String): String? = withContext(Dispatchers.IO) {
        val absolutePath = resolve(relativePath) ?: return@withContext null
        if (com.gfs.iyango.core.domain.WallpaperMediaKind.isVideoPath(relativePath)) {
            val frame = extractVideoFrame(absolutePath) ?: return@withContext null
            return@withContext try {
                extractAccentFromBitmap(frame)
            } finally {
                frame.recycle()
            }
        }
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, 64)
            }
            val bitmap = BitmapFactory.decodeFile(absolutePath, options) ?: return@runCatching null
            try {
                extractAccentFromBitmap(bitmap)
            } finally {
                bitmap.recycle()
            }
        }.getOrNull()
    }

    override suspend fun importVideoOrPackage(sourceUri: String, slot: String): ImportedWallpaperMedia? =
        withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(sourceUri)
                if (uri.scheme.isNullOrEmpty()) return@runCatching null

                val displayName = queryDisplayName(uri).orEmpty()
                val mime = context.contentResolver.getType(uri).orEmpty().lowercase(Locale.ROOT)
                val ext = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
                val safeSlot = sanitizeSlot(slot)
                val dir = File(context.filesDir, SAVED_DIR).apply { mkdirs() }

                val looksLikePackage = ext in PACKAGE_EXTENSIONS ||
                    mime == "application/zip" ||
                    (!mime.startsWith("video/") && !mime.startsWith("image/") && peekMagicIsPackage(uri))

                if (looksLikePackage) {
                    val tempDir = File(context.cacheDir, TEMP_DIR).apply { mkdirs() }
                    var extractedTempFile: File? = null
                    val extracted = context.contentResolver.openInputStream(uri)?.use { stream ->
                        WallpaperEnginePackageParser.extractBestMedia(stream) { chosenExt ->
                            val out = File(tempDir, "pkg_${System.currentTimeMillis()}.$chosenExt")
                            extractedTempFile = out
                            FileOutputStream(out)
                        }
                    }
                    val tempFile = extractedTempFile
                    if (extracted != null && tempFile != null && tempFile.isFile && tempFile.length() > 0L) {
                        try {
                            if (extracted.mediaType == WallpaperMediaType.VIDEO) {
                                val normalizedExt = if (extracted.extension == "webm") "webm" else "mp4"
                                val lum = sampleVideoLuminance(tempFile.absolutePath)
                                val target = File(dir, "wallpaper_${safeSlot}_${System.currentTimeMillis()}.$normalizedExt")
                                copyReplacing(tempFile, target)
                                if (!safeSlot.startsWith(WallpaperStore.SLOT_CAROUSEL_PREFIX)) {
                                    cleanupSlotFiles(dir, "wallpaper_${safeSlot}_", target.name)
                                }
                                return@runCatching ImportedWallpaperMedia(
                                    relativePath = "$SAVED_DIR/${target.name}",
                                    isVideo = true,
                                    averageLuminance = lum,
                                    isScenePreview = extracted.hasScenePackage,
                                )
                            } else {
                                val prepared = prepareFromLocalImageFile(tempFile) ?: return@runCatching null
                                val rel = commit(prepared.tempPath, slot, null) ?: run {
                                    discard(prepared.tempPath)
                                    return@runCatching null
                                }
                                return@runCatching ImportedWallpaperMedia(
                                    relativePath = rel,
                                    isVideo = false,
                                    averageLuminance = prepared.averageLuminance,
                                    isScenePreview = extracted.hasScenePackage,
                                )
                            }
                        } finally {
                            tempFile.delete()
                        }
                    }
                    tempFile?.delete()
                    if (ext in PACKAGE_EXTENSIONS) return@runCatching null
                }

                if (mime.startsWith("video/") || ext in DIRECT_VIDEO_EXTENSIONS) {
                    val normalizedExt = if (ext == "webm" || mime.contains("webm")) "webm" else "mp4"
                    val tempDir = File(context.cacheDir, TEMP_DIR).apply { mkdirs() }
                    val tempVideo = File(tempDir, "vid_${System.currentTimeMillis()}.$normalizedExt")
                    try {
                        val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(tempVideo).use { output ->
                                copyStreamCapped(input, output, MAX_DIRECT_VIDEO_BYTES)
                            }
                        } ?: -1L
                        if (copied <= 0L) return@runCatching null
                        val lum = sampleVideoLuminance(tempVideo.absolutePath)
                        val target = File(dir, "wallpaper_${safeSlot}_${System.currentTimeMillis()}.$normalizedExt")
                        copyReplacing(tempVideo, target)
                        if (!safeSlot.startsWith(WallpaperStore.SLOT_CAROUSEL_PREFIX)) {
                            cleanupSlotFiles(dir, "wallpaper_${safeSlot}_", target.name)
                        }
                        return@runCatching ImportedWallpaperMedia(
                            relativePath = "$SAVED_DIR/${target.name}",
                            isVideo = true,
                            averageLuminance = lum,
                        )
                    } finally {
                        tempVideo.delete()
                    }
                }

                val prepared = prepare(sourceUri) ?: return@runCatching null
                val rel = commit(prepared.tempPath, slot, null) ?: run {
                    discard(prepared.tempPath)
                    return@runCatching null
                }
                ImportedWallpaperMedia(
                    relativePath = rel,
                    isVideo = false,
                    averageLuminance = prepared.averageLuminance,
                )
            }.getOrNull()
        }

    private fun extractAccentFromBitmap(bitmap: Bitmap): String? {
        val total = bitmap.width * bitmap.height
        if (total <= 0) return null
        val step = maxOf(1, total / MAX_TONE_SAMPLES)
        val stepX = maxOf(1, kotlin.math.sqrt(step.toDouble()).toInt())
        val stepY = maxOf(1, step / stepX)
        val samples = ArrayList<Int>(MAX_TONE_SAMPLES)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                samples += bitmap.getPixel(x, y)
                x += stepX
            }
            y += stepY
        }
        return WallpaperTone.extractDominantAccentHex(samples.toIntArray())
    }

    private fun extractVideoFrame(absolutePath: String): Bitmap? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(absolutePath)
            retriever.getFrameAtTime(0L)
                ?: retriever.getFrameAtTime(-1L)
                ?: retriever.getFrameAtTime(1_000_000L)
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrNull()

    private fun sampleVideoLuminance(absolutePath: String): Float {
        val frame = extractVideoFrame(absolutePath) ?: return DEFAULT_VIDEO_LUMINANCE
        return try {
            val scaled = downscale(frame, 128, 128)
            try {
                sampledLuminance(scaled)
            } finally {
                if (scaled !== frame) scaled.recycle()
            }
        } finally {
            frame.recycle()
        }
    }

    private fun prepareFromLocalImageFile(file: File): PreparedWallpaper? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_SOURCE_DIMEN)
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        val scaled = downscale(decoded, MAX_OUTPUT_WIDTH, MAX_OUTPUT_HEIGHT)
        val prepared = writeTemp(scaled)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        return prepared
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun peekMagicIsPackage(uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buf = ByteArray(12)
            val read = stream.read(buf)
            if (read < 4) return@runCatching false
            val head4 = buf.copyOfRange(0, 4).toString(Charsets.US_ASCII)
            if (head4 == "TEXV") return@runCatching true
            if (read < 8) return@runCatching false
            val ascii = buf.copyOfRange(4, read).toString(Charsets.US_ASCII)
            ascii.startsWith("PKGV") || ascii.startsWith("PKGM") || ascii.startsWith("MPKG")
        } ?: false
    }.getOrDefault(false)

    private fun copyReplacing(src: File, dest: File) {
        if (dest.exists()) dest.delete()
        if (!src.renameTo(dest)) {
            src.copyTo(dest, overwrite = true)
        }
    }

    private fun copyStreamCapped(input: java.io.InputStream, output: FileOutputStream, limitBytes: Long): Long {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > limitBytes) return -1L
            output.write(buffer, 0, read)
        }
        return total
    }

    override suspend fun discard(tempAbsolutePath: String) = withContext(Dispatchers.IO) {
        runCatching { File(tempAbsolutePath).delete() }
        Unit
    }

    override fun resolve(relativePath: String): String? {
        if (relativePath.isBlank()) return null
        return runCatching {
            val root = File(context.filesDir, SAVED_DIR).canonicalFile
            val file = File(context.filesDir, relativePath).canonicalFile
            val inside = file.path == root.path ||
                file.path.startsWith(root.path + File.separator)
            file.takeIf { inside && it.isFile }?.absolutePath
        }.getOrNull()
    }

    override suspend fun delete(relativePath: String) = withContext(Dispatchers.IO) {
        resolve(relativePath)?.let { runCatching { File(it).delete() } }
        Unit
    }

    override suspend fun readBytes(relativePath: String): ByteArray? = withContext(Dispatchers.IO) {
        resolve(relativePath)?.let { path -> runCatching { File(path).readBytes() }.getOrNull() }
    }

    // ---------------- 编码与裁剪辅助 ----------------

    /** 把限界后的位图写进 cache 临时文件，并附带裁剪所需的尺寸与亮度元数据。 */
    private fun writeTemp(bitmap: Bitmap): PreparedWallpaper? {
        val dir = File(context.cacheDir, TEMP_DIR).apply { mkdirs() }
        val out = File(dir, "pending_${System.currentTimeMillis()}.jpg")
        FileOutputStream(out).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        }
        if (out.length() <= 0L) return null
        return PreparedWallpaper(
            tempPath = out.absolutePath,
            width = bitmap.width,
            height = bitmap.height,
            averageLuminance = sampledLuminance(bitmap),
        )
    }

    /**
     * 按像素窗口裁剪；窗口来自裁剪页针对临时文件的像素空间，先做防御性映射与
     * 边界夹取（独立解码的输出尺寸不应被假定与预览一致）。null 窗口返回 null（整图采用）。
     */
    private fun applyCrop(source: Bitmap, crop: CropPixelRegion?): Bitmap? {
        if (crop == null) return null
        val region = com.gfs.iyango.core.domain.CropGeometry.scaleRegion(
            crop,
            fromWidth = source.width,
            fromHeight = source.height,
            toWidth = source.width,
            toHeight = source.height,
        ) ?: return null
        val left = region.x.coerceIn(0, source.width - 1)
        val top = region.y.coerceIn(0, source.height - 1)
        val width = region.width.coerceIn(1, source.width - left)
        val height = region.height.coerceIn(1, source.height - top)
        if (width == source.width && height == source.height) return null
        return runCatching {
            Bitmap.createBitmap(source, left, top, width, height)
        }.getOrNull()
    }

    /** 避免对同一实例重复 recycle。 */
    private fun sampledLuminance(bitmap: Bitmap): Float {
        val total = bitmap.width * bitmap.height
        if (total <= 0) return WallpaperTone.LUMINANCE_UNKNOWN
        val step = maxOf(1, total / MAX_TONE_SAMPLES)
        val stepX = maxOf(1, kotlin.math.sqrt(step.toDouble()).toInt())
        val stepY = maxOf(1, step / stepX)
        val samples = ArrayList<Int>(MAX_TONE_SAMPLES)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                samples += bitmap.getPixel(x, y)
                x += stepX
            }
            y += stepY
        }
        return WallpaperTone.averageLuminance(samples.toIntArray())
    }

    /** 2 的幂采样率：保证解码后的短边仍不小于目标，避免过度降采样。 */
    private fun sampleSizeFor(width: Int, height: Int, maxDimen: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= maxDimen) {
            sample *= 2
            longest /= 2
        }
        return sample
    }

    private fun applyExifOrientation(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    private fun downscale(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxWidth && height <= maxHeight) return bitmap
        val scale = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
        return Bitmap.createScaledBitmap(
            bitmap,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    /** 只匹配当前完整槽位的时间戳文件，global 不能清理 global_dark。 */
    private fun cleanupSlotFiles(dir: File, prefix: String, keepName: String) {
        runCatching {
            val pattern = Regex("${Regex.escape(prefix)}[0-9]+\\.(jpg|mp4|webm)")
            dir.listFiles { file -> pattern.matches(file.name) && file.name != keepName }
                ?.forEach { it.delete() }
        }
    }

    // 旧版亮暗图片共用 global_/timetable_，无法从文件名推断归属，不能批量清理。
    // 新亮色前缀显式带 light；备份导入仍可沿用原来的 global/timetable 参数。
    private fun sanitizeSlot(slot: String): String = when (slot) {
        WallpaperStore.SLOT_GLOBAL -> "global_light"
        WallpaperStore.SLOT_TIMETABLE -> "timetable_light"
        else -> slot.filter { it.isLetterOrDigit() || it == '_' }.ifEmpty { "global_light" }
    }

    private companion object {
        const val TEMP_DIR = "wallpaper_tmp"
        const val SAVED_DIR = "wallpapers"
        const val MAX_SOURCE_DIMEN = 2048
        const val MAX_OUTPUT_WIDTH = 1440
        const val MAX_OUTPUT_HEIGHT = 2560
        const val JPEG_QUALITY = 88
        const val MAX_TONE_SAMPLES = 4096
        const val DEFAULT_VIDEO_LUMINANCE = 0.45f
        const val MAX_DIRECT_VIDEO_BYTES = 150L * 1024L * 1024L
        val PACKAGE_EXTENSIONS = setOf("mpkg", "pkg", "zip", "tex")
        val DIRECT_VIDEO_EXTENSIONS = setOf("mp4", "webm")
    }
}
