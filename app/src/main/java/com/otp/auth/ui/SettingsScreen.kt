package com.otp.auth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.otp.auth.data.AppThemeMode

@Composable
fun SettingsScreen(
    currentMode: AppThemeMode,
    isPrivacyEnabled: Boolean,
    googleAccountEmail: String?,
    onModeSelected: (AppThemeMode) -> Unit,
    onPrivacyToggle: (Boolean) -> Unit,
    onExportClick: () -> Unit,
    onImportClick: () -> Unit,
    onGoogleConnect: () -> Unit,
    onGoogleSync: () -> Unit,
    onGoogleSignOut: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // --- Cloud Backup ---
        SettingsSectionTitle("Cloud Backup", Icons.Default.Cloud)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                if (googleAccountEmail != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Linked Account", style = MaterialTheme.typography.labelMedium)
                            Text(googleAccountEmail, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                        }
                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CAF50))
                    }
                    Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))
                    SettingsActionRow("Sync Now", "Backup/Restore to Drive", Icons.Default.Sync, onGoogleSync)
                    Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))
                    SettingsActionRow("Unlink Account", "Disconnect to change account", Icons.Default.ExitToApp, onGoogleSignOut)
                } else {
                    SettingsActionRow("Link Google Account", "Backup to Google Drive", Icons.Default.AddLink, onGoogleConnect)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- Security ---
        SettingsSectionTitle("Security", Icons.Default.Lock)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Privacy Screen", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Text("Require authentication to open", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = isPrivacyEnabled, onCheckedChange = onPrivacyToggle)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- Appearance ---
        SettingsSectionTitle("Appearance", Icons.Default.DarkMode)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                ThemeOptionRow("System Default", AppThemeMode.SYSTEM, currentMode, onModeSelected)
                ThemeOptionRow("Light Theme", AppThemeMode.LIGHT, currentMode, onModeSelected)
                ThemeOptionRow("Dark Theme", AppThemeMode.DARK, currentMode, onModeSelected)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- Local Backup ---
        SettingsSectionTitle("Local Backup", Icons.Default.SdStorage)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                SettingsActionRow("Export to File", "Save .json backup", Icons.Default.FileUpload, onExportClick)
                Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))
                SettingsActionRow("Import from File", "Restore from .json", Icons.Default.FileDownload, onImportClick)
            }
        }
    }
}

@Composable
fun SettingsSectionTitle(text: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun SettingsActionRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ThemeOptionRow(text: String, mode: AppThemeMode, currentMode: AppThemeMode, onClick: (AppThemeMode) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable { onClick(mode) }.padding(vertical = 12.dp, horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = (mode == currentMode), onClick = null)
        Spacer(modifier = Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}