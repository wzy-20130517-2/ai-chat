package com.aichat.app.core.audio

/**
 * Edge TTS 的 Sec-MS-GEC 反滥用签名（v2）。
 *
 * 微软 Edge「大声朗读」免费端点的鉴权参数。2026-10 起服务端升级到 v2：
 * - `Sec-MS-GEC-Version` 前缀从 `1-` 改成 `2-`
 * - token 从纯 SHA256 变成 `<16位hex>-<64位hex>`（CS64 + SHA256 两个变体）
 * 旧格式现在一律 403。
 *
 * 【重要：这不是标准 SHA256】
 * 初始状态由数据头 7 字节算出，轮常量、schedule 常量、sigma 函数都被改过。
 * 不能图省事用 java.security.MessageDigest 代替 —— 结果完全不同。
 *
 * 【Kotlin 里怎么处理 32 位无符号】
 * 全程用 Long 做运算，每步 `and 0xFFFFFFFFL` 截断。这样做而不是用 Int，
 * 是因为乘法（如 `keyA * FACTOR`）需要 64 位中间结果，用 Int 会溢出。
 * 位运算 XOR/and/or 在 Long 上等价于 32 位版本（高位都是 0）。
 */
object SecMsGec {

    private const val MASK = 0xFFFFFFFFL
    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    /** Windows 文件时间纪元：1601-01-01 到 1970-01-01 的秒数。 */
    private const val WIN_EPOCH = 11644473600L

    private fun u32(v: Long): Long = v and MASK

    private val SHA256_INITIAL = longArrayOf(
        0x6A09E667L, 0xBB67AE85L, 0x3C6EF372L, 0xA54FF53AL,
        0x510E527FL, 0x9B05688CL, 0x1F83D9ABL, 0x5BE0CD19L,
    )

    private val SHA256_STATE = longArrayOf(
        0xFD9FBEA2L, 0x91243CE7L, 0xBE499094L, 0xCD2E1E0AL,
        0x6C006F49L, 0x04BD35ABL, 0xFEB0A7E4L, 0x03EFFBD2L,
    )

    private val SHA256_SCHEDULE = longArrayOf(
        0xEB24BF9EL, 0x1558691FL, 0xA0C09EE2L, 0x0CFA32BAL,
        0xA622EE8CL, 0xD1DD9491L, 0xC0DD25A0L, 0xF9E70AB6L,
        0xF0C31573L, 0x7058CE4EL, 0x7188E606L, 0x00E5AB33L,
        0xD258B060L, 0x72766564L,
    )

    private val SHA256_ROUND_CONSTANTS = longArrayOf(
        0x428A2F98L, 0x71374491L, 0xB5C0FBCFL, 0xE9B5DBA5L,
        0x3956C25BL, 0x59F111F1L, 0x923F82A4L, 0xAB1C5ED5L,
        0xD807AA98L, 0x12835B01L, 0x243185BEL, 0x550C7DC3L,
        0x72BE5D74L, 0x80DEB1FEL, 0x9BDC06A7L, 0xC19BF174L,
        0xE49B69C1L, 0xEFBE4786L, 0x0FC19DC6L, 0x240CA1CCL,
        0x2DE92C6FL, 0x4A7484AAL, 0x5CB0A9DCL, 0x76F988DAL,
        0x983E5152L, 0xA831C66DL, 0xB00327C8L, 0xBF597FC7L,
        0xC6E00BF3L, 0xD5A79147L, 0x06CA6351L, 0x14292967L,
        0x27B70A85L, 0x2E1B2138L, 0x4D2C6DFCL, 0x53380D13L,
        0x650A7354L, 0x766A0ABBL, 0x81C2C92EL, 0x92722C85L,
        0xA2BFE8A1L, 0xA81A664BL, 0xC24B8B70L, 0xC76C51A3L,
        0xD192E819L, 0xD6990624L, 0xF40E3585L, 0x106AA070L,
        0x19A4C116L, 0x1E376C08L, 0x2748774CL, 0x34B0BCB5L,
        0x391C0CB3L, 0x4ED8AA4AL, 0x5B9CCA4FL, 0x682E6FF3L,
        0x748F82EEL, 0x78A5636FL, 0x84C87814L, 0x8CC70208L,
        0x90BEFFFAL, 0xA4506CEBL, 0xBEF9A3F7L, 0xC67178F2L,
    )

    private const val SHA256_ADJUSTMENT = 0xC9DC06A7L
    private const val SHA256_SIGMA0_ADJUSTMENT = 0x059C0B80L
    private const val SHA256_SIGMA1_ADJUSTMENT = 0x000B92C0L

    /** ROUND[0..13] + ADJUSTMENT + ROUND[15..] */
    private val SHA256_BLOCK_CONSTANTS: LongArray = run {
        val out = LongArray(64)
        for (i in 0 until 14) out[i] = SHA256_ROUND_CONSTANTS[i]
        out[14] = SHA256_ADJUSTMENT
        for (i in 15 until 64) out[i] = SHA256_ROUND_CONSTANTS[i]
        out
    }

    private val CS64_COEFFICIENTS = arrayOf(
        longArrayOf(0xE82E91B1L, 0xB32700D0L), longArrayOf(0x44DE24C6L, 0x88DFF51DL),
        longArrayOf(0x3BEB65BCL, 0x29514038L), longArrayOf(0xC89C5933L, 0xB328CBDFL),
        longArrayOf(0x8D1EC211L, 0xD405B4D5L), longArrayOf(0x314EF5D5L, 0x26E55F3CL),
        longArrayOf(0xE92491B1L, 0x607801C6L), longArrayOf(0xF0A5B9D5L, 0x82B2C1FBL),
        longArrayOf(0x06064CF3L, 0xFA51A3DAL), longArrayOf(0xDA79FA60L, 0x207CC464L),
        longArrayOf(0xBED20560L, 0x3C3C74F3L), longArrayOf(0x89F9C9F8L, 0xD2D14B61L),
        longArrayOf(0x35FA38C6L, 0xBB04A2C1L), longArrayOf(0xCB49E3C6L, 0xC5626167L),
    )
    private const val CS64_FACTOR = 0x82893933L
    private const val CS64_ADJUSTMENT0 = 0xF2F90B1AL
    private const val CS64_ADJUSTMENT1 = 0x0B1A0000L

    // ───────────────────── 基础位运算 ─────────────────────

    /** 32 位左旋 16。原实现用 `v * 0x100000001 >> n` 模拟循环移位。 */
    private fun rol16(v: Long): Long {
        val x = u32(v)
        return u32((x shl 16) or (x ushr 16))
    }

    private fun sigma0(v: Long): Long {
        val x = u32(v)
        return ((x ushr 7) xor (x shl 25) xor (x ushr 18) xor (x shl 14) xor (x ushr 3)) and MASK
    }

    private fun sigma1(v: Long): Long {
        val x = u32(v)
        return ((x ushr 17) xor (x shl 15) xor (x ushr 19) xor (x shl 13) xor (x ushr 10)) and MASK
    }

    /** 循环右移：`(v * (2^32+1)) >> n` 的低 32 位。 */
    private fun rotr(v: Long, n: Int): Long {
        val x = u32(v)
        return ((x ushr n) or (x shl (32 - n))) and MASK
    }

    // ───────────────────── 字节工具 ─────────────────────

    private fun beWords(bytes: ByteArray, off: Int, len: Int): LongArray {
        val out = LongArray(len / 4)
        var i = 0
        while (i < out.size) {
            val p = off + i * 4
            out[i] = ((bytes[p].toLong() and 0xFF) shl 24) or
                ((bytes[p + 1].toLong() and 0xFF) shl 16) or
                ((bytes[p + 2].toLong() and 0xFF) shl 8) or
                (bytes[p + 3].toLong() and 0xFF)
            i++
        }
        return out
    }

    private fun wordsToBytesBE(words: LongArray): ByteArray {
        val out = ByteArray(words.size * 4)
        for (i in words.indices) {
            val w = words[i]
            out[i * 4] = ((w ushr 24) and 0xFF).toByte()
            out[i * 4 + 1] = ((w ushr 16) and 0xFF).toByte()
            out[i * 4 + 2] = ((w ushr 8) and 0xFF).toByte()
            out[i * 4 + 3] = (w and 0xFF).toByte()
        }
        return out
    }

    // ───────────────────── SHA-256 变体 ─────────────────────

    private fun expandSchedule(words: MutableList<Long>, firstWord: Int) {
        var i = firstWord
        while (i < 64) {
            val x = words[i - 2]
            val y = words[i - 15]
            val s1 = sigma1ForSchedule(x)
            val s0 = sigma0ForSchedule(y)
            words.add(u32(s1 + words[i - 7] + s0 + words[i - 16]))
            i++
        }
    }

    /** schedule 展开用的 sigma1（不取 u32，由调用方截断）。 */
    private fun sigma1ForSchedule(v: Long): Long {
        val x = u32(v)
        return (x ushr 17) xor (x shl 15) xor (x ushr 19) xor (x shl 13) xor (x ushr 10)
    }

    private fun sigma0ForSchedule(v: Long): Long {
        val x = u32(v)
        return (x ushr 7) xor (x shl 25) xor (x ushr 18) xor (x shl 14) xor (x ushr 3)
    }

    private fun sha256Init(data: ByteArray): LongArray {
        require(data.size >= 7) { "token salt 至少需要 7 字节" }

        val words = MutableList(30) { 0L }
        words[14] = ((data[0].toLong() and 0xFF) shl 16) or
            ((data[1].toLong() and 0xFF) shl 8) or
            (data[2].toLong() and 0xFF)
        words[15] = ((data[3].toLong() and 0xFF) shl 24) or
            ((data[4].toLong() and 0xFF) shl 16) or
            ((data[5].toLong() and 0xFF) shl 8) or
            (data[6].toLong() and 0xFF)

        for (index in 16 until 30) {
            var value = SHA256_SCHEDULE[index - 16]
            if (index - 2 >= 14) {
                value += if (index == 16) {
                    sigma1ForSchedule(words[index - 2]) xor SHA256_SIGMA1_ADJUSTMENT
                } else {
                    sigma1ForSchedule(words[index - 2])
                }
            }
            if (index - 7 >= 14) value += words[index - 7]
            if (index - 15 >= 14) {
                value += if (index == 29) {
                    sigma0ForSchedule(words[index - 15]) xor SHA256_SIGMA0_ADJUSTMENT
                } else {
                    sigma0ForSchedule(words[index - 15])
                }
            }
            words.add(u32(value))
        }

        words.add(
            u32(
                sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14] +
                    SHA256_ADJUSTMENT - SHA256_ROUND_CONSTANTS[14]
            )
        )

        expandSchedule(words, 31)
        val working = sha256Rounds(SHA256_STATE, words, 14)
        return LongArray(8) { u32(SHA256_INITIAL[it] + working[it]) }
    }

    /**
     * 跑 64 轮。firstRound 非 0 表示从 block 中途接着跑。
     *
     * 前半段通用循环、后半段手工展开 —— **不要「优化」成统一循环**。
     * 展开版的运算顺序和中间截断点不同，结果会变。
     */
    private fun sha256Rounds(state: LongArray, words: List<Long>, firstRound: Int = 0): LongArray {
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]

        val constants = if (firstRound == 14) SHA256_BLOCK_CONSTANTS else SHA256_ROUND_CONSTANTS
        val aligned = minOf(64, Math.floorDiv(firstRound + 7, 8) * 8)

        var index = firstRound
        while (index < aligned) {
            val t1 = h +
                (rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)) +
                (g xor (e and (f xor g))) +
                constants[index] + words[index]
            val t2 = (rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)) +
                ((a and b) or (c and (a or b)))
            val newA = u32(t1 + t2)
            h = g; g = f; f = e; e = u32(d + t1); d = c; c = b; b = a; a = newA
            index++
        }

        while (index < 64) {
            // 一轮 = 原实现的一个 index，8 轮一组手工展开
            for (step in 0 until 8) {
                if (index + step >= 64) break
                val idx = index + step
                // 手动轮转：每步相当于 [a..h] 左旋转一位
                val t1 = h +
                    (rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)) +
                    (g xor (e and (f xor g))) +
                    constants[idx] + words[idx]
                val t2 = (rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)) +
                    ((a and b) or (c and (a or b)))
                val newA = u32(t1 + t2)
                h = g; g = f; f = e; e = u32(d + t1); d = c; c = b; b = a; a = newA
            }
            index += 8
        }

        return longArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun sha256Digest(data: ByteArray): ByteArray {
        require(data.size >= 7) { "token salt 至少需要 7 字节" }

        val length = 57 + data.size
        val padLen = (((55 - length) % 64) + 64) % 64

        val paddedSize = (data.size - 7) + 1 + padLen + 8
        val block = ByteArray(paddedSize)
        System.arraycopy(data, 7, block, 0, data.size - 7)
        block[data.size - 7] = 0x80.toByte()

        // 末尾 8 字节：大端写入 length*8 位的 64 位值
        val bitLen = length.toLong() * 8
        for (i in 0 until 8) {
            block[paddedSize - 8 + i] = ((bitLen ushr (56 - i * 8)) and 0xFF).toByte()
        }

        var state = sha256Init(data)
        var off = 0
        while (off < block.size) {
            val words = beWords(block, off, 64).toMutableList()
            expandSchedule(words, 16)
            val working = sha256Rounds(state, words)
            state = LongArray(8) { u32(state[it] + working[it]) }
            off += 64
        }
        return wordsToBytesBE(state)
    }

    // ───────────────────── CS64 ─────────────────────

    private fun cs64Digest(data: ByteArray, digest: ByteArray): LongArray {
        fun readU32LE(b: ByteArray, off: Int): Long =
            (b[off].toLong() and 0xFF) or
                ((b[off + 1].toLong() and 0xFF) shl 8) or
                ((b[off + 2].toLong() and 0xFF) shl 16) or
                ((b[off + 3].toLong() and 0xFF) shl 24)

        val keyA = readU32LE(digest, 0) or 1L
        val keyB = readU32LE(digest, 4) or 1L
        val factors = longArrayOf(u32(keyA * CS64_FACTOR), u32(keyB * CS64_FACTOR))

        val terms = mutableListOf<Long>()
        val offsets = mutableListOf<Long>()
        for (i in CS64_COEFFICIENTS.indices) {
            terms.add(u32(CS64_COEFFICIENTS[i][0] * factors[i % 2]))
            offsets.add(CS64_COEFFICIENTS[i][1])
        }

        // 前面补一个 0 字节，再按小端拆成 32 位字（补齐到 4 字节倍数 + 字数为偶数）
        val withZero = ByteArray(data.size + 1)
        System.arraycopy(data, 0, withZero, 1, data.size)
        val paddedLen = ((withZero.size + 3) / 4) * 4
        val padded = ByteArray(paddedLen)
        System.arraycopy(withZero, 0, padded, 0, withZero.size)

        var leIndex = 0
        var off = 0
        while (off < paddedLen) {
            val word = (padded[off].toLong() and 0xFF) or
                ((padded[off + 1].toLong() and 0xFF) shl 8) or
                ((padded[off + 2].toLong() and 0xFF) shl 16) or
                ((padded[off + 3].toLong() and 0xFF) shl 24)

            val even = leIndex % 2 == 0
            var term = word * (if (even) keyA else keyB)
            var offset = rol16(word) * (if (even) 0xEF0569FBL else 0xC31713DBL)
            if (leIndex == 0) {
                term += CS64_ADJUSTMENT0 * factors[0]
                offset += CS64_ADJUSTMENT1
            }
            terms.add(u32(term))
            offsets.add(u32(offset))

            leIndex++
            off += 4
        }
        if (leIndex % 2 == 1) {
            terms.add(0L)
            offsets.add(0L)
        }

        var swapAcc = 0L
        var swapLast = 0L
        var state = 0L
        var acc = 0L

        var i = 0
        while (i < terms.size) {
            val x = terms[i]
            val y = terms[i + 1]

            var t = rol16(u32(offsets[i] + x))
            var q = u32(t * 0x76F50F34L)
            swapAcc += u32(rol16(q) * 0xC3EFEA97L + q)

            t = rol16(u32(offsets[i + 1] + y))
            q = u32(t * 0x1881CE3CL)
            swapLast = u32(rol16(q) * 0x35BD1EC9L + q)
            swapAcc = u32(swapAcc + swapLast)

            state = u32(state * keyA + x)
            state = u32(rol16(state) * 0xCF98B111L)
            state = u32(rol16(state) * 0x87085B9FL)
            state = u32(rol16(state) * 0x12CEB96DL)
            state = u32(rol16(state) * 0x257E1D83L)
            acc += state

            t = u32(state * keyB + y)
            t = u32(rol16(t) * 0xA27416F5L)
            t = u32(rol16(t) * 0xD38396FFL)
            state = u32(rol16(u32(rol16(t) * 0x7C932B89L)) * 0xBFA49F69L)
            acc = u32(acc + state)

            i += 2
        }

        return longArrayOf(swapLast xor u32(state), u32(swapAcc) xor u32(acc))
    }

    // ───────────────────── 对外接口 ─────────────────────

    /**
     * 生成 Sec-MS-GEC token。
     *
     * @param unixTimestamp 用指定时间代替当前时间（测试用）
     * @return `<16位hex>-<64位hex>`
     */
    fun generate(unixTimestamp: Long? = null): String {
        val nowSec = unixTimestamp ?: (System.currentTimeMillis() / 1000)
        var ticks = nowSec + WIN_EPOCH
        ticks -= ticks % 300
        ticks *= 10_000_000L

        val data = "$ticks$TRUSTED_CLIENT_TOKEN".toByteArray(Charsets.US_ASCII)
        val digest = sha256Digest(data)
        val cs64Words = cs64Digest(data, digest)

        val cs64 = ByteArray(8)
        val w0 = cs64Words[0]
        val w1 = cs64Words[1]
        for (i in 0 until 4) {
            cs64[i] = ((w0 ushr (i * 8)) and 0xFF).toByte()
            cs64[4 + i] = ((w1 ushr (i * 8)) and 0xFF).toByte()
        }

        return cs64.joinToString("") { "%02X".format(it.toInt() and 0xFF) } +
            "-" + digest.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }
}
