package com.otp.auth.crypto

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object TotpEngine {
    private const val ALGORITHM = "HmacSHA1"
    private const val PERIOD = 30L

    fun generateNow(secret: String): String {
        return try {
            val keyBytes = Base32.decode(secret)
            val time = System.currentTimeMillis() / 1000 / PERIOD
            generateTOTP(keyBytes, time)
        } catch (e: Exception) {
            "000000"
        }
    }

    fun getProgress(): Float {
        val time = System.currentTimeMillis() / 1000.0
        val remaining = PERIOD - (time % PERIOD)
        return (remaining / PERIOD).toFloat()
    }

    private fun generateTOTP(key: ByteArray, time: Long): String {
        val msg = ByteBuffer.allocate(8).putLong(time).array()
        val hmac = Mac.getInstance(ALGORITHM)
        hmac.init(SecretKeySpec(key, ALGORITHM))
        val hash = hmac.doFinal(msg)

        val offset = hash[hash.size - 1].toInt() and 0xf
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
                ((hash[offset + 1].toInt() and 0xff) shl 16) or
                ((hash[offset + 2].toInt() and 0xff) shl 8) or
                (hash[offset + 3].toInt() and 0xff)

        val otp = binary % 1_000_000
        return String.format("%06d", otp)
    }
}