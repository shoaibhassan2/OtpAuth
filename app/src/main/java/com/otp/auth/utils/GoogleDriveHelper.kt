package com.otp.auth.utils

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Collections

object GoogleDriveHelper {
    private const val BACKUP_FILE_NAME = "otp_auth_backup.bin"
    private const val FOLDER_ID = "appDataFolder"

    fun getSignInClient(context: Context): GoogleSignInClient {
        val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_APPDATA))
            .build()
        return GoogleSignIn.getClient(context, signInOptions)
    }

    fun getSignedInAccount(context: Context): GoogleSignInAccount? {
        return GoogleSignIn.getLastSignedInAccount(context)
    }

    private fun getDriveService(context: Context, account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(
            context, Collections.singleton(DriveScopes.DRIVE_APPDATA)
        )
        credential.selectedAccount = account.account

        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("OtpAuth").build()
    }

    // --- CHECK EXISTS ---
    suspend fun checkForExistingBackup(context: Context, account: GoogleSignInAccount): Boolean = withContext(Dispatchers.IO) {
        val service = getDriveService(context, account)
        val fileList = service.files().list()
            .setSpaces(FOLDER_ID)
            .setQ("name = '$BACKUP_FILE_NAME' and trashed = false")
            .setFields("files(id)")
            .execute()
        return@withContext fileList.files.isNotEmpty()
    }
    // --------------------

    suspend fun uploadBackup(context: Context, account: GoogleSignInAccount, encryptedData: ByteArray) = withContext(Dispatchers.IO) {
        val service = getDriveService(context, account)

        val fileList = service.files().list()
            .setSpaces(FOLDER_ID)
            .setQ("name = '$BACKUP_FILE_NAME' and trashed = false")
            .setFields("files(id, name)")
            .execute()

        val fileContent = ByteArrayContent("application/octet-stream", encryptedData)

        if (fileList.files.isEmpty()) {
            val metadata = com.google.api.services.drive.model.File()
                .setName(BACKUP_FILE_NAME)
                .setParents(Collections.singletonList(FOLDER_ID))
            service.files().create(metadata, fileContent).execute()
        } else {
            val fileId = fileList.files[0].id
            service.files().update(fileId, null, fileContent).execute()
        }
    }

    suspend fun downloadBackup(context: Context, account: GoogleSignInAccount): ByteArray? = withContext(Dispatchers.IO) {
        val service = getDriveService(context, account)

        val fileList = service.files().list()
            .setSpaces(FOLDER_ID)
            .setQ("name = '$BACKUP_FILE_NAME' and trashed = false")
            .setFields("files(id)")
            .execute()

        if (fileList.files.isEmpty()) return@withContext null

        val fileId = fileList.files[0].id
        val outputStream = ByteArrayOutputStream()
        service.files().get(fileId).executeMediaAndDownloadTo(outputStream)
        return@withContext outputStream.toByteArray()
    }
}