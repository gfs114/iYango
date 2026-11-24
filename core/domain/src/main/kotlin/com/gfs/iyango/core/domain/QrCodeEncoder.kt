package com.gfs.iyango.core.domain

import java.nio.charset.StandardCharsets

/** 二维码纠错等级（字节模式支持 L/M）。 */
enum class QrErrorCorrectionLevel(val formatBits: Int) {
    M(0),
    L(1),
}

/** 编码后的二维码方阵。 */
data class QrMatrix(
    val size: Int,
    val modules: BooleanArray,
    val version: Int,
    val correctionLevel: QrErrorCorrectionLevel,
) {
    fun isDark(x: Int, y: Int): Boolean = modules[y * size + x]

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QrMatrix) return false
        return size == other.size &&
            version == other.version &&
            correctionLevel == other.correctionLevel &&
            modules.contentEquals(other.modules)
    }

    override fun hashCode(): Int {
        var result = size
        result = 31 * result + version
        result = 31 * result + correctionLevel.hashCode()
        result = 31 * result + modules.contentHashCode()
        return result
    }
}

sealed interface QrEncodeResult {
    data class Ready(val matrix: QrMatrix) : QrEncodeResult
    data class TooLarge(val byteCount: Int, val maxBytes: Int) : QrEncodeResult
}

/**
 * 零依赖纯 Kotlin ISO/IEC 18004 Byte Mode QR Code 编码器（Version 1..40，L / M 档）。
 */
object QrCodeEncoder {

    const val PRACTICAL_MAX_BYTES = 2_400
    private const val COMPACT_M_THRESHOLD = 800

    fun encode(text: String): QrEncodeResult {
        val dataBytes = text.toByteArray(StandardCharsets.UTF_8)
        require(dataBytes.isNotEmpty()) { "二维码内容不能为空" }
        if (dataBytes.size > PRACTICAL_MAX_BYTES) {
            return QrEncodeResult.TooLarge(byteCount = dataBytes.size, maxBytes = PRACTICAL_MAX_BYTES)
        }
        val preferredLevel = if (dataBytes.size <= COMPACT_M_THRESHOLD) {
            QrErrorCorrectionLevel.M
        } else {
            QrErrorCorrectionLevel.L
        }
        val spec = findFittingSpec(dataBytes.size, preferredLevel)
            ?: return QrEncodeResult.TooLarge(byteCount = dataBytes.size, maxBytes = PRACTICAL_MAX_BYTES)

        val codewords = buildFinalCodewords(dataBytes, spec)
        val matrix = renderBestMaskMatrix(spec.version, spec.level, codewords)
        return QrEncodeResult.Ready(matrix)
    }

    internal data class VersionSpec(
        val version: Int,
        val level: QrErrorCorrectionLevel,
        val totalDataBytes: Int,
        val eccPerBlock: Int,
        val g1Blocks: Int,
        val g1DataBytes: Int,
        val g2Blocks: Int,
        val g2DataBytes: Int,
        val remainderBits: Int,
    )

    internal fun findFittingSpec(byteCount: Int, preferred: QrErrorCorrectionLevel): VersionSpec? {
        for (v in 1..40) {
            val spec = specFor(v, preferred)
            val countBits = if (v <= 9) 8 else 16
            val neededBits = 4 + countBits + byteCount * 8
            if (neededBits <= spec.totalDataBytes * 8) return spec
        }
        return null
    }

    internal fun specFor(version: Int, level: QrErrorCorrectionLevel): VersionSpec {
        val raw = when (level) {
            QrErrorCorrectionLevel.L -> RS_L[version - 1]
            QrErrorCorrectionLevel.M -> RS_M[version - 1]
        }
        val ecPerBlock = raw[0]
        val g1Blocks = raw[1]
        val g1Data = raw[2]
        val g2Blocks = raw[3]
        val g2Data = raw[4]
        val totalData = g1Blocks * g1Data + g2Blocks * g2Data
        val remainder = REMAINDER_BITS[version - 1]
        return VersionSpec(version, level, totalData, ecPerBlock, g1Blocks, g1Data, g2Blocks, g2Data, remainder)
    }

    private fun buildFinalCodewords(dataBytes: ByteArray, spec: VersionSpec): List<Boolean> {
        val bits = BitBuffer()
        // Mode = Byte (0100)
        bits.appendBits(0b0100, 4)
        val countBits = if (spec.version <= 9) 8 else 16
        bits.appendBits(dataBytes.size, countBits)
        for (b in dataBytes) {
            bits.appendBits(b.toInt() and 0xFF, 8)
        }
        val totalDataBits = spec.totalDataBytes * 8
        val terminator = minOf(4, totalDataBits - bits.size)
        if (terminator > 0) bits.appendBits(0, terminator)
        while (bits.size % 8 != 0) bits.appendBit(false)
        val bytes = bits.toByteList().toMutableList()
        var padIndex = 0
        while (bytes.size < spec.totalDataBytes) {
            bytes.add(if (padIndex % 2 == 0) 0xEC else 0x11)
            padIndex++
        }

        // 拆分为 RS 块并生成纠错码字
        val dataBlocks = ArrayList<ByteArray>(spec.g1Blocks + spec.g2Blocks)
        val eccBlocks = ArrayList<ByteArray>(spec.g1Blocks + spec.g2Blocks)
        var offset = 0
        repeat(spec.g1Blocks) {
            val block = ByteArray(spec.g1DataBytes) { i -> bytes[offset + i].toByte() }
            offset += spec.g1DataBytes
            dataBlocks.add(block)
            eccBlocks.add(ReedSolomon.computeEcc(block, spec.eccPerBlock))
        }
        repeat(spec.g2Blocks) {
            val block = ByteArray(spec.g2DataBytes) { i -> bytes[offset + i].toByte() }
            offset += spec.g2DataBytes
            dataBlocks.add(block)
            eccBlocks.add(ReedSolomon.computeEcc(block, spec.eccPerBlock))
        }

        // 交织写入比特流
        val out = BitBuffer()
        val maxDataLen = maxOf(spec.g1DataBytes, spec.g2DataBytes)
        for (i in 0 until maxDataLen) {
            for (block in dataBlocks) {
                if (i < block.size) out.appendBits(block[i].toInt() and 0xFF, 8)
            }
        }
        for (i in 0 until spec.eccPerBlock) {
            for (ecc in eccBlocks) {
                out.appendBits(ecc[i].toInt() and 0xFF, 8)
            }
        }
        repeat(spec.remainderBits) { out.appendBit(false) }
        return out.toBooleanList()
    }

    private fun renderBestMaskMatrix(
        version: Int,
        level: QrErrorCorrectionLevel,
        codewordsBits: List<Boolean>,
    ): QrMatrix {
        val size = 17 + 4 * version
        val template = Array(size) { IntArray(size) { -1 } } // -1=数据区, 0=白功能块, 1=黑功能块, 2=预留格式/版本
        placeFunctionPatterns(template, version, size)

        // 先填充原始数据位到 -1 槽位，再对 8 种掩码分别拷贝评分
        val baseModules = BooleanArray(size * size)
        val isFunction = BooleanArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val cell = template[y][x]
                val idx = y * size + x
                if (cell >= 0) {
                    isFunction[idx] = true
                    baseModules[idx] = cell == 1
                }
            }
        }

        placeDataBits(size, isFunction, baseModules, codewordsBits)

        var bestPenalty = Int.MAX_VALUE
        var bestModules = BooleanArray(size * size)
        for (mask in 0..7) {
            val candidate = baseModules.copyOf()
            applyMask(size, isFunction, candidate, mask)
            writeFormatInfo(size, candidate, level, mask)
            if (version >= 7) {
                writeVersionInfo(size, candidate, version)
            }
            val penalty = evaluatePenalty(size, candidate)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestModules = candidate
            }
        }
        return QrMatrix(
            size = size,
            modules = bestModules,
            version = version,
            correctionLevel = level,
        )
    }

    private fun placeFunctionPatterns(grid: Array<IntArray>, version: Int, size: Int) {
        // 3 个 Finder Patterns (7x7) + 1 格白色分隔符
        placeFinderWithSeparator(grid, 0, 0, size)
        placeFinderWithSeparator(grid, size - 7, 0, size)
        placeFinderWithSeparator(grid, 0, size - 7, size)

        // Timing Patterns
        for (i in 8 until size - 8) {
            val dark = if (i % 2 == 0) 1 else 0
            if (grid[6][i] == -1) grid[6][i] = dark
            if (grid[i][6] == -1) grid[i][6] = dark
        }

        // Alignment Patterns (Version >= 2)
        val centers = alignmentCoordinates(version)
        for (cy in centers) {
            for (cx in centers) {
                // 若与 3 个 Finder 重叠则跳过
                if ((cx <= 8 && cy <= 8) ||
                    (cx >= size - 9 && cy <= 8) ||
                    (cx <= 8 && cy >= size - 9)
                ) {
                    continue
                }
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        val maxDist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                        grid[cy + dy][cx + dx] = if (maxDist == 2 || maxDist == 0) 1 else 0
                    }
                }
            }
        }

        // Dark Module
        grid[4 * version + 9][8] = 1

        // 预留格式信息区（先标 2 占为功能位，稍后按掩码写入）
        for (i in 0..8) {
            if (grid[8][i] == -1) grid[8][i] = 2
            if (grid[i][8] == -1) grid[i][8] = 2
        }
        for (i in size - 8 until size) {
            grid[8][i] = 2
        }
        for (i in size - 7 until size) {
            grid[i][8] = 2
        }

        // 预留版本信息区 (Version >= 7)
        if (version >= 7) {
            for (i in 0 until 6) {
                for (j in 0 until 3) {
                    grid[i][size - 11 + j] = 2
                    grid[size - 11 + j][i] = 2
                }
            }
        }
    }

    private fun placeFinderWithSeparator(grid: Array<IntArray>, startX: Int, startY: Int, size: Int) {
        for (dy in -1..7) {
            for (dx in -1..7) {
                val x = startX + dx
                val y = startY + dy
                if (x !in 0 until size || y !in 0 until size) continue
                val dark = if (dx !in 0..6 || dy !in 0..6) {
                    0
                } else if (dx == 0 || dx == 6 || dy == 0 || dy == 6 || (dx in 2..4 && dy in 2..4)) {
                    1
                } else {
                    0
                }
                grid[y][x] = dark
            }
        }
    }

    private fun placeDataBits(
        size: Int,
        isFunction: BooleanArray,
        modules: BooleanArray,
        bits: List<Boolean>,
    ) {
        var bitIdx = 0
        var dirUp = true
        var rightCol = size - 1
        while (rightCol >= 1) {
            if (rightCol == 6) rightCol-- // 跳过竖直 timing pattern 列
            for (step in 0 until size) {
                val y = if (dirUp) size - 1 - step else step
                for (dx in 0..1) {
                    val x = rightCol - dx
                    val idx = y * size + x
                    if (!isFunction[idx]) {
                        modules[idx] = if (bitIdx < bits.size) bits[bitIdx++] else false
                    }
                }
            }
            dirUp = !dirUp
            rightCol -= 2
        }
    }

    private fun applyMask(size: Int, isFunction: BooleanArray, modules: BooleanArray, mask: Int) {
        for (y in 0 until size) {
            for (x in 0 until size) {
                val idx = y * size + x
                if (isFunction[idx]) continue
                val invert = when (mask) {
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
                if (invert) modules[idx] = !modules[idx]
            }
        }
    }

    private fun writeFormatInfo(
        size: Int,
        modules: BooleanArray,
        level: QrErrorCorrectionLevel,
        mask: Int,
    ) {
        val data = (level.formatBits shl 3) or mask
        var rem = data
        repeat(10) {
            rem = (rem shl 1) xor (((rem ushr 9) and 1) * 0x537)
        }
        val formatBits = ((data shl 10) or rem) xor 0x5412

        // 0..14 从 MSB 到 LSB（bit 14 为最先写入的格式位）
        fun bitAt(pos: Int): Boolean = ((formatBits ushr pos) and 1) != 0

        // 左上 Finder 周围 + 另外两角
        for (i in 0..5) modules[8 * size + i] = bitAt(14 - i)
        modules[8 * size + 7] = bitAt(8)
        modules[8 * size + 8] = bitAt(7)
        modules[7 * size + 8] = bitAt(6)
        for (i in 9..14) {
            val y = 14 - i
            modules[y * size + 8] = bitAt(14 - i)
        }

        // 右上横排 8 位（bit 7..0）+ 左下竖排 7 位（bit 14..8）
        for (i in 0..6) {
            modules[(size - 1 - i) * size + 8] = bitAt(14 - i)
        }
        for (i in 0..7) {
            modules[8 * size + (size - 8 + i)] = bitAt(7 - i)
        }
    }

    private fun writeVersionInfo(size: Int, modules: BooleanArray, version: Int) {
        var rem = version
        repeat(12) {
            rem = (rem shl 1) xor (((rem ushr 11) and 1) * 0x1F25)
        }
        val bits = (version shl 12) or rem
        for (i in 0 until 18) {
            val dark = ((bits ushr i) and 1) != 0
            val a = i / 3
            val b = size - 11 + (i % 3)
            modules[a * size + b] = dark
            modules[b * size + a] = dark
        }
    }

    private fun evaluatePenalty(size: Int, modules: BooleanArray): Int {
        var penalty = 0
        // N1: 行列连续 5 个及以上同色
        for (y in 0 until size) {
            var runLen = 1
            for (x in 1 until size) {
                if (modules[y * size + x] == modules[y * size + x - 1]) {
                    runLen++
                    if (runLen == 5) penalty += 3 else if (runLen > 5) penalty += 1
                } else {
                    runLen = 1
                }
            }
        }
        for (x in 0 until size) {
            var runLen = 1
            for (y in 1 until size) {
                if (modules[y * size + x] == modules[(y - 1) * size + x]) {
                    runLen++
                    if (runLen == 5) penalty += 3 else if (runLen > 5) penalty += 1
                } else {
                    runLen = 1
                }
            }
        }
        // N2: 2x2 同色块
        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val c = modules[y * size + x]
                if (c == modules[y * size + x + 1] &&
                    c == modules[(y + 1) * size + x] &&
                    c == modules[(y + 1) * size + x + 1]
                ) {
                    penalty += 3
                }
            }
        }
        // N3: 1:1:3:1:1 + 4 白块模式（两个方向：10111010000 或 00001011101）
        fun isPattern11(line: (Int) -> Boolean, start: Int): Boolean {
            val a = line(start) && !line(start + 1) && line(start + 2) && line(start + 3) &&
                line(start + 4) && !line(start + 5) && line(start + 6) &&
                !line(start + 7) && !line(start + 8) && !line(start + 9) && !line(start + 10)
            val b = !line(start) && !line(start + 1) && !line(start + 2) && !line(start + 3) &&
                line(start + 4) && !line(start + 5) && line(start + 6) &&
                line(start + 7) && line(start + 8) && !line(start + 9) && line(start + 10)
            return a || b
        }
        for (y in 0 until size) {
            for (x in 0..size - 11) {
                if (isPattern11({ idx -> modules[y * size + idx] }, x)) penalty += 40
            }
        }
        for (x in 0 until size) {
            for (y in 0..size - 11) {
                if (isPattern11({ idx -> modules[idx * size + x] }, y)) penalty += 40
            }
        }
        // N4: 黑块比例偏离 50%
        var darkCount = 0
        for (m in modules) if (m) darkCount++
        val total = size * size
        val k = kotlin.math.abs(darkCount * 20 - total * 10) / total
        penalty += k * 10
        return penalty
    }

    internal fun alignmentCoordinates(version: Int): IntArray {
        if (version == 1) return intArrayOf()
        val count = version / 7 + 2
        val last = 17 + 4 * version - 7
        val step = if (version == 32) 26 else ((last - 6) + (count - 2)) / (count - 1)
        val alignedStep = if (step % 2 == 0) step else step + 1
        val result = IntArray(count)
        result[0] = 6
        var pos = last
        for (i in count - 1 downTo 1) {
            result[i] = pos
            pos -= alignedStep
        }
        return result
    }

    private class BitBuffer {
        private val bits = ArrayList<Boolean>(512)
        val size: Int get() = bits.size
        fun appendBit(bit: Boolean) { bits.add(bit) }
        fun appendBits(value: Int, length: Int) {
            for (i in length - 1 downTo 0) {
                bits.add(((value ushr i) and 1) != 0)
            }
        }
        fun toBooleanList(): List<Boolean> = bits
        fun toByteList(): List<Int> {
            val out = ArrayList<Int>((bits.size + 7) / 8)
            var i = 0
            while (i < bits.size) {
                var b = 0
                for (j in 0 until 8) {
                    b = b shl 1
                    if (i + j < bits.size && bits[i + j]) b = b or 1
                }
                out.add(b)
                i += 8
            }
            return out
        }
    }

    internal object ReedSolomon {
        private val EXP = IntArray(512)
        private val LOG = IntArray(256)

        init {
            var x = 1
            for (i in 0 until 255) {
                EXP[i] = x
                LOG[x] = i
                x = x shl 1
                if (x >= 256) x = x xor 0x11D
            }
            for (i in 255 until 512) {
                EXP[i] = EXP[i - 255]
            }
        }

        fun mul(a: Int, b: Int): Int =
            if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

        fun generatorPoly(degree: Int): IntArray {
            var poly = intArrayOf(1)
            for (i in 0 until degree) {
                val factor = intArrayOf(1, EXP[i])
                val next = IntArray(poly.size + 1)
                for (p in poly.indices) {
                    next[p] = next[p] xor mul(poly[p], factor[0])
                    next[p + 1] = next[p + 1] xor mul(poly[p], factor[1])
                }
                poly = next
            }
            return poly
        }

        fun computeEcc(data: ByteArray, eccLen: Int): ByteArray {
            val gen = generatorPoly(eccLen)
            val msg = IntArray(data.size + eccLen)
            for (i in data.indices) msg[i] = data[i].toInt() and 0xFF
            for (i in data.indices) {
                val coef = msg[i]
                if (coef != 0) {
                    for (j in gen.indices) {
                        msg[i + j] = msg[i + j] xor mul(gen[j], coef)
                    }
                }
            }
            return ByteArray(eccLen) { i -> msg[data.size + i].toByte() }
        }
    }

    private val REMAINDER_BITS = intArrayOf(
        0, 7, 7, 7, 7, 7, 0, 0, 0, 0,
        0, 0, 0, 3, 3, 3, 3, 3, 3, 3,
        4, 4, 4, 4, 4, 4, 4, 3, 3, 3,
        3, 3, 3, 3, 0, 0, 0, 0, 0, 0,
    )

    // 每行：[ecPerBlock, g1Blocks, g1DataBytes, g2Blocks, g2DataBytes]
    private val RS_L = arrayOf(
        intArrayOf(7, 1, 19, 0, 0),
        intArrayOf(10, 1, 34, 0, 0),
        intArrayOf(15, 1, 55, 0, 0),
        intArrayOf(20, 1, 80, 0, 0),
        intArrayOf(26, 1, 108, 0, 0),
        intArrayOf(18, 2, 68, 0, 0),
        intArrayOf(20, 2, 78, 0, 0),
        intArrayOf(24, 2, 97, 0, 0),
        intArrayOf(30, 2, 116, 0, 0),
        intArrayOf(18, 2, 68, 2, 69),
        intArrayOf(20, 4, 81, 0, 0),
        intArrayOf(24, 2, 92, 2, 93),
        intArrayOf(26, 4, 107, 0, 0),
        intArrayOf(30, 3, 115, 1, 116),
        intArrayOf(22, 5, 87, 1, 88),
        intArrayOf(24, 5, 98, 1, 99),
        intArrayOf(28, 1, 107, 5, 108),
        intArrayOf(30, 5, 120, 1, 121),
        intArrayOf(28, 3, 113, 4, 114),
        intArrayOf(28, 3, 107, 5, 108),
        intArrayOf(28, 4, 116, 4, 117),
        intArrayOf(28, 2, 111, 7, 112),
        intArrayOf(30, 4, 121, 5, 122),
        intArrayOf(30, 6, 117, 4, 118),
        intArrayOf(26, 8, 106, 4, 107),
        intArrayOf(28, 10, 114, 2, 115),
        intArrayOf(30, 8, 122, 4, 123),
        intArrayOf(30, 3, 117, 10, 118),
        intArrayOf(30, 7, 116, 7, 117),
        intArrayOf(30, 5, 115, 10, 116),
        intArrayOf(30, 13, 115, 3, 116),
        intArrayOf(30, 17, 115, 0, 0),
        intArrayOf(30, 17, 115, 1, 116),
        intArrayOf(30, 13, 115, 6, 116),
        intArrayOf(30, 12, 121, 7, 122),
        intArrayOf(30, 6, 121, 14, 122),
        intArrayOf(30, 17, 122, 4, 123),
        intArrayOf(30, 4, 122, 18, 123),
        intArrayOf(30, 20, 117, 4, 118),
        intArrayOf(30, 19, 118, 6, 119),
    )

    private val RS_M = arrayOf(
        intArrayOf(10, 1, 16, 0, 0),
        intArrayOf(16, 1, 28, 0, 0),
        intArrayOf(26, 1, 44, 0, 0),
        intArrayOf(18, 2, 32, 0, 0),
        intArrayOf(24, 2, 43, 0, 0),
        intArrayOf(16, 4, 27, 0, 0),
        intArrayOf(18, 4, 31, 0, 0),
        intArrayOf(22, 2, 38, 2, 39),
        intArrayOf(22, 3, 36, 2, 37),
        intArrayOf(26, 4, 43, 1, 44),
        intArrayOf(30, 1, 50, 4, 51),
        intArrayOf(22, 6, 36, 2, 37),
        intArrayOf(22, 8, 37, 1, 38),
        intArrayOf(24, 4, 40, 5, 41),
        intArrayOf(24, 5, 41, 5, 42),
        intArrayOf(28, 7, 45, 3, 46),
        intArrayOf(28, 10, 46, 1, 47),
        intArrayOf(26, 9, 43, 4, 44),
        intArrayOf(26, 3, 44, 11, 45),
        intArrayOf(26, 3, 41, 13, 42),
        intArrayOf(26, 17, 42, 0, 0),
        intArrayOf(28, 17, 46, 0, 0),
        intArrayOf(28, 4, 47, 14, 48),
        intArrayOf(28, 6, 45, 14, 46),
        intArrayOf(28, 8, 47, 13, 48),
        intArrayOf(28, 19, 46, 4, 47),
        intArrayOf(28, 22, 45, 3, 46),
        intArrayOf(28, 3, 45, 23, 46),
        intArrayOf(28, 21, 45, 7, 46),
        intArrayOf(28, 19, 47, 10, 48),
        intArrayOf(28, 2, 46, 29, 47),
        intArrayOf(28, 10, 46, 23, 47),
        intArrayOf(28, 14, 46, 21, 47),
        intArrayOf(28, 14, 46, 23, 47),
        intArrayOf(28, 12, 47, 26, 48),
        intArrayOf(28, 6, 47, 34, 48),
        intArrayOf(28, 29, 46, 14, 47),
        intArrayOf(28, 13, 46, 32, 47),
        intArrayOf(28, 40, 47, 7, 48),
        intArrayOf(28, 18, 47, 31, 48),
    )
}
