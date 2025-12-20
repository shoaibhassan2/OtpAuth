package com.otp.auth.utils

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.otp.auth.data.Account
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object BackupHelper {
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private const val KEY_SIZE = 256
    private const val ITERATIONS = 10000

    // Export accounts to a secure file
    fun exportAccounts(accounts: List<Account>, password: String, outputStream: OutputStream) {
        val salt = ByteArray(SALT_SIZE).apply { SecureRandom().nextBytes(this) }
        val secretKey = deriveKey(password, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        
        val json = Gson().toJson(accounts)
        val encryptedData = cipher.doFinal(json.toByteArray(Charsets.UTF_8))

        // File Format: [Salt (16)] [IV (12)] [Encrypted Data]
        outputStream.write(salt)
        outputStream.write(iv)
        outputStream.write(encryptedData)
        outputStream.flush()
        outputStream.close()
    }

    // Import accounts from a secure file
    fun importAccounts(password: String, inputStream: InputStream): List<Account> {
        val bytes = inputStream.readBytes()
        inputStream.close()

        if (bytes.size < SALT_SIZE + IV_SIZE) throw IllegalArgumentException("Invalid file format")

        val salt = bytes.copyOfRange(0, SALT_SIZE)
        val iv = bytes.copyOfRange(SALT_SIZE, SALT_SIZE + IV_SIZE)
        val encryptedData = bytes.copyOfRange(SALT_SIZE + IV_SIZE, bytes.size)

        val secretKey = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        val decryptedJson = String(cipher.doFinal(encryptedData), Charsets.UTF_8)
        val type = object : TypeToken<List<Account>>() {}.type
        return Gson().fromJson(decryptedJson, type)
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_SIZE)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }
}