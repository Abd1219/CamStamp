package com.abdapps.camstamp

import android.Manifest
//import android.content.Context // No longer needed for getCurrentLocation parameter
import android.content.pm.PackageManager
import android.os.Environment
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview as CameraXPreview // Alias for CameraX Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview // Default Compose Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.abdapps.camstamp.ui.theme.CamStampTheme
import com.google.android.gms.location.LocationServices
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executor

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
    var customText by remember { mutableStateOf("") }
    var textFieldValue by remember { mutableStateOf("") }

    val previewView = remember {
        PreviewView(localContext).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    LaunchedEffect(zoomStateValue?.linearZoom) {
        zoomStateValue?.linearZoom?.let { cameraLinearZoom ->
            if (cameraLinearZoom != linearZoom) {
                linearZoom = cameraLinearZoom
            }
        }
    }
    
    fun toggleCamera() {
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        linearZoom = 0f // Reset zoom when toggling camera
    }

    LaunchedEffect(cameraProviderFuture, lifecycleOwner, cameraSelector, previewView) {
        try {
            val cameraProvider = cameraProviderFuture.get() // Suspends until provider is available
            
            // Unbind previous use cases before rebinding
            cameraProvider.unbindAll()

            // Build Preview use case
            val cameraXPreview = CameraXPreview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // Build ImageCapture use case
            imageCapture = ImageCapture.Builder().build()

            // Bind use cases to camera
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                cameraXPreview,
                imageCapture
            )
            // Reset linearZoom to what the new camera reports after binding
            // This ensures the slider reflects the actual zoom of the newly selected camera
            camera?.cameraInfo?.zoomState?.value?.linearZoom?.let {
                linearZoom = it
            }

        } catch (exc: Exception) {
            Log.e("CameraScreen", "Use case binding failed or camera unavailable", exc)
        }
    }

    fun getCurrentLocation() {
        if (hasLocationPermission) {
            try {
                fusedLocationClient.lastLocation
                    .addOnSuccessListener { location ->
                        if (location != null) {
                            latitude = location.latitude
                            longitude = location.longitude
                            Log.d("CameraScreen", "Location: $latitude, $longitude")
                        } else {
                            Log.d("CameraScreen", "Last location is null")
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("CameraScreen", "Failed to get location: ${e.message}", e)
                    }
            } catch (e: SecurityException) {
                Log.e("CameraScreen", "Location permission not granted when trying to get location.", e)
            }
        } else {
            Log.d("CameraScreen", "Attempted to get location without permission.")
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) {
                hasCameraPermission = true
            } else {
                Log.e("CameraScreen", "Camera permission denied")
            }
        }
    )

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions ->
            val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
            if (fineLocationGranted || coarseLocationGranted) {
                hasLocationPermission = true
                Log.d("CameraScreen", "Location permission granted, fetching location.")
                getCurrentLocation()
            } else {
                Log.e("CameraScreen", "Location permission denied")
            }
        }
    )

    LaunchedEffect(Unit) { // For initial permission checks
        when (PackageManager.PERMISSION_GRANTED) {
            ContextCompat.checkSelfPermission(localContext, Manifest.permission.CAMERA) -> {
                hasCameraPermission = true
            }
            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        when {
            ContextCompat.checkSelfPermission(localContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(localContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED -> {
                hasLocationPermission = true
                getCurrentLocation()
            }
            else -> locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    fun takePhoto(cameraExecutor: Executor) {
        val imageCapture = imageCapture ?: return
        getCurrentLocation()
        Log.d("CameraScreen", "Taking photo. Custom text: $customText, Location: $latitude, $longitude, Zoom: $linearZoom")

        val photoFile = File(
            localContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis()) + ".jpg"
        )
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions, cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e("CameraScreen", "Photo capture failed: ${exc.message}", exc)
                }
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val msg = "Photo capture succeeded: ${output.savedUri}"
                    Log.d("CameraScreen", msg)
                }
            }
        )
    }

    if (showCustomTextDialog) {
        AlertDialog(
            onDismissRequest = { showCustomTextDialog = false },
            title = { Text("Enter Custom Text") },
            text = { TextField(value = textFieldValue, onValueChange = { textFieldValue = it }, label = { Text("Text to stamp") }) },
            confirmButton = { Button(onClick = { customText = textFieldValue; showCustomTextDialog = false }) { Text("Save") } },
            dismissButton = { Button(onClick = { showCustomTextDialog = false }) { Text("Cancel") } }
        )
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (hasCameraPermission) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Camera and/or Location permission is required.", color = Color.White)
            }
        }

        Box(modifier = Modifier.fillMaxWidth().height(100.dp).background(Color.Black.copy(alpha = 0.5f)).align(Alignment.TopCenter)) {
            IconButton(onClick = { textFieldValue = customText; showCustomTextDialog = true }, modifier = Modifier.align(Alignment.CenterStart).padding(16.dp)) {
                Icon(imageVector = Icons.Filled.Edit, contentDescription = "Custom Text", tint = Color.White)
            }
        }

        Box(modifier = Modifier.fillMaxWidth().height(150.dp).background(Color.Black.copy(alpha = 0.5f)).align(Alignment.BottomCenter)) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier.fillMaxHeight().width(60.dp), 
                    contentAlignment = Alignment.Center
                ) {
                    Slider(
                        value = linearZoom,
                        onValueChange = {
                            linearZoom = it
                            camera?.cameraControl?.setLinearZoom(it)
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.graphicsLayer(rotationZ = -90f).width(120.dp).height(50.dp)
                    )
                }

                IconButton(onClick = {
                    if (hasCameraPermission && hasLocationPermission) {
                        takePhoto(ContextCompat.getMainExecutor(localContext))
                    } else {
                        Log.w("CameraScreen", "Cannot take photo, missing permissions. Camera: $hasCameraPermission, Location: $hasLocationPermission")
                        if (!hasCameraPermission) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        if (!hasLocationPermission) locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                }, modifier = Modifier.size(72.dp)) {
                    Icon(imageVector = Icons.Filled.PhotoCamera, contentDescription = "Take Photo", tint = Color.White, modifier = Modifier.size(64.dp))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { /* TODO: Handle image preview click */ }) {
                        Icon(imageVector = Icons.Filled.PhotoLibrary, contentDescription = "Image Preview", tint = Color.White, modifier = Modifier.size(40.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = { toggleCamera() }) { // Updated onClick
                        Icon(imageVector = Icons.Filled.FlipCameraAndroid, contentDescription = "Toggle Camera", tint = Color.White, modifier = Modifier.size(40.dp))
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun CameraScreenPreview() {
    CamStampTheme {
        CameraScreen()
    }
}
