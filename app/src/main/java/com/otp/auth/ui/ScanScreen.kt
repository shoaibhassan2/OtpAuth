package com.otp.auth.ui

import android.Manifest
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Log
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
fun ScanScreen(onCodeFound: (String) -> Unit) {
    var hasPermission by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var torchEnabled by remember { mutableStateOf(false) }
    var qrBoundingBox by remember { mutableStateOf<RectF?>(null) }
    var scanLineY by remember { mutableFloatStateOf(0f) }
    
    // Camera State to control Torch
    var camera by remember { mutableStateOf<Camera?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var barcodeScanner by remember { mutableStateOf<BarcodeScanner?>(null) }
    var analysisExecutor by remember { mutableStateOf<ExecutorService?>(null) }
    
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            cameraProvider?.unbindAll()
            analysisExecutor?.shutdown()
            barcodeScanner?.close()
        }
    }

    // Initialize
    LaunchedEffect(Unit) {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAllPotentialBarcodes()
            .build()
        
        barcodeScanner = BarcodeScanning.getClient(options)
        analysisExecutor = Executors.newSingleThreadExecutor()
        
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // Animation Loop
    LaunchedEffect(Unit) {
        while (true) {
            scanLineY += 0.01f // Slower, smoother animation
            if (scanLineY > 1f) scanLineY = 0f
            delay(16)
        }
    }

    // FIX: Torch Control Logic
    LaunchedEffect(torchEnabled, camera) {
        try {
            camera?.cameraControl?.enableTorch(torchEnabled)
        } catch (e: Exception) {
            Log.e("ScanScreen", "Failed to toggle torch", e)
        }
    }

    if (hasPermission) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Camera Preview
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        // FILL_CENTER crops the image to fill screen. 
                        // Coordinate math must match this.
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        cameraProvider = cameraProviderFuture.get()
                        
                        // Setup Camera and capture the Camera object
                        camera = setupCamera(
                            previewView, 
                            lifecycleOwner, 
                            context, 
                            barcodeScanner!!,
                            analysisExecutor!!, 
                            onBarcodeDetected = { barcode, rect ->
                                qrBoundingBox = rect
                                if (!isProcessing && barcode.startsWith("otpauth")) {
                                    isProcessing = true
                                    onCodeFound(barcode)
                                    // Debounce
                                    CoroutineScope(Dispatchers.Main).launch {
                                        delay(2000)
                                        isProcessing = false
                                    }
                                }
                            }
                        )
                    }, ContextCompat.getMainExecutor(ctx))
                    
                    previewView
                }
            )
            
            // QR Overlay
            Canvas(modifier = Modifier.fillMaxSize()) {
                qrBoundingBox?.let { box ->
                    // Draw green bounding box
                    drawRect(
                        color = Color.Green.copy(alpha = 0.3f),
                        topLeft = Offset(box.left, box.top),
                        size = Size(box.width(), box.height()),
                        style = Stroke(width = 3.dp.toPx())
                    )
                    
                    // Animated Scan Line
                    val lineY = box.top + (box.height() * scanLineY)
                    drawLine(
                        color = Color.Cyan,
                        start = Offset(box.left, lineY),
                        end = Offset(box.right, lineY),
                        strokeWidth = 2.dp.toPx()
                    )
                    
                    // Draw Corners
                    val cornerLen = 20.dp.toPx()
                    val stroke = 4.dp.toPx()
                    val color = Color.Green

                    // Top Left
                    drawLine(color, Offset(box.left, box.top), Offset(box.left + cornerLen, box.top), stroke)
                    drawLine(color, Offset(box.left, box.top), Offset(box.left, box.top + cornerLen), stroke)
                    
                    // Top Right
                    drawLine(color, Offset(box.right, box.top), Offset(box.right - cornerLen, box.top), stroke)
                    drawLine(color, Offset(box.right, box.top), Offset(box.right, box.top + cornerLen), stroke)

                    // Bottom Left
                    drawLine(color, Offset(box.left, box.bottom), Offset(box.left + cornerLen, box.bottom), stroke)
                    drawLine(color, Offset(box.left, box.bottom), Offset(box.left, box.bottom - cornerLen), stroke)

                    // Bottom Right
                    drawLine(color, Offset(box.right, box.bottom), Offset(box.right - cornerLen, box.bottom), stroke)
                    drawLine(color, Offset(box.right, box.bottom), Offset(box.right, box.bottom - cornerLen), stroke)
                }
            }
            
            // UI Controls
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Status Badge
                    Card(colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.6f))) {
                        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(12.dp).background(Color.Green, MaterialTheme.shapes.small))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Scanning...", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    
                    // Torch Toggle
                    IconButton(
                        onClick = { torchEnabled = !torchEnabled },
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.medium)
                    ) {
                        Icon(
                            imageVector = if (torchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = "Toggle Torch",
                            tint = Color.White
                        )
                    }
                }
                
                // Bottom Guidance
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Align QR code within frame",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        "Supports Google Authenticator QR codes",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Camera permission required", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant Permission")
                }
            }
        }
    }
}

// FIX: Return Camera object to allow Torch control
private fun setupCamera(
    previewView: PreviewView,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    context: android.content.Context,
    barcodeScanner: BarcodeScanner,
    analysisExecutor: ExecutorService,
    onBarcodeDetected: (String, RectF) -> Unit
): Camera? {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    val cameraProvider = cameraProviderFuture.get()
    
    val cameraSelector = CameraSelector.Builder()
        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
        .build()
    
    val preview = Preview.Builder()
        .setTargetAspectRatio(AspectRatio.RATIO_16_9)
        .build()
        .also { it.setSurfaceProvider(previewView.surfaceProvider) }
    
    val imageAnalysis = ImageAnalysis.Builder()
        .setTargetAspectRatio(AspectRatio.RATIO_16_9)
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
    
    imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
        val mediaImage = imageProxy.image ?: return@setAnalyzer
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        
        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (barcodes.isNotEmpty()) {
                    val barcode = barcodes[0]
                    barcode.rawValue?.let { value ->
                        barcode.boundingBox?.let { box ->
                            // FIX: Coordinate Transformation
                            val matrix = Matrix()
                            val viewWidth = previewView.width.toFloat()
                            val viewHeight = previewView.height.toFloat()
                            
                            // ImageProxy dimensions
                            val rawW = imageProxy.width.toFloat()
                            val rawH = imageProxy.height.toFloat()
                            
                            // Rotation handling: If 90 or 270, swap W/H
                            val rotation = imageProxy.imageInfo.rotationDegrees
                            val (imgW, imgH) = if (rotation == 90 || rotation == 270) {
                                rawH to rawW
                            } else {
                                rawW to rawH
                            }
                            
                            // FIX: Use MAX to emulate CENTER_CROP / FILL_CENTER
                            val scale = max(viewWidth / imgW, viewHeight / imgH)
                            
                            val offsetX = (viewWidth - imgW * scale) / 2f
                            val offsetY = (viewHeight - imgH * scale) / 2f
                            
                            matrix.setScale(scale, scale)
                            matrix.postTranslate(offsetX, offsetY)
                            
                            val srcRectF = RectF(box)
                            val dstRectF = RectF()
                            matrix.mapRect(dstRectF, srcRectF)
                            
                            onBarcodeDetected(value, dstRectF)
                        }
                    }
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }
    
    return try {
        cameraProvider.unbindAll()
        // Return the Camera object
        cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
    } catch (e: Exception) {
        Log.e("CameraSetup", "Failed to bind camera", e)
        null
    }
}