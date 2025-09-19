package com.abdapps.camstamp

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.TypedValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.core.content.edit
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
import androidx.camera.core.Preview as CameraXPreview
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * Estampa una imagen con detalles, forzando siempre orientación vertical (portrait).
 * 
 * Todas las fotos se guardan en orientación vertical sin importar cómo se tomaron,
 * para mantener consistencia visual en la galería.
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
    context: Context,
    wasDeviceHorizontal: Boolean = false
): Boolean {
    return withContext(Dispatchers.IO) {
        var originalBitmap: Bitmap? = null
        var uprightBitmap: Bitmap? = null
        var finalBitmap: Bitmap? = null
        var mutableBitmap: Bitmap? = null
        
        try {
            // 1. Cargar el bitmap y la orientación original desde EXIF
            val inputStream: InputStream = photoFile.inputStream()
            originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            if (originalBitmap == null || originalBitmap.isRecycled) {
                Log.e("StampUtils", "No se pudo cargar el bitmap o está reciclado")
                return@withContext false
            }

            val exifInterface = ExifInterface(photoFile.absolutePath)
            val orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
            
            Log.d("StampUtils", "Orientación EXIF original: $orientation")
            Log.d("StampUtils", "Dimensiones originales: ${originalBitmap.width}x${originalBitmap.height}")

            // 2. Aplicar la rotación EXIF para mostrar la imagen correctamente
            val matrix = Matrix()
            val rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            
            if (rotationDegrees != 0f) {
                matrix.postRotate(rotationDegrees)
                uprightBitmap = Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true)
                originalBitmap.recycle()
                originalBitmap = null
                Log.d("StampUtils", "Imagen rotada ${rotationDegrees}°. Nuevas dimensiones: ${uprightBitmap.width}x${uprightBitmap.height}")
            } else {
                uprightBitmap = originalBitmap
                Log.d("StampUtils", "No se requiere rotación")
            }

            if (uprightBitmap.isRecycled) {
                Log.e("StampUtils", "El bitmap uprightBitmap está reciclado")
                return@withContext false
            }

            // 3. SOLUCIÓN DEFINITIVA: Usar orientación del dispositivo, no dimensiones del bitmap
            Log.d("StampUtils", "=== ANÁLISIS DE ORIENTACIÓN DEFINITIVO ===")
            Log.d("StampUtils", "Orientación EXIF: $orientation")
            Log.d("StampUtils", "Dimensiones bitmap: ${uprightBitmap.width}x${uprightBitmap.height}")
            Log.d("StampUtils", "Dispositivo estaba horizontal: $wasDeviceHorizontal")
            
            finalBitmap = if (wasDeviceHorizontal) {
                // Si el dispositivo estaba horizontal, rotar sin importar las dimensiones
                Log.d("StampUtils", "DISPOSITIVO HORIZONTAL - Aplicando rotación -90°")
                
                val rotationMatrix = Matrix().apply { postRotate(-90f) }
                val rotatedBitmap = Bitmap.createBitmap(uprightBitmap, 0, 0, uprightBitmap.width, uprightBitmap.height, rotationMatrix, true)
                
                // Liberar el bitmap anterior si se creó uno nuevo
                if (rotatedBitmap != uprightBitmap) {
                    uprightBitmap.recycle()
                    uprightBitmap = null
                }
                
                Log.d("StampUtils", "ROTACIÓN APLICADA - Nuevas dimensiones: ${rotatedBitmap.width}x${rotatedBitmap.height}")
                rotatedBitmap
            } else {
                Log.d("StampUtils", "DISPOSITIVO VERTICAL - Manteniendo orientación original")
                uprightBitmap
            }
            Log.d("StampUtils", "Imagen final: ${finalBitmap.width}x${finalBitmap.height}")

            if (finalBitmap.isRecycled) {
                Log.e("StampUtils", "El bitmap finalBitmap está reciclado")
                return@withContext false
            }

            // 4. Preparar para dibujar sobre el bitmap final
            mutableBitmap = finalBitmap.copy(Bitmap.Config.ARGB_8888, true)
            
            if (mutableBitmap.isRecycled) {
                Log.e("StampUtils", "No se pudo crear el bitmap mutable")
                return@withContext false
            }
            
            val canvas = Canvas(mutableBitmap)
            val resources = context.resources
            
            // Calcular tamaño de texto proporcional al área total de la imagen
            val imageArea = mutableBitmap.width * mutableBitmap.height
            val baseFontSize = when {
                imageArea > 10_000_000 -> 32f  // Imágenes muy grandes (>10MP)
                imageArea > 5_000_000 -> 28f   // Imágenes grandes (5-10MP)
                imageArea > 2_000_000 -> 24f   // Imágenes medianas (2-5MP)
                imageArea > 1_000_000 -> 20f   // Imágenes pequeñas (1-2MP)
                else -> 16f                    // Imágenes muy pequeñas (<1MP)
            }
            val textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, baseFontSize, resources.displayMetrics)
            
            Log.d("StampUtils", "Imagen: ${mutableBitmap.width}x${mutableBitmap.height} (${String.format("%.1f", imageArea/1_000_000f)}MP), Fuente: ${baseFontSize}sp")
            
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

            // 7. Resetear la etiqueta de orientación EXIF.
            val finalExif = ExifInterface(photoFile.absolutePath)
            finalExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            finalExif.saveAttributes()
            
            Log.d("StampUtils", "Imagen procesada. La orientación EXIF ha sido reseteada a Normal.")
            true
        } catch (e: Exception) {
            Log.e("StampUtils", "Error al procesar la imagen: ${e.message}", e)
            false
        } finally {
            // Limpiar memoria de bitmaps
            try {
                originalBitmap?.let { if (!it.isRecycled) it.recycle() }
                uprightBitmap?.let { if (!it.isRecycled && it != finalBitmap) it.recycle() }
                finalBitmap?.let { if (!it.isRecycled && it != mutableBitmap) it.recycle() }
                mutableBitmap?.let { if (!it.isRecycled) it.recycle() }
            } catch (e: Exception) {
                Log.w("StampUtils", "Error al limpiar bitmaps: ${e.message}")
            }
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
    
    // --- Sensor Manager para orientación ---
    val sensorManager = remember { localContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val accelerometer = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }

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
    
    // --- Estados para la Miniatura ---
    var lastPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var photoId by remember { mutableIntStateOf(0) } // El contador de fotos para refrescar la UI
    var thumbnailImageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    
    // --- Estados para Mensajes de Éxito/Error ---
    var showMessageDialog by remember { mutableStateOf(false) }
    var messageTitle by remember { mutableStateOf("") }
    var messageText by remember { mutableStateOf("") }
    var isSuccessMessage by remember { mutableStateOf(true) }
    var hasShownThumbnailTip by remember { mutableStateOf(false) }
    
    // --- Estado para detectar orientación del dispositivo ---
    var isDeviceHorizontal by remember { mutableStateOf(false) }
    var currentOrientation by remember { mutableStateOf("Desconocido") }
    
    // --- Estado para calidad de imagen ---
    var isHighQuality by remember { mutableStateOf(true) } // true = 4096x3072, false = 1440x1920
    


    // --- Funciones de Lógica ---
    val showSuccessMessage: (String) -> Unit = { message ->
        messageTitle = "¡Éxito!"
        messageText = message
        isSuccessMessage = true
        showMessageDialog = true
    }
    
    val showErrorMessage: (String) -> Unit = { message ->
        messageTitle = "Error"
        messageText = message
        isSuccessMessage = false
        showMessageDialog = true
    }
    
    val openImageInGallery: (Uri) -> Unit = { uri ->
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            localContext.startActivity(intent)
        } catch (e: Exception) {
            Log.e("CameraScreen", "Error al abrir la imagen en la galería: ${e.message}", e)
            showErrorMessage("No se pudo abrir la imagen en la galería")
        }
    }
    
    // --- Funciones de Persistencia ---
    val sharedPreferences = remember { 
        localContext.getSharedPreferences("CamStampPrefs", Context.MODE_PRIVATE) 
    }
    
    val saveCustomText: (String) -> Unit = { text ->
        try {
            sharedPreferences.edit {
                putString("custom_text", text)
            }
            Log.d("CameraScreen", "Texto personalizado guardado: $text")
        } catch (e: Exception) {
            Log.e("CameraScreen", "Error al guardar texto personalizado: ${e.message}", e)
        }
    }
    
    val loadCustomText: () -> String = {
        try {
            val savedText = sharedPreferences.getString("custom_text", "") ?: ""
            Log.d("CameraScreen", "Texto personalizado cargado: $savedText")
            savedText
        } catch (e: Exception) {
            Log.e("CameraScreen", "Error al cargar texto personalizado: ${e.message}", e)
            ""
        }
    }
    
    val clearCustomText: () -> Unit = {
        try {
            sharedPreferences.edit {
                remove("custom_text")
            }
            currentCustomText = ""
            textFieldValue = ""
            Log.d("CameraScreen", "Texto personalizado limpiado")
        } catch (e: Exception) {
            Log.e("CameraScreen", "Error al limpiar texto personalizado: ${e.message}", e)
        }
    }
    
    val saveQualityPreference: (Boolean) -> Unit = { highQuality ->
        try {
            sharedPreferences.edit {
                putBoolean("high_quality", highQuality)
            }
            Log.d("CameraScreen", "Preferencia de calidad guardada: ${if (highQuality) "Alta" else "Media"}")
        } catch (e: Exception) {
            Log.e("CameraScreen", "Error al guardar preferencia de calidad: ${e.message}", e)
        }
    }
    
    val loadQualityPreference: () -> Boolean = {
        try {
            val savedQuality = sharedPreferences.getBoolean("high_quality", true) // Por defecto alta calidad
            Log.d("CameraScreen", "Preferencia de calidad cargada: ${if (savedQuality) "Alta" else "Media"}")
            savedQuality
        } catch (e: Exception) {
            Log.e("CameraScreen", "Error al cargar preferencia de calidad: ${e.message}", e)
            true // Por defecto alta calidad
        }
    }
    

    
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
    
    // --- Listener del sensor de orientación ---
    val sensorEventListener = remember {
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                event?.let {
                    val x = it.values[0]
                    val y = it.values[1]
                    
                    // Calcular orientación basada en acelerómetro
                    val orientation = when {
                        kotlin.math.abs(x) > kotlin.math.abs(y) -> {
                            if (kotlin.math.abs(x) > 2.0) "Horizontal" else currentOrientation
                        }
                        kotlin.math.abs(y) > kotlin.math.abs(x) -> {
                            if (kotlin.math.abs(y) > 8.0) "Vertical" else currentOrientation
                        }
                        else -> currentOrientation
                    }
                    
                    if (orientation != currentOrientation) {
                        currentOrientation = orientation
                        isDeviceHorizontal = (orientation == "Horizontal")
                        Log.d("CameraScreen", "Orientación detectada por sensor: $orientation")
                    }
                }
            }
            
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
    }
    
    val updateDeviceOrientation: () -> Unit = {
        // Usar la orientación detectada por el sensor
        Log.d("CameraScreen", "Orientación al tomar foto: $currentOrientation (Horizontal: $isDeviceHorizontal)")
    }

    // Se ejecuta cuando cambia el permiso de la cámara o cualquiera de las otras claves.
    LaunchedEffect(hasCameraPermission, cameraProviderFuture, lifecycleOwner, cameraSelector, previewView, isHighQuality) {
        if (hasCameraPermission) {
            try {
                val cameraProvider = cameraProviderFuture.await()
                cameraProvider.unbindAll()
                val cameraXPreview = CameraXPreview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                imageCapture = ImageCapture.Builder()
                    .setTargetRotation(previewView.display.rotation)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(
                        if (!isHighQuality) {
                            // Configuración para calidad media
                            ResolutionSelector.Builder()
                                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        android.util.Size(1440, 1920),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                                    )
                                )
                                .build()
                        } else {
                            // Configuración para alta calidad - máxima resolución disponible
                            ResolutionSelector.Builder()
                                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                                .build()
                        }
                    )
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
    


    LaunchedEffect(zoomStateValue?.linearZoom) { zoomStateValue?.linearZoom?.let { if (it != linearZoom) linearZoom = it } }
    LaunchedEffect(showFlashEffect) { if (showFlashEffect) { delay(150L); showFlashEffect = false } }
    
    // Auto-ocultar mensaje de éxito después de 3 segundos
    LaunchedEffect(showMessageDialog, isSuccessMessage) {
        if (showMessageDialog && isSuccessMessage) {
            delay(3000L)
            showMessageDialog = false
        }
    }
    
    // Cargar texto personalizado y preferencia de calidad al iniciar
    LaunchedEffect(Unit) {
        val savedText = loadCustomText()
        currentCustomText = savedText
        textFieldValue = savedText
        
        val savedQuality = loadQualityPreference()
        isHighQuality = savedQuality
    }
    
    // Registrar sensor de orientación
    LaunchedEffect(Unit) {
        accelerometer?.let { sensor ->
            sensorManager.registerListener(sensorEventListener, sensor, SensorManager.SENSOR_DELAY_UI)
        }
    }
    
    // Cleanup del sensor
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            sensorManager.unregisterListener(sensorEventListener)
        }
    }
    


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
    
    suspend fun saveFileToMediaStore(context: Context, file: File): Uri? {
        return withContext(Dispatchers.IO) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_${System.currentTimeMillis()}.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/CamStamp")
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            
            uri?.let {
                try {
                    resolver.openOutputStream(it)?.use { outputStream ->
                        file.inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    Log.d("CameraScreen", "Archivo copiado a MediaStore: $it")
                } catch (e: Exception) {
                    Log.e("CameraScreen", "Error al copiar a MediaStore: ${e.message}", e)
                    return@withContext null
                }
            }
            uri
        }
    }

    // --- Funciones de Acción ---
    fun toggleCamera() { cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA; linearZoom = 0f }
    
    fun takePhoto(cameraExecutor: Executor) {
        val imageCaptureInstance = imageCapture ?: return
        showFlashEffect = true
        getCurrentLocation()
        updateDeviceOrientation() // Detectar orientación al momento de tomar la foto
        
        // 1. Guardar en un archivo temporal primero
        val photoFile = File.createTempFile("CamStamp_temp", ".jpg", localContext.cacheDir)
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCaptureInstance.takePicture(outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onError(exc: ImageCaptureException) { 
                Log.e("CameraScreen", "Fallo al capturar foto: ${exc.message}", exc)
                showErrorMessage("Error al capturar la foto: ${exc.message}")
            }
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                Log.d("CameraScreen", "Foto temporal guardada en: ${output.savedUri}")
                
                lifecycleOwner.lifecycleScope.launch {
                    try {
                        // 2. Procesar la imagen (rotar, estampar, corregir EXIF)
                        val stamped = stampImageWithDetails(photoFile, currentCustomText, latitude, longitude, localContext, isDeviceHorizontal)
                        if (stamped) {
                            // 3. Mover la imagen procesada a la galería pública
                            val publicUri = saveFileToMediaStore(localContext, photoFile)
                            if (publicUri != null) {
                                // 4. Actualizar la UI con la URI pública
                                lastPhotoUri = publicUri
                                photoId++
                                
                                val successMessage = if (!hasShownThumbnailTip) {
                                    hasShownThumbnailTip = true
                                    "Foto guardada exitosamente.\n\nTip: Toca la miniatura para abrir en galería."
                                } else {
                                    "Foto guardada exitosamente en la galería"
                                }
                                showSuccessMessage(successMessage)
                            } else {
                                Log.e("CameraScreen", "No se pudo guardar la foto en la galería.")
                                showErrorMessage("No se pudo guardar la foto en la galería")
                            }
                        } else {
                            showErrorMessage("Error al procesar la imagen")
                        }
                        // 5. Borrar el archivo temporal
                        photoFile.delete()
                    } catch (e: Exception) {
                        Log.e("CameraScreen", "Error inesperado al procesar la foto: ${e.message}", e)
                        showErrorMessage("Error inesperado: ${e.message}")
                        // Asegurar que se borre el archivo temporal incluso si hay error
                        try { photoFile.delete() } catch (_: Exception) { /* Ignorar */ }
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
            text = { 
                TextField(
                    value = textFieldValue, 
                    onValueChange = { textFieldValue = it }, 
                    label = { Text("Texto a estampar") },
                    placeholder = { Text("Ej: Inspección de obra, Reporte técnico...") }
                ) 
            },
            confirmButton = { 
                Button(onClick = { 
                    currentCustomText = textFieldValue
                    saveCustomText(textFieldValue)
                    showCustomTextDialog = false 
                }) { 
                    Text("Guardar") 
                } 
            },
            dismissButton = { 
                Row {
                    Button(onClick = { 
                        clearCustomText()
                        showSuccessMessage("Texto personalizado limpiado")
                    }) { 
                        Text("Limpiar") 
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { showCustomTextDialog = false }) { 
                        Text("Cancelar") 
                    }
                }
            }
        )
    }

    if (showMessageDialog) {
        AlertDialog(
            onDismissRequest = { showMessageDialog = false },
            title = { 
                Text(
                    text = messageTitle,
                    color = if (isSuccessMessage) ComposeColor.Green else ComposeColor.Red
                )
            },
            text = { Text(messageText) },
            confirmButton = { 
                Button(onClick = { showMessageDialog = false }) { 
                    Text("Aceptar") 
                } 
            }
        )
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
                
                // Botón de calidad de imagen
                IconButton(onClick = {
                    isHighQuality = !isHighQuality
                    saveQualityPreference(isHighQuality)
                    Log.d("CameraScreen", "Calidad cambiada a: ${if (isHighQuality) "Alta (4096x3072)" else "Media (1440x1920)"}")
                }) {
                    Icon(
                        imageVector = Icons.Filled.HighQuality,
                        contentDescription = if (isHighQuality) "Calidad Alta - Cambiar a Media" else "Calidad Media - Cambiar a Alta",
                        tint = if (isHighQuality) ComposeColor.Green else ComposeColor.Yellow
                    )
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
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(80.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Indicador de zoom
                    Text(
                        text = "${(linearZoom * 10).toInt() + 1}x",
                        color = ComposeColor.White,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    // Slider de zoom mejorado
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Slider(
                            value = linearZoom, 
                            onValueChange = { newValue ->
                                linearZoom = newValue
                                camera?.cameraControl?.setLinearZoom(newValue)
                            }, 
                            valueRange = 0f..1f,
                            colors = SliderDefaults.colors(
                                thumbColor = ComposeColor.White,
                                activeTrackColor = ComposeColor.White.copy(alpha = 0.8f),
                                inactiveTrackColor = ComposeColor.White.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier
                                .graphicsLayer(rotationZ = -90f)
                                .width(140.dp) // Slider más largo
                                .height(60.dp) // Área táctil más grande
                        )
                    }
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
                            openImageInGallery(lastPhotoUri!!)
                        }
                    }) {
                        if (thumbnailImageBitmap != null) {
                            Image(
                                bitmap = thumbnailImageBitmap!!, 
                                contentDescription = "Miniatura - Clic para abrir en galería", 
                                modifier = Modifier.size(40.dp), 
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.PhotoLibrary, 
                                contentDescription = "Previsualización de Imagen", 
                                tint = ComposeColor.White, 
                                modifier = Modifier.size(40.dp)
                            )
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
