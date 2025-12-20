package com.otp.auth.ui

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.otp.auth.crypto.TotpEngine
import com.otp.auth.data.Account

@Composable
fun HomeScreen(accounts: List<Account>, progress: Float, onDelete: (Account) -> Unit) {
    // STATE: Track which account is pending deletion
    var accountToDelete by remember { mutableStateOf<Account?>(null) }

    if (accounts.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No accounts yet.\nTap + to add.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium
            )
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items = accounts, key = { it.id }) { account ->
                // CHANGE: Instead of passing 'onDelete' directly, we pass a lambda
                // that sets the 'accountToDelete' state.
                AccountCard(
                    account = account, 
                    progress = progress, 
                    onDelete = { accountToDelete = it }
                )
            }
        }
    }

    // --- DELETE CONFIRMATION DIALOG ---
    if (accountToDelete != null) {
        AlertDialog(
            onDismissRequest = { accountToDelete = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("Delete Account") },
            text = { 
                Text("Are you sure you want to delete \"${accountToDelete?.label}\"?\n\nThis action cannot be undone.") 
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Perform the actual deletion here
                        accountToDelete?.let { onDelete(it) }
                        accountToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { accountToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun AccountCard(account: Account, progress: Float, onDelete: (Account) -> Unit) {
    var otpCode by remember { mutableStateOf("------") }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    
    // Touch Animation State
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.96f else 1f, label = "pressScale")

    // Update OTP
    LaunchedEffect(progress) {
        val newCode = TotpEngine.generateNow(account.secret)
        if (newCode != otpCode) otpCode = newCode
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale) // Apply press animation
            .clickable(
                interactionSource = interactionSource,
                indication = null // Disable default ripple to use ours or custom
            ) {
                clipboardManager.setText(AnnotatedString(otpCode))
                Toast.makeText(context, "Copied code", Toast.LENGTH_SHORT).show()
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    account.issuer,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    account.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))

                // ANIMATION: Digit Vertical Slide
                AnimatedContent(
                    targetState = otpCode,
                    transitionSpec = {
                        slideInVertically { height -> height } + fadeIn() togetherWith
                                slideOutVertically { height -> -height } + fadeOut()
                    },
                    label = "OtpDigitAnim"
                ) { code ->
                    Text(
                        text = "${code.take(3)} ${code.takeLast(3)}",
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp
                        ),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            
            // Actions Row
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Circular Timer
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(44.dp)) {
                    val color by animateColorAsState(
                        targetValue = when {
                            progress < 0.15f -> Color(0xFFFF5252) // Critical Red
                            progress < 0.4f -> Color(0xFFFFD740) // Warning Yellow
                            else -> MaterialTheme.colorScheme.primary // Calm Blue
                        },
                        label = "timerColor"
                    )
                    
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // Background Track
                        drawArc(
                            color = Color.LightGray.copy(alpha = 0.3f),
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = Stroke(width = 4.dp.toPx())
                        )
                        // Progress Arc
                        drawArc(
                            color = color,
                            startAngle = -90f,
                            sweepAngle = progress * 360f,
                            useCenter = false,
                            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                    Text(
                        "${(progress * 30).toInt()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Spacer(Modifier.width(8.dp))
                
                // Delete Button
                IconButton(onClick = { onDelete(account) }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}