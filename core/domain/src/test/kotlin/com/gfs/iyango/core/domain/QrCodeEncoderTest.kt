package com.gfs.iyango.core.domain

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeEncoderTest {

    @Test
    fun `rs capacity tables match exact geometric matrix data capacity for all 40 versions`() {
        for (version in 1..40) {
            val size = 17 + 4 * version
            val reserved = computeFunctionAndFormatCount(version, size)
            val freeBits = size * size - reserved
            val expectedTotalCodewords = freeBits / 8
            val expectedRemainder = freeBits % 8

            for (level in QrErrorCorrectionLevel.entries) {
                val spec = QrCodeEncoder.specFor(version, level)
                val actualTotalCodewords =
                    spec.g1Blocks * (spec.g1DataBytes + spec.eccPerBlock) +
                        spec.g2Blocks * (spec.g2DataBytes + spec.eccPerBlock)
                assertEquals(
                    "Total codewords mismatch at v=$version level=$level",
                    expectedTotalCodewords,
                    actualTotalCodewords,
                )
                assertEquals(
                    "Remainder bits mismatch at v=$version level=$level",
                    expectedRemainder,
                    spec.remainderBits,
                )
            }
        }
    }

    @Test
    fun `encodes byte payloads across M and L thresholds and verifies ISO decode roundtrip`() {
        val payloads = listOf(
            "IYANGO-TT1:short_sample_123456" to QrErrorCorrectionLevel.M,
            ("A".repeat(800)) to QrErrorCorrectionLevel.M,
            ("B".repeat(801)) to QrErrorCorrectionLevel.L,
            ("IYANGO-TT1:" + "AbCdEf0123456789_-".repeat(80)) to QrErrorCorrectionLevel.L,
            ("X".repeat(2_400)) to QrErrorCorrectionLevel.L,
        )
        for ((text, expectedLevel) in payloads) {
            val result = QrCodeEncoder.encode(text) as QrEncodeResult.Ready
            val matrix = result.matrix
            assertEquals(expectedLevel, matrix.correctionLevel)
            assertFinderPatterns(matrix)
            assertEquals(text, decodeByteModeFromMatrix(matrix))
        }

        val tooLarge = QrCodeEncoder.encode("Z".repeat(2_401)) as QrEncodeResult.TooLarge
        assertEquals(2_401, tooLarge.byteCount)
    }

    private fun assertFinderPatterns(matrix: QrMatrix) {
        val corners = listOf(0 to 0, matrix.size - 7 to 0, 0 to matrix.size - 7)
        for ((ox, oy) in corners) {
            for (dy in 0..6) {
                for (dx in 0..6) {
                    val expectedDark = dx == 0 || dx == 6 || dy == 0 || dy == 6 || (dx in 2..4 && dy in 2..4)
                    assertEquals(expectedDark, matrix.isDark(ox + dx, oy + dy))
                }
            }
        }
    }

    private fun decodeByteModeFromMatrix(matrix: QrMatrix): String {
        val size = matrix.size
        val version = matrix.version
        val isFunc = buildFunctionMask(version, size)

        // Read primary format bits along y=8 & x=8 around top-left finder
        var rawFmt = 0
        for (x in 0..5) rawFmt = (rawFmt shl 1) or if (matrix.isDark(x, 8)) 1 else 0
        rawFmt = (rawFmt shl 1) or if (matrix.isDark(7, 8)) 1 else 0
        rawFmt = (rawFmt shl 1) or if (matrix.isDark(8, 8)) 1 else 0
        rawFmt = (rawFmt shl 1) or if (matrix.isDark(8, 7)) 1 else 0
        for (y in 5 downTo 0) rawFmt = (rawFmt shl 1) or if (matrix.isDark(8, y)) 1 else 0
        val unmaskedFmt = rawFmt xor 0x5412
        val maskId = (unmaskedFmt ushr 10) and 0b111
        val levelBits = (unmaskedFmt ushr 13) and 0b11
        assertEquals(matrix.correctionLevel.formatBits, levelBits)

        // Unmask and zig-zag read codeword bits
        val spec = QrCodeEncoder.specFor(version, matrix.correctionLevel)
        val totalBits = (spec.totalDataBytes + spec.g1Blocks * spec.eccPerBlock + spec.g2Blocks * spec.eccPerBlock) * 8
        val rawBits = ArrayList<Boolean>(totalBits)
        var dirUp = true
        var rightCol = size - 1
        while (rightCol >= 1 && rawBits.size < totalBits) {
            if (rightCol == 6) rightCol--
            for (step in 0 until size) {
                val y = if (dirUp) size - 1 - step else step
                for (dx in 0..1) {
                    val x = rightCol - dx
                    if (!isFunc[y * size + x]) {
                        val invert = when (maskId) {
                            0 -> (y + x) % 2 == 0
                            1 -> y % 2 == 0
                            2 -> x % 3 == 0
                            3 -> (y + x) % 3 == 0
                            4 -> (y / 2 + x / 3) % 2 == 0
                            5 -> ((y * x) % 2) + ((y * x) % 3) == 0
                            6 -> (((y * x) % 2) + ((y * x) % 3)) % 2 == 0
                            7 -> (((y + x) % 2) + ((y * x) % 3)) % 2 == 0
                            else -> false
                        }
                        rawBits.add(matrix.isDark(x, y) xor invert)
                        if (rawBits.size == totalBits) break
                    }
                }
                if (rawBits.size == totalBits) break
            }
            dirUp = !dirUp
            rightCol -= 2
        }

        val allBytes = IntArray(totalBits / 8) { i ->
            var b = 0
            for (j in 0 until 8) {
                b = (b shl 1) or if (rawBits[i * 8 + j]) 1 else 0
            }
            b
        }

        // De-interleave RS blocks & verify syndrome == 0
        val dataBlocks = ArrayList<ByteArray>()
        repeat(spec.g1Blocks) { dataBlocks.add(ByteArray(spec.g1DataBytes)) }
        repeat(spec.g2Blocks) { dataBlocks.add(ByteArray(spec.g2DataBytes)) }
        var idx = 0
        val maxData = maxOf(spec.g1DataBytes, spec.g2DataBytes)
        for (pos in 0 until maxData) {
            for (block in dataBlocks) {
                if (pos < block.size) block[pos] = allBytes[idx++].toByte()
            }
        }
        val eccBlocks = Array(dataBlocks.size) { ByteArray(spec.eccPerBlock) }
        for (pos in 0 until spec.eccPerBlock) {
            for (bIdx in eccBlocks.indices) {
                eccBlocks[bIdx][pos] = allBytes[idx++].toByte()
            }
        }
        for (bIdx in dataBlocks.indices) {
            val expectedEcc = QrCodeEncoder.ReedSolomon.computeEcc(dataBlocks[bIdx], spec.eccPerBlock)
            assertTrue("RS syndrome mismatch for block $bIdx", expectedEcc.contentEquals(eccBlocks[bIdx]))
        }

        // Parse Byte Mode bit stream
        val dataBits = ArrayList<Boolean>()
        for (block in dataBlocks) {
            for (b in block) {
                for (bit in 7 downTo 0) dataBits.add(((b.toInt() ushr bit) and 1) != 0)
            }
        }
        fun readInt(start: Int, len: Int): Int {
            var v = 0
            for (i in 0 until len) v = (v shl 1) or if (dataBits[start + i]) 1 else 0
            return v
        }
        val mode = readInt(0, 4)
        assertEquals(0b0100, mode)
        val countLen = if (version <= 9) 8 else 16
        val count = readInt(4, countLen)
        val out = ByteArray(count) { i -> readInt(4 + countLen + i * 8, 8).toByte() }
        return String(out, StandardCharsets.UTF_8)
    }

    private fun computeFunctionAndFormatCount(version: Int, size: Int): Int =
        buildFunctionMask(version, size).count { it }

    private fun buildFunctionMask(version: Int, size: Int): BooleanArray {
        val isFunc = BooleanArray(size * size)
        fun mark(x: Int, y: Int) { if (x in 0 until size && y in 0 until size) isFunc[y * size + x] = true }
        for ((ox, oy) in listOf(0 to 0, size - 7 to 0, 0 to size - 7)) {
            for (dy in -1..7) for (dx in -1..7) mark(ox + dx, oy + dy)
        }
        for (i in 8 until size - 8) {
            mark(i, 6); mark(6, i)
        }
        val centers = QrCodeEncoder.alignmentCoordinates(version)
        for (cy in centers) for (cx in centers) {
            if ((cx <= 8 && cy <= 8) || (cx >= size - 9 && cy <= 8) || (cx <= 8 && cy >= size - 9)) continue
            for (dy in -2..2) for (dx in -2..2) mark(cx + dx, cy + dy)
        }
        mark(8, 4 * version + 9)
        for (i in 0..8) { mark(i, 8); mark(8, i) }
        for (i in size - 8 until size) mark(i, 8)
        for (i in size - 7 until size) mark(8, i)
        if (version >= 7) {
            for (i in 0 until 6) for (j in 0 until 3) {
                mark(size - 11 + j, i); mark(i, size - 11 + j)
            }
        }
        return isFunc
    }
}
