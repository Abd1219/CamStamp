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
 * Estampa una imagen con detalles como texto personalizado, fecha y coordenadas.
 * La operación se realiza directamente sobre el archivo de imagen proporcionado.
 *
 * @param photoFile El archivo de la foto que se va a modificar.
 * @param customText El texto personalizado para estampar, puede contener saltos de línea.
 * @param latitude La latitud para estampar.
 * @param longitude La longitud para estampar.
 * @param context El contexto de la aplicación para acceder a recursos.
 * @return `true` si el estampado fue exitoso, `false` en caso de error.
 */
suspend fun stampImageWithDetails(
    photoFile: File,
    customText: String,
    latitude: Double?,
    longitude: Double?,
    context: Context
): Boolean {
    // Se ejecuta en un hilo de fondo para no bloquear la UI
    return withContext(Dispatchers.IO) {
        try {
            // --- Corrección de la rotación de la imagen ---
            val inputStream: InputStream = photoFile.inputStream()
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            val exifInterface = ExifInterface(photoFile.absolutePath)
            val orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            }

            val rotatedBitmap = Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true)
            val mutableBitmap = rotatedBitmap.copy(Bitmap.Config.ARGB_8888, true)

            // Detecta y registra la orientación final de la imagen
            if (mutableBitmap.width >= mutableBitmap.height) {
                Log.d("StampUtils", "La foto detectada es Horizontal (Landscape).")
            } else {
                Log.d("StampUtils", "La foto detectada es Vertical (Portrait).")
            }

            // Prepara el Canvas para dibujar sobre el bitmap ya rotado y mutable
            val canvas = Canvas(mutableBitmap)
            val resources = context.resources
            // Convierte el tamaño de fuente de SP a píxeles para el Canvas
            val textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24f, resources.displayMetrics)
            
            // Configuración del pincel para el texto
            val textPaint = Paint().apply {
                color = android.graphics.Color.YELLOW
                this.textSize = textSizePx
                isAntiAlias = true
                textAlign = Paint.Align.RIGHT // Alinea el texto a la derecha
            }
            // Configuración del pincel para el fondo del texto
            val backgroundPaint = Paint().apply {
                color = android.graphics.Color.argb(128, 0, 0, 0) // Negro semitransparente
                style = Paint.Style.FILL
            }

            // Formatea la fecha y hora actual
            val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val dateTimeString = dateTimeFormat.format(Date())
            
            // Construye la lista de líneas de texto a estampar
            val lines = mutableListOf<String>()
            if (customText.isNotBlank()) {
                // Si el texto personalizado tiene saltos de línea, lo divide en varias líneas
                customText.split('\n').forEach { line ->
                    if (line.isNotBlank()) {
                        lines.add(line)
                    }
                }
            }
            lines.add("Fecha: $dateTimeString")
            if (latitude != null && longitude != null) {
                // Agrega las coordenadas con formato completo y sin prefijos
                lines.add("$latitude, $longitude")
            }

            if (lines.isEmpty()) return@withContext true // No hay nada que estampar

            val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics)
            
            // --- Lógica para el bloque de texto alineado a la derecha ---

            // 1. Calcular el ancho máximo de todas las líneas de texto
            var maxWidth = 0f
            val tempRect = Rect()
            for (line in lines) {
                textPaint.getTextBounds(line, 0, line.length, tempRect)
                if (tempRect.width() > maxWidth) {
                    maxWidth = tempRect.width().toFloat()
                }
            }

            // 2. Definir coordenadas y dimensiones del bloque
            val textAlignmentX = mutableBitmap.width - padding - (padding / 2f) // Coordenada X para alinear el texto a la derecha
            val effectiveLineHeight = textSizePx + padding // Altura efectiva por línea (texto + espaciado)
            val totalBlockDrawingHeight = lines.size * effectiveLineHeight // Altura total del bloque de texto

            // Coordenada Y de la línea base de la primera línea de texto (la más alta visualmente)
            val yPosOfTopVisualLine = mutableBitmap.height - padding - totalBlockDrawingHeight
            // Coordenada Y de la línea base de la última línea de texto (la más baja visualmente)
            val yPosOfBottomVisualLine = yPosOfTopVisualLine + (lines.size - 1) * effectiveLineHeight

            // 3. Calcular y dibujar un único rectángulo de fondo para todo el bloque
            val fm = textPaint.fontMetrics // Métricas de la fuente para un cálculo preciso de la altura
            val bgTop = yPosOfTopVisualLine + fm.ascent - (padding / 2f)
            val bgBottom = yPosOfBottomVisualLine + fm.descent + (padding / 2f)
            val bgLeft = textAlignmentX - maxWidth - (padding / 2f)
            val bgRight = textAlignmentX + (padding / 2f)
            canvas.drawRect(bgLeft, bgTop, bgRight, bgBottom, backgroundPaint)

            // 4. Dibujar cada línea de texto, de arriba hacia abajo
            var currentLineBaselineY = yPosOfTopVisualLine
            for (line in lines) {
                canvas.drawText(line, textAlignmentX, currentLineBaselineY, textPaint)
                currentLineBaselineY += effectiveLineHeight // Mueve la coordenada Y hacia abajo para la siguiente línea
            }
            
            // Guarda el bitmap modificado sobreescribiendo el archivo original
            FileOutputStream(photoFile).use { out ->
                mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }
            Log.d("StampUtils", "Imagen estampada y guardada con éxito.")
            true
        } catch (e: Exception) {
            Log.e("StampUtils", "Error al estampar la imagen: ${e.message}", e)
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
    var cameraSelector by remember { mutableStateOf(CameraSelector.DEFAULT_BACK_CAMERA) } // Inicia con la cámara trasera
    var linearZoom by remember { mutableFloatStateOf(0f) } // Estado para el nivel de zoom (0f a 1f)
    val zoomStateValue = camera?.cameraInfo?.zoomState?.observeAsState()?.value

    // --- Estados de la Interfaz de Usuario (UI) ---
    var showCustomTextDialog by remember { mutableStateOf(false) } // Controla la visibilidad del diálogo de texto
    var currentCustomText by remember { mutableStateOf("") } // El texto que se estampará
    var textFieldValue by remember { mutableStateOf("") } // El valor actual del campo de texto en el diálogo
    var showFlashEffect by remember { mutableStateOf(false) } // Activa el efecto de flash blanco
    val previewView = remember { PreviewView(localContext) } // La vista que muestra la previsualización de la cámara
    
    // --- Estados para la Miniatura y la Vista a Pantalla Completa ---
    var lastPhotoUri by remember { mutableStateOf<Uri?>(null) } // URI de la última foto tomada
    var thumbnailImageBitmap by remember { mutableStateOf<ImageBitmap?>(null) } // Bitmap para la miniatura
    var showFullScreenImageDialog by remember { mutableStateOf(false) } // Controla la visibilidad del diálogo de pantalla completa
    var imageToShowInDialog by remember { mutableStateOf<Uri?>(null) } // URI de la imagen a mostrar en pantalla completa
    var fullScreenImageBitmap by remember { mutableStateOf<ImageBitmap?>(null) } // Bitmap para la imagen en pantalla completa

    // --- Funciones de Lógica ---

    // Lambda para obtener la última ubicación conocida
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

    // --- Efectos (LaunchedEffect) para manejar la lógica asíncrona y de ciclo de vida ---

    // Se ejecuta cuando cambia el permiso de la cámara o cualquiera de las otras claves.
    LaunchedEffect(hasCameraPermission, cameraProviderFuture, lifecycleOwner, cameraSelector, previewView) {
        if (hasCameraPermission) {
            try {
                val cameraProvider = cameraProviderFuture.await()
                cameraProvider.unbindAll() // Desvincula casos de uso anteriores
                val cameraXPreview = CameraXPreview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                imageCapture = ImageCapture.Builder().setTargetRotation(previewView.display.rotation).build()
                camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, cameraXPreview, imageCapture)
                camera?.cameraInfo?.zoomState?.value?.linearZoom?.let { linearZoom = it }
            } catch (exc: Exception) { Log.e("CameraScreen", "Fallo al vincular casos de uso: ${exc.message}", exc) }
        } // Si no hay permiso, no hace nada, la UI mostrará el mensaje correspondiente.
    }

    suspend fun loadRotatedImage(uri: Uri): ImageBitmap? {
        return withContext(Dispatchers.IO) {
            try {
                // Obtiene la ruta real del archivo desde la URI
                val imagePath = uri.path ?: return@withContext null

                // Decodifica el bitmap desde la ruta del archivo
                val originalBitmap = BitmapFactory.decodeFile(imagePath) ?: return@withContext null
                
                // Obtiene los datos EXIF desde la ruta del archivo (más fiable)
                val exifInterface = ExifInterface(imagePath)
                val orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)

                val matrix = Matrix()
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                }

                val rotatedBitmap = Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true)
                rotatedBitmap.asImageBitmap()
            } catch (e: Exception) {
                Log.e("ImageLoader", "Error al cargar la imagen rotada: ${e.message}", e)
                null
            }
        }
    }

    // Se ejecuta cuando `lastPhotoUri` cambia. Carga la nueva foto como un bitmap para la miniatura.
    LaunchedEffect(lastPhotoUri) {
        if (lastPhotoUri != null) {
            thumbnailImageBitmap = loadRotatedImage(lastPhotoUri!!)
        } else {
            thumbnailImageBitmap = null
        }
    }
    
    // Se ejecuta cuando `imageToShowInDialog` cambia. Carga la imagen para verla en pantalla completa.
    LaunchedEffect(imageToShowInDialog) {
        if (imageToShowInDialog != null) {
            fullScreenImageBitmap = loadRotatedImage(imageToShowInDialog!!)
            if(fullScreenImageBitmap == null) {
                showFullScreenImageDialog = false // Si falla la carga, no mostrar el diálogo
            }
        } else {
            fullScreenImageBitmap = null
        }
    }

    // Observa y actualiza el estado del zoom
    LaunchedEffect(zoomStateValue?.linearZoom) { zoomStateValue?.linearZoom?.let { if (it != linearZoom) linearZoom = it } }
    
    // Controla la duración del efecto de flash visual
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

    // Se ejecuta una vez al iniciar el Composable para verificar y solicitar permisos.
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(localContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            hasCameraPermission = true // Si el permiso ya estaba dado, actualiza el estado
        }

        if (ContextCompat.checkSelfPermission(localContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else {
            hasLocationPermission = true
            getCurrentLocation() // Si el permiso ya estaba dado, obtiene la ubicación
        }
    }
    
    // --- Funciones de Acción ---

    fun toggleCamera() { cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA; linearZoom = 0f }
    
    fun takePhoto(cameraExecutor: Executor) {
        val imageCaptureInstance = imageCapture ?: return
        showFlashEffect = true // Activa el flash visual
        getCurrentLocation() // Actualiza la ubicación justo antes de tomar la foto
        val photoFile = File(localContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "${SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis())}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCaptureInstance.takePicture(outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onError(exc: ImageCaptureException) { Log.e("CameraScreen", "Fallo al capturar foto: ${exc.message}", exc) }
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val savedUri = output.savedUri
                Log.d("CameraScreen", "Foto capturada con éxito: $savedUri")
                lastPhotoUri = savedUri
                lifecycleOwner.lifecycleScope.launch {
                    // Llama a la función para estampar la imagen después de guardarla
                    val stamped = stampImageWithDetails(photoFile, currentCustomText, latitude, longitude, localContext)
                    if (stamped && savedUri != null) {
                        // Refresca la URI para que Compose detecte el cambio en el archivo y actualice la miniatura
                        lastPhotoUri = null 
                        lastPhotoUri = Uri.fromFile(photoFile)
                    }
                }
            }
        })
    }

    // --- Composición de la Interfaz de Usuario (UI) ---

    // Diálogo para introducir el texto personalizado
    if (showCustomTextDialog) {
        AlertDialog(
            onDismissRequest = { showCustomTextDialog = false },
            title = { Text("Introduce texto personalizado") },
            text = { TextField(value = textFieldValue, onValueChange = { textFieldValue = it }, label = { Text("Texto a estampar") }) },
            confirmButton = { Button(onClick = { currentCustomText = textFieldValue; showCustomTextDialog = false }) { Text("Guardar") } },
            dismissButton = { Button(onClick = { showCustomTextDialog = false }) { Text("Cancelar") } }
        )
    }

    // Diálogo para mostrar la imagen en pantalla completa
    if (showFullScreenImageDialog && fullScreenImageBitmap != null) {
        Dialog(onDismissRequest = { showFullScreenImageDialog = false; imageToShowInDialog = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(modifier = Modifier.fillMaxSize().background(ComposeColor.Black.copy(alpha = 0.8f)).clickable { showFullScreenImageDialog = false; imageToShowInDialog = null }, contentAlignment = Alignment.Center) {
                Image(bitmap = fullScreenImageBitmap!!, contentDescription = "Imagen a pantalla completa", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
    }

    // Layout principal de la pantalla
    Column(modifier = modifier.fillMaxSize().background(ComposeColor.Black)) {
        // Barra superior (con botón para editar texto)
        Box(modifier = Modifier.fillMaxWidth().height(100.dp).background(ComposeColor.Black.copy(alpha = 0.5f))) { 
            IconButton(onClick = { textFieldValue = currentCustomText; showCustomTextDialog = true }, modifier = Modifier.align(Alignment.CenterStart).padding(16.dp)) {
                Icon(imageVector = Icons.Filled.Edit, contentDescription = "Texto Personalizado", tint = ComposeColor.White)
            }
        }
        
        // Área de la previsualización de la cámara
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) { 
            if (hasCameraPermission) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            }
            else {
                // Muestra mensaje si no hay permiso de cámara
                Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text("Se requiere permiso de cámara para usar esta función.", color = ComposeColor.White)
                }
            }
            // Muestra el efecto de flash sobre la previsualización
            if (showFlashEffect) {
                Box(modifier = Modifier.fillMaxSize().background(ComposeColor.White)) 
            }
        }
        
        // Barra inferior (con controles de la cámara)
        Box(modifier = Modifier.fillMaxWidth().height(150.dp).background(ComposeColor.Black.copy(alpha = 0.5f))) { 
            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                // Control deslizante para el zoom
                Box(modifier = Modifier.fillMaxHeight().width(60.dp), contentAlignment = Alignment.Center) { 
                    Slider(value = linearZoom, onValueChange = { linearZoom = it; camera?.cameraControl?.setLinearZoom(it) }, valueRange = 0f..1f, modifier = Modifier.graphicsLayer(rotationZ = -90f).width(120.dp).height(50.dp))
                }
                // Botón para tomar la foto
                IconButton(onClick = { 
                    if (hasCameraPermission && hasLocationPermission) { 
                        takePhoto(ContextCompat.getMainExecutor(localContext)) 
                    } else { 
                        // Si faltan permisos, los solicita
                        if(!hasCameraPermission) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        if(!hasLocationPermission) locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    } 
                }, modifier = Modifier.size(72.dp)) { 
                    Icon(imageVector = Icons.Filled.PhotoCamera, contentDescription = "Tomar Foto", tint = ComposeColor.White, modifier = Modifier.size(64.dp))
                }
                // Controles secundarios (miniatura y cambio de cámara)
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
