package com.otp.auth.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ManualEntryScreen(onSave: (String, String, String) -> Unit) {
    var issuer by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }

    Column(Modifier.padding(16.dp).fillMaxWidth()) {
        OutlinedTextField(value = issuer, onValueChange = { issuer = it }, label = { Text("Issuer (e.g., Google)") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Account Name") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = secret, onValueChange = { secret = it }, label = { Text("Secret Key") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onSave(issuer, label, secret) },
            modifier = Modifier.fillMaxWidth(),
            enabled = secret.isNotEmpty()
        ) {
            Text("Add Account")
        }
    }
}