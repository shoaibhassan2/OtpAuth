package com.otp.auth.utils

import android.net.Uri
import android.util.Base64
import com.otp.auth.crypto.Base32
import com.otp.auth.data.Account
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.net.URLDecoder

// Minimalist Protobuf Reader/Writer for Google Authenticator Migration
object GoogleAuthMigration {

    /* =======================
       ======= IMPORT ========
       ======================= */

    fun parseMigrationUri(uriString: String): List<Account> {
        val uri = Uri.parse(uriString)
        val data = uri.getQueryParameter("data") ?: return emptyList()

        // URL-decode first, then normalize Base64URL to standard Base64
        val urlDecoded = URLDecoder.decode(data, "UTF-8")
        val normalized = base64UrlToBase64(urlDecoded)
        val bytes = Base64.decode(normalized, Base64.DEFAULT)

        return parseProtobuf(bytes)
    }

    private fun parseProtobuf(bytes: ByteArray): List<Account> {
        val accounts = mutableListOf<Account>()
        val input = ByteArrayInputStream(bytes)

        while (input.available() > 0) {
            val tag = readVarInt(input)
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07

            if (fieldNumber == 1 && wireType == 2) {
                val length = readVarInt(input)
                val paramBytes = ByteArray(length)
                input.read(paramBytes)
                parseOtpParameters(paramBytes)?.let { accounts.add(it) }
            } else {
                skipField(input, wireType)
            }
        }
        return accounts
    }

    private fun parseOtpParameters(bytes: ByteArray): Account? {
        val input = ByteArrayInputStream(bytes)
        var secretBytes = ByteArray(0)
        var name = ""
        var issuer = ""

        while (input.available() > 0) {
            val tag = readVarInt(input)
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07

            when (fieldNumber) {
                1 -> {
                    val len = readVarInt(input)
                    secretBytes = ByteArray(len)
                    input.read(secretBytes)
                }
                2 -> {
                    val len = readVarInt(input)
                    name = String(ByteArray(len).also { input.read(it) })
                }
                3 -> {
                    val len = readVarInt(input)
                    issuer = String(ByteArray(len).also { input.read(it) })
                }
                else -> skipField(input, wireType)
            }
        }

        if (secretBytes.isEmpty()) return null

        val secretBase32 = Base32.encode(secretBytes)

        var finalIssuer = issuer
        var finalLabel = name
        if (issuer.isEmpty() && name.contains(":")) {
            val split = name.split(":", limit = 2)
            finalIssuer = split[0]
            finalLabel = split[1]
        }

        return Account(
            issuer = finalIssuer,
            label = finalLabel,
            secret = secretBase32
        )
    }

    /* =======================
       ======= EXPORT ========
       ======================= */

    fun getExportUris(accounts: List<Account>): List<String> {
        val batchSize = 10
        val batches = accounts.chunked(batchSize)
        val uris = mutableListOf<String>()

        batches.forEachIndexed { index, batch ->
            val protoBytes = createBatchProtobuf(batch, index, batches.size)

            // Base64URL encoding without padding
            val base64Url = Base64.encodeToString(
                protoBytes,
                Base64.NO_WRAP or Base64.URL_SAFE
            )

            // URL-encode to fully match Google Authenticator export
            val encodedData = URLEncoder.encode(base64Url, "UTF-8")
            uris.add("otpauth-migration://offline?data=$encodedData")
        }

        return uris
    }

    private fun createBatchProtobuf(
        accounts: List<Account>,
        batchIndex: Int,
        totalBatches: Int
    ): ByteArray {
        val output = ByteArrayOutputStream()

        for (acc in accounts) {
            val accBytes = createAccountProtobuf(acc)
            writeTag(output, 1, 2)
            writeVarInt(output, accBytes.size)
            output.write(accBytes)
        }

        writeTag(output, 2, 0); writeVarInt(output, 1)           // Version
        writeTag(output, 3, 0); writeVarInt(output, totalBatches) // Batch count
        writeTag(output, 4, 0); writeVarInt(output, batchIndex)   // Batch index
        writeTag(output, 5, 0); writeVarInt(output, (Math.random() * 1_000_000).toInt()) // Batch ID

        return output.toByteArray()
    }

    private fun createAccountProtobuf(acc: Account): ByteArray {
        val output = ByteArrayOutputStream()

        try {
            val rawSecret = Base32.decode(acc.secret)
            writeTag(output, 1, 2)
            writeVarInt(output, rawSecret.size)
            output.write(rawSecret)
        } catch (_: Exception) {}

        val nameBytes = acc.label.toByteArray()
        writeTag(output, 2, 2); writeVarInt(output, nameBytes.size); output.write(nameBytes)

        val issuerBytes = acc.issuer.toByteArray()
        writeTag(output, 3, 2); writeVarInt(output, issuerBytes.size); output.write(issuerBytes)

        writeTag(output, 4, 0); writeVarInt(output, 1) // Algorithm SHA1
        writeTag(output, 5, 0); writeVarInt(output, 1) // Digits 6
        writeTag(output, 6, 0); writeVarInt(output, 2) // Type TOTP

        return output.toByteArray()
    }

    /* =======================
       ===== PROTO HELP ======
       ======================= */

    private fun writeTag(out: ByteArrayOutputStream, field: Int, wire: Int) {
        writeVarInt(out, (field shl 3) or wire)
    }

    private fun writeVarInt(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v and 0xFFFFFF80.toInt() != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v)
    }

    private fun readVarInt(input: ByteArrayInputStream): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = input.read()
            if (b == -1) throw RuntimeException("EOF")
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    private fun skipField(input: ByteArrayInputStream, wireType: Int) {
        when (wireType) {
            0 -> readVarInt(input)
            1 -> input.skip(8)
            2 -> input.skip(readVarInt(input).toLong())
            5 -> input.skip(4)
        }
    }

    /* =======================
       ===== UTILITIES =======
       ======================= */

    private fun base64UrlToBase64(input: String): String {
        var s = input.replace('-', '+').replace('_', '/')
        while (s.length % 4 != 0) s += "="
        return s
    }

    private fun encodeBase32(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out = StringBuilder()
        var buffer = 0
        var bitsLeft = 0

        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                out.append(alphabet[(buffer shr (bitsLeft - 5)) and 31])
                bitsLeft -= 5
            }
        }

        if (bitsLeft > 0) {
            out.append(alphabet[(buffer shl (5 - bitsLeft)) and 31])
        }

        return out.toString()
    }
}
