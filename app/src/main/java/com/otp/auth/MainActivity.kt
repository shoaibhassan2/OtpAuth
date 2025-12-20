@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)

package com.otp.auth

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.otp.auth.crypto.TotpEngine
import com.otp.auth.data.Account
import com.otp.auth.data.AccountRepository
import com.otp.auth.data.SettingsManager
import com.otp.auth.ui.*
import com.otp.auth.ui.components.LockedScreen  // <--- FIXED: Imported here
import com.otp.auth.ui.components.PasswordDialog // <--- FIXED: Imported here
import com.otp.auth.ui.theme.OtpAuthTheme
import com.otp.auth.utils.BackupHelper
import com.otp.auth.utils.GoogleAuthMigration
import com.otp.auth.utils.GoogleDriveHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.regex.Pattern

class MainActivity : FragmentActivity() {
    private lateinit var settingsManager: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = AccountRepository(this)
        settingsManager = SettingsManager(this)

        setContent {
            val themeMode by settingsManager.themeMode.collectAsState()
            val isPrivacyEnabled by settingsManager.isPrivacyEnabled.collectAsState()
            var isUnlocked by remember { mutableStateOf(!isPrivacyEnabled) }
            
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_START && settingsManager.isPrivacyEnabled.value) {
                        isUnlocked = false
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            LaunchedEffect(isUnlocked, isPrivacyEnabled) {
                if (isPrivacyEnabled && !isUnlocked) {
                    authenticateUser("Unlock App", "Verify identity") { success -> isUnlocked = success }
                } else if (!isPrivacyEnabled) {
                    isUnlocked = true
                }
            }

            OtpAuthTheme(themeMode = themeMode) {
                if (isUnlocked) {
                    OtpApp(repository, settingsManager, ::authenticateUser)
                } else {
                    LockedScreen(onUnlockClick = {
                         authenticateUser("Unlock App", "Verify identity") { success -> isUnlocked = success }
                    })
                }
            }
        }
    }

    private fun authenticateUser(title: String, subtitle: String, onResult: (Boolean) -> Unit) {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onResult(true) }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onResult(false) }
                override fun onAuthenticationFailed() { onResult(false) }
            })
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
        biometricPrompt.authenticate(promptInfo)
    }
}

@Composable
fun OtpApp(
    repository: AccountRepository, 
    settingsManager: SettingsManager,
    authenticator: (String, String, (Boolean) -> Unit) -> Unit
) {
    var accounts by remember { mutableStateOf<List<Account>>(emptyList()) }
    var currentScreen by rememberSaveable { mutableStateOf("home") }
    var progress by remember { mutableFloatStateOf(1f) }
    
    // Google Drive State
    var googleAccountEmail by remember { mutableStateOf<String?>(null) }
    var showGoogleSyncDialog by remember { mutableStateOf(false) }
    var isCloudExporting by remember { mutableStateOf(true) }
    var showCloudPasswordDialog by remember { mutableStateOf(false) }
    
    // Dialog States
    var showExportTypeDialog by remember { mutableStateOf(false) }
    var showImportTypeDialog by remember { mutableStateOf(false) }
    var showFileExportPasswordDialog by remember { mutableStateOf(false) }
    var showFileImportPasswordDialog by remember { mutableStateOf(false) }
    
    var pendingFileUri by remember { mutableStateOf<Uri?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val currentThemeMode by settingsManager.themeMode.collectAsState()
    val isPrivacyEnabled by settingsManager.isPrivacyEnabled.collectAsState()

    val exportFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { pendingFileUri = it; showFileExportPasswordDialog = true }
    }
    val importFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { pendingFileUri = it; showFileImportPasswordDialog = true }
    }

    val googleSignInLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                googleAccountEmail = account.email
                Toast.makeText(context, "Connected: ${account.email}", Toast.LENGTH_SHORT).show()
            } catch (e: ApiException) {
                Toast.makeText(context, "Sign-in failed: ${e.statusCode}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { accounts = repository.loadAccounts() }
        val signedInAccount = GoogleDriveHelper.getSignedInAccount(context)
        if (signedInAccount != null) googleAccountEmail = signedInAccount.email
        while (true) { progress = TotpEngine.getProgress(); delay(100) }
    }

    fun navigateTo(screen: String) { currentScreen = screen }
    BackHandler(enabled = currentScreen != "home") { navigateTo("home") }

    fun addAccount(acc: Account) {
        if (accounts.any { it.secret == acc.secret }) { Toast.makeText(context, "Exists!", Toast.LENGTH_SHORT).show(); return }
        val newList = accounts + acc
        accounts = newList
        scope.launch { repository.saveAccounts(newList) }
        navigateTo("home")
    }
    
    fun addBatchAccounts(newAccounts: List<Account>) {
        var added = 0
        val current = accounts.toMutableList()
        newAccounts.forEach { if (current.none { existing -> existing.secret == it.secret }) { current.add(it); added++ } }
        if (added > 0) {
            accounts = current
            scope.launch { repository.saveAccounts(current) }
            Toast.makeText(context, "Imported $added accounts", Toast.LENGTH_LONG).show()
            navigateTo("home")
        } else {
            Toast.makeText(context, "No new accounts", Toast.LENGTH_LONG).show()
        }
    }

    // --- SECURE DELETION ---
    fun deleteAccount(acc: Account) {
        if (isPrivacyEnabled) {
            authenticator("Confirm Deletion", "Verify identity to delete account") { success ->
                if (success) {
                    val newList = accounts - acc
                    accounts = newList
                    scope.launch { repository.saveAccounts(newList) }
                    Toast.makeText(context, "Account Deleted", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            val newList = accounts - acc
            accounts = newList
            scope.launch { repository.saveAccounts(newList) }
            Toast.makeText(context, "Account Deleted", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(when (currentScreen) { "home" -> "Authenticator"; "settings" -> "Settings"; "about" -> "About"; "export_qr" -> "Export QR"; else -> "Back" }) },
                navigationIcon = { if (currentScreen != "home") IconButton(onClick = { navigateTo("home") }) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = { if (currentScreen == "home") { IconButton(onClick = { navigateTo("settings") }) { Icon(Icons.Default.Settings, "Settings") }; IconButton(onClick = { navigateTo("about") }) { Icon(Icons.Default.Info, "About") } } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer, titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer)
            )
        },
        floatingActionButton = {
            if (currentScreen == "home") {
                Column(horizontalAlignment = Alignment.End) {
                    SmallFloatingActionButton(onClick = { navigateTo("manual") }, containerColor = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.padding(bottom = 16.dp)) { Icon(Icons.Default.Keyboard, "Manual") }
                    FloatingActionButton(onClick = { navigateTo("scan") }) { Icon(Icons.Default.QrCodeScanner, "Scan") }
                }
            }
        }
    ) { padding ->
        AnimatedContent(targetState = currentScreen, label = "ScreenTransition", modifier = Modifier.padding(padding).fillMaxSize()) { targetScreen ->
            when (targetScreen) {
                "home" -> HomeScreen(accounts, progress, ::deleteAccount)
                "scan" -> ScanScreen(onCodeFound = { url -> 
                    if (url.startsWith("otpauth-migration://")) {
                        val migrated = GoogleAuthMigration.parseMigrationUri(url)
                        if (migrated.isNotEmpty()) addBatchAccounts(migrated)
                    } else {
                        val acc = parseQrContent(url, context)
                        if (acc != null) addAccount(acc) else Toast.makeText(context, "Invalid QR", Toast.LENGTH_SHORT).show()
                    }
                })
                "manual" -> ManualEntryScreen(onSave = { i, l, s -> addAccount(Account(issuer = i, label = l, secret = s)) })
                "about" -> AboutScreen()
                "settings" -> SettingsScreen(
                    currentMode = currentThemeMode,
                    isPrivacyEnabled = isPrivacyEnabled,
                    googleAccountEmail = googleAccountEmail,
                    onModeSelected = { settingsManager.setThemeMode(it) },
                    onPrivacyToggle = { settingsManager.setPrivacyEnabled(it) },
                    onExportClick = { showExportTypeDialog = true },
                    onImportClick = { showImportTypeDialog = true },
                    onGoogleConnect = {
                        val signInClient = GoogleDriveHelper.getSignInClient(context)
                        googleSignInLauncher.launch(signInClient.signInIntent)
                    },
                    onGoogleSync = { showGoogleSyncDialog = true },
                    onGoogleSignOut = {
                        val client = GoogleDriveHelper.getSignInClient(context)
                        client.signOut().addOnCompleteListener {
                            googleAccountEmail = null
                            Toast.makeText(context, "Account Unlinked", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                "export_qr" -> ExportScreen(accounts, onClose = { navigateTo("settings") })
            }
        }

        if (showGoogleSyncDialog) {
            AlertDialog(
                onDismissRequest = { showGoogleSyncDialog = false },
                title = { Text("Google Drive Sync") },
                text = { Text("Securely backup or restore using Google Drive (Encrypted).\n\nYou will need to set a password.") },
                confirmButton = {
                    Button(onClick = { 
                        showGoogleSyncDialog = false
                        isCloudExporting = true
                        showCloudPasswordDialog = true
                    }) { Text("Backup (Encrypt)") }
                },
                dismissButton = {
                    OutlinedButton(onClick = { 
                        showGoogleSyncDialog = false
                        isCloudExporting = false
                        showCloudPasswordDialog = true
                    }) { Text("Restore (Decrypt)") }
                }
            )
        }

        // --- CLOUD BACKUP (With Biometric Check) ---
        if (showCloudPasswordDialog) {
            PasswordDialog(
                title = if (isCloudExporting) "Encrypt Backup" else "Decrypt Backup",
                onDismiss = { showCloudPasswordDialog = false },
                onConfirm = { password ->
                    showCloudPasswordDialog = false
                    val account = GoogleDriveHelper.getSignedInAccount(context)
                    if (account != null) {
                        scope.launch(Dispatchers.IO) {
                            try {
                                if (isCloudExporting) {
                                    val exists = GoogleDriveHelper.checkForExistingBackup(context, account)
                                    suspend fun doUpload() {
                                        val stream = ByteArrayOutputStream()
                                        BackupHelper.exportAccounts(accounts, password, stream)
                                        GoogleDriveHelper.uploadBackup(context, account, stream.toByteArray())
                                        withContext(Dispatchers.Main) { Toast.makeText(context, "Encrypted Backup Uploaded!", Toast.LENGTH_LONG).show() }
                                    }
                                    if (exists) {
                                        withContext(Dispatchers.Main) {
                                            authenticator("Overwrite Cloud Backup", "Verify identity to overwrite") { success ->
                                                if (success) { scope.launch(Dispatchers.IO) { doUpload() } }
                                            }
                                        }
                                    } else {
                                        doUpload()
                                    }
                                } else {
                                    val encryptedBytes = GoogleDriveHelper.downloadBackup(context, account)
                                    if (encryptedBytes != null) {
                                        val stream = ByteArrayInputStream(encryptedBytes)
                                        val imported = BackupHelper.importAccounts(password, stream)
                                        withContext(Dispatchers.Main) { addBatchAccounts(imported) }
                                    } else {
                                        withContext(Dispatchers.Main) { Toast.makeText(context, "No backup found", Toast.LENGTH_SHORT).show() }
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) { Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_LONG).show() }
                            }
                        }
                    }
                }
            )
        }

        if (showExportTypeDialog) {
            AlertDialog(
                onDismissRequest = { showExportTypeDialog = false },
                title = { Text("Export Options") },
                text = { Text("How would you like to export your accounts?") },
                confirmButton = { TextButton(onClick = { showExportTypeDialog = false; navigateTo("export_qr") }) { Text("Show QR Code") } },
                dismissButton = { TextButton(onClick = { showExportTypeDialog = false; exportFileLauncher.launch("otp_backup.json") }) { Text("Save to File") } }
            )
        }
        if (showImportTypeDialog) {
            AlertDialog(
                onDismissRequest = { showImportTypeDialog = false },
                title = { Text("Import Options") },
                text = { Text("How would you like to import accounts?") },
                confirmButton = { TextButton(onClick = { showImportTypeDialog = false; navigateTo("scan") }) { Text("Scan QR Code") } },
                dismissButton = { TextButton(onClick = { showImportTypeDialog = false; importFileLauncher.launch("application/json") }) { Text("Read from File") } }
            )
        }

        // --- LOCAL EXPORT (Biometric Always Required) ---
        if (showFileExportPasswordDialog && pendingFileUri != null) {
            PasswordDialog(title = "Encrypt Backup", onDismiss = { showFileExportPasswordDialog = false }, onConfirm = { password ->
                showFileExportPasswordDialog = false
                authenticator("Confirm Export", "Verify identity to save backup") { success ->
                    if (success) {
                        scope.launch(Dispatchers.IO) {
                            try {
                                context.contentResolver.openOutputStream(pendingFileUri!!)?.use { BackupHelper.exportAccounts(accounts, password, it) }
                                withContext(Dispatchers.Main) { Toast.makeText(context, "Export Successful", Toast.LENGTH_LONG).show() }
                            } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show() } }
                        }
                    }
                }
            })
        }
        
        if (showFileImportPasswordDialog && pendingFileUri != null) {
             PasswordDialog(title = "Decrypt Backup", onDismiss = { showFileImportPasswordDialog = false }, onConfirm = { password ->
                showFileImportPasswordDialog = false
                scope.launch(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(pendingFileUri!!)?.use { 
                            val imported = BackupHelper.importAccounts(password, it)
                            withContext(Dispatchers.Main) { addBatchAccounts(imported) }
                        }
                    } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(context, "Wrong Password?", Toast.LENGTH_SHORT).show() } }
                }
            })
        }
    }
}

// Helpers
private fun parseQrContent(url: String, context: android.content.Context): Account? {
    return try {
        if (url.startsWith("otpauth://")) {
            val uri = Uri.parse(url)
            if (uri.host == "totp") {
                val secret = uri.getQueryParameter("secret")
                val issuer = uri.getQueryParameter("issuer") ?: "Unknown"
                val label = uri.path?.trim('/') ?: "Account"
                if (secret != null) return Account(issuer = issuer, label = label, secret = secret)
            }
        }
        if (isValidBase32(url)) return Account(issuer = "QR Code", label = "Imported Account", secret = url)
        extractFromUrlParams(url)
    } catch (e: Exception) { null }
}
private fun isValidBase32(input: String): Boolean {
    val clean = input.trim().uppercase()
    val pattern = Pattern.compile("^[A-Z2-7]+=*$")
    return pattern.matcher(clean).matches() && clean.length in 16..32
}
private fun extractFromUrlParams(url: String): Account? {
    return try {
        val secretRegex = Regex("secret=([A-Z2-7]+)", RegexOption.IGNORE_CASE)
        val secretMatch = secretRegex.find(url)
        if (secretMatch != null) {
            val secret = secretMatch.groupValues[1]
            val issuerRegex = Regex("issuer=([^&]+)", RegexOption.IGNORE_CASE)
            val issuerMatch = issuerRegex.find(url)
            val issuer = issuerMatch?.groupValues?.get(1) ?: "Unknown"
            val labelRegex = Regex("/([^?]+)\\?")
            val labelMatch = labelRegex.find(url)
            val label = labelMatch?.groupValues?.get(1) ?: "Account"
            return Account(issuer = issuer, label = label, secret = secret)
        }
        null
    } catch (e: Exception) { null }
}