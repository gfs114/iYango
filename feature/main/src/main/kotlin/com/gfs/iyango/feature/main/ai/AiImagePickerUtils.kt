package com.gfs.iyango.feature.main.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.gfs.iyango.core.domain.AiImagePayload
import java.io.ByteArrayOutputStream

private const val MAX_AI_IMAGE_DIMENSION_PX = 1600
private const val MAX_AI_IMAGE_BYTES = 4 * 1024 * 1024
private const val MAX_AI_RAW_URI_BYTES = 24 * 1024 * 1024

internal fun decodeSelectedAiImages(
    context: Context,
    uris: List<Uri>,
    maxImages: Int = 4,
): Pair<List<AiImagePayload>, Int> {
    val result = mutableListOf<AiImagePayload>()
    var skipped = 0
    for (uri in uris.take(maxImages)) {
        val payload = compressAiImageUri(context, uri)
        if (payload != null) {
            result += payload
        } else {
            skipped++
        }
    }
    return result to skipped
}

internal fun compressAiImageUri(
    context: Context,
    uri: Uri,
    displayNameOverride: String = "",
): AiImagePayload? = runCatching {
    val resolver = context.contentResolver
    val rawBytes = resolver.openInputStream(uri)?.use { stream ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = stream.read(buf)
            if (read <= 0) break
            if (out.size() + read > MAX_AI_RAW_URI_BYTES) return@runCatching null
            out.write(buf, 0, read)
        }
        out.toByteArray()
    } ?: return@runCatching null

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

    var sample = 1
    while (bounds.outWidth / sample > MAX_AI_IMAGE_DIMENSION_PX ||
        bounds.outHeight / sample > MAX_AI_IMAGE_DIMENSION_PX
    ) {
        sample *= 2
    }
    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
    val sampled = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
        ?: return@runCatching null

    val scaled = try {
        val maxSide = maxOf(sampled.width, sampled.height)
        if (maxSide > MAX_AI_IMAGE_DIMENSION_PX) {
            val ratio = MAX_AI_IMAGE_DIMENSION_PX.toFloat() / maxSide
            val w = (sampled.width * ratio).toInt().coerceAtLeast(1)
            val h = (sampled.height * ratio).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(sampled, w, h, true).also {
                if (it !== sampled) sampled.recycle()
            }
        } else {
            sampled
        }
    } catch (t: Throwable) {
        sampled.recycle()
        throw t
    }

    val jpegBytes = try {
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        out.toByteArray()
    } finally {
        scaled.recycle()
    }
    if (jpegBytes.isEmpty() || jpegBytes.size > MAX_AI_IMAGE_BYTES) return@runCatching null

    val name = displayNameOverride.ifBlank { queryAiImageDisplayName(context, uri) }
    AiImagePayload(
        bytes = jpegBytes,
        mimeType = "image/jpeg",
        displayName = name,
    )
}.getOrNull()

private fun queryAiImageDisplayName(context: Context, uri: Uri): String {
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val value = cursor.getString(idx)
                    if (!value.isNullOrBlank()) return value
                }
            }
        }
    }
    return uri.lastPathSegment.orEmpty().substringAfterLast('/')
}
