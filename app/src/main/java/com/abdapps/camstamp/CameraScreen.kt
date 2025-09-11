package com.abdapps.camstamp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
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
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.exifinterface.media.ExifInterface
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
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Estampa una imagen con detalles, asegurando que el resultado final sea siempre vertical.
 *
 * @param photoFile El archivo de la foto que se va a modificar.
 * @param customText El texto personalizado para estampar.
 * @param latitude La latitud para estampar.
 * @param longitude La longitud para estampar.
 * @param context El contexto de la aplicación.
 * @return `true` si el estampado fue exitoso, `false` en caso de error.
 */
suspend fun stampImageWithDetails(
    photoFile: File,
    customText: String,
    latitude: Double?,
    longitude: Double?,
    context: Context
): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            // 1. Cargar el bitmap y la orientación original desde EXIF
            val inputStream: InputStream = photoFile.inputStream()
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            val exifInterface = ExifInterface(photoFile.absolutePath)
            val orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)

            // 2. Crear un bitmap inicial con la orientación corregida (ponerlo "derecho")
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            }
            val uprightBitmap = Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true)
            originalBitmap.recycle() // Liberar memoria del bitmap original

            // 3. Forzar a que la imagen sea vertical si es horizontal
            val finalBitmap = if (uprightBitmap.width > uprightBitmap.height) {
                Log.d("StampUtils", "La imagen es horizontal, forzando a vertical.")
                val rotationMatrix = Matrix().apply { postRotate(90f) }
                val rotated = Bitmap.createBitmap(uprightBitmap, 0, 0, uprightBitmap.width, uprightBitmap.height, rotationMatrix, true)
                uprightBitmap.recycle() // Liberar memoria del bitmap "derecho"
                rotated
            } else {
                uprightBitmap
            }

            // 4. Preparar para dibujar sobre el bitmap final
            val mutableBitmap = finalBitmap.copy(Bitmap.Config.ARGB_8888, true)
            finalBitmap.recycle() // Liberar memoria
            
            val canvas = Canvas(mutableBitmap)
            val resources = context.resources
            val textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24f, resources.displayMetrics)
            
            val textPaint = Paint().apply {
                color = android.graphics.Color.YELLOW
                this.textSize = textSizePx
                isAntiAlias = true
                textAlign = Paint.Align.RIGHT
            }
            val backgroundPaint = Paint().apply {
                color = android.graphics.Color.argb(128, 0, 0, 0)
                style = Paint.Style.FILL
            }

            // 5. Construir y dibujar el texto
            val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val dateTimeString = dateTimeFormat.format(Date())
            val lines = mutableListOf<String>()
            customText.split('\n').forEach { line -> if (line.isNotBlank()) lines.add(line) }
            lines.add("Fecha: $dateTimeString")
            if (latitude != null && longitude != null) {
                lines.add("$latitude, $longitude")
            }

            if (lines.isNotEmpty()) {
                val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics)
                var maxWidth = 0f
                val tempRect = Rect()
                for (line in lines) {
                    textPaint.getTextBounds(line, 0, line.length, tempRect)
                    if (tempRect.width() > maxWidth) maxWidth = tempRect.width().toFloat()
                }

                val textAlignmentX = mutableBitmap.width - padding - (padding / 2f)
                val effectiveLineHeight = textSizePx + padding
                val totalBlockDrawingHeight = lines.size * effectiveLineHeight
                val yPosOfTopVisualLine = mutableBitmap.height - padding - totalBlockDrawingHeight
                val yPosOfBottomVisualLine = yPosOfTopVisualLine + (lines.size - 1) * effectiveLineHeight

                val fm = textPaint.fontMetrics
                canvas.drawRect(
                    textAlignmentX - maxWidth - (padding / 2f),
                    yPosOfTopVisualLine + fm.ascent - (padding / 2f),
                    textAlignmentX + (padding / 2f),
                    yPosOfBottomVisualLine + fm.descent + (padding / 2f),
                    backgroundPaint
                )

                var currentLineBaselineY = yPosOfTopVisualLine
                for (line in lines) {
                    canvas.drawText(line, textAlignmentX, currentLineBaselineY, textPaint)
                    currentLineBaselineY += effectiveLineHeight
                }
            }
            
            // 6. Guardar el bitmap final
            FileOutputStream(photoFile).use { out ->
                mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }
            mutableBitmap.recycle()

            // 7. **LA SOLUCIÓN DEFINITIVA**: Resetear la etiqueta de orientación EXIF.
            val finalExif = ExifInterface(photoFile.absolutePath)
            finalExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            finalExif.saveAttributes()
            
            Log.d("StampUtils", "Imagen procesada. La orientación EXIF ha sido reseteada a Normal.")
            true
        } catch (e: Exception) {
            Log.e("StampUtils", "Error al procesar la imagen: ${e.message}", e)
            false
        }
    }
}

/**
 * El Composable principal que muestra la pantalla de la cámara, controles y previsualizaciones.
 */
@Composable
fun CameraScreen(modifier: Modifier = Modifier) {
    // --- Contexto y Estados Principales ---
    val localContext = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember { mutableStateOf(false) }
    var hasLocationPermission by remember { mutableStateOf(false) }
    var latitude by remember { mutableStateOf<Double?>(null) }
    var longitude by remember { mutableStateOf<Double?>(null) }
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(localContext) }

    // --- Estados y Objetos de CameraX ---
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(localContext) }
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }
    var camera: Camera? by remember { mutableStateOf(null) }
    var cameraSelector by remember { mutableStateOf(CameraSelector.DEFAULT_BACK_CAMERA) }
    var linearZoom by remember { mutableFloatStateOf(0f) }
    val zoomStateValue = camera?.cameraInfo?.zoomState?.observeAsState()?.value
    var torchEnabled by remember { mutableStateOf(false) }

    // --- Estados de la Interfaz de Usuario (UI) ---
    var showCustomTextDialog by remember { mutableStateOf(false) }
    var currentCustomText by remember { mutableStateOf("") }
    var textFieldValue by remember { mutableStateOf("") }
    var showFlashEffect by remember { mutableStateOf(false) }
    val previewView = remember { PreviewView(localContext) }
    
    // --- Estados para la Miniatura y la Vista a Pantalla Completa ---
    var lastPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var photoId by remember { mutableIntStateOf(0) } // El contador de fotos para refrescar la UI
    var thumbnailImageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var showFullScreenImageDialog by remember { mutableStateOf(false) }
    var imageToShowInDialog by remember { mutableStateOf<Uri?>(null) }
    var fullScreenImageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    // --- Funciones de Lógica ---
    val getCurrentLocation: () -> Unit = {
        if (hasLocationPermission) {
            try {
                fusedLocationClient.lastLocation
                    .addOnSuccessListener { location ->
                        if (location != null) {
                            latitude = location.latitude
                            longitude = location.longitude
                        }
                    }
                    .addOnFailureListener { e -> Log.e("CameraScreen", "Fallo al obtener la ubicación: ${e.message}", e) }
            } catch (e: SecurityException) {
                Log.e("CameraScreen", "Excepción de seguridad al obtener la ubicación: ${e.message}", e)
            }
        }
    }

    // Se ejecuta cuando cambia el permiso de la cámara o cualquiera de las otras claves.
    LaunchedEffect(hasCameraPermission, cameraProviderFuture, lifecycleOwner, cameraSelector, previewView) {
        if (hasCameraPermission) {
            try {
                val cameraProvider = cameraProviderFuture.await()
                cameraProvider.unbindAll()
                val cameraXPreview = CameraXPreview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                imageCapture = ImageCapture.Builder()
                    .setTargetRotation(previewView.display.rotation)
                    .build()
                camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, cameraXPreview, imageCapture)
                
                // Si la nueva cámara no tiene flash, apaga el estado de la linterna
                if (camera?.cameraInfo?.hasFlashUnit() == false) {
                    torchEnabled = false
                }
                camera?.cameraControl?.enableTorch(torchEnabled) // Sincroniza el estado de la linterna

                camera?.cameraInfo?.zoomState?.value?.linearZoom?.let { linearZoom = it }
            } catch (exc: Exception) { Log.e("CameraScreen", "Fallo al vincular casos de uso: ${exc.message}", exc) }
        }
    }

    // Función simplificada para cargar una imagen. Ya no necesita rotar nada.
    suspend fun loadFinalImage(uri: Uri): ImageBitmap? {
        return withContext(Dispatchers.IO) {
            try {
                // Usar ContentResolver es el método más robusto para leer desde una URI.
                localContext.contentResolver.openInputStream(uri)?.use { inputStream ->
                    BitmapFactory.decodeStream(inputStream)?.asImageBitmap()
                }
            } catch (e: Exception) {
                Log.e("ImageLoader", "Error al cargar la imagen final: ${e.message}", e)
                null
            }
        }
    }

    // Se ejecuta cuando `photoId` cambia. Carga la nueva foto como un bitmap para la miniatura.
    LaunchedEffect(photoId) {
        if (photoId > 0 && lastPhotoUri != null) { // Asegurarse de que no se ejecute en la primera composición
            thumbnailImageBitmap = loadFinalImage(lastPhotoUri!!)
        } else if (photoId == 0) {
            thumbnailImageBitmap = null
        }
    }
    
    // Se ejecuta cuando `imageToShowInDialog` cambia. Carga la imagen para verla en pantalla completa.
    LaunchedEffect(imageToShowInDialog) {
        if (imageToShowInDialog != null) {
            fullScreenImageBitmap = loadFinalImage(imageToShowInDialog!!)
            if(fullScreenImageBitmap == null) {
                showFullScreenImageDialog = false // Si falla la carga, no mostrar el diálogo
            }
        } else {
            fullScreenImageBitmap = null
        }
    }

    LaunchedEffect(zoomStateValue?.linearZoom) { zoomStateValue?.linearZoom?.let { if (it != linearZoom) linearZoom = it } }
    LaunchedEffect(showFlashEffect) { if (showFlashEffect) { delay(150L); showFlashEffect = false } }

    // --- Controladores de Permisos ---
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> hasCameraPermission = granted }
    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            hasLocationPermission = true
            getCurrentLocation()
        }
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(localContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            hasCameraPermission = true
        }

        if (ContextCompat.checkSelfPermission(localContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else {
            hasLocationPermission = true
            getCurrentLocation()
        }
    }
    
    // --- Funciones de Acción ---
    fun toggleCamera() { cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA; linearZoom = 0f }
    
    fun takePhoto(cameraExecutor: Executor) {
        val imageCaptureInstance = imageCapture ?: return
        showFlashEffect = true
        getCurrentLocation()
        val photoFile = File(localContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "${SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis())}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCaptureInstance.takePicture(outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onError(exc: ImageCaptureException) { Log.e("CameraScreen", "Fallo al capturar foto: ${exc.message}", exc) }
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                Log.d("CameraScreen", "Foto capturada con éxito, procesando...")
                
                lifecycleOwner.lifecycleScope.launch {
                    val stamped = stampImageWithDetails(photoFile, currentCustomText, latitude, longitude, localContext)
                    if (stamped) {
                        // Actualizar la URI y el contador para forzar el refresco de la UI
                        lastPhotoUri = Uri.fromFile(photoFile)
                        photoId++
                    }
                }
            }
        })
    }

    // --- Composición de la Interfaz de Usuario (UI) ---
    if (showCustomTextDialog) {
        AlertDialog(
            onDismissRequest = { showCustomTextDialog = false },
            title = { Text("Introduce texto personalizado") },
            text = { TextField(value = textFieldValue, onValueChange = { textFieldValue = it }, label = { Text("Texto a estampar") }) },
            confirmButton = { Button(onClick = { currentCustomText = textFieldValue; showCustomTextDialog = false }) { Text("Guardar") } },
            dismissButton = { Button(onClick = { showCustomTextDialog = false }) { Text("Cancelar") } }
        )
    }

    if (showFullScreenImageDialog && fullScreenImageBitmap != null) {
        Dialog(onDismissRequest = { showFullScreenImageDialog = false; imageToShowInDialog = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(modifier = Modifier.fillMaxSize().background(ComposeColor.Black.copy(alpha = 0.8f)).clickable { showFullScreenImageDialog = false; imageToShowInDialog = null }, contentAlignment = Alignment.Center) {
                Image(bitmap = fullScreenImageBitmap!!, contentDescription = "Imagen a pantalla completa", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().background(ComposeColor.Black)) {
        Box(modifier = Modifier.fillMaxWidth().height(100.dp).background(ComposeColor.Black.copy(alpha = 0.5f))) { 
            Row(
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { textFieldValue = currentCustomText; showCustomTextDialog = true }) {
                    Icon(imageVector = Icons.Filled.Edit, contentDescription = "Texto Personalizado", tint = ComposeColor.White)
                }
                // Solo muestra el botón si la cámara actual tiene flash
                if (camera?.cameraInfo?.hasFlashUnit() == true) {
                    IconButton(onClick = {
                        val newTorchState = !torchEnabled
                        camera?.cameraControl?.enableTorch(newTorchState)
                        torchEnabled = newTorchState
                    }) {
                        Icon(
                            imageVector = if (torchEnabled) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                            contentDescription = "Activar/Desactivar Linterna",
                            tint = if (torchEnabled) ComposeColor.Yellow else ComposeColor.White
                        )
                    }
                }
            }
        }
        
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) { 
            if (hasCameraPermission) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            } else {
                Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text("Se requiere permiso de cámara para usar esta función.", color = ComposeColor.White)
                }
            }
            if (showFlashEffect) {
                Box(modifier = Modifier.fillMaxSize().background(ComposeColor.White)) 
            }
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
                    Icon(imageVector = Icons.Filled.PhotoCamera, contentDescription = "Tomar Foto", tint = ComposeColor.White, modifier = Modifier.size(64.dp))
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) { 
                    IconButton(onClick = {
                        if (lastPhotoUri != null) {
                            imageToShowInDialog = lastPhotoUri
                            showFullScreenImageDialog = true
                        }
                    }) {
                        if (thumbnailImageBitmap != null) {
                            Image(bitmap = thumbnailImageBitmap!!, contentDescription = "Miniatura de la última foto", modifier = Modifier.size(40.dp), contentScale = ContentScale.Crop)
                        } else {
                            Icon(imageVector = Icons.Filled.PhotoLibrary, contentDescription = "Previsualización de Imagen", tint = ComposeColor.White, modifier = Modifier.size(40.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = { toggleCamera() }) {
                        Icon(imageVector = Icons.Filled.FlipCameraAndroid, contentDescription = "Cambiar Cámara", tint = ComposeColor.White, modifier = Modifier.size(40.dp))
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun CameraScreenPreview() { CamStampTheme { CameraScreen() } }
