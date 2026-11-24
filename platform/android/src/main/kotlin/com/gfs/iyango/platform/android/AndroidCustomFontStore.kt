package com.gfs.iyango.platform.android

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import com.gfs.iyango.core.domain.CustomFontStore
import com.gfs.iyango.core.domain.ImportedFontInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [CustomFontStore] 的 Android 私有目录实现（`filesDir/custom_fonts`）。
 *
 * 复制前校验魔数头（TTF / OTF / TTC）与大小上限（30 MB），并通过 [Typeface.createFromFile]
 * 校验可读性后再原子落盘，避免损坏字体导致全局排版崩溃。
 */
@Singleton
class AndroidCustomFontStore private constructor(
    private val context: Context?,
    private val fontDirectory: File,
) : CustomFontStore {

    @Inject
    constructor(@ApplicationContext context: Context) : this(context, File(context.filesDir, FONT_DIR))

    internal constructor(fontDirectory: File) : this(null, fontDirectory)

    override suspend fun importFromUri(sourceUri: String, suggestedName: String?): ImportedFontInfo? =
        withContext(Dispatchers.IO) {
            val appContext = context ?: return@withContext null
            val uri = runCatching { Uri.parse(sourceUri) }.getOrNull() ?: return@withContext null
            if (uri.scheme.isNullOrEmpty()) return@withContext null

            var tempFile: File? = null
            try {
                if (!fontDirectory.isDirectory && !fontDirectory.mkdirs()) return@withContext null
                tempFile = File.createTempFile("font-import-", ".tmp", fontDirectory)

                var totalBytes = 0L
                val header = ByteArray(4)
                var headerRead = 0
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            totalBytes += read
                            if (totalBytes > MAX_FONT_BYTES) return@withContext null
                            if (headerRead < 4) {
                                val take = minOf(read, 4 - headerRead)
                                System.arraycopy(buffer, 0, header, headerRead, take)
                                headerRead += take
                            }
                            output.write(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                } ?: return@withContext null

                if (headerRead < 4 || !isValidFontHeader(header)) return@withContext null
                val loaded = runCatching { Typeface.createFromFile(tempFile) }.getOrNull() ?: return@withContext null
                @Suppress("UNUSED_VARIABLE")
                val unusedCheck = loaded

                val displayName = resolveDisplayName(appContext, uri, suggestedName)
                val ext = if (isOpenTypeOtfHeader(header) || displayName.lowercase().endsWith(".otf")) "otf" else "ttf"
                val relativeName = "font_${System.currentTimeMillis()}.$ext"
                val targetFile = File(fontDirectory, relativeName)
                moveAtomically(tempFile, targetFile)
                tempFile = null

                ImportedFontInfo(
                    relativePath = relativeName,
                    displayName = displayName,
                    sizeBytes = totalBytes,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            } finally {
                tempFile?.delete()
            }
        }

    override fun resolve(relativePath: String): String? {
        val target = safeResolveFile(relativePath) ?: return null
        return target.takeIf { it.isFile }?.absolutePath
    }

    override suspend fun delete(relativePath: String) {
        withContext(Dispatchers.IO) {
            safeResolveFile(relativePath)?.let { file ->
                if (file.isFile) file.delete()
            }
        }
    }

    private fun safeResolveFile(relativePath: String): File? {
        val clean = relativePath.trim()
        if (clean.isEmpty() || clean.startsWith("/") || clean.startsWith("\\") || clean.contains("..")) {
            return null
        }
        return runCatching {
            val baseCanonical = fontDirectory.canonicalPath
            val target = File(fontDirectory, clean)
            val targetCanonical = target.canonicalPath
            val prefix = if (baseCanonical.endsWith(File.separator)) baseCanonical else "$baseCanonical${File.separator}"
            if (targetCanonical.startsWith(prefix)) target else null
        }.getOrNull()
    }

    private fun resolveDisplayName(context: Context, uri: Uri, suggestedName: String?): String {
        val queried = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        }.getOrNull()
        val raw = (queried ?: suggestedName ?: uri.lastPathSegment).orEmpty()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        return raw.ifEmpty { "自定义字体.ttf" }
    }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val FONT_DIR = "custom_fonts"
        const val MAX_FONT_BYTES = 30L * 1024L * 1024L
    }
}

private fun isOpenTypeOtfHeader(bytes: ByteArray): Boolean =
    bytes.size >= 4 &&
        bytes[0] == 'O'.code.toByte() &&
        bytes[1] == 'T'.code.toByte() &&
        bytes[2] == 'T'.code.toByte() &&
        bytes[3] == 'O'.code.toByte()

/** 校验常见桌面/移动端轮廓字体文件头：TrueType (00 01 00 00 / true)、OpenType (OTTO)、TTC (ttcf)。 */
internal fun isValidFontHeader(bytes: ByteArray): Boolean {
    if (bytes.size < 4) return false
    val b0 = bytes[0].toInt() and 0xFF
    val b1 = bytes[1].toInt() and 0xFF
    val b2 = bytes[2].toInt() and 0xFF
    val b3 = bytes[3].toInt() and 0xFF
    val isTtf = (b0 == 0x00 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00)
    val isTrue = (b0 == 't'.code && b1 == 'r'.code && b2 == 'u'.code && b3 == 'e'.code)
    val isOtf = (b0 == 'O'.code && b1 == 'T'.code && b2 == 'T'.code && b3 == 'O'.code)
    val isTtc = (b0 == 't'.code && b1 == 't'.code && b2 == 'c'.code && b3 == 'f'.code)
    return isTtf || isTrue || isOtf || isTtc
}
