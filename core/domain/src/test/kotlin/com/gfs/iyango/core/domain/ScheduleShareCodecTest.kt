package com.gfs.iyango.core.domain

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.Deflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleShareCodecTest {

    @Test
    fun `roundtrip encodes compact json and extracts from surrounding chat text`() {
        val sampleJson = """{"meta":{"app":"ClassSchedule","version":1},"courses":[{"name":"高等数学","dayOfWeek":1,"startSection":1,"endSection":2,"startWeek":1,"endWeek":18,"weekType":0}]}"""
        val code = ScheduleShareCodec.encode(sampleJson)
        assertTrue(code.startsWith(ScheduleShareCodec.PREFIX_V1))
        assertTrue(ScheduleShareCodec.containsPotentialToken(code))

        val noisy = "这是这学期课表\n$code\n打开 iYango 导入吧！"
        val decoded = ScheduleShareCodec.decode(noisy) as ScheduleShareDecodeResult.Success
        assertEquals(1, decoded.version)
        assertEquals(sampleJson, String(decoded.jsonBytes, StandardCharsets.UTF_8))
    }

    @Test
    fun `rejects empty invalid liquid glass code unsupported version corrupted data and zip bomb`() {
        val empty = ScheduleShareCodec.decode("   ") as ScheduleShareDecodeResult.Failed
        assertEquals(ScheduleShareDecodeError.EMPTY_INPUT, empty.error)

        val invalid = ScheduleShareCodec.decode("随便一段文本") as ScheduleShareDecodeResult.Failed
        assertEquals(ScheduleShareDecodeError.INVALID_FORMAT, invalid.error)

        val glass = ScheduleShareCodec.decode("IYANGO-GLASS-V1:abcdef1234567890") as ScheduleShareDecodeResult.Failed
        assertEquals(ScheduleShareDecodeError.WRONG_CODE_KIND_LIQUID_GLASS, glass.error)

        val futureVer = ScheduleShareCodec.decode("IYANGO-TT99:abcdef1234567890abcdef") as ScheduleShareDecodeResult.Failed
        assertEquals(ScheduleShareDecodeError.UNSUPPORTED_VERSION, futureVer.error)

        val badData = ScheduleShareCodec.decode("IYANGO-TT1:not_valid_zlib_stream_payload_here") as ScheduleShareDecodeResult.Failed
        assertEquals(ScheduleShareDecodeError.CORRUPTED_DATA, badData.error)

        val nonJsonDeflated = deflateToken("hello plain text not json at all")
        val nonJson = ScheduleShareCodec.decode(nonJsonDeflated) as ScheduleShareDecodeResult.Failed
        assertEquals(ScheduleShareDecodeError.CORRUPTED_DATA, nonJson.error)

        // > 512 KB bomb (compressed repetitive zeros)
        val hugePayload = "{\"a\":\"" + "x".repeat(ScheduleShareCodec.MAX_DECOMPRESSED_BYTES + 1024) + "\"}"
        val bombToken = deflateToken(hugePayload)
        val bomb = ScheduleShareCodec.decode(bombToken) as ScheduleShareDecodeResult.Failed
        assertEquals(ScheduleShareDecodeError.PAYLOAD_TOO_LARGE, bomb.error)
        assertFalse(ScheduleShareCodec.containsPotentialToken("IYANGO-GLASS-V1:abc"))
    }

    private fun deflateToken(text: String): String {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        val compressed = try {
            deflater.setInput(bytes)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                if (n > 0) out.write(buf, 0, n) else if (deflater.needsInput()) break
            }
            out.toByteArray()
        } finally {
            deflater.end()
        }
        return ScheduleShareCodec.PREFIX_V1 + Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
    }
}
