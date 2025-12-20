package com.otp.auth.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.camera.core.Camera as CameraX
import androidx.camera.core.TorchState
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.AspectRatio
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
class MlKitQrScanner @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private lateinit var previewView: PreviewView
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraX: CameraX? = null  // Renamed to avoid ambiguity
    private var analysisExecutor: ExecutorService? = null
    private var barcodeScanner: BarcodeScanner? = null
    private var qrOverlay: QrOverlay? = null
    private var onQrDetected: ((String, RectF) -> Unit)? = null
    private var isProcessing = false
    private var lastDetectedCode: String? = null
    private var lastDetectionTime: Long = 0
    
    // Auto-zoom properties
    private var targetBarcodeWidth: Float = 0.3f // Target 30% of screen width
    private var currentZoomRatio: Float = 1.0f
    private val zoomSmoothingFactor = 0.2f
    
    // Animation properties
    private var scanLinePosition = 0f
    private var scanLineDirection = 1
    private val scanLineSpeed = 5f
    
    companion object {
        private const val TAG = "MlKitQrScanner"
        private const val DEBOUNCE_DELAY = 2000L // 2 seconds between scans
    }

    init {
        setupScanner()
        setupView()
    }

    private fun setupView() {
        // Create and add PreviewView as a child
        previewView = PreviewView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        addView(previewView)
        
        // Add overlay on top
        qrOverlay = QrOverlay(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
        }
        addView(qrOverlay)
    }

    private fun setupScanner() {
        // Initialize barcode scanner
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_AZTEC
            )
            .enableAllPotentialBarcodes()
            .build()
        
        barcodeScanner = BarcodeScanning.getClient(options)
        analysisExecutor = Executors.newSingleThreadExecutor()
        
        // Start animation
        startScanAnimation()
    }

    fun startCamera(lifecycleOwner: LifecycleOwner, onQrDetected: (String, RectF) -> Unit) {
        this.onQrDetected = onQrDetected
        
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases(lifecycleOwner)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases(lifecycleOwner: LifecycleOwner) {
        val cameraProvider = cameraProvider ?: return

        // Unbind existing use cases
        cameraProvider.unbindAll()

        // Camera selector - choose back camera by default
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        // Preview
        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .setTargetRotation(display.rotation)
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        // Image Analysis for QR detection
        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .setTargetRotation(display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()

        imageAnalysis.setAnalyzer(analysisExecutor ?: Executors.newSingleThreadExecutor()) { imageProxy ->
            processImage(imageProxy)
        }

        try {
            // Bind use cases to camera
            cameraX = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
            
            // Set up camera control callbacks
            setupCameraControls()
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun setupCameraControls() {
        cameraX?.let { cam ->
            // Enable auto-focus (torch is disabled by default)
            cam.cameraControl.enableTorch(false)
            
            // Set up zoom state observer
            cam.cameraInfo.zoomState.observeForever { zoomState ->
                zoomState?.let {
                    currentZoomRatio = it.zoomRatio
                }
            }
        }
    }

    private fun processImage(imageProxy: ImageProxy) {
        if (isProcessing) {
            imageProxy.close()
            return
        }
        
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        
        barcodeScanner?.process(image)
            ?.addOnSuccessListener { barcodes ->
                handleDetectedBarcodes(barcodes, imageProxy)
            }
            ?.addOnFailureListener { e ->
                Log.e(TAG, "Barcode scanning failed", e)
                imageProxy.close()
            }
            ?.addOnCompleteListener {
                // Ensure image is closed
                imageProxy.close()
            }
    }

    private fun handleDetectedBarcodes(barcodes: List<Barcode>, imageProxy: ImageProxy) {
        if (barcodes.isEmpty()) {
            qrOverlay?.clearBoundingBox()
            return
        }

        // Find the largest QR code (most likely the target)
        val primaryBarcode = barcodes.maxByOrNull { 
            it.boundingBox?.width() ?: 0
        } ?: return

        primaryBarcode.rawValue?.let { rawValue ->
            primaryBarcode.boundingBox?.let { boundingBox ->
                // Transform bounding box to view coordinates
                val transformedBox = transformBoundingBox(boundingBox, imageProxy)
                
                // Check if this is a new detection (debounce)
                val now = System.currentTimeMillis()
                if (rawValue != lastDetectedCode || now - lastDetectionTime > DEBOUNCE_DELAY) {
                    lastDetectedCode = rawValue
                    lastDetectionTime = now
                    
                    // Update overlay on UI thread
                    post {
                        qrOverlay?.setBoundingBox(transformedBox)
                        
                        // Auto-zoom on detected QR code
                        autoZoomOnBarcode(transformedBox)
                        
                        // Trigger callback
                        onQrDetected?.invoke(rawValue, transformedBox)
                    }
                } else {
                    // Update overlay but don't trigger callback
                    post {
                        qrOverlay?.setBoundingBox(transformedBox)
                    }
                }
            }
        }
    }

    private fun transformBoundingBox(
        boundingBox: Rect,
        imageProxy: ImageProxy
    ): RectF {
        // Get the transformation matrix from image to view
        val matrix = Matrix()
        
        // Get view dimensions
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        
        // Get image dimensions
        val imageWidth = imageProxy.width.toFloat()
        val imageHeight = imageProxy.height.toFloat()
        
        // Calculate scale factors
        val scaleX = viewWidth / imageWidth
        val scaleY = viewHeight / imageHeight
        val scale = min(scaleX, scaleY)
        
        // Calculate offset to center the image
        val offsetX = (viewWidth - imageWidth * scale) / 2
        val offsetY = (viewHeight - imageHeight * scale) / 2
        
        // Apply transformation
        matrix.setScale(scale, scale)
        matrix.postTranslate(offsetX, offsetY)
        
        // Apply rotation if needed
        when (imageProxy.imageInfo.rotationDegrees) {
            90 -> matrix.postRotate(90f, viewWidth / 2, viewHeight / 2)
            180 -> matrix.postRotate(180f, viewWidth / 2, viewHeight / 2)
            270 -> matrix.postRotate(270f, viewWidth / 2, viewHeight / 2)
        }
        
        // Transform bounding box
        val srcRectF = RectF(boundingBox)
        val dstRectF = RectF()
        matrix.mapRect(dstRectF, srcRectF)
        
        return dstRectF
    }

    private fun autoZoomOnBarcode(boundingBox: RectF) {
        cameraX?.let { cam ->
            val cameraInfo = cam.cameraInfo
            val cameraControl = cam.cameraControl
            
            // Get current zoom state
            val zoomState = cameraInfo.zoomState.value ?: return
            
            // Calculate desired zoom level based on barcode size
            val barcodeWidthPercent = boundingBox.width() / width
            val zoomDelta = targetBarcodeWidth / barcodeWidthPercent
            
            // Clamp zoom ratio to camera's range
            val minZoom = zoomState.minZoomRatio
            val maxZoom = zoomState.maxZoomRatio
            val newZoom = (currentZoomRatio * zoomDelta).coerceIn(minZoom, maxZoom)
            
            // Apply smoothed zoom
            val smoothedZoom = currentZoomRatio + (newZoom - currentZoomRatio) * zoomSmoothingFactor
            
            // Set zoom
            cameraControl.setZoomRatio(smoothedZoom)
        }
    }

    private fun startScanAnimation() {
        // Create a coroutine scope for animation
        val animationScope = CoroutineScope(Dispatchers.Main)
        
        animationScope.launch {
            while (isActive) {
                scanLinePosition += scanLineDirection * scanLineSpeed
                
                // Reverse direction at bounds
                if (scanLinePosition > height || scanLinePosition < 0) {
                    scanLineDirection *= -1
                    scanLinePosition = scanLinePosition.coerceIn(0f, height.toFloat())
                }
                
                qrOverlay?.setScanLinePosition(scanLinePosition)
                qrOverlay?.invalidate()
                
                delay(16) // ~60 FPS
            }
        }
    }

    fun stopCamera() {
        cameraProvider?.unbindAll()
        analysisExecutor?.shutdown()
        barcodeScanner?.close()
        qrOverlay?.clearBoundingBox()
    }

    fun toggleTorch() {
        cameraX?.cameraControl?.let { control ->
            val torchState = cameraX?.cameraInfo?.torchState?.value
            val isTorchOn = torchState == TorchState.ON
            control.enableTorch(!isTorchOn)
        }
    }

    fun switchCamera() {
        // Implementation for front/back camera switching
        // You can extend this based on your needs
    }
}

class QrOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    private val boundingBoxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    
    private val fillPaint = Paint().apply {
        color = Color.argb(30, 0, 255, 0) // Semi-transparent green
        style = Paint.Style.FILL
    }
    
    private val scanLinePaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.FILL
        strokeWidth = 3f
        isAntiAlias = true
        alpha = 200
    }
    
    private val cornerPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        strokeWidth = 8f
        isAntiAlias = true
    }
    
    private var boundingBox: RectF? = null
    private var scanLineY = 0f
    private val cornerLength = 40f
    private val pulsePaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 2f
        alpha = 150
    }
    private var pulseRadius = 0f
    private var pulseAlpha = 255
    
    fun setBoundingBox(box: RectF?) {
        boundingBox = box
        invalidate()
    }
    
    fun setScanLinePosition(y: Float) {
        scanLineY = y
    }
    
    fun clearBoundingBox() {
        boundingBox = null
        invalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        boundingBox?.let { box ->
            // Draw filled background
            canvas.drawRect(box, fillPaint)
            
            // Draw bounding box
            canvas.drawRect(box, boundingBoxPaint)
            
            // Draw animated scan line within the bounding box
            if (scanLineY in box.top..box.bottom) {
                canvas.drawLine(
                    box.left,
                    scanLineY,
                    box.right,
                    scanLineY,
                    scanLinePaint
                )
            }
            
            // Draw corner markers
            drawCorner(canvas, box.left, box.top, 0f) // Top-left
            drawCorner(canvas, box.right, box.top, 90f) // Top-right
            drawCorner(canvas, box.left, box.bottom, 270f) // Bottom-left
            drawCorner(canvas, box.right, box.bottom, 180f) // Bottom-right
            
            // Draw pulse animation
            drawPulseAnimation(canvas, box)
        }
    }
    
    private fun drawCorner(canvas: Canvas, x: Float, y: Float, rotation: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(rotation)
        
        // Draw L-shaped corner
        canvas.drawLine(0f, 0f, cornerLength, 0f, cornerPaint)
        canvas.drawLine(0f, 0f, 0f, cornerLength, cornerPaint)
        
        canvas.restore()
    }
    
    private fun drawPulseAnimation(canvas: Canvas, box: RectF) {
        val centerX = box.centerX()
        val centerY = box.centerY()
        val maxRadius = min(box.width(), box.height()) / 2
        
        pulseRadius += 2f
        pulseAlpha -= 5
        
        if (pulseRadius > maxRadius) {
            pulseRadius = 0f
            pulseAlpha = 255
        }
        
        pulsePaint.alpha = pulseAlpha.coerceIn(0, 255)
        canvas.drawCircle(centerX, centerY, pulseRadius, pulsePaint)
    }
}