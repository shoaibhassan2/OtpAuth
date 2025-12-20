package com.otp.auth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun AboutScreen() {
    Column(
        Modifier.fillMaxSize().padding(16.dp).background(MaterialTheme.colorScheme.background)
    ) {
        Text("About Developer", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        InfoItem("Name", "Shoaib Hassan")
        InfoItem("Role", "Systems & Security Software Engineer")
        Spacer(Modifier.height(16.dp))
        Text("Professional Summary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Security-focused developer with strong experience in low-level systems programming, reverse engineering, and secure application design.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        Divider()
        Spacer(Modifier.height(8.dp))
        Text("Disclaimer: Independent app, not affiliated with Google.", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
    }
}

@Composable
fun InfoItem(label: String, value: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text("$label: ", fontWeight = FontWeight.Bold)
        Text(value)
    }
}