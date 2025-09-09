package com.abdapps.camstamp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.util.TypedValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview as CameraXPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
// import androidx.core.location.LocationManagerCompat.getCurrentLocation // Removed to resolve conflict
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.abdapps.camstamp.ui.theme.CamStampTheme
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

suspend fun stampImageWithDetails(
    photoFile: File,
    customText: String,
    latitude: Double?,
    longitude: Double?,
    context: Context
): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val options = BitmapFactory.Options().apply { inMutable = true }
            val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath, options)
            if (bitmap == null) {
                Log.e("StampUtils", "Failed to decode bitmap from file.")
                return@withContext false
            }
            val canvas = Canvas(bitmap)
            val resources = context.resources
            val textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
            val textPaint = Paint().apply {
                color = android.graphics.Color.YELLOW
                this.textSize = textSizePx
                isAntiAlias = true
                textAlign = Paint.Align.RIGHT // Changed to RIGHT
            }
            val backgroundPaint = Paint().apply {
                color = android.graphics.Color.argb(128, 0, 0, 0) // Semi-transparent black
                style = Paint.Style.FILL
            }
            val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val dateTimeString = dateTimeFormat.format(Date())
            
            val lines = mutableListOf<String>()
            if (customText.isNotBlank()) {
                customText.split('\n').forEach { line ->
                    if (line.isNotBlank()) {
                        lines.add("Texto: $line")
                    }
                }
            }
            lines.add("Fecha: $dateTimeString")
            if (latitude != null && longitude != null) {
                lines.add(String.format(Locale.US, "Lat: %.4f, Lon: %.4f", latitude, longitude))
            }

            if (lines.isEmpty()) return@withContext true

            val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics)
            var yPos = bitmap.height - padding - (lines.size * (textSizePx + padding)) // Initial Y pos from bottom

            val textX = bitmap.width - padding - (padding /2) // X position for right alignment

            for (line in lines.reversed()) { // Draw from bottom up
                val textBounds = Rect()
                textPaint.getTextBounds(line, 0, line.length, textBounds)
                
                val bgTop = yPos - textSizePx + textPaint.ascent() - padding / 2
                val bgBottom = yPos + textPaint.descent() + padding / 2
                val bgRight = bitmap.width - padding
                val bgLeft = bgRight - textBounds.width() - padding // Adjust for right alignment
                
                val backgroundRect = RectF(bgLeft, bgTop, bgRight, bgBottom)
                canvas.drawRect(backgroundRect, backgroundPaint)
                canvas.drawText(line, textX, yPos, textPaint)
                
                yPos -= (textSizePx + padding) // Move Y position up for the next line
            }
            
            FileOutputStream(photoFile).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out) }
            Log.d("StampUtils", "Image stamped and saved successfully.")
            true
        } catch (e: Exception) {
            Log.e("StampUtils", "Error stamping image: ${e.message}", e)
            false
        }
    }
}

@Composable
fun CameraScreen(modifier: Modifier = Modifier) {
    val localContext = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember { mutableStateOf(false) }
    var hasLocationPermission by remember { mutableStateOf(false) }
    var latitude by remember { mutableStateOf<Double?>(null) }
    var longitude by remember { mutableStateOf<Double?>(null) }
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(localContext) }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(localContext) }
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }
    var camera: Camera? by remember { mutableStateOf(null) }
    var cameraSelector by remember { mutableStateOf(CameraSelector.DEFAULT_BACK_CAMERA) }
    var linearZoom by remember { mutableFloatStateOf(0f) }
    val zoomStateValue = camera?.cameraInfo?.zoomState?.observeAsState()?.value
    var showCustomTextDialog by remember { mutableStateOf(false) }
    var currentCustomText by remember { mutableStateOf("") }
    var textFieldValue by remember { mutableStateOf("") }
    var showFlashEffect by remember { mutableStateOf(false) }
    val previewView = remember { PreviewView(localContext).apply { layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT) } }
    var lastPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var thumbnailImageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    var showFullScreenImageDialog by remember { mutableStateOf(false) }
    var imageToShowInDialog by remember { mutableStateOf<Uri?>(null) }
    var fullScreenImageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    val getCurrentLocation: () -> Unit = {
        if (hasLocationPermission) {
            try {
                fusedLocationClient.lastLocation
                    .addOnSuccessListener { location ->
                        if (location != null) {
                            latitude = location.latitude
                            longitude = location.longitude
                        } else {
                            Log.d("CameraScreen", "FusedLocation: Last location is null")
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("CameraScreen", "FusedLocation: Failed to get location: ${e.message}", e)
                    }
            } catch (e: SecurityException) {
                Log.e("CameraScreen", "FusedLocation: Location permission not granted for lastLocation call (SecurityException): ${e.message}", e)
            }
        } else {
            Log.d("CameraScreen", "getCurrentLocation called but hasLocationPermission is false.")
        }
    }

    LaunchedEffect(cameraProviderFuture, lifecycleOwner, cameraSelector, previewView) {
        try {
            val CProvider = cameraProviderFuture.await()
            CProvider.unbindAll()
            val cameraXPreview = CameraXPreview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder().build()
            camera = CProvider.bindToLifecycle(lifecycleOwner, cameraSelector, cameraXPreview, imageCapture)
            camera?.cameraInfo?.zoomState?.value?.linearZoom?.let { linearZoom = it }
        } catch (exc: Exception) { Log.e("CameraScreen", "Use case binding failed: ${exc.message}", exc) }
    }

    LaunchedEffect(lastPhotoUri) {
        if (lastPhotoUri != null) {
            try {
                thumbnailImageBitmap = withContext(Dispatchers.IO) {
                    localContext.contentResolver.openInputStream(lastPhotoUri!!)?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
                }
            } catch (e: Exception) {
                Log.e("CameraScreen", "Error loading thumbnail: ${e.message}", e)
                thumbnailImageBitmap = null
            }
        } else { thumbnailImageBitmap = null }
    }
    
    LaunchedEffect(imageToShowInDialog) {
        if (imageToShowInDialog != null) {
            try {
                fullScreenImageBitmap = withContext(Dispatchers.IO) {
                    localContext.contentResolver.openInputStream(imageToShowInDialog!!)?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
                }
            } catch (e: Exception) {
                Log.e("CameraScreen", "Error loading full screen image: ${e.message}", e)
                fullScreenImageBitmap = null
                showFullScreenImageDialog = false
            }
        } else {
            fullScreenImageBitmap = null
        }
    }

    LaunchedEffect(zoomStateValue?.linearZoom) { zoomStateValue?.linearZoom?.let { if (it != linearZoom) linearZoom = it } }
    LaunchedEffect(showFlashEffect) { if (showFlashEffect) { delay(150L); showFlashEffect = false } }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> hasCameraPermission = granted }
    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineLocationGranted || coarseLocationGranted) {
            hasLocationPermission = true
            getCurrentLocation()
        } else {
            Log.d("CameraScreen", "Location permission denied by user through dialog.")
        }
    }

    LaunchedEffect(Unit) {
        // Check and request camera permission
        if (ContextCompat.checkSelfPermission(localContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            hasCameraPermission = true // Set to true if already granted
        }

        // Check and request location permissions
        if (ContextCompat.checkSelfPermission(localContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(localContext, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else {
            hasLocationPermission = true
            getCurrentLocation()
        }
    }
    
    fun toggleCamera() { cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA; linearZoom = 0f }
    
    fun takePhoto(cameraExecutor: Executor) {
        val imageCaptureInstance = imageCapture ?: return
        showFlashEffect = true
        getCurrentLocation()
        val photoFile = File(localContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "${SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis())}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        imageCaptureInstance.takePicture(outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onError(exc: ImageCaptureException) { Log.e("CameraScreen", "Photo capture failed: ${exc.message}", exc) }
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val savedUri = output.savedUri
                Log.d("CameraScreen", "Photo capture succeeded: $savedUri")
                lastPhotoUri = savedUri
                lifecycleOwner.lifecycleScope.launch {
                    val stamped = stampImageWithDetails(photoFile, currentCustomText, latitude, longitude, localContext)
                    if (stamped && savedUri != null) {
                        lastPhotoUri = null
                        lastPhotoUri = Uri.fromFile(photoFile)
                    } else if (savedUri != null) {
                        lastPhotoUri = savedUri
                    }
                }
            }
        })
    }

    if (showCustomTextDialog) {
        AlertDialog(
            onDismissRequest = { showCustomTextDialog = false },
            title = { Text("Enter Custom Text") },
            text = { TextField(value = textFieldValue, onValueChange = { textFieldValue = it }, label = { Text("Text to stamp") }) },
            confirmButton = { Button(onClick = { currentCustomText = textFieldValue; showCustomTextDialog = false }) { Text("Save") } },
            dismissButton = { Button(onClick = { showCustomTextDialog = false }) { Text("Cancel") } }
        )
    }

    if (showFullScreenImageDialog && fullScreenImageBitmap != null) {
        Dialog(
            onDismissRequest = { showFullScreenImageDialog = false; imageToShowInDialog = null },
            properties = DialogProperties(usePlatformDefaultWidth = false) 
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ComposeColor.Black.copy(alpha = 0.8f))
                    .clickable { showFullScreenImageDialog = false; imageToShowInDialog = null },
                contentAlignment = Alignment.Center
            ) {
                Image(bitmap = fullScreenImageBitmap!!, contentDescription = "Full screen image", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().background(ComposeColor.Black)) {
        Box(modifier = Modifier.fillMaxWidth().height(100.dp).background(ComposeColor.Black.copy(alpha = 0.5f))) { 
            IconButton(onClick = { textFieldValue = currentCustomText; showCustomTextDialog = true }, modifier = Modifier.align(Alignment.CenterStart).padding(16.dp)) {
                Icon(imageVector = Icons.Filled.Edit, contentDescription = "Custom Text", tint = ComposeColor.White)
            }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) { 
            if (hasCameraPermission) AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            if (showFlashEffect) Box(modifier = Modifier.fillMaxSize().background(ComposeColor.White))
            if (!hasCameraPermission && !showFlashEffect) Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) { Text("Camera permission is required to use this feature.", color = ComposeColor.White) }
        }
        Box(modifier = Modifier.fillMaxWidth().height(150.dp).background(ComposeColor.Black.copy(alpha = 0.5f))) { 
            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Box(modifier = Modifier.fillMaxHeight().width(60.dp), contentAlignment = Alignment.Center) { 
                    Slider(value = linearZoom, onValueChange = { linearZoom = it; camera?.cameraControl?.setLinearZoom(it) }, valueRange = 0f..1f, modifier = Modifier.graphicsLayer(rotationZ = -90f).width(120.dp).height(50.dp))
                }
                IconButton(onClick = { 
                    if (hasCameraPermission && hasLocationPermission) { 
                        takePhoto(ContextCompat.getMainExecutor(localContext)) 
                    } else { 
                        if(!hasCameraPermission) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        if(!hasLocationPermission) locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    } 
                }, modifier = Modifier.size(72.dp)) { 
                    Icon(imageVector = Icons.Filled.PhotoCamera, contentDescription = "Take Photo", tint = ComposeColor.White, modifier = Modifier.size(64.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) { 
                    IconButton(onClick = {
                        if (lastPhotoUri != null) {
                            imageToShowInDialog = lastPhotoUri
                            showFullScreenImageDialog = true
                        }
                    }) {
                        if (thumbnailImageBitmap != null) Image(bitmap = thumbnailImageBitmap!!, contentDescription = "Last photo thumbnail", modifier = Modifier.size(40.dp), contentScale = ContentScale.Crop)
                        else Icon(imageVector = Icons.Filled.PhotoLibrary, contentDescription = "Image Preview", tint = ComposeColor.White, modifier = Modifier.size(40.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = { toggleCamera() }) {
                        Icon(imageVector = Icons.Filled.FlipCameraAndroid, contentDescription = "Toggle Camera", tint = ComposeColor.White, modifier = Modifier.size(40.dp))
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun CameraScreenPreview() { CamStampTheme { CameraScreen() } }
