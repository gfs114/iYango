package com.gfs.iyango.feature.main.importexport

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.ByteArrayOutputStream

private const val MAX_QR_DECODE_DIMENSION_PX = 1600
private const val MAX_QR_URI_BYTES = 16 * 1024 * 1024

private val QR_DECODE_HINTS: Map<DecodeHintType, Any> = mapOf(
    DecodeHintType.TRY_HARDER to true,
    DecodeHintType.CHARACTER_SET to "UTF-8",
)

/**
 * 纯离线二维码解码器：兼容白底黑码、暗色截图反色与相册大图降采样。
 */
internal fun decodeQrFromBitmap(bitmap: Bitmap): String? {
    if (bitmap.width <= 0 || bitmap.height <= 0) return null
    val scaled = downscaleForDecode(bitmap)
    return try {
        val width = scaled.width
        val height = scaled.height
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        decodeFromLuminanceSource(source) ?: decodeFromLuminanceSource(source.invert())
    } finally {
        if (scaled !== bitmap) scaled.recycle()
    }
}

/**
 * 从相册或系统文件 Uri 解码二维码，失败返回 null。
 */
internal fun decodeQrFromUri(context: Context, uri: Uri): String? = runCatching {
    val rawBytes = context.contentResolver.openInputStream(uri)?.use { stream ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            if (out.size() + read > MAX_QR_URI_BYTES) return@runCatching null
            out.write(buffer, 0, read)
        }
        out.toByteArray()
    } ?: return@runCatching null

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

    var sample = 1
    while (bounds.outWidth / sample > MAX_QR_DECODE_DIMENSION_PX ||
        bounds.outHeight / sample > MAX_QR_DECODE_DIMENSION_PX
    ) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts)
        ?: return@runCatching null
    try {
        decodeQrFromBitmap(bitmap)
    } finally {
        bitmap.recycle()
    }
}.getOrNull()

/**
 * 从 CameraX ImageAnalysis YUV 亮度平面解码二维码，自动处理 rowStride 行填充。
 */
internal fun decodeQrFromYuvPlane(
    yBytes: ByteArray,
    width: Int,
    height: Int,
    rowStride: Int,
): String? {
    if (width <= 0 || height <= 0 || rowStride < width) return null
    val compact = if (rowStride == width) {
        if (yBytes.size < width * height) return null
        yBytes
    } else {
        if (yBytes.size < rowStride * (height - 1) + width) return null
        ByteArray(width * height).also { dst ->
            for (row in 0 until height) {
                System.arraycopy(yBytes, row * rowStride, dst, row * width, width)
            }
        }
    }
    val source = PlanarYUVLuminanceSource(
        compact,
        width,
        height,
        0,
        0,
        width,
        height,
        false,
    )
    return decodeFromLuminanceSource(source)
}

private fun decodeFromLuminanceSource(source: LuminanceSource): String? {
    val reader = QRCodeReader()
    runCatching {
        val text = reader.decode(BinaryBitmap(HybridBinarizer(source)), QR_DECODE_HINTS).text
        if (!text.isNullOrBlank()) return text
    }
    reader.reset()
    return runCatching {
        reader.decode(BinaryBitmap(GlobalHistogramBinarizer(source)), QR_DECODE_HINTS).text
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

private fun downscaleForDecode(bitmap: Bitmap): Bitmap {
    val maxSide = maxOf(bitmap.width, bitmap.height)
    if (maxSide <= MAX_QR_DECODE_DIMENSION_PX) return bitmap
    val ratio = MAX_QR_DECODE_DIMENSION_PX.toFloat() / maxSide.toFloat()
    val targetWidth = (bitmap.width * ratio).toInt().coerceAtLeast(1)
    val targetHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
}
