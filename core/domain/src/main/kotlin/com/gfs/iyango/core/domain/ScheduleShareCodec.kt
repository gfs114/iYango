package com.gfs.iyango.core.domain

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/** 解码错误分类。 */
enum class ScheduleShareDecodeError {
    EMPTY_INPUT,
    INVALID_FORMAT,
    WRONG_CODE_KIND_LIQUID_GLASS,
    UNSUPPORTED_VERSION,
    CORRUPTED_DATA,
    PAYLOAD_TOO_LARGE,
}

/** 课表分享口令解码结果。 */
sealed interface ScheduleShareDecodeResult {
    data class Success(
        val jsonBytes: ByteArray,
        val version: Int,
    ) : ScheduleShareDecodeResult

    data class Failed(
        val error: ScheduleShareDecodeError,
        val message: String,
    ) : ScheduleShareDecodeResult
}

/**
 * 同班课表分享口令编解码器。
 *
 * 格式：`IYANGO-TT1:<Base64UrlNoPadding(Deflate(UTF8(CompactScheduleJsonV1)))>`
 * 支持直接从包含聊天前后文字的剪贴板消息中提取口令，并用流式解压上限阻断膨胀攻击。
 */
object ScheduleShareCodec {

    const val PROTOCOL_HEAD = "IYANGO-TT"
    const val CURRENT_VERSION = 1
    const val PREFIX_V1 = "IYANGO-TT1:"
    const val MAX_ENCODED_CHARS = 32_768
    const val MAX_DECOMPRESSED_BYTES = 512 * 1024

    private val TOKEN_REGEX = Regex("IYANGO-TT(\\d+):([A-Za-z0-9_=-]{8,})")
    private val BODY_REGEX = Regex("^[A-Za-z0-9_-]+$")
    private const val LIQUID_GLASS_PREFIX_HEAD = "IYANGO-GLASS-V"

    /** 将紧凑 v1 课表 JSON 编码为短口令。 */
    fun encode(compactScheduleJson: String): String {
        val rawBytes = compactScheduleJson.toByteArray(StandardCharsets.UTF_8)
        require(rawBytes.isNotEmpty() && rawBytes.size <= MAX_DECOMPRESSED_BYTES) {
            "课表数据体积异常，建议使用 JSON 文件导出"
        }
        val compressed = deflateBest(rawBytes)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
        require(encoded.length <= MAX_ENCODED_CHARS) {
            "课表分享口令过长，建议使用 JSON 文件导出"
        }
        return "$PREFIX_V1$encoded"
    }

    /** 检查文本中是否疑似包含课表分享口令（供粘贴快捷键等场景做轻量判断）。 */
    fun containsPotentialToken(rawInput: String): Boolean =
        TOKEN_REGEX.containsMatchIn(rawInput)

    /** 从任意文本中提取并解码课表分享口令。 */
    fun decode(rawInput: String): ScheduleShareDecodeResult {
        if (rawInput.isBlank()) {
            return ScheduleShareDecodeResult.Failed(
                error = ScheduleShareDecodeError.EMPTY_INPUT,
                message = "请先输入或粘贴课表分享口令",
            )
        }
        val match = TOKEN_REGEX.find(rawInput)
        if (match == null) {
            if (rawInput.contains(LIQUID_GLASS_PREFIX_HEAD)) {
                return ScheduleShareDecodeResult.Failed(
                    error = ScheduleShareDecodeError.WRONG_CODE_KIND_LIQUID_GLASS,
                    message = "这是液态玻璃外观口令，请前往外观设置导入",
                )
            }
            return ScheduleShareDecodeResult.Failed(
                error = ScheduleShareDecodeError.INVALID_FORMAT,
                message = "未识别到有效的课表分享口令",
            )
        }

        val version = match.groupValues[1].toIntOrNull()
        if (version != CURRENT_VERSION) {
            return ScheduleShareDecodeResult.Failed(
                error = ScheduleShareDecodeError.UNSUPPORTED_VERSION,
                message = "当前版本不支持该口令版本（v${match.groupValues[1]}），请更新应用后重试",
            )
        }

        val body = match.groupValues[2].trimEnd('=')
        if (body.length > MAX_ENCODED_CHARS) {
            return ScheduleShareDecodeResult.Failed(
                error = ScheduleShareDecodeError.PAYLOAD_TOO_LARGE,
                message = "课表口令超出最大长度限制",
            )
        }
        if (body.length < 16 || !BODY_REGEX.matches(body)) {
            return ScheduleShareDecodeResult.Failed(
                error = ScheduleShareDecodeError.CORRUPTED_DATA,
                message = "课表分享口令已截断或包含非法字符",
            )
        }

        val compressed = runCatching { Base64.getUrlDecoder().decode(body) }.getOrNull()
            ?: return ScheduleShareDecodeResult.Failed(
                error = ScheduleShareDecodeError.CORRUPTED_DATA,
                message = "课表分享口令编码损坏，请重新复制完整口令",
            )

        val decompressed = when (val inflateResult = inflateBounded(compressed)) {
            InflateOutcome.TooLarge -> return ScheduleShareDecodeResult.Failed(
                error = ScheduleShareDecodeError.PAYLOAD_TOO_LARGE,
                message = "解压后的课表内容过大，已拦截导入",
            )
            InflateOutcome.Corrupted -> return ScheduleShareDecodeResult.Failed(
                error = ScheduleShareDecodeError.CORRUPTED_DATA,
                message = "课表分享口令数据损坏，请让发送方重新生成",
            )
            is InflateOutcome.Ok -> inflateResult.bytes
        }

        val text = decodeStrictUtf8(decompressed)
            ?: return ScheduleShareDecodeResult.Failed(
                error = ScheduleShareDecodeError.CORRUPTED_DATA,
                message = "口令内容不是有效的 UTF-8 课表文本",
            )
        val trimmed = text.trim()
        if (!(trimmed.startsWith('{') && trimmed.endsWith('}')) &&
            !(trimmed.startsWith('[') && trimmed.endsWith(']'))
        ) {
            return ScheduleShareDecodeResult.Failed(
                error = ScheduleShareDecodeError.CORRUPTED_DATA,
                message = "口令内的课表格式损坏，无法解析",
            )
        }

        return ScheduleShareDecodeResult.Success(
            jsonBytes = decompressed,
            version = version,
        )
    }

    private fun deflateBest(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        return try {
            deflater.setInput(input)
            deflater.finish()
            val out = ByteArrayOutputStream(input.size.coerceAtLeast(128))
            val buffer = ByteArray(4096)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                if (count > 0) out.write(buffer, 0, count)
                else if (deflater.needsInput()) break
            }
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private sealed interface InflateOutcome {
        data class Ok(val bytes: ByteArray) : InflateOutcome
        data object TooLarge : InflateOutcome
        data object Corrupted : InflateOutcome
    }

    private fun inflateBounded(compressed: ByteArray): InflateOutcome {
        if (compressed.isEmpty()) return InflateOutcome.Corrupted
        val inflater = Inflater()
        return try {
            inflater.setInput(compressed)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var total = 0
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        return InflateOutcome.Corrupted
                    }
                    continue
                }
                total += count
                if (total > MAX_DECOMPRESSED_BYTES) {
                    return InflateOutcome.TooLarge
                }
                out.write(buffer, 0, count)
            }
            if (total == 0) InflateOutcome.Corrupted else InflateOutcome.Ok(out.toByteArray())
        } catch (_: DataFormatException) {
            InflateOutcome.Corrupted
        } finally {
            inflater.end()
        }
    }

    private fun decodeStrictUtf8(bytes: ByteArray): String? = try {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val buffer: CharBuffer = decoder.decode(ByteBuffer.wrap(bytes))
        buffer.toString()
    } catch (_: Exception) {
        null
    }
}
