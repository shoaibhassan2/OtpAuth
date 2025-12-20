package com.otp.auth.crypto

object Base32 {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val LOOKUP = IntArray(256) { -1 }.apply {
        for (i in ALPHABET.indices) {
            this[ALPHABET[i].code] = i
        }
    }

    /* =======================
       ======= DECODE =========
       ======================= */

    fun decode(encoded: String): ByteArray {
        val clean = encoded
            .trim()
            .replace("-", "")
            .replace(" ", "")
            .uppercase()
            .trimEnd('=')

        if (clean.isEmpty()) return ByteArray(0)

        val out = ByteArray(clean.length * 5 / 8)
        var buffer = 0
        var bitsLeft = 0
        var index = 0

        for (c in clean) {
            val value = if (c.code < 256) LOOKUP[c.code] else -1
            require(value >= 0) { "Invalid Base32 character: $c" }

            buffer = (buffer shl 5) or value
            bitsLeft += 5

            if (bitsLeft >= 8) {
                out[index++] = ((buffer shr (bitsLeft - 8)) and 0xFF).toByte()
                bitsLeft -= 8
            }
        }

        return if (index == out.size) out else out.copyOf(index)
    }

    /* =======================
       ======= ENCODE =========
       ======================= */

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""

        val out = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bitsLeft = 0

        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8

            while (bitsLeft >= 5) {
                out.append(ALPHABET[(buffer shr (bitsLeft - 5)) and 31])
                bitsLeft -= 5
            }
        }

        if (bitsLeft > 0) {
            out.append(ALPHABET[(buffer shl (5 - bitsLeft)) and 31])
        }

        return out.toString()
    }
}
