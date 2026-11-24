package com.gfs.iyango.core.domain

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * 液态玻璃参数“一键分享口令”编解码器。
 *
 * 将四类全局玻璃参数与底栏玻璃参数压缩并编码为紧凑短口令，支持直接从聊天文本中提取口令并容错回退。
 */
object LiquidGlassShareCodec {

    const val PREFIX = "IYANGO-GLASS-V1:"
    private const val FORMAT_VERSION = "v1"
    private const val MAX_DECOMPRESSED_BYTES = 8192
    private const val MAX_BASE64_CHARS = 8192
    private val TOKEN_REGEX = Regex("IYANGO-GLASS-V1:([A-Za-z0-9_-]+)")

    fun encode(preset: LiquidGlassPreset): String = encode(
        name = preset.name,
        globalGlass = preset.globalGlass,
        dockGlass = preset.dockGlass,
        dockSurfaceAlphaLight = preset.dockSurfaceAlphaLight,
        dockSurfaceAlphaDark = preset.dockSurfaceAlphaDark,
    )

    fun encode(
        name: String,
        globalGlass: GlobalGlassSettings,
        dockGlass: DockGlassSettings,
        dockSurfaceAlphaLight: Float,
        dockSurfaceAlphaDark: Float,
    ): String {
        val cleanName = name.replace('\r', ' ').replace('\n', ' ').trim().take(24).ifBlank { "液态玻璃方案" }
        val safeGlobal = globalGlass.sanitized()
        val safeDock = dockGlass.sanitized()
        val safeDockLight = dockSurfaceAlphaLight.takeIf { it.isFinite() }?.coerceIn(0f, 0.8f) ?: 0f
        val safeDockDark = dockSurfaceAlphaDark.takeIf { it.isFinite() }?.coerceIn(0f, 0.8f) ?: 0f

        val payload = buildString {
            append(FORMAT_VERSION).append('\n')
            append(cleanName).append('\n')
            appendLayer(safeGlobal.navigation).append('\n')
            appendLayer(safeGlobal.content).append('\n')
            appendLayer(safeGlobal.control).append('\n')
            appendLayer(safeGlobal.overlay).append('\n')
            appendDock(safeDockLight, safeDockDark, safeDock)
        }.toByteArray(StandardCharsets.UTF_8)

        val compressed = deflate(payload)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
        return "$PREFIX$encoded"
    }

    fun decode(raw: String, generatedId: String = "import_${System.currentTimeMillis()}"): Result<LiquidGlassPreset> =
        runCatching {
            val match = TOKEN_REGEX.find(raw)
                ?: throw IllegalArgumentException("未找到有效的液态玻璃分享口令")
            val body = match.groupValues[1]
            require(body.length in 8..MAX_BASE64_CHARS) { "口令长度异常或已截断" }
            val compressed = Base64.getUrlDecoder().decode(body)
            val decompressed = inflateBounded(compressed)
            val lines = String(decompressed, StandardCharsets.UTF_8).split('\n')
            require(lines.size == 7 && lines[0] == FORMAT_VERSION) { "口令格式不兼容或已损坏" }

            val name = lines[1].replace('\r', ' ').trim().take(24).ifBlank { "导入方案" }
            val nav = parseLayer(lines[2], GlobalGlassSettings.DefaultNavigation)
            val content = parseLayer(lines[3], GlobalGlassSettings.DefaultContent)
            val control = parseLayer(lines[4], GlobalGlassSettings.DefaultControl)
            val overlay = parseLayer(lines[5], GlobalGlassSettings.DefaultOverlay)
            val (dockLight, dockDark, dockGlass) = parseDock(lines[6])

            LiquidGlassPreset(
                id = generatedId,
                name = name,
                globalGlass = GlobalGlassSettings(
                    navigation = nav,
                    content = content,
                    control = control,
                    overlay = overlay,
                ),
                dockGlass = dockGlass,
                dockSurfaceAlphaLight = dockLight,
                dockSurfaceAlphaDark = dockDark,
            ).sanitized()
        }

    private fun StringBuilder.appendLayer(layer: GlassLayerSettings): StringBuilder = append(
        listOf(
            fmt(layer.blurDp),
            fmt(layer.lensWidthDp),
            fmt(layer.lensHeightDp),
            fmt(layer.edgeWidthDp),
            fmt(layer.edgeBlurDp),
            fmt(layer.edgeAlpha),
            fmt(layer.tintAlphaLight),
            fmt(layer.tintAlphaDark),
            fmt(layer.tier0AlphaLight),
            fmt(layer.tier0AlphaDark),
            flag(layer.depthEffect),
            flag(layer.chromaticAberration),
        ).joinToString(","),
    )

    private fun StringBuilder.appendDock(
        dockLight: Float,
        dockDark: Float,
        dock: DockGlassSettings,
    ): StringBuilder = append(
        listOf(
            fmt(dockLight),
            fmt(dockDark),
            fmt(dock.blurDp),
            fmt(dock.lensWidthDp),
            fmt(dock.lensHeightDp),
            fmt(dock.edgeWidthDp),
            fmt(dock.edgeBlurDp),
            fmt(dock.edgeAlphaBase),
            fmt(dock.edgeAlphaHidden),
            fmt(dock.edgeAlphaIndicator),
            fmt(dock.indicatorLensWidthDp),
            fmt(dock.indicatorLensHeightDp),
            flag(dock.indicatorChromaticAberration),
            fmt(dock.indicatorSurfaceAlpha),
            fmt(dock.indicatorSurfacePressedAlpha),
            fmt(dock.indicatorShadowRadiusDp),
            fmt(dock.indicatorShadowAlpha),
            fmt(dock.indicatorInnerRadiusDp),
            fmt(dock.indicatorInnerAlpha),
            fmt(dock.legacyTier0AlphaLight),
            fmt(dock.legacyTier0AlphaDark),
        ).joinToString(","),
    )

    private fun parseLayer(line: String, fallback: GlassLayerSettings): GlassLayerSettings {
        val parts = line.split(',')
        require(parts.size == 12) { "口令参数项缺失" }
        return GlassLayerSettings(
            blurDp = parseFloat(parts[0]),
            lensWidthDp = parseFloat(parts[1]),
            lensHeightDp = parseFloat(parts[2]),
            edgeWidthDp = parseFloat(parts[3]),
            edgeBlurDp = parseFloat(parts[4]),
            edgeAlpha = parseFloat(parts[5]),
            tintAlphaLight = parseFloat(parts[6]),
            tintAlphaDark = parseFloat(parts[7]),
            tier0AlphaLight = parseFloat(parts[8]),
            tier0AlphaDark = parseFloat(parts[9]),
            depthEffect = parseFlag(parts[10]),
            chromaticAberration = parseFlag(parts[11]),
        ).sanitized(fallback)
    }

    private fun parseDock(line: String): Triple<Float, Float, DockGlassSettings> {
        val parts = line.split(',')
        require(parts.size == 21) { "口令底栏参数项缺失" }
        val dockLight = parseFloat(parts[0]).coerceIn(0f, 0.8f)
        val dockDark = parseFloat(parts[1]).coerceIn(0f, 0.8f)
        val dock = DockGlassSettings(
            blurDp = parseFloat(parts[2]),
            lensWidthDp = parseFloat(parts[3]),
            lensHeightDp = parseFloat(parts[4]),
            edgeWidthDp = parseFloat(parts[5]),
            edgeBlurDp = parseFloat(parts[6]),
            edgeAlphaBase = parseFloat(parts[7]),
            edgeAlphaHidden = parseFloat(parts[8]),
            edgeAlphaIndicator = parseFloat(parts[9]),
            indicatorLensWidthDp = parseFloat(parts[10]),
            indicatorLensHeightDp = parseFloat(parts[11]),
            indicatorChromaticAberration = parseFlag(parts[12]),
            indicatorSurfaceAlpha = parseFloat(parts[13]),
            indicatorSurfacePressedAlpha = parseFloat(parts[14]),
            indicatorShadowRadiusDp = parseFloat(parts[15]),
            indicatorShadowAlpha = parseFloat(parts[16]),
            indicatorInnerRadiusDp = parseFloat(parts[17]),
            indicatorInnerAlpha = parseFloat(parts[18]),
            legacyTier0AlphaLight = parseFloat(parts[19]),
            legacyTier0AlphaDark = parseFloat(parts[20]),
        ).sanitized()
        return Triple(dockLight, dockDark, dock)
    }

    private fun parseFloat(token: String): Float {
        val value = token.trim().toFloatOrNull()
            ?: throw IllegalArgumentException("口令包含无效数字")
        require(value.isFinite()) { "口令数值非法" }
        return value
    }

    private fun parseFlag(token: String): Boolean = when (token.trim()) {
        "1", "true" -> true
        "0", "false" -> false
        else -> throw IllegalArgumentException("口令布尔标志无效")
    }

    private fun fmt(value: Float): String {
        val safe = if (value.isFinite()) value else 0f
        val formatted = String.format(Locale.US, "%.3f", safe)
        return formatted.trimEnd('0').trimEnd('.').ifEmpty { "0" }.let {
            if (it == "-0") "0" else it
        }
    }

    private fun flag(value: Boolean): String = if (value) "1" else "0"

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        return try {
            deflater.setInput(input)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(256)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                if (count <= 0) break
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflateBounded(input: ByteArray): ByteArray {
        val inflater = Inflater()
        return try {
            inflater.setInput(input)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(256)
            var total = 0
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    total += count
                    require(total <= MAX_DECOMPRESSED_BYTES) { "口令内容过大" }
                    out.write(buffer, 0, count)
                }
            }
            require(inflater.finished()) { "口令压缩数据不完整" }
            out.toByteArray()
        } finally {
            inflater.end()
        }
    }
}
