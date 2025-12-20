package com.otp.auth.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.otp.auth.data.Account
import com.otp.auth.utils.GoogleAuthMigration

@Composable
fun ExportScreen(
    accounts: List<Account>,
    onClose: () -> Unit
) {
    // 1. Generate URIs (Batched)
    val exportUris = remember(accounts) { GoogleAuthMigration.getExportUris(accounts) }
    var currentIndex by remember { mutableIntStateOf(0) }
    
    // 2. Generate QR Bitmap for current index
    val qrBitmap = remember(currentIndex, exportUris) {
        if (exportUris.isNotEmpty()) generateQrBitmap(exportUris[currentIndex]) else null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Export Accounts",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(32.dp))

        Card(
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(8.dp),
            colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)
        ) {
            Box(Modifier.padding(16.dp)) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "Export QR Code",
                        modifier = Modifier.size(280.dp)
                    )
                } else {
                    Box(Modifier.size(280.dp), contentAlignment = Alignment.Center) {
                        Text("No accounts to export", color = androidx.compose.ui.graphics.Color.Black)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (exportUris.size > 1) {
            Text(
                text = "Part ${currentIndex + 1} of ${exportUris.size}",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = { if (currentIndex > 0) currentIndex-- },
                    enabled = currentIndex > 0
                ) { Text("Previous") }
                
                Button(
                    onClick = { if (currentIndex < exportUris.size - 1) currentIndex++ },
                    enabled = currentIndex < exportUris.size - 1
                ) { Text("Next") }
            }
        } else {
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text("Done")
            }
        }
    }
}

private fun generateQrBitmap(content: String): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, 512, 512)
        val w = bitMatrix.width
        val h = bitMatrix.height
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        for (x in 0 until w) {
            for (y in 0 until h) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}