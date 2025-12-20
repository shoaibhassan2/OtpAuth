package com.otp.auth.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AccountRepository(context: Context) {
    private val gson = Gson()
    private val sharedPreferences: SharedPreferences

    init {
        // 1. Create the Master Key (Stored in Android Keystore)
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        // 2. Open Encrypted Preferences
        // This looks like normal SharedPreferences but encrypts everything automatically.
        sharedPreferences = EncryptedSharedPreferences.create(
            context,
            "secure_otp_accounts", // File name
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    suspend fun saveAccounts(accounts: List<Account>) = withContext(Dispatchers.IO) {
        try {
            // Convert List -> JSON
            val json = gson.toJson(accounts)
            
            // Save (Encryption happens automatically)
            sharedPreferences.edit()
                .putString("accounts_data", json)
                .commit() // Commit ensures it writes to disk immediately
                
            Log.d("AccountRepo", "Saved ${accounts.size} accounts securely.")
        } catch (e: Exception) {
            Log.e("AccountRepo", "Save Failed", e)
        }
    }

    suspend fun loadAccounts(): List<Account> = withContext(Dispatchers.IO) {
        try {
            // Load (Decryption happens automatically)
            val json = sharedPreferences.getString("accounts_data", null)
            
            if (json.isNullOrEmpty()) {
                return@withContext emptyList()
            }

            // Convert JSON -> List
            val type = object : TypeToken<List<Account>>() {}.type
            val list: List<Account> = gson.fromJson(json, type) ?: emptyList()
            
            Log.d("AccountRepo", "Loaded ${list.size} accounts.")
            return@withContext list
        } catch (e: Exception) {
            Log.e("AccountRepo", "Load Failed", e)
            return@withContext emptyList()
        }
    }
}